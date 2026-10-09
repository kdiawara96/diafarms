-- Crédit prépayé (branche modele-credit, 2026-10-09). NE PAS LANCER SUR LA PROD sans
-- accord : ce fichier sert à relire ce que fait le code au démarrage, et à le refaire à la
-- main si besoin.
--
-- 1. Schéma : rien à faire à la main. ddl-auto=update ajoute :
--      abonnements        : credit_depuis, credit_epuise_le (date), essai_refuse (bool)
--      abonnement_config  : bonus_seuil, bonus_pourcent, credit_parrainage (double),
--                           seuil_sur_devis (int)
--      paiements_abonnement : recharge (bool), bonus (double)
--      parrainages        : recompense_credit (double)
--   et crée les tables mouvements_credit (compte de crédit, clé unique « cle ») et
--   essais_gratuits (un essai par téléphone / e-mail de propriétaire). Toutes les colonnes
--   ajoutées sont nullables ; aucune nouvelle valeur d'enum sur une colonne existante
--   (les recharges gardent periodicite = 'MENSUEL', le type de mouvement est un texte).
--
-- 2. Reprise des fermes existantes : faite par le code (CreditService.convertirAnciennesFermes,
--    au démarrage puis chaque jour à 00 h 30 UTC), idempotente. Équivalent SQL ci-dessous.
--
--    Règle : une ferme garde sa période déjà payée (ou son essai). Le crédit commence au
--    lendemain de sa date de fin actuelle, à 0 FCFA ; les mensualités ne comptent que les
--    jours à partir de là. Tant qu'elle n'a pas rechargé, sa date de fin ne bouge pas (elle
--    reçoit les mêmes rappels, puis la grâce et le blocage, comme aujourd'hui).
--    Fermes déjà bloquées : rien ne change ; à leur prochaine recharge, le crédit repart du
--    jour de la recharge (les jours bloqués ne sont jamais facturés).

BEGIN;

-- Avant : combien de fermes seront converties, et leur date de fin (inchangée).
SELECT COUNT(*) AS a_convertir, MIN(date_fin), MAX(date_fin)
FROM abonnements WHERE credit_depuis IS NULL;

UPDATE abonnements
SET credit_depuis = date_fin + 1,
    credit_epuise_le = date_fin + 1
WHERE credit_depuis IS NULL;

-- Mémoire des essais gratuits déjà donnés (propriétaire = premier ADMIN de chaque ferme) :
-- une nouvelle inscription avec le même téléphone ou e-mail n'aura pas d'essai, même si ce
-- compte est supprimé plus tard.
INSERT INTO essais_gratuits (farm_id, telephone, email, essai_donne, cree_le)
SELECT DISTINCT ON (u.farm_id) u.farm_id,
       CASE WHEN length(regexp_replace(regexp_replace(COALESCE(u.telephone, ''), '[^0-9]', '', 'g'), '^00', '')) = 8
            THEN '223' || regexp_replace(regexp_replace(COALESCE(u.telephone, ''), '[^0-9]', '', 'g'), '^00', '')
            WHEN length(regexp_replace(regexp_replace(COALESCE(u.telephone, ''), '[^0-9]', '', 'g'), '^00', '')) < 8
            THEN NULL
            ELSE regexp_replace(regexp_replace(COALESCE(u.telephone, ''), '[^0-9]', '', 'g'), '^00', '') END,
       LOWER(TRIM(u.email)), true, now()
FROM utilisateurs u
JOIN roles_users ru ON ru.id_utilisateurs = u.id
JOIN roles r ON r.id = ru.id_roles
WHERE r.role = 'ADMIN' AND u.farm_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM essais_gratuits e WHERE e.farm_id = u.farm_id)
ORDER BY u.farm_id, u.id;

-- Après : aucune date de fin n'a bougé, aucun mouvement de crédit créé.
SELECT COUNT(*) FILTER (WHERE date_fin <> credit_depuis - 1) AS fins_changees,
       (SELECT COUNT(*) FROM mouvements_credit) AS mouvements
FROM abonnements;

-- Relire, puis COMMIT (ou ROLLBACK).
ROLLBACK;

-- 3. Vérifications utiles après la mise en service :
--    solde de chaque ferme      : SELECT abonnement_id, SUM(montant) FROM mouvements_credit GROUP BY 1;
--    mensualités d'un mois      : SELECT * FROM mouvements_credit WHERE type = 'MENSUALITE' AND mois = '2026-11';
--    fermes à recharger         : SELECT a.farm_id FROM abonnements a LEFT JOIN mouvements_credit m ON m.abonnement_id = a.id
--                                 WHERE a.credit_depuis <= current_date GROUP BY a.farm_id HAVING COALESCE(SUM(m.montant), 0) <= 0;
