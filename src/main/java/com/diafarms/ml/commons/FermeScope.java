package com.diafarms.ml.commons;

import java.util.Objects;

import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;

// Isolation des fermes pour les endpoints qui chargent une entité par son uniqueId
// (détail, modification, suppression) : une entité d'une autre ferme est traitée
// exactement comme une entité inexistante (même message "introuvable", 400 via
// IllegalArgumentException), pour ne pas révéler ce qui existe ailleurs. Même
// principe que CommandeServiceImpl.commandeFarmScoped / FactureServiceImpl.
// factureFarmScoped, factorisé ici pour les autres services.
public final class FermeScope {

    private FermeScope() {}

    public static boolean memeFerme(Farm farm, Utilisateurs u) {
        return farm != null && u != null && u.getFarm() != null
                && Objects.equals(farm.getId(), u.getFarm().getId());
    }

    /** Lève IllegalArgumentException(messageIntrouvable) si l'entité n'existe pas
     * (farm de l'entité passée null parce que l'entité est null côté appelant) ou
     * n'appartient pas à la ferme de l'utilisateur courant. */
    public static void verifier(Farm farm, Utilisateurs u, String messageIntrouvable) {
        if (!memeFerme(farm, u)) {
            throw new IllegalArgumentException(messageIntrouvable);
        }
    }
}
