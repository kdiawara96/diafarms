# Abonnement (gestion manuelle des abonnements fermes) — design

Date : 2026-08-25
Statut : approuvé par l'utilisateur en chat, en attente de revue du fichier écrit.

## Contexte et objectif

Diafarms est multi-fermes (une `Farm` par client). Il n'existe aujourd'hui aucun
mécanisme d'abonnement/facturation : une ferme s'inscrit librement via `/inscription`
(`UtilisateurImpl.save`) et utilise l'app sans limite de temps.

Objectif : ajouter un cycle d'abonnement **géré manuellement** (pas d'intégration de
paiement automatique pour cette V1) :
- Essai gratuit de 14 jours à l'inscription.
- Un seul forfait (pas de paliers de fonctionnalités), décliné mensuel/annuel.
- La ferme déclare elle-même avoir payé ("J'ai payé"), ce qui notifie le SUPER_ADMIN
  par email pour validation manuelle (paiement reçu hors-ligne : Mobile Money,
  virement, cash...).
- Après validation, la ferme reçoit un email de confirmation.
- 24h de grâce après échéance avant blocage.
- Le blocage ne s'applique **qu'au web** — le mobile continue de fonctionner même si
  la ferme est en retard (les employés terrain ne doivent pas être bloqués pour un
  retard de paiement de l'ADMIN).
- Le prix et les durées (essai, grâce) sont configurables par le SUPER_ADMIN depuis
  l'app, jamais codés en dur — le montant exact du forfait n'est pas encore arrêté au
  moment de ce design.

Explicitement écarté pour cette V1 : facturer par nombre de projets actifs (l'app
permet déjà à un seul projet de couvrir plusieurs poulaillers via
`OccupationBatiment`, donc facilement contournable) ou par cheptel vivant (peut venir
plus tard comme métrique de taille, hors scope ici) ; paliers de fonctionnalités par
plan ; intégration d'un agrégateur de paiement (CinetPay, PayDunya...) ; gestion de
l'abonnement côté mobile.

## Rôles impliqués

- **SUPER_ADMIN** : compte plateforme, sans ferme (voir `MlApplication`/
  `super-admin-seed.json`, déjà en place). Valide les paiements déclarés, édite la
  configuration tarifaire. Aucune notion d'abonnement ne s'applique à lui.
- **ADMIN** (par ferme) : voit le statut d'abonnement de sa ferme, déclare un paiement.

## Modèle de données (nouvelles tables)

### `AbonnementConfig` (une seule ligne, plateforme entière)

Réglages modifiables par le SUPER_ADMIN, jamais codés en dur :
- `prixMensuel` (Double, FCFA)
- `prixAnnuel` (Double, FCFA)
- `dureeEssaiJours` (Integer, défaut 14)
- `dureeGraceHeures` (Integer, défaut 24)

Lu/écrit via une seule ligne (créée avec des valeurs par défaut au premier accès si
absente — même idée que `FarmAppSettings` mais à l'échelle de la plateforme, pas
par ferme).

### `Abonnement` (une par `Farm`, relation `@OneToOne`)

État courant de l'abonnement d'une ferme :
- `uniqueId`
- `farm` (OneToOne)
- `statut` (enum `StatutAbonnement` : `ESSAI`, `ACTIF`, `EXPIRE`) — calculé/rafraîchi
  à la lecture à partir de `dateFin` + `dureeGraceHeures`, pas par un job planifié
  (voir "Calcul du statut effectif" plus bas). Le champ stocké sert de repli/traçage,
  la valeur qui compte est toujours recalculée.
- `dateDebut` (LocalDate) — date de création de l'abonnement (= inscription de la
  ferme pour le tout premier essai).
- `dateFin` (LocalDate) — échéance courante. Pour l'essai initial :
  `dateDebut + dureeEssaiJours`.
- `periodicite` (enum `Periodicite` : `MENSUEL`, `ANNUEL`, nullable tant qu'aucun
  paiement n'a jamais été validé — pendant l'essai).

Créé automatiquement dans `UtilisateurImpl.save()` juste après la création de la
`Farm`, avec `statut=ESSAI`, `dateDebut=aujourd'hui`, `dateFin=aujourd'hui +
config.dureeEssaiJours`.

### `PaiementAbonnement` (historique, plusieurs par `Farm`)

Même schéma que `Salaire`/`PaiementSalaire` déjà dans l'app (une entité "état
courant" + une entité "historique des mouvements") :
- `uniqueId`
- `abonnement` (ManyToOne vers `Abonnement`, donc vers la ferme)
- `montant` (Double)
- `periodicite` (`MENSUEL`/`ANNUEL` — celle que ce paiement couvre)
- `moyenPaiement` (String libre : "Orange Money", "Wave", "Virement", "Espèces"...)
- `reference` (String, optionnelle — référence de transaction Mobile Money par ex.)
- `statut` (enum `StatutPaiementAbonnement` : `EN_ATTENTE`, `VALIDE`, `REJETE` — même
  vocabulaire que `StatutTransaction` pour rester cohérent dans toute l'app)
- `dateDeclaration` (LocalDateTime — quand l'ADMIN a cliqué "J'ai payé")
- `declarePar` (ManyToOne `Utilisateurs`)
- `dateValidation` (LocalDateTime, nullable)
- `validePar` (ManyToOne `Utilisateurs`, nullable — le SUPER_ADMIN qui a traité)
- `motifRejet` (String, nullable — si `REJETE`)

## Calcul du statut effectif

Une méthode pure (pas de job planifié, pas de `@Scheduled`) calcule le statut
affiché/utilisé à chaque lecture, à partir de `Abonnement.dateFin` et
`AbonnementConfig.dureeGraceHeures`. `dateFin` est un `LocalDate` (la ferme reste
active toute la journée indiquée) ; l'instant de coupure exact est
`dateFin.plusDays(1).atStartOfDay()` (minuit au tout début du lendemain), auquel on
ajoute la grâce en heures pour obtenir l'instant limite réel :

```
instantLimite = dateFin.plusDays(1).atStartOfDay().plusHours(dureeGraceHeures)

effectif =
  si maintenant < dateFin.plusDays(1).atStartOfDay()  → ACTIF (ou ESSAI si periodicite == null)
  si dateFin.plusDays(1).atStartOfDay() <= maintenant < instantLimite → ACTIF, flag "en grâce" (bandeau d'alerte)
  sinon (maintenant >= instantLimite)                  → EXPIRE
```

Le champ `statut` stocké en base est mis à jour à chaque validation de paiement (pour
garder une trace), mais **jamais lu directement pour décider d'un blocage** — c'est
toujours le calcul ci-dessus, exécuté à la demande, qui fait foi. Ça évite tout job
CRON et toute désynchronisation entre "minuit passé" et "l'utilisateur regarde l'app".

## Workflow

1. **Inscription** (`UtilisateurImpl.save`) → `Abonnement` créé, `ESSAI`, échéance
   J+14.
2. **Pendant l'essai/l'abonnement actif** : l'app fonctionne normalement. Un bandeau
   informatif (pas bloquant) indique le nombre de jours restants une fois à 3 jours
   ou moins de l'échéance.
3. **Déclaration de paiement** (`POST /abonnements/declarer-paiement`, ADMIN de la
   ferme) : crée un `PaiementAbonnement` en `EN_ATTENTE`, envoie un email au(x)
   SUPER_ADMIN(s) (voir Emails).
4. **Validation** (`POST /abonnements/{uniqueId}/valider`, SUPER_ADMIN uniquement) :
   - `PaiementAbonnement.statut = VALIDE`, `dateValidation`, `validePar` renseignés.
   - `Abonnement.dateFin` avancée de 30 jours (`MENSUEL`) ou 365 jours (`ANNUEL`) à
     partir de **`max(dateFin actuelle, aujourd'hui)`** — pour ne pas perdre de jours
     déjà payés si la ferme renouvelle en avance, ni repartir dans le passé si elle a
     laissé expirer.
   - `Abonnement.periodicite` mise à jour avec celle du paiement.
   - `Abonnement.statut = ACTIF`.
   - Email de confirmation à l'ADMIN de la ferme (voir Emails).
5. **Rejet** (`POST /abonnements/{uniqueId}/rejeter`, motif) : `PaiementAbonnement.
   statut = REJETE` — aucun changement sur `Abonnement` (l'échéance reste ce qu'elle
   était). Pas d'email de rejet en V1 (l'ADMIN verra son paiement toujours marqué
   comme non validé sur sa page Abonnement).
6. **Expiration + grâce dépassée** : le web bloque (voir Enforcement). Le mobile ne
   change pas de comportement.

## API (backend)

Nouveau `AbonnementController` :
- `GET /abonnements/moi` — statut effectif de la ferme de l'utilisateur courant
  (ESSAI/ACTIF/EXPIRE, jours restants, échéance, dernier paiement en attente le cas
  échéant). Accessible à tout utilisateur d'une ferme.
- `POST /abonnements/declarer-paiement` — body `{ periodicite, montant, moyenPaiement,
  reference }`. Réservé ADMIN/RESPONSABLE de la ferme (mêmes rôles qui gèrent déjà les
  paramètres de la ferme).
- `GET /abonnements/config` — lecture publique aux utilisateurs connectés (pour
  afficher les prix courants sur le bouton "J'ai payé").
- `PUT /abonnements/config` — SUPER_ADMIN uniquement.
- `GET /abonnements/en-attente` — SUPER_ADMIN uniquement, liste tous les
  `PaiementAbonnement` en `EN_ATTENTE`, toutes fermes confondues, avec le nom de la
  ferme.
- `POST /abonnements/{uniqueId}/valider` — SUPER_ADMIN uniquement.
- `POST /abonnements/{uniqueId}/rejeter` — SUPER_ADMIN uniquement, body `{ motif }`.

Contrôle d'accès SUPER_ADMIN : mirroring de `ensureCanManage`-style checks déjà
présents ailleurs (ex: `MagasinServiceImpl`) — vérifier le rôle explicitement dans le
service, pas seulement côté route.

## Web

### Page "Abonnement" (ferme)

Nouvelle page (ex: `/abonnement`, visible à tous les rôles d'une ferme, mais bouton
"J'ai payé" réservé ADMIN/RESPONSABLE) :
- Statut courant, échéance, jours restants.
- Historique des `PaiementAbonnement` de la ferme (statut, date, montant).
- Formulaire "J'ai payé" : choix mensuel/annuel (montants lus depuis
  `GET /abonnements/config`), moyen de paiement, référence.

### Gate de blocage (web uniquement)

Dans `App.tsx` (ou un composant englobant type `AuthProvider`) : au chargement,
`GET /abonnements/moi`. Si `EXPIRE` (grâce dépassée) **et** l'utilisateur n'est pas
SUPER_ADMIN : un écran plein bloquant remplace tout le contenu applicatif, sauf
`/abonnement` elle-même et la déconnexion. Si `ESSAI`/`ACTIF` (y compris en grâce) :
l'app fonctionne normalement, avec juste le bandeau informatif si proche de
l'échéance.

### Portail SUPER_ADMIN (minimal)

Pas de nouveau layout : `HomeRoute` (`App.tsx`) gagne une branche
`if (hasOnlyRole(user?.roles, "SUPER_ADMIN")) return <SuperAdminAbonnements />;`
avant le repli sur `<Dashboard />` — réutilise `DashboardLayout` existant (déjà
tolérant à une ferme absente ailleurs dans l'app). Cette page liste les
`PaiementAbonnement` en attente (toutes fermes) avec Valider/Rejeter, et une section
réglages pour `AbonnementConfig`.

## Emails (`EmailServiceImpl`)

Deux nouvelles méthodes, même style que les 3 existantes (`MimeMessageHelper`,
texte+HTML) :
- `sendAbonnementAValider(to, farmNom, montant, periodicite, moyenPaiement,
  reference)` — envoyé à **tous** les utilisateurs `SUPER_ADMIN` (requête sur
  `UtilisateursRepo` filtrée par rôle) à chaque déclaration de paiement.
- `sendAbonnementValide(to, fullName, farmNom, dateFin)` — envoyé à l'ADMIN qui a
  déclaré le paiement (`PaiementAbonnement.declarePar`), après validation.

## Erreurs et cas limites

- Déclarer un paiement alors qu'un autre est déjà `EN_ATTENTE` pour la même ferme :
  refusé (`IllegalArgumentException`) — une seule déclaration en attente à la fois,
  évite les doublons visibles dans le portail SUPER_ADMIN.
- Valider/rejeter un paiement déjà traité (`VALIDE`/`REJETE`) : refusé.
- `Farm` sans `Abonnement` (toutes les fermes créées avant ce déploiement, y compris
  celle déjà en test sur le serveur) : à la première lecture de
  `GET /abonnements/moi`, créer l'`Abonnement` à la volée avec un essai complet de
  `dureeEssaiJours` **à partir d'aujourd'hui** (jamais rétroactif à leur vraie date
  d'inscription) — choix délibéré pour qu'aucune ferme existante ne se retrouve
  bloquée du jour au lendemain par la simple mise en service de cette fonctionnalité.
- SUPER_ADMIN qui n'a pas de ferme : `GET /abonnements/moi` renvoie un statut neutre
  (`null`/"non applicable"), jamais une erreur.

## Hors scope (explicitement, pour cette V1)

- Paliers de fonctionnalités par plan.
- Paiement automatique / intégration agrégateur.
- Abonnement/blocage côté mobile.
- Job planifié de changement de statut (calcul à la volée uniquement, voir plus haut).
- Métrique de taille (cheptel vivant) pour moduler le prix.
