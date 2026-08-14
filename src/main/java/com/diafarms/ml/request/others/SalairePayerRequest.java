package com.diafarms.ml.request.others;

import lombok.Data;

@Data
public class SalairePayerRequest {
    private String employeUniqueId;
    private String periode; // "AAAA-MM"
    // Nombre de jours/heures travaillés pour cette période — obligatoire si le
    // Salaire est en mode JOURNALIER/HORAIRE (montant = tauxBase × quantite), ignoré
    // en MENSUEL. Saisi à la main : Diafarms n'a pas de système de pointage.
    private Double quantite;
    private Double montant; // optionnel — force le montant, prioritaire sur tauxBase×quantite
    private String description; // optionnel
}
