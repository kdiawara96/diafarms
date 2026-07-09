package com.diafarms.ml.ServiceImpl;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.DTO.TransactionStatsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Transaction;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.TransactionRepo;
import com.diafarms.ml.request.create.TransactionCreate;
import com.diafarms.ml.request.others.RejectTransactionRequest;
import com.diafarms.ml.request.update.TransactionUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepo transactionRepo;
    private final ProjetsRepo projetsRepo;
    private final LogsServices logs;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String generateRef() {
        String ref;
        do {
            ref = "TRX-" + String.format("%04d", (int) (Math.random() * 9999));
        } while (transactionRepo.existsByRef(ref));
        return ref;
    }

    @Override
    @Transactional
    public TransactionDTO create(TransactionCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();

        Transaction t = new Transaction();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setRef(generateRef());
        t.setType(TypeTransaction.valueOf(data.getType()));
        t.setDate(data.getDate() != null ? data.getDate() : java.time.LocalDate.now());
        t.setDescription(data.getDescription());
        t.setMontant(data.getMontant());
        t.setCategorie(data.getCategorie());
        t.setStatut(StatutTransaction.EN_ATTENTE);
        t.setInitialisation(Initialisation.init());

        if (data.getProjetUniqueId() != null && !data.getProjetUniqueId().isBlank()) {
            Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));
            t.setProjet(projet);
        }

        if (currentUser != null) {
            t.setFarm(currentUser.getFarm());
        }

        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Création de la transaction '" + saved.getRef() + "' (" + saved.getType() + ", " + saved.getMontant() + " FCFA)");
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO update(String uniqueId, TransactionUpdate data) {
        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));

        if (data.getType() != null) t.setType(TypeTransaction.valueOf(data.getType()));
        if (data.getDate() != null) t.setDate(data.getDate());
        if (data.getDescription() != null) t.setDescription(data.getDescription());
        if (data.getMontant() != null) t.setMontant(data.getMontant());
        if (data.getCategorie() != null) t.setCategorie(data.getCategorie());
        if (data.getProjetUniqueId() != null) {
            if (data.getProjetUniqueId().isBlank()) {
                t.setProjet(null);
            } else {
                Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                        .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));
                t.setProjet(projet);
            }
        }
        if (t.getInitialisation() != null) {
            t.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }

        Transaction saved = transactionRepo.save(t);

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Modification de la transaction '" + saved.getRef() + "'");
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));

        t.getInitialisation().setRemoved(!t.getInitialisation().getRemoved());
        transactionRepo.save(t);
        boolean removed = t.getInitialisation().getRemoved();

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), t.getId(), "Transaction",
                    (removed ? "Suppression" : "Restauration") + " de la transaction '" + t.getRef() + "'");
        }

        return removed ? "Transaction supprimée." : "Transaction récupérée.";
    }

    @Override
    @Transactional
    public TransactionDTO valider(String uniqueId) {
        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));

        Utilisateurs currentUser = getCurrentUserSafe();

        t.setStatut(StatutTransaction.VALIDE);
        t.setCommentaireRejet(null);
        t.setValidateur(currentUser);
        t.setDateValidation(LocalDateTime.now());
        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Validation de la transaction '" + saved.getRef() + "'");
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO rejeter(String uniqueId, RejectTransactionRequest data) {
        if (data.getCommentaire() == null || data.getCommentaire().isBlank()) {
            throw new IllegalArgumentException("Un commentaire est obligatoire pour rejeter une transaction.");
        }

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));

        Utilisateurs currentUser = getCurrentUserSafe();

        t.setStatut(StatutTransaction.REJETE);
        t.setCommentaireRejet(data.getCommentaire());
        t.setValidateur(currentUser);
        t.setDateValidation(LocalDateTime.now());
        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Rejet de la transaction '" + saved.getRef() + "' : " + data.getCommentaire());
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<TransactionDTO> list(int page, int size, String search, TypeTransaction type, StatutTransaction statut) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createdAt"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";

        Page<Transaction> transactionsPage = transactionRepo.search(farmId, type, statut, searchParam, pageable);

        List<TransactionDTO> dtoList = transactionsPage.getContent().stream()
                .map(TransactionDTO::fromEntity)
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                transactionsPage.getNumber() + 1,
                transactionsPage.getTotalPages(),
                transactionsPage.getTotalElements(),
                transactionsPage.getSize()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionStatsDTO getStats() {
        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        long nbValide = transactionRepo.countByFarmIdAndStatut(farmId, StatutTransaction.VALIDE);
        long nbAttente = transactionRepo.countByFarmIdAndStatut(farmId, StatutTransaction.EN_ATTENTE);
        long nbRejete = transactionRepo.countByFarmIdAndStatut(farmId, StatutTransaction.REJETE);
        Double totalEntrees = transactionRepo.sumMontantValideByType(farmId, TypeTransaction.ENTREE);
        Double totalSorties = transactionRepo.sumMontantValideByType(farmId, TypeTransaction.SORTIE);

        return TransactionStatsDTO.builder()
                .nbValide(nbValide)
                .nbAttente(nbAttente)
                .nbRejete(nbRejete)
                .totalEntreesValidees(totalEntrees != null ? totalEntrees : 0.0)
                .totalSortiesValidees(totalSorties != null ? totalSorties : 0.0)
                .build();
    }
}
