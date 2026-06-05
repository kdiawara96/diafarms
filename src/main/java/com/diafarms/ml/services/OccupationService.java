package com.diafarms.ml.services;


import com.diafarms.ml.models.OccupationBatiment;

public interface OccupationService {
    /**
     * Lie un projet à un bâtiment (Crée une occupation)
     */
    OccupationBatiment assignerBatimentAProjet(Long projetId, Long batimentId, Integer nbSujets, String dateEntree);

    /**
     * Modifie une liaison existante (ex: changer de bâtiment ou ajuster les dates/sujets)
     */
    OccupationBatiment modifierOccupation(Long occupationId, Long nouveauBatimentId, Integer nouveauNbSujets, String dateEntree, String dateSortie);

    /**
     * Supprime ou termine une liaison (Libère le bâtiment en mettant une date de sortie ou en supprimant le record)
     * Ici, on va plutôt "clôturer" l'occupation pour garder l'historique, c'est plus propre en gestion d'élevage.
     */
    void libererBatiment(Long occupationId, String dateSortie);
}
