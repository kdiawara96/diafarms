package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

// Dépenses validées d'un même rattachement (site et/ou poulailler) sur une période : sert au
// rapport "dépenses par site / par poulailler" (Reporting.tsx). Seules les dépenses AYANT un
// site ou un poulailler y figurent ; le reste (ferme entière) n'est pas listé ici.
@Getter
@Setter
@Builder
@AllArgsConstructor
public class DepenseRattachementDTO {
    private String siteUniqueId;
    private String siteNom;
    private String batimentUniqueId;
    private String batimentNom;
    private Double total;
    private Long nombre;
}
