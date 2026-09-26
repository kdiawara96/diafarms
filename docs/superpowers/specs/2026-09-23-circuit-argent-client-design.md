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

## Règle « acompte réservé » (décidée le 2026-09-26)

Avant : l'acompte d'une commande pouvait régler une ancienne vente du client (la commande
affichait « acompte reçu 10 000, payé sur commande 0 »). Désormais :

- Un paiement rattaché à une commande (acompte, règlement ou paiement à la livraison) est
  **réservé** à cette commande tant qu'elle est **ouverte** : EN_ATTENTE, CONFIRMEE ou
  EN_LIVRAISON, et non supprimée (`CompteClientService.estReservee`). Il ne règle que les
  livraisons de cette commande ; aucune autre vente, aucun remboursement général.
- Imputation (`CalculImputation.repartir`) : d'abord l'argent réservé (plus anciens
  d'abord), chacun sur sa seule commande (vente visée, puis ses livraisons) ; puis
  l'argent libre dans l'ordre d'avant (vente visée, ventes de sa commande, ventes les plus
  anciennes), qui peut aussi régler des livraisons après les acomptes.
- Livraison : la vente est créée puis rattachée à sa commande ; les imputations faites
  avant le rattachement sont retirées et l'imputation repasse, commande encore ouverte,
  pour que les acomptes réservés la règlent en premier. Le reste reste réservé.
- **Fin de la réservation** : dès que la commande est CONVERTIE (tout livré), CLOTUREE,
  ANNULEE ou supprimée, le reste devient une **avance libre** du client et l'imputation
  repasse aussitôt (les anciennes dettes sont réglées). Annulation avec remboursement : le
  remboursement passe d'abord, l'imputation ensuite. Une livraison supprimée ou restaurée
  met d'abord à jour le statut de la commande, puis impute (l'acompte redevient réservé
  si la commande se rouvre).
- Réouverture (commande récupérée, livraison supprimée qui fait repasser une commande
  CONVERTIE en cours) : `CompteClientService.reReserver` annule ce que l'argent de la
  commande réglait hors de ses livraisons, puis impute de nouveau. Même traitement que la
  reprise ci-dessous (`annulerHorsCommande`).
- Paiement à la livraison : enregistré avant la ré-imputation, il règle d'abord SA
  livraison (l'argent réservé qui vise une vente passe avant les acomptes).
- Refus (400) : paiement sur une commande en cours qui vise une vente hors de cette
  commande ; changement du client d'une livraison de commande.
- Remboursement : l'argent réservé n'est pris que si le remboursement vise sa commande
  (`commandeUniqueId`) ; sinon seule l'avance libre compte (message explicite).
- Compte client : `avance` inchangée (= avanceLibre + avanceReservee), plus
  `avanceLibre`, `avanceReservee` et `avancesReservees` (par commande). Commande :
  `acompteRecu`, `acompteImpute` (sur ses livraisons), `acompteReserve` (0 une fois
  terminée) et `avanceReservee` (tous ses paiements).
- Données existantes : `POST /diafarms/api/v1/admin/reprise-acompte-reserve`
  (SUPER_ADMIN, simulation par défaut, `executer=true` pour écrire, `farmUniqueId`
  facultatif). Pour chaque commande encore ouverte, les imputations de ses paiements sur
  des ventes qui ne sont pas ses livraisons sont annulées (motif « Reprise acompte
  réservé »), puis l'imputation repasse sur le client. Rapport avant/après par client.
  Idempotent.
