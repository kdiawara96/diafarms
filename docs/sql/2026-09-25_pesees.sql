-- Sessions de pesée (2026-09-25) : tables NOUVELLES sessions_pesee et pesees.
-- Rien à faire : Hibernate (ddl-auto=update) les crée au démarrage du nouveau jar,
-- avec les bons UNIQUE (unique_id), la valeur par défaut de pesees.annulee et la
-- contrainte CHECK de sessions_pesee.statut (EN_COURS, TERMINEE).
--
-- Vérification (lecture seule), après le premier démarrage du backend :
SELECT conrelid::regclass AS table_, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN ('sessions_pesee'::regclass, 'pesees'::regclass)
  AND contype IN ('c', 'u')
ORDER BY 1, 2;
-- Attendu : sessions_pesee_statut_check = CHECK (statut IN ('EN_COURS','TERMINEE'))
-- et un UNIQUE (unique_id) sur chacune des deux tables.
