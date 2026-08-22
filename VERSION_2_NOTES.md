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

### Mise à jour — web adapté (2026-08-08)

Le web est maintenant cohérent avec le nouveau modèle de rôles (commit `facf43f`, diafarms_back `97d1a6c`) : nav/routes/dashboards pour les 5 rôles, Projets avec 3 vraies sélections (Responsable/Production/Comptable) au lieu du texte libre, Comptabilité réécrite (tout le monde voit la table, COMPTABLE crée sans valider, RESPONSABLE valide sans créer, scopé à ses projets), Ventes (VENTE ne valide jamais), Production (RESPONSABLE lecture seule scopée), Paramètres (bascules simplifiées par rôle/plateforme). **C'est maintenant testable de bout en bout pour RESPONSABLE/COMPTABLE/VENTE/PRODUCTION** (contrairement à la phase précédente où seul le backend était prêt).

Corrigé au passage : `FarmAppSettingsDTO` côté web (interface TS dans `api.ts`) n'avait pas suivi le renommage du DTO backend — un vrai bug de désynchronisation, pas juste du cosmétique.

Ce qui n'est PAS encore fait (voir plan détaillé plus haut) :
- **Magasin de vente** (entité + transferts + stock par magasin) — le plus gros morceau restant, complexité financière réelle (calcul du stock "non encore transféré" vs "alloué à un magasin" vs "vendu"), à concevoir avec soin avant d'implémenter.
- Rapprochement montant théorique/rapporté + solde vendeur.
- Mobile : affichage des rôles simplifié, bâtiment/magasin obligatoires sur les formulaires concernés.

### Mise à jour — refonte des rôles terminée sur les 3 dépôts (2026-08-08)

**Backend** (`97d1a6c`, `04f0b15`) : migration des rôles en base faite (PRODUCTEUR→PRODUCTION, FINANCIER→COMPTABLE + nouveaux RESPONSABLE/VENTE liés aux mêmes users) ; `Projets` a ses 3 champs responsables en FK réelles ; `TransactionServiceImpl`/`NotificationServiceImpl` réécrits pour le nouveau modèle (RESPONSABLE valide/rejette transactions ET ventes de ses projets, scoping stats/liste/notifications) ; entités `MagasinVente`/`MagasinTransfert` + endpoints CRUD/stock ; `SoldeVendeur` (ledger delta appliqué à create/update/delete-toggle, basé sur le créateur d'origine).

**Décision d'architecture** (confirmée par l'utilisateur) : stock **transféré explicitement** projet→magasin (`MagasinTransfert`, cap = stock du projet non encore transféré) plutôt que réparti automatiquement à la vente comme avant — une vente ne fait plus que consommer le stock déjà présent dans SON magasin, la répartition entre projets contributeurs se fait au moment du transfert (réutilise `RepartitionUtil` inchangé, juste re-sourcé sur les transferts au lieu des ventes).

**Web** (`facf43f`, `ff2c5ac`) : nav/routes/dashboards 5 rôles, page `/magasins` standalone (ADMIN+RESPONSABLE, hors Paramètres car RESPONSABLE n'y a pas accès), dialogues Vente œufs/réforme avec magasin obligatoire + champ "Montant rapporté" + carte info, KPI Solde Vendeur sur `Ventes.tsx`, import Excel mis à jour (colonne Magasin).

**Mobile** (`2b3087b`) : `User.isFinance()` devient `isComptable() || isVente()` (deux rôles distincts, chacun un badge/libellé séparé) ; `FarmAppSettingsResponse` réaligné sur les 7 champs backend (régression silencieuse via Gson corrigée — les anciens noms de champs ne matchaient plus le JSON depuis le renommage backend, donc tous les boutons Finance étaient masqués à tort) ; magasin de vente obligatoire sur Vente œufs/réforme (nouveau spinner, stock plafonné par magasin via `/magasins/{id}/stock`, remplace l'ancien stock farm-wide) ; bâtiment obligatoire sur Collecte œufs/Soins/Mortalité/Achat aliment (fait dans une passe antérieure de cette même phase). Vérifié uniquement par lecture de code + comptage d'accolades/parenthèses — **aucun appareil ni build Gradle disponible dans cette session**, donc rien de tout ça n'a été testé visuellement sur mobile.

RESPONSABLE reste sans aucune présence mobile (jamais mentionné pour ce rôle dans la demande initiale) — cohérent avec `AppAccessRules` côté back qui n'a que `responsableWebEnabled`, pas de pendant mobile.

### Mise à jour — relecture finale + vraie vérification web (2026-08-08)

En reprenant le texte de la demande initiale point par point avant de donner le feu vert au test, deux choses trouvées et corrigées :

1. **Scoping manquant sur Reporting** (`226c5e2`, web) : les transactions y étaient déjà scopées côté serveur pour un RESPONSABLE, mais la production (œufs/aliment/mortalité) restait farm-wide — `Reporting.tsx` appelait `getSaisiesProductionAPI` sans filtre. Corrigé avec le même filtre client que `Production.tsx` (fetch large puis restriction aux projets renvoyés par `/projets/select`, déjà scopé serveur pour ce rôle).

2. **`tsc --noEmit` mentait depuis le début de cette phase** (`2d3dca2`, web) : la commande utilisée pour "vérifier" le web ciblait `tsconfig.json` (racine, `"files": []`, config solution avec `references`) qui ne type-check quasiment rien sans `--build` — sortie vide à tort interprétée comme "aucune erreur". La bonne commande est `npx tsc -p tsconfig.app.json --noEmit` (ou `tsc -b tsconfig.json`). En la lançant pour de vrai, ~10 erreurs réelles et déjà commitées sont apparues :
   - `Production.tsx`/`Ventes.tsx` : les 8 dialogues d'import Excel (Collecte œufs, Aliment achat/conso, Soins, Mortalité, Réforme, Vente œufs, Vente réforme) avaient un bug de rétrécissement de type dans `resolveProjet`/`resolveBatiment`/`resolveMagasin` — `return p;`/`return b;`/`return m;` sur la branche erreur ne s'excluait pas correctement de l'union de retour inférée des `parseXxxRow`. Corrigé en rendant le type de retour explicitement discriminé sur `error` (présent des deux côtés) et en reconstruisant un `{ error }` frais à chaque site d'appel plutôt que de renvoyer la variable telle quelle.
   - `mockData.ts` : 3 projets de démo + `defaultProjetInit()` utilisaient encore `responsable: string`, absent du type depuis la migration vers les FK.
   - `QrCodeDialog.tsx` (dialogue mort, jamais importé nulle part, mais toujours compilé) : `user.nom` au lieu de `user.fullName`.

   **Leçon retenue** (voir mémoire `tsc_diafarms_web_wrong_config` côté assistant) : dans ce dépôt, toujours vérifier avec `npx tsc -p tsconfig.app.json --noEmit`, jamais la forme nue `tsc --noEmit`. `npm run build` (`vite build`) ne type-check pas non plus par défaut (esbuild transpile sans vérifier) — ne pas s'y fier comme filet de sécurité.

Après ces deux corrections, backend/web/mobile sont propres (git status vide sur les 3 dépôts) et `tsc -p tsconfig.app.json --noEmit` passe réellement clean côté web. Le mobile reste non compilé faute d'appareil/Gradle disponible cette session (voir plus haut) — c'est le seul des 3 dépôts encore non vérifié par un vrai compilateur.

## Repères utiles pour une prochaine session

- Pattern de restriction par rôle réutilisé partout : "un cumul de rôles garde l'accès complet, seul un rôle PUR est restreint" — `hasOnlyRole`/`isRestrictedTo` (web `src/lib/roles.ts`), `isOnlyRole`/`isPureFinancier`/`isPureProducteur`/`isPureRole` (back, dupliqué par service : `TransactionServiceImpl`, `NotificationServiceImpl`, `AppAccessRules`).
- `./mvnw compile` peut afficher "Nothing to compile" de façon trompeuse si une compilation précédente dans le même tour a déjà tout recompilé — vérifier avec `stat -c '%y'` source vs `.class` en cas de doute.
- Le backend tourne en général avec **spring-boot-devtools** actif → recompiler (`./mvnw compile`) suffit à recharger le contexte dans le même process, pas besoin de relancer le JVM à la main pour la plupart des changements.

### Mise à jour — Bâtiment redevient poulailler-only, Magasin unifie vente+stockage (2026-08-10)

Demande utilisateur : le concept de `Batiment` ne doit plus couvrir que l'élevage
(poulailler) ; le "bâtiment de stockage" (introduit dans une phase précédente) est
fusionné dans la même table que `MagasinVente` plutôt que d'avoir une 3e table — un
seul concept `Magasin` avec `type` (`VENTE`/`STOCKAGE`). Logique métier inchangée :
collecte → magasin de stockage → transfert → magasin de vente → vente.

**Backend** (3 commits) :
- `MagasinVente` → renommé `Magasin` (classe Java + repo/service/DTO/controller, table
  reste `magasins_vente` en base pour éviter un rename risqué) ; ajout `type`
  (`Magasin.TypeMagasin`, colonne `type` avec CHECK constraint) et `seuilAlerteAlveoles`
  (déplacé depuis `Batiment`, pertinent seulement pour STOCKAGE). `MagasinRepo.list(type)`
  filtre optionnellement, `/magasins/list?type=STOCKAGE|VENTE`.
- `Batiment` perd `type`/`TypeBatiment` (POULAILLER/STOCKAGE/AUTRE) et
  `seuilAlerteAlveoles` — redevient poulailler-only, `BatimentRepo.findStockageActiveByFarmId`
  supprimé.
- `CollecteOeufs.batimentStockage` (FK Batiment) → `magasinStockage` (FK Magasin,
  colonne `magasin_stockage_id`) ; `MagasinTransfert.batimentStockage` → `magasinStockage`
  même principe. `MagasinTransfertServiceImpl`/`NotificationServiceImpl`
  (`addBatimentStockageAlerts` → `addMagasinStockageAlerts`) réécrits sur Magasin.
  Garde-fous ajoutés : une vente/un transfert vers un magasin exige `type=VENTE`, une
  collecte/un transfert depuis un magasin de stockage exige `type=STOCKAGE`.
- **Migration SQL manuelle** (colonnes déjà ajoutées par ddl-auto=update, migration de
  données faite à la main via psql, en transaction, vérifiée avant commit) : la seule
  vraie donnée `STOCKAGE` existante ("Mag Stockage A", batiments.id=3, créée lors du
  test de la phase précédente) migrée vers `magasins_vente` (nouveau id=2, type=STOCKAGE),
  les 2 `collectes_oeufs` qui la référençaient repointées sur `magasin_stockage_id`,
  l'ancien `batiments.id=3` soft-supprimé (`removed=true`, pas de hard delete — FK
  `collectes_oeufs.batiment_stockage_id`/`magasin_transferts.batiment_stockage_id`
  laissées orphelines mais intactes plutôt que droppées, aucune ligne de donnée perdue).

**Web** (2 commits) : `MagasinVenteDTO`/`Payload` → `MagasinDTO`/`MagasinPayload`
(+ `type`/`seuilAlerteAlveoles`) ; `Magasins.tsx` affiche deux sections (magasins de
vente / magasins de stockage) avec cartes différentes (stock+vendeurs pour VENTE,
disponible+seuil alvéoles pour STOCKAGE) ; `CreateMagasinDialog` a un sélecteur de type
avec champs conditionnels, type verrouillé après création. `CreateCollecteOeufsDialog`/
`CreateMagasinTransfertDialog` pointent sur `getMagasinsAPI("STOCKAGE")` au lieu de
`getSelectBatiment()` filtré côté client. `CreateBatimentDialog`/`EditBatimentDialog`
simplifiés (plus de sélecteur de type ni de seuil alvéoles, poulailler-only). Labels
"Bâtiment"/"Bâtiments" renommés "Poulailler"/"Poulaillers" dans le flux de création de
projet (`CreateProjectDialog`, `ProjetDetail.tsx`, import Excel `Projets.tsx`) et
Paramètres. `npx tsc -p tsconfig.app.json --noEmit` clean.

**Mobile** (1 commit) : `DataApi.getMagasinsSelect` prend maintenant un paramètre
`type` ; `MagasinSelectResponse` gagne `type`/`seuilAlerteAlveoles` ; le sélecteur
"bâtiment de stockage" de `SaisieFormActivity` (Collecte œufs) charge désormais
`GET /magasins/list?type=STOCKAGE` (déjà filtré serveur) au lieu de filtrer côté
client une liste de bâtiments — le champ `batimentsStockage` change de type
(`List<BatimentSelectResponse>` → `List<MagasinSelectResponse>`), noms de
méthodes/variables internes gardés tels quels par pragmatisme (renommage cosmétique
pur, aucun impact fonctionnel). `CollecteOeufsCreateRequest.batimentStockageUniqueId`
→ `magasinStockageUniqueId`. Labels "Bâtiment(s)" → "Poulailler(s)" sur l'accueil et le
formulaire de saisie. **Compilé pour de vrai cette fois** via
`./gradlew :app:compileDebugJavaWithJavac` (Gradle disponible dans cette session,
contrairement à la phase précédente) — 0 erreur.

**Choix d'ingénierie assumé** : `Batiment` n'a PAS été renommé en `Poulailler` au
niveau classe/table Java (contrairement à `MagasinVente`→`Magasin`) — blast radius
bien plus large (`OccupationBatiment`, dizaines de requêtes/DTO sur 3 dépôts) pour un
gain purement cosmétique. Le renommage `MagasinVente`→`Magasin` a été fait car plus
récent/petit ET sémantiquement nécessaire (une classe nommée "Vente" contenant des
lignes de type STOCKAGE aurait été trompeuse). Le résultat côté utilisateur est
identique : il ne voit plus jamais "Bâtiment" que pour un poulailler.
- Attention : une session Postgres/backend **réelle** de l'utilisateur (pas un environnement de test) a été détectée en cours de session — toujours vérifier avant toute opération destructive sur la base.

### Mise à jour — Clients, Commandes, Facturation, Salaires (2026-08-10 → 2026-08-12)

Feuille de route complète : `diafarms_back/ROADMAP_CLIENTS_COMMANDES_FACTURATION.md`
(checkboxes tenues à jour au fil de l'eau, décisions/choix par défaut documentés
dedans plutôt que redupliqués ici). **À tester par l'utilisateur avant tout autre
travail sur ces zones** — rien de ce qui suit n'a été testé en conditions réelles
(pas de serveur backend actif la majeure partie de cette session, aucun run
Android réel).

**Sections 1-2 — Client (fondation) + dette client (Option A)** : entité `Client`
(nom/téléphone/adresse/email, farm-scopée), rattachée en optionnel à
`VenteOeufs`/`VenteReforme`. Écart théorique/rapporté d'une vente routé vers
`SoldeClient` si un client est identifié, vers `SoldeVendeur` sinon (Option A du
roadmap, choisie explicitement par l'utilisateur — changement de comportement déjà
en prod sur `totalDuParVendeurs`). Remboursement de dette (`ClientServiceImpl.
payerDette`) génère une vraie `Transaction` + ajuste `SoldeClient`. Web : page
`/clients`, `ClientDetailDialog` (rapport + historique + paiement), cartes "Total dû
par les clients"/"Soldes clients" sur Comptabilité/Ventes/Reporting (liste pliable
"Voir plus", pas de navigation séparée — l'utilisateur a explicitement rejeté un
premier essai avec lien vers une autre page). Colonne Client sur les tableaux
Ventes/Comptabilité ("Inconnu" si vente sans client identifié).

**Section 3 — Commandes** : entité `Commande` (client **obligatoire**, contrairement
à une vente), cycle EN_ATTENTE→CONFIRMEE→CONVERTIE (crée la vraie vente via
`VenteOeufsService`/`VenteReformeService.create`, aucune duplication de la logique de
répartition entre projets) ou →ANNULEE. Point piège déjà géré : l'acompte devient
`montantRapporte` de la vente générée via un `nz()` explicite (jamais `null`), sinon
la convention "`null` = pas d'écart connu" masquerait à tort une vraie dette client
sur le reste non payé. Web : page `/commandes`, bouton "Convertir en vente".

**Section 4 — Facturation** : `Facture` (client obligatoire, numéro séquentiel
`FAC-{année}-{seq}`, **snapshot figé** au moment de l'émission — ne suit plus les
modifications ultérieures de la vente/commande d'origine), générée depuis UNE vente
ou UNE commande (jamais une ligne de `Ventes.tsx`/Comptabilité : une vente peut être
scindée en plusieurs `Transaction` par projet contributeur, la vraie source reste
accessible depuis la fiche client). PDF généré **côté backend** (nouvelle dépendance
`com.github.librepdf:openpdf`). "Marquer payée" réutilise `ClientService.payerDette`
(même Transaction + SoldeClient que le paiement de dette normal) plutôt que de
dupliquer la logique de règlement — risque assumé et documenté : rien n'empêche un
double comptage si quelqu'un enregistre le même paiement via les deux chemins
(fiche client ET facture) pour la même somme. Accès ADMIN/RESPONSABLE/COMPTABLE
(pas VENTE, décision du roadmap : "la facturation reste une action web
ADMIN/COMPTABLE").

**Section 5 — Salaires** : `Salaire` (base mensuelle par employé, upsert) +
`PaiementSalaire` (au plus un paiement par période "AAAA-MM", vérifié par
`existsBySalaire_IdAndPeriode`). "Payer" génère une transaction réelle via un
nouveau `TransactionService.createSortieCommune` (SORTIE, commun, sans projet ni
client — mirror de `createFromSource` qui lui est ENTREE-only et toujours lié à un
projet, donc pas réutilisable tel quel) + nouveau `SourceTransaction.SALAIRE`. Accès
ADMIN/RESPONSABLE/COMPTABLE.

**Piège récurrent évité sur TOUTES les nouvelles requêtes paginées** (`CommandeRepo`,
`FactureRepo`, `PaiementSalaireRepo`) : ne jamais mettre un `ORDER BY` explicite dans
le JPQL en même temps qu'un `Pageable` construit avec un `Sort` côté service — les
deux entrent en conflit. Toujours l'un OU l'autre (choix fait ici : `Sort` côté
service, JPQL sans `ORDER BY`, avec un commentaire dans chaque repo pour ne pas
réintroduire l'erreur).

**Section 6 (Utilisateurs — compléments) : PAS commencée.** Le roadmap lui-même
pose la question à trancher avec l'utilisateur ("quel manque précis a motivé cette
demande ?") — ne pas commencer sans réponse, plusieurs pistes possibles listées dans
le roadmap mais aucune confirmée.

**Mobile (2026-08-12)** — Client + Commande ajoutés côté VENTE uniquement (pas
COMPTABLE : `ensureCanManage` back n'autorise pas ce rôle pour Client/Commande ; pas
ADMIN/RESPONSABLE : aucune présence mobile pour ces rôles, caractéristique de toute
l'appli, pas propre à cette feature). Suit le patron existant à la lettre : écriture
toujours locale d'abord (`SaisieType.CLIENT_CREATE`/`COMMANDE_CREATE`, table
`saisies_locales`), synchronisée plus tard par `SyncManager`, jamais d'appel réseau
direct depuis le formulaire. **Limitation assumée et documentée en commentaire dans
le code** : le sélecteur client (vente optionnelle ou commande obligatoire) ne
propose QUE les clients déjà synchronisés côté serveur — `SyncManager` traite les
saisies une à la fois sans résolution de dépendances entre elles (et dans l'ordre
`created_at DESC`, donc le plus récent d'abord, ce qui aurait de toute façon traité
une commande AVANT le client qu'elle référence si la dépendance avait été autorisée).
Un client créé hors ligne redevient sélectionnable après sa synchronisation (cache
rafraîchi automatiquement après chaque sync, comme les projets/magasins).
Compilé/vérifié via `./gradlew compileDebugJavaWithJavac` + `processDebugResources`
(0 erreur) — **jamais lancé sur un appareil/émulateur réel cette session**.

**État de vérification à l'entrée de la prochaine session** : les 3 dépôts sont
`git status` propres sur `version_2`. Backend compile (`./mvnw clean compile`), web
type-check clean (`npx tsc -p tsconfig.app.json --noEmit`), mobile compile
(`./gradlew compileDebugJavaWithJavac`) — mais AUCUN des trois n'a été testé
fonctionnellement (pas de serveur dev lancé, pas de build web servi, pas d'APK
installé). L'utilisateur a dit vouloir tester après cette session : ne pas supposer
que quoi que ce soit fonctionne réellement avant qu'il ne confirme.

### Mise à jour — Unité de vente œufs sur Transfert/Commande + grille salariale (2026-08-12)

**Transfert de stock + Commande, quantité en œufs** : premier essai avec deux champs
simultanés additionnés (alvéoles + œufs, comme Collecte œufs) — **rejeté par
l'utilisateur**, qui voulait le toggle exclusif "Unité de vente" (Œuf OU Alvéole, un
seul champ dont l'unité change) déjà utilisé par Vente œufs, pas une saisie combinée.
Revenu en arrière sur les 3 fichiers concernés (`CreateMagasinTransfertDialog.tsx`,
`CreateCommandeDialog.tsx` web, `groupCommande` dans `SaisieFormActivity` mobile) pour
mirror exactement `CreateVenteOeufsDialog`/`groupVenteOeufs` (radio Œuf/Alvéole,
conversion vers œufs uniquement à l'envoi). **Leçon** : ne pas supposer qu'une
formulation ambiguë ("les deux alvéole et œuf") veut dire "champs combinés" — même si
un exemple chiffré était donné, vérifier plutôt que d'aligner sur un patron déjà
présent ailleurs dans l'app sans demander.

**Salaire — grille salariale (mensuel/journalier/horaire)** : `Salaire.montantMensuel`
→ `modePaiement` (MENSUEL/JOURNALIER/HORAIRE, `Salaire.ModePaiement` inner enum) +
`tauxBase` (sens dépendant du mode). `SalairePayerRequest` gagne `quantite` (nombre de
jours/heures travaillés, saisi à la main — pas de système de pointage dans Diafarms) ;
`SalaireServiceImpl.payer` calcule `tauxBase` directement en MENSUEL, `tauxBase ×
quantite` sinon, un `montant` explicite restant toujours prioritaire (prime/retenue
ponctuelle). `PaiementSalaire` garde `quantite` pour trace dans l'historique. Web :
`DefinirSalaireDialog` = éditeur de grille (mode + taux, libellé adapté),
`PayerSalaireDialog` ajoute le champ jours/heures uniquement si le mode n'est pas
MENSUEL, `Salaires.tsx` — ligne entière cliquable pour ouvrir le paiement (pas
seulement l'icône), colonne Mode ajoutée. Page/route restée nommée "Salaires" (choix
utilisateur explicite, pas "Personnel"). Mobile toujours hors périmètre pour Salaire
(décision déjà actée plus tôt cette session).

**Vérification** : backend `./mvnw clean compile` clean, web `tsc -p
tsconfig.app.json --noEmit` clean, mobile `compileDebugJavaWithJavac` +
`processDebugResources` clean pour la partie Transfert/Commande. Comme le reste,
**rien n'a été testé en conditions réelles**.

**Bug trouvé au premier vrai test (2026-08-12)** : `POST /salaires/definir` → 500.
Cause : renommer `Salaire.montantMensuel` → `tauxBase` fait ajouter `taux_base` par
`ddl-auto=update` mais ne DROP jamais l'ancienne colonne `montant_mensuel`, restée
`NOT NULL` sans défaut et orpheline (plus mappée par l'entité) → toute insertion
viole la contrainte NOT NULL (exception non catchée par `IllegalArgumentException`,
d'où 500 et pas 400). Table `salaires` vérifiée vide (0 ligne) avant correctif :
colonne droppée directement via psql (`ALTER TABLE salaires DROP COLUMN
montant_mensuel;`), aucune perte de données, aucun changement de code nécessaire.
**Leçon à généraliser** : `ddl-auto=update` n'ajoute que des colonnes, ne renomme et
ne supprime jamais rien — tout renommage de colonne (pas seulement les nouvelles
colonnes NOT NULL, leçon déjà connue) doit être suivi d'une vérification manuelle
(`\d table` via psql) que l'ancienne colonne n'est pas restée orpheline avec une
contrainte NOT NULL, surtout un jour après avoir livré la fonctionnalité sans
serveur backend actif pour le remarquer immédiatement.

**Deuxième bug trouvé juste après (2026-08-12)** : `POST /salaires/payer` → 500 aussi.
Cause différente mais même famille : `SourceTransaction.SALAIRE` (nouvelle valeur
ajoutée à un enum EXISTANT, pas une nouvelle colonne) — `ddl-auto=update` ne touche
jamais les CHECK constraints déjà en place, donc `transactions_source_type_check`
restait figé sur `('MANUEL','VENTE_OEUFS','VENTE_REFORME')` et rejetait toute
Transaction générée par un paiement de salaire. Corrigé via psql (`ALTER TABLE
transactions DROP CONSTRAINT ... ; ALTER TABLE transactions ADD CONSTRAINT ...`
avec les 4 valeurs), testé par un insert/delete manuel avant de confirmer. Vérifié
par la même occasion que les CHECK constraints de `commandes`/`factures`/`salaires`
(tables entièrement nouvelles cette session, jamais un enum étendu après coup) sont
déjà correctes — seul un enum qui existait AVANT cette session et qu'on étend avec
une nouvelle valeur (comme `SourceTransaction`) est à risque.
**Leçon généralisée** (voir aussi mémoire assistant `ddl_auto_update_column_rename_orphan`,
à renommer mentalement en "ddl-auto=update ne modifie jamais un CHECK/une colonne
existante") : après avoir ajouté une valeur à un `@Enumerated(EnumType.STRING)` déjà
utilisé par des lignes existantes, toujours vérifier le CHECK constraint réel en
base (`SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid =
'<table>'::regclass AND contype = 'c';`), pas seulement le code Java.

### Mise à jour — Personnel, identité de la ferme, UX Salaires (2026-08-12)

**Personnel** (nouvelle entité) : `Salaire.employe` pointait vers `Utilisateurs`
(comptes de connexion) — un gardien ou tout employé n'ayant jamais besoin de se
connecter ne pouvait donc pas être payé via le système. Ajout de `Personnel` (nom,
poste, téléphone, lien optionnel vers un compte `Utilisateurs` si la personne en a
un), et retarget de `Salaire.employe` vers cette entité. Migration effectuée en
direct sur la base de dev pendant que le serveur tournait (voir mémoire assistant
`ddl_auto_update_column_rename_orphan`, point 3 : retargeter un `@ManyToOne` ne fait
JAMAIS bouger la FK existante ni migrer les données — vérifié et corrigé à la main,
transaction validée avant commit). Web : `DefinirSalaireDialog` sélectionne
maintenant un Personnel (avec "+ Personnel" pour en créer un à la volée) au lieu
d'un Utilisateurs.

**Identité de la ferme (logo/tampon)** : `Farm.logoNomMinio`/`tamponNomMinio`
(nullable, ajout pur donc sans risque). Réutilise `MinioService` existant (déjà
utilisé pour les fichiers projet), pas de nouvelle infra d'upload.
`FarmController` étendu — upload/retrait réservés à ADMIN/SUPER_ADMIN. Insérés
automatiquement par `FactureServiceImpl.genererPdf` (déjà existant) et le nouveau
`SalaireServiceImpl.genererBulletinPdf` (**bulletin de salaire PDF**, nouveau
document, `GET /salaires/paiements/{uniqueId}/pdf`) — laissés simplement vides si la
ferme n'en a pas fourni, jamais de placeholder. Web : section "Identité de la ferme"
dans Paramètres (upload/aperçu/retrait), bouton de téléchargement du bulletin dans
l'historique des paiements.

**UX Salaires** (retours utilisateur après premier vrai test) : période saisie via
deux listes déroulantes (mois en toutes lettres + année) au lieu d'un
`<input type="month">` brut ; historique affiche "Août 2026" au lieu de "2026-08" et
la quantité avec son unité ("22 jour(s)"/"8 heure(s)") au lieu d'un nombre nu. Le
refus d'un double paiement sur la même période était déjà géré côté back
(confirmé, pas un bug).

**Menu latéral regroupé par section** (Production / Ventes / Finance /
Administration, Tableau de bord seul en tête) — purement visuel, `DashboardLayout`
seulement, aucun changement de permissions.

**Correction faite puis défaite dans la même session** : un premier essai avait mis
la quantité œufs de Transfert/Commande en double champ combiné (alvéoles + œufs
additionnés, comme Collecte) — l'utilisateur voulait en fait le toggle exclusif
"Unité de vente" (Œuf OU Alvéole) déjà utilisé par Vente œufs. Revenu en arrière sur
les 3 fichiers concernés avant de passer à autre chose. **Leçon** : une formulation
ambiguë avec un exemple chiffré ("les deux alvéole et œuf, exemple 10 alvéole et 20
œufs") ne suffit pas à conclure "champs combinés" avec certitude quand un patron
différent existe déjà ailleurs dans l'app pour un besoin très proche — vérifier
plutôt que d'aligner sur la première lecture plausible.

**Vérification** : backend `./mvnw clean compile` clean à chaque étape (serveur de
dev réel, `com.diafarms.ml.MlApplication`, tournait déjà sur le port 9093 pendant
toute cette session — devtools recharge automatiquement à la compilation, migration
DB faite en direct entre deux compilations). Web `tsc -p tsconfig.app.json --noEmit`
clean. **Cette fois, une partie a été testée en conditions réelles par
l'utilisateur** (c'est comme ça que les deux bugs 500 de la mise à jour précédente
ont été trouvés) — mais le logo/tampon/bulletin/Personnel de cette mise à jour n'ont
pas encore été testés par l'utilisateur au moment d'écrire cette note.

### Mise à jour — Historique des taux de salaire (2026-08-12, même session)

**Problème réel signalé par l'utilisateur** (pas un bug trouvé en testant, une
question posée directement) : un salaire passe de 75000 à 100000 FCFA, mais un mois
où l'employé était encore à 75000 n'a jamais été payé — le comptable veut le payer
maintenant, après le changement. Sans historique, `payer()` proposait par défaut le
taux ACTUEL de la grille (100000) pour ce mois passé, alors que 75000 était le bon
montant. Le champ montant restait modifiable à la main donc ce n'était pas cassé,
mais rien n'avertissait le comptable ni ne calculait le bon montant automatiquement.

**Choix tranché avec l'utilisateur** : historiser correctement plutôt qu'un simple
avertissement. Nouvelle entité `SalaireHistorique` (mode/taux/dateEffective/dateFin,
dateFin null = taux courant) — `SalaireServiceImpl.definir()` ferme l'enregistrement
actif et en ouvre un nouveau à chaque changement RÉEL de mode/taux (pas de doublon si
on re-sauvegarde la même valeur). `payer()` résout maintenant le taux réellement en
vigueur pour la PÉRIODE choisie (`resolveTauxPourPeriode` : dernier historique dont
`dateEffective` ne dépasse pas la fin du mois demandé), pas le taux courant — un
montant explicite reste toujours prioritaire (prime/retenue ponctuelle inchangée).

**Bulletin de paie aussi corrigé par la même occasion** : il lisait `Salaire.
tauxBase` (taux ACTUEL) au lieu du taux réellement payé ce jour-là — même classe de
bug. `PaiementSalaire` gagne `modePaiementApplique`/`tauxApplique`, renseignés à
chaque paiement avec le taux résolu pour la période, lus par le PDF en priorité
(repli sur le taux actuel de la grille uniquement pour un paiement antérieur à cette
fonctionnalité, jamais migré).

Nouvel endpoint `GET /salaires/{employeUniqueId}/taux?periode=AAAA-MM` — le web
(`PayerSalaireDialog`) le requête à chaque changement de mois/année et affiche un
avertissement explicite quand le taux de la période diffère du taux actuel de la
grille, avec le bon montant déjà pré-rempli.

**Migration** effectuée en direct sur la base de dev (serveur toujours en cours,
recompilation → devtools recharge → nouvelle table/colonnes ajoutées par
ddl-auto=update, purement additif donc sans risque) : un `SalaireHistorique` de
départ créé pour chacun des 2 `Salaire` existants (dateEffective = aujourd'hui), et
`modePaiementApplique`/`tauxApplique` rétro-remplis sur l'unique `PaiementSalaire`
déjà enregistré à partir du taux de son `Salaire` — vérifié avant commit, comme les
migrations précédentes de cette session.

### Mise à jour — Rebrand Cocorico + corrections mobile en profondeur (2026-08-16)

**Rebrand visuel** : nouveau logo/identité "Cocorico" adopté sur web (en-tête,
connexion, favicon, titres de page) et mobile (icône d'appli — jusque-là encore
l'icône par défaut Android jamais personnalisée, nom affiché, écran de connexion),
et pied de page des PDF générés (factures/bulletins). Noms techniques internes
(packages Java, dossiers de projet, base de données) volontairement inchangés —
décision explicite avec l'utilisateur, trop risqué de renommer l'`applicationId`
Android (casse l'identité de l'appli déjà installée) pour un gain invisible.

**Bug réel trouvé en creusant "le projet ne vient pas" sur mobile** — voir mémoire
persistante `jpql_implicit_join_or_nullable_assoc.md`. `ProjetsRepo.
findAssignedToUser`/`findRecentAssignedToUser` combinaient en OR plusieurs
associations nullable (`responsableProduction`/`responsableFinance`/`responsable`)
via navigation implicite JPQL — qui génère un INNER JOIN par défaut. Dès qu'UNE des
trois est null pour un projet (cas courant : "responsable" générique jamais
renseigné), Hibernate éliminait le projet ENTIER de la requête, même si les deux
autres associations matchaient. Confirmé en comparant une requête native (LEFT JOIN,
1 résultat) à la JPQL équivalente (implicite, 0 résultat) sur les mêmes données.
Corrigé avec des `LEFT JOIN` explicites sur les deux requêtes. Un audit du reste du
repository layer (122 `@Query`) n'a trouvé aucune autre occurrence du même piège.

**Correctifs mobile additionnels (même session)** :
- Rotation verrouillée en portrait partout (`fullSensor` → `portrait` dans le
  manifest, jamais configuré jusque-là).
- Badge de rôles dans l'en-tête : 3 badges séparés qui débordaient dès 3 rôles
  cumulés → un seul badge compact ("X · Y" ou "X +N").
- Sections Comptable/Vente séparées sur l'accueil (titre + grille chacune) au lieu
  d'un seul "Saisie comptable" mélangeant les deux rôles.
- Accès mobile par rôle (Production/Comptable/Vente) : Production n'était JAMAIS
  vérifié contre `productionMobileEnabled` — un compte multi-rôles pouvait se
  connecter grâce à un autre rôle actif et voyait quand même les cartes Production
  même désactivées par l'admin. Corrigé + mis en cache local (cache-first) pour que
  le menu reste utilisable hors ligne au lieu de rester vide indéfiniment tant que
  `/farm-settings` n'a pas répondu — la vraie cause du "aucun menu hors ligne"
  signalé par l'utilisateur.
- "Synchroniser" rafraîchit maintenant aussi les accès mobile, pas seulement les
  projets/magasins/clients/salaires.
- Vente œufs/réforme/fientes sur mobile : champ "Poulailler" retiré (n'a jamais
  servi, ventes farm-scopées), "Montant rapporté" ajouté (mobile n'avait qu'un seul
  champ montant, impossible d'enregistrer une vente à crédit depuis le terrain).
- Client : téléphone obligatoire + unique par ferme, web et mobile, avec contrainte
  DB (`ALTER TABLE ... SET NOT NULL` + `UNIQUE (farm_id, telephone)` — ddl-auto=
  update n'altère jamais la nullabilité d'une colonne existante, migration manuelle
  comme d'habitude).
- Nouveau : paiement de salaire depuis mobile (rôle Comptable, paiement seulement,
  pas de gestion de grille) — `GET /salaires/select` ajouté pour synchroniser la
  grille salariale hors ligne.

**MinIO** tournait en fait dans un conteneur Docker jamais réellement lancé sur
cette machine de dev (Docker pas installé) — cause des 500 sur l'upload logo/tampon.
Remplacé par un MinIO autonome (binaire direct, mêmes identifiants `.env`) : à
relancer manuellement après un redémarrage de la machine tant que ce n'est pas
transformé en vrai service système.
