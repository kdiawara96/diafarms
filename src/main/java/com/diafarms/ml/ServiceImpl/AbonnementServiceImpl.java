package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.diafarms.ml.commons.AbonnementEcheance;
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
import com.diafarms.ml.services.LogsServices;

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
    private final LogsServices logs;
    private final com.diafarms.ml.commons.AbonnementAccesMobile accesMobile;
    private final AbonnementTarifService tarifService;
    private final ParrainageService parrainageService;
    private final CreditService creditService;

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
        nouveau.setDureeGraceHeures(24); // colonne historique NOT NULL, plus utilisée
        nouveau.setDelaiGraceJours(AbonnementEcheance.DELAI_GRACE_JOURS_DEFAUT);
        return configRepo.save(nouveau);
    }

    // Crédit prépayé : l'essai reste gratuit, le crédit commence au lendemain de l'essai
    // (creditDepuis), à zéro. essaiRefuse : pas d'essai (téléphone ou e-mail du
    // propriétaire déjà utilisé pour une autre ferme) : la ferme est tout de suite « à
    // recharger » (page Abonnement ouverte, le reste du web bloqué jusqu'à la recharge).
    private void construireEtSauvegarderAbonnementEssai(Farm farm, boolean essaiRefuse) {
        AbonnementConfig config = getOuCreerConfig();
        Abonnement abonnement = new Abonnement();
        abonnement.setUniqueId(UUID.randomUUID().toString());
        abonnement.setFarm(farm);
        abonnement.setStatut(StatutAbonnement.ESSAI);
        LocalDate aujourdHui = LocalDate.now();
        abonnement.setDateDebut(aujourdHui);
        if (essaiRefuse) {
            // Premier jour non couvert placé avant le délai de grâce : bloqué dès aujourd'hui.
            LocalDate epuise = aujourdHui.minusDays(AbonnementEcheance.delaiGraceJours(config));
            abonnement.setEssaiRefuse(true);
            abonnement.setCreditDepuis(aujourdHui);
            abonnement.setCreditEpuiseLe(epuise);
            abonnement.setDateFin(epuise.minusDays(1));
        } else {
            abonnement.setDateFin(aujourdHui.plusDays(config.getDureeEssaiJours()));
            abonnement.setCreditDepuis(abonnement.getDateFin().plusDays(1));
            abonnement.setCreditEpuiseLe(abonnement.getCreditDepuis());
        }
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
        construireEtSauvegarderAbonnementEssai(farm, false);
    }

    // Inscription d'une nouvelle ferme : un seul essai gratuit par propriétaire (téléphone
    // ou e-mail déjà vus sur une autre ferme, même supprimée : pas d'essai). true si
    // l'essai est donné.
    @Override
    @Transactional
    public boolean creerEssaiPourFarm(Farm farm, String telephone, String email) {
        boolean refuse = creditService.essaiDejaUtilise(farm.getId(), telephone, email);
        construireEtSauvegarderAbonnementEssai(farm, refuse);
        creditService.memoriserEssai(farm.getId(), telephone, email, !refuse);
        return !refuse;
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
        construireEtSauvegarderAbonnementEssai(farm, false);
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

    // Console SUPER_ADMIN (AdminConsoleService) : même création paresseuse que getMoi,
    // pour agir sur une ferme qui ne s'est jamais reconnectée depuis l'arrivée des abonnements.
    public Abonnement abonnementDeLaFerme(Farm farm) {
        return getOuCreerAbonnement(farm);
    }

    // Statut effectif : toujours recalculé (jamais lu dans Abonnement.statut), voir
    // AbonnementEcheance pour les règles (date de fin incluse, puis délai de grâce en
    // jours pendant lequel rien n'est bloqué).
    private AbonnementEcheance.Etat calculerStatutEffectif(Abonnement abonnement, AbonnementConfig config) {
        return AbonnementEcheance.calculer(abonnement, config, LocalDate.now());
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

        AbonnementEcheance.Etat effectif = calculerStatutEffectif(abonnement, config);

        PaiementAbonnement enAttente = paiementAbonnementRepo
                .findByAbonnement_IdAndStatut(abonnement.getId(), StatutPaiementAbonnement.EN_ATTENTE)
                .orElse(null);

        PaiementAbonnementDTO enAttenteDto = PaiementAbonnementDTO.fromEntity(enAttente);
        if (enAttenteDto != null) {
            enAttenteDto.setBonusPrevu(com.diafarms.ml.commons.AbonnementCredit.bonus(enAttente.getMontant(),
                    com.diafarms.ml.commons.AbonnementCredit.regles(config)));
        }
        AbonnementDTO dto = AbonnementDTO.of(abonnement, effectif, enAttenteDto);
        com.diafarms.ml.DTO.AbonnementTarifDTO tarif = tarifService.tarifFerme(farm.getId(), abonnement, config);
        dto.setTarif(com.diafarms.ml.commons.AbonnementTarif.pourLaFerme(tarif));
        dto.setCredit(creditService.etat(abonnement, config, tarif.poulesComptees()));
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AbonnementDTO> listerFermes() {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        LocalDate aujourdHui = LocalDate.now();
        // Une seule requête pour toutes les déclarations en attente (et non une par ferme).
        java.util.Map<Long, PaiementAbonnement> enAttente = new java.util.HashMap<>();
        for (PaiementAbonnement p : paiementAbonnementRepo.findAllAvecFermeParStatut(StatutPaiementAbonnement.EN_ATTENTE)) {
            enAttente.putIfAbsent(p.getAbonnement().getId(), p);
        }
        List<Abonnement> abonnements = abonnementRepo.findAllAvecFerme();
        java.util.Map<Long, Abonnement> parFerme = new java.util.HashMap<>();
        for (Abonnement a : abonnements) parFerme.put(a.getFarm().getId(), a);
        // Tarifs de toutes les fermes en une fois (voir AbonnementTarifService).
        java.util.Map<Long, com.diafarms.ml.DTO.AbonnementTarifDTO> tarifs = tarifService.tarifsFermes(null, parFerme, config);
        return abonnements.stream()
                .map(a -> {
                    AbonnementDTO dto = AbonnementDTO.of(a, AbonnementEcheance.calculer(a, config, aujourdHui),
                            PaiementAbonnementDTO.fromEntity(enAttente.get(a.getId())));
                    dto.setTarif(tarifs.get(a.getFarm().getId()));
                    return dto;
                })
                .toList();
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

    // Compte de crédit de la ferme courante (page Abonnement), plus récent d'abord.
    @Override
    @Transactional(readOnly = true)
    public List<com.diafarms.ml.DTO.MouvementCreditDTO> getMouvements() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) return List.of();
        return abonnementRepo.findByFarm_Id(currentUser.getFarm().getId())
                .map(a -> creditService.mouvements(a.getId(), false)).orElse(List.of());
    }

    public static final double RECHARGE_MAX = 10_000_000;

    // « J'ai rechargé » (crédit prépayé) : la ferme indique le montant envoyé, le moyen et
    // la référence ; le SUPER_ADMIN vérifie puis valide (valider) et le crédit est ajouté.
    // Ancien client sans montant : le prix du mois ou de l'an affiché devient la recharge.
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
        if (request.getMoyenPaiement() == null || request.getMoyenPaiement().isBlank()) {
            throw new IllegalArgumentException("Indiquez comment vous avez envoyé l'argent.");
        }
        Double montantRecharge = request.getMontant();
        if (montantRecharge != null && (montantRecharge.isNaN() || montantRecharge <= 0 || montantRecharge > RECHARGE_MAX)) {
            throw new IllegalArgumentException("Indiquez le montant envoyé (plus de 0 et au plus 10 000 000 FCFA).");
        }
        Periodicite periodicite = Periodicite.MENSUEL;
        if (montantRecharge == null) {
            if (request.getPeriodicite() == null) {
                throw new IllegalArgumentException("Indiquez le montant envoyé.");
            }
            try {
                periodicite = Periodicite.valueOf(request.getPeriodicite().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Périodicité invalide (attendu MENSUEL ou ANNUEL) : " + request.getPeriodicite());
            }
        }
        String moyen = request.getMoyenPaiement().trim();
        if (moyen.length() > 50) moyen = moyen.substring(0, 50);
        String reference = request.getReference() == null || request.getReference().isBlank() ? null : request.getReference().trim();
        if (reference != null && reference.length() > 100) reference = reference.substring(0, 100);

        Abonnement abonnement = getOuCreerAbonnement(currentUser.getFarm());
        if (abonnement.estSuspendu()) {
            throw new IllegalArgumentException("L'accès de votre ferme est suspendu : vous ne pouvez pas déclarer de paiement. "
                    + "Contactez-nous sur WhatsApp au +223 83 91 86 99.");
        }

        if (paiementAbonnementRepo.findByAbonnement_IdAndStatut(abonnement.getId(), StatutPaiementAbonnement.EN_ATTENTE).isPresent()) {
            throw new IllegalArgumentException("Une recharge est déjà en attente de vérification.");
        }

        AbonnementConfig config = getOuCreerConfig();
        com.diafarms.ml.DTO.AbonnementTarifDTO tarif = tarifService.tarifFerme(currentUser.getFarm().getId(), abonnement, config);
        double montant;
        if (montantRecharge != null) {
            montant = Math.round(montantRecharge);
        } else {
            // Ancien client : le prix affiché (mois ou an) devient le montant rechargé.
            if (!com.diafarms.ml.commons.AbonnementTarif.facturable(tarif)) {
                throw new IllegalArgumentException("Le prix n'a pas pu être calculé, réessayez dans un instant.");
            }
            montant = periodicite == Periodicite.ANNUEL ? tarif.prixAnnuel() : tarif.prixMensuel();
            if (request.getMontantAffiche() != null && Math.round(request.getMontantAffiche()) != Math.round(montant)) {
                throw new IllegalArgumentException("Le prix a été mis à jour, rechargez la page.");
            }
        }

        PaiementAbonnement paiement = new PaiementAbonnement();
        paiement.setUniqueId(UUID.randomUUID().toString());
        paiement.setAbonnement(abonnement);
        paiement.setMontant(montant);
        paiement.setMontantAttendu(montantRecharge == null ? montant : null);
        paiement.setPoulesComptees(tarif.poulesComptees());
        paiement.setPeriodicite(periodicite);
        paiement.setRecharge(true);
        paiement.setMoyenPaiement(moyen);
        paiement.setReference(reference);
        paiement.setStatut(StatutPaiementAbonnement.EN_ATTENTE);
        paiement.setDateDeclaration(LocalDateTime.now());
        paiement.setDeclarePar(currentUser);
        paiement.setInitialisation(Initialisation.init());
        PaiementAbonnement saved = paiementAbonnementRepo.save(paiement);
        logs.addLogs(currentUser.getId(), saved.getId(), "PaiementAbonnement",
                "Déclaration d'une recharge de crédit : " + Math.round(montant) + " FCFA");

        String farmNom = resoudreFarmNom(currentUser.getFarm(), currentUser.getFarmName());
        double bonusPrevu = com.diafarms.ml.commons.AbonnementCredit.bonus(montant,
                com.diafarms.ml.commons.AbonnementCredit.regles(config));
        String texte = "La ferme " + farmNom + " a déclaré une recharge de crédit.\n\nMontant : "
                + AbonnementEcheance.fcfa(montant)
                + (bonusPrevu > 0 ? "\nBonus prévu à la validation : " + AbonnementEcheance.fcfa(bonusPrevu) : "")
                + "\nMoyen : " + moyen + (reference != null ? "\nRéférence : " + reference : "")
                + "\n\nOuvre la console d'administration (Paiements et réglages) pour vérifier puis valider.";
        for (Utilisateurs superAdmin : utilisateursRepo.findAllSuperAdmins()) {
            try {
                emailService.sendMessageCocorico(superAdmin.getEmail(), superAdmin.getFullName(), "Recharge à valider",
                        "Recharge à valider : " + farmNom, texte);
            } catch (Exception e) {
                // un e-mail en échec ne bloque jamais la déclaration
            }
        }

        PaiementAbonnementDTO dto = PaiementAbonnementDTO.fromEntity(saved);
        dto.setBonusPrevu(bonusPrevu);
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<PaiementAbonnementDTO> listEnAttente(int page, int size) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        Pageable pageable = PageRequest.of(page, size);
        Page<PaiementAbonnement> resultPage = paiementAbonnementRepo
                .findByStatutOrderByDateDeclarationAsc(StatutPaiementAbonnement.EN_ATTENTE, pageable);

        com.diafarms.ml.commons.AbonnementCredit.Regles rc = com.diafarms.ml.commons.AbonnementCredit.regles(
                configRepo.findFirstByOrderByIdAsc());
        return new PaginatedResponse<>(
                resultPage.getContent().stream().map(p -> {
                    PaiementAbonnementDTO d = PaiementAbonnementDTO.fromEntity(p);
                    d.setBonusPrevu(com.diafarms.ml.commons.AbonnementCredit.bonus(p.getMontant(), rc));
                    return d;
                }).toList(),
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
        // Abonnement verrouillé avant toute lecture (voir AbonnementRepo.verrouillerParId) :
        // une action SUPER_ADMIN simultanée (activer, suspendre...) passe avant ou après,
        // jamais au milieu. Statut du paiement relu en base une fois le verrou obtenu.
        Abonnement abonnement = abonnementRepo.verrouillerParId(paiement.getAbonnement().getId())
                .orElseThrow(() -> new IllegalArgumentException("Abonnement introuvable."));
        if (paiementAbonnementRepo.statutEnBase(paiement.getId()) != StatutPaiementAbonnement.EN_ATTENTE) {
            throw new IllegalArgumentException("Cette déclaration a déjà été traitée.");
        }
        // Crédit prépayé : toute déclaration validée (recharge, ou ancien paiement d'une
        // période déclaré avant le passage au crédit) ajoute son montant au crédit, plus le
        // bonus éventuel ; l'échéance est recalculée (voir CreditService.appliquerRecharge).
        // Une ferme suspendue le reste : valider ne lève jamais la suspension.
        paiement.setStatut(StatutPaiementAbonnement.VALIDE);
        paiement.setDateValidation(LocalDateTime.now());
        paiement.setValidePar(currentUser);
        paiement.setRecharge(true);
        paiementAbonnementRepo.save(paiement);
        CreditService.Recharge recharge = creditService.appliquerRecharge(abonnement, paiement, currentUser);
        accesMobile.invaliderApresCommit(abonnement.getFarm().getId()); // lectures mobiles rétablies tout de suite
        PaiementAbonnement saved = paiementAbonnementRepo.save(paiement);
        logs.addLogs(currentUser.getId(), saved.getId(), "PaiementAbonnement",
                "Validation du paiement d'abonnement de la ferme " + resoudreFarmNom(abonnement.getFarm(),
                        paiement.getDeclarePar() != null ? paiement.getDeclarePar().getFarmName() : null));

        // Parrainage : 1 mois offert au parrain au premier paiement validé (après le commit).
        parrainageService.apresPaiementValide(abonnement.getFarm().getId(), saved.getId(), currentUser.getId());

        String farmNom = resoudreFarmNom(abonnement.getFarm(),
                paiement.getDeclarePar() != null ? paiement.getDeclarePar().getFarmName() : null);
        Long farmId = abonnement.getFarm().getId();
        creditService.apresCommit(() -> creditService.emailRechargeValidee(farmId, farmNom, recharge));

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
        PaiementAbonnement saved = paiementAbonnementRepo.save(paiement);
        logs.addLogs(currentUser.getId(), saved.getId(), "PaiementAbonnement",
                "Rejet du paiement d'abonnement de la ferme " + resoudreFarmNom(paiement.getAbonnement().getFarm(),
                        paiement.getDeclarePar() != null ? paiement.getDeclarePar().getFarmName() : null));
        return PaiementAbonnementDTO.fromEntity(saved);
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
        if (!isSuperAdmin(currentUser)) {
            // 403 (voir AbonnementController.updateConfig).
            throw new org.springframework.security.access.AccessDeniedException("Seul un SUPER_ADMIN peut modifier les prix.");
        }

        AbonnementConfig config = getOuCreerConfig();
        if (request.getPrixMensuel() != null) config.setPrixMensuel(request.getPrixMensuel());
        if (request.getPrixAnnuel() != null) config.setPrixAnnuel(request.getPrixAnnuel());
        if (request.getDureeEssaiJours() != null) config.setDureeEssaiJours(request.getDureeEssaiJours());
        if (request.getDureeGraceHeures() != null) config.setDureeGraceHeures(request.getDureeGraceHeures());
        if (request.getDelaiGraceJours() != null) {
            if (request.getDelaiGraceJours() < 0 || request.getDelaiGraceJours() > 60) {
                throw new IllegalArgumentException("Le délai de grâce doit être compris entre 0 et 60 jours.");
            }
            config.setDelaiGraceJours(request.getDelaiGraceJours());
        }
        // Prix par poule : toutes les vérifications avant la moindre modification.
        if (request.getPrixParPoule() != null && (request.getPrixParPoule() <= 0 || request.getPrixParPoule() > 10000)) {
            throw new IllegalArgumentException("Le prix par poule doit être supérieur à 0 et au plus 10 000 FCFA.");
        }
        if (request.getPrixMinimumMensuel() != null && (request.getPrixMinimumMensuel() < 0 || request.getPrixMinimumMensuel() > 10_000_000)) {
            throw new IllegalArgumentException("Le prix minimum par mois doit être compris entre 0 et 10 000 000 FCFA.");
        }
        if (request.getMoisOffertsAnnuel() != null && (request.getMoisOffertsAnnuel() < 0 || request.getMoisOffertsAnnuel() > 11)) {
            throw new IllegalArgumentException("Les mois offerts sur l'année doivent être compris entre 0 et 11.");
        }
        if (request.getArrondi() != null && (request.getArrondi() < 1 || request.getArrondi() > 100_000)) {
            throw new IllegalArgumentException("L'arrondi doit être compris entre 1 et 100 000 FCFA.");
        }
        if (request.getBonusSeuil() != null && (request.getBonusSeuil() < 0 || request.getBonusSeuil() > 100_000_000)) {
            throw new IllegalArgumentException("Le seuil du bonus doit être compris entre 0 et 100 000 000 FCFA.");
        }
        if (request.getBonusPourcent() != null && (request.getBonusPourcent() < 0 || request.getBonusPourcent() > 100)) {
            throw new IllegalArgumentException("Le bonus doit être compris entre 0 et 100 %.");
        }
        if (request.getSeuilSurDevis() != null && (request.getSeuilSurDevis() < 1 || request.getSeuilSurDevis() > 1_000_000)) {
            throw new IllegalArgumentException("Le seuil « sur devis » doit être compris entre 1 et 1 000 000 poules.");
        }
        if (request.getCreditParrainage() != null && (request.getCreditParrainage() < 0 || request.getCreditParrainage() > 1_000_000)) {
            throw new IllegalArgumentException("Le crédit de parrainage doit être compris entre 0 et 1 000 000 FCFA.");
        }
        if (request.getBonusSeuil() != null) config.setBonusSeuil(request.getBonusSeuil());
        if (request.getBonusPourcent() != null) config.setBonusPourcent(request.getBonusPourcent());
        if (request.getSeuilSurDevis() != null) config.setSeuilSurDevis(request.getSeuilSurDevis());
        if (request.getCreditParrainage() != null) config.setCreditParrainage(request.getCreditParrainage());
        if (request.getPrixParPoule() != null) config.setPrixParPoule(request.getPrixParPoule());
        if (request.getPrixMinimumMensuel() != null) config.setPrixMinimumMensuel(request.getPrixMinimumMensuel());
        if (request.getMoisOffertsAnnuel() != null) config.setMoisOffertsAnnuel(request.getMoisOffertsAnnuel());
        if (request.getArrondi() != null) config.setArrondi(request.getArrondi());
        AbonnementConfig saved = configRepo.save(config);
        logs.addLogs(currentUser.getId(), saved.getId(), "AbonnementConfig", "Mise à jour de la configuration des abonnements");
        return AbonnementConfigDTO.fromEntity(saved);
    }
}
