package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.MouvementCredit;

// Une ligne du compte de crédit (page Abonnement, fiche de la console).
public record MouvementCreditDTO(
        String uniqueId,
        String type,
        double montant,
        double soldeApres,
        LocalDateTime date,
        String mois,
        Double poulesMoyenne,
        Integer jours,
        Integer joursMois,
        boolean prixFixe,
        String libelle,
        String auteurNom) {

    // auteur : seulement pour la console (la ferme ne voit pas qui de l'équipe a agi).
    public static MouvementCreditDTO of(MouvementCredit m, boolean avecAuteur) {
        return new MouvementCreditDTO(m.getUniqueId(), m.getType(), m.getMontant(), m.getSoldeApres(),
                m.getDateMouvement(), m.getMois(), m.getPoulesMoyenne(), m.getJours(), m.getJoursMois(),
                Boolean.TRUE.equals(m.getPrixFixe()), m.getLibelle(),
                avecAuteur && m.getAuteur() != null ? m.getAuteur().getFullName() : null);
    }
}
