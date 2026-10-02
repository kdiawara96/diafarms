package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Un mode de paiement d'une ferme (voir ModesPaiementService). `code` est la valeur à
// renvoyer avec un paiement ; `historique` = valeur de l'enum ModePaiement connue des
// anciens téléphones ; `personnalise` = ajouté par la ferme (code PERSO_...).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ModePaiementFermeDTO {
    private String code;
    private String libelle;
    private boolean actif;
    private boolean historique;
    private boolean personnalise;
}
