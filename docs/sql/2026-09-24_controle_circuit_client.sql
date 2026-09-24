-- Contrôle du circuit de l'argent client (2026-09-24) — LECTURE SEULE.
--
-- À lancer en production APRÈS l'exécution de la reprise
-- (POST /diafarms/api/v1/admin/reprise-circuit-client?executer=true), puis quand on veut
-- vérifier les comptes. Chaque requête renvoie les lignes FAUTIVES : résultat vide =
-- contrôle réussi. Colonnes : controle, client, detail.
--
-- Ces contrôles sont INDÉPENDANTS de GET /clients/{uid}/compte (qui calcule le compte à
-- partir des mêmes tables) : ils vérifient directement les enregistrements. Le script de
-- scénarios (scripts/scenarios-circuit-client.sh) exécute ce même fichier.
--
-- Cas connu après la reprise : un remboursement ancien que les paiements repris ne
-- couvrent pas (avertissement « non couverts par des paiements » du rapport de reprise)
-- ressort au contrôle 1 — à arbitrer avec le client, pas une erreur du programme.
--
--   psql -d <base> -At -F ' | ' -f docs/sql/2026-09-24_controle_circuit_client.sql

-- 1. Remboursé : Σ remboursements actifs = Σ imputations actives de type REMBOURSEMENT
--    (argent rendu entièrement pris sur des paiements reçus).
SELECT '1 rembourse' AS controle, c.nom AS client,
       'remboursements ' || COALESCE(r.total, 0) || ' / imputes ' || COALESCE(i.total, 0) AS detail
FROM clients c
LEFT JOIN (SELECT client_id, SUM(montant) AS total FROM remboursements_client
           WHERE statut = 'ACTIF' GROUP BY client_id) r ON r.client_id = c.id
LEFT JOIN (SELECT client_id, SUM(montant) AS total FROM imputations_paiement
           WHERE statut = 'ACTIF' AND cible_type = 'REMBOURSEMENT' GROUP BY client_id) i ON i.client_id = c.id
WHERE ABS(COALESCE(r.total, 0) - COALESCE(i.total, 0)) >= 0.01;

-- 2. Avance ≥ 0 : Σ paiements actifs − Σ imputations actives (ventes + remboursements).
SELECT '2 avance negative' AS controle, c.nom AS client,
       'paiements ' || COALESCE(p.total, 0) || ' / imputes ' || COALESCE(i.total, 0) AS detail
FROM clients c
LEFT JOIN (SELECT client_id, SUM(montant) AS total FROM paiements_client
           WHERE statut = 'ACTIF' GROUP BY client_id) p ON p.client_id = c.id
LEFT JOIN (SELECT client_id, SUM(montant) AS total FROM imputations_paiement
           WHERE statut = 'ACTIF' GROUP BY client_id) i ON i.client_id = c.id
WHERE COALESCE(p.total, 0) - COALESCE(i.total, 0) < -0.01;

-- 3. Vente : Σ imputations actives ≤ montant de la vente.
SELECT '3 vente surpayee' AS controle, c.nom AS client,
       i.cible_type || ' ' || i.cible_unique_id || ' : montant ' || COALESCE(v.montant, 0) || ' / imputes ' || i.total AS detail
FROM (SELECT cible_type, cible_unique_id, client_id, SUM(montant) AS total FROM imputations_paiement
      WHERE statut = 'ACTIF' AND cible_type IN ('VENTE_OEUFS', 'VENTE_REFORME')
      GROUP BY cible_type, cible_unique_id, client_id) i
LEFT JOIN (SELECT 'VENTE_OEUFS' AS t, unique_id, montant FROM ventes_oeufs
           UNION ALL SELECT 'VENTE_REFORME', unique_id, montant FROM ventes_reforme) v
       ON v.t = i.cible_type AND v.unique_id = i.cible_unique_id
LEFT JOIN clients c ON c.id = i.client_id
WHERE i.total > COALESCE(v.montant, 0) + 0.01;

-- 4. Paiement : Σ imputations actives ≤ montant du paiement ; idem par remboursement.
SELECT '4 paiement surimpute' AS controle, c.nom AS client,
       'paiement ' || p.unique_id || ' : montant ' || p.montant || ' / imputes ' || SUM(i.montant) AS detail
FROM paiements_client p
JOIN imputations_paiement i ON i.paiement_id = p.id AND i.statut = 'ACTIF'
LEFT JOIN clients c ON c.id = p.client_id
GROUP BY c.nom, p.unique_id, p.montant
HAVING SUM(i.montant) > p.montant + 0.01
UNION ALL
SELECT '4 remboursement surimpute', c.nom,
       'remboursement ' || r.unique_id || ' : montant ' || r.montant || ' / imputes ' || SUM(i.montant)
FROM remboursements_client r
JOIN imputations_paiement i ON i.cible_type = 'REMBOURSEMENT' AND i.cible_unique_id = r.unique_id AND i.statut = 'ACTIF'
LEFT JOIN clients c ON c.id = r.client_id
GROUP BY c.nom, r.unique_id, r.montant
HAVING SUM(i.montant) > r.montant + 0.01;

-- 5. Aucune imputation active sur un paiement ANNULE, un remboursement ANNULE, une vente
--    supprimée ou introuvable, ni rattachée à un autre client que celui du paiement.
SELECT '5 imputation orpheline' AS controle, c.nom AS client,
       'imputation ' || i.unique_id || ' (' || i.cible_type || ' ' || i.cible_unique_id || ') : ' ||
       CASE WHEN p.statut <> 'ACTIF' THEN 'paiement annule'
            WHEN p.client_id <> i.client_id THEN 'paiement d''un autre client'
            WHEN i.cible_type = 'REMBOURSEMENT' AND r.id IS NULL THEN 'remboursement introuvable'
            WHEN i.cible_type = 'REMBOURSEMENT' THEN 'remboursement annule'
            WHEN vo.id IS NULL AND vr.id IS NULL THEN 'vente introuvable'
            ELSE 'vente supprimee' END AS detail
FROM imputations_paiement i
JOIN paiements_client p ON p.id = i.paiement_id
LEFT JOIN clients c ON c.id = i.client_id
LEFT JOIN remboursements_client r ON i.cible_type = 'REMBOURSEMENT' AND r.unique_id = i.cible_unique_id
LEFT JOIN ventes_oeufs vo ON i.cible_type = 'VENTE_OEUFS' AND vo.unique_id = i.cible_unique_id
LEFT JOIN ventes_reforme vr ON i.cible_type = 'VENTE_REFORME' AND vr.unique_id = i.cible_unique_id
WHERE i.statut = 'ACTIF'
  AND (p.statut <> 'ACTIF'
       OR p.client_id <> i.client_id
       OR (i.cible_type = 'REMBOURSEMENT' AND (r.id IS NULL OR r.statut <> 'ACTIF'))
       OR (i.cible_type = 'VENTE_OEUFS' AND (vo.id IS NULL OR vo.removed = true))
       OR (i.cible_type = 'VENTE_REFORME' AND (vr.id IS NULL OR vr.removed = true)));

-- 6. Comptabilité : Σ transactions « Paiement client » non supprimées = Σ paiements
--    actifs (par client) ; idem « Remboursement au client » / remboursements actifs.
SELECT '6 compta paiements' AS controle, c.nom AS client,
       'transactions ' || COALESCE(t.total, 0) || ' / paiements ' || COALESCE(p.total, 0) AS detail
FROM clients c
LEFT JOIN (SELECT client_id, SUM(montant) AS total FROM transactions
           WHERE source_type = 'PAIEMENT_CLIENT' AND COALESCE(removed, false) = false GROUP BY client_id) t ON t.client_id = c.id
LEFT JOIN (SELECT client_id, SUM(montant) AS total FROM paiements_client
           WHERE statut = 'ACTIF' GROUP BY client_id) p ON p.client_id = c.id
WHERE ABS(COALESCE(t.total, 0) - COALESCE(p.total, 0)) >= 0.01
UNION ALL
SELECT '6 compta remboursements', c.nom,
       'transactions ' || COALESCE(t.total, 0) || ' / remboursements ' || COALESCE(r.total, 0)
FROM clients c
LEFT JOIN (SELECT client_id, SUM(montant) AS total FROM transactions
           WHERE source_type = 'REMBOURSEMENT_CLI' AND COALESCE(removed, false) = false GROUP BY client_id) t ON t.client_id = c.id
LEFT JOIN (SELECT client_id, SUM(montant) AS total FROM remboursements_client
           WHERE statut = 'ACTIF' GROUP BY client_id) r ON r.client_id = c.id
WHERE ABS(COALESCE(t.total, 0) - COALESCE(r.total, 0)) >= 0.01;

-- 7. Ventes à un client : plus aucun montant rapporté après la reprise (il est devenu un
--    paiement client ; seules les ventes SANS client gardent le contrôle du vendeur).
SELECT '7 montant rapporte restant' AS controle, c.nom AS client,
       'vente ' || v.unique_id || ' du ' || v.date || ' : montant rapporte ' || v.montant_rapporte AS detail
FROM (SELECT unique_id, date, montant_rapporte, client_id, removed FROM ventes_oeufs
      UNION ALL SELECT unique_id, date, montant_rapporte, client_id, removed FROM ventes_reforme) v
JOIN clients c ON c.id = v.client_id
WHERE v.montant_rapporte IS NOT NULL AND COALESCE(v.removed, false) = false;
