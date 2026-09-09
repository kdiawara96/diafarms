package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Transaction;

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
public class TransactionDTO {

    private String uniqueId;
    private String ref;
    private LocalDate date;
    private TypeTransaction type;
    private String projetCode; // "Commun" si pas de projet lié
    private String projetUniqueId;
    private List<ProjetsSelect> projetsConcernes; // uniquement pertinent quand "Commun"
    private String description;
    private Double montant;
    // Montant réellement encaissé pour cette transaction (vente à crédit partielle ou
    // totale : montant reste la valeur théorique des œufs/réforme sortis, montantReel
    // reflète ce que le vendeur a effectivement rapporté — voir VenteOeufs/VenteReforme
    // .montantRapporte et TransactionServiceImpl.enrichMontantReel). Égal à montant par
    // défaut (aucun écart connu) ; seul .list() calcule la vraie valeur pour les
    // transactions issues d'une vente, via un ratio par ligne de répartition.
    private Double montantReel;
    // Client concerné — soit directement rattaché à la transaction (Transaction.client,
    // ex: "Remboursement client", voir ClientServiceImpl.payerDette), soit dérivé de la vente
    // d'origine pour une transaction "Vente œufs"/"Vente réforme" avec client identifié
    // (voir VenteOeufs/VenteReforme.client, rempli par TransactionServiceImpl.
    // enrichMontantReel). Null = vente directe (sans client) ou transaction sans lien à
    // un client.
    private String clientNom;
    private String categorie;
    private StatutTransaction statut;
    private String commentaireRejet;
    private String validateurNom;
    private LocalDateTime dateValidation;
    private LocalDateTime createdAt;
    private SourceTransaction sourceType;
    private String sourceUniqueId;
    // Qui a initié la transaction (saisie manuelle ou vente à l'origine) — affiché sur
    // la page Ventes quand un ADMIN regarde "tous les vendeurs", voir Ventes.tsx.
    private String creeParNom;
    // Non null = suppression en attente de validation par un admin/responsable —
    // voir TransactionServiceImpl.demanderSuppression.
    private String demandeSuppressionParNom;
    private LocalDateTime dateDemandeSuppression;

    public static TransactionDTO fromEntity(Transaction t) {
        if (t == null) return null;

        return TransactionDTO.builder()
                .uniqueId(t.getUniqueId())
                .ref(t.getRef())
                .date(t.getDate())
                .type(t.getType())
                .projetCode(t.getProjet() != null ? t.getProjet().getCode() : "Commun")
                .projetUniqueId(t.getProjet() != null ? t.getProjet().getUniqueId() : null)
                .projetsConcernes(t.getProjetsConcernes() != null
                        ? t.getProjetsConcernes().stream().map(ProjetsSelect::selectEntity).toList()
                        : List.of())
                .description(t.getDescription())
                .montant(t.getMontant())
                .montantReel(t.getMontant()) // corrigé ensuite par enrichMontantReel si pertinent
                .clientNom(t.getClient() != null ? t.getClient().getNom() : null) // idem si issue d'une vente
                .categorie(t.getCategorie())
                .statut(t.getStatut())
                .commentaireRejet(t.getCommentaireRejet())
                .validateurNom(t.getValidateur() != null ? t.getValidateur().getFullName() : null)
                .dateValidation(t.getDateValidation())
                .createdAt(t.getInitialisation() != null ? t.getInitialisation().getCreatedAt() : null)
                .sourceType(t.getSourceType())
                .sourceUniqueId(t.getSourceUniqueId())
                .creeParNom(t.getCreePar() != null ? t.getCreePar().getFullName() : null)
                .demandeSuppressionParNom(t.getDemandeSuppressionPar() != null ? t.getDemandeSuppressionPar().getFullName() : null)
                .dateDemandeSuppression(t.getDateDemandeSuppression())
                .build();
    }
}
