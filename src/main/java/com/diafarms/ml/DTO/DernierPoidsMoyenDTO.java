package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Poids moyen de la dernière session de pesée TERMINEE d'un projet — sert à estimer le
// poids d'une commande/vente de réforme au kilo (GET /pesees/dernier-poids-moyen).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DernierPoidsMoyenDTO {
    private String projetUniqueId;
    private Double poidsMoyenKg;
    private LocalDateTime dateFin;
    private String sessionUniqueId;
    private Integer nombreTotalSujets;
}
