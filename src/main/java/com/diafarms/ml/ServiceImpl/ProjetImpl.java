package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.ProjetAssigneDTO;
import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.DTO.ProjetsSelect;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Objectif;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.Alimentation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.InvestissementRepartition;
import com.diafarms.ml.models.OccupationBatiment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Race;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.Vaccination;
import com.diafarms.ml.models.Batiment.StatutBatiment;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.ConsommationAliment;
import com.diafarms.ml.repository.AlimentationRepo;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.ConsommationAlimentRepo;
import com.diafarms.ml.repository.TransactionRepo;
import com.diafarms.ml.repository.InvestissementRepartitionRepository;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.OccupationBatimentRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.RaceRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.repository.VaccinationRepo;
import com.diafarms.ml.request.create.OccupationCreate;
import com.diafarms.ml.request.create.ProjetCreate;
import com.diafarms.ml.request.create.VaccinCreate;
import com.diafarms.ml.request.update.ProjetUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.ProjectAlertConfigService;
import com.diafarms.ml.services.ProjetServices;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjetImpl implements ProjetServices {

    private final ProjetsRepo projetsRepo;
    private final RaceRepo raceRepo;
    private final UtilisateursRepo utilisateursRepo;
    private final AlimentationRepo alimentationRepo;
    private final ConsommationAlimentRepo consommationAlimentRepo;
    private final TransactionRepo transactionRepo;
    private final VaccinationRepo vaccinationRepo;
    private final OccupationBatimentRepo occupationBatimentRepo;
    private final BatimentRepo batimentRepo;
    private final InvestissementRepartitionRepository investissementRepartitionRepo;
    private final OtherService otherService;
    private final LogsServices logs;
    private final MortaliteRepo mortaliteRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;
    private final com.diafarms.ml.repository.ReformeRepo reformeRepo;
    private final TransactionService transactionService;

    private static final int TAUX_PONTE_WINDOW_DAYS = 7;

    // Achat sujets (nbSujets × puSujet) et "autres charges" saisis à la création/
    // modification du projet sont de vrais frais de démarrage — ils comptaient dans
    // caTotalSujets (mal nommé : c'est un coût, pas un CA) mais ne généraient jamais
    // de sortie comptable réelle avant ça. sourceUniqueId synthétique et stable
    // (pas d'entité dédiée pour cette "dépense") pour que syncSortie retrouve
    // toujours la même ligne d'une modification à l'autre. Voir
    // AlimentationImpl.syncTransaction pour le même principe général.
    private void syncAchatSujets(Projets projet, Utilisateurs currentUser) {
        if (currentUser == null || currentUser.getFarm() == null) return;
        Integer nbSujets = projet.getNbSujets();
        Double puSujet = projet.getPuSujet();
        Double montant = (nbSujets != null && puSujet != null) ? nbSujets * puSujet : null;
        String description = "Achat sujets : " + (nbSujets != null ? nbSujets : 0) + " sujets — projet " + projet.getTitre();
        transactionService.syncSortie(projet, currentUser.getFarm(), montant, "Achat sujets",
                projet.getDebut(), description, SourceTransaction.PROJET_ACHAT_SUJETS, "SUJETS-" + projet.getUniqueId(), currentUser);
    }

    private void syncAutresCharges(Projets projet, Utilisateurs currentUser) {
        if (currentUser == null || currentUser.getFarm() == null) return;
        String description = "Autres charges initiales — projet " + projet.getTitre();
        transactionService.syncSortie(projet, currentUser.getFarm(), projet.getAutresDepense(), "Autres charges",
                projet.getDebut(), description, SourceTransaction.PROJET_CHARGES, "CHARGES-" + projet.getUniqueId(), currentUser);
    }

    // Mortalité cumulée réelle (morts / effectif initial) et taux de ponte
    // récent (moyenne journalière des 7 derniers jours / effectif actuel) —
    // ProjetsDTO.fromEntity(data) seul les mettait à 0.0 en dur.
    private Double computeMortaliteCumulee(Projets p) {
        if (p.getNbSujets() == null || p.getNbSujets() <= 0) return 0.0;
        Integer morts = mortaliteRepo.sumMortsByProjetId(p.getId());
        double taux = ((morts == null ? 0 : morts) * 100.0) / p.getNbSujets();
        return Math.round(taux * 10) / 10.0;
    }

    // Chiffre d'affaires réel (pas une projection) : somme des transactions "entrée"
    // validées du projet. Voir ProjetsDTO.fromEntity/fromEntityList — remplace
    // data.getChiffreAffaires(), figé à 0.0 depuis la création du projet.
    private Double computeChiffreAffairesReel(Projets p) {
        Double montant = transactionRepo.sumMontantValideByProjetIdAndType(p.getId(), TypeTransaction.ENTREE);
        return montant == null ? 0.0 : montant;
    }

    private Double computeTauxPonte(Projets p) {
        if (p.getNbSujets() == null || p.getNbSujets() <= 0) return 0.0;
        Integer morts = mortaliteRepo.sumMortsByProjetId(p.getId());
        int effectifActuel = p.getNbSujets() - (morts == null ? 0 : morts);
        if (effectifActuel <= 0) return 0.0;

        Integer oeufsRecents = collecteOeufsRepo.sumOeufsByProjetIdSince(p.getId(), LocalDate.now().minusDays(TAUX_PONTE_WINDOW_DAYS));
        double moyenneJournaliere = (oeufsRecents == null ? 0 : oeufsRecents) / (double) TAUX_PONTE_WINDOW_DAYS;
        double taux = (moyenneJournaliere / effectifActuel) * 100;
        return Math.round(taux * 10) / 10.0;
    }

    // Effectif vivant (nbSujets initial - mortalité - déjà réformés, Production) —
    // même calcul que celui validé à la création par ReformeImpl, exposé ici pour
    // l'affichage (Fiche Projet, bilan de clôture) sans dupliquer le calcul côté
    // front. sujetsReformesCumulee vient de Reforme (comptage Production), plus de
    // VenteReforme (Finance, plafonnée à l'échelle de la ferme, pas par projet).
    private int nzInt(Integer v) {
        return v == null ? 0 : v;
    }

    private Integer computeSujetsReformesCumulee(Projets p) {
        return nzInt(reformeRepo.sumSujetsByProjetId(p.getId()));
    }

    private Integer computeEffectifVivant(Projets p) {
        int nbSujets = p.getNbSujets() == null ? 0 : p.getNbSujets();
        int morts = nzInt(mortaliteRepo.sumMortsByProjetId(p.getId()));
        int dejaReformes = computeSujetsReformesCumulee(p);
        return nbSujets - morts - dejaReformes;
    }

    // AJOUT DE L'INJECTION ICI :
    private final ProjectAlertConfigService projectAlertConfigService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // --- Génération UID ---
    private String generateUID() {
        return UUID.randomUUID().toString();
    }
    // --- Génération Code ---
    private String generateCode() {
        long count = projetsRepo.count() + 1;
        String code;
        
        do {
            code = String.format("PRJ-%03d", count);
            count++;
        } while (projetsRepo.existsByCode(code)); // Requête SQL directe
        
        return code;
    }

    // --- Parse date ---
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            throw new RuntimeException("Format de date invalide : " + dateStr + ". Attendu : yyyy-MM-dd");
        }
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

    @Transactional(readOnly = true) // Garde la session ouverte pendant le mapping, a cause de Race car c'est LAZY la relaton avec projets
    @Override
    public PaginatedResponse<ProjetsDTO> getAllProjets(int page, int size, String search, String filter) {
        
        // Tri par date de création descendante (champ dans l'objet embedded Initialisation)
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "initialisation.createdAt")
        );

        // Détermination de l'état d'archivage selon le filtre
        Boolean isArchive = null;
        if (filter != null) {
            if (filter.equalsIgnoreCase("actif")) {
                isArchive = false;
            } else if (filter.equalsIgnoreCase("archive")) {
                isArchive = true;
            }
        }

        Utilisateurs currentUser = null;
        try {
            currentUser = otherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        
        Long farmId = null;
        if (currentUser != null && currentUser.getFarm() != null) {
            farmId = currentUser.getFarm().getId();
        }

        String searchParam = (search == null || search.isBlank()) ? null : search.trim();
        
        if (search != null && !search.isBlank()) {
            searchParam = "%" + search.trim().toLowerCase() + "%";
        }
        
        // Appel du repo
        Page<Projets> projetsPage = projetsRepo.searchProjets(farmId, isArchive, searchParam, pageable);

        // Mapping des entités vers le DTO de listage
        List<ProjetsDTO> dtoList = projetsPage.getContent().stream()
                .map(p -> ProjetsDTO.fromEntityList(p, computeTauxPonte(p), computeMortaliteCumulee(p), computeChiffreAffairesReel(p),
                        computeSujetsReformesCumulee(p), computeEffectifVivant(p)))
                .toList();

        return new PaginatedResponse<>(
            dtoList,
            projetsPage.getNumber() + 1, // Conversion vers index 1 pour le Front
            projetsPage.getTotalPages(),
            projetsPage.getTotalElements(),
            projetsPage.getSize()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ProjetsDTO getProjetByUniqueId(String uniqueId) {
        Projets projet = projetsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Projet non trouvé avec l'uniqueId : " + uniqueId));

        return ProjetsDTO.fromEntity(projet, computeTauxPonte(projet), computeMortaliteCumulee(projet), computeChiffreAffairesReel(projet),
                computeSujetsReformesCumulee(projet), computeEffectifVivant(projet));
    }

    // ============================================================
    // CREATE PROJET (AVEC TOUTES LES SOUS-ENTITÉS)
    // ============================================================
    @Override
    @Transactional
    public ProjetsDTO createProjet(ProjetCreate data) {
        
        // 1. Récupérer l'utilisateur connecté et sa ferme
        Utilisateurs currentUser = getCurrentUserSafe();
        Farm farm = currentUser != null ? currentUser.getFarm() : null;
        
        Long raceId = data.getRaceId();
        Race race = null;
        // 2. Vérifier et récupérer la race
        if (raceId != null) {
             race = raceRepo.findById(raceId)
            .orElseThrow(() -> new RuntimeException("Race non trouvée avec l'id : " + data.getRaceId()));
        }

        // 3. Vérifier et récupérer les responsables
        Utilisateurs responsableProduction = null;
        Long responsableProductionId = data.getResponsableProductionId();
        if (responsableProductionId != null) {
            responsableProduction = utilisateursRepo.findById(responsableProductionId)
                    .orElseThrow(() -> new RuntimeException("Responsable production non trouvé avec l'id : " + data.getResponsableProductionId()));
        }

        Utilisateurs responsableFinance = null;
        Long responsableFinanceId = data.getResponsableFinanceId();
        if (responsableFinanceId != null) {
            responsableFinance = utilisateursRepo.findById(responsableFinanceId)
                    .orElseThrow(() -> new RuntimeException("Responsable finance non trouvé avec l'id : " + data.getResponsableFinanceId()));
        }

        Utilisateurs responsable = null;
        Long responsableId = data.getResponsableId();
        if (responsableId != null) {
            responsable = utilisateursRepo.findById(responsableId)
                    .orElseThrow(() -> new RuntimeException("Responsable non trouvé avec l'id : " + data.getResponsableId()));
        }

        // 4. Créer le projet
        Projets projet = new Projets();

        projet.setUniqueId(generateUID());
        projet.setCode(generateCode());
        projet.setTitre(data.getTitre());
        projet.setResponsable(responsable);
        projet.setDebut(parseDate(data.getDateDebut()));
        projet.setFinPrevue(parseDate(data.getDateFinPrevue()));
        projet.setNbSujets(data.getNbSujets());
        projet.setPuSujet(data.getPuSujet());
        projet.setAutresDepense(data.getAutresDepense());
        projet.setObjectif(Objectif.valueOf(data.getObjectif()));
        projet.setFournisseurs_poussins(data.getFournisseursPoussins());
        projet.setRace(race);
        projet.setResponsableProduction(responsableProduction);
        projet.setResponsableFinance(responsableFinance);
        projet.setFarm(farm);

        double caTotalSujets = (data.getNbSujets() != null ? data.getNbSujets() : 0) * (data.getPuSujet() != null ? data.getPuSujet() : 0) + (data.getAutresDepense() != null ? data.getAutresDepense() : 0);
        projet.setCaTotalSujets(caTotalSujets);
        // chiffreAffaires (colonne entité) n'est plus lu par le DTO : le "chiffre
        // d'affaires" exposé au front est calculé à la volée depuis les transactions
        // "entrée" validées du projet (voir computeChiffreAffairesReel), forcément 0
        // à la création puisqu'aucune vente n'a encore été saisie.
        projet.setChiffreAffaires(0.0);
        projet.setMargeNette(0.0); // Sera calculé plus tard avec tous les coûts réels

        projet.setInitialisation(Initialisation.init());

        // Sauvegarder le projet d'abord (pour avoir l'ID pour les relations)
        Projets savedProjet = projetsRepo.save(projet);

        // Achat sujets + autres charges de démarrage — voir syncAchatSujets/
        // syncAutresCharges : jusqu'ici jamais transformés en vraie sortie comptable.
        syncAchatSujets(savedProjet, currentUser);
        syncAutresCharges(savedProjet, currentUser);

        // 5. Créer l'alimentation initiale
        if (data.getAlimentNom() != null && !data.getAlimentNom().trim().isEmpty()) {
            Alimentation alimentation = new Alimentation();
            alimentation.setUniqueId(generateUID());
            alimentation.setNomAliment(data.getAlimentNom());
            alimentation.setSac(data.getSac() != null ? data.getSac().doubleValue() : 0.0);
            alimentation.setQuantiteKg(data.getQuantiteKg());
            alimentation.setCoutTotal(data.getCoutTotalAliment());
            alimentation.setDateDistribution(parseDate(data.getDateDebut()));
            alimentation.setObservations(data.getObservations());
            alimentation.setProjet(savedProjet);
            alimentation.setFarm(farm);
            alimentation.setInitialisation(Initialisation.init());

            Alimentation savedAlimentation = alimentationRepo.save(alimentation);
            if (currentUser != null && currentUser.getFarm() != null) {
                String descAliment = "Achat aliment : " + savedAlimentation.getNomAliment() + " (" + savedAlimentation.getQuantiteKg() + " kg) — projet " + savedProjet.getTitre();
                transactionService.syncSortie(savedProjet, currentUser.getFarm(), savedAlimentation.getCoutTotal(), "Aliment",
                        savedAlimentation.getDateDistribution(), descAliment, SourceTransaction.ALIMENTATION, savedAlimentation.getUniqueId(), currentUser);
            }
        }

        // APPEL DE TA MÉTHODE POUR CRÉER LES ALERTES PAR DÉFAUT
        projectAlertConfigService.insertDefaultAlertsForProject(savedProjet);

        // 6. Créer les vaccinations
        if (data.getVaccins() != null && !data.getVaccins().isEmpty()) {
            for (VaccinCreate vaccinData : data.getVaccins()) {
                Vaccination vaccination = new Vaccination();
                vaccination.setUniqueId(generateUID());
                vaccination.setNomVaccin(vaccinData.getNomVaccin());
                vaccination.setQuantite(vaccinData.getQuantite());
                vaccination.setPrixUnitaire(vaccinData.getPrixUnitaire());
                vaccination.setCoutTotal(vaccinData.getCoutTotal());
                
                if (vaccinData.getModeAdministration() != null && !vaccinData.getModeAdministration().isEmpty()) {
                    vaccination.setModeAdministration(
                        String.join(" | ", vaccinData.getModeAdministration())
                    );
                }
                
                vaccination.setProjet(savedProjet);
                vaccination.setFarm(farm);
                vaccination.setInitialisation(Initialisation.init());

                Vaccination savedVaccination = vaccinationRepo.save(vaccination);
                if (currentUser != null && currentUser.getFarm() != null) {
                    String descVaccin = "Vaccin " + savedVaccination.getNomVaccin() + " (" + savedVaccination.getQuantite() + " doses) — projet " + savedProjet.getTitre();
                    transactionService.syncSortie(savedProjet, currentUser.getFarm(), savedVaccination.getCoutTotal(), "Vaccination",
                            savedProjet.getDebut(), descVaccin, SourceTransaction.VACCINATION, savedVaccination.getUniqueId(), currentUser);
                }
            }
        }

        // 7. Créer les occupations de bâtiments
        if (data.getOccupations() != null && !data.getOccupations().isEmpty()) {
            for (OccupationCreate occData : data.getOccupations()) {

                Long batimentId = occData.getBatimentId();
                Batiment batiment = null;

                if (batimentId != null) {
                    // Vérifier le bâtiment
                    batiment = batimentRepo.findById(batimentId)
                            .orElseThrow(() -> new RuntimeException("Bâtiment non trouvé avec l'id : " + batimentId));
                }

                if (batiment != null && occupationBatimentRepo.existsOccupationActive(batiment.getId())) {
                    throw new RuntimeException("Le bâtiment " + batiment.getNom() + " est déjà occupé.");
                }

                // Créer l'occupation
                OccupationBatiment occupation = new OccupationBatiment();
                occupation.setProjet(savedProjet);
                occupation.setBatiment(batiment);
                occupation.setNbSujetsDansBatiment(occData.getNbSujets());
                occupation.setDateEntree(parseDate(occData.getDateEntree()));
                occupation.setDateSortie(parseDate(occData.getDateSortie()));

                occupationBatimentRepo.save(occupation);

                if (batiment != null) {
                    // Mettre à jour le statut du bâtiment
                    batiment.setStatut(StatutBatiment.OCCUPE);
                    batimentRepo.save(batiment);
                }
            }
        }

        // 8. Log
        if (currentUser != null) {
            logs.addLogs(
                currentUser.getId(),
                savedProjet.getId(),
                "Projet",
                "Création du projet '" + savedProjet.getTitre() 
                    + "' (" + savedProjet.getNbSujets() + " sujets, Objectif : " 
                    + savedProjet.getObjectif() + ") | Cout Achat total : " 
                    + savedProjet.getCaTotalSujets() + " FCFA"
            );
        }

        return ProjetsDTO.fromEntity(savedProjet);
    }

    @Override
    @Transactional
    public String deleteOrRecoverProjet(String uniqueId) {
        Projets projet = projetsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException(
                        "Projet non trouvé avec l'uniqueId : " + uniqueId));

        projet.getInitialisation()
                .setRemoved(!projet.getInitialisation().getRemoved());

        projetsRepo.save(projet);

        boolean removed = projet.getInitialisation().getRemoved();

        // Un projet supprimé ne doit plus accumuler d'amortissement : on fige
        // (clôture) ses répartitions d'investissement encore actives à la date
        // du jour, au lieu de les laisser continuer à compter silencieusement.
        if (removed) {
            List<InvestissementRepartition> repartitionsActives =
                    investissementRepartitionRepo.findActiveByProjetUniqueId(uniqueId);
            for (InvestissementRepartition r : repartitionsActives) {
                r.figerLaVentilation(LocalDate.now());
            }
            investissementRepartitionRepo.saveAll(repartitionsActives);
        }

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(
                currentUser.getId(),
                projet.getId(),
                "Projet",
                (removed ? "Suppression" : "Restauration") + " du projet '" + projet.getTitre() + "'"
            );
        }

        return removed
                ? "Projet supprimé."
                : "Projet récupéré.";
    }

    /**
     * Clôture (archive) définitivement un projet — DISTINCT de deleteOrRecoverProjet
     * (removed = suppression/corbeille). C'est le SEUL mécanisme qui fait passer
     * initialisation.archive à true : un projet ne devient jamais "terminé" tout seul
     * juste parce que sa date de fin prévue est dépassée (fréquent en élevage réel —
     * un lot peut durer plus longtemps que prévu), il faut une action explicite de
     * l'admin. C'est ce champ "active" (!archive) qui est ensuite utilisé partout
     * pour ne pas ramener un projet clos sur le mobile hors ligne (voir
     * ProjetsSelect.selectEntity), plutôt que de comparer une date.
     *
     * Rejette explicitement un projet déjà clôturé plutôt que de re-toggle en
     * silence : l'ancienne version (archiveOrRecoverProjet) togglait sans condition,
     * donc un second clic sur "Clôturer" (ou un double-submit) rouvrait le projet à
     * son insu, affiché comme une clôture réussie côté front.
     *
     * Fige aussi les répartitions d'investissement (Ventilation analytique) encore
     * actives sur ce projet — sinon elles restaient affichées "En cours / Continu"
     * indéfiniment après la clôture, continuant silencieusement à accumuler de
     * l'amortissement pour un projet pourtant terminé. Même logique que
     * deleteOrRecoverProjet (corbeille), qui le faisait déjà pour la suppression
     * mais pas pour la clôture.
     */
    @Override
    @Transactional
    public String cloturerProjet(String uniqueId) {
        Projets projet = projetsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException(
                        "Projet non trouvé avec l'uniqueId : " + uniqueId));

        if (Boolean.TRUE.equals(projet.getInitialisation().getArchive())) {
            throw new RuntimeException("Ce projet est déjà clôturé.");
        }

        projet.getInitialisation().setArchive(true);
        projetsRepo.save(projet);

        List<InvestissementRepartition> repartitionsActives =
                investissementRepartitionRepo.findActiveByProjetUniqueId(uniqueId);
        for (InvestissementRepartition r : repartitionsActives) {
            r.figerLaVentilation(LocalDate.now());
        }
        investissementRepartitionRepo.saveAll(repartitionsActives);

        logCloture(projet, "Clôture");

        return "Projet clôturé.";
    }

    /**
     * Rouvre un projet clôturé — refusé si sa date de fin prévue est déjà passée :
     * un projet rouvert au-delà de cette date resterait "actif" indéfiniment, avec
     * un cheptel qu'on continuerait à saisir sur un lot censé être terminé. Refusé
     * aussi si le projet n'est pas clôturé (rien à rouvrir).
     *
     * Le stock d'aliment déjà transféré vers un autre projet lors de la clôture
     * (voir transfererStock) n'est PAS restitué par cette méthode : la réouverture
     * ne touche jamais Alimentation/ConsommationAliment, uniquement le flag archive
     * — le transfert reste définitif, comme un vrai mouvement de stock physique.
     */
    @Override
    @Transactional
    public String rouvrirProjet(String uniqueId) {
        Projets projet = projetsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException(
                        "Projet non trouvé avec l'uniqueId : " + uniqueId));

        if (!Boolean.TRUE.equals(projet.getInitialisation().getArchive())) {
            throw new RuntimeException("Ce projet n'est pas clôturé.");
        }
        if (projet.getFinPrevue() != null && LocalDate.now().isAfter(projet.getFinPrevue())) {
            throw new RuntimeException(
                "Impossible de rouvrir : la date de fin prévue (" + projet.getFinPrevue() + ") est déjà passée."
            );
        }

        projet.getInitialisation().setArchive(false);
        projetsRepo.save(projet);
        logCloture(projet, "Réouverture");

        return "Projet rouvert.";
    }

    private void logCloture(Projets projet, String action) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(
                currentUser.getId(),
                projet.getId(),
                "Projet",
                action + " du projet '" + projet.getTitre() + "'"
            );
        }
    }

    /**
     * Transfère le stock d'aliment restant (achats - consommations) d'un projet vers
     * un autre, typiquement juste avant sa clôture — pour ne pas perdre un reste de
     * sac déjà payé. Valorisé au prix moyen d'achat au kg du projet source (pas juste
     * la quantité), sinon la valeur du stock disparaîtrait du bilan à la clôture.
     * Concrètement : un nouvel achat (Alimentation) dans le projet cible, compensé par
     * une consommation dans le projet source pour que son stock retombe à 0.
     */
    @Override
    @Transactional
    public String transfererStock(String projetSourceUniqueId, String projetCibleUniqueId) {
        if (projetSourceUniqueId.equals(projetCibleUniqueId)) {
            throw new IllegalArgumentException("Le projet cible doit être différent du projet à clôturer.");
        }
        Projets source = projetsRepo.findByUniqueId(projetSourceUniqueId)
                .orElseThrow(() -> new RuntimeException("Projet source introuvable : " + projetSourceUniqueId));
        Projets cible = projetsRepo.findByUniqueId(projetCibleUniqueId)
                .orElseThrow(() -> new RuntimeException("Projet cible introuvable : " + projetCibleUniqueId));

        double achete = nz(alimentationRepo.sumAcheteByProjetId(source.getId()));
        double consomme = nz(consommationAlimentRepo.sumConsommeByProjetId(source.getId()));
        double restantKg = achete - consomme;
        if (restantKg <= 0) {
            return "Aucun stock d'aliment restant à transférer.";
        }

        double coutAchete = nz(alimentationRepo.sumCoutAcheteByProjetId(source.getId()));
        double prixMoyenKg = achete > 0 ? coutAchete / achete : 0.0;
        double valeurTransferee = Math.round(restantKg * prixMoyenKg * 100) / 100.0;

        Utilisateurs currentUser = getCurrentUserSafe();
        Farm farm = currentUser != null ? currentUser.getFarm() : null;

        Alimentation entree = new Alimentation();
        entree.setUniqueId(generateUID());
        entree.setNomAliment("Transfert depuis " + source.getCode());
        entree.setSac(0.0);
        entree.setQuantiteKg(restantKg);
        entree.setCoutTotal(valeurTransferee);
        entree.setDateDistribution(LocalDate.now());
        entree.setObservations("Stock restant transféré lors de la clôture du projet " + source.getCode());
        entree.setProjet(cible);
        entree.setFarm(farm);
        entree.setInitialisation(Initialisation.init());
        alimentationRepo.save(entree);

        ConsommationAliment sortie = new ConsommationAliment();
        sortie.setUniqueId(generateUID());
        sortie.setProjet(source);
        sortie.setDate(LocalDate.now());
        sortie.setQuantiteKg(restantKg);
        sortie.setFarm(farm);
        sortie.setInitialisation(Initialisation.init());
        consommationAlimentRepo.save(sortie);

        if (currentUser != null) {
            logs.addLogs(
                currentUser.getId(),
                source.getId(),
                "Projet",
                "Transfert de " + restantKg + " kg d'aliment (valeur " + valeurTransferee
                    + " FCFA) vers le projet '" + cible.getCode() + "'"
            );
        }

        return "Stock transféré : " + restantKg + " kg vers " + cible.getCode() + ".";
    }

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    @Override
    @Transactional
    public ProjetsDTO updateProjet(String uniqueId, ProjetUpdate data) {
        
        // 1. Rérupérer le projet existant
        Projets projet = projetsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Projet non trouvé avec l'uniqueId : " + uniqueId));

        // 2. Vérifier et récupérer la race (si fournie)
        Long raceId = data.getRaceId();

        if (raceId != null) {
            Race race = raceRepo.findById(raceId)
                    .orElseThrow(() -> new RuntimeException("Race non trouvée avec l'id : " + data.getRaceId()));
            projet.setRace(race);
        }

        // 3. Mettre à jour les champs simples (seulement si non null pour permettre les mises à jour partielles)
        if (data.getTitre() != null) {
            projet.setTitre(data.getTitre());
        }
        if (data.getResponsableId() != null) {
            Utilisateurs responsable = utilisateursRepo.findById(data.getResponsableId())
                    .orElseThrow(() -> new RuntimeException("Responsable non trouvé avec l'id : " + data.getResponsableId()));
            projet.setResponsable(responsable);
        }
        if (data.getResponsableProductionId() != null) {
            Utilisateurs responsableProduction = utilisateursRepo.findById(data.getResponsableProductionId())
                    .orElseThrow(() -> new RuntimeException("Responsable production non trouvé avec l'id : " + data.getResponsableProductionId()));
            projet.setResponsableProduction(responsableProduction);
        }
        if (data.getResponsableFinanceId() != null) {
            Utilisateurs responsableFinance = utilisateursRepo.findById(data.getResponsableFinanceId())
                    .orElseThrow(() -> new RuntimeException("Responsable finance non trouvé avec l'id : " + data.getResponsableFinanceId()));
            projet.setResponsableFinance(responsableFinance);
        }
        if (data.getDateDebut() != null) {
            projet.setDebut(parseDate(data.getDateDebut()));
        }
        if (data.getDateFinPrevue() != null) {
            projet.setFinPrevue(parseDate(data.getDateFinPrevue()));
        }
        if (data.getNbSujets() != null) {
            projet.setNbSujets(data.getNbSujets());
        }
        if (data.getPuSujet() != null) {
            projet.setPuSujet(data.getPuSujet());
        }
        if (data.getAutresDepense() != null) {
            projet.setAutresDepense(data.getAutresDepense());
        }
        if (data.getObjectif() != null) {
            projet.setObjectif(Objectif.valueOf(data.getObjectif()));
        }
        if (data.getFournisseursPoussins() != null) {
            projet.setFournisseurs_poussins(data.getFournisseursPoussins());
        }

        // 4. Recalculer le CA total si nbSujets, puSujet ou autresDepense ont changé
        double nbSujets = projet.getNbSujets() != null ? projet.getNbSujets() : 0;
        double puSujet = projet.getPuSujet() != null ? projet.getPuSujet() : 0;
        double autresDepense = projet.getAutresDepense() != null ? projet.getAutresDepense() : 0;
        
        double caTotalSujets = (nbSujets * puSujet) + autresDepense;
        projet.setCaTotalSujets(caTotalSujets);
        // chiffreAffaires n'est plus recalculé ici : voir computeChiffreAffairesReel,
        // le DTO l'écrase avec la somme réelle des transactions "entrée" validées.

        // 5. Sauvegarder
        Projets updatedProjet = projetsRepo.save(projet);

        // 6. Log
        Utilisateurs currentUser = getCurrentUserSafe();
        syncAchatSujets(updatedProjet, currentUser);
        syncAutresCharges(updatedProjet, currentUser);
        if (currentUser != null) {
            logs.addLogs(
                currentUser.getId(),
                updatedProjet.getId(),
                "Projet",
                "Mise à jour du projet '" + updatedProjet.getTitre() 
                    + "' (" + updatedProjet.getNbSujets() + " sujets, Objectif : " 
                    + updatedProjet.getObjectif() + ") | Coût Achat total : " 
                    + updatedProjet.getCaTotalSujets() + " FCFA"
            );
        }

        return ProjetsDTO.fromEntity(updatedProjet, computeTauxPonte(updatedProjet), computeMortaliteCumulee(updatedProjet), computeChiffreAffairesReel(updatedProjet),
                computeSujetsReformesCumulee(updatedProjet), computeEffectifVivant(updatedProjet));
    }
    
    /**
     * Liste des projets pour les sélecteurs (dropdowns web + mobile). Un ADMIN/SUPER_ADMIN
     * voit tous les projets de sa ferme ; un PRODUCTEUR/FINANCIER ne voit que ceux où il
     * est explicitement désigné responsableProduction ou responsableFinance — avant ce
     * correctif, n'importe quel utilisateur authentifié recevait tous les projets de
     * toutes les fermes, sans distinction de rôle ni d'affectation.
     */
    @Override
    public List<ProjetsSelect> selectEntity() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null) {
            return List.of();
        }

        boolean isAdmin = currentUser.getRoles() != null && currentUser.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
        // COMPTABLE reste farm-wide (comme sur Comptabilité) même dans ce sélecteur —
        // contrairement à RESPONSABLE/VENTE/PRODUCTION qui ne voient que leurs projets
        // assignés, un COMPTABLE doit pouvoir rattacher une transaction manuelle à
        // n'importe quel projet de la ferme.
        boolean isPureComptable = currentUser.getRoles() != null && !currentUser.getRoles().isEmpty()
                && currentUser.getRoles().stream().allMatch(r -> "COMPTABLE".equalsIgnoreCase(r.getRole()));

        List<Projets> projets;
        if (isAdmin && currentUser.getFarm() == null) {
            // Compte admin système de bootstrap, jamais rattaché à une ferme (voir
            // MlApplication.run()) : accès complet plutôt que bloqué par l'absence de ferme.
            projets = projetsRepo.findByInitialisation_RemovedFalse();
        } else if (currentUser.getFarm() == null) {
            projets = List.of();
        } else if (isAdmin || isPureComptable) {
            projets = projetsRepo.findAllActiveByFarm(currentUser.getFarm().getId());
        } else {
            projets = projetsRepo.findAssignedToUser(currentUser.getFarm().getId(), currentUser.getUniqueId());
        }

        return projets.stream()
                .map(ProjetsSelect::selectEntity)
                .toList();
    }

    /**
     * Derniers projets associés à un utilisateur (responsable production et/ou
     * finance), pour la modale "Profil & Accès Mobile Utilisateur" côté web. Portée
     * par la ferme de l'ADMIN appelant (comme selectEntity ci-dessus) : si
     * l'utilisateur ciblé n'appartient pas à cette ferme, la liste renvoyée est vide
     * plutôt que de risquer une fuite de données inter-fermes.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProjetAssigneDTO> getProjetsAssignes(String userUniqueId, int limit) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return List.of();
        }

        List<Projets> projets = projetsRepo.findRecentAssignedToUser(
                currentUser.getFarm().getId(), userUniqueId, PageRequest.of(0, limit));

        return projets.stream().map(p -> {
            List<String> roles = new java.util.ArrayList<>();
            if (p.getResponsableProduction() != null && userUniqueId.equals(p.getResponsableProduction().getUniqueId())) {
                roles.add("PRODUCTION");
            }
            if (p.getResponsableFinance() != null && userUniqueId.equals(p.getResponsableFinance().getUniqueId())) {
                roles.add("COMPTABLE");
            }
            return ProjetAssigneDTO.builder()
                    .uniqueId(p.getUniqueId())
                    .code(p.getCode())
                    .titre(p.getTitre())
                    .createdAt(p.getInitialisation() != null ? p.getInitialisation().getCreatedAt() : null)
                    .active(p.getInitialisation() == null || !Boolean.TRUE.equals(p.getInitialisation().getArchive()))
                    .roles(roles)
                    .build();
        }).toList();
    }

}
