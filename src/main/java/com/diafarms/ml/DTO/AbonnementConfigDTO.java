package com.diafarms.ml.DTO;

import com.diafarms.ml.commons.AbonnementEcheance;
import com.diafarms.ml.models.AbonnementConfig;

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
public class AbonnementConfigDTO {
    private Double prixMensuel;
    private Double prixAnnuel;
    private Integer dureeEssaiJours;
    private Integer dureeGraceHeures; // historique, plus utilisé pour le calcul
    // Toujours renseigné (null en base = 5, voir AbonnementEcheance.delaiGraceJours).
    private Integer delaiGraceJours;

    public static AbonnementConfigDTO fromEntity(AbonnementConfig c) {
        if (c == null) return null;
        return AbonnementConfigDTO.builder()
                .prixMensuel(c.getPrixMensuel())
                .prixAnnuel(c.getPrixAnnuel())
                .dureeEssaiJours(c.getDureeEssaiJours())
                .dureeGraceHeures(c.getDureeGraceHeures())
                .delaiGraceJours(AbonnementEcheance.delaiGraceJours(c))
                .build();
    }
}
