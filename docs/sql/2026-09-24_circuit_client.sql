-- Circuit de l'argent client (2026-09-24) — contraintes CHECK des nouvelles valeurs d'enum.
--
-- À lancer AVANT le déploiement du nouveau backend (voir l'ordre complet dans
-- VERSION_2_NOTES.md, section 2026-09-24) : ce script ne fait qu'ÉLARGIR des contraintes
-- sur des tables qui existent déjà (transactions, commandes, factures) — sans effet pour
-- l'ancien jar, qui n'écrit aucune des nouvelles valeurs. Rejouable (DROP IF EXISTS).
-- Sans lui, le nouveau backend échoue dès la première écriture d'une nouvelle valeur
-- (ddl-auto=update ne met jamais à jour une contrainte CHECK existante) :
-- - transactions.source_type : PAIEMENT_CLIENT, REMBOURSEMENT_CLI (+ VENTE_DIVERSE)
-- - commandes.statut : EN_LIVRAISON, CLOTUREE
-- - factures.statut : ANNULEE ; factures.source_type : VENTES
-- Les nouvelles tables (paiements_client, imputations_paiement, remboursements_client,
-- factures_lignes) sont créées par le backend avec leurs contraintes à jour.
--
-- Vérifier d'abord les noms réels des contraintes :
-- SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
-- WHERE conrelid IN ('transactions'::regclass,'commandes'::regclass,'factures'::regclass)
-- AND contype='c';
--
-- Même liste transactions.source_type que docs/sql/2026-09-23_ventes_diverses.sql (étape 1).

BEGIN;

ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_source_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_source_type_check CHECK (source_type IN (
  'MANUEL','VENTE_OEUFS','VENTE_REFORME','SALAIRE','ALIMENTATION','SOINS','VACCINATION',
  'INVESTISSEMENT','PROJET_ACHAT_SUJETS','PROJET_CHARGES','VENTE_DIVERSE','PAIEMENT_CLIENT','REMBOURSEMENT_CLI'));

ALTER TABLE commandes DROP CONSTRAINT IF EXISTS commandes_statut_check;
ALTER TABLE commandes ADD CONSTRAINT commandes_statut_check CHECK (statut IN (
  'EN_ATTENTE','CONFIRMEE','EN_LIVRAISON','CONVERTIE','CLOTUREE','ANNULEE'));

ALTER TABLE factures DROP CONSTRAINT IF EXISTS factures_source_type_check;
ALTER TABLE factures ADD CONSTRAINT factures_source_type_check CHECK (source_type IN (
  'VENTE_OEUFS','VENTE_REFORME','COMMANDE','VENTES'));

ALTER TABLE factures DROP CONSTRAINT IF EXISTS factures_statut_check;
ALTER TABLE factures ADD CONSTRAINT factures_statut_check CHECK (statut IN (
  'IMPAYEE','PARTIELLE','PAYEE','ANNULEE'));

COMMIT;

-- Ensuite : reprise (SUPER_ADMIN uniquement), simulation puis exécution —
--   POST /diafarms/api/v1/admin/reprise-circuit-client?executer=false[&farmUniqueId=...]
--   POST /diafarms/api/v1/admin/reprise-circuit-client?executer=true[&farmUniqueId=...]
-- puis contrôle : docs/sql/2026-09-24_controle_circuit_client.sql (lecture seule).
