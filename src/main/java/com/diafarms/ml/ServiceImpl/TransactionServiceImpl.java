package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
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
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Farm;
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

    private boolean isAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    /**
     * Restriction du RAPPORT (/transactions/stats, qui alimente les cartes KPI et le
     * "Rapport général" de Comptabilité) : null = pas de restriction (vue ferme entière,
     * réservé à un ADMIN/SUPER_ADMIN qui n'a pas choisi de financier précis) ; liste
     * (éventuellement vide) = restreint aux transactions des projets où l'utilisateur
     * ciblé est responsableFinance.
     *
     * Un utilisateur non-admin (FINANCIER) est TOUJOURS restreint à son propre périmètre,
     * quel que soit financierUniqueId reçu du client — jamais celui d'un autre financier,
     * sinon il suffirait de changer ce paramètre pour voir le rapport d'un collègue. Seul
     * un ADMIN/SUPER_ADMIN peut se placer dans la vue d'un financier choisi ("voir comme").
     */
    private List<Long> resolveProjetIdsScope(Utilisateurs currentUser, Long farmId, String financierUniqueId) {
        if (currentUser == null || farmId == null) {
            return isAdmin(currentUser) ? null : List.of();
        }
        if (isAdmin(currentUser)) {
            if (financierUniqueId == null || financierUniqueId.isBlank()) {
                return null;
            }
            return projetsRepo.findProjetIdsAssignedAsFinanceToUser(farmId, financierUniqueId);
        }
        return projetsRepo.findProjetIdsAssignedAsFinanceToUser(farmId, currentUser.getUniqueId());
    }

    /**
     * Restriction de la LISTE (/transactions/list) : contrairement au rapport ci-dessus,
     * la liste n'est JAMAIS auto-restreinte pour un non-admin — elle reste utilisée telle
     * quelle par la page Ventes (acte Finance à l'échelle de la ferme entière par
     * conception, voir VenteOeufsImpl/VenteReformeImpl) et par le Dashboard admin. Seul un
     * ADMIN/SUPER_ADMIN qui fournit explicitement financierUniqueId se place dans la vue
     * scopée d'un financier (utilisé par le filtre "voir comme" de Comptabilité).
     */
    private List<Long> resolveProjetIdsScopeForList(Utilisateurs currentUser, Long farmId, String financierUniqueId) {
        if (!isAdmin(currentUser) || farmId == null || financierUniqueId == null || financierUniqueId.isBlank()) {
            return null;
        }
        return projetsRepo.findProjetIdsAssignedAsFinanceToUser(farmId, financierUniqueId);
    }

    // FINANCIER est son SEUL rôle (pas de cumul, ex: PRODUCTEUR + FINANCIER) : un
    // compte qui cumule les rôles garde l'accès complet, un autre rôle justifiant
    // déjà l'accès non restreint (même règle que hasOnlyRole côté front, voir
    // src/lib/roles.ts).
    private boolean isPureFinancier(Utilisateurs u) {
        return u != null && u.getRoles() != null && !u.getRoles().isEmpty()
                && u.getRoles().stream().allMatch(r -> "FINANCIER".equalsIgnoreCase(r.getRole()));
    }

    /**
     * Restriction par CRÉATEUR de la LISTE (page Ventes) : un FINANCIER pur ne voit
     * que ses propres ventes, quel que soit vendeurUniqueId reçu du client — jamais
     * celui d'un collègue. Un ADMIN/SUPER_ADMIN peut choisir n'importe quel vendeur
     * (ou aucun = tout le monde). Un PRODUCTEUR (ou un cumul de rôles) n'est pas
     * restreint pour l'instant.
     */
    private String resolveVendeurScopeForList(Utilisateurs currentUser, String vendeurUniqueId) {
        if (isAdmin(currentUser)) {
            return (vendeurUniqueId == null || vendeurUniqueId.isBlank()) ? null : vendeurUniqueId;
        }
        if (isPureFinancier(currentUser)) {
            return currentUser.getUniqueId();
        }
        return null;
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
        // Une saisie faite par un ADMIN (côté web, en pratique — le mobile ne propose
        // aucun écran de saisie à un compte ADMIN seul, voir HomeActivity.setupVisibilityByRole)
        // est déjà validée : ce n'est qu'une saisie terrain (Producteur/Financier, mobile)
        // qui doit d'abord passer par la validation manuelle habituelle.
        boolean estAdmin = isAdmin(currentUser);
        t.setStatut(estAdmin ? StatutTransaction.VALIDE : StatutTransaction.EN_ATTENTE);
        if (estAdmin) {
            // Même trace que la validation manuelle (voir valider()) : sans ça, "Dernière
            // décision par" resterait vide pour une transaction pourtant déjà validée.
            t.setValidateur(currentUser);
            t.setDateValidation(LocalDateTime.now());
        }
        t.setCreePar(currentUser);
        t.setInitialisation(Initialisation.init());

        boolean commun = !Boolean.FALSE.equals(data.getCommun())
                && (Boolean.TRUE.equals(data.getCommun()) || data.getProjetUniqueId() == null || data.getProjetUniqueId().isBlank());

        if (!commun) {
            Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));
            t.setProjet(projet);
        } else if (data.getProjetsConcernesUniqueIds() != null && !data.getProjetsConcernesUniqueIds().isEmpty()) {
            t.setProjetsConcernes(projetsRepo.findByUniqueIdIn(data.getProjetsConcernesUniqueIds()));
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
    public TransactionDTO createFromSource(Projets projet, Farm farm, Double montant, String categorie, java.time.LocalDate date,
                                            String description, SourceTransaction sourceType, String sourceUniqueId, Utilisateurs creePar) {
        Transaction t = new Transaction();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setRef(generateRef());
        t.setType(TypeTransaction.ENTREE);
        t.setDate(date != null ? date : java.time.LocalDate.now());
        t.setDescription(description);
        t.setMontant(montant);
        t.setCategorie(categorie);
        // Contrairement à une transaction manuelle (create() ci-dessus), une transaction
        // générée depuis une vente d'œufs/réforme (voir VenteOeufsImpl/VenteReformeImpl)
        // n'est pas une simple déclaration à vérifier : elle est bornée par le stock réel
        // déjà validé (impossible de survendre, voir repartirEtCreerTransactions) — elle
        // n'a donc pas besoin d'une validation manuelle séparée pour compter dans
        // "Total entrées". Avant ce correctif, une vente restait invisible en
        // comptabilité tant que quelqu'un ne validait pas sa transaction à la main.
        t.setStatut(StatutTransaction.VALIDE);
        t.setDateValidation(LocalDateTime.now());
        t.setProjet(projet);
        t.setSourceType(sourceType);
        t.setSourceUniqueId(sourceUniqueId);
        t.setFarm(farm);
        t.setCreePar(creePar);
        t.setInitialisation(Initialisation.init());

        Transaction saved = transactionRepo.save(t);
        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void toggleRemovedBySource(String sourceUniqueId) {
        transactionRepo.findBySourceUniqueId(sourceUniqueId).ifPresent(t -> {
            t.getInitialisation().setRemoved(!t.getInitialisation().getRemoved());
            transactionRepo.save(t);
        });
    }

    @Override
    @Transactional
    public void updateMontantBySource(String sourceUniqueId, Double montant) {
        transactionRepo.findBySourceUniqueId(sourceUniqueId).ifPresent(t -> {
            t.setMontant(montant);
            t.getInitialisation().setUpdatedAt(LocalDateTime.now());
            transactionRepo.save(t);
        });
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
        if (Boolean.TRUE.equals(data.getCommun())) {
            t.setProjet(null);
            t.setProjetsConcernes(data.getProjetsConcernesUniqueIds() != null && !data.getProjetsConcernesUniqueIds().isEmpty()
                    ? projetsRepo.findByUniqueIdIn(data.getProjetsConcernesUniqueIds())
                    : new java.util.ArrayList<>());
        } else if (Boolean.FALSE.equals(data.getCommun())) {
            if (data.getProjetUniqueId() == null || data.getProjetUniqueId().isBlank()) {
                throw new IllegalArgumentException("Un projet doit être sélectionné si la transaction n'est pas commune.");
            }
            Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));
            t.setProjet(projet);
            t.setProjetsConcernes(new java.util.ArrayList<>());
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
        Utilisateurs currentUser = getCurrentUserSafe();
        if (!isAdmin(currentUser)) {
            throw new IllegalArgumentException("Seul un administrateur peut valider une transaction.");
        }

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));

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
        Utilisateurs currentUser = getCurrentUserSafe();
        if (!isAdmin(currentUser)) {
            throw new IllegalArgumentException("Seul un administrateur peut rejeter une transaction.");
        }
        if (data.getCommentaire() == null || data.getCommentaire().isBlank()) {
            throw new IllegalArgumentException("Un commentaire est obligatoire pour rejeter une transaction.");
        }

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));

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
    public PaginatedResponse<TransactionDTO> list(int page, int size, String search, TypeTransaction type, StatutTransaction statut,
                                                   String projetUniqueId, String financierUniqueId, String vendeurUniqueId,
                                                   LocalDate dateDebut, LocalDate dateFin) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createdAt"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;

        String vendeurScope = resolveVendeurScopeForList(currentUser, vendeurUniqueId);
        List<Long> scopedProjetIds = resolveProjetIdsScopeForList(currentUser, farmId, financierUniqueId);

        Page<Transaction> transactionsPage;
        if (vendeurScope != null) {
            transactionsPage = transactionRepo.searchByCreePar(farmId, vendeurScope, type, statut, dateDebut, dateFin, searchParam, pageable);
        } else if (scopedProjetIds != null && scopedProjetIds.isEmpty()) {
            transactionsPage = Page.empty(pageable);
        } else if (scopedProjetIds != null) {
            transactionsPage = transactionRepo.searchScoped(scopedProjetIds, type, statut, projetParam, dateDebut, dateFin, searchParam, pageable);
        } else {
            transactionsPage = transactionRepo.search(farmId, type, statut, projetParam, dateDebut, dateFin, searchParam, pageable);
        }

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
    public TransactionStatsDTO getStats(String financierUniqueId, LocalDate dateDebut, LocalDate dateFin) {
        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        List<Long> scopedProjetIds = resolveProjetIdsScope(currentUser, farmId, financierUniqueId);

        if (scopedProjetIds != null && scopedProjetIds.isEmpty()) {
            return TransactionStatsDTO.builder()
                    .nbValide(0).nbAttente(0).nbRejete(0)
                    .totalEntreesValidees(0.0).totalSortiesValidees(0.0)
                    .totalVenteOeufs(0.0).totalVenteReforme(0.0)
                    .build();
        }

        long nbValide, nbAttente, nbRejete;
        Double totalEntrees, totalSorties, totalVenteOeufs, totalVenteReforme;

        if (scopedProjetIds != null) {
            nbValide = transactionRepo.countByProjetIdsAndStatut(scopedProjetIds, StatutTransaction.VALIDE, dateDebut, dateFin);
            nbAttente = transactionRepo.countByProjetIdsAndStatut(scopedProjetIds, StatutTransaction.EN_ATTENTE, dateDebut, dateFin);
            nbRejete = transactionRepo.countByProjetIdsAndStatut(scopedProjetIds, StatutTransaction.REJETE, dateDebut, dateFin);
            totalEntrees = transactionRepo.sumMontantValideByProjetIdsAndType(scopedProjetIds, TypeTransaction.ENTREE, dateDebut, dateFin);
            totalSorties = transactionRepo.sumMontantValideByProjetIdsAndType(scopedProjetIds, TypeTransaction.SORTIE, dateDebut, dateFin);
            totalVenteOeufs = transactionRepo.sumMontantValideByProjetIdsAndSourceType(scopedProjetIds, SourceTransaction.VENTE_OEUFS, dateDebut, dateFin);
            totalVenteReforme = transactionRepo.sumMontantValideByProjetIdsAndSourceType(scopedProjetIds, SourceTransaction.VENTE_REFORME, dateDebut, dateFin);
        } else {
            nbValide = transactionRepo.countByFarmIdAndStatutAndDateRange(farmId, StatutTransaction.VALIDE, dateDebut, dateFin);
            nbAttente = transactionRepo.countByFarmIdAndStatutAndDateRange(farmId, StatutTransaction.EN_ATTENTE, dateDebut, dateFin);
            nbRejete = transactionRepo.countByFarmIdAndStatutAndDateRange(farmId, StatutTransaction.REJETE, dateDebut, dateFin);
            totalEntrees = transactionRepo.sumMontantValideByTypeAndDateRange(farmId, TypeTransaction.ENTREE, dateDebut, dateFin);
            totalSorties = transactionRepo.sumMontantValideByTypeAndDateRange(farmId, TypeTransaction.SORTIE, dateDebut, dateFin);
            totalVenteOeufs = transactionRepo.sumMontantValideBySourceTypeAndDateRange(farmId, SourceTransaction.VENTE_OEUFS, dateDebut, dateFin);
            totalVenteReforme = transactionRepo.sumMontantValideBySourceTypeAndDateRange(farmId, SourceTransaction.VENTE_REFORME, dateDebut, dateFin);
        }

        return TransactionStatsDTO.builder()
                .nbValide(nbValide)
                .nbAttente(nbAttente)
                .nbRejete(nbRejete)
                .totalEntreesValidees(totalEntrees != null ? totalEntrees : 0.0)
                .totalSortiesValidees(totalSorties != null ? totalSorties : 0.0)
                .totalVenteOeufs(totalVenteOeufs != null ? totalVenteOeufs : 0.0)
                .totalVenteReforme(totalVenteReforme != null ? totalVenteReforme : 0.0)
                .build();
    }
}
