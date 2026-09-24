package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.enums.StatutMouvement;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Commande;
import com.diafarms.ml.models.Commande.StatutCommande;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ClientRepo;
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
    private final PaiementClientService paiementClientService;
    private final CompteClientService compteClientService;
    private final LogsServices logs;
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

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
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
        CommandeDTO dto = CommandeDTO.fromEntity(c);
        dto.setStatutLibelle(statutLibelle(c.getStatut()));
        dto.setResteALivrer(nz(c.getQuantite()) - nz(c.getQuantiteLivree()));

        List<CommandeDTO.LivraisonDTO> livraisons = new ArrayList<>();
        double montantLivre = 0.0;
        double payeSurCommande = 0.0;
        if (c.getType() == TypeStockMagasin.OEUFS) {
            for (VenteOeufs v : venteOeufsRepo.findActivesByCommandeId(c.getId())) {
                double montant = nz(v.getMontant());
                double paye = compteClientService.payeVente(CibleImputation.VENTE_OEUFS, v.getUniqueId());
                montantLivre += montant;
                payeSurCommande += paye;
                livraisons.add(CommandeDTO.LivraisonDTO.builder()
                        .venteUniqueId(v.getUniqueId()).date(v.getDate()).quantite(v.getQuantiteOeufs())
                        .montant(montant).paye(CalculImputation.arrondi(paye))
                        .statutPaiement(statutPaiementLigne(montant, paye))
                        .build());
            }
        } else {
            for (VenteReforme v : venteReformeRepo.findActivesByCommandeId(c.getId())) {
                double montant = nz(v.getMontant());
                double paye = compteClientService.payeVente(CibleImputation.VENTE_REFORME, v.getUniqueId());
                montantLivre += montant;
                payeSurCommande += paye;
                livraisons.add(CommandeDTO.LivraisonDTO.builder()
                        .venteUniqueId(v.getUniqueId()).date(v.getDate()).quantite(v.getNombreSujets())
                        .montant(montant).paye(CalculImputation.arrondi(paye))
                        .statutPaiement(statutPaiementLigne(montant, paye))
                        .build());
            }
        }

        double acompteRecu = paiementClientRepo.findByCommandeId(c.getId()).stream()
                .filter(p -> p.getStatut() == StatutMouvement.ACTIF && p.getOrigine() == OriginePaiement.ACOMPTE)
                .mapToDouble(p -> nz(p.getMontant())).sum();

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
        if (data.getMontantEstime() == null || data.getMontantEstime() <= 0) {
            throw new IllegalArgumentException("Le montant estimé doit être positif.");
        }
        if (data.getMagasinUniqueId() == null || data.getMagasinUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le magasin de destination est obligatoire.");
        }

        Client client = clientRepo.findByUniqueId(data.getClientUniqueId());
        if (client == null) {
            throw new IllegalArgumentException("Client introuvable : " + data.getClientUniqueId());
        }
        Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
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
        c.setDateCommande(data.getDateCommande() != null && !data.getDateCommande().isBlank()
                ? LocalDate.parse(data.getDateCommande()) : LocalDate.now());
        c.setDateLivraisonPrevue(data.getDateLivraisonPrevue() != null && !data.getDateLivraisonPrevue().isBlank()
                ? LocalDate.parse(data.getDateLivraisonPrevue()) : null);
        c.setStatut(StatutCommande.EN_ATTENTE);
        c.setCreePar(currentUser);
        c.setFarm(currentUser.getFarm());
        c.setInitialisation(Initialisation.init());

        Commande saved = commandeRepo.save(c);
        logs.addLogs(currentUser.getId(), saved.getId(), "Commande",
                "Nouvelle commande de " + saved.getQuantite() + " (" + type + ") pour " + client.getNom());

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

        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) {
            throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        }
        if (c.getStatut() != StatutCommande.EN_ATTENTE) {
            throw new IllegalArgumentException("Seule une commande en attente peut être modifiée.");
        }

        // Une commande déjà (partiellement) livrée a une vente réelle basée sur ces
        // chiffres (voir livrer()) : les changer ensuite désynchroniserait ce qui a été
        // livré de ce qui reste à livrer.
        if (nz(c.getQuantiteLivree()) > 0 && (data.getQuantite() != null || data.getMontantEstime() != null || data.getPrixUnitaireEstime() != null)) {
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
            c.setDateLivraisonPrevue(data.getDateLivraisonPrevue().isBlank() ? null : LocalDate.parse(data.getDateLivraisonPrevue()));
        }
        if (data.getMagasinUniqueId() != null && !data.getMagasinUniqueId().isBlank()) {
            Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
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
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
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
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
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
        // Un éventuel trop-perçu reste en avance du client (visible sur sa fiche).
        if (u != null) logs.addLogs(u.getId(), saved.getId(), "Commande", "Commande clôturée — motif : " + motif);
        return enrichir(saved);
    }

    @Override
    @Transactional
    public CommandeDTO annuler(String uniqueId, String motifBrut, boolean rembourserAcompte, String modeBrut) {
        Utilisateurs u = getCurrentUserSafe();
        ensureCanDecider(u);
        String motif = MotifSuppressionRequest.exiger(motifBrut);
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
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
                        "Annulation de la commande — " + motif, c);
            }
        }
        if (u != null) logs.addLogs(u.getId(), c.getId(), "Commande", "Commande annulée — motif : " + motif);
        return enrichir(c);
    }

    @Override
    @Transactional
    public CommandeDTO convertirEnVente(String uniqueId) {
        // Livre tout ce qu'il reste, en un coup, sans nouvel argent compté à cet
        // instant — l'ancien comportement à un seul coup, gardé pour compatibilité
        // (bouton "Convertir en vente" historique).
        return livrer(uniqueId, null, 0.0, null);
    }

    @Override
    @Transactional
    public CommandeDTO livrer(String uniqueId, Integer quantiteDemandee, Double montantRecu, String modeBrut) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
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
        double prixUnitaire = c.getPrixUnitaireEstime() != null ? c.getPrixUnitaireEstime()
                : c.getMontantEstime() / c.getQuantite();
        double montantLivraison = prixUnitaire * quantite;

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
            data.setDate(LocalDate.now().toString());
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
            data.setMontantRapporte(null);
            data.setModePaiement(null);
            data.setDate(LocalDate.now().toString());
            VenteReformeDTO vente = venteReformeService.create(data);
            venteUniqueId = vente.getUniqueId();
            typeCible = CibleImputation.VENTE_REFORME;
            VenteReforme entity = venteReformeRepo.findByUniqueId(venteUniqueId)
                    .orElseThrow(() -> new IllegalArgumentException("Vente introuvable après création : " + venteUniqueId));
            entity.setCommande(c);
            venteReformeRepo.save(entity);
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

        // L'argent NOUVEAU reçu à cette livraison précise devient un paiement LIVRAISON ;
        // sinon (rien de neuf) on laisse une éventuelle avance déjà au compte du client
        // (acompte, paiement complémentaire) régler cette livraison maintenant qu'elle
        // est rattachée à la commande — priorité voir CalculImputation.ordrePour.
        if (nz(montantRecu) > 0) {
            paiementClientService.enregistrerInterne(c.getClient(), montantRecu, mode(modeBrut), OriginePaiement.LIVRAISON,
                    c, typeCible, venteUniqueId, null, null, LocalDate.now());
        } else {
            compteClientService.imputer(c.getClient());
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Commande",
                    "Livraison de " + quantite + " (" + c.getType() + ") pour " + c.getClient().getNom()
                            + (complete ? " — commande entièrement livrée" : " — reste " + (c.getQuantite() - quantiteLivreeApres)));
        }
        return enrichir(saved);
    }

    @Override
    @Transactional
    public CommandeDTO enregistrerPaiement(String uniqueId, PaiementClientCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        if (c.getStatut() == StatutCommande.CLOTUREE || c.getStatut() == StatutCommande.ANNULEE) {
            throw new IllegalArgumentException("Cette commande est terminée, elle ne peut plus recevoir de paiement.");
        }
        if (data == null || data.getMontant() == null || data.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant payé doit être positif.");
        }
        // Rien encore livré : c'est un acompte (avant même la première vente) ; une fois
        // la livraison entamée, tout nouveau paiement est un règlement ordinaire.
        OriginePaiement origine = nz(c.getQuantiteLivree()) == 0 ? OriginePaiement.ACOMPTE : OriginePaiement.REGLEMENT;
        LocalDate date = data.getDate() == null || data.getDate().isBlank() ? LocalDate.now() : LocalDate.parse(data.getDate());
        paiementClientService.enregistrerInterne(c.getClient(), data.getMontant(), mode(data.getMode()), origine,
                c, null, null, null, data.getObservations(), date);
        return enrichir(c);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanDelete(currentUser);
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        if (!c.getInitialisation().getRemoved() && c.getStatut() != StatutCommande.EN_ATTENTE) {
            throw new IllegalArgumentException("Seule une commande en attente peut être supprimée — annulez-la plutôt.");
        }
        c.getInitialisation().setRemoved(!c.getInitialisation().getRemoved());
        commandeRepo.save(c);
        boolean removed = c.getInitialisation().getRemoved();
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
        String clientParam = (clientUniqueId == null || clientUniqueId.isBlank()) ? null : clientUniqueId;

        Page<Commande> commandePage = commandeRepo.search(currentUser.getFarm().getId(), statutEnum, clientParam, pageable);
        List<CommandeDTO> dtoList = commandePage.getContent().stream().map(this::enrichir).toList();

        return new PaginatedResponse<>(
                dtoList,
                commandePage.getNumber() + 1,
                commandePage.getTotalPages(),
                commandePage.getTotalElements(),
                commandePage.getSize()
        );
    }
}
