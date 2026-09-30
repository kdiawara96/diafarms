#!/usr/bin/env bash
# Revue du 2026-09-30 : dates des dépenses, soins pris dans le stock, main-d'œuvre,
# transferts d'aliment, type de transaction, sortie « Médicament ».
#
# 1. Changer la date d'un achat d'aliment déplace sa dépense.
# 2. Une entrée « Transfert depuis ... » (clôture d'un projet) ne se modifie ni ne se
#    supprime (elle créerait une dépense en double).
# 3. Un soin pris dans le stock ne crée jamais de dépense SOINS/VACCINATION (coût vidé,
#    dépense existante retirée, pas de retour à la restauration).
# 4. Coût de main-d'œuvre d'un projet et affectations du personnel : refusés à un vendeur.
# 5. Transaction sans type : 400 (et non 500) ; sortie « Médicament » : 400 vers l'achat
#    de médicament.
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
    ok = bool(eval(expr, {"d": d, "code": code, "err": err}))
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

api() {
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


# ---------------------------------------------------------------------------
TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }
FARM_ID="$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
read -r PROJET BATIMENT < <(psql_run "SELECT p.unique_id || ' ' || b.unique_id FROM projets p
  JOIN occupations_batiments o ON o.projet_id = p.id JOIN batiments b ON b.id = o.batiment_id
  WHERE p.farm_id = $FARM_ID AND p.removed = false ORDER BY p.id LIMIT 1")
AUJ="$(date +%F)"
HIER="$(date -d yesterday +%F)"
AVANT_HIER="$(date -d '-2 days' +%F)"
SUFFIXE="$(uuid | cut -c1-8)"
tx_de_source() { psql_run "SELECT unique_id FROM transactions WHERE source_unique_id = '$1'"; }

echo "== 1. Achat d'aliment : la dépense suit la date de l'achat"
api POST "/alimentations/create/$PROJET" "{\"nomAliment\":\"Maïs date $SUFFIXE\",\"sac\":1,\"quantiteKg\":50,\"coutTotal\":12000,\"dateDistribution\":\"$HIER\"}"
check "achat d'aliment daté d'hier" "code in (200, 201)"
ALIM="$(jval "d['data']['uniqueId']")"
check_eq "dépense datée d'hier" "$HIER" "$(psql_run "SELECT date FROM transactions WHERE source_unique_id = '$ALIM'")"
api PUT "/alimentations/update/$ALIM" "{\"dateDistribution\":\"$AVANT_HIER\"}"
check "date de l'achat changée (avant-hier)" "code == 200"
check_eq "dépense déplacée à avant-hier" "$AVANT_HIER" "$(psql_run "SELECT date FROM transactions WHERE source_unique_id = '$ALIM'")"
api DELETE "/alimentations/delete/$ALIM"

echo "== 2. Aliment « Transfert depuis ... » (clôture d'un projet) : ni modifiable ni supprimable"
api POST "/alimentations/create/$PROJET" "{\"nomAliment\":\"Maïs transfert $SUFFIXE\",\"sac\":1,\"quantiteKg\":10,\"coutTotal\":2000,\"dateDistribution\":\"$AUJ\"}"
TRANSF="$(jval "d['data']['uniqueId']")"
# Même forme que ProjetImpl.transfererStock (pas de dépense liée).
psql_run "UPDATE alimentations SET nom_aliment = 'Transfert depuis PRJ-X', observations = 'Stock restant transféré lors de la clôture du projet PRJ-X' WHERE unique_id = '$TRANSF'" >/dev/null
psql_run "UPDATE transactions SET removed = true WHERE source_unique_id = '$TRANSF'" >/dev/null
api PUT "/alimentations/update/$TRANSF" '{"coutTotal":2500}'
check "transfert : modification refusée" "code == 400 and 'transfert de fin de projet' in err"
api DELETE "/alimentations/delete/$TRANSF"
check "transfert : suppression refusée" "code == 400 and 'transfert de fin de projet' in err"
check_eq "transfert : toujours sans dépense" "0" "$(psql_run "SELECT count(*) FROM transactions WHERE source_unique_id = '$TRANSF' AND coalesce(removed,false) = false")"
psql_run "UPDATE alimentations SET removed = true WHERE unique_id = '$TRANSF'" >/dev/null

echo "== 3. Soin pris dans le stock : jamais de dépense SOINS/VACCINATION"
NOM="Vaccin revue $SUFFIXE"
api POST "/medicaments/create/$PROJET" "{\"nom\":\"$NOM\",\"forme\":\"LIQUIDE\",\"unite\":\"flacon\",\"quantite\":10,\"coutTotal\":50000,\"dateAchat\":\"$AUJ\"}"
check "achat de 10 flacons" "code in (200, 201)"
api POST /soins/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"type\":\"VACCINATION\",\"produit\":\"$NOM\",\"unite\":\"flacon\",\"quantite\":2,\"depuisStock\":true,\"coutTotal\":9000}"
check "soin pris dans le stock (coût envoyé quand même)" "code in (200, 201)"
SOIN1="$(jval "d['data']['uniqueId']")"
check_eq "aucune dépense pour ce soin" "0" "$(psql_run "SELECT count(*) FROM transactions WHERE source_unique_id = '$SOIN1' AND coalesce(removed,false) = false")"
check_eq "coût du soin vidé" "" "$(psql_run "SELECT coalesce(cout_total::text,'') FROM soins WHERE unique_id = '$SOIN1'")"
api POST /soins/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"type\":\"VACCINATION\",\"produit\":\"$NOM\",\"quantite\":1,\"coutTotal\":4000}"
check "soin hors stock avec coût" "code in (200, 201)"
SOIN2="$(jval "d['data']['uniqueId']")"
check_eq "dépense du soin hors stock" "1" "$(psql_run "SELECT count(*) FROM transactions WHERE source_unique_id = '$SOIN2' AND coalesce(removed,false) = false")"
api PUT "/soins/update/$SOIN2" '{"depuisStock":true,"unite":"flacon","coutTotal":4000}'
check "soin passé en « pris dans le stock »" "code == 200"
check_eq "sa dépense est retirée" "0" "$(psql_run "SELECT count(*) FROM transactions WHERE source_unique_id = '$SOIN2' AND coalesce(removed,false) = false")"
api PUT "/soins/deleteOrRecover/$SOIN2"
api PUT "/soins/deleteOrRecover/$SOIN2"
check "soin supprimé puis restauré" "code == 200"
check_eq "restauré : toujours sans dépense" "0" "$(psql_run "SELECT count(*) FROM transactions WHERE source_unique_id = '$SOIN2' AND coalesce(removed,false) = false")"
api PUT "/soins/deleteOrRecover/$SOIN1"; api PUT "/soins/deleteOrRecover/$SOIN2"

echo "== 4. Main-d'œuvre : réservée aux rôles qui gèrent les salaires"
PERSONNEL="$(psql_run "SELECT unique_id FROM personnel WHERE farm_id = $FARM_ID LIMIT 1" 2>/dev/null || true)"
api GET "/main-oeuvre/projets/$PROJET/cout"
check "admin : coût de main-d'œuvre du projet" "code == 200"
ADMIN_TOKEN="$TOKEN"
TOKEN="$(login "${VENTE_EMAIL:-vente@t.local}" "$ADMIN_PWD")"
if [ -n "$TOKEN" ]; then
  api GET "/main-oeuvre/projets/$PROJET/cout"
  check "vendeur : coût de main-d'œuvre refusé" "code == 400 and 'droits' in err"
  api GET "/main-oeuvre/personnel/${PERSONNEL:-inconnu}/affectations"
  check "vendeur : affectations du personnel refusées" "code == 400 and 'droits' in err"
else
  echo "ECHEC  connexion du vendeur (vente@t.local)"; FAIL=$((FAIL+1))
fi
TOKEN="$ADMIN_TOKEN"

echo "== 5. Transactions : type obligatoire (400, plus de 500), sortie « Médicament » redirigée"
api POST /transactions/create "{\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Sans type $SUFFIXE\",\"montant\":100,\"categorie\":\"Divers\"}"
check "sans type : 400 « Type de transaction obligatoire »" "code == 400 and 'Type de transaction obligatoire' in err"
api POST /transactions/create "{\"type\":\"SORTIE\",\"projetUniqueId\":\"$PROJET\",\"date\":\"$AUJ\",\"description\":\"Médoc $SUFFIXE\",\"montant\":100,\"categorie\":\"Médicaments\"}"
check "sortie « Médicaments » : 400 vers l'achat de médicament" "code == 400 and 'Achat de médicament' in err"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Divers $SUFFIXE\",\"montant\":100,\"categorie\":\"Divers\"}"
check "sortie « Divers » acceptée" "code in (200, 201)"
T_DIVERS="$(jval "d['data']['uniqueId']")"
api PUT "/transactions/update/$T_DIVERS" '{"categorie":"medicament"}'
check "modification en « medicament » refusée" "code == 400 and 'Achat de médicament' in err"
api PUT "/transactions/deleteOrRecover/$T_DIVERS" '{"motif":"Test revue"}'

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
