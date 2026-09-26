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
    // Non null seulement si sourceType = VENTE_OEUFS/VENTE_REFORME : uniqueId de la VENTE
    // ENTIÈRE (pas cette seule part par projet) — voir TransactionServiceImpl.enrichMontantReel.
    // Sert au web/Ventes.tsx pour demander/confirmer la suppression de la vente depuis la
    // transaction affichée (supprimer la transaction seule ne touche ni au stock ni au
    // solde, voir VenteOeufsImpl/VenteReformeImpl.confirmerSuppression).
    private String venteUniqueId;
    // Non null = une suppression de LA VENTE (pas de cette transaction) est en attente —
    // le web l'affiche pour proposer "Confirmer/Refuser" à un admin/responsable.
    private String venteDemandeSuppressionParNom;
    private String categorie;
    private StatutTransaction statut;
    private String commentaireRejet;
    private String validateurNom;
    private LocalDateTime dateValidation;
    private LocalDateTime createdAt;
    private SourceTransaction sourceType;
    private String sourceUniqueId;
    // Rattachements facultatifs (null = ferme entière) — voir Transaction.site/batiment.
    private String siteUniqueId;
    private String siteNom;
    private String batimentUniqueId;
    private String batimentNom;
    // Qui a initié la transaction (saisie manuelle ou vente à l'origine) — affiché sur
    // la page Ventes quand un ADMIN regarde "tous les vendeurs", voir Ventes.tsx.
    private String creeParNom;
    // Non null = suppression en attente de validation par un admin/responsable —
    // voir TransactionServiceImpl.demanderSuppression.
    private String demandeSuppressionParNom;
    private LocalDateTime dateDemandeSuppression;
    private String motifSuppression;
    // true = transaction générée par une vente (œufs, réforme, fientes/autre) : elle ne
    // se modifie ni ne se supprime depuis la Comptabilité, seulement via sa vente (voir
    // TransactionServiceImpl.ensurePasLieeAUneVente), sinon vente et comptabilité divergent.
    private boolean lieeAUneVente;
    // true = transaction verrouillée comptablement : générée par une vente OU par un
    // paiement/remboursement client — ne se modifie ni ne se supprime depuis la
    // Comptabilité, seulement via sa source (vente, paiement, remboursement).
    private boolean verrouillee;
    // Transaction générée par une saisie (soins, aliment, investissement, salaire, coûts
    // de démarrage du projet) : libellé de cette saisie et de l'écran où la modifier ;
    // null sinon. Modifier/supprimer/rejeter la transaction seule est refusé (voir
    // TransactionServiceImpl.ensurePasLieeAUneVente).
    private String saisieSource;
    // true = le rattachement (site, poulailler) se modifie depuis la Comptabilité : toute
    // transaction non verrouillée, y compris une transaction générée (saisieSource non
    // null), pour laquelle c'est même la SEULE modification permise.
    private boolean rattachementModifiable;

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
                .siteUniqueId(t.getSite() != null ? t.getSite().getUniqueId() : null)
                .siteNom(t.getSite() != null ? t.getSite().getNom() : null)
                .batimentUniqueId(t.getBatiment() != null ? t.getBatiment().getUniqueId() : null)
                .batimentNom(t.getBatiment() != null ? t.getBatiment().getNom() : null)
                .creeParNom(t.getCreePar() != null ? t.getCreePar().getFullName() : null)
                .demandeSuppressionParNom(t.getDemandeSuppressionPar() != null ? t.getDemandeSuppressionPar().getFullName() : null)
                .dateDemandeSuppression(t.getDateDemandeSuppression())
                .motifSuppression(t.getMotifSuppression())
                .lieeAUneVente(isSourceVente(t.getSourceType()))
                .verrouillee(isSourceVerrouillee(t.getSourceType()))
                .saisieSource(saisieSourceGeneree(t.getSourceType()))
                .rattachementModifiable(!isSourceVerrouillee(t.getSourceType()))
                // Une vente diverse n'a qu'une transaction, pointant directement vers elle ;
                // œufs/réforme passent par leur ligne de répartition (enrichMontantReel).
                .venteUniqueId(t.getSourceType() == SourceTransaction.VENTE_DIVERSE ? t.getSourceUniqueId() : null)
                .build();
    }

    public static boolean isSourceVente(SourceTransaction s) {
        return s == SourceTransaction.VENTE_OEUFS || s == SourceTransaction.VENTE_REFORME
                || s == SourceTransaction.VENTE_DIVERSE;
    }

    // Verrou comptable étendu : ventes + paiements/remboursements client — voir
    // TransactionServiceImpl.ensurePasLieeAUneVente.
    public static boolean isSourceVerrouillee(SourceTransaction s) {
        return isSourceVente(s) || s == SourceTransaction.PAIEMENT_CLIENT || s == SourceTransaction.REMBOURSEMENT_CLI;
    }

    // Libellé (avec l'écran où la modifier) de la saisie qui a généré la transaction ;
    // null pour une transaction saisie à la main (MANUEL) ou une source traitée ailleurs
    // (ventes, paiements/remboursements client : voir isSourceVerrouillee).
    public static String saisieSourceGeneree(SourceTransaction source) {
        if (source == null) return null;
        return switch (source) {
            case SOINS -> "un soin (fiche du projet, section Santé / Vétérinaire)";
            case VACCINATION -> "une vaccination (fiche du projet, section Santé / Vétérinaire)";
            case ALIMENTATION -> "un achat d'aliment (fiche du projet, section Alimentation)";
            case INVESTISSEMENT -> "un investissement (page Investissements)";
            case SALAIRE -> "un paiement de salaire (page Salaires)";
            case PROJET_ACHAT_SUJETS -> "l'achat des sujets du projet (page Projets, modification du projet)";
            case PROJET_CHARGES -> "les charges de démarrage du projet (page Projets, modification du projet)";
            default -> null;
        };
    }
}
