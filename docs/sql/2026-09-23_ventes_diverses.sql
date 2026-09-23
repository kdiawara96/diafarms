-- À lancer UNE FOIS, APRÈS le redémarrage du backend qui crée la table ventes_diverses
-- (ddl-auto=update). Rejouable sans risque : chaque étape vérifie ce qui existe déjà.

BEGIN;

-- 1. Nouvelle valeur VENTE_DIVERSE dans la contrainte CHECK de transactions.source_type
--    (ddl-auto=update ne met jamais à jour une contrainte existante).
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_source_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_source_type_check CHECK (source_type IN (
  'MANUEL','VENTE_OEUFS','VENTE_REFORME','SALAIRE','ALIMENTATION','SOINS','VACCINATION',
  'INVESTISSEMENT','PROJET_ACHAT_SUJETS','PROJET_CHARGES','VENTE_DIVERSE'));

-- 2. Chaque ancienne entrée d'argent "Vente fientes" / "Autre vente" devient une vraie
--    vente (même unique_id que sa transaction), y compris les supprimées (historique).
INSERT INTO ventes_diverses (unique_id, date, produit, quantite, prix_unitaire, montant, description,
                             farm_id, cree_par_id, demande_suppression_par_id, date_demande_suppression,
                             motif_suppression, created_at, updated_at, removed, archive)
SELECT t.unique_id, t.date,
       CASE WHEN t.categorie = 'Vente fientes' THEN 'FIENTES' ELSE 'AUTRE' END,
       NULL, NULL, t.montant, t.description,
       t.farm_id, t.cree_par_id, t.demande_suppression_par_id, t.date_demande_suppression,
       CASE WHEN t.demande_suppression_par_id IS NOT NULL THEN 'Demande faite avant l''obligation du motif' END,
       t.created_at, t.updated_at, COALESCE(t.removed, false), COALESCE(t.archive, false)
FROM transactions t
WHERE t.type = 'ENTREE' AND t.categorie IN ('Vente fientes', 'Autre vente')
  AND t.source_type = 'MANUEL' AND t.farm_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM ventes_diverses v WHERE v.unique_id = t.unique_id);

-- 3. Les transactions pointent maintenant vers leur vente ; une demande de suppression en
--    attente a été reportée sur la vente (étape 2), elle se traite désormais depuis Ventes.
UPDATE transactions t
SET source_type = 'VENTE_DIVERSE', source_unique_id = t.unique_id,
    demande_suppression_par_id = NULL, date_demande_suppression = NULL
WHERE t.type = 'ENTREE' AND t.categorie IN ('Vente fientes', 'Autre vente')
  AND t.source_type = 'MANUEL'
  AND EXISTS (SELECT 1 FROM ventes_diverses v WHERE v.unique_id = t.unique_id);

-- Contrôle avant COMMIT : les deux nombres doivent être égaux.
SELECT (SELECT COUNT(*) FROM ventes_diverses) AS ventes_diverses,
       (SELECT COUNT(*) FROM transactions WHERE source_type = 'VENTE_DIVERSE') AS transactions_liees;

COMMIT;

-- 4. LECTURE SEULE — ventes restées actives alors que TOUTES leurs transactions ont été
--    supprimées depuis la Comptabilité (cas Tigiri). À supprimer ensuite depuis la page
--    Ventes (demande + confirmation), ce qui corrige aussi le stock et le solde client.
SELECT 'OEUFS' AS type, v.unique_id, v.date, v.montant, c.nom AS client
FROM ventes_oeufs v LEFT JOIN clients c ON c.id = v.client_id
WHERE v.removed = false
  AND EXISTS (SELECT 1 FROM ventes_oeufs_repartitions r WHERE r.vente_oeufs_id = v.id)
  AND NOT EXISTS (SELECT 1 FROM ventes_oeufs_repartitions r JOIN transactions t ON t.source_unique_id = r.unique_id
                  WHERE r.vente_oeufs_id = v.id AND t.removed = false)
UNION ALL
SELECT 'REFORME', v.unique_id, v.date, v.montant, c.nom
FROM ventes_reforme v LEFT JOIN clients c ON c.id = v.client_id
WHERE v.removed = false
  AND EXISTS (SELECT 1 FROM ventes_reforme_repartitions r WHERE r.vente_reforme_id = v.id)
  AND NOT EXISTS (SELECT 1 FROM ventes_reforme_repartitions r JOIN transactions t ON t.source_unique_id = r.unique_id
                  WHERE r.vente_reforme_id = v.id AND t.removed = false);
