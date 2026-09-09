package com.diafarms.ml.request.others;

import lombok.Data;

// Corrige un paiement déjà enregistré (erreur de saisie sur le montant) — voir
// SalaireServiceImpl.modifierPaiement. Ne touche jamais à la période ni à l'employé :
// pour ça, il faut supprimer ce paiement et en refaire un nouveau.
@Data
public class SalairePaiementUpdateRequest {
    private Double montant;
}
