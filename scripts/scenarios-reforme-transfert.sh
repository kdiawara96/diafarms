#!/usr/bin/env bash
# Réforme -> transfert automatique vers un point de vente, reprise des réformes sans
# transfert, idempotence (seuls les succès sont rejoués).
#
# Chaque passage crée sa propre ferme (SQL : ferme, ADMIN cloné de admin@t.local,
# poulailler, projet de 500 sujets) pour maîtriser le nombre de points de vente.
# 1. Ferme sans point de vente : réforme enregistrée sans transfert.
# 2. Un seul point de vente : ancien corps (sans magasinVenteUniqueId) accepté, transfert
#    lié créé, vente de réformes possible.
# 3. Modification/suppression/restauration : le transfert suit ; jamais de stock négatif
#    au point de vente (baisse/suppression refusée au-delà de ce qui n'est pas vendu).
# 4. Plusieurs points de vente sans désignation : 400 « Choisissez le point de vente des
#    réformés » ; choix explicite ; déplacement ; désignation commune des magasins de
#    stockage = point de vente par défaut ; point de vente d'une autre ferme refusé.
# 5. Reprise POST /admin/reformes/transferts-manquants : simulation, exécution, second
#    passage sans effet, droits (autre ferme 403, COMPTABLE 403, SUPER_ADMIN).
# 6. Idempotence : un 400 (stock insuffisant) n'est pas mémorisé ; même clé après ajout
#    de stock -> 201 exécuté ; ancien 400 mémorisé (avant la règle) -> ré-exécuté.
#
# Pré-requis : Postgres + backend démarrés, base seedée par scenarios-circuit-client.sh.
# Variables : BASE, PGHOST, PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen),
# ADMIN_EMAIL / ADMIN_PWD, SUPER_ID / SUPER_PWD (superadmin / change-me).
# Sortie : une ligne OK/ECHEC par assertion ; code 0 si tout est OK.
set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@t.local}"
ADMIN_PWD="${ADMIN_PWD:-Test1234!}"
SUPER_ID="${SUPER_ID:-superadmin}"
SUPER_PWD="${SUPER_PWD:-change-me}"

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
err = " ".join((d.get("errors") or []) if isinstance(d, dict) else [])
ok = False
try:
    ok = bool(eval(expr, {"d": d, "code": code, "rejoue": rejoue, "err": err, "len": len, "sum": sum}))
except Exception as e:
    print("   exception:", e, file=sys.stderr)
if not ok:
    print("   code HTTP:", code, "rejoué:", rejoue, "réponse:", json.dumps(d, ensure_ascii=False)[:700], file=sys.stderr)
sys.exit(0 if ok else 1)
PY
  then ok "$label"; else echec "$label"; fi
}

check_sql() { # "libellé" "requête" "attendu"
  local got; got="$(psql_run "$2")"
  if [ "$got" = "$3" ]; then ok "$1"; else echec "$1 (attendu « $3 », obtenu « $got »)"; fi
}

# api MÉTHODE CHEMIN CORPS [CLÉ] avec $TOKEN
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
login_web() {
  curl -s -i -X POST "$BASE/auth" -H 'X-Client-Type: web' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | sed -n 's/^[Ss]et-[Cc]ookie: diafarms_access_token=\([^;]*\).*/\1/p' | head -1
}

AUJ="$(date +%Y-%m-%d)"
HIER="$(date -d yesterday +%Y-%m-%d)"
SUF="$(uuid | cut -c1-8)"

# ---------------------------------------------------------------------------
# Préparation : ferme neuve
# ---------------------------------------------------------------------------
ADMIN_TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$ADMIN_TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }
FARM_PRINCIPALE="$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
FARM_UID="reft-ferme-$SUF"
psql_run "INSERT INTO farms (unique_id, nom) VALUES ('$FARM_UID', 'Ferme réforme $SUF')" >/dev/null
F="$(psql_run "SELECT id FROM farms WHERE unique_id = '$FARM_UID'")"
psql_run "INSERT INTO utilisateurs (unique_id, username, email, full_name, password, farm_id, statut, removed, archive, must_change_password, token_version, telephone, code_change_password, consultation_seule, created_at)
  SELECT 'reft-admin-$SUF', 'reftadmin$SUF', 'reft-$SUF@t.local', 'AdminReforme', password, $F, true, false, false, false, 0, '7$SUF', 0, false, now()
  FROM utilisateurs WHERE email = '$ADMIN_EMAIL'" >/dev/null
psql_run "INSERT INTO roles_users (id_utilisateurs, id_roles) SELECT u.id, r.id FROM utilisateurs u, roles r
  WHERE u.unique_id = 'reft-admin-$SUF' AND r.role = 'ADMIN'" >/dev/null
psql_run "INSERT INTO batiments (unique_id, nom, capacite, statut, farm_id, removed, archive)
  VALUES ('reft-bat-$SUF', 'Poulailler réforme $SUF', 1000, 'OCCUPE', $F, false, false)" >/dev/null
psql_run "INSERT INTO projets (unique_id, code, titre, objectif, race_id, farm_id, nb_sujets, date_debut, removed, archive)
  SELECT 'reft-projet-$SUF', 'RF-$SUF', 'Projet réforme $SUF', p.objectif, p.race_id, $F, 500, DATE '2026-01-01', false, false
  FROM projets p WHERE p.farm_id = $FARM_PRINCIPALE ORDER BY p.id LIMIT 1" >/dev/null
psql_run "INSERT INTO occupations_batiments (date_entree, nb_sujets_dans_batiment, batiment_id, projet_id)
  SELECT DATE '2026-01-01', 500, b.id, p.id FROM batiments b, projets p
  WHERE b.unique_id = 'reft-bat-$SUF' AND p.unique_id = 'reft-projet-$SUF'" >/dev/null
PROJET="reft-projet-$SUF"
PROJET_ID="$(psql_run "SELECT id FROM projets WHERE unique_id = '$PROJET'")"

TOKEN="$(login "reft-$SUF@t.local" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin de la ferme de test"; exit 1; }
FTOKEN="$TOKEN"

reforme() { # nombre [magasinVenteUniqueId] [date] [clé]
  local corps="{\"projetUniqueId\":\"$PROJET\",\"date\":\"${3:-$AUJ}\",\"nombreSujets\":$1${2:+,\"magasinVenteUniqueId\":\"$2\"}}"
  api POST /reformes/create "$corps" "${4:-}"
}
stock() { # magasin -> reformeDisponible
  api GET "/magasins/$1/stock" ""
  jval "d['data']['reformeDisponible']"
}
transfert_sql() { # uniqueId réforme -> "magasin_nom|quantite|removed|date"
  psql_run "SELECT m.nom || '|' || t.quantite || '|' || t.removed || '|' || t.date FROM magasin_transferts t
    JOIN magasins_vente m ON m.id = t.magasin_id JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$1'"
}

# ---------------------------------------------------------------------------
echo "== 1. Ferme sans point de vente"
reforme 2
check "réforme acceptée sans point de vente dans la ferme" "code == 201 and d['data']['magasinVenteUniqueId'] is None"
R0="$(jval "d['data']['uniqueId']")"
check_sql "aucun transfert créé" "SELECT count(*) FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R0'" "0"

# ---------------------------------------------------------------------------
echo "== 2. Un seul point de vente : ancien corps accepté"
api POST /magasins/create "{\"nom\":\"Boutique A $SUF\",\"type\":\"VENTE\"}"
A="$(jval "d['data']['uniqueId']")"
check "point de vente A créé" "code in (200, 201)"
reforme 10
check "réforme de 10 sans magasinVenteUniqueId (ancien téléphone) : 201 vers A" \
  "code == 201 and d['data']['magasinVenteUniqueId'] == '$A' and d['data']['magasinVenteNom'] == 'Boutique A $SUF'"
R1="$(jval "d['data']['uniqueId']")"
check_sql "transfert lié : 10 sujets vers A, date de la réforme" "$(echo "SELECT m.nom || '|' || t.quantite || '|' || t.removed || '|' || t.date FROM magasin_transferts t JOIN magasins_vente m ON m.id = t.magasin_id JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R1'")" "Boutique A $SUF|10|false|$AUJ"
check_sql "transfert de type REFORME, projet et ferme de la réforme" \
  "SELECT t.type || '|' || (t.projet_id = $PROJET_ID) || '|' || (t.farm_id = $F) FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R1'" "REFORME|true|true"
[ "$(stock "$A")" = "10" ] && ok "stock de réformés de A = 10" || echec "stock de réformés de A = 10 (obtenu $(jval "d['data']['reformeDisponible']"))"
api GET "/magasin-transferts/disponible?projetUniqueId=$PROJET&type=REFORME" ""
api POST /ventes-reforme/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$A\",\"nombreSujets\":4,\"prixUnitaire\":2500,\"montant\":10000,\"montantRapporte\":10000}"
check "vente de 4 réformés depuis A : 201" "code == 201"
[ "$(stock "$A")" = "6" ] && ok "stock de A = 6 après la vente" || echec "stock de A = 6 après la vente"
api POST /ventes-reforme/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$A\",\"nombreSujets\":7,\"prixUnitaire\":2500,\"montant\":17500,\"montantRapporte\":17500}"
check "vente de 7 (> 6 en stock) : 400" "code == 400 and 'insuffisant' in err"

# ---------------------------------------------------------------------------
echo "== 3. Modification, suppression, restauration"
api PUT "/reformes/update/$R1" '{"nombreSujets":6}'
check "réforme 10 -> 6 (4 vendus, 6 en stock) : 200" "code == 200 and d['data']['nombreSujets'] == 6"
check_sql "transfert lié ramené à 6" "SELECT t.quantite FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R1'" "6"
[ "$(stock "$A")" = "2" ] && ok "stock de A = 2" || echec "stock de A = 2"
api PUT "/reformes/update/$R1" '{"nombreSujets":3}'
check "réforme 6 -> 3 alors que 4 sont vendus : 400 clair" "code == 400 and 'déjà été vendus' in err and 'Boutique A $SUF' in err"
check_sql "réforme et transfert inchangés après le refus" \
  "SELECT r.nombre_sujets || '|' || t.quantite FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R1'" "6|6"
api PUT "/reformes/deleteOrRecover/$R1" ""
check "suppression d'une réforme déjà vendue : 400" "code == 400 and 'supprimer cette réforme' in err"
check_sql "réforme toujours active" "SELECT removed FROM reformes WHERE unique_id = '$R1'" "f"
api PUT "/reformes/update/$R1" "{\"nombreSujets\":8,\"date\":\"$HIER\"}"
check "réforme 6 -> 8 et date d'hier : 200" "code == 200"
check_sql "transfert lié : 8 sujets, date d'hier" "SELECT t.quantite || '|' || t.date FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R1'" "8|$HIER"

reforme 5
R2="$(jval "d['data']['uniqueId']")"
[ "$(stock "$A")" = "9" ] && ok "réforme R2 de 5 : stock de A = 9" || echec "réforme R2 de 5 : stock de A = 9"
api PUT "/reformes/deleteOrRecover/$R2" ""
check "suppression de R2 (rien vendu) : 200" "code == 200"
check_sql "transfert de R2 supprimé avec elle" "SELECT t.removed FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R2'" "t"
[ "$(stock "$A")" = "4" ] && ok "stock de A = 4 après suppression" || echec "stock de A = 4 après suppression"
api PUT "/reformes/deleteOrRecover/$R2" ""
check "restauration de R2 : 200" "code == 200"
check_sql "transfert de R2 restauré" "SELECT t.removed || '|' || t.quantite FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R2'" "false|5"
[ "$(stock "$A")" = "9" ] && ok "stock de A = 9 après restauration" || echec "stock de A = 9 après restauration"
api GET "/magasin-transferts/disponible?projetUniqueId=$PROJET&type=REFORME" ""
check_sql "plus rien à transférer à la main depuis le projet (hors réforme R0 sans point de vente)" \
  "SELECT (SELECT COALESCE(SUM(nombre_sujets),0) FROM reformes WHERE projet_id = $PROJET_ID AND removed = false) - (SELECT COALESCE(SUM(quantite),0) FROM magasin_transferts WHERE projet_id = $PROJET_ID AND removed = false)" "2"

# ---------------------------------------------------------------------------
echo "== 4. Plusieurs points de vente"
api POST /magasins/create "{\"nom\":\"Boutique B $SUF\",\"type\":\"VENTE\"}"
B="$(jval "d['data']['uniqueId']")"
reforme 3
check "deux points de vente, aucun désigné, ancien corps : 400" "code == 400 and 'Choisissez le point de vente des réformés' in err"
reforme 3 "$B"
check "même réforme avec magasinVenteUniqueId = B : 201" "code == 201 and d['data']['magasinVenteUniqueId'] == '$B'"
R3="$(jval "d['data']['uniqueId']")"
[ "$(stock "$B")" = "3" ] && ok "stock de B = 3" || echec "stock de B = 3"
api PUT "/reformes/update/$R2" "{\"magasinVenteUniqueId\":\"$B\"}"
check "R2 déplacée de A vers B : 200" "code == 200 and d['data']['magasinVenteUniqueId'] == '$B'"
[ "$(stock "$A")" = "4" ] && ok "stock de A = 4" || echec "stock de A = 4"
[ "$(stock "$B")" = "8" ] && ok "stock de B = 8" || echec "stock de B = 8"
api PUT "/reformes/update/$R1" "{\"magasinVenteUniqueId\":\"$B\"}"
check "déplacer R1 (4 de ses sujets déjà vendus depuis A) : 400" "code == 400 and 'changer le point de vente' in err"
api POST /magasins/create "{\"nom\":\"Stockage $SUF\",\"type\":\"STOCKAGE\",\"magasinVenteParDefautUniqueId\":\"$B\"}"
check "magasin de stockage désignant B" "code in (200, 201)"
reforme 1
check "désignation commune des magasins de stockage : ancien corps -> B" "code == 201 and d['data']['magasinVenteUniqueId'] == '$B'"
AUTRE_PDV="$(psql_run "SELECT unique_id FROM magasins_vente WHERE farm_id = $FARM_PRINCIPALE AND type = 'VENTE' AND removed = false LIMIT 1")"
reforme 1 "$AUTRE_PDV"
check "point de vente d'une autre ferme : 400 introuvable" "code == 400 and 'Point de vente introuvable' in err"
reforme 1 "$(psql_run "SELECT unique_id FROM magasins_vente WHERE unique_id IN (SELECT m.unique_id FROM magasins_vente m WHERE m.farm_id = $F AND m.type = 'STOCKAGE') LIMIT 1")"
check "magasin de stockage comme point de vente : 400" "code == 400 and 'Point de vente introuvable' in err"

# ---------------------------------------------------------------------------
echo "== 5. Reprise des réformes sans transfert"
# Réformes d'avant le transfert automatique : 7 (ancienne) et 3 (récente), aucune
# rattachée à un point de vente ; plus un transfert manuel de 2 sujets.
psql_run "INSERT INTO reformes (unique_id, date, nombre_sujets, projet_id, farm_id, batiment_id, removed, archive, created_at)
  SELECT 'reft-legacy1-$SUF', DATE '2026-03-01', 7, $PROJET_ID, $F, b.id, false, false, now() FROM batiments b WHERE b.unique_id = 'reft-bat-$SUF'" >/dev/null
psql_run "INSERT INTO reformes (unique_id, date, nombre_sujets, projet_id, farm_id, batiment_id, removed, archive, created_at)
  SELECT 'reft-legacy2-$SUF', DATE '2026-04-01', 3, $PROJET_ID, $F, b.id, false, false, now() FROM batiments b WHERE b.unique_id = 'reft-bat-$SUF'" >/dev/null
api POST /magasin-transferts/create "{\"magasinUniqueId\":\"$A\",\"projetUniqueId\":\"$PROJET\",\"type\":\"REFORME\",\"quantite\":2,\"date\":\"$AUJ\"}"
check "transfert manuel de 2 sujets vers A" "code in (200, 201)"
# À transférer : R0 (2) + 7 + 3 - 2 manuels = 10.
NB_AVANT="$(psql_run "SELECT count(*) FROM magasin_transferts WHERE farm_id = $F")"
api POST "/admin/reformes/transferts-manquants" ""
check "simulation par l'ADMIN de la ferme : 10 à transférer vers B (désigné)" \
  "code == 200 and d['data']['execute'] == False and d['data']['totalATransferer'] == 10 and d['data']['transfertsCrees'] == 0 and d['data']['pointDeVenteUniqueId'] == '$B' and d['data']['projets'][0]['manquant'] == 10"
check_sql "simulation : rien écrit" "SELECT count(*) FROM magasin_transferts WHERE farm_id = $F" "$NB_AVANT"
api POST "/admin/reformes/transferts-manquants?farmUniqueId=$FARM_UID&executer=true&magasinVenteUniqueId=$A" ""
check "exécution vers A : 10 sujets, 3 transferts (récentes d'abord)" \
  "code == 200 and d['data']['execute'] == True and d['data']['totalTransfere'] == 10 and d['data']['transfertsCrees'] == 3 and d['data']['pointDeVenteUniqueId'] == '$A'"
check_sql "réforme récente (3) entièrement liée" "SELECT t.quantite || '|' || t.removed FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = 'reft-legacy2-$SUF'" "3|false"
check_sql "réforme ancienne (7) liée pour 5 (2 couverts par le transfert manuel)" "SELECT t.quantite FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = 'reft-legacy1-$SUF'" "5"
check_sql "point de vente renseigné sur les réformes reprises" "SELECT count(*) FROM reformes r JOIN magasins_vente m ON m.id = r.magasin_vente_id WHERE r.unique_id IN ('reft-legacy1-$SUF','reft-legacy2-$SUF','$R0') AND m.unique_id = '$A'" "3"
check_sql "projet : réformés = transférés" \
  "SELECT (SELECT SUM(nombre_sujets) FROM reformes WHERE projet_id = $PROJET_ID AND removed = false) = (SELECT SUM(quantite) FROM magasin_transferts WHERE projet_id = $PROJET_ID AND removed = false)" "t"
api POST "/admin/reformes/transferts-manquants?executer=true&magasinVenteUniqueId=$A" ""
check "second passage : rien à transférer (idempotent)" "code == 200 and d['data']['totalATransferer'] == 0 and d['data']['transfertsCrees'] == 0"
[ "$(stock "$A")" = "16" ] && ok "stock de A = 4 + 2 manuels + 10 repris = 16" || echec "stock de A = 16 (obtenu $(jval "d['data']['reformeDisponible']"))"
api POST /ventes-reforme/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$A\",\"nombreSujets\":16,\"prixUnitaire\":2000,\"montant\":32000,\"montantRapporte\":32000}"
check "vente de tout le stock repris depuis A : 201" "code == 201"
api PUT "/reformes/update/reft-legacy2-$SUF" '{"nombreSujets":2}'
check "baisse d'une réforme reprise dont les sujets sont vendus : 400" "code == 400 and 'déjà été vendus' in err"

TOKEN="$ADMIN_TOKEN"
api POST "/admin/reformes/transferts-manquants?farmUniqueId=$FARM_UID" ""
check "ADMIN d'une autre ferme : 403" "code == 403"
COMPTA_TOKEN="$(login "${COMPTA_EMAIL:-compta@t.local}" "${COMPTA_PWD:-Test1234!}")"
if [ -n "$COMPTA_TOKEN" ]; then
  TOKEN="$COMPTA_TOKEN"
  api POST "/admin/reformes/transferts-manquants" ""
  check "COMPTABLE : 403" "code == 403"
fi
SUPER_TOKEN="$(login_web "$SUPER_ID" "$SUPER_PWD")"
[ -n "$SUPER_TOKEN" ] || SUPER_TOKEN="$(login "$SUPER_ID" "$SUPER_PWD")"
if [ -n "$SUPER_TOKEN" ]; then
  TOKEN="$SUPER_TOKEN"
  api POST "/admin/reformes/transferts-manquants" ""
  check "SUPER_ADMIN sans farmUniqueId : 400" "code == 400 and 'farmUniqueId' in err"
  api POST "/admin/reformes/transferts-manquants?farmUniqueId=$FARM_UID" ""
  check "SUPER_ADMIN, simulation sur la ferme de test : 0 à transférer" "code == 200 and d['data']['totalATransferer'] == 0 and d['data']['farmUniqueId'] == '$FARM_UID'"
else
  echec "connexion SUPER_ADMIN ($SUPER_ID)"
fi
TOKEN="$FTOKEN"

# ---------------------------------------------------------------------------
echo "== 6. Idempotence : seuls les succès sont mémorisés"
CLE="$(uuid)"
VENTE="{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$A\",\"nombreSujets\":3,\"prixUnitaire\":2000,\"montant\":6000,\"montantRapporte\":6000}"
api POST /ventes-reforme/create "$VENTE" "$CLE"
check "vente sans stock à A : 400" "code == 400 and not rejoue and 'insuffisant' in err"
check_sql "le 400 n'est pas mémorisé (clé libérée)" "SELECT count(*) FROM idempotency_requests WHERE cle = '$CLE'" "0"
api POST /ventes-reforme/create "$VENTE" "$CLE"
check "renvoi immédiat : 400 ré-exécuté (pas rejoué)" "code == 400 and not rejoue"
reforme 3 "$A"
check "réforme de 3 vers A (le stock arrive)" "code == 201"
api POST /ventes-reforme/create "$VENTE" "$CLE"
check "même clé après correction du stock : 201 exécuté" "code == 201 and not rejoue"
api POST /ventes-reforme/create "$VENTE" "$CLE"
check "renvoi du succès : 201 rejoué" "code == 201 and rejoue"
check_sql "une seule vente créée" "SELECT count(*) FROM ventes_reforme v JOIN magasins_vente m ON m.id = v.magasin_id WHERE m.unique_id = '$A' AND v.montant = 6000 AND v.removed = false" "1"

# Ancien 400 mémorisé par la version précédente du serveur : ré-exécuté, plus rejoué.
CLE2="$(uuid)"
reforme 2 "$A"
psql_run "INSERT INTO idempotency_requests (cle, utilisateur, farm_id, methode_chemin, hash_corps, statut, statut_reponse, corps_reponse, type_contenu, created_at)
  VALUES ('$CLE2', 'reftadmin$SUF', $F, 'POST /diafarms/api/v1/ventes-reforme/create', 'ancien', 'TERMINE', 400,
  '{\"message\":\"Données invalides\",\"status\":400,\"errors\":[\"Stock de sujets réformés insuffisant dans ce magasin (0 sujet(s) restants).\"]}', 'application/json', now())" >/dev/null
api POST /ventes-reforme/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$A\",\"nombreSujets\":2,\"prixUnitaire\":2000,\"montant\":4000,\"montantRapporte\":4000}" "$CLE2"
check "ancien 400 mémorisé : la requête est ré-exécutée (201)" "code == 201 and not rejoue"
check_sql "clé désormais mémorisée avec le succès" "SELECT statut || '|' || statut_reponse FROM idempotency_requests WHERE cle = '$CLE2'" "TERMINE|201"

# Réforme envoyée par le téléphone avec une clé : le 400 « Choisissez » n'est pas
# mémorisé, le renvoi avec un point de vente passe avec la même clé.
CLE3="$(uuid)"
reforme 1 "" "$AUJ" "$CLE3"
check "réforme sans point de vente (2 points de vente, B désigné) : 201" "code == 201"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
