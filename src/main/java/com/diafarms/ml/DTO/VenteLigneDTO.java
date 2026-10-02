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
    private Double montantRapporte; // null = pas d'écart déclaré, ou vente avec client (voir plus bas)
    private Double montantReel; // montantRapporte, sinon montant ; pour une vente avec client = payé
    private String clientUniqueId;
    private String clientNom;
    // Champs propres à une vente AVEC client (voir CompteClientService) — null pour une
    // vente sans client (ANCIEN comportement montantRapporte/montantReel encore utilisé)
    // et pour les ventes diverses (jamais de client).
    private Double paye;
    private Double resteAPayer;
    // Part de "paye" reçue À LA VENTE (paiements d'origine VENTE sur cette vente) : si le
    // client est retiré, c'est ce montant qui repasse en montant rapporté par le vendeur
    // (valeur proposée par défaut, voir VenteOeufsImpl.update).
    private Double payeALaVente;
    // "PAYEE" | "PARTIELLE" | "NON_PAYEE" (vente à un client, voir ClientVenteLigneDTO) ou
    // "COMPTANT" (vente sans client, encaissée directement — diverses comprises).
    private String statutPaiement;
    // Non null = cette vente est la livraison d'une Commande — voir VenteOeufs/VenteReforme.commande.
    private String commandeUniqueId;
    private String magasinNom;
    private String creeParUniqueId;
    private String creeParNom;
    private List<String> projets; // codes des projets contributeurs, vide = commune
    // Vente diverse seulement : projet de la vente (« Le Projet »), null = toute la ferme.
    private String projetUniqueId;
    // Part de chaque projet dans la vente (répartition : œufs ou sujets attribués et
    // montant), dans l'ordre des lignes de répartition ; vide = commune.
    private List<PartProjetDTO> repartitionProjets;
    // Statut de la (des) transaction(s) générée(s) : REJETE si l'une a été rejetée
    // (anciennes ventes, avant que le rejet d'une vente passe par sa suppression).
    private StatutTransaction statut;
    private String demandeSuppressionParNom;
    private LocalDateTime dateDemandeSuppression;
    private String motifSuppression;
    // Vente réforme seulement (null sinon) — voir VenteReformeDTO pour le détail.
    private String typeVente; // "TETE" ou "KILO"
    private Double poidsTotalKg;
    private Double poidsMoyenParSujet;
    private Double prixParKg;
    private Double prixParTete;
}
