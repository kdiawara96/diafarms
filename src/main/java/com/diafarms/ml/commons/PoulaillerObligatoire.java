package com.diafarms.ml.commons;

import java.util.List;

import org.springframework.stereotype.Component;

import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.repository.OccupationBatimentRepo;

import lombok.RequiredArgsConstructor;

// Collecte d'œufs, sortie d'aliment, soins, mortalité et réforme se passent dans UN
// poulailler : sans lui, ni le suivi par poulailler (ponte, mortalité, consommation)
// ni les plafonds par poulailler ne tiennent. Le poulailler est donc obligatoire et doit
// être un de ceux occupés par le projet. Seule exception : un projet qui n'occupe qu'un
// poulailler, où il est déduit (anciens APK, saisies hors ligne faites sans ce champ).
// L'achat d'aliment n'est PAS concerné : il est lié au projet, pas à un poulailler.
@Component
@RequiredArgsConstructor
public class PoulaillerObligatoire {

    private final OccupationBatimentRepo occupationBatimentRepo;

    /** Poulailler d'une nouvelle saisie (batimentUniqueId vide = déduit si possible). */
    public Batiment resoudre(Projets projet, String batimentUniqueId) {
        List<Batiment> poulaillers = occupationBatimentRepo.findBatimentsByProjetId(projet.getId());
        if (batimentUniqueId == null || batimentUniqueId.isBlank()) {
            if (poulaillers.size() == 1) {
                return poulaillers.get(0);
            }
            if (poulaillers.isEmpty()) {
                throw new IllegalArgumentException("Ce projet n'occupe aucun poulailler : affectez-lui d'abord un poulailler.");
            }
            throw new IllegalArgumentException("Le poulailler est obligatoire : choisissez dans quel poulailler cela s'est passé.");
        }
        return poulaillers.stream()
                .filter(b -> batimentUniqueId.equals(b.getUniqueId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Ce poulailler n'est pas occupé par ce projet."));
    }

    /** Poulailler après modification : null = inchangé, mais une ancienne saisie sans
     * poulailler doit en recevoir un dès qu'on la modifie. */
    public Batiment resoudrePourModification(Projets projet, Batiment actuel, String batimentUniqueId) {
        if (batimentUniqueId == null) {
            return actuel != null ? actuel : resoudre(projet, null);
        }
        return resoudre(projet, batimentUniqueId);
    }
}
