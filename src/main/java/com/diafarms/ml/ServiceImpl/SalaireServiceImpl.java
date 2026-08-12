package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.PaiementSalaireDTO;
import com.diafarms.ml.DTO.SalaireDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.PaiementSalaire;
import com.diafarms.ml.models.Salaire;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.PaiementSalaireRepo;
import com.diafarms.ml.repository.SalaireRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.request.create.SalaireDefinirRequest;
import com.diafarms.ml.request.others.SalairePayerRequest;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.SalaireService;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

// Salaire = fiche salariale d'un employé (base mensuelle) ; PaiementSalaire = un
// paiement réel pour une période — voir Salaire.java/PaiementSalaire.java. "Payer le
// salaire" génère une vraie Transaction (TransactionService.createSortieCommune)
// plutôt que de laisser ressaisir une transaction manuelle non structurée. Permissions
// alignées sur FactureServiceImpl : gestion RH/finance réservée à
// ADMIN/RESPONSABLE/COMPTABLE, pas VENTE/PRODUCTION.
@Service
@RequiredArgsConstructor
public class SalaireServiceImpl implements SalaireService {

    private final SalaireRepo salaireRepo;
    private final PaiementSalaireRepo paiementSalaireRepo;
    private final UtilisateursRepo utilisateursRepo;
    private final TransactionService transactionService;
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
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour gérer les salaires.");
        }
    }

    @Override
    @Transactional
    public SalaireDTO definir(SalaireDefinirRequest data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (data.getEmployeUniqueId() == null || data.getEmployeUniqueId().isBlank()) {
            throw new IllegalArgumentException("L'employé est obligatoire.");
        }
        if (data.getMontantMensuel() == null || data.getMontantMensuel() <= 0) {
            throw new IllegalArgumentException("Le salaire mensuel doit être positif.");
        }
        Utilisateurs employe = utilisateursRepo.findByUniqueId(data.getEmployeUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Employé introuvable : " + data.getEmployeUniqueId()));

        Salaire s = salaireRepo.findByEmploye_UniqueIdAndFarm_Id(data.getEmployeUniqueId(), currentUser.getFarm().getId());
        boolean nouveau = (s == null);
        if (nouveau) {
            s = new Salaire();
            s.setUniqueId(java.util.UUID.randomUUID().toString());
            s.setEmploye(employe);
            s.setFarm(currentUser.getFarm());
            s.setInitialisation(Initialisation.init());
        } else if (s.getInitialisation() != null) {
            s.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }
        s.setMontantMensuel(data.getMontantMensuel());

        Salaire saved = salaireRepo.save(s);
        logs.addLogs(currentUser.getId(), saved.getId(), "Salaire",
                (nouveau ? "Salaire de base défini pour " : "Salaire de base mis à jour pour ") + employe.getFullName()
                        + " (" + data.getMontantMensuel() + " FCFA/mois)");
        return SalaireDTO.fromEntity(saved, paiementSalaireRepo.findFirstBySalaire_IdOrderByPeriodeDesc(saved.getId()));
    }

    @Override
    @Transactional
    public PaiementSalaireDTO payer(SalairePayerRequest data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (data.getEmployeUniqueId() == null || data.getEmployeUniqueId().isBlank()) {
            throw new IllegalArgumentException("L'employé est obligatoire.");
        }
        if (data.getPeriode() == null || !data.getPeriode().matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("La période est obligatoire (format AAAA-MM).");
        }

        Salaire s = salaireRepo.findByEmploye_UniqueIdAndFarm_Id(data.getEmployeUniqueId(), currentUser.getFarm().getId());
        if (s == null) {
            throw new IllegalArgumentException("Aucun salaire de base défini pour cet employé — définissez-le d'abord.");
        }
        if (paiementSalaireRepo.existsBySalaire_IdAndPeriode(s.getId(), data.getPeriode())) {
            throw new IllegalArgumentException("Le salaire de " + data.getPeriode() + " a déjà été payé pour " + s.getEmploye().getFullName() + ".");
        }
        double montant = (data.getMontant() != null && data.getMontant() > 0) ? data.getMontant() : s.getMontantMensuel();

        PaiementSalaire p = new PaiementSalaire();
        p.setUniqueId(java.util.UUID.randomUUID().toString());
        p.setSalaire(s);
        p.setPeriode(data.getPeriode());
        p.setMontantPaye(montant);
        p.setDatePaiement(LocalDate.now());
        p.setCreePar(currentUser);
        p.setInitialisation(Initialisation.init());
        PaiementSalaire saved = paiementSalaireRepo.save(p);

        String description = (data.getDescription() != null && !data.getDescription().isBlank())
                ? data.getDescription()
                : "Salaire " + data.getPeriode() + " — " + s.getEmploye().getFullName();
        transactionService.createSortieCommune(currentUser.getFarm(), montant, "Salaires", LocalDate.now(),
                description, SourceTransaction.SALAIRE, saved.getUniqueId(), currentUser);

        logs.addLogs(currentUser.getId(), saved.getId(), "PaiementSalaire",
                "Salaire de " + montant + " FCFA payé à " + s.getEmploye().getFullName() + " pour " + data.getPeriode());
        return PaiementSalaireDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<SalaireDTO> list(int page, int size) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "employe.fullName"));
        Page<Salaire> salairePage = salaireRepo.search(currentUser.getFarm().getId(), pageable);
        List<SalaireDTO> dtoList = salairePage.getContent().stream()
                .map(s -> SalaireDTO.fromEntity(s, paiementSalaireRepo.findFirstBySalaire_IdOrderByPeriodeDesc(s.getId())))
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                salairePage.getNumber() + 1,
                salairePage.getTotalPages(),
                salairePage.getTotalElements(),
                salairePage.getSize()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<PaiementSalaireDTO> listPaiements(String employeUniqueId, int page, int size) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }
        Salaire s = salaireRepo.findByEmploye_UniqueIdAndFarm_Id(employeUniqueId, currentUser.getFarm().getId());
        if (s == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periode"));
        Page<PaiementSalaire> paiementPage = paiementSalaireRepo.findBySalaireId(s.getId(), pageable);
        List<PaiementSalaireDTO> dtoList = paiementPage.getContent().stream().map(PaiementSalaireDTO::fromEntity).toList();

        return new PaginatedResponse<>(
                dtoList,
                paiementPage.getNumber() + 1,
                paiementPage.getTotalPages(),
                paiementPage.getTotalElements(),
                paiementPage.getSize()
        );
    }
}
