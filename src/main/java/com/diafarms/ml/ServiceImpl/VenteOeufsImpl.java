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

import com.diafarms.ml.DTO.StockOeufsDTO;
import com.diafarms.ml.DTO.VenteOeufsDTO;
import com.diafarms.ml.DTO.VenteOeufsRepartitionDTO;
import com.diafarms.ml.commons.FermeScope;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.enums.TypeVenteOeufs;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteOeufsRepartition;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.MagasinRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.VenteOeufsRepartitionRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.update.VenteOeufsUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteOeufsService;

import lombok.RequiredArgsConstructor;

// Vente d'œufs (Finance) : vendue DEPUIS un magasin précis (voir Magasin), qui a
// lui-même reçu son stock par des transferts explicites depuis un ou plusieurs projets
// (voir MagasinTransfert) — remplace l'ancienne répartition automatique farm-wide à la
// vente. La répartition entre projets contributeurs (pour le chiffre d'affaires par
// projet) se calcule maintenant à partir des transferts reçus par CE magasin, pas du
// stock collecté de toute la ferme.
@Service
@RequiredArgsConstructor
public class VenteOeufsImpl implements VenteOeufsService {

    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteOeufsRepartitionRepo repartitionRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;
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

    // Peut DEMANDER une suppression — jamais le vendeur (VENTE), même pour sa propre
    // vente : il ne doit pas pouvoir effacer la trace d'un manquant sur l'argent qu'il
    // devait rapporter (voir SoldeVendeurServiceImpl). Même population que
    // TransactionServiceImpl.ensureCanDemanderSuppression.
    private void ensureCanDemanderSuppression(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour demander la suppression d'une vente.");
        }
    }

    // Peut CONFIRMER une demande, ou supprimer/restaurer directement — un cran plus
    // strict qu'une demande (COMPTABLE exclu) : une vente déjà encaissée en partie ou
    // en totalité ne doit jamais disparaître sur la seule décision d'une personne qui
    // manipule l'argent au quotidien.
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

    /** BON -> pool de transfert OEUFS, CASSE -> pool OEUFS_CASSES — deux pools de stock
     * magasin totalement séparés (voir CollecteOeufsImpl, TypeStockMagasin). */
    private TypeStockMagasin typeStock(TypeVenteOeufs typeOeuf) {
        return typeOeuf == TypeVenteOeufs.CASSE ? TypeStockMagasin.OEUFS_CASSES : TypeStockMagasin.OEUFS;
    }

    /** Stock (bon OU cassé selon typeOeuf) restant DANS ce magasin, projet par projet
     * (ceux qui y ont transféré du stock) — sert de poids pour la répartition
     * proportionnelle d'une vente entre les projets contributeurs de CE magasin précis. */
    private Map<Long, Integer> disponibleParProjetDansMagasin(Magasin magasin, TypeVenteOeufs typeOeuf) {
        TypeStockMagasin type = typeStock(typeOeuf);
        Map<Long, Integer> disponible = new LinkedHashMap<>();
        List<Long> projetIds = magasinTransfertRepo.findDistinctProjetIdsByMagasinAndType(magasin.getId(), type);
        for (Long projetId : projetIds) {
            int transfere = nz(magasinTransfertRepo.sumQuantiteByMagasinAndProjetAndType(magasin.getId(), projetId, type));
            int vendu = nz(repartitionRepo.sumQuantiteByProjetIdAndMagasinId(projetId, magasin.getId(), typeOeuf));
            int restant = transfere - vendu;
            if (restant > 0) disponible.put(projetId, restant);
        }
        return disponible;
    }

    /** Répartit la vente entre les projets contributeurs DE CE MAGASIN, sauvegarde les
     * lignes de VenteOeufsRepartition et génère une Transaction par projet — factorisé
     * pour être appelé identiquement par create() et update(). */
    private List<VenteOeufsRepartition> repartirEtCreerTransactions(VenteOeufs saved, Farm farm, int quantite, double montant, Utilisateurs creePar) {
        Map<Long, Integer> disponible = disponibleParProjetDansMagasin(saved.getMagasin(), saved.getTypeOeuf());
        Map<Long, Projets> projetsParId = new LinkedHashMap<>();
        for (Long projetId : disponible.keySet()) {
            projetsRepo.findById(projetId).ifPresent(p -> projetsParId.put(projetId, p));
        }

        List<RepartitionUtil.Part> parts = RepartitionUtil.repartir(quantite, montant, disponible);
        List<VenteOeufsRepartition> lignes = new java.util.ArrayList<>();

        // Traçabilité de l'écart directement dans la ligne (même texte identique sur
        // chaque part de cette vente, car montant/montantRapporte sont ceux de LA VENTE
        // ENTIÈRE, pas de cette part précise) — sans ça, un "il reste 2500 à payer" sur
        // le solde vendeur ne permettait de retrouver AUCUNE ligne précise dans la table
        // des transactions. Voir aussi la carte Solde Vendeur (agrégée) sur Ventes.tsx.
        String suffixeEcart = suffixeEcartRapporte(saved.getMontant(), saved.getMontantRapporte());
        boolean casse = saved.getTypeOeuf() == TypeVenteOeufs.CASSE;
        String libelleOeufs = casse ? "œufs cassés" : "œufs";

        for (RepartitionUtil.Part part : parts) {
            Projets projet = projetsParId.get(part.projetId);

            VenteOeufsRepartition r = new VenteOeufsRepartition();
            r.setUniqueId(java.util.UUID.randomUUID().toString());
            r.setVenteOeufs(saved);
            r.setProjet(projet);
            r.setQuantiteAttribuee(part.quantite);
            r.setMontantAttribue(part.montant);
            lignes.add(repartitionRepo.save(r));

            transactionService.createFromSource(
                    projet, farm, part.montant, casse ? "Vente œufs cassés" : "Vente œufs", saved.getDate(),
                    "Vente de " + part.quantite + " " + libelleOeufs + " (part de " + saved.getQuantiteOeufs() + " vendus, magasin " + saved.getMagasin().getNom() + ")" + suffixeEcart,
                    SourceTransaction.VENTE_OEUFS, r.getUniqueId(), creePar
            );
        }
        return lignes;
    }

    /** " · Rapporté : X FCFA / Y FCFA théoriques (manque/surplus Z FCFA)", vide si pas
     * encore de montant rapporté saisi ou si égal au théorique (rien à signaler). Même
     * convention de signe que SoldeVendeurServiceImpl.ajusterSolde : écart = théorique -
     * rapporté, positif = le vendeur doit de l'argent à la ferme. */
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
    public VenteOeufsDTO create(VenteOeufsCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Farm farm = currentUser.getFarm();

        if (data.getQuantiteOeufs() == null || data.getQuantiteOeufs() <= 0) {
            throw new IllegalArgumentException("La quantité d'œufs vendus doit être positive.");
        }
        if (data.getMontant() == null || data.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant de la vente doit être positif.");
        }
        // Requis côté serveur en dernier ressort (déjà imposé côté web/mobile) : sans
        // prix unitaire ni montant rapporté, le suivi réel/théorique (SoldeClient/
        // SoldeVendeur) et le coût de revient par projet perdent toute fiabilité.
        if (data.getPrixUnitaire() == null || data.getPrixUnitaire() <= 0) {
            throw new IllegalArgumentException("Le prix unitaire est obligatoire.");
        }
        // Vente SANS client : montantRapporte sert au contrôle du vendeur (voir
        // SoldeVendeurServiceImpl), donc obligatoire. Vente AVEC client : l'argent reçu est
        // un paiement client (voir plus bas, PaiementClientService) ; montantRapporte n'est
        // pas utilisé (client résolu plus bas, donc on teste directement clientUniqueId ici).
        boolean sansClient = data.getClientUniqueId() == null || data.getClientUniqueId().isBlank();
        if (sansClient && (data.getMontantRapporte() == null || data.getMontantRapporte() < 0)) {
            throw new IllegalArgumentException("Le montant rapporté est obligatoire.");
        }
        if (data.getMagasinUniqueId() == null || data.getMagasinUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le magasin de vente est obligatoire.");
        }

        TypeVenteOeufs typeOeuf;
        try {
            typeOeuf = (data.getTypeOeuf() == null || data.getTypeOeuf().isBlank())
                    ? TypeVenteOeufs.BON : TypeVenteOeufs.valueOf(data.getTypeOeuf().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Type de vente invalide (attendu BON ou CASSE) : " + data.getTypeOeuf());
        }

        Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
        if (magasin.getType() != Magasin.TypeMagasin.VENTE) {
            throw new IllegalArgumentException("On ne peut vendre que depuis un magasin de type VENTE.");
        }

        int restant = disponibleParProjetDansMagasin(magasin, typeOeuf).values().stream().mapToInt(Integer::intValue).sum();
        if (data.getQuantiteOeufs() > restant) {
            throw new IllegalArgumentException(
                (typeOeuf == TypeVenteOeufs.CASSE ? "Stock d'œufs cassés insuffisant" : "Stock d'œufs insuffisant")
                        + " dans ce magasin (" + restant + " œuf(s) restants)."
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

        VenteOeufs v = new VenteOeufs();
        v.setUniqueId(java.util.UUID.randomUUID().toString());
        v.setFarm(farm);
        v.setMagasin(magasin);
        v.setClient(client);
        v.setCreePar(currentUser);
        v.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        v.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        v.setQuantiteOeufs(data.getQuantiteOeufs());
        v.setPrixUnitaire(data.getPrixUnitaire());
        v.setMontant(data.getMontant());
        // Vente AVEC client : l'argent reçu est un paiement client (voir PaiementClient) ;
        // montantRapporte ne sert plus qu'au contrôle du vendeur sur une vente SANS client.
        v.setMontantRapporte(client == null ? data.getMontantRapporte() : null);
        v.setTypeOeuf(typeOeuf);
        v.setInitialisation(Initialisation.init());

        VenteOeufs saved = venteOeufsRepo.save(v);

        List<VenteOeufsRepartition> lignes = repartirEtCreerTransactions(saved, farm, data.getQuantiteOeufs(), data.getMontant(), currentUser);

        if (client == null) {
            if (data.getMontantRapporte() != null) {
                soldeVendeurService.ajusterSolde(currentUser, farm, data.getMontant() - data.getMontantRapporte());
            }
        } else {
            if (data.getMontantRapporte() != null && data.getMontantRapporte() > 0) {
                paiementClientService.enregistrerInterne(client, data.getMontantRapporte(),
                        modeOuEspeces(data.getModePaiement()), OriginePaiement.VENTE, saved.getCommande(),
                        CibleImputation.VENTE_OEUFS, saved.getUniqueId(), null, null, saved.getDate());
            } else {
                compteClientService.imputer(client); // une avance éventuelle règle cette vente
            }
        }

        logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs",
                "Vente de " + saved.getQuantiteOeufs() + " œufs (" + saved.getMontant() + " FCFA) depuis " + magasin.getNom() + ", répartie entre les projets contributeurs");

        VenteOeufsDTO dto = VenteOeufsDTO.fromEntity(saved);
        dto.setRepartitions(lignes.stream().map(VenteOeufsRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public VenteOeufsDTO update(String uniqueId, VenteOeufsUpdate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        // Modifier une vente touche au solde (montant rapporté) : même population que pour
        // en demander la suppression, jamais le vendeur (il effacerait son propre manquant).
        ensureCanModifier(currentUser);
        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .filter(x -> FermeScope.memeFerme(x.getFarm(), currentUser))
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));

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

        // Capturé AVANT toute mutation : sert à annuler l'ancien écart du solde
        // (vendeur OU client selon qui portait l'écart à l'époque) plus bas, avant
        // d'appliquer le nouveau (voir bloc solde après les mutations).
        Double ancienMontant = v.getMontant();
        Double ancienMontantRapporte = v.getMontantRapporte();
        Client ancienClient = v.getClient();

        if (data.getDate() != null) v.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) v.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getPrixUnitaire() != null) v.setPrixUnitaire(data.getPrixUnitaire());

        boolean redistribuer = data.getQuantiteOeufs() != null || data.getMontant() != null;

        if (data.getQuantiteOeufs() != null) {
            if (data.getQuantiteOeufs() <= 0) {
                throw new IllegalArgumentException("La quantité d'œufs vendus doit être positive.");
            }
            if (v.getMagasin() == null) {
                throw new IllegalArgumentException("Cette vente n'est rattachée à aucun magasin (ancienne vente farm-wide) : quantité non modifiable.");
            }
            Map<Long, Integer> disponible = disponibleParProjetDansMagasin(v.getMagasin(), v.getTypeOeuf());
            int restantHorsCetteVente = disponible.values().stream().mapToInt(Integer::intValue).sum() + nz(v.getQuantiteOeufs());
            if (data.getQuantiteOeufs() > restantHorsCetteVente) {
                throw new IllegalArgumentException(
                    "Stock d'œufs insuffisant dans ce magasin (" + restantHorsCetteVente + " œuf(s) restants)."
                );
            }
            v.setQuantiteOeufs(data.getQuantiteOeufs());
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
            compteClientService.annulerImputationsCible(CibleImputation.VENTE_OEUFS, uniqueId, "Client de la vente modifié");
        } else if (data.getMontant() != null && v.getMontant() < nz(ancienMontant)) {
            // Montant corrigé à la baisse : les imputations excédentaires sont annulées
            // (redeviennent une avance) avant de laisser imputer() les réappliquer plus bas.
            compteClientService.ramenerImputationsCible(CibleImputation.VENTE_OEUFS, uniqueId, v.getMontant(), "Montant de la vente corrigé");
        }

        if (v.getInitialisation() != null) {
            v.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        VenteOeufs saved = venteOeufsRepo.save(v);

        if (venteAUnClient) {
            if (clientChanged) {
                if (ancienClient != null) compteClientService.imputer(ancienClient);
                if (saved.getClient() != null) compteClientService.imputer(saved.getClient());
            } else if (saved.getClient() != null && data.getMontant() != null) {
                compteClientService.imputer(saved.getClient());
            }
        }

        // Quantité et/ou montant modifiés : on refait la répartition à zéro (les
        // anciennes lignes et leurs Transactions sont retirées puis recréées) plutôt
        // que d'essayer de corriger les parts existantes au prorata — plus simple et
        // sans risque d'incohérence entre projets.
        List<VenteOeufsRepartition> lignesActuelles;
        if (redistribuer) {
            List<VenteOeufsRepartition> anciennes = repartitionRepo.findByVenteOeufs_UniqueId(saved.getUniqueId());
            for (VenteOeufsRepartition ancienne : anciennes) {
                transactionService.setRemovedBySource(ancienne.getUniqueId(), true);
            }
            repartitionRepo.deleteAll(anciennes);
            lignesActuelles = repartirEtCreerTransactions(saved, saved.getFarm(), saved.getQuantiteOeufs(), saved.getMontant(), saved.getCreePar() != null ? saved.getCreePar() : currentUser);
        } else {
            lignesActuelles = repartitionRepo.findByVenteOeufs_UniqueId(saved.getUniqueId());
            // Pas de redistribution (quantité/montant théorique inchangés), mais l'écart
            // rapporté a pu changer : on rafraîchit juste le texte de traçabilité de
            // chaque ligne existante, sans toucher réf/montant/statut de la transaction.
            if (ecartChange) {
                String suffixeEcart = suffixeEcartRapporte(saved.getMontant(), saved.getMontantRapporte());
                String libelleOeufs = saved.getTypeOeuf() == TypeVenteOeufs.CASSE ? "œufs cassés" : "œufs";
                for (VenteOeufsRepartition ligne : lignesActuelles) {
                    transactionService.updateDescriptionBySource(ligne.getUniqueId(),
                            "Vente de " + ligne.getQuantiteAttribuee() + " " + libelleOeufs + " (part de " + saved.getQuantiteOeufs() + " vendus, magasin " + saved.getMagasin().getNom() + ")" + suffixeEcart);
                }
            }
        }

        // Sans redistribution, les transactions existantes gardaient l'ancienne date :
        // la vente et la comptabilité ne tombaient plus sur le même jour.
        if (!redistribuer && data.getDate() != null) {
            for (VenteOeufsRepartition ligne : lignesActuelles) {
                transactionService.updateDateBySource(ligne.getUniqueId(), saved.getDate());
            }
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs", "Modification d'une vente d'œufs");
        }

        VenteOeufsDTO dto = VenteOeufsDTO.fromEntity(saved);
        dto.setRepartitions(lignesActuelles.stream().map(VenteOeufsRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId, String motif) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);

        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .filter(x -> FermeScope.memeFerme(x.getFarm(), currentUser))
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));

        boolean removed = !v.getInitialisation().getRemoved();
        // Motif exigé pour supprimer, pas pour restaurer.
        if (removed) v.setMotifSuppression(MotifSuppressionRequest.exiger(motif));
        // Restaurer une livraison : la commande doit pouvoir la reprendre (voir
        // LivraisonCommandeService), vérifié avant toute écriture.
        if (!removed) livraisonCommandeService.verifierRestauration(v.getCommande(), v.getQuantiteOeufs());
        // Verrou client avant de toucher aux imputations (voir CompteClientService.verrouiller).
        compteClientService.verrouiller(v.getClient());
        v.getInitialisation().setRemoved(removed);
        venteOeufsRepo.save(v);

        // Set explicite (jamais un toggle) : la transaction générée suit TOUJOURS l'état
        // qu'on vient de décider pour la vente, même si elle avait déjà été modifiée
        // séparément entre-temps (ex: supprimée à part depuis la Comptabilité) — un
        // simple flip l'aurait remise dans le mauvais état dans ce cas.
        for (VenteOeufsRepartition r : repartitionRepo.findByVenteOeufs_UniqueId(uniqueId)) {
            transactionService.setRemovedBySource(r.getUniqueId(), removed);
        }

        // Supprimer une vente annule aussi son impact sur le solde (vendeur ou client
        // selon qui le portait — et la restauration le réapplique) — sinon une dette
        // resterait comptée pour une vente qui n'existe plus.
        // Livraison d'une commande : sa quantité quitte (ou retrouve) la commande. AVANT
        // imputer() : le statut de la commande décide si ses acomptes sont encore réservés
        // (voir CompteClientService.estReservee).
        if (removed) livraisonCommandeService.livraisonSupprimee(v.getCommande(), v.getQuantiteOeufs());
        else livraisonCommandeService.livraisonRestauree(v.getCommande(), v.getQuantiteOeufs());

        if (v.getClient() != null) {
            if (removed) {
                compteClientService.annulerImputationsCible(CibleImputation.VENTE_OEUFS, uniqueId,
                        "Vente supprimée : " + v.getMotifSuppression());
            }
            compteClientService.imputer(v.getClient()); // l'argent libéré peut régler d'autres ventes
        } else if (v.getCreePar() != null && v.getMontantRapporte() != null) {
            double ecart = nz(v.getMontant()) - v.getMontantRapporte();
            soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), removed ? -ecart : ecart);
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteOeufs",
                    (removed ? "Suppression" : "Restauration") + " d'une vente d'œufs"
                            + (removed ? ", motif : " + v.getMotifSuppression() : ""));
        }

        return removed ? "Vente supprimée." : "Vente récupérée.";
    }

    @Override
    @Transactional
    public VenteOeufsDTO demanderSuppression(String uniqueId, String motif) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanDemanderSuppression(currentUser);
        String motifValide = MotifSuppressionRequest.exiger(motif);

        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .filter(x -> FermeScope.memeFerme(x.getFarm(), currentUser))
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));
        if (v.getDemandeSuppressionPar() != null) {
            throw new IllegalArgumentException("Une demande de suppression est déjà en attente pour cette vente.");
        }
        v.setDemandeSuppressionPar(currentUser);
        v.setDateDemandeSuppression(java.time.LocalDateTime.now());
        v.setMotifSuppression(motifValide);
        VenteOeufs saved = venteOeufsRepo.save(v);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs",
                    "Demande de suppression d'une vente d'œufs, motif : " + motifValide);
        }
        return VenteOeufsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public VenteOeufsDTO confirmerSuppression(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);

        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .filter(x -> FermeScope.memeFerme(x.getFarm(), currentUser))
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));
        if (v.getDemandeSuppressionPar() == null) {
            throw new IllegalArgumentException("Aucune demande de suppression en attente pour cette vente.");
        }
        if (Boolean.TRUE.equals(v.getInitialisation().getRemoved())) {
            throw new IllegalArgumentException("Cette vente est déjà supprimée.");
        }
        compteClientService.verrouiller(v.getClient()); // voir deleteOrRecover

        v.getInitialisation().setRemoved(true);
        venteOeufsRepo.save(v);

        for (VenteOeufsRepartition r : repartitionRepo.findByVenteOeufs_UniqueId(uniqueId)) {
            transactionService.setRemovedBySource(r.getUniqueId(), true);
        }
        livraisonCommandeService.livraisonSupprimee(v.getCommande(), v.getQuantiteOeufs());
        if (v.getClient() != null) {
            compteClientService.annulerImputationsCible(CibleImputation.VENTE_OEUFS, uniqueId,
                    "Vente supprimée : " + v.getMotifSuppression());
            compteClientService.imputer(v.getClient());
        } else if (v.getCreePar() != null && v.getMontantRapporte() != null) {
            double ecart = nz(v.getMontant()) - v.getMontantRapporte();
            soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), -ecart);
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteOeufs", "Suppression confirmée pour une vente d'œufs, motif : " + v.getMotifSuppression());
        }
        return VenteOeufsDTO.fromEntity(v);
    }

    @Override
    @Transactional
    public VenteOeufsDTO annulerDemandeSuppression(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);

        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .filter(x -> FermeScope.memeFerme(x.getFarm(), currentUser))
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));
        if (v.getDemandeSuppressionPar() == null) {
            throw new IllegalArgumentException("Aucune demande de suppression en attente pour cette vente.");
        }
        v.setDemandeSuppressionPar(null);
        v.setDateDemandeSuppression(null);
        v.setMotifSuppression(null);
        VenteOeufs saved = venteOeufsRepo.save(v);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs", "Demande de suppression refusée pour une vente d'œufs");
        }
        return VenteOeufsDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<VenteOeufsDTO> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        Page<VenteOeufs> resultPage = venteOeufsRepo.search(farmId, pageable);

        List<VenteOeufsDTO> dtoList = resultPage.getContent().stream()
                .map(VenteOeufsDTO::fromEntity)
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
    public StockOeufsDTO getStock() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Long farmId = currentUser.getFarm().getId();

        // Reste un indicateur farm-wide global (utile en reporting admin) : bon état
        // (StockOeufsRegle) - ventes d'œufs bons, tous magasins confondus — distinct du stock par magasin
        // (voir MagasinService.getStock), qui seul plafonne une vente précise.
        int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByFarmId(farmId));
        int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByFarmId(farmId));
        int totalNonUtilisable = nz(collecteOeufsRepo.sumOeufsNonUtilisablesByFarmId(farmId));
        int totalVendu = nz(venteOeufsRepo.sumQuantiteBonByFarmId(farmId));
        int restant = com.diafarms.ml.commons.StockOeufsRegle.bonEtat(totalCollecte, totalCasse, totalNonUtilisable) - totalVendu;

        return StockOeufsDTO.builder()
                .totalCollecte(totalCollecte)
                .totalCasse(totalCasse)
                .totalNonUtilisable(totalNonUtilisable)
                .totalVendu(totalVendu)
                .stockRestant(restant)
                .statut(restant <= 0 ? "EPUISE" : "ACTIF")
                .build();
    }
}
