package com.diafarms.ml.ServiceImpl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Batiment.StatutBatiment;
import com.diafarms.ml.models.OccupationBatiment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.OccupationBatimentRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.services.OccupationService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Service
@RequiredArgsConstructor // Génère le constructeur pour l'injection des repositories
public class OccupationBatimentServiceImpl implements OccupationService {

    private final OccupationBatimentRepo occupationRepository;
    private final ProjetsRepo projetsRepository;
    private final BatimentRepo batimentRepository;

    @Override
    @Transactional
    public OccupationBatiment assignerBatimentAProjet(Long projetId, Long batimentId, Integer nbSujets, String dateEntree) {

        LocalDate dateEntreeParsed = convertirEnDate(dateEntree, LocalDate.now());

        if (projetId == null) {
            throw new RuntimeException("L'identifiant du projet ne peut pas être nul.");
        }
        if (batimentId == null) {
            throw new RuntimeException("L'identifiant du bâtiment ne peut pas être nul.");
        }
         if (nbSujets != null && nbSujets < 0) {
            throw new RuntimeException("Le nombre de sujets dans le bâtiment ne peut pas être négatif.");
        }
         if (dateEntreeParsed != null && dateEntreeParsed.isAfter(LocalDate.now())) {
            throw new RuntimeException("La date d'entrée ne peut pas être dans le futur.");
        }
        // 1. Récupération des entités
        Projets projet = projetsRepository.findById(projetId)
                .orElseThrow(() -> new RuntimeException("Projet non trouvé avec l'id : " + projetId));
        
        Batiment batiment = batimentRepository.findById(batimentId)
                .orElseThrow(() -> new RuntimeException("Bâtiment non trouvé avec l'id : " + batimentId));

        // 2. Vérification de la disponibilité du bâtiment
        if (batiment.getStatut() == StatutBatiment.OCCUPE) {
            throw new RuntimeException("Le bâtiment " + batiment.getNom() + " est déjà occupé.");
        }

        // 3. Création de la liaison (Occupation)
        OccupationBatiment occupation = new OccupationBatiment();
        occupation.setProjet(projet);
        occupation.setBatiment(batiment);
        occupation.setNbSujetsDansBatiment(nbSujets);
        occupation.setDateEntree(dateEntreeParsed);

        // 4. Mise à jour du statut du bâtiment
        batiment.setStatut(StatutBatiment.OCCUPE);
        batimentRepository.save(batiment);

        return occupationRepository.save(occupation);
    }

    @Override
    @Transactional
    public OccupationBatiment modifierOccupation(Long occupationId, Long nouveauBatimentId, Integer nouveauNbSujets, String dateEntree, String dateSortie) {
        
         if(occupationId == null) {
            throw new RuntimeException("L'identifiant de l'occupation ne peut pas être nul.");
        }

            // if (dateSortie != null && dateSortie.isAfter(LocalDate.now())) {
            //     throw new RuntimeException("La date de sortie ne peut pas être dans le futur.");
            // }
            
            LocalDate dateEntreeParsed = convertirEnDate(dateEntree, LocalDate.now());
            if (dateEntreeParsed != null && dateEntreeParsed.isAfter(LocalDate.now())) {
                throw new RuntimeException("La date d'entrée ne peut pas être dans le futur.");
            }
            
            if (nouveauNbSujets != null && nouveauNbSujets < 0) {
                throw new RuntimeException("Le nombre de sujets dans le bâtiment ne peut pas être négatif.");
            }
            
            if (nouveauBatimentId != null) {
                Batiment nouveauBatiment = batimentRepository.findById(nouveauBatimentId)
                        .orElseThrow(() -> new RuntimeException("Nouveau bâtiment non trouvé avec l'id : " + nouveauBatimentId));
                
                if (nouveauBatiment.getStatut() == StatutBatiment.OCCUPE) {
                    throw new RuntimeException("Le nouveau bâtiment est déjà occupé.");
                }
            }
        // 1. Trouver l'occupation existante
        OccupationBatiment occupation = occupationRepository.findById(occupationId)
                .orElseThrow(() -> new RuntimeException("Occupation non trouvée"));

        // 2. Si le bâtiment change, il faut libérer l'ancien et occuper le nouveau
        if (!occupation.getBatiment().getId().equals(nouveauBatimentId)) {
            // Libérer l'ancien bâtiment
            Batiment ancienBatiment = occupation.getBatiment();
            ancienBatiment.setStatut(StatutBatiment.DISPONIBLE);
            batimentRepository.save(ancienBatiment);

            // Supprimer l'ancienne occupation
            occupationRepository.delete(occupation);

            if (nouveauBatimentId == null) {
                throw new RuntimeException("Le nouvel identifiant de bâtiment ne peut pas être nul.");
            }

            // Occuper le nouveau bâtiment
            Batiment nouveauBatiment = batimentRepository.findById(nouveauBatimentId)
                    .orElseThrow(() -> new RuntimeException("Nouveau bâtiment non trouvé"));
            
            if (nouveauBatiment.getStatut() == StatutBatiment.OCCUPE) {
                throw new RuntimeException("Le nouveau bâtiment est déjà occupé.");
            }
            
            nouveauBatiment.setStatut(StatutBatiment.OCCUPE);
            batimentRepository.save(nouveauBatiment);
            
            occupation.setBatiment(nouveauBatiment);
        }

        // 3. Mettre à jour les autres données
        occupation.setNbSujetsDansBatiment(nouveauNbSujets);
        occupation.setDateEntree(dateEntreeParsed);
        occupation.setDateSortie(dateSortie != null ? convertirEnDate(dateSortie, null) : null);

        // Si une date de sortie est spécifiée et qu'elle est passée ou aujourd'hui, on libère le bâtiment
        if (occupation.getDateSortie() != null && !occupation.getDateSortie().isAfter(LocalDate.now())) {
            occupation.getBatiment().setStatut(StatutBatiment.DISPONIBLE);
        }

        return occupationRepository.save(occupation);
    }

    @Override
    @Transactional
    public void libererBatiment(Long occupationId, String dateSortie) {

        LocalDate dateSortieParsed = convertirEnDate(dateSortie, null);
        if (dateSortieParsed != null && dateSortieParsed.isAfter(LocalDate.now())) {
            throw new RuntimeException("La date de sortie ne peut pas être dans le futur.");
        }
        if(occupationId == null) {
            throw new RuntimeException("L'identifiant de l'occupation ne peut pas être nul.");
        }
        // 1. Trouver la liaison
        OccupationBatiment occupation = occupationRepository.findById(occupationId)
                .orElseThrow(() -> new RuntimeException("Occupation non trouvée"));

        // 2. Mettre à jour la date de sortie de la liaison
        occupation.setDateSortie(dateSortieParsed);
        occupationRepository.save(occupation);

        // 3. Libérer le bâtiment pour qu'il redevienne disponible
        Batiment batiment = occupation.getBatiment();
        batiment.setStatut(StatutBatiment.DISPONIBLE);
        batimentRepository.save(batiment);
    }

    /**
     * Méthode utilitaire pour parser la String en LocalDate proprement
     */
    private LocalDate convertirEnDate(String dateStr, LocalDate valeurParDefaut) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return valeurParDefaut;
        }
        try {
            return LocalDate.parse(dateStr.trim()); // Attend le format "YYYY-MM-DD"
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Le format de la date est incorrect. Utilisez 'YYYY-MM-DD' (Ex: 2026-06-05)");
        }
    }
}