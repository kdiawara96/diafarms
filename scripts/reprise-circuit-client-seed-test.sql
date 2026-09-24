-- Données de test « forme ancienne » (avant la refonte du circuit de l'argent client)
-- pour vérifier POST /admin/reprise-circuit-client. À charger sur une base de TEST
-- déjà créée par le backend (tables existantes), jamais en production :
--
--   psql -d <base_test> -v farm=1 -v admin=2 -v vendeur=4 -v magasin=2 \
--        -f scripts/reprise-circuit-client-seed-test.sql
--
-- farm = id de la ferme, admin / vendeur = ids d'utilisateurs de cette ferme,
-- magasin = id d'un magasins_vente de cette ferme. Tout est préfixé « rep- » (unique_id)
-- / « REP » (ref, numéro de facture) ; relancer le script supprime d'abord ces lignes.
--
-- Ce que faisait l'ancien code (commit 5d7a75b) et que le script reproduit :
--   * payerDette / acompte : Transaction ENTREE MANUEL avec client, catégorie
--     « Remboursement client » ou « Acompte client » ; soldes_client -= montant.
--   * rembourser : Transaction SORTIE MANUEL « Remboursement au client » ; solde += montant.
--   * vente avec client : solde += montant − montant_rapporte.
--   * marquerPayee : payerDette (« Paiement facture <numéro> ») + montant_paye de la
--     facture + RECOPIE du même montant dans montant_rapporte de la vente (le double
--     comptage que la reprise retire).
--   * livraison de commande : vente créée, commande.vente_unique_id = DERNIÈRE vente.
--
-- Soldes attendus après reprise (solde = reste à payer − avance, positif = doit) :
--   Rep L1 commande   : avant 15000, après 15000 (écart 0)
--   Rep L2 factures   : avant 0,     après 0     (écart 0, 2 recopies retirées)
--   Rep L3 avance     : avant -4000, après -4000 (écart 0, 1 rejetée ignorée, 1 supprimée)
--   Rep L4 rembourst  : avant 5000,  après 0     (écart -5000 : remboursement non couvert)
--   Rep L5 cmd+fact.  : avant 0,     après 0     (écart 0, 1 recopie retirée)
--   Rep L6 paiement   : avant -3000, après -3000 (écart 0)
--   Rep L7 cmd annulée: avant 50000, après 50000 (écart 0 : le remboursement est imputé
--                       sur l'acompte AVANT que la vente postérieure ne l'absorbe)
--   Rep L8 fact.annul.: avant 0,     après 0     (écart 0 : recopie retirée malgré ANNULEE)
--   Rep L9 fact.legacy: avant 15000, après 15000 (écart 0) ; facture FAC-REP-0004 reçoit
--                       une ligne (rep-vr-9, 30000), payé 15000 / reste 15000 (PARTIELLE)
--   Rep L10 sans rapp.: avant 0,     après 0     (écart 0 : vente d'août sans montant
--                       rapporté = payée ; paiement de 7000 créé et listé)
--   Rep L11 en attente: avant -2500, après 0     (écart +2500 : paiement EN_ATTENTE ignoré,
--                       avertissement « à valider ou rejeter avant la reprise »)
-- Compteurs attendus pour ces clients (première exécution) :
--   paiementsCrees = 17 (10 transactions reprises : L1 acompte, L2 ×3, L3, L5, L6, L7, L8, L9 ;
--                        + 6 ventes à montant rapporté > 0 : rep-vo-1b, rep-vo-2, rep-vo-2b, rep-vr-5,
--                          rep-vo-8, rep-vr-9 ; + 1 vente sans montant rapporté : rep-vo-10)
--   remboursementsCrees = 3 (L3, L4, L7) ; recopiesFacturesRetirees = 5 ;
--   ventesConverties = 9 (les 6 ci-dessus + rep-vo-1a, rep-vr-3, rep-vo-7 à 0) ;
--   lignesFacturesCreees = 5 (FAC-REP-0001, 00010, 0002 sur rep-vr-5, 0003, 0004) ;
--   ventesSansMontantRapporte = [rep-vo-10, Rep L10, 2026-08-15, 7000] ;
--   avertissements : REP-007 rejetée, REP-008 supprimée, REP-015 en attente, commande
--   rep-cmd-1 (livraison antérieure non rattachée), remboursement L4 non couvert.
-- Deuxième exécution : 0 partout (idempotence).

BEGIN;

-- Nettoyage (relance)
DELETE FROM imputations_paiement WHERE client_id IN (SELECT id FROM clients WHERE unique_id LIKE 'rep-cli-%');
DELETE FROM remboursements_client WHERE client_id IN (SELECT id FROM clients WHERE unique_id LIKE 'rep-cli-%');
DELETE FROM paiements_client WHERE client_id IN (SELECT id FROM clients WHERE unique_id LIKE 'rep-cli-%');
DELETE FROM transactions WHERE client_id IN (SELECT id FROM clients WHERE unique_id LIKE 'rep-cli-%') OR unique_id LIKE 'rep-%';
DELETE FROM factures_lignes WHERE facture_id IN (SELECT id FROM factures WHERE unique_id LIKE 'rep-%');
DELETE FROM factures WHERE unique_id LIKE 'rep-%';
DELETE FROM ventes_oeufs WHERE unique_id LIKE 'rep-%';
DELETE FROM ventes_reforme WHERE unique_id LIKE 'rep-%';
DELETE FROM commandes WHERE unique_id LIKE 'rep-%';
DELETE FROM soldes_client WHERE unique_id LIKE 'rep-%';
DELETE FROM clients WHERE unique_id LIKE 'rep-cli-%';

INSERT INTO clients (unique_id, nom, telephone, farm_id, removed, archive, created_at) VALUES
 ('rep-cli-1', 'Rep L1 commande',  '79000001', :farm, false, false, now()),
 ('rep-cli-2', 'Rep L2 factures',  '79000002', :farm, false, false, now()),
 ('rep-cli-3', 'Rep L3 avance',    '79000003', :farm, false, false, now()),
 ('rep-cli-4', 'Rep L4 rembourst', '79000004', :farm, false, false, now()),
 ('rep-cli-5', 'Rep L5 cmd+fact',  '79000005', :farm, false, false, now()),
 ('rep-cli-6', 'Rep L6 paiement',  '79000006', :farm, false, false, now()),
 ('rep-cli-7', 'Rep L7 cmd annulee', '79000007', :farm, false, false, now()),
 ('rep-cli-8', 'Rep L8 fact annulee', '79000008', :farm, false, false, now()),
 ('rep-cli-9', 'Rep L9 fact legacy', '79000009', :farm, false, false, now()),
 ('rep-cli-10', 'Rep L10 sans rapporte', '79000010', :farm, false, false, '2026-08-10'),
 ('rep-cli-11', 'Rep L11 en attente', '79000011', :farm, false, false, now());

-- ---------------------------------------------------------------------------------
-- L1 : commande 100 œufs à 1000, acompte 40000, deux livraisons de 30 (0 puis 5000
-- reçus à la livraison). vente_unique_id = dernière livraison seulement.
-- Ancien solde : -40000 + (30000-0) + (30000-5000) = 15000.
INSERT INTO ventes_oeufs (unique_id, date, montant, montant_rapporte, quantite_oeufs, prix_unitaire, type_oeuf,
                          client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vo-1a', '2026-09-02', 30000, 0,    30, 1000, 'BON', (SELECT id FROM clients WHERE unique_id='rep-cli-1'), :farm, :magasin, :vendeur, false, false, now()),
 ('rep-vo-1b', '2026-09-05', 30000, 5000, 30, 1000, 'BON', (SELECT id FROM clients WHERE unique_id='rep-cli-1'), :farm, :magasin, :vendeur, false, false, now());
INSERT INTO commandes (unique_id, client_id, magasin_id, type, quantite, quantite_livree, prix_unitaire_estime, montant_estime,
                       montant_acompte, date_commande, statut, vente_unique_id, cree_par_id, farm_id, removed, archive, created_at) VALUES
 ('rep-cmd-1', (SELECT id FROM clients WHERE unique_id='rep-cli-1'), :magasin, 'OEUFS', 100, 60, 1000, 100000,
  40000, '2026-09-01', 'CONFIRMEE', 'rep-vo-1b', :admin, :farm, false, false, now());
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-1', 'REP-001', '2026-09-01', 'ENTREE', 40000, 'Acompte client', 'VALIDE', 'MANUEL',
  'Acompte sur commande — 100 (OEUFS)', (SELECT id FROM clients WHERE unique_id='rep-cli-1'), :farm, :admin, false, false, now());

-- ---------------------------------------------------------------------------------
-- L2 : vente 50000 (20000 rapportés), facture FAC-REP-0001 payée 10000 + 20000 via
-- marquerPayee (recopiés : montant_rapporte 20000 -> 50000) ; vente 8000 (3000
-- rapportés), facture FAC-REP-00010 payée 5000 (recopié : 3000 -> 8000). Le numéro
-- FAC-REP-00010 commence par FAC-REP-0001 : vérifie que les recopies ne se mélangent pas.
-- Ancien solde : (50000-20000) - 30000 + (8000-3000) - 5000 = 0.
INSERT INTO ventes_oeufs (unique_id, date, montant, montant_rapporte, quantite_oeufs, prix_unitaire, type_oeuf,
                          client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vo-2',  '2026-09-03', 50000, 50000, 50, 1000, 'BON', (SELECT id FROM clients WHERE unique_id='rep-cli-2'), :farm, :magasin, :vendeur, false, false, now()),
 ('rep-vo-2b', '2026-09-04', 8000,  8000,  8,  1000, 'BON', (SELECT id FROM clients WHERE unique_id='rep-cli-2'), :farm, :magasin, :vendeur, false, false, now());
INSERT INTO factures (unique_id, numero_facture, client_id, farm_id, date_emission, source_type, source_unique_id, description,
                      quantite, prix_unitaire, montant_total, montant_paye, statut, legacy, cree_par_id, removed, archive, created_at) VALUES
 ('rep-fac-1',  'FAC-REP-0001',  (SELECT id FROM clients WHERE unique_id='rep-cli-2'), :farm, '2026-09-03', 'VENTE_OEUFS', 'rep-vo-2',
  '50 œufs', 50, 1000, 50000, 30000, 'PARTIELLE', false, :admin, false, false, now()),
 ('rep-fac-10', 'FAC-REP-00010', (SELECT id FROM clients WHERE unique_id='rep-cli-2'), :farm, '2026-09-04', 'VENTE_OEUFS', 'rep-vo-2b',
  '8 œufs', 8, 1000, 8000, 5000, 'PARTIELLE', false, :admin, false, false, now());
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-2a', 'REP-002', '2026-09-06', 'ENTREE', 10000, 'Remboursement client', 'VALIDE', 'MANUEL',
  'Paiement facture FAC-REP-0001', (SELECT id FROM clients WHERE unique_id='rep-cli-2'), :farm, :admin, false, false, now()),
 ('rep-tx-2b', 'REP-003', '2026-09-08', 'ENTREE', 20000, 'Remboursement client', 'VALIDE', 'MANUEL',
  'Paiement facture FAC-REP-0001', (SELECT id FROM clients WHERE unique_id='rep-cli-2'), :farm, :admin, false, false, now()),
 ('rep-tx-2c', 'REP-004', '2026-09-08', 'ENTREE', 5000, 'Remboursement client', 'VALIDE', 'MANUEL',
  'Paiement facture FAC-REP-00010', (SELECT id FROM clients WHERE unique_id='rep-cli-2'), :farm, :admin, false, false, now());

-- ---------------------------------------------------------------------------------
-- L3 : vente de réforme 15000 non payée à la vente, paiement de dette 25000,
-- remboursement de 6000. Plus : un paiement REJETE (ignoré, avertissement) et un
-- acompte supprimé (removed, ignoré). Ancien solde : 15000 - 25000 + 6000 = -4000.
INSERT INTO ventes_reforme (unique_id, date, montant, montant_rapporte, nombre_sujets, prix_unitaire, type_vente,
                            client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vr-3', '2026-09-02', 15000, 0, 3, 5000, 'TETE', (SELECT id FROM clients WHERE unique_id='rep-cli-3'), :farm, :magasin, :vendeur, false, false, now());
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-3a', 'REP-005', '2026-09-04', 'ENTREE', 25000, 'Remboursement client', 'VALIDE', 'MANUEL',
  'Paiement de dette — Rep L3 avance', (SELECT id FROM clients WHERE unique_id='rep-cli-3'), :farm, :admin, false, false, now()),
 ('rep-tx-3b', 'REP-006', '2026-09-07', 'SORTIE', 6000, 'Remboursement au client', 'VALIDE', 'MANUEL',
  'Remboursement d''une avance — Rep L3 avance', (SELECT id FROM clients WHERE unique_id='rep-cli-3'), :farm, :admin, false, false, now()),
 ('rep-tx-3c', 'REP-007', '2026-09-05', 'ENTREE', 7000, 'Remboursement client', 'REJETE', 'MANUEL',
  'Paiement saisi par erreur', (SELECT id FROM clients WHERE unique_id='rep-cli-3'), :farm, :admin, false, false, now()),
 ('rep-tx-3d', 'REP-008', '2026-09-05', 'ENTREE', 9999, 'Acompte client', 'VALIDE', 'MANUEL',
  'Acompte supprimé', (SELECT id FROM clients WHERE unique_id='rep-cli-3'), :farm, :admin, true, false, now());

-- ---------------------------------------------------------------------------------
-- L4 : remboursement de 5000 sans aucun paiement (donnée incohérente) : la reprise le
-- garde mais ne peut pas l'imputer -> avertissement, écart -5000.
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-4', 'REP-009', '2026-09-06', 'SORTIE', 5000, 'Remboursement au client', 'VALIDE', 'MANUEL',
  'Remboursement sans avance', (SELECT id FROM clients WHERE unique_id='rep-cli-4'), :farm, :admin, false, false, now());

-- ---------------------------------------------------------------------------------
-- L5 : commande REFORME 10 à 5000 entièrement livrée (10000 reçus à la livraison),
-- facture FAC-REP-0002 sur la COMMANDE payée 40000 (recopiés sur la vente
-- commande.vente_unique_id : 10000 -> 50000). Ancien solde : (50000-10000) - 40000 = 0.
INSERT INTO ventes_reforme (unique_id, date, montant, montant_rapporte, nombre_sujets, prix_unitaire, type_vente,
                            client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vr-5', '2026-09-03', 50000, 50000, 10, 5000, 'TETE', (SELECT id FROM clients WHERE unique_id='rep-cli-5'), :farm, :magasin, :vendeur, false, false, now());
INSERT INTO commandes (unique_id, client_id, magasin_id, type, quantite, quantite_livree, prix_unitaire_estime, montant_estime,
                       montant_acompte, date_commande, statut, vente_unique_id, cree_par_id, farm_id, removed, archive, created_at) VALUES
 ('rep-cmd-5', (SELECT id FROM clients WHERE unique_id='rep-cli-5'), :magasin, 'REFORME', 10, 10, 5000, 50000,
  0, '2026-09-01', 'CONVERTIE', 'rep-vr-5', :admin, :farm, false, false, now());
INSERT INTO factures (unique_id, numero_facture, client_id, farm_id, date_emission, source_type, source_unique_id, description,
                      quantite, prix_unitaire, montant_total, montant_paye, statut, legacy, cree_par_id, removed, archive, created_at) VALUES
 ('rep-fac-2', 'FAC-REP-0002', (SELECT id FROM clients WHERE unique_id='rep-cli-5'), :farm, '2026-09-03', 'COMMANDE', 'rep-cmd-5',
  '10 sujets de réforme', 10, 5000, 50000, 40000, 'PARTIELLE', false, :admin, false, false, now());
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-5', 'REP-010', '2026-09-09', 'ENTREE', 40000, 'Remboursement client', 'VALIDE', 'MANUEL',
  'Paiement facture FAC-REP-0002', (SELECT id FROM clients WHERE unique_id='rep-cli-5'), :farm, :admin, false, false, now());

-- ---------------------------------------------------------------------------------
-- L6 : transaction manuelle « Paiement client » sans vente -> avance 3000.
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-6', 'REP-011', '2026-09-10', 'ENTREE', 3000, 'Paiement client', 'VALIDE', 'MANUEL',
  'Avance versée', (SELECT id FROM clients WHERE unique_id='rep-cli-6'), :farm, :admin, false, false, now());

-- ---------------------------------------------------------------------------------
-- L7 : acompte 40000 sur une commande, commande annulée et acompte remboursé (40000),
-- puis plus tard une vente à crédit de 50000 (rien reçu). Ancien solde :
-- -40000 + 40000 + 50000 = 50000. Le remboursement doit consommer l'acompte (reçu
-- avant lui), pas laisser la vente l'absorber (sinon solde 10000).
INSERT INTO commandes (unique_id, client_id, magasin_id, type, quantite, quantite_livree, prix_unitaire_estime, montant_estime,
                       montant_acompte, date_commande, statut, vente_unique_id, cree_par_id, farm_id, removed, archive, created_at) VALUES
 ('rep-cmd-7', (SELECT id FROM clients WHERE unique_id='rep-cli-7'), :magasin, 'OEUFS', 40, 0, 1000, 40000,
  40000, '2026-08-01', 'ANNULEE', NULL, :admin, :farm, false, false, now());
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-7a', 'REP-012', '2026-08-01', 'ENTREE', 40000, 'Acompte client', 'VALIDE', 'MANUEL',
  'Acompte sur commande — 40 (OEUFS)', (SELECT id FROM clients WHERE unique_id='rep-cli-7'), :farm, :admin, false, false, now()),
 ('rep-tx-7b', 'REP-013', '2026-08-05', 'SORTIE', 40000, 'Remboursement au client', 'VALIDE', 'MANUEL',
  'Commande annulée, acompte rendu', (SELECT id FROM clients WHERE unique_id='rep-cli-7'), :farm, :admin, false, false, now());
INSERT INTO ventes_oeufs (unique_id, date, montant, montant_rapporte, quantite_oeufs, prix_unitaire, type_oeuf,
                          client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vo-7', '2026-09-01', 50000, 0, 50, 1000, 'BON', (SELECT id FROM clients WHERE unique_id='rep-cli-7'), :farm, :magasin, :vendeur, false, false, now());

-- ---------------------------------------------------------------------------------
-- L8 : vente 20000 (5000 rapportés), facture FAC-REP-0003 payée 15000 via marquerPayee
-- (recopié : 5000 -> 20000), puis facture ANNULEE (entre le déploiement et la reprise).
-- La recopie doit être retirée quand même. Ancien solde : (20000-5000) - 15000 = 0.
INSERT INTO ventes_oeufs (unique_id, date, montant, montant_rapporte, quantite_oeufs, prix_unitaire, type_oeuf,
                          client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vo-8', '2026-09-02', 20000, 20000, 20, 1000, 'BON', (SELECT id FROM clients WHERE unique_id='rep-cli-8'), :farm, :magasin, :vendeur, false, false, now());
INSERT INTO factures (unique_id, numero_facture, client_id, farm_id, date_emission, source_type, source_unique_id, description,
                      quantite, prix_unitaire, montant_total, montant_paye, statut, legacy, motif_annulation, cree_par_id, removed, archive, created_at) VALUES
 ('rep-fac-3', 'FAC-REP-0003', (SELECT id FROM clients WHERE unique_id='rep-cli-8'), :farm, '2026-09-02', 'VENTE_OEUFS', 'rep-vo-8',
  '20 œufs', 20, 1000, 20000, 15000, 'ANNULEE', false, 'Erreur de facturation', :admin, false, false, now());
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-8', 'REP-014', '2026-09-06', 'ENTREE', 15000, 'Remboursement client', 'VALIDE', 'MANUEL',
  'Paiement facture FAC-REP-0003', (SELECT id FROM clients WHERE unique_id='rep-cli-8'), :farm, :admin, false, false, now());

-- ---------------------------------------------------------------------------------
-- L9 : vente de réforme 30000 avec 10000 rapportés ; facture FAC-REP-0004 générée par
-- l'ancien code (montant_paye initial = montant rapporté 10000), puis marquerPayee 5000
-- (montant_paye 15000, recopie : montant_rapporte 10000 -> 15000). Ancien solde :
-- 30000 - 10000 - 5000 = 15000. La reprise lui crée une ligne ; payé = 15000 (historique)
-- + paiements reçus après la reprise ; payable encore 15000, pas plus.
INSERT INTO ventes_reforme (unique_id, date, montant, montant_rapporte, nombre_sujets, prix_unitaire, type_vente,
                            client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vr-9', '2026-09-04', 30000, 15000, 6, 5000, 'TETE', (SELECT id FROM clients WHERE unique_id='rep-cli-9'), :farm, :magasin, :vendeur, false, false, now());
INSERT INTO factures (unique_id, numero_facture, client_id, farm_id, date_emission, source_type, source_unique_id, description,
                      quantite, prix_unitaire, montant_total, montant_paye, statut, legacy, cree_par_id, removed, archive, created_at) VALUES
 ('rep-fac-4', 'FAC-REP-0004', (SELECT id FROM clients WHERE unique_id='rep-cli-9'), :farm, '2026-09-04', 'VENTE_REFORME', 'rep-vr-9',
  '6 sujets de réforme', 6, 5000, 30000, 15000, 'PARTIELLE', false, :admin, false, false, now());
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-9', 'REP-016', '2026-09-07', 'ENTREE', 5000, 'Remboursement client', 'VALIDE', 'MANUEL',
  'Paiement facture FAC-REP-0004', (SELECT id FROM clients WHERE unique_id='rep-cli-9'), :farm, :admin, false, false, now());

-- ---------------------------------------------------------------------------------
-- L10 : vente d'œufs à un client SANS montant rapporté, saisie le 15/08 (ancien code,
-- montant rapporté encore facultatif) : l'ancien modèle la considérait payée (rien au
-- solde). Ancien solde : 0. La reprise crée un paiement de 7000 qui la règle.
INSERT INTO ventes_oeufs (unique_id, date, montant, montant_rapporte, quantite_oeufs, prix_unitaire, type_oeuf,
                          client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vo-10', '2026-08-15', 7000, NULL, 7, 1000, 'BON', (SELECT id FROM clients WHERE unique_id='rep-cli-10'), :farm, :magasin, :vendeur, false, false, '2026-08-15 10:00');

-- ---------------------------------------------------------------------------------
-- L11 : paiement client de 2500 resté EN_ATTENTE (ancien circuit de validation) :
-- ignoré par la reprise avec avertissement. Ancien solde : -2500 ; après : 0.
INSERT INTO transactions (unique_id, ref, date, type, montant, categorie, statut, source_type, description,
                          client_id, farm_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-tx-11', 'REP-015', '2026-09-11', 'ENTREE', 2500, 'Paiement client', 'EN_ATTENTE', 'MANUEL',
  'Avance en attente de validation', (SELECT id FROM clients WHERE unique_id='rep-cli-11'), :farm, :admin, false, false, now());

-- Vente SANS client avec montant rapporté : ne doit pas être touchée par la reprise.
INSERT INTO ventes_oeufs (unique_id, date, montant, montant_rapporte, quantite_oeufs, prix_unitaire, type_oeuf,
                          client_id, farm_id, magasin_id, cree_par_id, removed, archive, created_at) VALUES
 ('rep-vo-sans-client', '2026-09-05', 12000, 11000, 12, 1000, 'BON', NULL, :farm, :magasin, :vendeur, false, false, now());

-- Anciens soldes stockés (soldes_client), tels que l'ancien code les avait laissés.
INSERT INTO soldes_client (unique_id, client_id, farm_id, solde, removed, archive, created_at)
SELECT 'rep-sc-' || c.unique_id, c.id, :farm, v.solde, false, false, now()
FROM clients c JOIN (VALUES ('rep-cli-1', 15000.0), ('rep-cli-2', 0.0), ('rep-cli-3', -4000.0),
                            ('rep-cli-4', 5000.0), ('rep-cli-5', 0.0), ('rep-cli-6', -3000.0),
                            ('rep-cli-7', 50000.0), ('rep-cli-8', 0.0), ('rep-cli-9', 15000.0),
                            ('rep-cli-10', 0.0), ('rep-cli-11', -2500.0)) v(uid, solde)
  ON v.uid = c.unique_id;

COMMIT;
