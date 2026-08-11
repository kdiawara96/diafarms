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
public class SoldeClientDTO {
    private String clientUniqueId;
    private String clientNom;
    // Positif = le client doit de l'argent à la ferme (vente à crédit), négatif = avance.
    private double solde;
}
