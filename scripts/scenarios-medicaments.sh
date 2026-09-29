#!/usr/bin/env bash
# Médicaments : achat (dépense + stock du projet) et soins pris dans le stock.
#
# - Achat de médicament (POST /medicaments/create/{projet}) : dépense verrouillée (source
#   MEDICAMENT, catégorie Santé / Vétérinaire, sans site) + entrée en stock du projet.
# - Soin pris dans le stock (depuisStock) : refusé au-delà du restant ; produit et unité
#   sans tenir compte des majuscules ; soin libre (non pris dans le stock) sans effet.
# - Achat entamé : quantité minimum, ni changement de projet ni suppression.
# - Isolation des fermes.
# Pré-requis : base seedée par scenarios-circuit-client.sh et la contrainte CHECK à jour
# (docs/sql/2026-09-29_source_type_medicament.sql). Rejouable.
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

# verrou "libellé" TRANSACTION_UID "écran attendu dans le message"
verrou() {
  local label="$1" t="$2" ecran="$3"
  api PUT "/transactions/update/$t" '{"montant":1,"description":"Retouche comptable"}'
  check "$label : modification refusée, message vers « $ecran »" "code == 400 and 'générée automatiquement' in err and '$ecran' in err"
  api PUT "/transactions/deleteOrRecover/$t" '{"motif":"Essai de suppression"}'
  check "$label : suppression refusée" "code >= 400 and 'générée automatiquement' in err"
  api PUT "/transactions/demander-suppression/$t" '{"motif":"Essai de demande"}'
  check "$label : demande de suppression refusée" "code == 400 and 'générée automatiquement' in err"
  api PUT "/transactions/rejeter/$t" '{"commentaire":"Essai de rejet"}'
  check "$label : rejet refusé" "code == 400 and 'générée automatiquement' in err"
  check_eq "$label : transaction intacte (active, VALIDE, pas de demande)" "f|VALIDE|" \
    "$(psql_run "SELECT coalesce(removed,false) || '|' || statut || '|' || coalesce(demande_suppression_par_id::text,'') FROM transactions WHERE unique_id = '$t'" | sed 's/^false/f/;s/^true/t/')"
}

tx_de_source() { psql_run "SELECT unique_id FROM transactions WHERE source_unique_id = '$1'"; }
tx_removed() { psql_run "SELECT coalesce(removed,false) FROM transactions WHERE source_unique_id = '$1'" | sed 's/^false/f/;s/^true/t/'; }

TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }
FARM_ID="$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
read -r PROJET BATIMENT < <(psql_run "SELECT p.unique_id || ' ' || b.unique_id FROM projets p
  JOIN occupations_batiments o ON o.projet_id = p.id JOIN batiments b ON b.id = o.batiment_id
  WHERE p.farm_id = $FARM_ID AND p.removed = false ORDER BY p.id LIMIT 1")
AUJ="$(date +%F)"
SUFFIXE="$(uuid | cut -c1-6)"
NOM="Vaccin Newcastle $SUFFIXE"
stock_restant() { api GET "/medicaments/stock/$PROJET"; jval "next((round(s['restant'], 2) for s in d['data'] if s['nom'].lower() == '$NOM'.lower() and s['unite'].lower() == 'flacon'), None)"; }
soin() { # $1 produit, $2 unite, $3 quantite, $4 depuisStock
  api POST /soins/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"type\":\"VACCINATION\",\"produit\":\"$1\",\"unite\":\"$2\",\"quantite\":$3,\"depuisStock\":$4}"
}

echo "== 1. Achat : dépense + stock"
api POST "/medicaments/create/$PROJET" "{\"nom\":\"$NOM\",\"forme\":\"LIQUIDE\",\"unite\":\"flacon\",\"quantite\":10,\"coutTotal\":50000,\"dateAchat\":\"$AUJ\",\"fournisseur\":\"Vétopharma\"}"
check "achat de 10 flacons créé, prix unitaire déduit (5000)" "code == 201 and d['data']['prixUnitaire'] == 5000 and d['data']['forme'] == 'LIQUIDE'"
ACHAT="$(jval "d['data']['uniqueId']")"
check_eq "dépense générée : MEDICAMENT, Santé / Vétérinaire, 50000, projet, sans site, 10 × 5000" "MEDICAMENT|Santé / Vétérinaire|50000|$PROJET|-|10|5000" \
  "$(psql_run "SELECT t.source_type || '|' || t.categorie || '|' || t.montant::bigint || '|' || p.unique_id || '|' || coalesce(t.site_id::text,'-') || '|' || t.quantite::bigint || '|' || t.prix_unitaire::bigint FROM transactions t JOIN projets p ON p.id = t.projet_id WHERE t.source_unique_id = '$ACHAT'")"
check_eq "stock : 10 flacons" "10.0" "$(stock_restant)"
T_ACHAT="$(tx_de_source "$ACHAT")"
api PUT "/transactions/update/$T_ACHAT" '{"montant":1}'
check "dépense de l'achat : modification refusée depuis la transaction" "code == 400 and 'achat de médicament' in err"

echo "== 2. Soins pris dans le stock"
soin "$NOM" "flacon" 3 true
check "soin de 3 flacons : accepté" "code in (200, 201) and d['data']['depuisStock'] is True"
SOIN1="$(jval "d['data']['uniqueId']")"
check_eq "stock : 7 flacons" "7.0" "$(stock_restant)"
soin "$NOM" "flacon" 8 true
check "soin de 8 flacons (reste 7) : refusé" "code == 400 and 'il reste 7' in err"
soin "$(echo "$NOM" | tr '[:upper:]' '[:lower:]')" "Flacon" 2 true
check "même produit écrit autrement (majuscules) : accepté" "code in (200, 201)"
SOIN2="$(jval "d['data']['uniqueId']")"
check_eq "stock : 5 flacons" "5.0" "$(stock_restant)"
soin "Vaccin offert $SUFFIXE" "dose" 100 false
check "soin libre (non pris dans le stock) : accepté" "code in (200, 201) and not d['data'].get('depuisStock')"
SOIN3="$(jval "d['data']['uniqueId']")"
check_eq "stock inchangé par le soin libre" "5.0" "$(stock_restant)"
soin "Produit inconnu $SUFFIXE" "flacon" 1 true
check "produit jamais acheté pris « dans le stock » : refusé" "code == 400 and 'pas de' in err"
api PUT "/soins/update/$SOIN2" '{"quantite":8}'
check "soin porté à 8 flacons (3 + 8 > 10) : refusé" "code == 400 and 'Stock insuffisant' in err"
api PUT "/soins/update/$SOIN2" '{"quantite":7}'
check "soin porté à 7 flacons (3 + 7 = 10) : accepté" "code == 200"
check_eq "stock : 0 flacon" "0.0" "$(stock_restant)"

echo "== 3. Achat entamé"
api PUT "/medicaments/update/$ACHAT" '{"quantite":6}'
check "achat réduit à 6 (10 utilisés) : refusé, minimum indiqué" "code == 400 and 'minimum pour cet achat est 10' in err"
AUTRE_P="$(psql_run "SELECT unique_id FROM projets WHERE farm_id = $FARM_ID AND unique_id <> '$PROJET' ORDER BY id LIMIT 1")"
psql_run "UPDATE projets SET removed = false WHERE unique_id = '$AUTRE_P'" >/dev/null
api PUT "/medicaments/update/$ACHAT" "{\"projetUniqueId\":\"$AUTRE_P\"}"
check "achat entamé changé de projet : refusé" "code == 400 and 'impossible de changer' in err"
psql_run "UPDATE projets SET removed = true WHERE unique_id = '$AUTRE_P'" >/dev/null
api DELETE "/medicaments/delete/$ACHAT"
check "achat entamé supprimé : refusé" "code == 400 and 'Impossible de supprimer' in err"
api PUT "/medicaments/update/$ACHAT" '{"coutTotal":48000}'
check "prix de l'achat entamé : modifiable, dépense suit" "code == 200 and d['data']['coutTotal'] == 48000"
check_eq "dépense mise à jour" "48000" "$(psql_run "SELECT montant::bigint FROM transactions WHERE source_unique_id = '$ACHAT'")"

echo "== 4. Soins retirés : l'achat redevient libre"
for s in "$SOIN1" "$SOIN2" "$SOIN3"; do api PUT "/soins/deleteOrRecover/$s"; done
check_eq "stock : 10 flacons" "10.0" "$(stock_restant)"
api DELETE "/medicaments/delete/$ACHAT"
check "achat non entamé supprimé" "code == 200"
check_eq "dépense de l'achat retirée" "t" "$(tx_removed "$ACHAT")"

echo "== 5. Isolation des fermes"
AUTRE_FERME_P="$(psql_run "SELECT p.unique_id FROM projets p WHERE p.farm_id <> $FARM_ID ORDER BY p.id LIMIT 1")"
api GET "/medicaments/stock/$AUTRE_FERME_P"
check "stock d'un projet d'une autre ferme : refusé" "code == 400"
api POST "/medicaments/create/$AUTRE_FERME_P" "{\"nom\":\"Pirate\",\"unite\":\"flacon\",\"quantite\":1,\"coutTotal\":100}"
check "achat sur un projet d'une autre ferme : refusé" "code == 400"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
