# Diafarms — notes de session `version_2`

Notes de continuité pour une future session (assistant ou humain) — pas de la doc produit. Couvre les trois dépôts, tous sur la branche `version_2` :
- Backend : `/home/mother/Bureau/diafarms_back`
- Web : `/home/mother/Bureau/Diafarms_web`
- Mobile : `/home/mother/Bureau/Diafarms`

## Contexte

`version_2` a été créée pour une révision majeure après une branche `hamidou` de sauvegarde initiale (seed de données fictives 2020→aujourd'hui pour démo client, avec l'aval explicite de l'utilisateur). Depuis, plusieurs phases de travail enchaînées dans la même session longue.

## Ce qui a été fait (par thème, pas chronologique)

### 1. Données de démo
Base vidée puis repeuplée avec un historique fictif cohérent (bâtiments, races, projets ponte/mixte/chair — certains clos certains en cours, notifications, comptes production/finance, investissements, consommations, collectes, dépenses, occupations bâtiments, ventes œufs/réforme) comme si l'utilisateur (Karim) était admin depuis 2020.

### 2. Mobile — correctifs de base
- Clavier qui cachait le champ mot de passe (`LoginActivity`, `SaisieFormActivity`) → gestion des insets IME.
- Rotation native activée sur toutes les activités (`AndroidManifest.xml`, `android:screenOrientation="fullSensor"`).

### 3. Web — Ventes / Production
- Page **Charges** supprimée (doublon avec Comptabilité).
- Nouvelle page **Ventes** (`src/pages/Ventes.tsx`) : boutons Vente d'œufs / Vente réforme / Vente de fientes / Autre, KPIs, filtres.
- Table Production : colonne **Alvéole** (œufs ÷ 30), icône œuf cassé revue.
- Web Alimentation : choix **Achat vs Consommation** (`AlimentationChoiceDialog.tsx`, `CreateAchatAlimentDialog.tsx`), miroir du mobile.
- Mobile : bouton **Vente de fientes** ajouté (`SaisieType.VENTE_FIENTES`), réutilise le flux Entrée/Sortie générique.

### 4. Rôles FINANCIER / PRODUCTEUR sur le web
Les deux rôles peuvent désormais se connecter au web (avant : mobile only), avec restriction stricte des deux côtés (jamais juste côté UI) :
- `src/lib/roles.ts` (web) / `AppAccessRules.java` + petites fonctions `isPureFinancier`/`isPureRole` (back) : **un cumul de rôles garde toujours l'accès complet** — la restriction ne s'applique que si le rôle est SEUL.
- FINANCIER (web) : Tableau de bord (adapté), Comptabilité (scopé à ses projets `responsableFinance`), Ventes (ses propres ventes, filtrable par ADMIN), Profil. Pas de Paramètres. Peut vendre, ne peut pas valider/rejeter (imposé aussi côté back, `TransactionServiceImpl.valider/rejeter`). Ne reçoit **aucune** notification sauf le rejet d'une de ses transactions par l'admin.
- PRODUCTEUR (web) : Tableau de bord (adapté), Production (scopée à ses projets assignés), Profil.
- Filtres admin "voir comme" (financier précis / vendeur précis / plage de dates) sur Comptabilité et Ventes — `TransactionServiceImpl.resolveProjetIdsScope` (stats, auto-restreint) vs `resolveProjetIdsScopeForList`/`resolveVendeurScopeForList` (liste, explicite admin seulement).
- Traçabilité créateur : `Transaction.creePar` (nouvelle colonne `cree_par_id`) — alimente la colonne "Vendeur" de Ventes et la notification de rejet. **Rows antérieures à ce champ = `cree_par_id` NULL, pas un bug** (voir backfill ponctuel fait en session pour 2 lignes historiques identifiées par l'utilisateur).

### 5. FarmAppSettings — accès mobile/web configurable par l'admin
Nouvelle entité `FarmAppSettings` (8 booléens, un par ferme) : PRODUCTEUR mobile/web, FINANCIER web, + 5 actions FINANCIER mobile (vente œufs/réforme/fientes, entrée, sortie). Si aucune des 5 actions FINANCIER mobile n'est active → pas d'accès mobile du tout.
- Back : `models/FarmAppSettings.java`, `repository/FarmAppSettingsRepo.java`, `DTO/FarmAppSettingsDTO.java`, `ServiceImpl/FarmAppSettingsServiceImpl.java`, `controllers/FarmAppSettingsController.java` (`GET`/`PUT /farm-settings`), `commons/AppAccessRules.java` (logique partagée).
- **Trois points d'application distincts côté back** (pas un seul, car le mobile via QR ne repasse jamais par `/auth`) :
  1. `AuthImpl.jwt()` — login mot de passe (web, et mobile en théorie même si `LoginActivity` mobile ne l'utilise jamais en pratique, voir plus bas).
  2. `QRCodeController.generateQRCode()`/`scanQRCode()` — génération du QR par l'admin.
  3. `UserStatusJwtValidator` — revalidation continue de CHAQUE requête authentifiée pour un token `type=QR_CODE` : c'est le vrai point d'application pour une session mobile déjà ouverte, puisque `CameraScanActivity` déchiffre et utilise le JWT côté client sans jamais appeler `/scan`.
- Web : section "Accès à l'application" dans Paramètres (`Parametres.tsx`), toggles `Switch`.
- Mobile : `HomeActivity.loadFarmAppSettings()` masque par défaut les 5 boutons Finance et ne révèle que ceux autorisés (fail-closed si l'appel échoue).
- **Point important découvert en session** : `LoginActivity.attemptLogin()` mobile est **100% offline** (déverrouille une session déjà stockée localement, jamais d'appel réseau) — la vérification `clientType=mobile` dans `AuthImpl.jwt()` ne s'applique donc jamais à ce client mobile précis (reste utile en défense en profondeur pour un autre client hypothétique). Les vrais points d'entrée mobile sont le QR (génération + scan) et la revalidation continue.

### 6. Sécurité mots de passe
- Web ne montre plus **jamais** un mot de passe/identifiant à l'écran (login, inscription, création de compte producteur/financier) — email uniquement (`EmailServiceImpl`, déjà en place, réutilisé).
- Premier compte admin : email de bienvenue avec identifiants, plus d'affichage écran non plus.
- Nouveau bouton admin **"Réinitialiser le mot de passe"** (`Utilisateurs.tsx`, à côté de Modifier/Révoquer, popup `ConfirmDialog` avant envoi) → `UtilisateurImpl.resetPasswordAndNotify()` génère un nouveau mot de passe, force `mustChangePassword`, envoie par email.

### 7. Cohérence des données (plafonds serveur)
Même pattern partout (`IllegalArgumentException` si dépassement, jamais juste un warning UI) :
- Consommation aliment ≤ stock acheté (**déjà en place avant cette session**).
- Vente d'œufs (en unité œufs, le toggle alvéole du web ne fait que convertir avant envoi) ≤ stock ferme (**déjà en place**).
- **Nouveau cette session** : Collecte d'œufs ≤ effectif vivant du projet (`CollecteOeufsImpl.effectifVivant()` = `nbSujets - mortalité - déjà réformés`, même formule que `ReformeImpl`, corrigée en session pour aussi exclure les réformés). Hint visuel "🐔 Effectif vivant disponible" sur les dialogues web création/édition de collecte (réutilise `GET /reformes/effectif/{projetUniqueId}`, pas de nouvel endpoint).

### 8. Import Excel en masse (web)
Le client a commencé à saisir sur papier → besoin d'importer en masse plutôt que ligne par ligne. Infra générique + 6 domaines :
- `src/lib/importExcel.ts` (lecture `.xlsx` via `xlsx`/SheetJS déjà utilisé pour l'export, génération de modèle) + `src/components/dialogs/ImportExcelDialog.tsx` (modèle téléchargeable → upload → aperçu ligne par ligne avec erreurs → import **séquentiel** — jamais parallèle, plusieurs domaines ont des plafonds cumulés côté serveur qui raceraient sinon).
- Bâtiments et Races : bouton dans Paramètres.
- Projets (`Projets.tsx`) : recherche **race par code** (`RAC-001`, pas le nom) et **responsables par téléphone** (unique, pas le nom) ; étapes alimentation/vaccination volontairement hors scope (à compléter ensuite depuis la fiche projet).
- Comptabilité (`Comptabilite.tsx`) : import de transactions manuelles, admin seulement.
- Ventes (`Ventes.tsx`) : sélecteur de type puis 4 modèles (œufs/réforme/fientes/autre), champs et endpoints différents par type.
- Production (`Production.tsx`) : sélecteur de type puis 6 modèles (collecte œufs, achat aliment, consommation aliment, soins, mortalité, réforme-comptage).
- Convention appliquée partout : un champ à choix limité affiche ses options dans l'en-tête de colonne (`"Type * (Vaccin/Médicament/Autre)"`), les dates portent toutes `(AAAA-MM-JJ)`.

## Ce qui N'A PAS été vérifié / reste ouvert

- **Mobile** : aucun appareil connecté pendant cette session (`adb devices` vide) — le bouton Vente de fientes, le masquage des boutons Finance par `FarmAppSettings`, et tout le reste des changements mobile n'ont été vérifiés que par lecture de code + compilation, jamais visuellement sur device.
- **Import Excel web** : vérifié uniquement par `tsc --noEmit` (aucune erreur), jamais cliqué dans le navigateur — templates/parsing/import réel non testés de bout en bout.
- Petite incohérence cosmétique connue et non corrigée : sur le formulaire mobile générique réutilisé pour Vente de fientes, la case "commune" et les "projets concernés" restent visibles alors que `commun` est forcé à `true` quoi qu'il arrive.
- Test de bout en bout de `FarmAppSettings` (toggle web → effet réel sur QR généré / session déjà ouverte) non fait dans cette session.

## Prochaine phase — refonte des rôles (PLAN, PAS ENCORE IMPLÉMENTÉ)

Discuté le 2026-08-07, plan présenté et en attente de confirmation/correction de l'utilisateur avant tout début d'implémentation. Rien de cette section n'existe dans le code — c'est une note d'intention pour ne pas perdre le contexte.

### Nouveau modèle de rôles
Remplace PRODUCTEUR/FINANCIER par 5 rôles : **RESPONSABLE, COMPTABLE, VENTE, PRODUCTION, ADMIN** (SUPER_ADMIN inchangé). `roles` est une table (pas un enum Java) — migration à faible risque en base réelle (2 ADMIN, 1 PRODUCTEUR, 1 FINANCIER, 0 SUPER_ADMIN au moment du plan) :
- `PRODUCTEUR` renommé en place → `PRODUCTION`.
- `FINANCIER` renommé en place → `COMPTABLE` (garde son user lié), puis nouveaux rôles `RESPONSABLE`/`VENTE` créés et liés à ce même user (décision utilisateur : l'ex-FINANCIER garde tout son pouvoir actuel à la migration, l'admin retire ensuite ce qui ne convient pas via Modifier utilisateur).
- Toujours le principe : un cumul de rôles garde l'accès complet (`isPureX` partout, jamais une restriction sur un rôle non-exclusif).

### Projets — 3 champs responsables (sélections, plus de texte libre)
- `responsable` (actuellement texte libre) → FK vers un user RESPONSABLE. Nouveau pouvoir : gère/clôture ses projets, valide/rejette comptabilité ET ventes liées à ses projets (admin ou lui, jamais un simple COMPTABLE/VENTE).
- `responsableProduction` → reste, sourcé sur PRODUCTION (remplace PRODUCTEUR).
- `responsableFinance` → reste, sourcé sur COMPTABLE (remplace FINANCIER) — sert au filtre admin "voir comme un comptable" (comme aujourd'hui), COMPTABLE lui-même reste farm-wide/non scopé par défaut (à confirmer : son message original ne dit jamais "lié à ses projets" pour comptable, contrairement à responsable/vente/production où c'est systématique).

### Changement de comportement — validation des ventes
Aujourd'hui `VenteOeufsImpl`/`VenteReformeImpl.createFromSource` met TOUJOURS `VALIDE`, quel que soit le créateur — rien à valider actuellement. Pour que "VENTE crée mais ne valide jamais, RESPONSABLE valide" ait un sens, une vente créée par un VENTE pur doit passer par `EN_ATTENTE` (comme une transaction manuelle aujourd'hui pour un non-admin), validée par ADMIN ou le RESPONSABLE du projet concerné. Vente créée par ADMIN/RESPONSABLE lui-même reste auto-validée.

### Magasin de vente — nouvelle entité, vrai stock séparé (décision utilisateur confirmée)
- `MagasinVente` (nom, description, farm-scoped) + `MagasinTransfert` (transfert explicite d'un stock **projet → magasin**, quantité/date/type/qui) — **remplace la répartition automatique proportionnelle à la vente** (`VenteOeufsImpl.repartirEtCreerTransactions`) par une répartition explicite au moment du transfert. C'est la décision d'architecture la plus significative du plan (pas juste confirmée mot pour mot par l'utilisateur, à revalider en priorité si le test révèle un malentendu).
- `MagasinVente` ↔ Utilisateurs (VENTE) many-to-many : quel vendeur vend depuis quel(s) magasin(s).
- `VenteOeufs`/`VenteReforme`/vente-fientes gagnent une référence `magasin` ; le plafond de stock passe de farm-wide à par-magasin.
- Nouveaux champs de rapprochement : `montantTheorique` (quantité × prix, calcul auto) + `montantRapporte` (saisie : ce que le vendeur ramène réellement ce jour-là) + `SoldeVendeur` (solde cumulé créance/dette par vendeur, reporté d'une vente à l'autre, carte KPI) — libellés français proposés : "Montant théorique" / "Montant réellement rapporté" / "Solde du vendeur", à valider.

### Web
Nav/routes par rôle (`hasOnlyRole`/`isRestrictedTo`, pattern existant) ; Projets (3 selects) ; Comptabilité (RESPONSABLE = table complète scopée + valider/rejeter, COMPTABLE = farm-wide création seule, catégorie "Transport"→"Logistique") ; Ventes (VENTE scopé à ses magasins, RESPONSABLE scopé à ses projets pour validation, nouvelle saisie "Montant rapporté" + carte solde vendeur) ; Production (PRODUCTION scopé lecture/écriture comme PRODUCTEUR aujourd'hui, RESPONSABLE scopé lecture seule) ; Reporting scopé RESPONSABLE ; Paramètres (nouvelle section Magasins, CRUD + transferts).

### Mobile
- Nettoyage de l'affichage des rôles (2 badges + 2 dialogues dupliqués "Production/Finance/Administration" à remplacer par les 5 nouveaux libellés).
- `FarmAppSettings` se simplifie : les 5 toggles fins FINANCIER mobile deviennent inutiles (COMPTABLE mobile = entrée/sortie fixe, VENTE mobile = 3 types vente fixe) → toggles par rôle mobile/web seulement (`productionMobile/Web`, `comptableMobile/Web`, `venteMobile/Web`). RESPONSABLE : pas d'accès mobile du tout (jamais mentionné pour mobile).
- Bâtiment devient **obligatoire** (pas juste proposé) sur Achat aliment/Collecte œufs/Soins/Mortalité.
- Magasin devient **obligatoire** sur Vente œufs/Vente réforme (nouveau spinner, même pattern que bâtiment).
- Le mode hors-ligne existant ne doit pas casser (rappel explicite de l'utilisateur).

## Repères utiles pour une prochaine session

- Pattern de restriction par rôle réutilisé partout : "un cumul de rôles garde l'accès complet, seul un rôle PUR est restreint" — `hasOnlyRole`/`isRestrictedTo` (web `src/lib/roles.ts`), `isOnlyRole`/`isPureFinancier`/`isPureProducteur`/`isPureRole` (back, dupliqué par service : `TransactionServiceImpl`, `NotificationServiceImpl`, `AppAccessRules`).
- `./mvnw compile` peut afficher "Nothing to compile" de façon trompeuse si une compilation précédente dans le même tour a déjà tout recompilé — vérifier avec `stat -c '%y'` source vs `.class` en cas de doute.
- Le backend tourne en général avec **spring-boot-devtools** actif → recompiler (`./mvnw compile`) suffit à recharger le contexte dans le même process, pas besoin de relancer le JVM à la main pour la plupart des changements.
- Attention : une session Postgres/backend **réelle** de l'utilisateur (pas un environnement de test) a été détectée en cours de session — toujours vérifier avant toute opération destructive sur la base.
