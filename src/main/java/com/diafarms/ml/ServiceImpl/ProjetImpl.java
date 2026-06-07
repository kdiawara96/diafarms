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
import com.diafarms.ml.services.LogsServices;
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
    public ProjetsDTO getProjetByUniqueId(String uniqueId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getProjetByUniqueId'");
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

        // 2. Vérifier et récupérer la race
        Race race = raceRepo.findById(data.getRaceId())
                .orElseThrow(() -> new RuntimeException("Race non trouvée avec l'id : " + data.getRaceId()));

        // 3. Vérifier et récupérer les responsables
        Utilisateurs responsableProduction = null;
        if (data.getResponsableProductionId() != null) {
            responsableProduction = utilisateursRepo.findById(data.getResponsableProductionId())
                    .orElseThrow(() -> new RuntimeException("Responsable production non trouvé avec l'id : " + data.getResponsableProductionId()));
        }

        Utilisateurs responsableFinance = null;
        if (data.getResponsableFinanceId() != null) {
            responsableFinance = utilisateursRepo.findById(data.getResponsableFinanceId())
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
        projet.setChiffreAffaires(caTotalSujets); // 
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
                // Vérifier le bâtiment
                Batiment batiment = batimentRepo.findById(occData.getBatimentId())
                        .orElseThrow(() -> new RuntimeException("Bâtiment non trouvé avec l'id : " + occData.getBatimentId()));

                if (batiment.getStatut() != null && batiment.getStatut().toString().equals("OCCUPE")) {
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

                // Mettre à jour le statut du bâtiment
                batiment.setStatut(StatutBatiment.OCCUPE);
                batimentRepo.save(batiment);
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
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteOrRecoverProjet'");
    }

    
    
}
