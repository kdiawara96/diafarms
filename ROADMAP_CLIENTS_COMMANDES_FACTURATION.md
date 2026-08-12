# Feuille de route — Clients, Utilisateurs, Salaires, Commandes, Facturation

Document de planification (pas encore implémenté). Périmètre : les 3 dépôts
(`diafarms_back`, `Diafarms_web`, `Diafarms` mobile), branche `version_2`.

État des lieux vérifié dans le code avant d'écrire ce document (2026-08-10) :
- **Client** : n'existe nulle part (aucune entité, aucun champ sur `VenteOeufs`/`VenteReforme`). Une vente n'est aujourd'hui liée qu'à un magasin/vendeur/projet.
- **Utilisateurs** : gestion déjà largement existante (page `Utilisateurs.tsx`, CRUD complet côté back `usersControllers.java` — création, édition, désactivation/réactivation, reset mot de passe, QR). Voir "Question ouverte" plus bas sur ce qu'il reste réellement à faire.
- **Salaire** : n'existe pas comme concept structuré — aujourd'hui un salaire est juste une `Transaction` manuelle avec `catégorie = "Salaires"`, sans notion d'employé/périodicité/historique.
- **Commande** : n'existe pas du tout.
- **Facture** : n'existe pas du tout (aucun modèle, pas de génération PDF).
- **Ledger existant à réutiliser comme modèle** : `SoldeVendeur` (une ligne par vendeur, `solde` ajusté à chaque vente selon l'écart théorique/rapporté, voir `SoldeVendeurServiceImpl.ajusterSolde`) — patron structurel direct pour une future dette client.

## Ordre de dépendance recommandé

```
1. Client (fondation)
2. Rattachement Client aux ventes + dette client (dépend de 1)
3. Commandes (dépend de 1, réutilise la logique de vente/répartition existante)
4. Facturation (dépend de 1, peut générer depuis une vente ou une commande)
5. Salaires (indépendant, peut être fait en parallèle)
6. Utilisateurs — compléments (indépendant, périmètre à confirmer)
```

---

## 1. Gestion des clients (fondation) — ✅ FAIT (2026-08-11)

- [x] Backend : entité `Client` (uniqueId, nom, téléphone, adresse, email optionnel, farm, `Initialisation`) — même patron que `Magasin`/`Batiment`.
- [x] Backend : `ClientRepo`, `ClientDTO`, `ClientServiceImpl`, `ClientController` — CRUD standard (create/update/list/search paginée/deleteOrRecover). Création ouverte à ADMIN/RESPONSABLE/VENTE, modification/suppression réservées à ADMIN/RESPONSABLE.
- [x] Web : page `/clients` (liste + recherche + pagination, dialogues créer/modifier), suit le patron `Magasins.tsx`. Visible à ADMIN/RESPONSABLE/COMPTABLE/VENTE.
- [ ] Mobile : pas encore fait — reste à ajouter un sélecteur de client (optionnel) sur les écrans de vente mobile (`SaisieFormActivity`), avec fallback "sans client". **❓ Question toujours ouverte** : un vendeur mobile doit-il pouvoir créer un client à la volée, ou uniquement choisir parmi ceux déjà créés côté web ?

## 2. Rattachement du client aux ventes + dette client — ✅ FAIT côté backend+web (2026-08-11)

- [x] Backend : `client` (`@ManyToOne`, **nullable**) ajouté à `VenteOeufs` et `VenteReforme` — on peut toujours vendre sans client ("vente directe").
- [x] Backend : migration additive uniquement (colonne nullable, `ddl-auto=update` a suffi).
- [x] Web : `CreateVenteOeufsDialog`/`CreateVenteReformeDialog` — sélecteur de client optionnel ("Vente directe (sans client)" par défaut) + bouton "+ Client" pour créer un client à la volée sans quitter le formulaire.
- [ ] Mobile : pas encore fait (même sélecteur à ajouter sur `SaisieFormActivity`).

### Dette client — **✅ Option A retenue et implémentée**

Aujourd'hui, l'écart entre `montant` (théorique) et `montantRapporte` (ce que le
vendeur a rapporté) alimente uniquement `SoldeVendeur` — c'est-à-dire que le
système traite systématiquement cet écart comme si le **vendeur** était en
tort (erreur de caisse, vol, oubli), qu'il y ait un client identifié ou non.

Or si une vente est faite **à un client identifié** et que l'écart correspond
en réalité à une **vente à crédit** (le client n'a pas encore tout payé), ce
n'est pas le vendeur qui doit cet argent à la ferme — c'est le client. Deux
options :

- **Option A (recommandée)** : quand `client` est renseigné sur la vente, l'écart théorique/rapporté alimente un nouveau `SoldeClient` (même patron que `SoldeVendeur` : une ligne par client, `solde` ajusté à la création/modification/suppression d'une vente) **au lieu de** `SoldeVendeur`. Quand `client` est absent (vente anonyme), comportement inchangé : l'écart reste sur `SoldeVendeur` (on ne peut pas relancer un client qu'on n'a pas identifié).
- **Option B** : garder `SoldeVendeur` tel quel dans tous les cas, et ajouter `SoldeClient` comme un **second** ledger indépendant, purement informatif — le vendeur reste responsable de l'écart devant la ferme, mais on note en parallèle que "le client X doit encore Y" sans que ça change la comptabilité vendeur.

L'option A change un comportement déjà en production (le calcul de
`SoldeVendeur`/`totalDuParVendeurs` affiché en Comptabilité/Reporting) — à
valider explicitement avec l'utilisateur avant de coder.

- [x] Choix retenu : **Option A**, implémentée (l'utilisateur a validé "vas y").
- [x] Backend : entité `SoldeClient` (uniqueId, client — FK unique, farm, solde, `Initialisation`), repo/service/controller — miroir de `SoldeVendeur`. `VenteOeufsImpl`/`VenteReformeImpl.ajusterEcart` route vers `SoldeClient` si un client est renseigné, `SoldeVendeur` sinon — le routage suit aussi un changement de client sur une vente déjà créée.
- [x] Backend : `ClientReportDTO` — total acheté (théorique), total payé (réel, `COALESCE(montantRapporte, montant)` par vente), solde dû (depuis `SoldeClient`), historique chronologique des ventes (`GET /clients/{uniqueId}/report`).
- [x] Web : cliquer sur un client dans `/clients` ouvre `ClientDetailDialog` — rapport complet (acheté/payé/dû + historique avec écart théorique/réel par ligne).
- [x] Web : carte "Total dû par les clients" + section "Soldes clients" (liste pliable "Voir plus"/"Voir moins") sur Comptabilité/Ventes/Reporting, colonne Client sur les tableaux Ventes/Comptabilité ("Inconnu" si vente sans client), remboursement de dette client (`payerDette`) avec formulaire dans `ClientDetailDialog`.

## 3. Gestion des commandes — ✅ FAIT côté backend+web (2026-08-12)

Une commande = ce qu'un client demande **avant** que la vente ne soit
finalisée (quantité pas encore livrée, paiement pas encore encaissé, ou
acompte seulement) — distincte d'une vente immédiate.

- [x] Backend : entité `Commande` (uniqueId, client — obligatoire, farm, magasin destination, dateCommande, dateLivraisonPrevue, type OEUFS/REFORME, quantité, prixUnitaireEstime, montantEstime, montantAcompte optionnel, statut EN_ATTENTE/CONFIRMEE/CONVERTIE/ANNULEE, venteUniqueId une fois convertie, `Initialisation`).
- [x] Backend : `CommandeRepo`, `CommandeDTO`, `CommandeServiceImpl`, `CommandeController` — create/update (EN_ATTENTE seulement)/confirmer/annuler/`convertirEnVente` (réutilise directement `VenteOeufsService`/`VenteReformeService.create`, aucune duplication de la logique de répartition ; l'acompte devient `montantRapporte` de la vente, jamais laissé null pour que le reste dû devienne une vraie dette client)/deleteOrRecover (EN_ATTENTE seulement)/list paginée par statut+client.
- [x] Web : page `/commandes` (onglets par statut), `CreateCommandeDialog` (client obligatoire + "+ Client" à la volée, magasin, type, quantité/prix/montant auto-calculé, acompte), bouton "Convertir en vente" par ligne, bouton "+ Nouvelle commande" depuis la fiche client.
- [ ] Mobile : pas fait — **décision prise par défaut** (cohérence avec Client) : ADMIN/RESPONSABLE/VENTE peuvent créer/gérer des commandes côté web, mobile laissé de côté pour l'instant comme pour Client.

## 4. Finaliser la facturation — ✅ FAIT côté backend+web (2026-08-12)

- [x] Décisions tranchées avec l'utilisateur (2026-08-12) : **client obligatoire** sur `Facture` (comme `Commande`, pas de facture anonyme) ; génération PDF **côté backend** (OpenPDF).
- [x] Backend : entité `Facture` (numéroFacture séquentiel par ferme format `FAC-{année}-{seq}`, client obligatoire, dateEmission, sourceType/sourceUniqueId — snapshot figé d'UNE vente ou UNE commande au moment de l'émission, montantTotal, montantPaye, statut PAYEE/PARTIELLE/IMPAYEE, farm).
- [x] Backend : dépendance OpenPDF (`com.github.librepdf:openpdf`), PDF généré depuis la facture (en-tête, client, ligne description/quantité/prix/montant, total/payé/reste, statut).
- [x] Backend : `FactureController` — générer depuis une vente/commande (`sourceType` VENTE_OEUFS/VENTE_REFORME/COMMANDE, une seule facture par source), lister/filtrer par statut+client, télécharger le PDF, marquer comme payée (réutilise `ClientService.payerDette` — vraie Transaction + `SoldeClient`, pas une case cochée isolée).
- [x] Web : page `/factures` (onglets par statut, téléchargement PDF, marquer payée), bouton "Facturer" sur `Commandes.tsx` et sur l'historique d'achats de `ClientDetailDialog` (pas sur Ventes.tsx/Comptabilité : une ligne y est une Transaction, potentiellement scindée en plusieurs par projet contributeur — la vraie source d'une vente est accessible depuis la fiche client). Accès réservé à ADMIN/RESPONSABLE/COMPTABLE.
- [ ] Mobile : hors périmètre (comme RESPONSABLE, la facturation reste une action web ADMIN/COMPTABLE).

## 5. Gestion des salaires

- [ ] Backend : entité `Salaire` (uniqueId, `employe` — FK `Utilisateurs`, farm, montantMensuel de base, historique de paiements — soit une sous-entité `PaiementSalaire` (période, montantPaye, datePaiement, statut) soit un simple historique de `Transaction` liées).
- [ ] Backend : action "Payer le salaire" pour une période donnée → génère automatiquement une `Transaction` (sortie, catégorie "Salaires", nouveau `SourceTransaction.SALAIRE` pour la traçabilité) plutôt que de laisser l'utilisateur saisir une transaction manuelle non structurée comme aujourd'hui — évite la double-saisie et garde la Comptabilité exacte.
- [ ] Backend : `SalaireRepo`, `SalaireDTO`, `SalaireServiceImpl`, `SalaireController`.
- [ ] Web : page `/salaires` — liste des employés avec salaire de base, historique des paiements, bouton "Payer" par période, export Excel mensuel.
- [ ] Mobile : hors périmètre (gestion RH/finance, pas un besoin terrain).

## 6. Gestion des utilisateurs — compléments

La gestion de base existe déjà et semble complète (création, rôles, désactivation,
reset mot de passe, QR). **❓ Question à trancher avec l'utilisateur** : quel
manque précis a motivé cette demande ? Pistes possibles à valider avant de
coder quoi que ce soit ici :
- [ ] Lier un utilisateur directement à sa fiche salaire (voir section 5) depuis la page Utilisateurs.
- [ ] Rattacher un utilisateur VENTE à un ou plusieurs magasins depuis SA fiche (aujourd'hui ça se fait uniquement depuis la fiche du magasin, dans l'autre sens).
- [ ] Suppression définitive (hard delete) en plus de la désactivation actuelle.
- [ ] Autre chose de précis identifié par l'utilisateur.

---

## Notes transverses

- Toutes les nouvelles entités suivent le patron déjà en place dans ce projet : `uniqueId` (UUID), `farm` (scoping obligatoire), `Initialisation` (audit + soft delete via `removed`), DTO dédié avec `fromEntity`/`select`, repo Spring Data avec requêtes JPQL explicites documentées.
- Toute nouvelle colonne doit rester **nullable** à l'ajout (`ddl-auto=update` ne gère pas les colonnes NOT NULL sur une table déjà peuplée sans migration SQL manuelle — leçon déjà tirée cette session sur `Batiment`/`Magasin`).
- Avant de coder chaque section, relire les **❓ Questions à trancher** correspondantes avec l'utilisateur — plusieurs changent un comportement métier déjà en production (notamment la dette client vs dette vendeur).
