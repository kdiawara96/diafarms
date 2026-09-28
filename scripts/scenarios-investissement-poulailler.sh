#!/usr/bin/env bash
# Investissement « Construction & Protection » + poulaillers : script de bout en bout.
#
# 1. Création atomique : investissement + 2 poulaillers dans une seule transaction ;
#    un poulailler invalide (capacité 0, nom en double) annule TOUT (ni investissement
#    ni poulailler créé).
# 2. Lien vers un poulailler existant ; refus d'un poulailler d'une autre ferme.
# 3. COMPTABLE : refus de créer un poulailler par ce chemin.
# 4. Modification : retrait d'un lien (le poulailler reste), création d'un nouveau.
# 5. Suppression de l'investissement : les poulaillers restent, seuls les liens partent.
#    Suppression d'un poulailler : seul son lien part, l'investissement reste.
# 6. Le poulailler créé apparaît dans /batiments/list-paginated (badge investissement),
#    /batiments/select (assistant de création de Projet, mobile) et /batiments/tous.
# 7. Aucune répartition d'amortissement n'est créée par le lien.
#
# Pré-requis : Postgres + backend démarrés, base seedée par scenarios-circuit-client.sh
# (ferme + ADMIN admin@t.local + COMPTABLE compta@t.local, mot de passe Test1234!).
# Rejouable : chaque passage utilise des noms uniques.
#
# Variables : BASE, PGHOST, PGPORT (55432), PGUSER (postgres), PGDATABASE
# (diafarms_scen), ADMIN_EMAIL / ADMIN_PWD, COMPTA_EMAIL / COMPTA_PWD.

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

# check "libellé" "expression python sur d (réponse JSON) et code (statut HTTP)"
check() {
  local label="$1" expr="$2"
  if python3 - "$TMP/body" "$TMP/code" "$expr" <<'PY'
import json, sys
body, codef, expr = sys.argv[1], sys.argv[2], sys.argv[3]
code = int(open(codef).read().strip() or 0)
try:
    d = json.load(open(body))
except Exception:
    d = {}
ok = False
try:
    ok = bool(eval(expr, {"d": d, "code": code, "abs": abs, "len": len}))
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

# check_sql "libellé" "requête" "valeur attendue"
check_sql() {
  local got
  got="$(psql_run "$2")"
  if [ "$got" = "$3" ]; then echo "OK     $1"; PASS=$((PASS+1))
  else echo "ECHEC  $1 (attendu « $3 », obtenu « $got »)"; FAIL=$((FAIL+1))
  fi
}

api() { # $1 = méthode, $2 = chemin relatif à BASE, $3 = corps JSON (ou "")
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${3:+-d "$3"} > "$TMP/code"
}

login() {
  curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"])
except Exception: print("")'
}


COMPTA_EMAIL="${COMPTA_EMAIL:-compta@t.local}"
COMPTA_PWD="${COMPTA_PWD:-$ADMIN_PWD}"

TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }
FARM_ID="$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
SUF="$(python3 -c 'import random,string; print("".join(random.choices(string.ascii_uppercase, k=5)))')"
P1="Poulailler A $SUF"; P2="Poulailler B $SUF"; P3="Poulailler C $SUF"; PEX="Existant $SUF"

invest() { # $1 = nom, $2 = batimentIds JSON, $3 = nouveauxPoulaillers JSON
  printf '{"categorie":"Construction & Protection","nom":"%s","montant":1200000,"dateAchat":"2026-09-01","fournisseur":"","dureeAmortissement":60,"affectation":"COMMUN","commentaire":"","type":"Lineaire","batimentIds":%s,"nouveauxPoulaillers":%s}' "$1" "$2" "$3"
}
nb_bat() { psql_run "SELECT count(*) FROM batiments WHERE farm_id = $FARM_ID AND nom = '$1'"; }
nb_inv() { psql_run "SELECT count(*) FROM investissements WHERE nom = '$1'"; }

echo "== 1. Création atomique investissement + 2 poulaillers"
api POST /investissements/create "$(invest "Bâtiment $SUF" '[]' "[{\"nom\":\"$P1\",\"capacite\":1000,\"superficieM2\":120,\"description\":\"Construit en 2026\"},{\"nom\":\"$P2\",\"capacite\":500}]")"
check "200, 2 poulaillers reliés" "code == 200 and len(d['data']['batiments']) == 2"
INV="$(jval "d['data']['uniqueId']")"
check_sql "poulailler A créé (capacité 1000, 120 m², DISPONIBLE)" "SELECT capacite || ' ' || superficie_m2 || ' ' || statut FROM batiments WHERE farm_id = $FARM_ID AND nom = '$P1'" "1000 120 DISPONIBLE"
check_sql "poulailler B créé" "$(printf "SELECT count(*) FROM batiments WHERE farm_id = %s AND nom = '%s' AND removed = false" "$FARM_ID" "$P2")" "1"
check_sql "2 liens en base" "SELECT count(*) FROM investissement_batiments ib JOIN investissements i ON i.id = ib.investissement_id WHERE i.unique_id = '$INV'" "2"
check_sql "aucune répartition créée par le lien" "SELECT count(*) FROM investissement_repartitions r JOIN investissements i ON i.id = r.investissement_id WHERE i.unique_id = '$INV'" "0"
B1="$(psql_run "SELECT unique_id FROM batiments WHERE farm_id = $FARM_ID AND nom = '$P1'")"
B2="$(psql_run "SELECT unique_id FROM batiments WHERE farm_id = $FARM_ID AND nom = '$P2'")"

echo "== 2. Rollback si un poulailler est invalide"
api POST /investissements/create "$(invest "Rollback $SUF" '[]' "[{\"nom\":\"Rb1 $SUF\",\"capacite\":300},{\"nom\":\"Rb2 $SUF\",\"capacite\":0}]")"
check "capacité 0 : 400" "code == 400 and 'capacité' in ' '.join(d.get('errors') or [])"
check_sql "rollback : aucun investissement" "SELECT count(*) FROM investissements WHERE nom = 'Rollback $SUF'" "0"
check_sql "rollback : premier poulailler non créé" "SELECT count(*) FROM batiments WHERE nom = 'Rb1 $SUF'" "0"
api POST /investissements/create "$(invest "Doublon $SUF" '[]' "[{\"nom\":\"Rb3 $SUF\",\"capacite\":300},{\"nom\":\"$P1\",\"capacite\":10}]")"
check "nom déjà existant dans la ferme : 400" "code == 400 and 'existe déjà' in ' '.join(d.get('errors') or [])"
check_sql "doublon : rien créé" "SELECT (SELECT count(*) FROM investissements WHERE nom = 'Doublon $SUF') + (SELECT count(*) FROM batiments WHERE nom = 'Rb3 $SUF')" "0"
api POST /investissements/create "$(invest "Doublon2 $SUF" '[]' "[{\"nom\":\"Rb4 $SUF\",\"capacite\":300},{\"nom\":\"rb4 $SUF\",\"capacite\":10}]")"
check "même nom deux fois dans la demande : 400" "code == 400"
check_sql "doublon interne : rien créé" "SELECT count(*) FROM batiments WHERE lower(nom) = lower('Rb4 $SUF')" "0"

echo "== 3. Relier un poulailler existant"
api POST /batiments/create "{\"nom\":\"$PEX\",\"capacite\":800}"
check "création directe depuis Poulaillers inchangée (201)" "code == 201"
BEX="$(jval "d['data']['uniqueId']")"
api POST /investissements/create "$(invest "Toiture $SUF" "[\"$BEX\"]" '[]')"
check "200, relié à l'existant" "code == 200 and [b['uniqueId'] for b in d['data']['batiments']] == ['$BEX']"
INV2="$(jval "d['data']['uniqueId']")"
check_sql "aucun nouveau poulailler" "SELECT count(*) FROM batiments WHERE farm_id = $FARM_ID AND nom = '$PEX'" "1"

echo "== 4. Poulailler d'une autre ferme refusé"
psql_run "INSERT INTO farms (unique_id) SELECT 'pesee-autre-ferme' WHERE NOT EXISTS (SELECT 1 FROM farms WHERE unique_id = 'pesee-autre-ferme')" >/dev/null
psql_run "INSERT INTO batiments (unique_id, nom, capacite, statut, farm_id, removed)
  SELECT 'invpoul-autre-bat', 'Poulailler autre ferme', 100, 'DISPONIBLE', (SELECT id FROM farms WHERE unique_id = 'pesee-autre-ferme'), false
  WHERE NOT EXISTS (SELECT 1 FROM batiments WHERE unique_id = 'invpoul-autre-bat')" >/dev/null
api POST /investissements/create "$(invest "Autre ferme $SUF" '["invpoul-autre-bat"]' '[]')"
check "400 « introuvable »" "code == 400 and 'introuvable' in ' '.join(d.get('errors') or [])"
check_sql "rien créé" "SELECT count(*) FROM investissements WHERE nom = 'Autre ferme $SUF'" "0"
api PUT "/investissements/update/$INV2" "$(invest "Toiture $SUF" '["invpoul-autre-bat"]' '[]')"
check "modification : 400 « introuvable »" "code == 400 and 'introuvable' in ' '.join(d.get('errors') or [])"

echo "== 5. COMPTABLE refusé"
TOKEN_ADMIN="$TOKEN"
TOKEN="$(login "$COMPTA_EMAIL" "$COMPTA_PWD")"
if [ -z "$TOKEN" ]; then echo "ECHEC  connexion $COMPTA_EMAIL"; FAIL=$((FAIL+1)); else
  api POST /investissements/create "$(invest "Compta $SUF" '[]' "[{\"nom\":\"Compta $SUF\",\"capacite\":100}]")"
  check "400 « droits »" "code == 400 and 'droits' in ' '.join(d.get('errors') or [])"
  check_sql "rien créé" "SELECT (SELECT count(*) FROM investissements WHERE nom = 'Compta $SUF') + (SELECT count(*) FROM batiments WHERE nom = 'Compta $SUF')" "0"
fi
TOKEN="$TOKEN_ADMIN"

echo "== 6. Modification : retirer un lien, créer un poulailler"
api PUT "/investissements/update/$INV" "$(invest "Bâtiment $SUF" "[\"$B1\"]" "[{\"nom\":\"$P3\",\"capacite\":700}]")"
check "200, liens A + C" "code == 200 and sorted(b['nom'] for b in d['data']['batiments']) == sorted(['$P1', '$P3'])"
check_sql "poulailler B conservé (lien seul retiré)" "SELECT count(*) FROM batiments WHERE unique_id = '$B2' AND removed = false" "1"
api PUT "/investissements/update/$INV" "$(invest "Bâtiment $SUF" 'null' '[]')"
check "batimentIds absent : liens inchangés" "code == 200 and len(d['data']['batiments']) == 2"

echo "== 7. Listes de poulaillers"
api GET "/batiments/list-paginated?page=0&size=50&search=$SUF" ""
check "Poulaillers : badge investissement" "code == 200 and any(b['nom'] == '$P1' and b['investissements'] == ['Bâtiment $SUF'] for b in d['data']['data'])"
api GET /batiments/select ""
check "assistant Projet / mobile (select) : poulailler A présent" "code == 200 and any(b['uniqueId'] == '$B1' for b in d['data'])"
api GET /batiments/tous ""
check "mobile (tous) : poulailler C présent" "code == 200 and any(b['nom'] == '$P3' for b in d['data'])"

echo "== 8. Suppressions"
api DELETE "/investissements/delete/$INV" ""
check "suppression investissement : 200" "code == 200"
check_sql "poulaillers A, B, C toujours là" "SELECT count(*) FROM batiments WHERE farm_id = $FARM_ID AND nom IN ('$P1', '$P2', '$P3') AND removed = false" "3"
check_sql "liens supprimés" "SELECT count(*) FROM investissement_batiments ib JOIN batiments b ON b.id = ib.batiment_id WHERE b.nom IN ('$P1', '$P3')" "0"
api PUT "/batiments/deleteOrRecover/$BEX" ""
check "suppression du poulailler existant : 200" "code == 200"
check_sql "son lien est retiré" "SELECT count(*) FROM investissement_batiments ib JOIN batiments b ON b.id = ib.batiment_id WHERE b.unique_id = '$BEX'" "0"
check_sql "l'investissement reste" "SELECT count(*) FROM investissements WHERE unique_id = '$INV2'" "1"
api PUT "/batiments/deleteOrRecover/$BEX" ""
api GET "/investissements/find/$INV2" ""
check "récupération : investissement sans lien, détail OK" "code == 200 and d['data']['batiments'] == []"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
