package com.diafarms.ml.request.others;

import lombok.Data;

// Console SUPER_ADMIN : ajustement du crédit d'une ferme. montant en FCFA, positif pour
// ajouter, négatif pour retirer ; motif obligatoire (montré à la ferme).
@Data
public class AdminAjustementRequest {
    private Double montant;
    private String motif;
    // Identifiant de l'envoi : un même envoi rejoué n'ajuste jamais deux fois.
    private String requestId;
}
