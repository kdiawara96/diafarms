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

import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Objectif;
import com.diafarms.ml.models.Alimentation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.OccupationBatiment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Race;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.Vaccination;
import com.diafarms.ml.models.Batiment.StatutBatiment;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.AlimentationRepo;
import com.diafarms.ml.repository.BatimentRepo;
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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjetImpl implements ProjetServices {

    private final ProjetsRepo projetsRepo;
    private final RaceRepo raceRepo;
    private final UtilisateursRepo utilisateursRepo;
    private final AlimentationRepo alimentationRepo;
    private final VaccinationRepo vaccinationRepo;
    private final OccupationBatimentRepo occupationBatimentRepo;
    private final BatimentRepo batimentRepo;
    private final OtherService otherService;
    private final LogsServices logs;

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
        if (currentUser != null) {
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
                .map(ProjetsDTO::fromEntityList)
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
        
        return ProjetsDTO.fromEntity(projet);
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

        // 4. Créer le projet
        Projets projet = new Projets();

        projet.setUniqueId(generateUID());
        projet.setCode(generateCode());
        projet.setTitre(data.getTitre());
        projet.setResponsable(data.getNomResponsable());
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

        // Calculer CA prévu et marge nette initiale
        double caTotalSujets = (data.getNbSujets() != null ? data.getNbSujets() : 0) * (data.getPuSujet() != null ? data.getPuSujet() : 0) + (data.getAutresDepense() != null ? data.getAutresDepense() : 0);
        projet.setCaTotalSujets(caTotalSujets);
        projet.setChiffreAffaires(0.0); 
        projet.setMargeNette(0.0); // Sera calculé plus tard avec tous les coûts réels

        projet.setInitialisation(Initialisation.init());

        // Sauvegarder le projet d'abord (pour avoir l'ID pour les relations)
        Projets savedProjet = projetsRepo.save(projet);

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

            alimentationRepo.save(alimentation);
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

                vaccinationRepo.save(vaccination);
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

                if (batiment != null && batiment.getStatut() != null && batiment.getStatut().toString().equals("OCCUPE")) {
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
    public String deleteOrRecoverProjet(String uniqueId) {
        Projets projet = projetsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException(
                        "Projet non trouvé avec l'uniqueId : " + uniqueId));

        projet.getInitialisation()
                .setRemoved(!projet.getInitialisation().getRemoved());

        projetsRepo.save(projet);

        return projet.getInitialisation().getRemoved()
                ? "Projet supprimé."
                : "Projet récupéré.";
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
        if (data.getNomResponsable() != null) {
            projet.setResponsable(data.getNomResponsable());
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
        // projet.setChiffreAffaires(caTotalSujets);

        // 5. Sauvegarder
        Projets updatedProjet = projetsRepo.save(projet);

        // 6. Log
        Utilisateurs currentUser = getCurrentUserSafe();
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

        return ProjetsDTO.fromEntity(updatedProjet);
    }

    
    
}
