-- Sessions de pesée menées depuis le web + journal (2026-09-25).
-- Rien à faire : Hibernate (ddl-auto=update) crée au démarrage du nouveau jar :
--   * la table NOUVELLE sessions_pesee_evenements (UNIQUE unique_id, CHECK sur type
--     CREATION_WEB/AJOUT_WEB/MODIFICATION_WEB/ANNULATION_WEB/TERMINAISON_WEB) ;
--   * sessions_pesee.version  bigint DEFAULT 0  (lignes existantes → 0) ;
--   * sessions_pesee.origine  varchar(10) NULL   (null = MOBILE) ;
--   * pesees.origine          varchar(10) NULL   (null = MOBILE) ;
--   * pesees.modifiee         boolean NULL       (null = non modifiée).
-- Toutes les nouvelles colonnes sont nullables ou ont une valeur par défaut : aucune
-- ligne existante n'est bloquée. Aucune nouvelle valeur d'enum sur une colonne
-- existante : pas d'ALTER de contrainte CHECK nécessaire.
--
-- Vérification (lecture seule), après le premier démarrage du backend :
SELECT table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE (table_name = 'sessions_pesee' AND column_name IN ('version', 'origine'))
   OR (table_name = 'pesees' AND column_name IN ('origine', 'modifiee'))
   OR table_name = 'sessions_pesee_evenements'
ORDER BY table_name, ordinal_position;
SELECT COUNT(*) AS sessions_sans_version FROM sessions_pesee WHERE version IS NULL; -- attendu : 0
