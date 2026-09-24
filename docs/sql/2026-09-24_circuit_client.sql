-- Circuit de l'argent client (2026-09-24)
--
-- À lancer APRÈS le redémarrage qui crée les nouvelles tables/colonnes :
-- - PaiementClient, ImputationPaiement, RemboursementClient (modèle)
-- - transactions.source_type : nouveau type PAIEMENT_CLIENT/REMBOURSEMENT_CLI
-- - commandes.statut : nouveaux états EN_LIVRAISON, CLOTUREE
-- - factures.statut : nouvel état ANNULEE
--
-- À lancer AVANT l'endpoint de reprise : POST /diafarms/api/v1/admin/reprise-circuit-client
--
-- Vérifier d'abord les noms réels des contraintes :
-- SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
-- WHERE conrelid IN ('transactions'::regclass,'commandes'::regclass,'factures'::regclass)
-- AND contype='c';
--
-- Note : le script docs/sql/2026-09-23_ventes_diverses.sql doit avoir été exécuté avant celui-ci
-- (il ajoute VENTE_DIVERSE à transactions.source_type ; la contrainte ci-dessous l'inclut déjà).

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

--
-- Utilisation - Endpoint de reprise
--
-- 1. Simulation (examiner les changements, pas d'effet de bord) :
--    POST /diafarms/api/v1/admin/reprise-circuit-client?executer=false
--
-- 2. Exécution (applique les changements) :
--    POST /diafarms/api/v1/admin/reprise-circuit-client?executer=true
--
