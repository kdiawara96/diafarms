#!/usr/bin/env bash
# Task 10 — Circuit de l'argent client : script de bout en bout.
#
# Consomme l'API des Tasks 4-9 (commandes, paiements/remboursements client,
# ventes d'œufs, factures) contre un backend DÉJÀ EN MARCHE sur $BASE (voir
# ci-dessous), et vérifie les 13 scénarios de
# docs/superpowers/specs/2026-09-23-circuit-argent-client-design.md.
#
# Pré-requis (non gérés par ce script) : Postgres + le jar backend démarrés,
# .env pointant sur une base de test, un super-admin seedé (voir
# .superpowers/sdd/2026-09-23-circuit-argent-client/task-10-brief.md, Step 1).
#
# Le script CRÉE lui-même (idempotent : réutilise ce qui existe déjà) : une
# ferme + un ADMIN + un COMPTABLE + un VENTE, une race/un poulailler/un projet,
# deux magasins (Stock, Boutique) et du stock d'œufs dans Boutique. Il est donc
# rejouable contre la même base sans tout recréer, et fonctionne aussi bien
# contre une base fraîche.
#
# Variables d'environnement :
#   BASE        URL de base de l'API (défaut http://localhost:9199/diafarms/api/v1)
#   PGHOST      hôte/dossier socket Postgres pour la promotion ADMIN par SQL
#               (défaut : vide -> psql utilise son socket par défaut)
#   PGPORT      port Postgres (défaut 55432)
#   PGUSER      utilisateur Postgres (défaut postgres)
#   PGDATABASE  base Postgres (défaut diafarms_test)
#   SUPERADMIN_ID / SUPERADMIN_PWD  identifiants du super-admin déjà seedé
#               (défaut superadmin / change-me)
#
# Sortie : une ligne "OK"/"ECHEC" par assertion ; code de sortie 0 si tout est
# OK, 1 sinon.

set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_test}"
SUPERADMIN_ID="${SUPERADMIN_ID:-superadmin}"
SUPERADMIN_PWD="${SUPERADMIN_PWD:-change-me}"

ADMIN_EMAIL="admin@t.local"
ADMIN_PWD="Test1234!"
COMPTA_EMAIL="compta@t.local"
VENTE_EMAIL="vente@t.local"

FAILURES=0

# ---------------------------------------------------------------------------
# Aides génériques
# ---------------------------------------------------------------------------

psql_run() { # $1 = requête SQL (une ligne)
  local args=(-p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE")
  [ -n "${PGHOST:-}" ] && args=(-h "$PGHOST" "${args[@]}")
  psql "${args[@]}" -v ON_ERROR_STOP=1 -Atc "$1"
}

# Extrait un champ d'un JSON par chemin pointé (ex: "data.compte.totalVendu",
# "data.data.0.livraisons.1.statutPaiement"). Imprime "" si absent.
jpath() {
  python3 - "$1" "$2" <<'PY'
import json, sys
d = json.loads(sys.argv[1])
cur = d
for p in sys.argv[2].split('.'):
    if cur is None:
        break
    if isinstance(cur, list):
        try:
            cur = cur[int(p)]
        except (ValueError, IndexError):
            cur = None
    elif isinstance(cur, dict):
        cur = cur.get(p)
    else:
        cur = None
print(cur if cur is not None else "")
PY
}

# Appel HTTP authentifié. Positionne les globales BODY et HTTP_STATUS —
# appeler directement (jamais via $(...), qui perdrait les globales dans une
# sous-coquille).
call() { # $1=METHOD $2=PATH $3=JSON(optionnel)
  local method="$1" path="$2" data="${3:-}" out
  if [ -n "$data" ]; then
    out=$(curl -sS -w $'\n%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$data")
  else
    out=$(curl -sS -w $'\n%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $TOKEN")
  fi
  HTTP_STATUS="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

login() { # $1=identifiant $2=password -> positionne TOKEN
  local resp
  resp=$(curl -sS -X POST "$BASE/auth" -H "X-Client-Type: mobile" \
    --data-urlencode "grantType=password" \
    --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2")
  TOKEN=$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])
data = d.get('data') or {}
print(data.get('accessToken') or '')
" "$resp")
}

# Lit /clients/{uid}/compte et positionne COMPTE (JSON du sous-objet "compte").
compte() { # $1 = clientUid
  call GET "/clients/$1/compte"
  COMPTE=$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])
print(json.dumps((d.get('data') or {}).get('compte') or {}))
" "$BODY")
}

champ() { # $1=json-objet $2=cle -> valeur (ou vide)
  python3 -c "
import json, sys
d = json.loads(sys.argv[1])
v = d.get(sys.argv[2])
print(v if v is not None else '')
" "$1" "$2"
}

verifier() { # $1=libelle $2=attendu $3=obtenu
  local libelle="$1" attendu="$2" obtenu="$3" resultat
  resultat=$(python3 -c "
import sys
a, b = sys.argv[1], sys.argv[2]
try:
    print('OK' if abs(float(a) - float(b)) < 0.01 else 'ECHEC')
except ValueError:
    print('OK' if a == b else 'ECHEC')
" "$attendu" "$obtenu")
  if [ "$resultat" = "OK" ]; then
    echo "OK    $libelle (attendu=$attendu, obtenu=$obtenu)"
  else
    echo "ECHEC $libelle : attendu=$attendu, obtenu=$obtenu"
    FAILURES=$((FAILURES + 1))
  fi
}

# Les deux invariants comptables du Step 2 du brief, appliqués à $1 = JSON du
# compte (voir compte() ci-dessus).
invariants() { # $1=JSON-compte $2=libelle
  local json="$1" label="$2" out rc
  out=$(python3 - "$json" <<'PY'
import json, sys
c = json.loads(sys.argv[1])
ok1 = abs((c["totalPaye"] - c["totalRembourse"]) - (c["totalImputeVentes"] + c["avance"])) < 0.01
ok2 = abs(c["totalVendu"] - (c["totalImputeVentes"] + c["resteAPayer"])) < 0.01
print("OK" if ok1 and ok2 else "ECHEC invariants", c)
sys.exit(0 if ok1 and ok2 else 1)
PY
)
  rc=$?
  if [ $rc -eq 0 ]; then
    echo "OK    invariants $label ($out)"
  else
    echo "ECHEC invariants $label : $out"
    FAILURES=$((FAILURES + 1))
  fi
}

# Compteur global de téléphone : le téléphone est obligatoire ET unique par
# ferme côté ClientServiceImpl.create (contrairement au commentaire "optionnel"
# de ClientCreate.java, qui ne reflète pas la validation réelle du service).
# Positionne CLIENT_UID (globale) — appeler directement, jamais via $(...), le
# compteur ne devrait pas être perdu dans une sous-coquille.
CLIENT_TEL_SEQ=80000000
nouveau_client() { # $1=nom -> positionne CLIENT_UID
  CLIENT_TEL_SEQ=$((CLIENT_TEL_SEQ + 1))
  call POST /clients/create "{\"nom\":\"$1\",\"telephone\":\"$CLIENT_TEL_SEQ\"}"
  CLIENT_UID=$(jpath "$BODY" "data.uniqueId")
  if [ -z "$CLIENT_UID" ]; then
    echo "ECHEC : création du client '$1' a échoué ($HTTP_STATUS) : $BODY"
    FAILURES=$((FAILURES + 1))
  fi
}

echo "== Base API : $BASE =="

# ---------------------------------------------------------------------------
# Étape 1 : environnement (ferme, comptes, race/poulailler/projet, magasins,
# stock d'œufs) — idempotent.
# ---------------------------------------------------------------------------

echo "-- Seeding --"

login "$ADMIN_EMAIL" "$ADMIN_PWD"
if [ -n "$TOKEN" ] && [ "$TOKEN" != "None" ]; then
  echo "Admin déjà existant ($ADMIN_EMAIL) — réutilisation."
else
  echo "Création de la ferme et de l'ADMIN..."
  login "$SUPERADMIN_ID" "$SUPERADMIN_PWD"
  if [ -z "$TOKEN" ] || [ "$TOKEN" = "None" ]; then
    echo "ECHEC : impossible de se connecter en super-admin ($SUPERADMIN_ID). Abandon."
    exit 1
  fi
  # fullName SANS ESPACE : /users/create plante (StringIndexOutOfBoundsException
  # dans UtilisateurImpl.generateUsername, ligne ~169 — voir rapport) dès que le
  # nom complet contient un espace. Bug pré-existant, hors périmètre de cette
  # tâche : contourné ici plutôt que corrigé.
  call POST /users/create "{\"fullName\":\"AdminScen\",\"email\":\"$ADMIN_EMAIL\",\"telephone\":\"70000001\",\"farmName\":\"FermeScenariosCircuitClient\",\"roles\":[\"COMPTABLE\"]}"
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "201" ]; then
    echo "ECHEC : création de l'ADMIN a échoué ($HTTP_STATUS) : $BODY"
    exit 1
  fi
  # Promotion COMPTABLE -> ADMIN (créer directement un ADMIN via /users/create
  # renvoie 500 — recette connue) puis mot de passe connu.
  psql_run "UPDATE roles_users SET id_roles=(select id from roles where role='ADMIN') WHERE id_utilisateurs=(select id from utilisateurs where email='$ADMIN_EMAIL')" >/dev/null
  HASH=$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'$ADMIN_PWD', bcrypt.gensalt(10)).decode())")
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$ADMIN_EMAIL'" >/dev/null
  login "$ADMIN_EMAIL" "$ADMIN_PWD"
  if [ -z "$TOKEN" ] || [ "$TOKEN" = "None" ]; then
    echo "ECHEC : connexion ADMIN après promotion a échoué. Abandon."
    exit 1
  fi

  call PUT /farm-settings '{"productionMobileEnabled":true,"productionWebEnabled":true,"comptableMobileEnabled":true,"comptableWebEnabled":true,"venteMobileEnabled":true,"venteWebEnabled":true,"responsableWebEnabled":true}'

  call POST /users/create-pro-or-finance "{\"fullName\":\"Compta Scen\",\"email\":\"$COMPTA_EMAIL\",\"telephone\":\"70000002\",\"roles\":[\"COMPTABLE\"]}"
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$COMPTA_EMAIL'" >/dev/null

  call POST /users/create-pro-or-finance "{\"fullName\":\"Vente Scen\",\"email\":\"$VENTE_EMAIL\",\"telephone\":\"70000003\",\"roles\":[\"VENTE\"]}"
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$VENTE_EMAIL'" >/dev/null

  login "$ADMIN_EMAIL" "$ADMIN_PWD"
fi

# Id numérique de l'ADMIN (ProjetCreate.responsableId) : lu directement en
# base plutôt que décodé du JWT, plus simple et fiable.
ADMIN_ID=$(psql_run "select id from utilisateurs where email='$ADMIN_EMAIL'")
if [ -z "$ADMIN_ID" ]; then
  echo "ECHEC : impossible de retrouver l'id de l'ADMIN en base. Abandon."
  exit 1
fi

# Race
call GET "/races/list"
RACE_ID=$(jpath "$BODY" "data.0.id")
FOUND_RACE=""
if [ -n "$RACE_ID" ]; then
  FOUND_RACE=$(python3 -c "
import json, sys
items = (json.loads(sys.argv[1]).get('data') or [])
for r in items:
    if r.get('nom') == 'Pondeuse Scen':
        print(r['id']); break
" "$BODY")
fi
if [ -n "$FOUND_RACE" ]; then
  RACE_ID="$FOUND_RACE"
  echo "Race déjà existante (id=$RACE_ID) — réutilisation."
else
  call POST /races/create '{"nom":"Pondeuse Scen","type":"PONDEUSE","origine":"Locale","description":"Race de test scenarios","esperanceVieAnnees":3,"poidsAdulteKg":2.0,"productionOeufsAn":280,"couleurOeuf":"BRUN"}'
  RACE_ID=$(jpath "$BODY" "data.id")
  psql_run "UPDATE races SET temps_croissance='MOYEN', rusticite='MOYENNE', adaptation_climat='CHAUD_SEC', certification_race='AUCUNE', poids_abattage='NON_APPLICABLE' WHERE id=$RACE_ID" >/dev/null
  echo "Race créée (id=$RACE_ID)."
fi

# Bâtiment (endpoint non paginé : "data" est directement la liste)
call GET "/batiments/list"
BATIMENT_ID=$(python3 -c "
import json, sys
items = json.loads(sys.argv[1]).get('data') or []
for b in items:
    if b.get('nom') == 'Poulailler Scen':
        print(b['id']); break
" "$BODY")
if [ -n "$BATIMENT_ID" ]; then
  echo "Bâtiment déjà existant (id=$BATIMENT_ID) — réutilisation."
else
  call POST /batiments/create '{"nom":"Poulailler Scen","capacite":6000}'
  BATIMENT_ID=$(jpath "$BODY" "data.id")
  BATIMENT_UID=$(jpath "$BODY" "data.uniqueId")
  echo "Bâtiment créé (id=$BATIMENT_ID)."
fi
[ -z "${BATIMENT_UID:-}" ] && BATIMENT_UID=$(psql_run "select unique_id from batiments where id=$BATIMENT_ID")

# Projet
call GET "/projets/list?page=0&size=50"
PROJET_UID=$(python3 -c "
import json, sys
items = (json.loads(sys.argv[1]).get('data') or {}).get('data') or []
for p in items:
    if p.get('titre') == 'Projet Scenarios Circuit Client':
        print(p['uniqueId']); break
" "$BODY")
if [ -n "$PROJET_UID" ]; then
  echo "Projet déjà existant ($PROJET_UID) — réutilisation."
else
  call POST /projets/create "{\"titre\":\"Projet Scenarios Circuit Client\",\"responsableId\":$ADMIN_ID,\"dateDebut\":\"2026-01-01\",\"dateFinPrevue\":\"2027-12-31\",\"nbSujets\":5000,\"puSujet\":0,\"objectif\":\"PONTE\",\"raceId\":$RACE_ID,\"occupations\":[{\"batimentId\":$BATIMENT_ID,\"dateEntree\":\"2026-01-01\",\"nbSujets\":5000}]}"
  PROJET_UID=$(jpath "$BODY" "data.uniqueId")
  if [ -z "$PROJET_UID" ]; then
    echo "ECHEC : création du projet a échoué ($HTTP_STATUS) : $BODY"
    exit 1
  fi
  echo "Projet créé ($PROJET_UID)."
fi

# Magasins
call GET "/magasins/list"
STOCK_UID=$(python3 -c "
import json, sys
items = json.loads(sys.argv[1]).get('data') or []
for m in items:
    if m.get('nom') == 'Stock Scen':
        print(m['uniqueId']); break
" "$BODY")
BOUTIQUE_UID=$(python3 -c "
import json, sys
items = json.loads(sys.argv[1]).get('data') or []
for m in items:
    if m.get('nom') == 'Boutique Scen':
        print(m['uniqueId']); break
" "$BODY")
if [ -z "$STOCK_UID" ]; then
  call POST /magasins/create '{"nom":"Stock Scen","type":"STOCKAGE"}'
  STOCK_UID=$(jpath "$BODY" "data.uniqueId")
  echo "Magasin de stockage créé ($STOCK_UID)."
else
  echo "Magasin de stockage déjà existant ($STOCK_UID) — réutilisation."
fi
if [ -z "$BOUTIQUE_UID" ]; then
  call POST /magasins/create '{"nom":"Boutique Scen","type":"VENTE"}'
  BOUTIQUE_UID=$(jpath "$BODY" "data.uniqueId")
  echo "Magasin de vente créé ($BOUTIQUE_UID)."
else
  echo "Magasin de vente déjà existant ($BOUTIQUE_UID) — réutilisation."
fi

# Stock d'œufs dans Boutique : les scénarios consomment au total ~360 œufs ;
# on vise une marge confortable (>= 500) et on complète si besoin.
call GET "/magasins/$BOUTIQUE_UID/stock"
STOCK_ACTUEL=$(jpath "$BODY" "data.oeufsDisponible")
[ -z "$STOCK_ACTUEL" ] && STOCK_ACTUEL=0
echo "Stock d'œufs actuel dans Boutique Scen : $STOCK_ACTUEL."
if [ "$STOCK_ACTUEL" -lt 500 ]; then
  APPORT=2000
  call POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PROJET_UID\",\"batimentUniqueId\":\"$BATIMENT_UID\",\"magasinStockageUniqueId\":\"$STOCK_UID\",\"date\":\"$(date +%F)\",\"oeufsCollectes\":$APPORT,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "201" ]; then
    echo "ECHEC : collecte d'œufs a échoué ($HTTP_STATUS) : $BODY"
    exit 1
  fi
  call POST /magasin-transferts/create "{\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"projetUniqueId\":\"$PROJET_UID\",\"magasinStockageUniqueId\":\"$STOCK_UID\",\"type\":\"OEUFS\",\"quantite\":$APPORT,\"date\":\"$(date +%F)\"}"
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "201" ]; then
    echo "ECHEC : transfert d'œufs vers Boutique a échoué ($HTTP_STATUS) : $BODY"
    exit 1
  fi
  echo "Apport de $APPORT œufs collectés puis transférés vers Boutique Scen."
fi

echo "-- Seeding terminé (magasin de vente = $BOUTIQUE_UID) --"
echo

# ---------------------------------------------------------------------------
# Scénarios
# ---------------------------------------------------------------------------

scenario1() {
  echo "== Scénario 1 : livraison totale payée intégralement =="
  local client cmd
  nouveau_client "Scenario 1"
  client="$CLIENT_UID"
  call POST /commandes/create "{\"clientUniqueId\":\"$client\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":100,\"prixUnitaireEstime\":1000,\"montantEstime\":100000}"
  cmd=$(jpath "$BODY" "data.uniqueId")
  call POST "/commandes/$cmd/livrer?quantite=100&montantRecu=100000&mode=ESPECES"
  verifier "S1 livraison HTTP" "200" "$HTTP_STATUS"

  compte "$client"
  verifier "S1 totalVendu" "100000" "$(champ "$COMPTE" totalVendu)"
  verifier "S1 totalPaye" "100000" "$(champ "$COMPTE" totalPaye)"
  verifier "S1 resteAPayer" "0" "$(champ "$COMPTE" resteAPayer)"
  verifier "S1 avance" "0" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S1"
}

scenario2() {
  echo "== Scénario 2 : livraison partielle sans paiement =="
  local client cmd
  nouveau_client "Scenario 2"
  client="$CLIENT_UID"
  call POST /commandes/create "{\"clientUniqueId\":\"$client\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":100,\"prixUnitaireEstime\":1000,\"montantEstime\":100000}"
  cmd=$(jpath "$BODY" "data.uniqueId")
  call POST "/commandes/$cmd/livrer?quantite=60"
  verifier "S2 livraison HTTP" "200" "$HTTP_STATUS"

  compte "$client"
  verifier "S2 totalVendu" "60000" "$(champ "$COMPTE" totalVendu)"
  verifier "S2 resteAPayer" "60000" "$(champ "$COMPTE" resteAPayer)"
  invariants "$COMPTE" "S2"
}

# Scénarios 3 et 4 s'enchaînent sur le même client/commande (voir brief : "3.
# ... ; 4. suite : ...").
S3_CLIENT=""
S3_COMMANDE=""
scenario3() {
  echo "== Scénario 3 : acompte puis livraison partielle imputée =="
  local client cmd
  nouveau_client "Scenario 3-4"
  client="$CLIENT_UID"
  call POST /commandes/create "{\"clientUniqueId\":\"$client\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":100,\"prixUnitaireEstime\":1000,\"montantEstime\":100000,\"montantAcompte\":40000}"
  cmd=$(jpath "$BODY" "data.uniqueId")

  compte "$client"
  verifier "S3 avance après acompte" "40000" "$(champ "$COMPTE" avance)"

  call POST "/commandes/$cmd/livrer?quantite=60"
  verifier "S3 livraison HTTP" "200" "$HTTP_STATUS"

  compte "$client"
  verifier "S3 totalImputeVentes" "40000" "$(champ "$COMPTE" totalImputeVentes)"
  verifier "S3 resteAPayer" "20000" "$(champ "$COMPTE" resteAPayer)"
  invariants "$COMPTE" "S3"

  S3_CLIENT="$client"
  S3_COMMANDE="$cmd"
}

scenario4() {
  echo "== Scénario 4 : suite du scénario 3 — règlement du solde =="
  if [ -z "$S3_COMMANDE" ]; then
    echo "ECHEC S4 : scénario 3 non exécuté avant."
    FAILURES=$((FAILURES + 1))
    return
  fi
  call POST "/commandes/$S3_COMMANDE/paiement" '{"montant":20000,"mode":"ESPECES"}'
  verifier "S4 paiement HTTP" "201" "$HTTP_STATUS"

  compte "$S3_CLIENT"
  verifier "S4 resteAPayer" "0" "$(champ "$COMPTE" resteAPayer)"
  verifier "S4 avance" "0" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S4"
}

scenario5() {
  echo "== Scénario 5 : annulation sans remboursement de l'acompte =="
  local client cmd
  nouveau_client "Scenario 5"
  client="$CLIENT_UID"
  call POST /commandes/create "{\"clientUniqueId\":\"$client\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":100,\"prixUnitaireEstime\":1000,\"montantEstime\":100000,\"montantAcompte\":40000}"
  cmd=$(jpath "$BODY" "data.uniqueId")

  call PUT "/commandes/$cmd/annuler" '{"motif":"Client renonce a la commande","rembourserAcompte":false}'
  verifier "S5 annulation HTTP" "200" "$HTTP_STATUS"
  verifier "S5 statut commande" "ANNULEE" "$(jpath "$BODY" "data.statut")"

  compte "$client"
  verifier "S5 avance" "40000" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S5"
}

scenario6() {
  echo "== Scénario 6 : annulation avec remboursement de l'acompte =="
  local client cmd
  nouveau_client "Scenario 6"
  client="$CLIENT_UID"
  call POST /commandes/create "{\"clientUniqueId\":\"$client\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":100,\"prixUnitaireEstime\":1000,\"montantEstime\":100000,\"montantAcompte\":40000}"
  cmd=$(jpath "$BODY" "data.uniqueId")

  call PUT "/commandes/$cmd/annuler" '{"motif":"Client renonce, remboursement immediat","rembourserAcompte":true,"mode":"ESPECES"}'
  verifier "S6 annulation HTTP" "200" "$HTTP_STATUS"

  compte "$client"
  verifier "S6 totalRembourse" "40000" "$(champ "$COMPTE" totalRembourse)"
  verifier "S6 avance" "0" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S6"
}

scenario7() {
  echo "== Scénario 7 (+7bis) : remboursement partiel puis dépassement =="
  local client cmd
  nouveau_client "Scenario 7"
  client="$CLIENT_UID"
  call POST /commandes/create "{\"clientUniqueId\":\"$client\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":100,\"prixUnitaireEstime\":1000,\"montantEstime\":100000,\"montantAcompte\":40000}"
  cmd=$(jpath "$BODY" "data.uniqueId")

  call PUT "/commandes/$cmd/annuler" '{"motif":"Client renonce sans remboursement immediat","rembourserAcompte":false}'
  verifier "S7 annulation HTTP" "200" "$HTTP_STATUS"

  call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":15000,\"mode\":\"ESPECES\",\"motif\":\"Remboursement partiel demande par le client\"}"
  verifier "S7 remboursement partiel HTTP" "201" "$HTTP_STATUS"

  compte "$client"
  verifier "S7 avance après remboursement partiel" "25000" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S7"

  # 7bis : dépasse l'avance disponible (25000) -> 400.
  call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":30000,\"mode\":\"ESPECES\",\"motif\":\"Tentative de depassement\"}"
  verifier "S7bis HTTP" "400" "$HTTP_STATUS"
  local msg
  msg=$(jpath "$BODY" "errors.0")
  if [[ "$msg" == *"dépasse l'avance disponible"* ]]; then
    echo "OK    S7bis message d'erreur (contient « dépasse l'avance disponible »)"
  else
    echo "ECHEC S7bis message d'erreur : obtenu=$msg"
    FAILURES=$((FAILURES + 1))
  fi

  compte "$client"
  verifier "S7bis avance inchangée" "25000" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S7bis"
}

scenario8() {
  echo "== Scénario 8 : acompte, livraison partielle, clôture =="
  local client cmd
  nouveau_client "Scenario 8"
  client="$CLIENT_UID"
  call POST /commandes/create "{\"clientUniqueId\":\"$client\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":100,\"prixUnitaireEstime\":1000,\"montantEstime\":100000,\"montantAcompte\":40000}"
  cmd=$(jpath "$BODY" "data.uniqueId")

  call POST "/commandes/$cmd/livrer?quantite=30"
  verifier "S8 livraison HTTP" "200" "$HTTP_STATUS"

  call PUT "/commandes/$cmd/cloturer" '{"motif":"Livraison partielle terminee, le reste ne sera pas livre"}'
  verifier "S8 clôture HTTP" "200" "$HTTP_STATUS"
  verifier "S8 statut commande" "CLOTUREE" "$(jpath "$BODY" "data.statut")"

  compte "$client"
  verifier "S8 totalImputeVentes" "30000" "$(champ "$COMPTE" totalImputeVentes)"
  verifier "S8 avance" "10000" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S8"
}

# Scénarios 9 et 13 partagent le même client/commande : le brief dit
# explicitement "facture sur les livraisons DU SCÉNARIO 9" — la facture est
# donc générée avant le paiement final de 60000, puis payée via la facture
# (ce qui règle aussi le scénario 9 : "paiement 60000 -> toutes deux PAYEE").
S9_L1_VENTE=""
scenario9_et_13() {
  echo "== Scénarios 9 et 13 : deux livraisons, facturation avant le paiement final =="
  local client cmd
  nouveau_client "Scenario 9-13"
  client="$CLIENT_UID"
  call POST /commandes/create "{\"clientUniqueId\":\"$client\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":100,\"prixUnitaireEstime\":1000,\"montantEstime\":100000,\"montantAcompte\":40000}"
  cmd=$(jpath "$BODY" "data.uniqueId")

  compte "$client"
  verifier "S9 avance après acompte" "40000" "$(champ "$COMPTE" avance)"

  call POST "/commandes/$cmd/livrer?quantite=60"
  verifier "S9 livraison L1 HTTP" "200" "$HTTP_STATUS"
  S9_L1_VENTE=$(jpath "$BODY" "data.livraisons.0.venteUniqueId")

  call POST "/commandes/$cmd/livrer?quantite=40"
  verifier "S9 livraison L2 HTTP" "200" "$HTTP_STATUS"

  # -- Scénario 13 : facture AVANT le paiement final --
  call POST /factures/generer "{\"sourceType\":\"COMMANDE\",\"sourceUniqueId\":\"$cmd\"}"
  verifier "S13 génération facture HTTP" "201" "$HTTP_STATUS"
  local facture
  facture=$(jpath "$BODY" "data.uniqueId")
  verifier "S13 statut facture avant paiement final" "PARTIELLE" "$(jpath "$BODY" "data.statut")"
  verifier "S13 montantTotal facture" "100000" "$(jpath "$BODY" "data.montantTotal")"
  verifier "S13 montantPaye facture avant paiement final" "40000" "$(jpath "$BODY" "data.montantPaye")"
  verifier "S13 resteAPayer facture avant paiement final" "60000" "$(jpath "$BODY" "data.resteAPayer")"

  call POST "/factures/$facture/paiement" '{"montant":60000,"mode":"ESPECES"}'
  verifier "S13 paiement facture HTTP" "200" "$HTTP_STATUS"
  verifier "S13 statut facture après paiement" "PAYEE" "$(jpath "$BODY" "data.statut")"
  verifier "S13 montantPaye facture après paiement" "100000" "$(jpath "$BODY" "data.montantPaye")"

  # -- Retour au scénario 9 : le compte du client doit refléter le paiement,
  #    sans double comptage (mêmes chiffres que si le paiement avait été fait
  #    directement sur la commande).
  compte "$client"
  verifier "S9 totalVendu final" "100000" "$(champ "$COMPTE" totalVendu)"
  verifier "S9 totalPaye final" "100000" "$(champ "$COMPTE" totalPaye)"
  verifier "S9 totalImputeVentes final" "100000" "$(champ "$COMPTE" totalImputeVentes)"
  verifier "S9 resteAPayer final" "0" "$(champ "$COMPTE" resteAPayer)"
  verifier "S9 avance final" "0" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S9/S13"

  call GET "/commandes/list?clientUniqueId=$client&size=5"
  local statuts
  statuts=$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])
items = (d.get('data') or {}).get('data') or []
if not items:
    print('AUCUNE_COMMANDE'); sys.exit()
c = items[0]
livs = c.get('livraisons') or []
print(','.join(l.get('statutPaiement', '?') for l in livs))
" "$BODY")
  if [ "$statuts" = "PAYEE,PAYEE" ]; then
    echo "OK    S9 les deux livraisons sont PAYEE ($statuts)"
  else
    echo "ECHEC S9 les deux livraisons sont PAYEE : obtenu=$statuts"
    FAILURES=$((FAILURES + 1))
  fi

  # -- Scénario 13 (suite) : deuxième facture sur L1 -> 400 (déjà facturée).
  call POST /factures/generer "{\"sourceType\":\"VENTE_OEUFS\",\"sourceUniqueId\":\"$S9_L1_VENTE\"}"
  verifier "S13 deuxième facture sur L1 HTTP" "400" "$HTTP_STATUS"
}

scenario10() {
  echo "== Scénario 10 : vente réglée puis montant corrigé à la baisse =="
  local client vente paiement
  nouveau_client "Scenario 10"
  client="$CLIENT_UID"
  # montantRapporte:0 : la conception dit qu'il "n'est plus utilisé" pour une
  # vente avec client (VenteOeufsImpl.java:284-286, l'entité le force à null
  # dans ce cas) mais la validation d'entrée l'exige encore inconditionnellement
  # (VenteOeufsImpl.java:236-238 — voir rapport, bug pré-existant hors périmètre
  # de cette tâche) : valeur factice ici pour la contourner côté script.
  call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"clientUniqueId\":\"$client\",\"quantiteOeufs\":5,\"prixUnitaire\":1000,\"montant\":5000,\"montantRapporte\":0}"
  vente=$(jpath "$BODY" "data.uniqueId")
  verifier "S10 création vente HTTP" "201" "$HTTP_STATUS"

  call POST /paiements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":5000,\"mode\":\"ESPECES\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$vente\"}"
  verifier "S10 paiement ciblé HTTP" "201" "$HTTP_STATUS"

  compte "$client"
  verifier "S10 resteAPayer avant correction" "0" "$(champ "$COMPTE" resteAPayer)"

  call PUT "/ventes-oeufs/update/$vente" '{"montant":3000}'
  verifier "S10 correction montant HTTP" "200" "$HTTP_STATUS"

  compte "$client"
  verifier "S10 totalImputeVentes" "3000" "$(champ "$COMPTE" totalImputeVentes)"
  verifier "S10 avance" "2000" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S10"

  call GET "/clients/$client/compte"
  local resume
  resume=$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])
imps = (d.get('data') or {}).get('imputations') or []
annulees = sum(1 for i in imps if i.get('statut') == 'ANNULE')
actives_3000 = sum(1 for i in imps if i.get('statut') == 'ACTIF' and abs(i.get('montant', 0) - 3000) < 0.01)
print(f'{annulees},{actives_3000}')
" "$BODY")
  if [ "$resume" = "1,1" ]; then
    echo "OK    S10 historique imputations (1 ANNULEE + 1 ACTIVE de 3000)"
  else
    echo "ECHEC S10 historique imputations : obtenu (annulees,actives_3000)=$resume"
    FAILURES=$((FAILURES + 1))
  fi
}

scenario11() {
  echo "== Scénario 11 : vente payée puis client changé =="
  local clientA clientB vente
  nouveau_client "Scenario 11 A"
  clientA="$CLIENT_UID"
  nouveau_client "Scenario 11 B"
  clientB="$CLIENT_UID"
  call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"clientUniqueId\":\"$clientA\",\"quantiteOeufs\":5,\"prixUnitaire\":1000,\"montant\":5000,\"montantRapporte\":0}"
  vente=$(jpath "$BODY" "data.uniqueId")
  verifier "S11 création vente HTTP" "201" "$HTTP_STATUS"

  call POST /paiements-client/create "{\"clientUniqueId\":\"$clientA\",\"montant\":5000,\"mode\":\"ESPECES\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$vente\"}"
  verifier "S11 paiement ciblé HTTP" "201" "$HTTP_STATUS"

  call PUT "/ventes-oeufs/update/$vente" "{\"clientUniqueId\":\"$clientB\"}"
  verifier "S11 changement de client HTTP" "200" "$HTTP_STATUS"

  compte "$clientA"
  verifier "S11 avance client A" "5000" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S11 client A"

  compte "$clientB"
  verifier "S11 resteAPayer client B" "5000" "$(champ "$COMPTE" resteAPayer)"
  invariants "$COMPTE" "S11 client B"
}

scenario12() {
  echo "== Scénario 12 : paiement, remboursement, annulation refusée =="
  local client paiement
  nouveau_client "Scenario 12"
  client="$CLIENT_UID"
  call POST /paiements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":10000,\"mode\":\"ESPECES\"}"
  paiement=$(jpath "$BODY" "data.uniqueId")
  verifier "S12 paiement HTTP" "201" "$HTTP_STATUS"

  compte "$client"
  verifier "S12 avance après paiement" "10000" "$(champ "$COMPTE" avance)"

  call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":4000,\"mode\":\"ESPECES\",\"motif\":\"Remboursement de test\"}"
  verifier "S12 remboursement HTTP" "201" "$HTTP_STATUS"

  compte "$client"
  verifier "S12 avance après remboursement" "6000" "$(champ "$COMPTE" avance)"
  verifier "S12 totalRembourse" "4000" "$(champ "$COMPTE" totalRembourse)"

  call PUT "/paiements-client/annuler/$paiement" '{"motif":"Tentative annulation apres remboursement"}'
  verifier "S12 annulation paiement HTTP" "400" "$HTTP_STATUS"
  local msg
  msg=$(jpath "$BODY" "errors.0")
  if [[ "$msg" == *"annulez d'abord le remboursement"* ]]; then
    echo "OK    S12 message d'erreur (contient « annulez d'abord le remboursement »)"
  else
    echo "ECHEC S12 message d'erreur : obtenu=$msg"
    FAILURES=$((FAILURES + 1))
  fi

  compte "$client"
  verifier "S12 avance inchangée" "6000" "$(champ "$COMPTE" avance)"
  invariants "$COMPTE" "S12"
}

scenario1
scenario2
scenario3
scenario4
scenario5
scenario6
scenario7
scenario8
scenario9_et_13
scenario10
scenario11
scenario12

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "Tous les scénarios sont OK (0 échec)."
  exit 0
else
  echo "$FAILURES échec(s) au total."
  exit 1
fi
