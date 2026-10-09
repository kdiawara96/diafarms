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
    // Prix par poule (voir commons/AbonnementTarif) : toujours renseignés (valeurs par
    // défaut si vides en base). prixMensuel/prixAnnuel ci-dessus : ancien tarif fixe,
    // plus utilisé pour le prix des fermes.
    private Double prixParPoule;
    private Double prixMinimumMensuel;
    private Integer moisOffertsAnnuel;
    private Integer arrondi;
    // Crédit prépayé (voir commons/AbonnementCredit), toujours renseignés.
    private Double bonusSeuil;
    private Double bonusPourcent;
    private Integer seuilSurDevis;
    private Double creditParrainage;

    public static AbonnementConfigDTO fromEntity(AbonnementConfig c) {
        if (c == null) return null;
        com.diafarms.ml.commons.AbonnementTarif.Regles r = com.diafarms.ml.commons.AbonnementTarif.regles(c);
        return AbonnementConfigDTO.builder()
                .prixMensuel(c.getPrixMensuel())
                .prixAnnuel(c.getPrixAnnuel())
                .dureeEssaiJours(c.getDureeEssaiJours())
                .dureeGraceHeures(c.getDureeGraceHeures())
                .delaiGraceJours(AbonnementEcheance.delaiGraceJours(c))
                .prixParPoule(r.prixParPoule())
                .prixMinimumMensuel(r.prixMinimumMensuel())
                .moisOffertsAnnuel(r.moisOffertsAnnuel())
                .arrondi(r.arrondi())
                .bonusSeuil(com.diafarms.ml.commons.AbonnementCredit.regles(c).bonusSeuil())
                .bonusPourcent(com.diafarms.ml.commons.AbonnementCredit.regles(c).bonusPourcent())
                .seuilSurDevis(com.diafarms.ml.commons.AbonnementCredit.regles(c).seuilSurDevis())
                .creditParrainage(com.diafarms.ml.commons.AbonnementCredit.regles(c).creditParrainage())
                .build();
    }
}
