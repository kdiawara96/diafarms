package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.AlimentationDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Alimentation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.AlimentationRepo;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.ConsommationAlimentRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.request.create.AlimentationCreate;
import com.diafarms.ml.request.update.AlimentationUpdate;
import com.diafarms.ml.services.AlimentationService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AlimentationImpl implements AlimentationService {


    private final AlimentationRepo alimentationRepo;
    private final ProjetsRepo projetsRepo;
    private final BatimentRepo batimentRepo;
    private final ConsommationAlimentRepo consommationAlimentRepo;
    private final OtherService otherService;
    private final LogsServices logs;
    private final TransactionService transactionService;

    // Génère/synchronise la sortie comptable liée à cet achat d'aliment — voir
    // TransactionService.syncSortie : plus besoin de ressaisir le coût manuellement
    // en Comptabilité, la Transaction suit automatiquement coutTotal.
    private void syncTransaction(Alimentation a, Utilisateurs currentUser) {
        if (currentUser == null || currentUser.getFarm() == null) return;
        String description = "Achat aliment : " + a.getNomAliment() + " (" + a.getQuantiteKg() + " kg), projet "
                + (a.getProjet() != null ? a.getProjet().getTitre() : "?");
        // L'achat connaît son poulailler (facultatif) et son projet : la dépense les reprend
        // (poulailler de l'achat, site du projet) pour le suivi par poulailler / par site.
        transactionService.syncSortie(a.getProjet(), currentUser.getFarm(), a.getCoutTotal(), "Aliment",
                a.getDateDistribution(), description, SourceTransaction.ALIMENTATION, a.getUniqueId(), currentUser,
                a.getBatiment(), a.getProjet() != null ? a.getProjet().getSite() : null, true);
    }

    // --- Génération UID ---   
    private String generateUID() {
        return "ALI-" + java.util.UUID.randomUUID().toString();
    }

    // --- Récupération utilisateur safe ---
    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            System.err.println("Impossible de récupérer l'utilisateur connecté : " + e.getMessage());   
            return null;
        }
    }

    // L'achat d'aliment est un acte financier (il crée le stock ET la sortie d'argent) :
    // réservé à la finance, plus à la Production, qui ne fait que consommer et consulter
    // le stock. Même population que TransactionServiceImpl.ensureCanDemanderSuppression.
    private void ensureCanManageAchat(Utilisateurs u) {
        boolean ok = u != null && u.getRoles() != null && u.getRoles().stream().anyMatch(r ->
                "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole())
                        || "RESPONSABLE".equalsIgnoreCase(r.getRole()) || "COMPTABLE".equalsIgnoreCase(r.getRole()));
        if (!ok) {
            throw new IllegalArgumentException("Seule la finance (comptable, responsable ou administrateur) peut enregistrer, modifier ou supprimer un achat d'aliment.");
        }
    }

    // --- Log helper ---
    private void logAction(Utilisateurs currentUser, Alimentation alimentation, String message) {
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), alimentation.getId(), "Alimentation", message);
        }
    }

    // ============================================================
    // SAVE
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO save(AlimentationCreate data, String uniqueIdProjet) {
        // 1. Vérifier le projet
        Projets projet = projetsRepo.findByUniqueId(uniqueIdProjet)
                .orElseThrow(() -> new RuntimeException("Projet non trouvé avec l'UID : " + uniqueIdProjet));

        // 2. Récupérer l'utilisateur et sa ferme
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManageAchat(currentUser);
        if (data.getCoutTotal() == null || data.getCoutTotal() <= 0) {
            throw new IllegalArgumentException("Le coût total de l'achat est obligatoire.");
        }
        Farm farm = currentUser != null ? currentUser.getFarm() : null;

        // 3. Créer l'entité
        Alimentation alimentation = new Alimentation();
        alimentation.setUniqueId(generateUID());
        alimentation.setNomAliment(data.getNomAliment());
        alimentation.setSac(data.getSac());
        alimentation.setQuantiteKg(data.getQuantiteKg());
        alimentation.setCoutTotal(data.getCoutTotal());
        alimentation.setDateDistribution(
            data.getDateDistribution() != null
                ? LocalDate.parse(data.getDateDistribution())
                : LocalDate.now()
        );
        alimentation.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        alimentation.setObservations(data.getObservations());
        alimentation.setFournisseur(data.getFournisseur());
        alimentation.setProjet(projet);
        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            alimentation.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        alimentation.setFarm(farm);
        alimentation.setInitialisation(Initialisation.init());

        // 4. Sauvegarder
        Alimentation saved = alimentationRepo.save(alimentation);
        syncTransaction(saved, currentUser);

        // 5. Log
        logAction(currentUser, saved,
            "Création de l'alimentation '" + saved.getNomAliment()
                + "' (" + saved.getQuantiteKg() + " kg, " + saved.getSac() + " sacs) pour le projet '"
                + projet.getTitre() + "' | Coût total : " + saved.getCoutTotal() + " FCFA"
        );

        return AlimentationDTO.fromEntityList(saved);
    }

    // ============================================================
    // UPDATE
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO update(String uniqueId, AlimentationUpdate data) {
        ensureCanManageAchat(getCurrentUserSafe());
        // 1. Trouver l'alimentation
        Alimentation alimentation = alimentationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Alimentation non trouvée avec l'UID : " + uniqueId));

        // 2. Vérifier si non supprimée
        if (Boolean.TRUE.equals(alimentation.getInitialisation().getRemoved())) {
            throw new RuntimeException("Cette alimentation a été supprimée et ne peut pas être modifiée.");
        }

        // 3. Sauvegarder anciennes valeurs pour le log
        String ancienNom = alimentation.getNomAliment();
        Double ancienneQuantite = alimentation.getQuantiteKg();
        Double ancienCout = alimentation.getCoutTotal();

        // 4. Mettre à jour les champs
        if (data.getNomAliment() != null && !data.getNomAliment().trim().isEmpty()) {
            alimentation.setNomAliment(data.getNomAliment());
        }
        if (data.getSac() != null) {
            alimentation.setSac(data.getSac());
        }
        if (data.getQuantiteKg() != null) {
            if (data.getQuantiteKg() < ancienneQuantite) {
                Long projetId = alimentation.getProjet().getId();
                double totalAchete = alimentationRepo.sumAcheteByProjetId(projetId);
                double totalConsomme = consommationAlimentRepo.sumConsommeByProjetId(projetId);
                double nouveauTotalAchete = totalAchete - ancienneQuantite + data.getQuantiteKg();
                if (nouveauTotalAchete < totalConsomme) {
                    throw new RuntimeException(
                        "Impossible de réduire cet achat : le stock consommé (" + totalConsomme
                            + " kg) dépasserait le stock acheté (" + nouveauTotalAchete + " kg) pour ce projet."
                    );
                }
            }
            alimentation.setQuantiteKg(data.getQuantiteKg());
        }
        if (data.getCoutTotal() != null) {
            alimentation.setCoutTotal(data.getCoutTotal());
        }
        if (data.getDateDistribution() != null) {
            alimentation.setDateDistribution(
                LocalDate.parse(data.getDateDistribution())
            );
        }
        if (data.getObservations() != null) {
            alimentation.setObservations(data.getObservations());
        }
        if (data.getHeure() != null) {
            alimentation.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        }
        if (data.getBatimentUniqueId() != null) {
            alimentation.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (data.getFournisseur() != null) {
            alimentation.setFournisseur(data.getFournisseur());
        }

        // Mise à jour date
        alimentation.setInitialisation(Initialisation.updateDate(alimentation.getInitialisation()));

        // 5. Sauvegarder
        Alimentation updated = alimentationRepo.save(alimentation);

        // 6. Log
        Utilisateurs currentUser = getCurrentUserSafe();
        syncTransaction(updated, currentUser);
        logAction(currentUser, updated,
            "Modification de l'alimentation '" + ancienNom + "' → '" + updated.getNomAliment()
                + "' | Quantité : " + ancienneQuantite + " → " + updated.getQuantiteKg()
                + " kg | Coût : " + ancienCout + " → " + updated.getCoutTotal() + " FCFA"
        );

        return AlimentationDTO.fromEntityList(updated);
    }

    // ============================================================
    // DELETE (Soft Delete)
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO delete(String uniqueId) {
        ensureCanManageAchat(getCurrentUserSafe());
        // 1. Trouver l'alimentation
        Alimentation alimentation = alimentationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Alimentation non trouvée avec l'UID : " + uniqueId));

        // 2. Vérifier si déjà supprimée
        if (Boolean.TRUE.equals(alimentation.getInitialisation().getRemoved())) {
            throw new RuntimeException("Cette alimentation est déjà supprimée.");
        }

        // 3. Soft delete (le hard delete précédent effaçait définitivement la ligne,
        // incohérent avec le reste de l'app où tout est récupérable)
        alimentation.getInitialisation().setRemoved(true);
        alimentation.setInitialisation(Initialisation.updateDate(alimentation.getInitialisation()));

        Alimentation deleted = alimentationRepo.save(alimentation);
        transactionService.setRemovedBySource(deleted.getUniqueId(), true);

        // 4. Log
        Utilisateurs currentUser = getCurrentUserSafe();

        logAction(currentUser, deleted,
            "Suppression de l'alimentation '" + deleted.getNomAliment()
                + "' (" + deleted.getQuantiteKg() + " kg) du projet '"
                + deleted.getProjet().getTitre() + "'"
        );

        return AlimentationDTO.fromEntityList(deleted);
    }

    // ============================================================
    // LIST BY PROJECT
    // ============================================================
    @Override
    @Transactional(readOnly = true)
    public List<AlimentationDTO> findByProjetUniqueId(String uniqueIdProjet) {
        return alimentationRepo.findByProjetUniqueIdAndInitialisationRemovedFalse(uniqueIdProjet)
                .stream()
                .map(AlimentationDTO::fromEntityList)
                .toList();
    }


    // ============================================================
    // GET BY UNIQUE ID
    // ============================================================
    @Override
    @Transactional(readOnly = true)
    public AlimentationDTO findByUniqueId(String uniqueId) {
        Alimentation alimentation = alimentationRepo.findByUniqueIdAndInitialisationRemovedFalse(uniqueId)
                .orElseThrow(() -> new RuntimeException("Alimentation non trouvée avec l'UID : " + uniqueId));
        return AlimentationDTO.fromEntityList(alimentation);
    }

    // ============================================================
    // LIST GLOBAL (paginée, pour la page Production)
    // ============================================================
    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<AlimentationDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dateDistribution"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<Alimentation> resultPage = alimentationRepo.search(farmId, projetParam, batimentParam, searchParam, pageable);

        List<AlimentationDTO> dtoList = resultPage.getContent().stream()
                .map(AlimentationDTO::fromEntityList)
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }

}
