#!/usr/bin/env bash
# Mobile 1.31 : livraison de commande datée + comptes clients groupés. Script de bout en bout.
#
# 1. POST /commandes/{uid}/livrer?date=AAAA-MM-JJ&heure=HH:mm : une livraison saisie hors
#    ligne garde sa date réelle (vente, transactions, argent reçu) ; date future ou heure
#    mal formée : 400 ; sans date : aujourd'hui.
# 2. GET /clients/comptes?page&size : mêmes chiffres que /clients/{uid}/compte pour chaque
#    client de la ferme, en un appel ; refusé au rôle PRODUCTION.
#
# Pré-requis : Postgres + backend démarrés, base seedée par scenarios-circuit-client.sh.
# Variables : BASE, PGHOST, PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen),
# ADMIN_EMAIL / ADMIN_PWD. Sortie : une ligne OK/ECHEC par assertion ; code 0 si tout est OK.

set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@t.local}"
ADMIN_PWD="${ADMIN_PWD:-Test1234!}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAIL=0

psql_run() {
  local args=(-p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE")
  [ -n "${PGHOST:-}" ] && args=(-h "$PGHOST" "${args[@]}")
  psql "${args[@]}" -v ON_ERROR_STOP=1 -Atc "$1"
}
uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
jval() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$TMP/body" "$1"; }

check() {
  local label="$1" expr="$2"
  if python3 - "$TMP/body" "$TMP/code" "$expr" <<'PY'
import json, sys
body, codef, expr = sys.argv[1:4]
code = int(open(codef).read().strip() or 0)
try:
    d = json.load(open(body))
except Exception:
    d = {}
err = " ".join(d.get("errors") or []) if isinstance(d, dict) else ""
ok = False
try:
    ok = bool(eval(expr, {"d": d, "code": code, "err": err, "len": len}))
except Exception as e:
    print("   exception:", e, file=sys.stderr)
if not ok:
    print("   code HTTP:", code, "réponse:", json.dumps(d, ensure_ascii=False)[:600], file=sys.stderr)
sys.exit(0 if ok else 1)
PY
  then echo "OK     $label"; PASS=$((PASS+1))
  else echo "ECHEC  $label"; FAIL=$((FAIL+1))
  fi
}
check_eq() {
  if [ "$2" = "$3" ]; then echo "OK     $1"; PASS=$((PASS+1))
  else echo "ECHEC  $1 (attendu « $2 », obtenu « $3 »)"; FAIL=$((FAIL+1)); fi
}

api_as() { # $1 = jeton, $2 = méthode, $3 = chemin, $4 = corps
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$2" "$BASE$3" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: mobile' ${4:+-d "$4"} > "$TMP/code"
}
api() { api_as "$TOKEN" "$@"; }

login() {
  curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"])
except Exception: print("")'
}

# ---------------------------------------------------------------------------
TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }
FARM_ID="$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
read -r PROJET BATIMENT < <(psql_run "SELECT p.unique_id || ' ' || b.unique_id FROM projets p
  JOIN occupations_batiments o ON o.projet_id = p.id JOIN batiments b ON b.id = o.batiment_id
  WHERE p.farm_id = $FARM_ID AND p.removed = false ORDER BY p.id LIMIT 1")
SUFFIXE="$(uuid | cut -c1-8)"
AUJ="$(date +%F)"
DEMAIN="$(date -d tomorrow +%F)"
JOUR_LIVRAISON="$(date -d '6 days ago' +%F)"

api POST /magasins/create "{\"nom\":\"Boutique 131 $SUFFIXE\",\"type\":\"VENTE\"}"
BOUTIQUE="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Stock 131 $SUFFIXE\",\"type\":\"STOCKAGE\",\"magasinVenteParDefautUniqueId\":\"$BOUTIQUE\"}"
STOCK="$(jval "d['data']['uniqueId']")"
api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"magasinStockageUniqueId\":\"$STOCK\",\"date\":\"$(date -d '10 days ago' +%F)\",\"oeufsCollectes\":30,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
check "préparation : 30 œufs dans la boutique" "code == 201"
api POST /clients/create "{\"nom\":\"Client 131 $SUFFIXE\",\"telephone\":\"8$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\"}"
CLIENT="$(jval "d['data']['uniqueId']")"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"OEUFS\",\"quantite\":10,\"prixUnitaireEstime\":100,\"montantEstime\":1000,\"dateCommande\":\"$(date -d '8 days ago' +%F)\"}"
CMD="$(jval "d['data']['uniqueId']")"
CMD_ID="$(psql_run "SELECT id FROM commandes WHERE unique_id = '$CMD'")"

echo "== 1. Livraison datée (saisie hors ligne)"
api POST "/commandes/$CMD/livrer?quantite=2&date=$DEMAIN" ""
check "date de livraison future : 400" "code == 400 and 'futur' in err"
api POST "/commandes/$CMD/livrer?quantite=2&date=$JOUR_LIVRAISON&heure=25:99" ""
check "heure mal formée : 400" "code == 400 and 'Heure invalide' in err"
api POST "/commandes/$CMD/livrer?quantite=2&date=26-09-2026" ""
check "date mal formée : 400" "code == 400 and 'Date invalide' in err"
check_eq "aucune vente créée par ces refus" "0" "$(psql_run "SELECT count(*) FROM ventes_oeufs WHERE commande_id = $CMD_ID")"

api POST "/commandes/$CMD/livrer?quantite=4&montantRecu=150&mode=ESPECES&date=$JOUR_LIVRAISON&heure=09:30" ""
check "livraison datée du $JOUR_LIVRAISON à 09:30 : acceptée" "code == 200 and d['data']['quantiteLivree'] == 4"
check_eq "vente créée à la date et à l'heure de la livraison" "$JOUR_LIVRAISON 09:30:00" \
  "$(psql_run "SELECT date || ' ' || heure FROM ventes_oeufs WHERE commande_id = $CMD_ID")"
check_eq "transactions de la vente à cette date" "$JOUR_LIVRAISON" \
  "$(psql_run "SELECT string_agg(DISTINCT t.date::text, ',') FROM transactions t JOIN ventes_oeufs_repartitions r ON r.unique_id = t.source_unique_id
     JOIN ventes_oeufs v ON v.id = r.vente_oeufs_id WHERE v.commande_id = $CMD_ID")"
check_eq "argent reçu à la livraison (paiement LIVRAISON + sa transaction) à cette date" "$JOUR_LIVRAISON|$JOUR_LIVRAISON" \
  "$(psql_run "SELECT p.date || '|' || t.date FROM paiements_client p JOIN transactions t ON t.source_unique_id = p.unique_id
     WHERE p.commande_id = $CMD_ID AND p.origine = 'LIVRAISON'")"

api POST "/commandes/$CMD/livrer?quantite=1" ""
check "livraison sans date : acceptée" "code == 200"
check_eq "sans date : vente datée d'aujourd'hui, sans heure" "$AUJ|" \
  "$(psql_run "SELECT date || '|' || coalesce(heure::text, '') FROM ventes_oeufs WHERE commande_id = $CMD_ID ORDER BY id DESC LIMIT 1")"

echo "== 2. Comptes clients groupés"
api GET "/clients/comptes?page=0&size=500" ""
check "GET /clients/comptes : 200" "code == 200 and len(d['data']['data']) > 0"
cp "$TMP/body" "$TMP/comptes.json"
check_eq "un compte par client actif de la ferme" \
  "$(psql_run "SELECT count(*) FROM clients WHERE farm_id = $FARM_ID AND removed = false")" \
  "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["totalItems"])' "$TMP/comptes.json")"
LISTE_UIDS="$(python3 -c 'import json,sys; print(",".join(chr(39)+c["clientUniqueId"]+chr(39) for c in json.load(open(sys.argv[1]))["data"]["data"]))' "$TMP/comptes.json")"
check_eq "aucun client d'une autre ferme" "0" "$(psql_run "SELECT count(*) FROM clients WHERE unique_id IN ($LISTE_UIDS) AND farm_id <> $FARM_ID")"

# Chaque compte groupé = /clients/{uid}/compte (40 clients au plus : les plus actifs d'abord).
ECARTS=0; N=0
for uid in $(psql_run "SELECT c.unique_id FROM clients c WHERE c.farm_id = $FARM_ID AND c.removed = false
    ORDER BY (SELECT count(*) FROM paiements_client p WHERE p.client_id = c.id) DESC, c.id DESC LIMIT 40"); do
  api GET "/clients/$uid/compte" ""
  if ! python3 - "$TMP/comptes.json" "$TMP/body" "$uid" <<'PY'
import json, sys
groupes = {c["clientUniqueId"]: c for c in json.load(open(sys.argv[1]))["data"]["data"]}
seul = json.load(open(sys.argv[2]))["data"]["compte"]
g = groupes.get(sys.argv[3])
champs = ["totalVendu", "totalPaye", "totalRembourse", "totalImputeVentes", "resteAPayer", "avance",
          "avanceLibre", "avanceReservee", "solde", "avancesReservees"]
ok = g is not None and all(g[k] == seul[k] for k in champs)
if not ok:
    print("   écart", sys.argv[3], {k: (g or {}).get(k) for k in champs}, {k: seul[k] for k in champs}, file=sys.stderr)
sys.exit(0 if ok else 1)
PY
  then ECARTS=$((ECARTS+1)); fi
  N=$((N+1))
done
check_eq "comptes groupés identiques à /clients/{uid}/compte ($N clients comparés)" "0" "$ECARTS"
check_eq "client de la livraison : 500 livrés, 150 reçus, reste 350, rien de réservé" "350.0|0.0|350.0" \
  "$(python3 -c 'import json,sys; g={c["clientUniqueId"]: c for c in json.load(open(sys.argv[1]))["data"]["data"]}; c=g[sys.argv[2]]; print("%s|%s|%s" % (c["resteAPayer"], c["avanceReservee"], c["solde"]))' "$TMP/comptes.json" "$CLIENT")"

api GET "/clients/comptes?page=0&size=2" ""
check "pagination : 2 comptes par page, pages suivantes annoncées" "code == 200 and len(d['data']['data']) == 2 and d['data']['currentPage'] == 1 and d['data']['totalPages'] >= 2"

HASH="$(psql_run "SELECT password FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
PROD_EMAIL="prod-131@t.local"
if [ -z "$(psql_run "SELECT id FROM utilisateurs WHERE email = '$PROD_EMAIL'")" ]; then
  api POST /users/create-pro-or-finance "{\"fullName\":\"Production 131\",\"email\":\"$PROD_EMAIL\",\"telephone\":\"5$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\",\"roles\":[\"PRODUCTION\"]}"
  check "utilisateur PRODUCTION créé" "code in (200, 201)"
fi
psql_run "UPDATE utilisateurs SET password = '$HASH', must_change_password = false WHERE email = '$PROD_EMAIL'" >/dev/null
TOKEN_PROD="$(login "$PROD_EMAIL" "$ADMIN_PWD")"
api_as "$TOKEN_PROD" GET "/clients/comptes" ""
check "rôle PRODUCTION : comptes clients refusés (400)" "code == 400 and 'droits' in err"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
