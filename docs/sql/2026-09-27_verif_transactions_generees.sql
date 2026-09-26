-- Transactions générées par une saisie (2026-09-27) : contrôle AVANT déploiement, LECTURE SEULE.
--
-- Depuis ce déploiement, une transaction dont la source est un soin, une vaccination, un
-- achat d'aliment, un investissement, un paiement de salaire ou les coûts de démarrage d'un
-- projet (PROJET_ACHAT_SUJETS, PROJET_CHARGES) ne se modifie, ne se supprime et ne se rejette
-- plus depuis la Comptabilité : uniquement par sa saisie source. Une incohérence ancienne
-- (transaction retirée ou rejetée depuis la Comptabilité alors que la saisie vit encore, ou
-- l'inverse) ne pourra donc plus être corrigée depuis la Comptabilité : la repérer AVANT.
--
-- Source « active » = saisie existante, non supprimée, dont le projet (s'il y en a un)
-- n'est pas supprimé, et dont le coût est positif (un coût nul retire normalement la
-- transaction). Résultat vide = rien à reprendre.
--
-- Colonnes : ferme, cas, source_type, transaction (ref, unique_id), statut, montant,
-- source_unique_id, detail.
--
--   psql -d <base> -At -F ' | ' -f docs/sql/2026-09-27_verif_transactions_generees.sql

WITH generees AS (
    SELECT t.id, t.ref, t.unique_id, t.farm_id, t.source_type, t.source_unique_id, t.statut, t.montant,
           coalesce(t.removed, false) AS t_removed
    FROM transactions t
    WHERE t.source_type IN ('SOINS', 'VACCINATION', 'ALIMENTATION', 'INVESTISSEMENT', 'SALAIRE',
                            'PROJET_ACHAT_SUJETS', 'PROJET_CHARGES')
),
sources AS (
    SELECT g.*,
           CASE g.source_type
             WHEN 'ALIMENTATION' THEN (
               SELECT NOT coalesce(a.removed, false) AND NOT coalesce(p.removed, false) AND coalesce(a.cout_total, 0) > 0
               FROM alimentations a LEFT JOIN projets p ON p.id = a.projet_id WHERE a.unique_id = g.source_unique_id)
             WHEN 'SOINS' THEN (
               SELECT NOT coalesce(s.removed, false) AND NOT coalesce(p.removed, false) AND coalesce(s.cout_total, 0) > 0
               FROM soins s LEFT JOIN projets p ON p.id = s.projet_id WHERE s.unique_id = g.source_unique_id)
             WHEN 'VACCINATION' THEN (
               SELECT NOT coalesce(s.removed, false) AND NOT coalesce(p.removed, false) AND coalesce(s.cout_total, 0) > 0
               FROM soins s LEFT JOIN projets p ON p.id = s.projet_id WHERE s.unique_id = g.source_unique_id)
             WHEN 'INVESTISSEMENT' THEN (
               SELECT NOT coalesce(i.removed, false) AND coalesce(i.montant, 0) > 0
               FROM investissements i WHERE i.unique_id = g.source_unique_id)
             WHEN 'SALAIRE' THEN (
               SELECT NOT coalesce(ps.removed, false) AND coalesce(ps.montant_paye, 0) > 0
               FROM paiements_salaire ps WHERE ps.unique_id = g.source_unique_id)
             WHEN 'PROJET_ACHAT_SUJETS' THEN (
               SELECT NOT coalesce(p.removed, false) AND coalesce(p.nb_sujets, 0) * coalesce(p.pu_sujet, 0) > 0
               FROM projets p WHERE 'SUJETS-' || p.unique_id = g.source_unique_id)
             WHEN 'PROJET_CHARGES' THEN (
               SELECT NOT coalesce(p.removed, false) AND coalesce(p.autres_depense, 0) > 0
               FROM projets p WHERE 'CHARGES-' || p.unique_id = g.source_unique_id)
           END AS source_active
    FROM generees g
)
SELECT coalesce(f.nom, f.unique_id) AS ferme,
       CASE
         WHEN (s.t_removed OR s.statut = 'REJETE') AND s.source_active
           THEN 'A. transaction retirée/rejetée, saisie source active'
         WHEN NOT s.t_removed AND s.statut <> 'REJETE' AND s.source_active IS NULL
           THEN 'B. transaction active, saisie source introuvable'
         ELSE 'B. transaction active, saisie source supprimée (ou projet supprimé, ou coût nul)'
       END AS cas,
       s.source_type, s.ref, s.unique_id, s.statut, s.montant, s.source_unique_id,
       CASE WHEN s.t_removed THEN 'transaction supprimée' ELSE 'transaction active' END AS detail
FROM sources s
LEFT JOIN farms f ON f.id = s.farm_id
WHERE ((s.t_removed OR s.statut = 'REJETE') AND s.source_active)
   OR (NOT s.t_removed AND s.statut <> 'REJETE' AND s.source_active IS DISTINCT FROM true)
ORDER BY ferme, cas, s.source_type, s.ref;
