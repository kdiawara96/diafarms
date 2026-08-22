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
import com.diafarms.ml.commons.Initialisation;
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
    private final SoldeClientServiceImpl soldeClientService;
    private final LogsServices logs;
    private final OtherService otherService;
    private final TransactionService transactionService;

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

    /** Route l'écart théorique/rapporté vers le solde du CLIENT si la vente en a un
     * (vente à crédit : ce n'est pas le vendeur qui est en tort), sinon vers le solde
     * du vendeur (comportement historique, vente "directe" sans client identifié). */
    private void ajusterEcart(Client client, Utilisateurs vendeur, Farm farm, double delta) {
        if (client != null) {
            soldeClientService.ajusterSolde(client, farm, delta);
        } else if (vendeur != null) {
            soldeVendeurService.ajusterSolde(vendeur, farm, delta);
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

    /** " — Rapporté : X FCFA / Y FCFA théoriques (manque/surplus Z FCFA)", vide si pas
     * encore de montant rapporté saisi ou si égal au théorique (rien à signaler). Même
     * convention de signe que SoldeVendeurServiceImpl.ajusterSolde : écart = théorique -
     * rapporté, positif = le vendeur doit de l'argent à la ferme. */
    private String suffixeEcartRapporte(Double montantTheorique, Double montantRapporte) {
        if (montantTheorique == null || montantRapporte == null || montantRapporte.equals(montantTheorique)) {
            return "";
        }
        double ecart = montantTheorique - montantRapporte;
        return String.format(Locale.FRANCE, " — Rapporté : %.0f FCFA / %.0f FCFA théoriques (%s %.0f FCFA)",
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
        if (data.getMontantRapporte() == null || data.getMontantRapporte() < 0) {
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
            if (client == null) {
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
        v.setMontantRapporte(data.getMontantRapporte());
        v.setTypeOeuf(typeOeuf);
        v.setInitialisation(Initialisation.init());

        VenteOeufs saved = venteOeufsRepo.save(v);

        List<VenteOeufsRepartition> lignes = repartirEtCreerTransactions(saved, farm, data.getQuantiteOeufs(), data.getMontant(), currentUser);

        if (data.getMontantRapporte() != null) {
            ajusterEcart(client, currentUser, farm, data.getMontant() - data.getMontantRapporte());
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
        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));

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

        if (data.getMontantRapporte() != null) {
            v.setMontantRapporte(data.getMontantRapporte());
        }

        if (data.getClientUniqueId() != null) {
            if (data.getClientUniqueId().isBlank()) {
                v.setClient(null);
            } else {
                Client client = clientRepo.findByUniqueId(data.getClientUniqueId());
                if (client == null) {
                    throw new IllegalArgumentException("Client introuvable : " + data.getClientUniqueId());
                }
                v.setClient(client);
            }
        }

        // Solde (vendeur OU client selon qui porte l'écart) : annule l'ancien écart
        // puis applique le nouveau — déclenché aussi si le client a changé (l'écart
        // doit alors migrer de sa cible précédente vers la nouvelle), pas seulement
        // si le montant/montant rapporté a changé. Le vendeur de référence reste celui
        // qui a créé la vente (v.creePar), pas celui qui modifie.
        String ancienClientId = ancienClient != null ? ancienClient.getUniqueId() : null;
        String nouveauClientId = v.getClient() != null ? v.getClient().getUniqueId() : null;
        boolean clientChanged = ancienClientId == null ? nouveauClientId != null : !ancienClientId.equals(nouveauClientId);
        boolean ecartChange = data.getMontantRapporte() != null || data.getMontant() != null;
        if (ecartChange || clientChanged) {
            if (ancienMontantRapporte != null) {
                ajusterEcart(ancienClient, v.getCreePar(), v.getFarm(), -(nz(ancienMontant) - ancienMontantRapporte));
            }
            if (v.getMontantRapporte() != null) {
                ajusterEcart(v.getClient(), v.getCreePar(), v.getFarm(), nz(v.getMontant()) - v.getMontantRapporte());
            }
        }

        if (v.getInitialisation() != null) {
            v.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        VenteOeufs saved = venteOeufsRepo.save(v);

        // Quantité et/ou montant modifiés : on refait la répartition à zéro (les
        // anciennes lignes et leurs Transactions sont retirées puis recréées) plutôt
        // que d'essayer de corriger les parts existantes au prorata — plus simple et
        // sans risque d'incohérence entre projets.
        List<VenteOeufsRepartition> lignesActuelles;
        if (redistribuer) {
            List<VenteOeufsRepartition> anciennes = repartitionRepo.findByVenteOeufs_UniqueId(saved.getUniqueId());
            for (VenteOeufsRepartition ancienne : anciennes) {
                transactionService.toggleRemovedBySource(ancienne.getUniqueId());
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

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs", "Modification d'une vente d'œufs");
        }

        VenteOeufsDTO dto = VenteOeufsDTO.fromEntity(saved);
        dto.setRepartitions(lignesActuelles.stream().map(VenteOeufsRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));

        v.getInitialisation().setRemoved(!v.getInitialisation().getRemoved());
        venteOeufsRepo.save(v);
        boolean removed = v.getInitialisation().getRemoved();

        for (VenteOeufsRepartition r : repartitionRepo.findByVenteOeufs_UniqueId(uniqueId)) {
            transactionService.toggleRemovedBySource(r.getUniqueId());
        }

        // Supprimer une vente annule aussi son impact sur le solde (vendeur ou client
        // selon qui le portait — et la restauration le réapplique) — sinon une dette
        // resterait comptée pour une vente qui n'existe plus.
        if ((v.getCreePar() != null || v.getClient() != null) && v.getMontantRapporte() != null) {
            double ecart = nz(v.getMontant()) - v.getMontantRapporte();
            ajusterEcart(v.getClient(), v.getCreePar(), v.getFarm(), removed ? -ecart : ecart);
        }

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteOeufs",
                    (removed ? "Suppression" : "Restauration") + " d'une vente d'œufs");
        }

        return removed ? "Vente supprimée." : "Vente récupérée.";
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

        // Reste un indicateur farm-wide global (utile en reporting admin) : collecté -
        // cassé - vendu, tous magasins confondus — distinct du stock par magasin
        // (voir MagasinService.getStock), qui seul plafonne une vente précise.
        int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByFarmId(farmId));
        int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByFarmId(farmId));
        int totalNonUtilisable = nz(collecteOeufsRepo.sumOeufsNonUtilisablesByFarmId(farmId));
        int totalVendu = nz(venteOeufsRepo.sumQuantiteByFarmId(farmId));
        int restant = (totalCollecte - totalCasse - totalNonUtilisable) - totalVendu;

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
