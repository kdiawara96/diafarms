package com.diafarms.ml.request.create;

import lombok.Data;

// Pas de projetUniqueId : la répartition entre projets contributeurs est calculée
// automatiquement côté serveur, DANS le magasin choisi — voir VenteReformeImpl.
@Data
public class VenteReformeCreate {
    private String date;
    private String heure; // "HH:mm", optionnel
    private String magasinUniqueId; // obligatoire, plafonne la quantité vendable
    private String clientUniqueId; // optionnel — voir VenteReforme.client
    private Integer nombreSujets;
    private Double prixUnitaire; // prix par tête (TETE) ou prix par kg (KILO)
    private Double montant;
    private Double montantRapporte; // optionnel : ce que le vendeur a réellement rapporté
    // "TETE" (défaut si absent) ou "KILO" — voir TypeVenteReforme.
    private String typeVente;
    private Double poidsTotalKg; // obligatoire si typeVente=KILO, ignoré sinon
}
