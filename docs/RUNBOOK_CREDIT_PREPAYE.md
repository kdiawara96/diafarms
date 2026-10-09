# Crédit prépayé : consignes d'exploitation

## `date_fin` n'est plus une donnée à modifier

- `abonnements.date_fin` est maintenant **calculée** à partir du crédit : dernier jour couvert (estimation tant que le crédit est positif), ou veille du premier jour non couvert (`credit_epuise_le`).
- Elle est recalculée après chaque mouvement (recharge, bonus, mensualité, parrainage, ajustement) et chaque nuit à 00 h 30 UTC.
- **Ne jamais modifier `date_fin` en SQL** : la valeur sera écrasée au prochain recalcul, et les rappels, la grâce et le blocage seront faux en attendant.

## Donner du temps ou corriger un crédit

- Pour offrir du temps ou corriger une erreur : **console, fiche de la ferme, « Ajuster le crédit »** (montant en plus ou en moins, raison obligatoire, visible par la ferme).
- Argent reçu en main propre : **« Recharger »** dans la fiche (le bonus s'applique comme pour une déclaration).
- Ferme en essai : « Prolonger l'essai » reste possible tant qu'elle n'a jamais rechargé.
- Suspension : « Suspendre » puis « Réactiver » (ou une recharge de la console). Les jours suspendus ne sont jamais facturés.

## Pendant un déploiement

- **Ne pas valider de recharge** et ne pas utiliser la console pendant le redémarrage du serveur.
- Au premier démarrage, le serveur passe les anciennes fermes au crédit (une seule fois, sans rien facturer). Vérifier ensuite avec `docs/sql/2026-10-09_credit_prepaye.sql` (partie « Après »).
- Si le serveur était arrêté le 1er du mois, la tâche de la nuit suivante rattrape les mensualités oubliées, sans jamais en prélever deux fois.

## Contrôles rapides

- Simulation de la tâche (rien n'est écrit) : `POST /admin/credit/tache?executer=false` (SUPER_ADMIN).
- Solde d'une ferme : somme de `mouvements_credit.montant` pour son abonnement.
