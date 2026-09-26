#!/usr/bin/env bash
# Idempotence des envois mobiles (en-tête Idempotency-Key) : script de bout en bout.
#
# L'appli mobile envoie ses saisies hors ligne une à une ; si la réponse se perd, la saisie
# est renvoyée plus tard avec la MÊME clé. Le serveur ne doit jamais la créer deux fois :
# - même clé + même corps : la première réponse est rejouée (Idempotency-Replayed: true) ;
# - 5 envois simultanés : une seule ligne ;
# - même clé, autre corps : 422 ;
# - un refus métier 400 est rejoué tel quel (pas ré-exécuté) ;
# - une erreur serveur 5xx n'est pas mémorisée : le renvoi ré-exécute la saisie ;
# - chaque création mobile couverte une fois (collecte, vente, paiement client, transaction,
#   consommation, mortalité, réforme, commande) ;
# - sans l'en-tête, comportement inchangé (deux envois = deux lignes).
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

ok()    { echo "OK     $1"; PASS=$((PASS+1)); }
echec() { echo "ECHEC  $1"; FAIL=$((FAIL+1)); }

# check "libellé" "expression python sur d (réponse JSON), code (statut HTTP), rejoue (bool)"
check() {
  local label="$1" expr="$2"
  if python3 - "$TMP/body" "$TMP/code" "$TMP/headers" "$expr" <<'PY'
import json, sys
body, codef, headf, expr = sys.argv[1:5]
code = int(open(codef).read().strip() or 0)
try:
    d = json.load(open(body))
except Exception:
    d = {}
try:
    h = open(headf).read().lower()
except Exception:
    h = ""
rejoue = "idempotency-replayed: true" in h
ok = False
try:
    ok = bool(eval(expr, {"d": d, "code": code, "rejoue": rejoue, "len": len}))
except Exception as e:
    print("   exception:", e, file=sys.stderr)
if not ok:
    print("   code HTTP:", code, "rejoué:", rejoue, "réponse:", json.dumps(d, ensure_ascii=False)[:600], file=sys.stderr)
sys.exit(0 if ok else 1)
PY
  then ok "$label"; else echec "$label"; fi
}

check_eq() { # "libellé" attendu obtenu
  if [ "$2" = "$3" ]; then ok "$1"; else echec "$1 (attendu « $2 », obtenu « $3 »)"; fi
}

# api MÉTHODE CHEMIN CORPS [CLÉ] : réponse dans $TMP/body, $TMP/code, $TMP/headers
api() {
  local cle="${4:-}"
  : > "$TMP/headers"
  curl -s -D "$TMP/headers" -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: mobile' ${cle:+-H "Idempotency-Key: $cle"} ${3:+-d "$3"} > "$TMP/code"
}

login() {
  curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"])
except Exception: print("")'
}

compter() { psql_run "SELECT count(*) FROM $1"; }
idem_sql() { psql_run "SELECT $2 FROM idempotency_requests WHERE cle = '$1'"; }

# cas_idem "libellé" CHEMIN CORPS TABLE : envoi, renvoi avec la même clé, une seule ligne.
cas_idem() {
  local label="$1" chemin="$2" corps="$3" table="$4" cle avant code1 body1
  cle="$(uuid)"
  avant="$(compter "$table")"
  api POST "$chemin" "$corps" "$cle"
  code1="$(cat "$TMP/code")"; body1="$(cat "$TMP/body")"
  check "$label : premier envoi accepté ($code1)" "code in (200, 201) and not rejoue"
  api POST "$chemin" "$corps" "$cle"
  check "$label : renvoi rejoué (même statut, Idempotency-Replayed)" "code == $code1 and rejoue"
  if [ "$(cat "$TMP/body")" = "$body1" ]; then ok "$label : réponse rejouée identique"; else echec "$label : réponse rejouée différente"; fi
  check_eq "$label : une seule ligne créée dans $table" "$((avant + 1))" "$(compter "$table")"
  LAST_UID="$(python3 -c 'import json,sys; print((json.loads(sys.argv[1]).get("data") or {}).get("uniqueId",""))' "$body1")"
}

jour_au_hasard() { python3 -c 'import random,datetime; print(datetime.date(2026,2,1)+datetime.timedelta(days=random.randint(0,200)))'; }

# ---------------------------------------------------------------------------
# Préparation
# ---------------------------------------------------------------------------
TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }
FARM_ID="$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
read -r PROJET BATIMENT < <(psql_run "SELECT p.unique_id || ' ' || b.unique_id FROM projets p
  JOIN occupations_batiments o ON o.projet_id = p.id JOIN batiments b ON b.id = o.batiment_id
  WHERE p.farm_id = $FARM_ID AND p.removed = false ORDER BY p.id LIMIT 1")
[ -n "${PROJET:-}" ] || { echo "ECHEC  aucun projet avec poulailler dans la ferme de l'admin"; exit 1; }
SUFFIXE="$(uuid | cut -c1-8)"
AUJ="$(date +%F)"

api POST /magasins/create "{\"nom\":\"Boutique idem $SUFFIXE\",\"type\":\"VENTE\"}"
BOUTIQUE="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Stock idem $SUFFIXE\",\"type\":\"STOCKAGE\",\"magasinVenteParDefautUniqueId\":\"$BOUTIQUE\"}"
STOCK="$(jval "d['data']['uniqueId']")"
api POST /clients/create "{\"nom\":\"Client idem $SUFFIXE\",\"telephone\":\"7$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\"}"
CLIENT="$(jval "d['data']['uniqueId']")"
check "préparation : magasins et client créés" "code in (200, 201) and d['data']['uniqueId']"

collecte_corps() { # $1 = jour, $2 = œufs collectés
  echo "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"magasinStockageUniqueId\":\"$STOCK\",\"date\":\"$1\",\"oeufsCollectes\":$2,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
}

# ---------------------------------------------------------------------------
echo "== 1. Collecte envoyée deux fois avec la même clé"
cas_idem "collecte" /collectes-oeufs/create "$(collecte_corps "$(jour_au_hasard)" 20)" collectes_oeufs
CLE_COLLECTE="$(psql_run "SELECT cle FROM idempotency_requests ORDER BY id DESC LIMIT 1")"
check_eq "enregistrement TERMINE, statut 201, ferme et utilisateur renseignés" "TERMINE|201|$FARM_ID|oui" \
  "$(psql_run "SELECT statut || '|' || statut_reponse || '|' || farm_id || '|' || CASE WHEN utilisateur <> '' THEN 'oui' ELSE 'non' END FROM idempotency_requests WHERE cle = '$CLE_COLLECTE'")"

echo "== 2. Cinq envois simultanés de la même saisie"
CLE="$(uuid)"; CORPS="$(collecte_corps "$(jour_au_hasard)" 10)"
AVANT="$(compter collectes_oeufs)"
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/collectes-oeufs/create" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $CLE" -d "$CORPS" > "$TMP/par$i" &
done
wait
CODES="$(cat "$TMP"/par* | sort | tr '\n' ' ')"
check_eq "une seule collecte créée malgré 5 envois simultanés (codes : $CODES)" "$((AVANT + 1))" "$(compter collectes_oeufs)"
if python3 -c 'import sys; c=sys.argv[1].split(); sys.exit(0 if "201" in c and all(x in ("201","409") for x in c) else 1)' "$CODES"
then ok "codes simultanés : 201 (exécuté ou rejoué) ou 409 (en cours)"; else echec "codes simultanés inattendus : $CODES"; fi
api POST /collectes-oeufs/create "$CORPS" "$CLE"
check "renvoi après coup : 201 rejoué" "code == 201 and rejoue"

echo "== 3. Même clé, autre saisie"
api POST /collectes-oeufs/create "$(collecte_corps "$(jour_au_hasard)" 11)" "$CLE"
check "autre corps avec une clé déjà utilisée : 422" "code == 422 and 'Clé déjà utilisée pour une autre saisie' in (d.get('errors') or [''])[0]"
api POST /mortalites/create "{\"projetUniqueId\":\"$PROJET\",\"date\":\"$AUJ\",\"nombreMorts\":1}" "$CLE"
check "autre chemin avec une clé déjà utilisée : 422" "code == 422"
check_eq "aucune ligne créée par ces refus" "$((AVANT + 1))" "$(compter collectes_oeufs)"
# Ordre des champs et espaces différents : même saisie (JSON normalisé).
CORPS_REORDONNE="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(json.dumps(dict(reversed(list(d.items()))), indent=2))' "$CORPS")"
api POST /collectes-oeufs/create "$CORPS_REORDONNE" "$CLE"
check "même saisie, champs dans un autre ordre : rejouée (pas 422)" "code == 201 and rejoue"

echo "== 4. Refus métier 400 rejoué, pas ré-exécuté"
CLE="$(uuid)"
api POST /ventes-oeufs/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$BOUTIQUE\",\"quantiteOeufs\":5,\"prixUnitaire\":100,\"montant\":500}" "$CLE"
check "vente au-delà du stock de la boutique vide : 400" "code == 400 and not rejoue"
MSG400="$(cat "$TMP/body")"
# Du stock arrive entre-temps : une ré-exécution réussirait, le rejeu doit rester 400.
api POST /collectes-oeufs/create "$(collecte_corps "$(jour_au_hasard)" 20)" ""
check "collecte (transfert auto vers la boutique)" "code == 201"
api POST /ventes-oeufs/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$BOUTIQUE\",\"quantiteOeufs\":5,\"prixUnitaire\":100,\"montant\":500}" "$CLE"
check "renvoi du refus : 400 rejoué, bien que le stock suffise désormais" "code == 400 and rejoue"
if [ "$(cat "$TMP/body")" = "$MSG400" ]; then ok "message du 400 rejoué identique"; else echec "message du 400 rejoué différent"; fi
check_eq "enregistrement du 400 conservé (TERMINE)" "TERMINE|400" "$(idem_sql "$CLE" "statut || '|' || statut_reponse")"

echo "== 5. Erreur serveur 5xx non mémorisée"
psql_run "CREATE OR REPLACE FUNCTION idem_panne() RETURNS trigger AS \$\$ BEGIN RAISE EXCEPTION 'panne simulée'; END \$\$ LANGUAGE plpgsql" >/dev/null
psql_run "DROP TRIGGER IF EXISTS idem_panne ON collectes_oeufs; CREATE TRIGGER idem_panne BEFORE INSERT ON collectes_oeufs FOR EACH ROW EXECUTE FUNCTION idem_panne()" >/dev/null 2>&1
CLE="$(uuid)"; CORPS="$(collecte_corps "$(jour_au_hasard)" 10)"
AVANT="$(compter collectes_oeufs)"
api POST /collectes-oeufs/create "$CORPS" "$CLE"
check "panne en base : 500" "code >= 500"
check_eq "aucun enregistrement d'idempotence gardé après le 500" "0" "$(idem_sql "$CLE" "count(*)")"
psql_run "DROP TRIGGER idem_panne ON collectes_oeufs; DROP FUNCTION idem_panne()" >/dev/null
api POST /collectes-oeufs/create "$CORPS" "$CLE"
check "renvoi après la panne : ré-exécuté (201, non rejoué)" "code == 201 and not rejoue"
check_eq "la collecte existe une fois" "$((AVANT + 1))" "$(compter collectes_oeufs)"

echo "== 6. Chaque création mobile"
cas_idem "vente d'œufs" /ventes-oeufs/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$BOUTIQUE\",\"clientUniqueId\":\"$CLIENT\",\"quantiteOeufs\":2,\"prixUnitaire\":100,\"montant\":200}" ventes_oeufs
cas_idem "paiement client" /paiements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"montant\":150,\"mode\":\"ESPECES\"}" paiements_client
cas_idem "transaction" /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Idempotence $SUFFIXE\",\"montant\":500,\"categorie\":\"Divers\"}" transactions
api POST "/alimentations/create/$PROJET" "{\"nomAliment\":\"Maïs idem\",\"sac\":1,\"quantiteKg\":50,\"coutTotal\":10000,\"dateDistribution\":\"$AUJ\"}" ""
check "achat d'aliment (stock pour la consommation)" "code in (200, 201)"
cas_idem "consommation d'aliment" /consommations-aliment/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"quantiteKg\":1}" consommations_aliment
cas_idem "mortalité" /mortalites/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"nombreMorts\":1,\"cause\":\"Idempotence\"}" mortalites
cas_idem "réforme" /reformes/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"nombreSujets\":1}" reformes
cas_idem "commande" /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"OEUFS\",\"quantite\":10,\"montantEstime\":1000}" commandes

echo "== 7. Sans en-tête : comportement inchangé"
AVANT="$(compter mortalites)"
CORPS="{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"nombreMorts\":1,\"cause\":\"Sans clé\"}"
api POST /mortalites/create "$CORPS" ""; api POST /mortalites/create "$CORPS" ""
check_eq "deux envois sans clé = deux mortalités" "$((AVANT + 2))" "$(compter mortalites)"
api POST /mortalites/create "$CORPS" "clé invalide !"
check "clé mal formée : 400" "code == 400"

echo "== 8. Réponses non définitives (404, 409) : clé libérée"
CLE="$(uuid)"
api POST /route-inexistante/create '{"a":1}' "$CLE"
check "route absente : 404" "code == 404"
check_eq "404 non mémorisé (clé libérée)" "0" "$(idem_sql "$CLE" "count(*)")"
# 409 métier : conflit d'identifiant simulé à l'enregistrement d'une session de pesée.
psql_run "CREATE OR REPLACE FUNCTION idem_conflit() RETURNS trigger AS \$\$ BEGIN RAISE EXCEPTION 'conflit simulé' USING ERRCODE = 'unique_violation'; END \$\$ LANGUAGE plpgsql" >/dev/null
psql_run "DROP TRIGGER IF EXISTS idem_conflit ON sessions_pesee; CREATE TRIGGER idem_conflit BEFORE INSERT ON sessions_pesee FOR EACH ROW EXECUTE FUNCTION idem_conflit()" >/dev/null 2>&1
CLE="$(uuid)"; SID="$(uuid)"
CORPS="{\"uniqueId\":\"$SID\",\"projetUniqueId\":\"$PROJET\",\"nombreParDefaut\":3,\"dateDebut\":\"2026-09-25T08:00:00\",\"statut\":\"EN_COURS\",\"pesees\":[]}"
sync_pesee() {
  : > "$TMP/headers"
  curl -s -D "$TMP/headers" -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/pesees/sessions/sync" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'X-Pesee-Contrat: 2' \
    -H "Idempotency-Key: $CLE" -d "$CORPS" > "$TMP/code"
}
sync_pesee
check "conflit d'enregistrement : 409" "code == 409"
check_eq "409 non mémorisé (clé libérée)" "0" "$(idem_sql "$CLE" "count(*)")"
psql_run "DROP TRIGGER idem_conflit ON sessions_pesee; DROP FUNCTION idem_conflit()" >/dev/null
sync_pesee
check "renvoi après le conflit : exécuté (200, non rejoué)" "code == 200 and not rejoue"
check_eq "session de pesée créée une fois" "1" "$(psql_run "SELECT count(*) FROM sessions_pesee WHERE unique_id = '$SID'")"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
