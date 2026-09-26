package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.CommandeDTO;
import com.diafarms.ml.DTO.VenteOeufsDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.commons.DateSaisie;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.enums.StatutMouvement;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.enums.TypeVenteReforme;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Commande;
import com.diafarms.ml.models.Commande.StatutCommande;
import com.diafarms.ml.models.ImputationPaiement;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.PaiementClient;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.ImputationPaiementRepo;
import com.diafarms.ml.repository.CommandeRepo;
import com.diafarms.ml.repository.MagasinRepo;
import com.diafarms.ml.repository.PaiementClientRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.CommandeCreate;
import com.diafarms.ml.request.create.PaiementClientCreate;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.services.CommandeService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.VenteOeufsService;
import com.diafarms.ml.services.VenteReformeService;

import lombok.RequiredArgsConstructor;

// Commande = ce qu'un client demande AVANT que la vente ne soit finalisée — voir
// Commande.java. Cycle de vie : EN_ATTENTE -> CONFIRMEE -> (EN_LIVRAISON) -> CONVERTIE
// (livrée intégralement, voir livrer()) ; ou CLOTUREE (arrêtée après une livraison
// partielle, voir cloturer()) ; ou ANNULEE (rien livré, voir annuler()) à tout moment
// avant. Permissions alignées sur ClientServiceImpl : création/actions courantes
// ouvertes à ADMIN/RESPONSABLE/VENTE (un vendeur prend des commandes sur le terrain),
// clôture/annulation/suppression réservées à ADMIN/SUPER_ADMIN/RESPONSABLE.
@Service
@RequiredArgsConstructor
public class CommandeServiceImpl implements CommandeService {

    private final CommandeRepo commandeRepo;
    private final ClientRepo clientRepo;
    private final MagasinRepo magasinRepo;
    private final VenteOeufsService venteOeufsService;
    private final VenteReformeService venteReformeService;
    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteReformeRepo venteReformeRepo;
    private final PaiementClientRepo paiementClientRepo;
    private final ImputationPaiementRepo imputationPaiementRepo;
    private final PaiementClientService paiementClientService;
    private final CompteClientService compteClientService;
    private final LogsServices logs;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasRole(Utilisateurs u, String role) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> role.equalsIgnoreCase(r.getRole()));
    }

    private boolean isAdmin(Utilisateurs u) {
        return hasRole(u, "ADMIN") || hasRole(u, "SUPER_ADMIN");
    }

    private void ensureCanManage(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "VENTE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour gérer les commandes.");
        }
    }

    private void ensureCanDelete(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE")) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut supprimer une commande.");
        }
    }

    // Décider du sort d'une commande (clôture, annulation) : mêmes rôles que
    // PaiementClientService.ensureCanAnnuler/rembourser — de l'argent ou un
    // engagement client est en jeu.
    private void ensureCanDecider(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE")) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut décider du sort de cette commande.");
        }
    }

    // Enregistrer un paiement (acompte/règlement) : mêmes rôles que
    // PaiementClientService.ensureCanEncaisser — distinct de ensureCanManage, qui gère
    // les commandes elles-mêmes (création, livraison) mais pas qui peut encaisser
    // l'argent d'un client.
    private void ensureCanEncaisser(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE") && !hasRole(u, "VENTE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour enregistrer un paiement client.");
        }
    }

    // Commande introuvable OU d'une autre ferme -> même message (même principe que
    // FactureServiceImpl.factureFarmScoped) : sans ce contrôle, un utilisateur d'une ferme
    // pouvait livrer, encaisser, annuler... la commande d'une autre ferme par son uniqueId.
    // Une commande supprimée est introuvable, sauf pour deleteOrRecover (récupération).
    private Commande commandeFarmScoped(String uniqueId, Utilisateurs u) {
        return commandeFarmScoped(uniqueId, u, false, false);
    }

    // verrou : deux actions simultanées sur la même commande (livrer, clôturer, annuler,
    // confirmer, encaisser) sont sérialisées. Ordre des verrous partout : client PUIS
    // commande (comme CompteClientService.verrouiller, puis imputer) pour ne jamais
    // s'interbloquer avec une saisie qui verrouille le client d'abord. La commande est
    // donc lue sans verrou, son client verrouillé, puis elle est relue sous verrou
    // (findByUniqueIdForUpdate + refresh : l'état est celui de la base, pas celui déjà en
    // mémoire).
    private Commande commandeFarmScoped(String uniqueId, Utilisateurs u, boolean inclureSupprimees, boolean verrou) {
        Commande c = commandeFarmScoped(uniqueId, u, inclureSupprimees);
        if (!verrou) return c;
        compteClientService.verrouiller(c.getClient());
        Commande v = commandeRepo.findByUniqueIdForUpdate(uniqueId);
        entityManager.refresh(v);
        return commandeFarmScoped(uniqueId, u, inclureSupprimees);
    }

    private Commande commandeFarmScoped(String uniqueId, Utilisateurs u, boolean inclureSupprimees) {
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null || u == null || u.getFarm() == null
                || c.getFarm() == null || !c.getFarm().getId().equals(u.getFarm().getId())
                || (!inclureSupprimees && c.getInitialisation() != null && Boolean.TRUE.equals(c.getInitialisation().getRemoved()))) {
            throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        }
        return c;
    }

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static double arrondi3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static TypeVenteReforme parseTarification(String raw) {
        if (raw == null || raw.isBlank()) return TypeVenteReforme.TETE;
        try {
            return TypeVenteReforme.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tarification invalide (attendu TETE ou KILO) : " + raw);
        }
    }

    // Applique tarification/prixKgEstime/poidsEstimeKg à la commande (création ou
    // modification) puis recalcule montantEstime = poids x prix/kg quand les deux sont
    // connus (sinon montantEstime, déjà posé par l'appelant, reste celui du client).
    private void appliquerTarification(Commande c, CommandeCreate data, boolean creation) {
        TypeVenteReforme tarif = creation || data.getTarification() != null
                ? parseTarification(data.getTarification())
                : (c.getTarification() != null ? c.getTarification() : TypeVenteReforme.TETE);
        if (tarif == TypeVenteReforme.KILO && c.getType() != TypeStockMagasin.REFORME) {
            throw new IllegalArgumentException("La tarification au kilo ne concerne que les commandes de réformes.");
        }
        c.setTarification(tarif);
        if (tarif == TypeVenteReforme.TETE) {
            if (data.getPrixKgEstime() != null || data.getPoidsEstimeKg() != null) {
                throw new IllegalArgumentException("Le prix au kilo et le poids estimé ne concernent que les commandes au kilo.");
            }
            c.setPrixKgEstime(null);
            c.setPoidsEstimeKg(null);
            return;
        }
        if (data.getPrixKgEstime() != null) c.setPrixKgEstime(data.getPrixKgEstime());
        if (data.getPoidsEstimeKg() != null) c.setPoidsEstimeKg(data.getPoidsEstimeKg() > 0 ? data.getPoidsEstimeKg() : null);
        if (c.getPrixKgEstime() == null || c.getPrixKgEstime() <= 0) {
            throw new IllegalArgumentException("Le prix au kilo est obligatoire pour une commande au kilo.");
        }
        if (c.getPoidsEstimeKg() != null) {
            c.setMontantEstime(CalculImputation.arrondi(c.getPoidsEstimeKg() * c.getPrixKgEstime()));
        } else if (c.getMontantEstime() == null || c.getMontantEstime() <= 0) {
            throw new IllegalArgumentException("Indiquez le poids estimé (kg) ou le montant estimé de la commande.");
        }
    }

    private static ModePaiement mode(String raw) {
        if (raw == null || raw.isBlank()) return ModePaiement.ESPECES;
        try {
            return ModePaiement.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mode de paiement inconnu : " + raw);
        }
    }

    private static String statutLibelle(StatutCommande s) {
        if (s == null) return null;
        return switch (s) {
            case EN_ATTENTE -> "En attente";
            case CONFIRMEE -> "Confirmée";
            case EN_LIVRAISON -> "En cours de livraison";
            case CONVERTIE -> "Livrée";
            case CLOTUREE -> "Clôturée";
            case ANNULEE -> "Annulée";
        };
    }

    private static String statutPaiementLigne(double montant, double paye) {
        double reste = CalculImputation.arrondi(montant - paye);
        return reste <= 0 ? "PAYEE" : (paye > 0 ? "PARTIELLE" : "NON_PAYEE");
    }

    // Construit le CommandeDTO enrichi : chiffres et historique de livraisons
    // recalculés à partir des ventes/paiements réels de la commande — rien n'est
    // stocké, comme CompteClientService.compte pour un client. Appelé par toutes les
    // méthodes qui renvoient un CommandeDTO (y compris list(), acceptable à l'échelle
    // d'une ferme).
    private CommandeDTO enrichir(Commande c) {
        return enrichirTous(List.of(c)).get(0);
    }

    // Même calcul pour toute une page de commandes, en requêtes groupées (livraisons,
    // payé par livraison, paiements, imputations) : plus une requête par vente ou par
    // paiement.
    private List<CommandeDTO> enrichirTous(List<Commande> commandes) {
        if (commandes.isEmpty()) return List.of();
        List<Long> ids = commandes.stream().map(Commande::getId).toList();
        Map<Long, List<VenteOeufs>> oeufs = new HashMap<>();
        for (VenteOeufs v : venteOeufsRepo.findActivesByCommandeIds(ids))
            oeufs.computeIfAbsent(v.getCommande().getId(), k -> new ArrayList<>()).add(v);
        Map<Long, List<VenteReforme>> reformes = new HashMap<>();
        for (VenteReforme v : venteReformeRepo.findActivesByCommandeIds(ids))
            reformes.computeIfAbsent(v.getCommande().getId(), k -> new ArrayList<>()).add(v);
        Set<String> venteUids = new HashSet<>();
        oeufs.values().forEach(l -> l.forEach(v -> venteUids.add(v.getUniqueId())));
        reformes.values().forEach(l -> l.forEach(v -> venteUids.add(v.getUniqueId())));
        Map<String, Double> payeParVente = new HashMap<>();
        if (!venteUids.isEmpty()) {
            for (Object[] r : imputationPaiementRepo.sumActivesParVente(venteUids))
                payeParVente.put((String) r[0], ((Number) r[1]).doubleValue());
        }
        Map<Long, List<PaiementClient>> paiements = new HashMap<>();
        List<Long> paiementIds = new ArrayList<>();
        for (PaiementClient p : paiementClientRepo.findByCommandeIds(ids)) {
            paiements.computeIfAbsent(p.getCommande().getId(), k -> new ArrayList<>()).add(p);
            paiementIds.add(p.getId());
        }
        // [cibleType, cibleUniqueId, montant] par paiement
        Map<Long, List<Object[]>> imputations = new HashMap<>();
        if (!paiementIds.isEmpty()) {
            for (Object[] r : imputationPaiementRepo.sumActivesParPaiementEtCible(paiementIds))
                imputations.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add(new Object[]{r[1], r[2], r[3]});
        }
        List<CommandeDTO> out = new ArrayList<>();
        for (Commande c : commandes) {
            out.add(enrichir(c, oeufs.getOrDefault(c.getId(), List.of()), reformes.getOrDefault(c.getId(), List.of()),
                    payeParVente, paiements.getOrDefault(c.getId(), List.of()), imputations));
        }
        return out;
    }

    private CommandeDTO enrichir(Commande c, List<VenteOeufs> ventesOeufs, List<VenteReforme> ventesReforme,
                                 Map<String, Double> payeParVente, List<PaiementClient> paiements,
                                 Map<Long, List<Object[]>> imputations) {
        CommandeDTO dto = CommandeDTO.fromEntity(c);
        dto.setStatutLibelle(statutLibelle(c.getStatut()));
        dto.setResteALivrer(nz(c.getQuantite()) - nz(c.getQuantiteLivree()));

        List<CommandeDTO.LivraisonDTO> livraisons = new ArrayList<>();
        double montantLivre = 0.0;
        double payeSurCommande = 0.0;
        if (c.getType() == TypeStockMagasin.OEUFS) {
            for (VenteOeufs v : ventesOeufs) {
                double montant = nz(v.getMontant());
                double paye = CalculImputation.arrondi(payeParVente.getOrDefault(v.getUniqueId(), 0.0));
                montantLivre += montant;
                payeSurCommande += paye;
                livraisons.add(CommandeDTO.LivraisonDTO.builder()
                        .venteUniqueId(v.getUniqueId()).date(v.getDate()).quantite(v.getQuantiteOeufs())
                        .montant(montant).paye(paye)
                        .statutPaiement(statutPaiementLigne(montant, paye))
                        .build());
            }
        } else {
            double poidsLivre = 0.0;
            for (VenteReforme v : ventesReforme) {
                double montant = nz(v.getMontant());
                double paye = CalculImputation.arrondi(payeParVente.getOrDefault(v.getUniqueId(), 0.0));
                montantLivre += montant;
                payeSurCommande += paye;
                if (v.getTypeVente() == TypeVenteReforme.KILO) poidsLivre += nz(v.getPoidsTotalKg());
                livraisons.add(CommandeDTO.LivraisonDTO.builder()
                        .venteUniqueId(v.getUniqueId()).date(v.getDate()).quantite(v.getNombreSujets())
                        .montant(montant).paye(paye)
                        .statutPaiement(statutPaiementLigne(montant, paye))
                        .typeVente(v.getTypeVente() != null ? v.getTypeVente().name() : TypeVenteReforme.TETE.name())
                        .poidsTotalKg(v.getPoidsTotalKg())
                        .prixUnitaire(v.getPrixUnitaire())
                        .build());
            }
            if (c.getTarification() == TypeVenteReforme.KILO) dto.setPoidsLivreKg(arrondi3(poidsLivre));
        }

        // Acomptes : reçu = Σ paiements ACOMPTE actifs ; imputé = ce qu'ils ont réglé sur
        // les livraisons de CETTE commande ; réservé = ce qui en reste tant que la commande
        // est ouverte (0 une fois terminée : le reste est alors une avance libre du
        // client). avanceReservee : pareil pour TOUS les paiements rattachés à la commande
        // (acomptes, règlements, paiements à la livraison).
        Set<String> ventesCommande = new HashSet<>();
        livraisons.forEach(l -> ventesCommande.add(l.getVenteUniqueId()));
        boolean reservee = CompteClientService.estReservee(c);
        double acompteRecu = 0, acompteImpute = 0, acompteReserve = 0, avanceReservee = 0;
        for (PaiementClient p : paiements) {
            if (p.getStatut() != StatutMouvement.ACTIF) continue;
            boolean acompte = p.getOrigine() == OriginePaiement.ACOMPTE;
            double imputeTout = 0, imputeCommande = 0;
            for (Object[] i : imputations.getOrDefault(p.getId(), List.of())) {
                double m = ((Number) i[2]).doubleValue();
                imputeTout += m;
                if (i[0] != CibleImputation.REMBOURSEMENT && ventesCommande.contains((String) i[1])) imputeCommande += m;
            }
            double reste = reservee ? Math.max(0, nz(p.getMontant()) - imputeTout) : 0;
            avanceReservee += reste;
            if (acompte) {
                acompteRecu += nz(p.getMontant());
                acompteImpute += imputeCommande;
                acompteReserve += reste;
            }
        }
        dto.setAcompteImpute(CalculImputation.arrondi(acompteImpute));
        dto.setAcompteReserve(CalculImputation.arrondi(acompteReserve));
        dto.setAvanceReservee(CalculImputation.arrondi(avanceReservee));

        dto.setMontantLivre(CalculImputation.arrondi(montantLivre));
        dto.setAcompteRecu(CalculImputation.arrondi(acompteRecu));
        dto.setPayeSurCommande(CalculImputation.arrondi(payeSurCommande));
        dto.setResteAPayerLivre(CalculImputation.arrondi(montantLivre - payeSurCommande));
        dto.setLivraisons(livraisons);
        return dto;
    }

    @Override
    @Transactional
    public CommandeDTO create(CommandeCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (data.getClientUniqueId() == null || data.getClientUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le client est obligatoire pour une commande.");
        }
        if (data.getQuantite() == null || data.getQuantite() <= 0) {
            throw new IllegalArgumentException("La quantité commandée doit être positive.");
        }
        boolean kiloAvecPoids = parseTarification(data.getTarification()) == TypeVenteReforme.KILO
                && data.getPoidsEstimeKg() != null && data.getPoidsEstimeKg() > 0;
        if (!kiloAvecPoids && (data.getMontantEstime() == null || data.getMontantEstime() <= 0)) {
            throw new IllegalArgumentException("Le montant estimé doit être positif.");
        }
        if (data.getMagasinUniqueId() == null || data.getMagasinUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le magasin de destination est obligatoire.");
        }

        Client client = clientRepo.findByUniqueId(data.getClientUniqueId());
        if (client == null || client.getFarm() == null || !client.getFarm().getId().equals(currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Client introuvable : " + data.getClientUniqueId());
        }
        Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
                .filter(m -> m.getFarm() != null && m.getFarm().getId().equals(currentUser.getFarm().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
        if (magasin.getType() != Magasin.TypeMagasin.VENTE) {
            throw new IllegalArgumentException("Une commande ne peut viser qu'un magasin de type VENTE.");
        }
        TypeStockMagasin type;
        try {
            type = TypeStockMagasin.valueOf(data.getType().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Type de commande invalide (attendu OEUFS ou REFORME) : " + data.getType());
        }

        Commande c = new Commande();
        c.setUniqueId(java.util.UUID.randomUUID().toString());
        c.setClient(client);
        c.setMagasin(magasin);
        c.setType(type);
        c.setQuantite(data.getQuantite());
        c.setPrixUnitaireEstime(data.getPrixUnitaireEstime());
        c.setMontantEstime(data.getMontantEstime());
        c.setMontantAcompte(data.getMontantAcompte());
        appliquerTarification(c, data, true);
        c.setDateCommande(DateSaisie.parse(data.getDateCommande(), LocalDate.now()));
        c.setDateLivraisonPrevue(DateSaisie.parse(data.getDateLivraisonPrevue(), null));
        c.setStatut(StatutCommande.EN_ATTENTE);
        c.setCreePar(currentUser);
        c.setFarm(currentUser.getFarm());
        c.setInitialisation(Initialisation.init());

        Commande saved = commandeRepo.save(c);
        logs.addLogs(currentUser.getId(), saved.getId(), "Commande",
                "Nouvelle commande de " + saved.getQuantite() + " (" + type
                        + (saved.getTarification() == TypeVenteReforme.KILO ? ", au kilo" : "") + ") pour " + client.getNom());

        // L'acompte est de l'argent RÉELLEMENT encaissé dès maintenant, pas seulement un
        // nombre théorique sur la commande — enregistré comme un vrai paiement client
        // (PaiementClientService.enregistrerInterne), pas juste imputable plus tard.
        if (nz(saved.getMontantAcompte()) > 0) {
            paiementClientService.enregistrerInterne(client, saved.getMontantAcompte(), mode(data.getModePaiement()),
                    OriginePaiement.ACOMPTE, saved, null, null, null, "Acompte sur commande", saved.getDateCommande());
        }

        return enrichir(saved);
    }

    @Override
    @Transactional
    public CommandeDTO update(String uniqueId, CommandeCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        Commande c = commandeFarmScoped(uniqueId, currentUser);
        if (c.getStatut() != StatutCommande.EN_ATTENTE) {
            throw new IllegalArgumentException("Seule une commande en attente peut être modifiée.");
        }

        // Une commande déjà (partiellement) livrée a une vente réelle basée sur ces
        // chiffres (voir livrer()) : les changer ensuite désynchroniserait ce qui a été
        // livré de ce qui reste à livrer.
        if (nz(c.getQuantiteLivree()) > 0 && (data.getQuantite() != null || data.getMontantEstime() != null || data.getPrixUnitaireEstime() != null
                || (data.getTarification() != null && parseTarification(data.getTarification()) != c.getTarification())
                || data.getPrixKgEstime() != null || data.getPoidsEstimeKg() != null)) {
            throw new IllegalArgumentException("Cette commande a déjà commencé à être livrée : la quantité et le montant ne peuvent plus être modifiés.");
        }
        if (data.getQuantite() != null) {
            if (data.getQuantite() <= 0) throw new IllegalArgumentException("La quantité commandée doit être positive.");
            c.setQuantite(data.getQuantite());
        }
        if (data.getPrixUnitaireEstime() != null) c.setPrixUnitaireEstime(data.getPrixUnitaireEstime());
        if (data.getMontantEstime() != null) {
            if (data.getMontantEstime() <= 0) throw new IllegalArgumentException("Le montant estimé doit être positif.");
            c.setMontantEstime(data.getMontantEstime());
        }
        // Commande au kilo (ou qui le devient) : toujours repasser par appliquerTarification,
        // pour qu'un montantEstime envoyé seul ne contredise pas poids estimé x prix/kg.
        if (data.getTarification() != null || data.getPrixKgEstime() != null || data.getPoidsEstimeKg() != null
                || c.getTarification() == TypeVenteReforme.KILO) {
            appliquerTarification(c, data, false);
        }
        // Un acompte supplémentaire est désormais un paiement à part entière (voir
        // enregistrerPaiement/PaiementClientService.enregistrerInterne) — plus un simple
        // delta discret sur ce champ, qui n'était ni tracé ni visible dans l'historique
        // des paiements. montantAcompte reste donc figé après la création : c'est
        // l'historique du tout premier acompte, rien d'autre.
        if (data.getMontantAcompte() != null
                && CalculImputation.arrondi(data.getMontantAcompte()) != CalculImputation.arrondi(nz(c.getMontantAcompte()))) {
            throw new IllegalArgumentException(
                    "Un acompte supplémentaire s'enregistre comme un paiement sur la commande.");
        }

        if (data.getDateLivraisonPrevue() != null) {
            c.setDateLivraisonPrevue(DateSaisie.parse(data.getDateLivraisonPrevue(), null));
        }
        if (data.getMagasinUniqueId() != null && !data.getMagasinUniqueId().isBlank()) {
            Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
                    .filter(m -> m.getFarm() != null && m.getFarm().getId().equals(currentUser.getFarm().getId()))
                    .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
            if (magasin.getType() != Magasin.TypeMagasin.VENTE) {
                throw new IllegalArgumentException("Une commande ne peut viser qu'un magasin de type VENTE.");
            }
            c.setMagasin(magasin);
        }
        if (c.getInitialisation() != null) c.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());

        Commande saved = commandeRepo.save(c);
        return enrichir(saved);
    }

    @Override
    @Transactional
    public CommandeDTO confirmer(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Commande c = commandeFarmScoped(uniqueId, currentUser, false, true);
        if (c.getStatut() != StatutCommande.EN_ATTENTE) {
            throw new IllegalArgumentException("Seule une commande en attente peut être confirmée.");
        }
        c.setStatut(StatutCommande.CONFIRMEE);
        return enrichir(commandeRepo.save(c));
    }

    @Override
    @Transactional
    public CommandeDTO cloturer(String uniqueId, String motifBrut) {
        Utilisateurs u = getCurrentUserSafe();
        ensureCanDecider(u);
        String motif = MotifSuppressionRequest.exiger(motifBrut);
        Commande c = commandeFarmScoped(uniqueId, u, false, true);
        if (nz(c.getQuantiteLivree()) == 0) {
            throw new IllegalArgumentException("Rien n'a été livré : annulez la commande au lieu de la clôturer.");
        }
        if (c.getStatut() == StatutCommande.CONVERTIE || c.getStatut() == StatutCommande.CLOTUREE
                || c.getStatut() == StatutCommande.ANNULEE) {
            throw new IllegalArgumentException("Cette commande est déjà terminée.");
        }
        c.setStatut(StatutCommande.CLOTUREE);
        c.setMotifFin(motif);
        Commande saved = commandeRepo.save(c);
        // Le reste des acomptes n'est plus réservé : il devient une avance libre du client,
        // qui règle aussitôt ses autres ventes dues (voir CompteClientService.estReservee).
        compteClientService.imputer(c.getClient());
        if (u != null) logs.addLogs(u.getId(), saved.getId(), "Commande", "Commande clôturée, motif : " + motif);
        return enrichir(saved);
    }

    @Override
    @Transactional
    public CommandeDTO annuler(String uniqueId, String motifBrut, boolean rembourserAcompte, String modeBrut) {
        Utilisateurs u = getCurrentUserSafe();
        ensureCanDecider(u);
        String motif = MotifSuppressionRequest.exiger(motifBrut);
        Commande c = commandeFarmScoped(uniqueId, u, false, true);
        if (nz(c.getQuantiteLivree()) > 0) {
            throw new IllegalArgumentException("Cette commande a déjà été livrée en partie : clôturez-la au lieu de l'annuler.");
        }
        if (c.getStatut() == StatutCommande.ANNULEE) {
            throw new IllegalArgumentException("Cette commande est déjà annulée.");
        }
        c.setStatut(StatutCommande.ANNULEE);
        c.setMotifFin(motif);
        commandeRepo.save(c);
        if (rembourserAcompte) {
            double disponible = compteClientService.sourcesDisponibles(c.getClient()).stream()
                    .filter(s -> c.getUniqueId().equals(s.commandeUniqueId()))
                    .mapToDouble(CalculImputation.Source::reste).sum();
            if (disponible > 0) {
                paiementClientService.rembourserInterne(c.getClient(), disponible, mode(modeBrut),
                        "Annulation de la commande : " + motif, c);
            }
        }
        // Ce qui n'a pas été rendu n'est plus réservé à la commande : avance libre, qui
        // règle aussitôt les autres ventes dues du client (APRÈS le remboursement éventuel).
        compteClientService.imputer(c.getClient());
        if (u != null) logs.addLogs(u.getId(), c.getId(), "Commande", "Commande annulée, motif : " + motif);
        return enrichir(c);
    }

    @Override
    @Transactional
    public CommandeDTO convertirEnVente(String uniqueId) {
        // Livre tout ce qu'il reste, en un coup, sans nouvel argent compté à cet
        // instant — l'ancien comportement à un seul coup, gardé pour compatibilité
        // (bouton "Convertir en vente" historique).
        return livrer(uniqueId, null, 0.0, null, null, null, null, null);
    }

    @Override
    @Transactional
    public CommandeDTO livrer(String uniqueId, Integer quantiteDemandee, Double montantRecu, String modeBrut,
                              Double poidsTotalKg, Double prixKg, String dateBrute, String heureBrute) {
        LocalDate dateLivraison = DateSaisie.parse(dateBrute, LocalDate.now());
        if (dateLivraison.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("La date de livraison ne peut pas être dans le futur.");
        }
        String heureLivraison = null;
        if (heureBrute != null && !heureBrute.isBlank()) {
            try {
                heureLivraison = java.time.LocalTime.parse(heureBrute.trim()).toString();
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("Heure invalide : " + heureBrute + " (format attendu HH:mm).");
            }
        }
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Commande c = commandeFarmScoped(uniqueId, currentUser, false, true);
        if (c.getStatut() == StatutCommande.CLOTUREE) {
            throw new IllegalArgumentException("Cette commande est clôturée, elle ne peut plus être livrée.");
        }
        if (c.getStatut() == StatutCommande.ANNULEE) {
            throw new IllegalArgumentException("Une commande annulée ne peut pas être livrée.");
        }
        if (c.getStatut() == StatutCommande.CONVERTIE) {
            throw new IllegalArgumentException("Cette commande a déjà été entièrement livrée.");
        }

        int quantiteRestante = c.getQuantite() - nz(c.getQuantiteLivree());
        int quantite = quantiteDemandee != null ? quantiteDemandee : quantiteRestante;
        if (quantite <= 0) {
            throw new IllegalArgumentException("La quantité à livrer doit être positive.");
        }
        if (quantite > quantiteRestante) {
            throw new IllegalArgumentException(
                "Quantité supérieure à ce qu'il reste à livrer sur cette commande (" + quantiteRestante + " restant(s))."
            );
        }

        // Prix au même prorata que le prix unitaire estimé de la commande (ou déduit du
        // montant total si aucun prix unitaire n'a été renseigné) : une livraison
        // partielle de la moitié de la commande vaut la moitié de son montant estimé.
        //
        // Commande au KILO : la quantité reste en sujets (stock, reste à livrer), mais le
        // montant se calcule ICI sur le poids réellement pesé à la livraison :
        // montant = poids x prix/kg (prix de la commande, ou prix/kg surchargé à la
        // livraison). La vente générée est une VenteReforme typeVente=KILO.
        boolean auKilo = c.getTarification() == TypeVenteReforme.KILO;
        double prixUnitaire;
        double montantLivraison;
        if (auKilo) {
            if (poidsTotalKg == null || poidsTotalKg <= 0) {
                throw new IllegalArgumentException("Commande au kilo : indiquez le poids total pesé (kg) des sujets livrés.");
            }
            if (quantiteDemandee == null) {
                throw new IllegalArgumentException("Commande au kilo : indiquez le nombre de sujets livrés.");
            }
            Double prix = prixKg != null ? prixKg : c.getPrixKgEstime();
            if (prix == null || prix <= 0) {
                throw new IllegalArgumentException("Commande au kilo : le prix au kilo doit être positif.");
            }
            prixUnitaire = prix;
            montantLivraison = CalculImputation.arrondi(poidsTotalKg * prix);
        } else {
            if (poidsTotalKg != null || prixKg != null) {
                throw new IllegalArgumentException("Cette commande est tarifée par sujet : le poids et le prix au kilo ne s'appliquent pas.");
            }
            prixUnitaire = c.getPrixUnitaireEstime() != null ? c.getPrixUnitaireEstime()
                    : c.getMontantEstime() / c.getQuantite();
            montantLivraison = prixUnitaire * quantite;
        }

        // montantRapporte/modePaiement laissés vides à la création de la vente : cette
        // livraison n'est PAS un paiement en elle-même — on crée nous-mêmes le paiement
        // LIVRAISON juste après (ou on laisse l'acompte déjà encaissé régler la
        // livraison via compteClientService.imputer), une fois la vente rattachée à la
        // commande (sinon l'imputation ne saurait pas prioriser cette commande).
        String venteUniqueId;
        CibleImputation typeCible;
        if (c.getType() == TypeStockMagasin.OEUFS) {
            VenteOeufsCreate data = new VenteOeufsCreate();
            data.setMagasinUniqueId(c.getMagasin().getUniqueId());
            data.setClientUniqueId(c.getClient().getUniqueId());
            data.setQuantiteOeufs(quantite);
            data.setPrixUnitaire(prixUnitaire);
            data.setMontant(montantLivraison);
            data.setMontantRapporte(null);
            data.setModePaiement(null);
            data.setDate(dateLivraison.toString());
            data.setHeure(heureLivraison);
            VenteOeufsDTO vente = venteOeufsService.create(data);
            venteUniqueId = vente.getUniqueId();
            typeCible = CibleImputation.VENTE_OEUFS;
            VenteOeufs entity = venteOeufsRepo.findByUniqueId(venteUniqueId)
                    .orElseThrow(() -> new IllegalArgumentException("Vente introuvable après création : " + venteUniqueId));
            entity.setCommande(c);
            venteOeufsRepo.save(entity);
        } else {
            VenteReformeCreate data = new VenteReformeCreate();
            data.setMagasinUniqueId(c.getMagasin().getUniqueId());
            data.setClientUniqueId(c.getClient().getUniqueId());
            data.setNombreSujets(quantite);
            data.setPrixUnitaire(prixUnitaire);
            data.setMontant(montantLivraison);
            data.setTypeVente(auKilo ? TypeVenteReforme.KILO.name() : TypeVenteReforme.TETE.name());
            data.setPoidsTotalKg(auKilo ? poidsTotalKg : null);
            data.setMontantRapporte(null);
            data.setModePaiement(null);
            data.setDate(dateLivraison.toString());
            data.setHeure(heureLivraison);
            VenteReformeDTO vente = venteReformeService.create(data);
            venteUniqueId = vente.getUniqueId();
            typeCible = CibleImputation.VENTE_REFORME;
            VenteReforme entity = venteReformeRepo.findByUniqueId(venteUniqueId)
                    .orElseThrow(() -> new IllegalArgumentException("Vente introuvable après création : " + venteUniqueId));
            entity.setCommande(c);
            venteReformeRepo.save(entity);
        }

        // La vente vient d'être imputée comme une vente ordinaire (sans commande) : on
        // refait l'imputation maintenant qu'elle porte la commande, commande encore
        // ouverte, pour que les acomptes réservés la règlent en premier (voir
        // CalculImputation.repartir). Le reste d'un acompte ne se libère qu'après, quand
        // le statut passe éventuellement à CONVERTIE.
        // Argent reçu à cette livraison : enregistré ICI, avant de refaire l'imputation,
        // pour qu'il règle d'abord cette livraison (il la vise ; CalculImputation.repartir
        // passe les paiements réservés qui visent une vente avant les autres).
        compteClientService.retirerImputationsProvisoires(typeCible, venteUniqueId);
        if (nz(montantRecu) > 0) {
            paiementClientService.enregistrerInterne(c.getClient(), montantRecu, mode(modeBrut), OriginePaiement.LIVRAISON,
                    c, typeCible, venteUniqueId, null, null, dateLivraison);
        } else {
            compteClientService.imputer(c.getClient());
        }

        int quantiteLivreeApres = nz(c.getQuantiteLivree()) + quantite;
        c.setQuantiteLivree(quantiteLivreeApres);
        boolean complete = quantiteLivreeApres >= c.getQuantite();
        // vente_unique_id n'est plus réécrit ici : ce champ ne peut de toute façon plus
        // pointer qu'une seule vente alors qu'une commande peut désormais en avoir
        // plusieurs (livraisons multiples) — conservé en lecture pour l'historique des
        // commandes converties avant ce changement.
        c.setStatut(complete ? StatutCommande.CONVERTIE : StatutCommande.EN_LIVRAISON);
        Commande saved = commandeRepo.save(c);

        // Commande entièrement livrée : le reste de ses acomptes devient libre et règle
        // les autres ventes dues du client (idempotent si rien ne change).
        if (complete) compteClientService.imputer(c.getClient());

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Commande",
                    "Livraison de " + quantite + " (" + c.getType() + ")"
                            + (auKilo ? ", " + poidsTotalKg + " kg à " + prixUnitaire + " FCFA/kg" : "")
                            + " pour " + c.getClient().getNom()
                            + (complete ? ", commande entièrement livrée" : ", reste " + (c.getQuantite() - quantiteLivreeApres)));
        }
        return enrichir(saved);
    }

    @Override
    @Transactional
    public CommandeDTO enregistrerPaiement(String uniqueId, PaiementClientCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanEncaisser(currentUser);
        Commande c = commandeFarmScoped(uniqueId, currentUser, false, true);
        if (c.getStatut() == StatutCommande.CLOTUREE || c.getStatut() == StatutCommande.ANNULEE) {
            throw new IllegalArgumentException("Cette commande est terminée, elle ne peut plus recevoir de paiement.");
        }
        if (data == null || data.getMontant() == null || data.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant payé doit être positif.");
        }
        // Rien encore livré : c'est un acompte (avant même la première vente) ; une fois
        // la livraison entamée, tout nouveau paiement est un règlement ordinaire.
        OriginePaiement origine = nz(c.getQuantiteLivree()) == 0 ? OriginePaiement.ACOMPTE : OriginePaiement.REGLEMENT;
        LocalDate date = DateSaisie.parse(data.getDate(), LocalDate.now());
        paiementClientService.enregistrerInterne(c.getClient(), data.getMontant(), mode(data.getMode()), origine,
                c, null, null, null, data.getObservations(), date);
        return enrichir(c);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanDelete(currentUser);
        Commande c = commandeFarmScoped(uniqueId, currentUser, true, false);
        if (!c.getInitialisation().getRemoved() && c.getStatut() != StatutCommande.EN_ATTENTE) {
            throw new IllegalArgumentException("Seule une commande en attente peut être supprimée : annulez-la plutôt.");
        }
        c.getInitialisation().setRemoved(!c.getInitialisation().getRemoved());
        commandeRepo.save(c);
        boolean removed = c.getInitialisation().getRemoved();
        // Supprimée : ses acomptes deviennent libres ; récupérée : de nouveau réservés, y
        // compris ce qui avait réglé d'autres ventes entre-temps (reReserver).
        if (removed) compteClientService.imputer(c.getClient());
        else compteClientService.reReserver(c);
        return removed ? "Commande supprimée." : "Commande récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<CommandeDTO> list(int page, int size, String statut, String clientUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dateCommande"));

        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }

        StatutCommande statutEnum = (statut == null || statut.isBlank() || "tous".equalsIgnoreCase(statut))
                ? null : StatutCommande.valueOf(statut.toUpperCase());
        // hasX + valeur factice non nulle : jamais de "(:x IS NULL OR ...)" (crash Postgres),
        // voir CommandeRepo.search.
        boolean hasStatut = statutEnum != null;
        boolean hasClient = clientUniqueId != null && !clientUniqueId.isBlank();

        Page<Commande> commandePage = commandeRepo.search(currentUser.getFarm().getId(),
                hasStatut, hasStatut ? statutEnum : StatutCommande.EN_ATTENTE,
                hasClient, hasClient ? clientUniqueId : "", pageable);
        List<CommandeDTO> dtoList = enrichirTous(commandePage.getContent());

        return new PaginatedResponse<>(
                dtoList,
                commandePage.getNumber() + 1,
                commandePage.getTotalPages(),
                commandePage.getTotalElements(),
                commandePage.getSize()
        );
    }
}
