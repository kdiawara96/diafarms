package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.diafarms.ml.enums.StatutTransaction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une ligne de la page Ventes, lue directement dans la table de la vente (ventes_oeufs,
// ventes_reforme, ventes_diverses) — une ligne par VENTE, plus une par part de projet
// comme quand la page reconstruisait les ventes depuis la comptabilité.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class VenteLigneDTO {
    private String uniqueId; // uniqueId de la vente (pas d'une transaction)
    private String type; // "OEUFS", "REFORME", "FIENTES" ou "AUTRE"
    private String categorie; // libellé comptable : "Vente œufs", "Vente réforme", "Vente fientes", "Autre vente"
    private LocalDate date;
    private String description;
    private Double quantite;
    private String unite; // "œufs", "œufs cassés", "sujets", "sacs" ou null
    private Double prixUnitaire;
    private Double montant; // théorique
    private Double montantRapporte; // null = pas d'écart déclaré
    private Double montantReel; // montantRapporte, sinon montant
    private String clientUniqueId;
    private String clientNom;
    private String magasinNom;
    private String creeParUniqueId;
    private String creeParNom;
    private List<String> projets; // codes des projets contributeurs, vide = commune
    // Statut de la (des) transaction(s) générée(s) : REJETE si l'une a été rejetée
    // (anciennes ventes, avant que le rejet d'une vente passe par sa suppression).
    private StatutTransaction statut;
    private String demandeSuppressionParNom;
    private LocalDateTime dateDemandeSuppression;
    private String motifSuppression;
}
