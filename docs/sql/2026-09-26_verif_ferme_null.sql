-- Vérifications en lecture seule avant/après le déploiement du contrôle de ferme
-- (FermeScope, commit du 2026-09-25). Aucune écriture : SELECT uniquement.
-- Usage : psql -h <hôte> -p <port> -U <user> -d <base> -f verif-ferme-null.sql
-- Toute ligne > 0 ci-dessous = enregistrement devenu invisible/inmodifiable
-- (farm_id null) ou rattachement incohérent entre fermes.

BEGIN TRANSACTION READ ONLY;

\echo '=== 1. Lignes sans ferme (farm_id NULL) ==='
SELECT 'projets'         AS table_name, count(*) AS farm_id_null FROM projets         WHERE farm_id IS NULL
UNION ALL SELECT 'races',           count(*) FROM races           WHERE farm_id IS NULL
UNION ALL SELECT 'batiments',       count(*) FROM batiments       WHERE farm_id IS NULL
UNION ALL SELECT 'transactions',    count(*) FROM transactions    WHERE farm_id IS NULL
UNION ALL SELECT 'collectes_oeufs', count(*) FROM collectes_oeufs WHERE farm_id IS NULL
UNION ALL SELECT 'clients',         count(*) FROM clients         WHERE farm_id IS NULL
UNION ALL SELECT 'investissements', count(*) FROM investissements WHERE farm_id IS NULL
UNION ALL SELECT 'salaires',        count(*) FROM salaires        WHERE farm_id IS NULL
-- Rôles : table de jointure roles_users (id_utilisateurs, id_roles) -> roles.role.
-- Le SUPER_ADMIN n'a normalement pas de ferme : exclu du décompte.
UNION ALL SELECT 'utilisateurs (hors SUPER_ADMIN)', count(*) FROM utilisateurs u
  WHERE u.farm_id IS NULL
    AND NOT EXISTS (SELECT 1 FROM roles_users ru JOIN roles r ON r.id = ru.id_roles
                    WHERE ru.id_utilisateurs = u.id AND upper(r.role) = 'SUPER_ADMIN');

\echo '=== 1b. Détail des utilisateurs sans ferme (hors SUPER_ADMIN) ==='
SELECT u.id, u.email,
       (SELECT string_agg(r.role, ',') FROM roles_users ru JOIN roles r ON r.id = ru.id_roles
         WHERE ru.id_utilisateurs = u.id) AS roles
FROM utilisateurs u
WHERE u.farm_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM roles_users ru JOIN roles r ON r.id = ru.id_roles
                  WHERE ru.id_utilisateurs = u.id AND upper(r.role) = 'SUPER_ADMIN')
ORDER BY u.id;

\echo '=== 2. Projets dont la race appartient à une autre ferme ==='
SELECT p.id AS projet_id, p.code, p.titre, p.farm_id AS ferme_projet,
       r.id AS race_id, r.nom AS race, r.farm_id AS ferme_race
FROM projets p
JOIN races r ON r.id = p.race_id
WHERE r.farm_id IS DISTINCT FROM p.farm_id
ORDER BY p.id;

\echo '=== 3. Projets dont un responsable (général, finance, production) est d''une autre ferme ==='
SELECT p.id AS projet_id, p.code, p.titre, p.farm_id AS ferme_projet,
       x.role_projet, u.id AS utilisateur_id, u.email, u.farm_id AS ferme_utilisateur
FROM projets p
CROSS JOIN LATERAL (VALUES ('responsable', p.responsable_user_id),
                           ('finance',     p.finance_user_id),
                           ('production',  p.production_user_id)) AS x(role_projet, user_id)
JOIN utilisateurs u ON u.id = x.user_id
WHERE u.farm_id IS DISTINCT FROM p.farm_id
ORDER BY p.id, x.role_projet;

ROLLBACK;
