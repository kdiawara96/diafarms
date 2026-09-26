package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.StatsReformeDTO;
import com.diafarms.ml.DTO.StockReformeDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.DTO.VenteReformeRepartitionDTO;
import com.diafarms.ml.commons.FermeScope;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.enums.TypeVenteReforme;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.models.VenteReformeRepartition;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.MagasinRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.update.VenteReformeUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteReformeService;

import lombok.RequiredArgsConstructor;

// Vente réforme (Finance) : voir VenteOeufsImpl pour le détail du mécanisme — vendue
// DEPUIS un magasin précis, alimenté par des transferts explicites depuis un ou
// plusieurs projets (voir MagasinTransfert).
@Service
@RequiredArgsConstructor
public class VenteReformeImpl implements VenteReformeService {

    private final VenteReformeRepo venteReformeRepo;
    private final VenteReformeRepartitionRepo repartitionRepo;
    private final ReformeRepo reformeRepo;
    private final ProjetsRepo projetsRepo;
    private final MagasinRepo magasinRepo;
    private final MagasinTransfertRepo magasinTransfertRepo;
    private final ClientRepo clientRepo;
    private final SoldeVendeurServiceImpl soldeVendeurService;
    private final LogsServices logs;
    private final OtherService otherService;
    private final TransactionService transactionService;
    private final PaiementClientService paiementClientService;
    private final CompteClientService compteClientService;
    private final LivraisonCommandeService livraisonCommandeService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private boolean hasRole(Utilisateurs u, String role) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> role.equalsIgnoreCase(r.getRole()));
    }

    private boolean isAdmin(Utilisateurs u) {
        return hasRole(u, "ADMIN") || hasRole(u, "SUPER_ADMIN");
    }

    // Même règle que VenteOeufsImpl : jamais le vendeur (VENTE), même pour sa propre
    // vente.
    private void ensureCanDemanderSuppression(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour demander la suppression d'une vente.");
        }
    }

    private void ensureCanModifier(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Seul un administrateur, un responsable ou un comptable peut modifier une vente.");
        }
    }

    private void ensureCanConfirmerSuppression(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE")) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut supprimer une vente.");
        }
    }

    private TypeVenteReforme parseTypeVente(String raw) {
        if (raw == null || raw.isBlank()) {
            return TypeVenteReforme.TETE;
        }
        try {
            return TypeVenteReforme.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Type de vente invalide (attendu TETE ou KILO) : " + raw);
        }
    }

    /** "" ou null -> ESPECES (comportement historique implicite) ; sinon la valeur de
     * l'enum ModePaiement — même règle que PaiementClientService.mode(String). */
    private ModePaiement modeOuEspeces(String raw) {
        if (raw == null || raw.isBlank()) return ModePaiement.ESPECES;
        try {
            return ModePaiement.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mode de paiement inconnu : " + raw);
        }
    }

    private Map<Long, Integer> disponibleParProjetDansMagasin(Magasin magasin) {
        Map<Long, Integer> disponible = new LinkedHashMap<>();
        List<Long> projetIds = magasinTransfertRepo.findDistinctProjetIdsByMagasinAndType(magasin.getId(), TypeStockMagasin.REFORME);
        for (Long projetId : projetIds) {
            int transfere = nz(magasinTransfertRepo.sumQuantiteByMagasinAndProjetAndType(magasin.getId(), projetId, TypeStockMagasin.REFORME));
            int vendu = nz(repartitionRepo.sumSujetsByProjetIdAndMagasinId(projetId, magasin.getId()));
            int restant = transfere - vendu;
            if (restant > 0) disponible.put(projetId, restant);
        }
        return disponible;
    }

    private List<VenteReformeRepartition> repartirEtCreerTransactions(VenteReforme saved, Farm farm, int nombreSujets, double montant, Utilisateurs creePar) {
        Map<Long, Integer> disponible = disponibleParProjetDansMagasin(saved.getMagasin());
        Map<Long, Projets> projetsParId = new LinkedHashMap<>();
        for (Long projetId : disponible.keySet()) {
            projetsRepo.findById(projetId).ifPresent(p -> projetsParId.put(projetId, p));
        }

        List<RepartitionUtil.Part> parts = RepartitionUtil.repartir(nombreSujets, montant, disponible);
        List<VenteReformeRepartition> lignes = new java.util.ArrayList<>();

        // Traçabilité de l'écart directement dans la ligne — voir le commentaire
        // équivalent dans VenteOeufsImpl.repartirEtCreerTransactions.
        String suffixeEcart = suffixeEcartRapporte(saved.getMontant(), saved.getMontantRapporte());

        for (RepartitionUtil.Part part : parts) {
            Projets projet = projetsParId.get(part.projetId);

            VenteReformeRepartition r = new VenteReformeRepartition();
            r.setUniqueId(java.util.UUID.randomUUID().toString());
            r.setVenteReforme(saved);
            r.setProjet(projet);
            r.setNombreSujetsAttribue(part.quantite);
            r.setMontantAttribue(part.montant);
            lignes.add(repartitionRepo.save(r));

            transactionService.createFromSource(
                    projet, farm, part.montant, "Vente réforme", saved.getDate(),
                    "Vente réforme : " + part.quantite + " sujet(s) (part de " + saved.getNombreSujets() + " vendus, magasin " + saved.getMagasin().getNom() + ")" + suffixeEcart,
                    SourceTransaction.VENTE_REFORME, r.getUniqueId(), creePar
            );
        }
        return lignes;
    }

    /** " · Rapporté : X FCFA / Y FCFA théoriques (manque/surplus Z FCFA)", vide si pas
     * encore de montant rapporté saisi ou si égal au théorique — même helper que
     * VenteOeufsImpl (dupliqué, pas de base commune entre les deux services), même
     * convention de signe que SoldeVendeurServiceImpl.ajusterSolde. */
    private String suffixeEcartRapporte(Double montantTheorique, Double montantRapporte) {
        if (montantTheorique == null || montantRapporte == null || montantRapporte.equals(montantTheorique)) {
            return "";
        }
        double ecart = montantTheorique - montantRapporte;
        return String.format(Locale.FRANCE, " · Rapporté : %.0f FCFA / %.0f FCFA théoriques (%s %.0f FCFA)",
                montantRapporte, montantTheorique, ecart > 0 ? "manque" : "surplus", Math.abs(ecart));
    }

    @Override
    @Transactional
    public VenteReformeDTO create(VenteReformeCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Farm farm = currentUser.getFarm();

        if (data.getNombreSujets() == null || data.getNombreSujets() <= 0) {
            throw new IllegalArgumentException("Le nombre de sujets vendus doit être positif.");
        }
        if (data.getMontant() == null || data.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant de la vente doit être positif.");
        }
        // Requis côté serveur en dernier ressort — voir VenteOeufsImpl.create (même
        // raisonnement).
        if (data.getPrixUnitaire() == null || data.getPrixUnitaire() <= 0) {
            throw new IllegalArgumentException("Le prix unitaire est obligatoire.");
        }
        TypeVenteReforme typeVente = parseTypeVente(data.getTypeVente());
        if (typeVente == TypeVenteReforme.KILO && (data.getPoidsTotalKg() == null || data.getPoidsTotalKg() <= 0)) {
            throw new IllegalArgumentException("Le poids total (kg) est obligatoire pour une vente au kilo.");
        }
        // Vente SANS client : montantRapporte sert au contrôle du vendeur (voir
        // SoldeVendeurServiceImpl), donc obligatoire. Vente AVEC client : l'argent reçu est
        // un paiement client (voir plus bas, PaiementClientService) ; montantRapporte n'est
        // pas utilisé (client résolu plus bas, donc on teste directement clientUniqueId ici).
        // Symétrique de VenteOeufsImpl.create.
        boolean sansClient = data.getClientUniqueId() == null || data.getClientUniqueId().isBlank();
        if (sansClient && (data.getMontantRapporte() == null || data.getMontantRapporte() < 0)) {
            throw new IllegalArgumentException("Le montant rapporté est obligatoire.");
        }
        if (data.getMagasinUniqueId() == null || data.getMagasinUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le magasin de vente est obligatoire.");
        }

        Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
        if (magasin.getType() != Magasin.TypeMagasin.VENTE) {
            throw new IllegalArgumentException("On ne peut vendre que depuis un magasin de type VENTE.");
        }

        int restant = disponibleParProjetDansMagasin(magasin).values().stream().mapToInt(Integer::intValue).sum();
        if (data.getNombreSujets() > restant) {
            throw new IllegalArgumentException(
                "Stock de sujets réformés insuffisant dans ce magasin (" + restant + " sujet(s) restants)."
            );
        }

        Client client = null;
        if (data.getClientUniqueId() != null && !data.getClientUniqueId().isBlank()) {
            client = clientRepo.findByUniqueId(data.getClientUniqueId());
            // Client d'une autre ferme : même message qu'introuvable.
            if (client == null || client.getFarm() == null || !client.getFarm().getId().equals(farm.getId())) {
                throw new IllegalArgumentException("Client introuvable : " + data.getClientUniqueId());
            }
        }

        VenteReforme v = new VenteReforme();
        v.setUniqueId(java.util.UUID.randomUUID().toString());
        v.setFarm(farm);
        v.setMagasin(magasin);
        v.setClient(client);
        v.setCreePar(currentUser);
        v.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        v.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        v.setNombreSujets(data.getNombreSujets());
        v.setPrixUnitaire(data.getPrixUnitaire());
        v.setMontant(data.getMontant());
        // Vente AVEC client : l'argent reçu est un paiement client (voir PaiementClient) ;
        // montantRapporte ne sert plus qu'au contrôle du vendeur sur une vente SANS client.
        v.setMontantRapporte(client == null ? data.getMontantRapporte() : null);
        v.setTypeVente(typeVente);
        v.setPoidsTotalKg(typeVente == TypeVenteReforme.KILO ? data.getPoidsTotalKg() : null);
        v.setInitialisation(Initialisation.init());

        VenteReforme saved = venteReformeRepo.save(v);

        List<VenteReformeRepartition> lignes = repartirEtCreerTransactions(saved, farm, data.getNombreSujets(), data.getMontant(), currentUser);

        if (client == null) {
            if (data.getMontantRapporte() != null) {
                soldeVendeurService.ajusterSolde(currentUser, farm, data.getMontant() - data.getMontantRapporte());
            }
        } else {
            if (data.getMontantRapporte() != null && data.getMontantRapporte() > 0) {
                paiementClientService.enregistrerInterne(client, data.getMontantRapporte(),
                        modeOuEspeces(data.getModePaiement()), OriginePaiement.VENTE, saved.getCommande(),
                        CibleImputation.VENTE_REFORME, saved.getUniqueId(), null, null, saved.getDate());
            } else {
                compteClientService.imputer(client); // une avance éventuelle règle cette vente
            }
        }

        logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme",
                "Vente réforme de " + saved.getNombreSujets() + " sujet(s) (" + saved.getMontant() + " FCFA) depuis " + magasin.getNom() + ", répartie entre les projets contributeurs");

        VenteReformeDTO dto = VenteReformeDTO.fromEntity(saved);
        dto.setRepartitions(lignes.stream().map(VenteReformeRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public VenteReformeDTO update(String uniqueId, VenteReformeUpdate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        // Modifier une vente touche au solde (montant rapporté) : même population que pour
        // en demander la suppression, jamais le vendeur (il effacerait son propre manquant).
        ensureCanModifier(currentUser);
        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));

        // Client visé par la modification (null = inchangé). Passer de « sans client » à
        // « un client » (ou l'inverse) casserait le modèle d'argent : l'écart du vendeur
        // resterait dans son solde, le montant rapporté ne deviendrait pas un paiement
        // client, ou au contraire l'argent déjà reçu du client serait compté une deuxième
        // fois comme espèces rapportées. Refusé : on supprime la vente et on la ressaisit.
        // Changer de client (A -> B) reste permis.
        Client clientDemande = null;
        if (data.getClientUniqueId() != null && !data.getClientUniqueId().isBlank()) {
            clientDemande = clientRepo.findByUniqueId(data.getClientUniqueId());
            if (clientDemande == null || clientDemande.getFarm() == null || v.getFarm() == null
                    || !clientDemande.getFarm().getId().equals(v.getFarm().getId())) {
                throw new IllegalArgumentException("Client introuvable : " + data.getClientUniqueId());
            }
        }
        if (data.getClientUniqueId() != null
                && (v.getClient() == null) != (clientDemande == null)) {
            throw new IllegalArgumentException("Pour ajouter ou retirer le client d'une vente, supprimez-la et ressaisissez-la.");
        }
        // Verrou des clients concernés AVANT de toucher aux imputations (voir
        // CompteClientService.verrouiller) : ancien et nouveau client en cas de changement.
        compteClientService.verrouiller(v.getClient(), clientDemande);

        Double ancienMontant = v.getMontant();
        Double ancienMontantRapporte = v.getMontantRapporte();
        Client ancienClient = v.getClient();

        if (data.getDate() != null) v.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) v.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getPrixUnitaire() != null) v.setPrixUnitaire(data.getPrixUnitaire());

        if (data.getTypeVente() != null) {
            TypeVenteReforme nouveauType = parseTypeVente(data.getTypeVente());
            Double poids = data.getPoidsTotalKg() != null ? data.getPoidsTotalKg() : v.getPoidsTotalKg();
            if (nouveauType == TypeVenteReforme.KILO && (poids == null || poids <= 0)) {
                throw new IllegalArgumentException("Le poids total (kg) est obligatoire pour une vente au kilo.");
            }
            v.setTypeVente(nouveauType);
            v.setPoidsTotalKg(nouveauType == TypeVenteReforme.KILO ? poids : null);
        } else if (data.getPoidsTotalKg() != null && v.getTypeVente() == TypeVenteReforme.KILO) {
            v.setPoidsTotalKg(data.getPoidsTotalKg());
        }

        boolean redistribuer = data.getNombreSujets() != null || data.getMontant() != null;

        if (data.getNombreSujets() != null) {
            if (data.getNombreSujets() <= 0) {
                throw new IllegalArgumentException("Le nombre de sujets vendus doit être positif.");
            }
            if (v.getMagasin() == null) {
                throw new IllegalArgumentException("Cette vente n'est rattachée à aucun magasin (ancienne vente farm-wide) : quantité non modifiable.");
            }
            Map<Long, Integer> disponible = disponibleParProjetDansMagasin(v.getMagasin());
            int restantHorsCetteVente = disponible.values().stream().mapToInt(Integer::intValue).sum() + nz(v.getNombreSujets());
            if (data.getNombreSujets() > restantHorsCetteVente) {
                throw new IllegalArgumentException(
                    "Stock de sujets réformés insuffisant dans ce magasin (" + restantHorsCetteVente + " sujet(s) restants)."
                );
            }
            v.setNombreSujets(data.getNombreSujets());
        }
        if (data.getMontant() != null) {
            if (data.getMontant() <= 0) {
                throw new IllegalArgumentException("Le montant de la vente doit être positif.");
            }
            v.setMontant(data.getMontant());
        }

        // Client A -> client B seulement (ajout/retrait refusés plus haut).
        if (clientDemande != null) {
            v.setClient(clientDemande);
        }

        // Vente à un client (avant OU après cette modification) : plus de montantRapporte
        // manuel — l'argent reçu passe par un paiement enregistré depuis la fiche client
        // (voir PaiementClientService), pas par ce formulaire de vente.
        boolean venteAUnClient = ancienClient != null || v.getClient() != null;
        if (venteAUnClient && data.getMontantRapporte() != null) {
            throw new IllegalArgumentException("Pour une vente à un client, enregistrez un paiement depuis la fiche client.");
        }

        if (data.getMontantRapporte() != null) {
            v.setMontantRapporte(data.getMontantRapporte());
        }

        String ancienClientId = ancienClient != null ? ancienClient.getUniqueId() : null;
        String nouveauClientId = v.getClient() != null ? v.getClient().getUniqueId() : null;
        boolean clientChanged = ancienClientId == null ? nouveauClientId != null : !ancienClientId.equals(nouveauClientId);
        // Sert aussi plus bas (hors client) à rafraîchir le texte de traçabilité des
        // lignes de répartition existantes quand il n'y a pas eu de redistribution.
        boolean ecartChange = data.getMontantRapporte() != null || data.getMontant() != null;

        // Sans client (avant ET après) : comportement historique, écart théorique/rapporté
        // au solde du vendeur qui a créé la vente (pas celui qui modifie).
        if (!venteAUnClient) {
            if (ecartChange) {
                if (ancienMontantRapporte != null) {
                    soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), -(nz(ancienMontant) - ancienMontantRapporte));
                }
                if (v.getMontantRapporte() != null) {
                    soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), nz(v.getMontant()) - v.getMontantRapporte());
                }
            }
        } else if (clientChanged) {
            // Client A -> client B : l'argent de A déjà imputé sur cette vente redevient
            // une avance de A, et l'avance éventuelle de B règle la vente — ré-imputé plus
            // bas, une fois la vente sauvegardée.
            compteClientService.annulerImputationsCible(CibleImputation.VENTE_REFORME, uniqueId, "Client de la vente modifié");
        } else if (data.getMontant() != null && v.getMontant() < nz(ancienMontant)) {
            // Montant corrigé à la baisse : les imputations excédentaires sont annulées
            // (redeviennent une avance) avant de laisser imputer() les réappliquer plus bas.
            compteClientService.ramenerImputationsCible(CibleImputation.VENTE_REFORME, uniqueId, v.getMontant(), "Montant de la vente corrigé");
        }

        if (v.getInitialisation() != null) {
            v.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        VenteReforme saved = venteReformeRepo.save(v);

        if (venteAUnClient) {
            if (clientChanged) {
                if (ancienClient != null) compteClientService.imputer(ancienClient);
                if (saved.getClient() != null) compteClientService.imputer(saved.getClient());
            } else if (saved.getClient() != null && data.getMontant() != null) {
                compteClientService.imputer(saved.getClient());
            }
        }

        List<VenteReformeRepartition> lignesActuelles;
        if (redistribuer) {
            List<VenteReformeRepartition> anciennes = repartitionRepo.findByVenteReforme_UniqueId(saved.getUniqueId());
            for (VenteReformeRepartition ancienne : anciennes) {
                transactionService.setRemovedBySource(ancienne.getUniqueId(), true);
            }
            repartitionRepo.deleteAll(anciennes);
            lignesActuelles = repartirEtCreerTransactions(saved, saved.getFarm(), saved.getNombreSujets(), saved.getMontant(), saved.getCreePar() != null ? saved.getCreePar() : currentUser);
        } else {
            lignesActuelles = repartitionRepo.findByVenteReforme_UniqueId(saved.getUniqueId());
            // Pas de redistribution, mais l'écart rapporté a pu changer — voir le
            // commentaire équivalent dans VenteOeufsImpl.update().
            if (ecartChange) {
                String suffixeEcart = suffixeEcartRapporte(saved.getMontant(), saved.getMontantRapporte());
                for (VenteReformeRepartition ligne : lignesActuelles) {
                    transactionService.updateDescriptionBySource(ligne.getUniqueId(),
                            "Vente réforme : " + ligne.getNombreSujetsAttribue() + " sujet(s) (part de " + saved.getNombreSujets() + " vendus, magasin " + saved.getMagasin().getNom() + ")" + suffixeEcart);
                }
            }
        }

        // Sans redistribution, les transactions existantes gardaient l'ancienne date :
        // la vente et la comptabilité ne tombaient plus sur le même jour.
        if (!redistribuer && data.getDate() != null) {
            for (VenteReformeRepartition ligne : lignesActuelles) {
                transactionService.updateDateBySource(ligne.getUniqueId(), saved.getDate());
            }
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme", "Modification d'une vente réforme");
        }

        VenteReformeDTO dto = VenteReformeDTO.fromEntity(saved);
        dto.setRepartitions(lignesActuelles.stream().map(VenteReformeRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId, String motif) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);

        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));

        boolean removed = !v.getInitialisation().getRemoved();
        // Motif exigé pour supprimer, pas pour restaurer.
        if (removed) v.setMotifSuppression(MotifSuppressionRequest.exiger(motif));
        // Restaurer une livraison : la commande doit pouvoir la reprendre (voir
        // LivraisonCommandeService), vérifié avant toute écriture.
        if (!removed) livraisonCommandeService.verifierRestauration(v.getCommande(), v.getNombreSujets());
        // Verrou client avant de toucher aux imputations (voir CompteClientService.verrouiller).
        compteClientService.verrouiller(v.getClient());
        v.getInitialisation().setRemoved(removed);
        venteReformeRepo.save(v);

        // Set explicite (jamais un toggle) — voir VenteOeufsImpl.deleteOrRecover pour
        // le raisonnement complet.
        for (VenteReformeRepartition r : repartitionRepo.findByVenteReforme_UniqueId(uniqueId)) {
            transactionService.setRemovedBySource(r.getUniqueId(), removed);
        }

        if (v.getClient() != null) {
            if (removed) {
                compteClientService.annulerImputationsCible(CibleImputation.VENTE_REFORME, uniqueId,
                        "Vente supprimée : " + v.getMotifSuppression());
            }
            compteClientService.imputer(v.getClient()); // l'argent libéré peut régler d'autres ventes
        } else if (v.getCreePar() != null && v.getMontantRapporte() != null) {
            double ecart = nz(v.getMontant()) - v.getMontantRapporte();
            soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), removed ? -ecart : ecart);
        }

        // Livraison d'une commande : sa quantité quitte (ou retrouve) la commande.
        if (removed) livraisonCommandeService.livraisonSupprimee(v.getCommande(), v.getNombreSujets());
        else livraisonCommandeService.livraisonRestauree(v.getCommande(), v.getNombreSujets());

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteReforme",
                    (removed ? "Suppression" : "Restauration") + " d'une vente réforme"
                            + (removed ? ", motif : " + v.getMotifSuppression() : ""));
        }

        return removed ? "Vente supprimée." : "Vente récupérée.";
    }

    @Override
    @Transactional
    public VenteReformeDTO demanderSuppression(String uniqueId, String motif) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanDemanderSuppression(currentUser);
        String motifValide = MotifSuppressionRequest.exiger(motif);

        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));
        if (v.getDemandeSuppressionPar() != null) {
            throw new IllegalArgumentException("Une demande de suppression est déjà en attente pour cette vente.");
        }
        v.setDemandeSuppressionPar(currentUser);
        v.setDateDemandeSuppression(java.time.LocalDateTime.now());
        v.setMotifSuppression(motifValide);
        VenteReforme saved = venteReformeRepo.save(v);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme",
                    "Demande de suppression d'une vente réforme, motif : " + motifValide);
        }
        return VenteReformeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public VenteReformeDTO confirmerSuppression(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);

        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));
        if (v.getDemandeSuppressionPar() == null) {
            throw new IllegalArgumentException("Aucune demande de suppression en attente pour cette vente.");
        }
        if (Boolean.TRUE.equals(v.getInitialisation().getRemoved())) {
            throw new IllegalArgumentException("Cette vente est déjà supprimée.");
        }
        compteClientService.verrouiller(v.getClient()); // voir deleteOrRecover

        v.getInitialisation().setRemoved(true);
        venteReformeRepo.save(v);

        for (VenteReformeRepartition r : repartitionRepo.findByVenteReforme_UniqueId(uniqueId)) {
            transactionService.setRemovedBySource(r.getUniqueId(), true);
        }
        if (v.getClient() != null) {
            compteClientService.annulerImputationsCible(CibleImputation.VENTE_REFORME, uniqueId,
                    "Vente supprimée : " + v.getMotifSuppression());
            compteClientService.imputer(v.getClient());
        } else if (v.getCreePar() != null && v.getMontantRapporte() != null) {
            double ecart = nz(v.getMontant()) - v.getMontantRapporte();
            soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), -ecart);
        }
        livraisonCommandeService.livraisonSupprimee(v.getCommande(), v.getNombreSujets());

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteReforme", "Suppression confirmée pour une vente réforme, motif : " + v.getMotifSuppression());
        }
        return VenteReformeDTO.fromEntity(v);
    }

    @Override
    @Transactional
    public VenteReformeDTO annulerDemandeSuppression(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);

        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));
        if (v.getDemandeSuppressionPar() == null) {
            throw new IllegalArgumentException("Aucune demande de suppression en attente pour cette vente.");
        }
        v.setDemandeSuppressionPar(null);
        v.setDateDemandeSuppression(null);
        v.setMotifSuppression(null);
        VenteReforme saved = venteReformeRepo.save(v);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme", "Demande de suppression refusée pour une vente réforme");
        }
        return VenteReformeDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<VenteReformeDTO> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        Page<VenteReforme> resultPage = venteReformeRepo.search(farmId, pageable);

        List<VenteReformeDTO> dtoList = resultPage.getContent().stream()
                .map(VenteReformeDTO::fromEntity)
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public VenteReformeDTO detail(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        String introuvable = "Vente réforme introuvable : " + uniqueId;
        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException(introuvable));
        FermeScope.verifier(v.getFarm(), currentUser, introuvable);
        if (v.getInitialisation() != null && Boolean.TRUE.equals(v.getInitialisation().getRemoved())) {
            throw new IllegalArgumentException(introuvable);
        }
        return VenteReformeDTO.fromEntity(v);
    }

    // Cumul pour StatsReformeDTO.Chiffres (montants arrondis au franc près, poids à 3 décimales).
    private static final class Cumul {
        final java.util.Set<Long> ventes = new java.util.HashSet<>();
        int sujets;
        double montant;
        int sujetsKilo;
        double poidsKilo;
        double montantKilo;

        void ajouter(Long venteId, int sujets, double montant, boolean kilo, double poids) {
            ventes.add(venteId);
            this.sujets += sujets;
            this.montant += montant;
            if (kilo) {
                sujetsKilo += sujets;
                poidsKilo += poids;
                montantKilo += montant;
            }
        }

        StatsReformeDTO.Chiffres chiffres(String projetUniqueId, String projetCode) {
            return StatsReformeDTO.Chiffres.builder()
                    .projetUniqueId(projetUniqueId)
                    .projetCode(projetCode)
                    .nombreVentes(ventes.size())
                    .nombreSujetsVendus(sujets)
                    .montantTotal(arr2(montant))
                    .prixMoyenParTete(sujets > 0 ? arr2(montant / sujets) : null)
                    .nombreSujetsVendusAuKilo(sujetsKilo)
                    .poidsTotalVenduKg(arr3(poidsKilo))
                    .montantVenduAuKilo(arr2(montantKilo))
                    .prixMoyenKg(poidsKilo > 0 ? arr2(montantKilo / poidsKilo) : null)
                    .poidsMoyenParSujetKg(sujetsKilo > 0 ? arr3(poidsKilo / sujetsKilo) : null)
                    .build();
        }
    }

    private static double arr2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double arr3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static boolean venteAuKilo(VenteReforme v) {
        return v.getTypeVente() == TypeVenteReforme.KILO && v.getPoidsTotalKg() != null && v.getPoidsTotalKg() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public StatsReformeDTO stats(LocalDate dateDebut, LocalDate dateFin, String projetUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Long farmId = currentUser.getFarm().getId();
        LocalDate deb = dateDebut != null ? dateDebut : LocalDate.of(1900, 1, 1);
        LocalDate fin = dateFin != null ? dateFin : LocalDate.of(2999, 12, 31);
        if (fin.isBefore(deb)) {
            throw new IllegalArgumentException("La date de fin précède la date de début.");
        }
        boolean hasProjet = projetUniqueId != null && !projetUniqueId.isBlank();
        Projets projetFiltre = null;
        if (hasProjet) {
            String introuvable = "Projet introuvable : " + projetUniqueId;
            projetFiltre = projetsRepo.findByUniqueId(projetUniqueId.trim())
                    .orElseThrow(() -> new IllegalArgumentException(introuvable));
            FermeScope.verifier(projetFiltre.getFarm(), currentUser, introuvable);
        }

        // Par projet : parts de répartition (sujets et montant attribués) ; le poids d'une
        // vente au kilo est réparti au prorata des sujets attribués.
        Map<Long, Cumul> parProjet = new LinkedHashMap<>();
        Map<Long, Projets> projets = new LinkedHashMap<>();
        for (VenteReformeRepartition r : repartitionRepo.findPourStats(farmId, deb, fin, hasProjet,
                hasProjet ? projetUniqueId.trim() : "")) {
            VenteReforme v = r.getVenteReforme();
            int sujets = nz(r.getNombreSujetsAttribue());
            boolean kilo = venteAuKilo(v);
            double poids = kilo && nz(v.getNombreSujets()) > 0 ? v.getPoidsTotalKg() * sujets / v.getNombreSujets() : 0.0;
            projets.put(r.getProjet().getId(), r.getProjet());
            parProjet.computeIfAbsent(r.getProjet().getId(), k -> new Cumul())
                    .ajouter(v.getId(), sujets, nz(r.getMontantAttribue()), kilo, poids);
        }
        List<StatsReformeDTO.Chiffres> lignes = parProjet.entrySet().stream()
                .map(e -> e.getValue().chiffres(projets.get(e.getKey()).getUniqueId(), projets.get(e.getKey()).getCode()))
                .sorted(java.util.Comparator.comparing(StatsReformeDTO.Chiffres::getProjetCode,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .toList();

        StatsReformeDTO.Chiffres total;
        if (hasProjet) {
            Cumul c = projetFiltre != null ? parProjet.get(projetFiltre.getId()) : null;
            total = (c != null ? c : new Cumul()).chiffres(null, null);
        } else {
            // Toute la ferme : directement depuis les ventes (inclut d'éventuelles
            // anciennes ventes sans répartition).
            Cumul c = new Cumul();
            for (VenteReforme v : venteReformeRepo.findActivesPourListe(farmId, deb, fin)) {
                boolean kilo = venteAuKilo(v);
                c.ajouter(v.getId(), nz(v.getNombreSujets()), nz(v.getMontant()), kilo, kilo ? v.getPoidsTotalKg() : 0.0);
            }
            total = c.chiffres(null, null);
        }

        return StatsReformeDTO.builder()
                .dateDebut(dateDebut)
                .dateFin(dateFin)
                .projetUniqueId(projetFiltre != null ? projetFiltre.getUniqueId() : null)
                .projetCode(projetFiltre != null ? projetFiltre.getCode() : null)
                .total(total)
                .parProjet(lignes)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public StockReformeDTO getStock() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Long farmId = currentUser.getFarm().getId();

        // Indicateur farm-wide global (reporting admin), distinct du stock par magasin
        // qui seul plafonne une vente précise — voir VenteOeufsImpl.getStock.
        int totalReforme = nz(reformeRepo.sumSujetsByFarmId(farmId));
        int totalVendu = nz(venteReformeRepo.sumSujetsVendusByFarmId(farmId));
        int restant = totalReforme - totalVendu;

        return StockReformeDTO.builder()
                .totalReforme(totalReforme)
                .totalVendu(totalVendu)
                .stockRestant(restant)
                .statut(restant <= 0 ? "EPUISE" : "ACTIF")
                .build();
    }
}
