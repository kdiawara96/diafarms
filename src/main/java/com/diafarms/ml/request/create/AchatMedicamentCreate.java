package com.diafarms.ml.request.create;

import lombok.Data;

// Création ET modification (en modification, null = inchangé).
@Data
public class AchatMedicamentCreate {
    private String nom; // obligatoire
    private String forme; // LIQUIDE | POUDRE | COMPRIME | AUTRE
    private String unite; // obligatoire : flacon, ml, sachet, g, boîte...
    private Double quantite; // obligatoire, > 0
    private Double prixUnitaire; // facultatif ; absent = coutTotal / quantite
    private Double coutTotal; // obligatoire, > 0 (montant payé)
    private String dateAchat; // AAAA-MM-JJ, défaut aujourd'hui
    private String fournisseur;
    private String observations;
    private String batimentUniqueId; // facultatif ("" = retirer en modification)
    private String projetUniqueId; // modification seulement : changer de projet
}
