# Circuit de l'argent client — conception validée

Validée par l'utilisateur le 2026-09-23 (« Ok vas y » sur la page
https://claude.ai/artifact/UxG2sAh5XXnbdms77dEE5a, recommandations retenues pour les six
questions). Analyse de l'existant : https://claude.ai/artifact/L1PYCnefTeUkBiKQdGMTrD.

## Principe

Une seule source par information, et aucun montant existant n'est modifié pour « faire
tomber juste » : chaque mouvement est un enregistrement daté, signé, retraçable.

| Objet | Question | Nature |
|---|---|---|
| Commande | Que veut le client ? | enregistré |
| Livraison = Vente liée à la commande | Qu'est-ce qui a été remis / vendu ? | enregistré (1 livraison = 1 vente) |
| Vente directe | Vente sans commande | enregistré |
| Paiement client | Quel argent le client a donné ? | enregistré, jamais modifié (annulable avec motif) |
| Imputation | Quel paiement règle quelle vente (ou quel remboursement), pour combien ? | enregistré, automatique, annulable |
| Avance client | Argent reçu non imputé | **calculé** = Σ paiements − Σ imputations |
| Reste à payer | Ce que le client doit | **calculé** = Σ ventes − Σ imputations sur ventes |
| Remboursement | Argent rendu au client | enregistré, consomme des paiements via des imputations |
| Facture | Document remis au client | regroupe 1..n ventes du même client ; « payé » calculé |

Solde net client = reste à payer − avance (positif = il doit, négatif = avance).

## Décisions (les six questions)

1. Livraison et vente : **un seul enregistrement** (la vente porte `commande`).
2. **Un produit par commande** (inchangé).
3. Imputation **automatique** (vente ciblée par le paiement, puis ventes de la même
   commande, puis ventes les plus anciennes du client), avec choix possible de la vente
   au moment du paiement.
4. Trop-perçu : **reste en avance** par défaut ; remboursement sur décision.
5. Une facture **peut regrouper plusieurs ventes** du même client ; une vente n'est
   jamais facturée deux fois.
6. **Mode de paiement** obligatoire : espèces, Orange Money, Moov Money, Wave, virement,
   chèque, autre.

## Statuts

- Commande : EN_ATTENTE, CONFIRMEE, EN_LIVRAISON, CONVERTIE (libellé « Livrée »),
  CLOTUREE, ANNULEE. Annuler seulement si rien n'est livré ; clôturer si livrée en
  partie. Motif obligatoire pour les deux.
- Vente : active / supprimée (circuit existant demande → confirmation, motif). État de
  paiement calculé : NON_PAYEE, PARTIELLE, PAYEE.
- Paiement, remboursement : ENREGISTRE / ANNULE (motif, qui, quand).
- Facture : EMISE, PARTIELLE, PAYEE (calculés), ANNULEE (motif) ; lignes figées.

## Comptabilité

- Vendu = Σ ventes actives (chiffre d'affaires).
- Encaissé = paiements clients + ventes au comptant sans client (montant rapporté)
  + autres entrées d'argent.
- Remboursé = Σ remboursements.
- Invariants par client : Σ paiements − Σ remboursements = Σ imputations sur ventes +
  avance ; Σ ventes = Σ imputations sur ventes + reste à payer.

## Contrôle du vendeur

- Vente avec client : l'argent reçu est un paiement client (reçu par le vendeur) ; le
  manque est dû par le client. `montantRapporte` n'est plus utilisé.
- Vente sans client : inchangé (`montantRapporte`, écart au solde vendeur).

## Reprise de l'existant

Acomptes, « Remboursement client », « Paiement facture » et montants rapportés des
ventes avec client deviennent des paiements ; « Remboursement au client » devient des
remboursements ; les recopies faites par « marquer payée » dans `montantRapporte` sont
retirées (elles doublaient le paiement). Un rapport « solde avant / solde recalculé » par
client est produit en simulation et validé par l'utilisateur avant l'exécution réelle.
