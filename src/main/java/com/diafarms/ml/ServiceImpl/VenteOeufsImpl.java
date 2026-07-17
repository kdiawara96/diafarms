package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteOeufsRepartition;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.VenteOeufsRepartitionRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.update.VenteOeufsUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteOeufsService;

import lombok.RequiredArgsConstructor;

// Vente d'œufs (Finance) : acte commercial à l'échelle de la ferme entière, plafonné
// par le total collecté (CollecteOeufs, Production) de TOUTE LA FERME moins déjà
// vendu. PAS rattachée à un seul projet : répartie automatiquement au prorata du
// stock disponible de chaque projet contributeur (RepartitionUtil), pour que le
// chiffre d'affaires par projet (computeChiffreAffairesReel) reste exact — chaque
// part génère sa propre Transaction "entrée", attribuée directement à SON projet.
@Service
@RequiredArgsConstructor
public class VenteOeufsImpl implements VenteOeufsService {

    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteOeufsRepartitionRepo repartitionRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;
    private final ProjetsRepo projetsRepo;
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

    private int stockFermeRestant(Long farmId) {
        int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByFarmId(farmId));
        int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByFarmId(farmId));
        int totalVendu = nz(venteOeufsRepo.sumQuantiteByFarmId(farmId));
        return (totalCollecte - totalCasse) - totalVendu;
    }

    /** Stock d'œufs vendables restant, projet par projet, pour tous les projets actifs
     * de la ferme — sert de poids pour la répartition proportionnelle d'une vente. */
    private Map<Long, Integer> disponibleParProjet(List<Projets> projets) {
        Map<Long, Integer> disponible = new LinkedHashMap<>();
        for (Projets p : projets) {
            int collecte = nz(collecteOeufsRepo.sumOeufsCollectesByProjetId(p.getId()));
            int casse = nz(collecteOeufsRepo.sumOeufsCassesByProjetId(p.getId()));
            int vendu = nz(repartitionRepo.sumQuantiteByProjetId(p.getId()));
            int restant = (collecte - casse) - vendu;
            if (restant > 0) disponible.put(p.getId(), restant);
        }
        return disponible;
    }

    /** Répartit la vente entre les projets contributeurs, sauvegarde les lignes de
     * VenteOeufsRepartition et génère une Transaction par projet — factorisé pour
     * être appelé identiquement par create() et update(). Retourne les lignes créées
     * (plutôt que de compter sur saved.getRepartitions(), lazy et potentiellement pas
     * à jour dans le même contexte de persistance/transaction). */
    private List<VenteOeufsRepartition> repartirEtCreerTransactions(VenteOeufs saved, Farm farm, int quantite, double montant) {
        List<Projets> projetsActifs = projetsRepo.findAllActiveByFarm(farm.getId());
        Map<Long, Integer> disponible = disponibleParProjet(projetsActifs);
        Map<Long, Projets> projetsParId = projetsActifs.stream()
                .collect(java.util.stream.Collectors.toMap(Projets::getId, p -> p));

        List<RepartitionUtil.Part> parts = RepartitionUtil.repartir(quantite, montant, disponible);
        List<VenteOeufsRepartition> lignes = new java.util.ArrayList<>();

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
                    projet, farm, part.montant, "Vente œufs", saved.getDate(),
                    "Vente de " + part.quantite + " œufs (part de " + saved.getQuantiteOeufs() + " vendus)",
                    SourceTransaction.VENTE_OEUFS, r.getUniqueId()
            );
        }
        return lignes;
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

        int restant = stockFermeRestant(farm.getId());
        if (data.getQuantiteOeufs() > restant) {
            throw new IllegalArgumentException(
                "Stock d'œufs insuffisant pour la ferme (" + restant + " œuf(s) restants)."
            );
        }

        VenteOeufs v = new VenteOeufs();
        v.setUniqueId(java.util.UUID.randomUUID().toString());
        v.setFarm(farm);
        v.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        v.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        v.setQuantiteOeufs(data.getQuantiteOeufs());
        v.setPrixUnitaire(data.getPrixUnitaire());
        v.setMontant(data.getMontant());
        v.setInitialisation(Initialisation.init());

        VenteOeufs saved = venteOeufsRepo.save(v);

        List<VenteOeufsRepartition> lignes = repartirEtCreerTransactions(saved, farm, data.getQuantiteOeufs(), data.getMontant());

        logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs",
                "Vente de " + saved.getQuantiteOeufs() + " œufs (" + saved.getMontant() + " FCFA), répartie entre les projets contributeurs");

        VenteOeufsDTO dto = VenteOeufsDTO.fromEntity(saved);
        dto.setRepartitions(lignes.stream().map(VenteOeufsRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public VenteOeufsDTO update(String uniqueId, VenteOeufsUpdate data) {
        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));

        if (data.getDate() != null) v.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) v.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getPrixUnitaire() != null) v.setPrixUnitaire(data.getPrixUnitaire());

        boolean redistribuer = data.getQuantiteOeufs() != null || data.getMontant() != null;

        if (data.getQuantiteOeufs() != null) {
            if (data.getQuantiteOeufs() <= 0) {
                throw new IllegalArgumentException("La quantité d'œufs vendus doit être positive.");
            }
            Long farmId = v.getFarm().getId();
            int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByFarmId(farmId));
            int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByFarmId(farmId));
            int totalVendu = nz(venteOeufsRepo.sumQuantiteByFarmId(farmId));
            int ancienneQuantite = nz(v.getQuantiteOeufs());
            int nouveauTotalVendu = totalVendu - ancienneQuantite + data.getQuantiteOeufs();
            if (nouveauTotalVendu > (totalCollecte - totalCasse)) {
                throw new IllegalArgumentException(
                    "Stock d'œufs insuffisant pour la ferme (" + ((totalCollecte - totalCasse) - (totalVendu - ancienneQuantite)) + " œuf(s) restants)."
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
            lignesActuelles = repartirEtCreerTransactions(saved, saved.getFarm(), saved.getQuantiteOeufs(), saved.getMontant());
        } else {
            lignesActuelles = repartitionRepo.findByVenteOeufs_UniqueId(saved.getUniqueId());
        }

        Utilisateurs currentUser = getCurrentUserSafe();
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

        int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByFarmId(farmId));
        int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByFarmId(farmId));
        int totalVendu = nz(venteOeufsRepo.sumQuantiteByFarmId(farmId));
        int restant = (totalCollecte - totalCasse) - totalVendu;

        return StockOeufsDTO.builder()
                .totalCollecte(totalCollecte)
                .totalCasse(totalCasse)
                .totalVendu(totalVendu)
                .stockRestant(restant)
                .statut(restant <= 0 ? "EPUISE" : "ACTIF")
                .build();
    }
}
