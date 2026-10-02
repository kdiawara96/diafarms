package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Pays et devise de la ferme (voir commons.Devise, CataloguePays). Défaut des fermes
// existantes : ML / XOF (FCFA, 0 décimale).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DeviseFermeDTO {
    private String pays;       // ISO 3166 alpha-2, ou "AUTRE"
    private String paysNom;
    private String devise;     // ISO 4217
    private String symbole;    // unité affichée après le montant : "FCFA", "€", "₦"...
    private String nomDevise;
    private int decimales;     // 0 pour XOF/XAF/GNF, 2 sinon
}
