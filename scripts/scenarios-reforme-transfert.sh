#!/usr/bin/env bash
# Réformés : même chemin que les œufs. Réforme -> magasin de STOCKAGE -> point de vente,
# automatiquement si le magasin de stockage a un point de vente par défaut, sinon par un
# transfert manuel depuis ce magasin. Reprise des réformes anciennes, idempotence.
#
# Chaque passage crée sa propre ferme (SQL : ferme, ADMIN cloné de admin@t.local,
# poulailler, projet de 500 sujets) pour maîtriser le nombre de magasins.
# 1. Ferme sans magasin de stockage : réforme acceptée (ancien téléphone), sans magasin.
# 2. Magasin de stockage S1 sans point de vente par défaut : les réformés y restent ;
#    vente refusée ; transfert manuel S1 -> A ; baisse/suppression refusées au-delà de ce
#    qui reste dans S1.
# 3. Magasin S2 avec point de vente par défaut B : transfert automatique ; modification,
#    suppression, restauration, changement de magasin ; jamais de stock négatif.
# 4. Ancien téléphone avec plusieurs magasins : magasin de la dernière collecte du projet ;
#    ancien contrat (point de vente) ; magasins invalides refusés.
# 5. Reprise POST /admin/reformes/transferts-manquants : simulation, exécution, second
#    passage sans effet, réformes déjà transférées à la main laissées telles quelles, droits.
# 6. Idempotence : un 400 (stock insuffisant) n'est pas mémorisé.
# 7. Restauration d'une vente (réforme, œufs) refusée si le stock ne la couvre plus ;
#    réforme du 2 octobre 2026 envoyée directement à un point de vente (ancien modèle).
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

reforme() { # nombre [magasinStockageUniqueId] [date] [clé] [champ supplémentaire json]
  local corps="{\"projetUniqueId\":\"$PROJET\",\"date\":\"${3:-$AUJ}\",\"nombreSujets\":$1${2:+,\"magasinStockageUniqueId\":\"$2\"}${5:+,$5}}"
  api POST /reformes/create "$corps" "${4:-}"
}
stock() { # point de vente -> reformeDisponible
  api GET "/magasins/$1/stock" ""
  jval "d['data']['reformeDisponible']"
}
stock_s() { # magasin de stockage -> réformés disponibles
  api GET "/magasin-transferts/disponible-batiment?magasinStockageUniqueId=$1&type=REFORME" ""
  jval "d['data']"
}
egal() { # libellé attendu obtenu
  [ "$2" = "$3" ] && ok "$1" || echec "$1 (attendu « $2 », obtenu « $3 »)"
}
lie() { # uniqueId réforme -> "pdv|stockage|quantite|removed"
  psql_run "SELECT m.nom || '|' || COALESCE(s.nom, '-') || '|' || t.quantite || '|' || t.removed FROM magasin_transferts t
    JOIN magasins_vente m ON m.id = t.magasin_id LEFT JOIN magasins_vente s ON s.id = t.magasin_stockage_id
    JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$1'"
}
vente() { # point de vente nombre
  api POST /ventes-reforme/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$1\",\"nombreSujets\":$2,\"prixUnitaire\":2500,\"montant\":$(( $2 * 2500 )),\"montantRapporte\":$(( $2 * 2500 ))}" "${3:-}"
}
transfert() { # stockage point_de_vente quantite
  api POST /magasin-transferts/create "{\"magasinUniqueId\":\"$2\",\"magasinStockageUniqueId\":\"$1\",\"type\":\"REFORME\",\"quantite\":$3,\"date\":\"$AUJ\"}"
}

# ---------------------------------------------------------------------------
echo "== 1. Ferme sans magasin de stockage"
reforme 2
check "ancien corps sans magasin, aucun magasin de stockage : 201 sans magasin" \
  "code == 201 and d['data']['magasinStockageUniqueId'] is None and d['data']['magasinVenteUniqueId'] is None"
R0="$(jval "d['data']['uniqueId']")"
check_sql "aucun transfert créé" "SELECT count(*) FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R0'" "0"

# ---------------------------------------------------------------------------
echo "== 2. Magasin de stockage S1 sans point de vente par défaut"
api POST /magasins/create "{\"nom\":\"Boutique A $SUF\",\"type\":\"VENTE\"}"
A="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Stockage 1 $SUF\",\"type\":\"STOCKAGE\"}"
S1="$(jval "d['data']['uniqueId']")"
check "S1 créé" "code in (200, 201)"
reforme 10
check "ancien corps (téléphone 1.34), un seul magasin de stockage : 201 vers S1" \
  "code == 201 and d['data']['magasinStockageUniqueId'] == '$S1' and d['data']['magasinStockageNom'] == 'Stockage 1 $SUF' and d['data']['magasinVenteUniqueId'] is None"
R1="$(jval "d['data']['uniqueId']")"
check_sql "pas de transfert automatique (S1 sans point de vente par défaut)" "SELECT count(*) FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R1'" "0"
egal "réformés au magasin S1 = 10" "10" "$(stock_s "$S1")"
egal "point de vente A vide" "0" "$(stock "$A")"
vente "$A" 1
check "vente depuis A sans réformés au point de vente : 400" "code == 400 and 'insuffisant' in err"
transfert "$S1" "$A" 11
check "transfert de 11 depuis S1 (10 dedans) : 400" "code == 400 and 'Réformés insuffisants' in err and '10 sujet(s)' in err"
transfert "$S1" "$A" 6
check "transfert manuel de 6 S1 -> A : 201" "code in (200, 201) and d['data'][0]['quantite'] == 6"
check_sql "transfert manuel : type REFORME, magasin de stockage S1, projet" \
  "SELECT t.type || '|' || s.unique_id || '|' || (t.projet_id = $PROJET_ID) FROM magasin_transferts t JOIN magasins_vente s ON s.id = t.magasin_stockage_id JOIN magasins_vente m ON m.id = t.magasin_id WHERE m.unique_id = '$A'" "REFORME|$S1|true"
egal "S1 = 4" "4" "$(stock_s "$S1")"
egal "A = 6" "6" "$(stock "$A")"
vente "$A" 2
check "vente de 2 depuis A : 201" "code == 201"
api PUT "/reformes/update/$R1" '{"nombreSujets":5}'
check "réforme 10 -> 5 alors que 6 ont quitté S1 : 400 clair" "code == 400 and 'transférés depuis le magasin de stockage' in err and 'Stockage 1 $SUF' in err"
api PUT "/reformes/update/$R1" '{"nombreSujets":7}'
check "réforme 10 -> 7 (reste 1 dans S1) : 200" "code == 200 and d['data']['nombreSujets'] == 7"
egal "S1 = 1" "1" "$(stock_s "$S1")"
api PUT "/reformes/deleteOrRecover/$R1" ""
check "suppression d'une réforme dont des sujets ont quitté S1 : 400" "code == 400 and 'supprimer cette réforme' in err"
check_sql "réforme toujours active" "SELECT removed FROM reformes WHERE unique_id = '$R1'" "f"
reforme 4 "$S1"
R1B="$(jval "d['data']['uniqueId']")"
egal "réforme de 4 dans S1 : S1 = 5" "5" "$(stock_s "$S1")"
api PUT "/reformes/deleteOrRecover/$R1B" ""
check "suppression d'une réforme encore dans S1 : 200" "code == 200"
egal "S1 = 1" "1" "$(stock_s "$S1")"
api PUT "/reformes/deleteOrRecover/$R1B" ""
check "restauration : 200" "code == 200"
egal "S1 = 5" "5" "$(stock_s "$S1")"

# ---------------------------------------------------------------------------
echo "== 3. Magasin S2 avec point de vente par défaut B : transfert automatique"
api POST /magasins/create "{\"nom\":\"Boutique B $SUF\",\"type\":\"VENTE\"}"
B="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Stockage 2 $SUF\",\"type\":\"STOCKAGE\",\"magasinVenteParDefautUniqueId\":\"$B\"}"
S2="$(jval "d['data']['uniqueId']")"
reforme 10 "$S2"
check "réforme de 10 vers S2 : 201, passée à B" "code == 201 and d['data']['magasinStockageUniqueId'] == '$S2' and d['data']['magasinVenteUniqueId'] == '$B'"
R2="$(jval "d['data']['uniqueId']")"
egal "transfert lié : B, depuis S2, 10, actif" "Boutique B $SUF|Stockage 2 $SUF|10|false" "$(lie "$R2")"
check_sql "transfert lié : type, projet, ferme, date" \
  "SELECT t.type || '|' || (t.projet_id = $PROJET_ID) || '|' || (t.farm_id = $F) || '|' || t.date FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R2'" "REFORME|true|true|$AUJ"
egal "S2 = 0 (tout est passé à B)" "0" "$(stock_s "$S2")"
egal "B = 10" "10" "$(stock "$B")"
vente "$B" 4
check "vente de 4 depuis B : 201" "code == 201"
api PUT "/reformes/update/$R2" '{"nombreSujets":3}'
check "réforme 10 -> 3 alors que 4 sont vendus : 400" "code == 400 and 'déjà été vendus' in err and 'Boutique B $SUF' in err"
egal "réforme et transfert inchangés" "Boutique B $SUF|Stockage 2 $SUF|10|false" "$(lie "$R2")"
api PUT "/reformes/update/$R2" "{\"nombreSujets\":12,\"date\":\"$HIER\"}"
check "réforme 10 -> 12, date d'hier : 200" "code == 200"
check_sql "transfert lié : 12, date d'hier" "SELECT t.quantite || '|' || t.date FROM magasin_transferts t JOIN reformes r ON r.id = t.reforme_id WHERE r.unique_id = '$R2'" "12|$HIER"
egal "B = 8" "8" "$(stock "$B")"
api PUT "/reformes/deleteOrRecover/$R2" ""
check "suppression d'une réforme dont 4 sujets sont vendus : 400" "code == 400 and 'supprimer cette réforme' in err"
reforme 5 "$S2"
R3="$(jval "d['data']['uniqueId']")"
egal "B = 13" "13" "$(stock "$B")"
api PUT "/reformes/deleteOrRecover/$R3" ""
check "suppression de R3 (rien vendu) : 200" "code == 200"
egal "transfert de R3 supprimé avec elle" "Boutique B $SUF|Stockage 2 $SUF|5|true" "$(lie "$R3")"
egal "B = 8" "8" "$(stock "$B")"
api PUT "/reformes/update/$R3" "{\"magasinStockageUniqueId\":\"$S1\"}"
check "changer le magasin d'une réforme supprimée : 400" "code == 400 and 'Restaurez' in err"
api PUT "/reformes/deleteOrRecover/$R3" ""
check "restauration de R3 : 200" "code == 200"
egal "transfert de R3 restauré" "Boutique B $SUF|Stockage 2 $SUF|5|false" "$(lie "$R3")"
egal "B = 13" "13" "$(stock "$B")"
api PUT "/reformes/update/$R3" "{\"magasinStockageUniqueId\":\"$S1\"}"
check "R3 déplacée de S2 vers S1 (sans point de vente par défaut) : 200" "code == 200 and d['data']['magasinStockageUniqueId'] == '$S1' and d['data']['magasinVenteUniqueId'] is None"
egal "transfert de R3 retiré de B" "Boutique B $SUF|Stockage 1 $SUF|0|true" "$(lie "$R3")"
egal "B = 8" "8" "$(stock "$B")"
egal "S1 = 10" "10" "$(stock_s "$S1")"
api PUT "/reformes/update/$R3" '{"nombreSujets":7}'
check "R3 dans S1 (sans point de vente par défaut) : 5 -> 7, 200" "code == 200 and d['data']['magasinVenteUniqueId'] is None"
egal "transfert de R3 reste garé (0, supprimé)" "Boutique B $SUF|Stockage 1 $SUF|0|true" "$(lie "$R3")"
egal "B inchangé = 8" "8" "$(stock "$B")"
egal "S1 = 10 + 2 = 12" "12" "$(stock_s "$S1")"
api PUT "/reformes/update/$R3" "{\"magasinStockageUniqueId\":\"$S2\",\"nombreSujets\":6}"
check "R3 revient dans S2 avec 6 sujets : 200, passée à B" "code == 200 and d['data']['magasinVenteUniqueId'] == '$B'"
egal "transfert de R3 : 6 vers B" "Boutique B $SUF|Stockage 2 $SUF|6|false" "$(lie "$R3")"
egal "B = 14" "14" "$(stock "$B")"
egal "S1 = 5" "5" "$(stock_s "$S1")"
api PUT "/reformes/update/$R1" "{\"magasinStockageUniqueId\":\"$S2\"}"
check "R1 (6 sujets déjà partis de S1 à la main) vers S2 : 400" "code == 400 and 'transférés depuis le magasin de stockage' in err"
vente "$B" 14
check "vente de tout le stock de B : 201" "code == 201"
api PUT "/reformes/update/$R3" "{\"magasinStockageUniqueId\":\"$S1\"}"
check "R3 vendue : changement de magasin refusé" "code == 400 and 'déjà été vendus' in err"

# ---------------------------------------------------------------------------
echo "== 4. Ancien téléphone, plusieurs magasins de stockage"
api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"reft-bat-$SUF\",\"magasinStockageUniqueId\":\"$S1\",\"date\":\"$AUJ\",\"oeufsCollectes\":10,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
check "collecte du projet dans S1" "code == 201"
reforme 1
check "ancien corps, deux magasins : magasin de la dernière collecte du projet (S1)" "code == 201 and d['data']['magasinStockageUniqueId'] == '$S1'"
reforme 1 "" "$AUJ" "" "\"magasinVenteUniqueId\":\"$B\""
check "ancien contrat (point de vente B) : magasin dont B est le point de vente par défaut (S2)" \
  "code == 201 and d['data']['magasinStockageUniqueId'] == '$S2' and d['data']['magasinVenteUniqueId'] == '$B'"
AUTRE_S="$(psql_run "SELECT unique_id FROM magasins_vente WHERE farm_id = $FARM_PRINCIPALE AND type = 'STOCKAGE' AND removed = false LIMIT 1")"
reforme 1 "$AUTRE_S"
check "magasin de stockage d'une autre ferme : 400" "code == 400 and 'Magasin de stockage invalide' in err"
reforme 1 "$A"
check "point de vente donné comme magasin de stockage : 400" "code == 400 and 'Magasin de stockage invalide' in err"

# ---------------------------------------------------------------------------
echo "== 5. Reprise des réformes anciennes sans magasin"
# Réformes d'avant la règle : L1 (7, la plus ancienne) couverte par un transfert manuel
# ancien « depuis le projet » de 7 sujets, L2 (3) jamais placée ; R0 (2) de la partie 1.
psql_run "INSERT INTO reformes (unique_id, date, nombre_sujets, projet_id, farm_id, batiment_id, removed, archive, created_at)
  SELECT 'reft-legacy1-$SUF', DATE '2026-03-01', 7, $PROJET_ID, $F, b.id, false, false, now() FROM batiments b WHERE b.unique_id = 'reft-bat-$SUF'" >/dev/null
psql_run "INSERT INTO reformes (unique_id, date, nombre_sujets, projet_id, farm_id, batiment_id, removed, archive, created_at)
  SELECT 'reft-legacy2-$SUF', DATE '2026-04-01', 3, $PROJET_ID, $F, b.id, false, false, now() FROM batiments b WHERE b.unique_id = 'reft-bat-$SUF'" >/dev/null
api POST /magasin-transferts/create "{\"magasinUniqueId\":\"$A\",\"projetUniqueId\":\"$PROJET\",\"type\":\"REFORME\",\"quantite\":13,\"date\":\"$AUJ\"}"
check "transfert ancien « depuis le projet » plafonné aux réformés sans magasin (12)" "code == 400 and '12 sujet(s)' in err"
api POST /magasin-transferts/create "{\"magasinUniqueId\":\"$A\",\"projetUniqueId\":\"$PROJET\",\"type\":\"REFORME\",\"quantite\":7,\"date\":\"$AUJ\"}"
check "transfert ancien de 7 vers A (couvre L1)" "code in (200, 201)"
api GET "/magasin-transferts/disponible?projetUniqueId=$PROJET&type=REFORME" ""
check "disponible « depuis le projet » = 5 (réformés sans magasin seulement)" "code == 200 and d['data'] == 5"
NB_AVANT="$(psql_run "SELECT count(*) FROM magasin_transferts WHERE farm_id = $F")"
api POST "/admin/reformes/transferts-manquants" ""
check "simulation sans magasin précisé, deux magasins : erreur claire" "code == 200 and 'Plusieurs magasins de stockage' in d['data']['erreur'] and d['data']['reformesAffectees'] == 0"
api POST "/admin/reformes/transferts-manquants?magasinStockageUniqueId=$S1" ""
check "simulation vers S1 : 5 à affecter (R0 + L2), L1 laissée, aucun point de vente" \
  "code == 200 and d['data']['execute'] == False and d['data']['totalAffectable'] == 5 and d['data']['pointDeVenteUniqueId'] is None and d['data']['projets'][0]['laisses'] == 7 and d['data']['projets'][0]['dejaTransferes'] == 7"
check_sql "simulation : rien écrit" "SELECT count(*) FROM magasin_transferts WHERE farm_id = $F" "$NB_AVANT"
check_sql "simulation : aucune réforme affectée" "SELECT count(*) FROM reformes WHERE unique_id IN ('$R0','reft-legacy2-$SUF') AND magasin_stockage_id IS NOT NULL" "0"
api POST "/admin/reformes/transferts-manquants?farmUniqueId=$FARM_UID&executer=true&magasinStockageUniqueId=$S2" ""
check "exécution vers S2 (point de vente B) : 5 affectés, 2 transferts automatiques" \
  "code == 200 and d['data']['execute'] == True and d['data']['totalAffecte'] == 5 and d['data']['totalTransfere'] == 5 and d['data']['transfertsCrees'] == 2 and d['data']['pointDeVenteUniqueId'] == '$B'"
egal "L2 : magasin S2, transfert de 3 vers B" "Boutique B $SUF|Stockage 2 $SUF|3|false" "$(lie "reft-legacy2-$SUF")"
egal "R0 : transfert de 2 vers B" "Boutique B $SUF|Stockage 2 $SUF|2|false" "$(lie "$R0")"
check_sql "L1 (déjà au point de vente à la main) laissée sans magasin ni transfert lié" \
  "SELECT COALESCE(magasin_stockage_id::text, 'vide') || '|' || (SELECT count(*) FROM magasin_transferts t WHERE t.reforme_id = r.id) FROM reformes r WHERE unique_id = 'reft-legacy1-$SUF'" "vide|0"
egal "B = 1 (partie 4) + 5 repris = 6" "6" "$(stock "$B")"
api POST "/admin/reformes/transferts-manquants?executer=true&magasinStockageUniqueId=$S2" ""
check "second passage : rien à affecter (idempotent)" "code == 200 and d['data']['totalAffectable'] == 0 and d['data']['transfertsCrees'] == 0"
check_sql "aucun transfert de plus" "SELECT count(*) FROM magasin_transferts WHERE farm_id = $F" "$((NB_AVANT + 2))"
api PUT "/reformes/update/reft-legacy1-$SUF" '{"nombreSujets":2}'
check "L1 7 -> 2 alors que 7 sont partis à la main : 400" "code == 400 and 'transférés à la main' in err"
api PUT "/reformes/update/reft-legacy1-$SUF" "{\"magasinStockageUniqueId\":\"$S1\"}"
check "L1 (déjà au point de vente) vers un magasin de stockage : 400" "code == 400 and 'déjà au point de vente (transfert manuel)' in err and 'modifiez seulement le nombre ou la cause' in err"

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
  api POST "/admin/reformes/transferts-manquants?farmUniqueId=$FARM_UID&magasinStockageUniqueId=$S2" ""
  check "SUPER_ADMIN, simulation sur la ferme de test : 0 à affecter" "code == 200 and d['data']['totalAffectable'] == 0 and d['data']['farmUniqueId'] == '$FARM_UID'"
else
  echec "connexion SUPER_ADMIN ($SUPER_ID)"
fi
TOKEN="$FTOKEN"

# ---------------------------------------------------------------------------
echo "== 6. Idempotence : seuls les succès sont mémorisés"
STOCK_A="$(stock "$A")"
egal "A = 6 reçus de S1 - 2 vendus + 7 anciens = 11" "11" "$STOCK_A"
CLE="$(uuid)"
vente "$A" $((STOCK_A + 1)) "$CLE"
check "vente au-delà du stock de A : 400" "code == 400 and not rejoue and 'insuffisant' in err"
check_sql "le 400 n'est pas mémorisé (clé libérée)" "SELECT count(*) FROM idempotency_requests WHERE cle = '$CLE'" "0"
transfert "$S1" "$A" 1
check "transfert d'1 sujet S1 -> A (le stock arrive)" "code in (200, 201)"
vente "$A" $((STOCK_A + 1)) "$CLE"
check "même clé après correction du stock : 201 exécuté" "code == 201 and not rejoue"
vente "$A" $((STOCK_A + 1)) "$CLE"
check "renvoi du succès : 201 rejoué" "code == 201 and rejoue"
CLE2="$(uuid)"
reforme 2 "$S2"
psql_run "INSERT INTO idempotency_requests (cle, utilisateur, farm_id, methode_chemin, hash_corps, statut, statut_reponse, corps_reponse, type_contenu, created_at)
  VALUES ('$CLE2', 'reftadmin$SUF', $F, 'POST /diafarms/api/v1/ventes-reforme/create', 'ancien', 'TERMINE', 400,
  '{\"message\":\"Données invalides\",\"status\":400,\"errors\":[\"Stock de sujets réformés insuffisant dans ce magasin (0 sujet(s) restants).\"]}', 'application/json', now())" >/dev/null
vente "$B" 2 "$CLE2"
check "ancien 400 mémorisé : la requête est ré-exécutée (201)" "code == 201 and not rejoue"
check_sql "clé désormais mémorisée avec le succès" "SELECT statut || '|' || statut_reponse FROM idempotency_requests WHERE cle = '$CLE2'" "TERMINE|201"
CLE3="$(uuid)"
reforme 1 "" "$AUJ" "$CLE3"
check "réforme du téléphone avec clé, sans magasin : 201" "code == 201"

# ---------------------------------------------------------------------------
echo "== 7. Restauration de vente, réforme envoyée directement au point de vente (ancien modèle)"
api POST /magasins/create "{\"nom\":\"Boutique C $SUF\",\"type\":\"VENTE\"}"
C="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Stockage 3 $SUF\",\"type\":\"STOCKAGE\",\"magasinVenteParDefautUniqueId\":\"$C\"}"
S3="$(jval "d['data']['uniqueId']")"
reforme 10 "$S3"
R7="$(jval "d['data']['uniqueId']")"
vente "$C" 8
V7="$(jval "d['data']['uniqueId']")"
check "réforme 10 via S3 vers C puis vente de 8 : 201" "code == 201"
api PUT "/ventes-reforme/deleteOrRecover/$V7" '{"motif":"erreur de saisie"}'
check "suppression de la vente : 200" "code == 200"
api PUT "/reformes/update/$R7" '{"nombreSujets":2}'
check "réforme ramenée à 2 (vente supprimée) : 200" "code == 200"
api PUT "/ventes-reforme/deleteOrRecover/$V7" '{}'
check "restauration de la vente de 8 avec 2 en stock : 400 clair" "code == 400 and 'Impossible de restaurer cette vente' in err and 'Boutique C $SUF' in err"
egal "stock de C = 2 (jamais négatif)" "2" "$(stock "$C")"
api PUT "/reformes/update/$R7" '{"nombreSujets":9}'
api PUT "/ventes-reforme/deleteOrRecover/$V7" '{}'
check "réforme remontée à 9 : restauration acceptée" "code == 200"
egal "stock de C = 1" "1" "$(stock "$C")"

api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"reft-bat-$SUF\",\"magasinStockageUniqueId\":\"$S2\",\"date\":\"$AUJ\",\"oeufsCollectes\":30,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
check "collecte de 30 œufs (transfert automatique vers B)" "code == 201"
api POST /ventes-oeufs/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$B\",\"quantiteOeufs\":20,\"prixUnitaire\":100,\"montant\":2000,\"montantRapporte\":2000}"
VO="$(jval "d['data']['uniqueId']")"
check "vente de 20 œufs depuis B : 201" "code == 201"
api PUT "/ventes-oeufs/deleteOrRecover/$VO" '{"motif":"erreur de saisie"}'
psql_run "UPDATE magasin_transferts SET quantite = 5 WHERE magasin_id = (SELECT id FROM magasins_vente WHERE unique_id = '$B') AND type = 'OEUFS' AND projet_id = $PROJET_ID" >/dev/null
api PUT "/ventes-oeufs/deleteOrRecover/$VO" '{}'
check "restauration de la vente de 20 œufs avec 5 en stock : 400" "code == 400 and 'Impossible de restaurer cette vente' in err"

# Réforme du 2 octobre 2026 (ancien modèle) : envoyée directement à C, transfert lié sans
# magasin de stockage. Ses modifications suivent toujours ce transfert.
psql_run "INSERT INTO reformes (unique_id, date, nombre_sujets, projet_id, farm_id, batiment_id, removed, archive, created_at, magasin_vente_id)
  SELECT 'reft-direct-$SUF', DATE '2026-10-02', 6, $PROJET_ID, $F, b.id, false, false, now(), (SELECT id FROM magasins_vente WHERE unique_id = '$C')
  FROM batiments b WHERE b.unique_id = 'reft-bat-$SUF'" >/dev/null
psql_run "INSERT INTO magasin_transferts (unique_id, magasin_id, projet_id, reforme_id, type, quantite, date, farm_id, removed, archive, created_at)
  SELECT 'reft-direct-t-$SUF', (SELECT id FROM magasins_vente WHERE unique_id = '$C'), $PROJET_ID, r.id, 'REFORME', 6, DATE '2026-10-02', $F, false, false, now()
  FROM reformes r WHERE r.unique_id = 'reft-direct-$SUF'" >/dev/null
egal "C = 7" "7" "$(stock "$C")"
api PUT "/reformes/update/reft-direct-$SUF" '{"nombreSujets":4}'
check "ancienne réforme directe 6 -> 4 : 200" "code == 200"
egal "transfert lié suivi (4, sans magasin de stockage)" "Boutique C $SUF|-|4|false" "$(lie "reft-direct-$SUF")"
vente "$C" 5
api PUT "/reformes/deleteOrRecover/reft-direct-$SUF" ""
check "suppression alors que C a vendu ses sujets : 400" "code == 400 and 'déjà été vendus' in err"
api PUT "/reformes/update/reft-direct-$SUF" "{\"magasinStockageUniqueId\":\"$S1\"}"
check "ancienne réforme directe vendue : changement de magasin refusé" "code == 400 and 'déjà été vendus' in err"

check_sql "aucun magasin de stockage en négatif (réformés)" \
  "SELECT count(*) FROM (SELECT s.id, p.id AS pid,
     (SELECT COALESCE(SUM(nombre_sujets),0) FROM reformes r WHERE r.magasin_stockage_id = s.id AND r.projet_id = p.id AND r.removed = false)
   - (SELECT COALESCE(SUM(quantite),0) FROM magasin_transferts t WHERE t.magasin_stockage_id = s.id AND t.projet_id = p.id AND t.type = 'REFORME' AND t.removed = false) AS reste
   FROM magasins_vente s, projets p WHERE s.farm_id = $F AND s.type = 'STOCKAGE' AND p.id = $PROJET_ID) x WHERE reste < 0" "0"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
