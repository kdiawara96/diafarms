-- Autorise la nouvelle source de transaction MEDICAMENT (achat de médicament ou de vaccin,
-- voir AchatMedicament). ddl-auto=update ne met pas à jour une contrainte CHECK existante.
-- À lancer AVANT de déployer le serveur qui l'utilise. Aucune donnée modifiée.
BEGIN;
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_source_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_source_type_check CHECK (source_type IN
 ('MANUEL','VENTE_OEUFS','VENTE_REFORME','SALAIRE','ALIMENTATION','SOINS','VACCINATION',
  'INVESTISSEMENT','PROJET_ACHAT_SUJETS','PROJET_CHARGES','VENTE_DIVERSE','PAIEMENT_CLIENT',
  'REMBOURSEMENT_CLI','MEDICAMENT'));
COMMIT;
