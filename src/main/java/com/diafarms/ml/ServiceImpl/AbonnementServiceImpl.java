package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.AbonnementConfigDTO;
import com.diafarms.ml.DTO.AbonnementDTO;
import com.diafarms.ml.DTO.PaiementAbonnementDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Periodicite;
import com.diafarms.ml.enums.StatutAbonnement;
import com.diafarms.ml.enums.StatutPaiementAbonnement;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.PaiementAbonnement;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.AbonnementRepo;
import com.diafarms.ml.repository.PaiementAbonnementRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.request.others.AbonnementConfigUpdateRequest;
import com.diafarms.ml.request.others.DeclarerPaiementAbonnementRequest;
import com.diafarms.ml.request.others.RejeterPaiementAbonnementRequest;
import com.diafarms.ml.services.AbonnementService;
import com.diafarms.ml.services.EmailService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AbonnementServiceImpl implements AbonnementService {

    private final AbonnementRepo abonnementRepo;
    private final PaiementAbonnementRepo paiementAbonnementRepo;
    private final AbonnementConfigRepo configRepo;
    private final UtilisateursRepo utilisateursRepo;
    private final EmailService emailService;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSuperAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private boolean isAdminOuResponsable(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "RESPONSABLE".equalsIgnoreCase(r.getRole()));
    }

    private void ensureSuperAdmin(Utilisateurs u) {
        if (!isSuperAdmin(u)) {
            throw new IllegalArgumentException("Seul un SUPER_ADMIN peut effectuer cette action.");
        }
    }

    // Ligne unique de config, créée avec des valeurs par défaut si absente — voir
    // AbonnementConfig.
    private AbonnementConfig getOuCreerConfig() {
        return configRepo.findAll().stream().findFirst().orElseGet(() -> {
            AbonnementConfig config = new AbonnementConfig();
            config.setPrixMensuel(15000.0);
            config.setPrixAnnuel(150000.0);
            config.setDureeEssaiJours(14);
            config.setDureeGraceHeures(24);
            return configRepo.save(config);
        });
    }

    @Override
    @Transactional
    public void creerEssaiPourFarm(Farm farm) {
        AbonnementConfig config = getOuCreerConfig();
        Abonnement abonnement = new Abonnement();
        abonnement.setUniqueId(UUID.randomUUID().toString());
        abonnement.setFarm(farm);
        abonnement.setStatut(StatutAbonnement.ESSAI);
        LocalDate aujourdHui = LocalDate.now();
        abonnement.setDateDebut(aujourdHui);
        abonnement.setDateFin(aujourdHui.plusDays(config.getDureeEssaiJours()));
        abonnement.setInitialisation(Initialisation.init());
        abonnementRepo.save(abonnement);
    }

    // Création paresseuse pour les fermes créées avant ce déploiement (voir spec,
    // section "Erreurs et cas limites") — essai complet à partir d'AUJOURD'HUI,
    // jamais rétroactif à la vraie date d'inscription de la ferme.
    private Abonnement getOuCreerAbonnement(Farm farm) {
        return abonnementRepo.findByFarm_Id(farm.getId()).orElseGet(() -> {
            creerEssaiPourFarm(farm);
            return abonnementRepo.findByFarm_Id(farm.getId())
                    .orElseThrow(() -> new IllegalStateException("Échec de création de l'abonnement."));
        });
    }

    // Voir spec, section "Calcul du statut effectif" : dateFin est un LocalDate (la
    // ferme reste active toute la journée indiquée), l'instant de coupure réel est
    // dateFin+1 jour à minuit, plus la grâce en heures.
    private record StatutCalcule(String statut, boolean enGrace, long joursRestants) {}

    private StatutCalcule calculerStatutEffectif(Abonnement abonnement, AbonnementConfig config) {
        LocalDateTime maintenant = LocalDateTime.now();
        LocalDateTime finJournee = abonnement.getDateFin().plusDays(1).atStartOfDay();
        LocalDateTime instantLimite = finJournee.plusHours(config.getDureeGraceHeures());
        long joursRestants = ChronoUnit.DAYS.between(LocalDate.now(), abonnement.getDateFin());

        boolean estEssai = abonnement.getPeriodicite() == null;
        if (maintenant.isBefore(finJournee)) {
            return new StatutCalcule(estEssai ? "ESSAI" : "ACTIF", false, joursRestants);
        }
        if (maintenant.isBefore(instantLimite)) {
            return new StatutCalcule(estEssai ? "ESSAI" : "ACTIF", true, joursRestants);
        }
        return new StatutCalcule("EXPIRE", false, joursRestants);
    }

    @Override
    @Transactional
    public AbonnementDTO getMoi() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return null; // SUPER_ADMIN, ou utilisateur non authentifié.
        }
        Farm farm = currentUser.getFarm();
        Abonnement abonnement = getOuCreerAbonnement(farm);
        AbonnementConfig config = getOuCreerConfig();

        StatutCalcule effectif = calculerStatutEffectif(abonnement, config);

        PaiementAbonnement enAttente = paiementAbonnementRepo
                .findByAbonnement_IdAndStatut(abonnement.getId(), StatutPaiementAbonnement.EN_ATTENTE)
                .orElse(null);

        return AbonnementDTO.of(abonnement, effectif.statut(), effectif.enGrace(), effectif.joursRestants(),
                PaiementAbonnementDTO.fromEntity(enAttente));
    }

    @Override
    @Transactional
    public PaiementAbonnementDTO declarerPaiement(DeclarerPaiementAbonnementRequest request) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (!isAdminOuResponsable(currentUser)) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut déclarer un paiement.");
        }
        if (request.getPeriodicite() == null || request.getMoyenPaiement() == null || request.getMoyenPaiement().isBlank()) {
            throw new IllegalArgumentException("Périodicité et moyen de paiement sont obligatoires.");
        }
        Periodicite periodicite;
        try {
            periodicite = Periodicite.valueOf(request.getPeriodicite().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Périodicité invalide (attendu MENSUEL ou ANNUEL) : " + request.getPeriodicite());
        }

        Abonnement abonnement = getOuCreerAbonnement(currentUser.getFarm());

        if (paiementAbonnementRepo.findByAbonnement_IdAndStatut(abonnement.getId(), StatutPaiementAbonnement.EN_ATTENTE).isPresent()) {
            throw new IllegalArgumentException("Une déclaration de paiement est déjà en attente de validation.");
        }

        AbonnementConfig config = getOuCreerConfig();
        double montant = periodicite == Periodicite.ANNUEL ? config.getPrixAnnuel() : config.getPrixMensuel();

        PaiementAbonnement paiement = new PaiementAbonnement();
        paiement.setUniqueId(UUID.randomUUID().toString());
        paiement.setAbonnement(abonnement);
        paiement.setMontant(montant);
        paiement.setPeriodicite(periodicite);
        paiement.setMoyenPaiement(request.getMoyenPaiement());
        paiement.setReference(request.getReference());
        paiement.setStatut(StatutPaiementAbonnement.EN_ATTENTE);
        paiement.setDateDeclaration(LocalDateTime.now());
        paiement.setDeclarePar(currentUser);
        paiement.setInitialisation(Initialisation.init());
        PaiementAbonnement saved = paiementAbonnementRepo.save(paiement);

        String farmNom = currentUser.getFarm().getNom() != null ? currentUser.getFarm().getNom() : currentUser.getFarm().getUniqueId();
        for (Utilisateurs superAdmin : utilisateursRepo.findAllSuperAdmins()) {
            emailService.sendAbonnementAValider(superAdmin.getEmail(), farmNom, montant,
                    periodicite.name(), request.getMoyenPaiement(), request.getReference());
        }

        return PaiementAbonnementDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<PaiementAbonnementDTO> listEnAttente(int page, int size) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        Pageable pageable = PageRequest.of(page, size);
        Page<PaiementAbonnement> resultPage = paiementAbonnementRepo
                .findByStatutOrderByDateDeclarationAsc(StatutPaiementAbonnement.EN_ATTENTE, pageable);

        return new PaginatedResponse<>(
                resultPage.getContent().stream().map(PaiementAbonnementDTO::fromEntity).toList(),
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }

    @Override
    @Transactional
    public PaiementAbonnementDTO valider(String paiementUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        PaiementAbonnement paiement = paiementAbonnementRepo.findByUniqueId(paiementUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Déclaration de paiement introuvable : " + paiementUniqueId));
        if (paiement.getStatut() != StatutPaiementAbonnement.EN_ATTENTE) {
            throw new IllegalArgumentException("Cette déclaration a déjà été traitée.");
        }

        Abonnement abonnement = paiement.getAbonnement();
        int joursAjoutes = paiement.getPeriodicite() == Periodicite.ANNUEL ? 365 : 30;
        // À partir de la plus tardive entre l'échéance actuelle et aujourd'hui : ne
        // fait jamais perdre de jours déjà payés (renouvellement en avance), ne
        // repart jamais dans le passé (ferme qui a laissé expirer).
        LocalDate base = abonnement.getDateFin().isAfter(LocalDate.now()) ? abonnement.getDateFin() : LocalDate.now();
        abonnement.setDateFin(base.plusDays(joursAjoutes));
        abonnement.setPeriodicite(paiement.getPeriodicite());
        abonnement.setStatut(StatutAbonnement.ACTIF);
        abonnementRepo.save(abonnement);

        paiement.setStatut(StatutPaiementAbonnement.VALIDE);
        paiement.setDateValidation(LocalDateTime.now());
        paiement.setValidePar(currentUser);
        PaiementAbonnement saved = paiementAbonnementRepo.save(paiement);

        if (paiement.getDeclarePar() != null) {
            String farmNom = abonnement.getFarm().getNom() != null ? abonnement.getFarm().getNom() : abonnement.getFarm().getUniqueId();
            emailService.sendAbonnementValide(paiement.getDeclarePar().getEmail(),
                    paiement.getDeclarePar().getFullName(), farmNom, abonnement.getDateFin());
        }

        return PaiementAbonnementDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public PaiementAbonnementDTO rejeter(String paiementUniqueId, RejeterPaiementAbonnementRequest request) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        PaiementAbonnement paiement = paiementAbonnementRepo.findByUniqueId(paiementUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Déclaration de paiement introuvable : " + paiementUniqueId));
        if (paiement.getStatut() != StatutPaiementAbonnement.EN_ATTENTE) {
            throw new IllegalArgumentException("Cette déclaration a déjà été traitée.");
        }

        paiement.setStatut(StatutPaiementAbonnement.REJETE);
        paiement.setDateValidation(LocalDateTime.now());
        paiement.setValidePar(currentUser);
        paiement.setMotifRejet(request != null ? request.getMotif() : null);
        return PaiementAbonnementDTO.fromEntity(paiementAbonnementRepo.save(paiement));
    }

    @Override
    @Transactional
    public AbonnementConfigDTO getConfig() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null) {
            throw new IllegalArgumentException("Utilisateur introuvable.");
        }
        return AbonnementConfigDTO.fromEntity(getOuCreerConfig());
    }

    @Override
    @Transactional
    public AbonnementConfigDTO updateConfig(AbonnementConfigUpdateRequest request) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        AbonnementConfig config = getOuCreerConfig();
        if (request.getPrixMensuel() != null) config.setPrixMensuel(request.getPrixMensuel());
        if (request.getPrixAnnuel() != null) config.setPrixAnnuel(request.getPrixAnnuel());
        if (request.getDureeEssaiJours() != null) config.setDureeEssaiJours(request.getDureeEssaiJours());
        if (request.getDureeGraceHeures() != null) config.setDureeGraceHeures(request.getDureeGraceHeures());
        return AbonnementConfigDTO.fromEntity(configRepo.save(config));
    }
}
