package com.diafarms.ml.request.others;

import lombok.Data;

// Console SUPER_ADMIN : tarif spécial d'une ferme (prix fixe par mois, en FCFA), qui
// remplace le prix par poule. prixMensuelFixe null = retirer le tarif spécial.
@Data
public class AdminPrixFixeRequest {
    private Double prixMensuelFixe;
    private String motif;
}
