package com.diafarms.ml.services;

import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.DTO.VenteDiverseDTO;
import com.diafarms.ml.request.create.TransactionCreate;
import com.diafarms.ml.request.create.VenteDiverseCreate;
import com.diafarms.ml.request.update.VenteDiverseUpdate;

public interface VenteDiverseService {

    String CATEGORIE_FIENTES = "Vente fientes";
    String CATEGORIE_AUTRE = "Autre vente";

    VenteDiverseDTO create(VenteDiverseCreate data);
    VenteDiverseDTO update(String uniqueId, VenteDiverseUpdate data);
    /** Suppression directe (ADMIN/RESPONSABLE), motif obligatoire ; ou restauration. */
    String deleteOrRecover(String uniqueId, String motif);
    VenteDiverseDTO demanderSuppression(String uniqueId, String motif);
    VenteDiverseDTO confirmerSuppression(String uniqueId);
    VenteDiverseDTO annulerDemandeSuppression(String uniqueId);

    /** Ancien chemin (entrée d'argent catégorie "Vente fientes"/"Autre vente", encore
     * envoyé par les APK déjà installés et leurs saisies hors ligne) : crée la vraie
     * vente et renvoie sa transaction, comme l'aurait fait /transactions/create. */
    TransactionDTO createDepuisTransaction(TransactionCreate data);

    static boolean estVenteDiverse(TransactionCreate data) {
        return data != null && "ENTREE".equalsIgnoreCase(data.getType())
                && (CATEGORIE_FIENTES.equals(data.getCategorie()) || CATEGORIE_AUTRE.equals(data.getCategorie()));
    }
}
