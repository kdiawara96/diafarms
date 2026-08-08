package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SoldeVendeurDTO {
    private String vendeurUniqueId;
    private String vendeurNom;
    // Positif = le vendeur doit de l'argent à la ferme (dette), négatif = crédit.
    private double solde;
}
