package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    // Auto-injection paresseuse : nécessaire pour que l'appel à
    // creerEssaiPourFarmIsole depuis getOuCreerAbonnement passe par le proxy Spring
    // (voir plus bas) — un appel this.creerEssaiPourFarmIsole(...) ignorerait
    // complètement son @Transactional et l'exécuterait dans la transaction ambiante
    // de l'appelant, ce qui rendrait le rattrapage de la course de création (voir
    // getOuCreerAbonnement) inefficace : Postgres avorte toute la transaction sur
    // une violation de contrainte, donc la relecture qui suit échouerait elle
    // aussi. Type concret (pas l'interface AbonnementService) car
    // creerEssaiPourFarmIsole est un détail d'implémentation interne, pas exposé
    // sur le contrat public du service. @Lazy évite la référence circulaire au
    // moment de la construction du bean.
    @Autowired
    @Lazy
    private AbonnementServiceImpl self;

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

    // Farm.nom est null par construction pour une ferme fraîchement inscrite (le nom
    // saisi à l'inscription est stocké sur Utilisateurs.farmName, jamais recopié sur
    // Farm tant que l'ADMIN n'a pas visité Paramètres → Identité de la ferme) — un
    // UUID brut n'aide personne à identifier la ferme dans le portail SUPER_ADMIN ou
    // l'objet d'un email, donc on retombe sur le nom saisi à l'inscription avant
    // l'UUID en dernier recours.
    private String resoudreFarmNom(Farm farm, String nomUtilisateurFallback) {
        if (farm.getNom() != null) {
            return farm.getNom();
        }
        if (nomUtilisateurFallback != null) {
            return nomUtilisateurFallback;
        }
        return farm.getUniqueId();
    }

    // Ligne unique de config, créée avec des valeurs par défaut si absente — voir
    // AbonnementConfig.
    private AbonnementConfig getOuCreerConfig() {
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        if (config != null) {
            return config;
        }
        AbonnementConfig nouveau = new AbonnementConfig();
        nouveau.setPrixMensuel(15000.0);
        nouveau.setPrixAnnuel(150000.0);
        nouveau.setDureeEssaiJours(14);
        nouveau.setDureeGraceHeures(24);
        return configRepo.save(nouveau);
    }

    private void construireEtSauvegarderAbonnementEssai(Farm farm) {
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

    // Appelé depuis UtilisateurImpl.save() à l'inscription d'une NOUVELLE ferme :
    // doit rester dans la MÊME transaction que la création de la Farm elle-même
    // (propagation par défaut, REQUIRED) — la ligne Farm n'est pas encore commitée
    // tant que cette transaction n'a pas fini, donc une transaction isolée ici (comme
    // pour le chemin paresseux ci-dessous) ne verrait pas encore cette Farm et
    // échouerait sur la contrainte de clé étrangère.
    @Override
    @Transactional
    public void creerEssaiPourFarm(Farm farm) {
        construireEtSauvegarderAbonnementEssai(farm);
    }

    // Variante utilisée UNIQUEMENT par le chemin de création paresseuse ci-dessous,
    // pour une ferme PRÉEXISTANTE (donc déjà commitée depuis longtemps) : isolée
    // dans sa propre transaction (REQUIRES_NEW) pour qu'une violation de contrainte
    // concurrente n'avorte que cette transaction-ci, jamais celle de l'appelant —
    // voir le commentaire sur le champ `self` plus haut. Ne jamais appeler via
    // `this.`, seulement `self.creerEssaiPourFarmIsole(...)`, sous peine de rendre
    // ce @Transactional inerte.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void creerEssaiPourFarmIsole(Farm farm) {
        construireEtSauvegarderAbonnementEssai(farm);
    }

    // Création paresseuse pour les fermes créées avant ce déploiement (voir spec,
    // section "Erreurs et cas limites") — essai complet à partir d'AUJOURD'HUI,
    // jamais rétroactif à la vraie date d'inscription de la ferme.
    private Abonnement getOuCreerAbonnement(Farm farm) {
        return abonnementRepo.findByFarm_Id(farm.getId()).orElseGet(() -> {
            try {
                self.creerEssaiPourFarmIsole(farm);
            } catch (DataIntegrityViolationException e) {
                // Course entre deux requêtes concurrentes (ex. AbonnementGate et la page
                // Abonnement.tsx qui appellent toutes les deux GET /abonnements/moi au
                // même chargement de page) : l'autre thread a déjà inséré la ligne, la
                // contrainte unique sur Abonnement.farm a rejeté celle-ci dans SA PROPRE
                // transaction (REQUIRES_NEW), qui a donc avorté seule — la transaction de
                // cet appelant reste saine. On relit la ligne existante au lieu de
                // propager un 500.
            }
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
    @Transactional(readOnly = true)
    public List<PaiementAbonnementDTO> getHistorique() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return List.of();
        }
        return paiementAbonnementRepo.findByAbonnement_Farm_IdOrderByDateDeclarationDesc(currentUser.getFarm().getId())
                .stream()
                .map(PaiementAbonnementDTO::fromEntity)
                .toList();
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

        String farmNom = resoudreFarmNom(currentUser.getFarm(), currentUser.getFarmName());
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
            String farmNom = resoudreFarmNom(abonnement.getFarm(), paiement.getDeclarePar().getFarmName());
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
