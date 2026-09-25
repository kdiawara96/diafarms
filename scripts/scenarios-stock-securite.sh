#!/usr/bin/env bash
# Stock d'œufs + isolation des fermes : script de bout en bout.
#
# 1. Stock : le transfert MANUEL depuis un magasin de stockage applique exactement la
#    même règle que le transfert AUTOMATIQUE à la collecte (StockOeufsRegle) :
#    bon état = collectés - cassés - non utilisables ; cassés dans le pool séparé
#    OEUFS_CASSES ; non utilisables jamais transférables.
# 2. Sécurité : un utilisateur ne peut ni lire ni modifier/supprimer un projet (ou un
#    magasin, une collecte...) d'une autre ferme : réponse 400 « introuvable ».
#
# Pré-requis (non gérés ici) : Postgres + backend démarrés, base seedée par
# scenarios-circuit-client.sh (ferme + ADMIN admin@t.local / Test1234! + projet avec
# poulailler). Le script crée lui-même (idempotent) une AUTRE ferme + un projet + un
# magasin par SQL. Chaque passage crée ses propres magasins : rejouable sur la même base.
#
# Variables : BASE (défaut http://localhost:9199/diafarms/api/v1), PGHOST (dossier
# socket ou hôte), PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen),
# ADMIN_EMAIL / ADMIN_PWD.
#
# Sortie : une ligne OK/ECHEC par assertion ; code de sortie 0 si tout est OK, 1 sinon.

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

# Autre ferme + projet + magasin (SQL, idempotent).
psql_run "INSERT INTO farms (unique_id) SELECT 'pesee-autre-ferme' WHERE NOT EXISTS (SELECT 1 FROM farms WHERE unique_id = 'pesee-autre-ferme')" >/dev/null
psql_run "INSERT INTO projets (unique_id, code, titre, objectif, race_id, farm_id)
  SELECT 'pesee-autre-projet', 'PRJ-AUTRE', 'Projet autre ferme', p.objectif, p.race_id, (SELECT id FROM farms WHERE unique_id = 'pesee-autre-ferme')
  FROM projets p WHERE p.unique_id = '$PROJET'
  AND NOT EXISTS (SELECT 1 FROM projets WHERE unique_id = 'pesee-autre-projet')" >/dev/null
psql_run "INSERT INTO magasins_vente (unique_id, nom, type, farm_id, removed)
  SELECT 'stock-autre-magasin', 'Magasin autre ferme', 'STOCKAGE', (SELECT id FROM farms WHERE unique_id = 'pesee-autre-ferme'), false
  WHERE NOT EXISTS (SELECT 1 FROM magasins_vente WHERE unique_id = 'stock-autre-magasin')" >/dev/null
AUTRE_PROJET="pesee-autre-projet"
AUTRE_MAGASIN="stock-autre-magasin"

# Date passée tirée au hasard dans la vie du projet : évite le plafond journalier de
# ponte (effectif vivant) quand le script est rejoué plusieurs fois.
JOUR="$(python3 -c 'import random,datetime; print(datetime.date(2026,2,1)+datetime.timedelta(days=random.randint(0,200)))')"
SUFFIXE="$(uuid | cut -c1-8)"

api POST /magasins/create "{\"nom\":\"Stock manuel $SUFFIXE\",\"type\":\"STOCKAGE\"}"
STOCK="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Boutique manuelle $SUFFIXE\",\"type\":\"VENTE\"}"
BOUTIQUE="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Boutique auto $SUFFIXE\",\"type\":\"VENTE\"}"
BOUTIQUE_AUTO="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Stock auto $SUFFIXE\",\"type\":\"STOCKAGE\",\"magasinVenteParDefautUniqueId\":\"$BOUTIQUE_AUTO\"}"
STOCK_AUTO="$(jval "d['data']['uniqueId']")"
check "magasins de test créés" "code in (200, 201) and d['data']['uniqueId']"

collecte() { # $1 = magasin de stockage
  api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"magasinStockageUniqueId\":\"$1\",\"date\":\"$JOUR\",\"oeufsCollectes\":100,\"oeufsCasses\":10,\"oeufsNonUtilisables\":15}"
}
transfert() { # $1 = type, $2 = quantité
  api POST /magasin-transferts/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"magasinStockageUniqueId\":\"$STOCK\",\"type\":\"$1\",\"quantite\":$2,\"date\":\"$JOUR\"}"
}

# ---------------------------------------------------------------------------
echo "== 1. Collecte 100 œufs dont 10 cassés et 15 non utilisables (stock sans transfert auto)"
collecte "$STOCK"
check "collecte enregistrée" "code in (200, 201)"
api GET "/magasin-transferts/disponible-batiment?magasinStockageUniqueId=$STOCK&type=OEUFS"
check "disponible bon état = 75 (100 - 10 - 15)" "code == 200 and d['data'] == 75"
api GET "/magasin-transferts/disponible-batiment?magasinStockageUniqueId=$STOCK&type=OEUFS_CASSES"
check "disponible cassés = 10" "code == 200 and d['data'] == 10"

echo "== 2. Transfert manuel au-delà du bon état"
transfert OEUFS 76
check "76 œufs refusés (400, stock insuffisant, 75 restants)" "code == 400 and '75' in ' '.join(d.get('errors') or [])"
transfert OEUFS 85
check "85 œufs (= collectés - cassés, ancienne règle) refusés" "code == 400"

echo "== 3. Transfert manuel exact"
transfert OEUFS 75
check "75 œufs acceptés" "code in (200, 201)"
api GET "/magasin-transferts/disponible-batiment?magasinStockageUniqueId=$STOCK&type=OEUFS"
check "plus rien de transférable en bon état (les 15 non utilisables restent bloqués)" "code == 200 and d['data'] == 0"
transfert OEUFS 1
check "1 œuf de plus refusé" "code == 400"

echo "== 4. Cassés : pool séparé, inchangé"
transfert OEUFS_CASSES 11
check "11 cassés refusés" "code == 400"
transfert OEUFS_CASSES 10
check "10 cassés acceptés" "code in (200, 201)"

echo "== 5. Transfert automatique : même règle"
collecte "$STOCK_AUTO"
check "collecte identique dans le stock à transfert automatique" "code in (200, 201)"
api GET "/magasins/$BOUTIQUE_AUTO/stock"
check "boutique auto : 75 bons + 10 cassés" "code == 200 and d['data']['oeufsDisponible'] == 75 and d['data']['oeufsCassesDisponible'] == 10"
api GET "/magasin-transferts/disponible-batiment?magasinStockageUniqueId=$STOCK_AUTO&type=OEUFS"
check "après transfert auto, plus rien de transférable manuellement" "code == 200 and d['data'] == 0"
api GET "/magasins/$BOUTIQUE/stock"
check "boutique manuelle = boutique auto (75 bons + 10 cassés)" "code == 200 and d['data']['oeufsDisponible'] == 75 and d['data']['oeufsCassesDisponible'] == 10"

echo "== 6. Disponible par projet (même règle)"
api GET "/magasin-transferts/disponible?projetUniqueId=$PROJET&type=OEUFS"
ATTENDU="$(psql_run "SELECT (SELECT COALESCE(SUM(oeufs_collectes - oeufs_casses - oeufs_non_utilisables),0) FROM collectes_oeufs c JOIN projets p ON p.id = c.projet_id WHERE p.unique_id = '$PROJET' AND c.removed = false)
  - (SELECT COALESCE(SUM(quantite),0) FROM magasin_transferts t JOIN projets p ON p.id = t.projet_id WHERE p.unique_id = '$PROJET' AND t.type = 'OEUFS' AND t.removed = false)")"
check "disponible projet = Σ bon état - Σ transferts OEUFS ($ATTENDU)" "code == 200 and d['data'] == $ATTENDU"

echo "== 7. Projet d'une autre ferme"
TITRE_AVANT="$(psql_run "SELECT titre FROM projets WHERE unique_id = '$AUTRE_PROJET'")"
api PUT "/projets/update/$AUTRE_PROJET" '{"titre":"Piraté"}'
check "updateProjet refusé (400 introuvable)" "code == 400 and 'non trouvé' in ' '.join(d.get('errors') or [])"
check_sql "titre du projet de l'autre ferme inchangé" "SELECT titre FROM projets WHERE unique_id = '$AUTRE_PROJET'" "$TITRE_AVANT"
api GET "/projets/findbyUniqueId/$AUTRE_PROJET"
check "détail refusé (400)" "code == 400"
api DELETE "/projets/delete/$AUTRE_PROJET"
check "suppression refusée (400)" "code == 400"
check_sql "projet de l'autre ferme non supprimé" "SELECT COALESCE(removed, false) FROM projets WHERE unique_id = '$AUTRE_PROJET'" "f"
api PUT "/projets/cloturer/$AUTRE_PROJET"
check "clôture refusée (400)" "code == 400"
api PUT "/projets/$PROJET/transferer-stock/$AUTRE_PROJET"
check "transfert de stock d'aliment vers l'autre ferme refusé (400)" "code == 400"
TITRE_PROPRE="$(psql_run "SELECT titre FROM projets WHERE unique_id = '$PROJET'")"
api PUT "/projets/update/$PROJET" "{\"titre\":\"$TITRE_PROPRE\"}"
check "témoin : updateProjet sur son propre projet accepté" "code == 200"

echo "== 8. Autres ressources d'une autre ferme"
api PUT "/magasins/update/$AUTRE_MAGASIN" '{"nom":"Piraté"}'
check "modification d'un magasin de l'autre ferme refusée (400)" "code == 400"
check_sql "nom du magasin de l'autre ferme inchangé" "SELECT nom FROM magasins_vente WHERE unique_id = '$AUTRE_MAGASIN'" "Magasin autre ferme"
api GET "/magasins/$AUTRE_MAGASIN/stock"
check "stock d'un magasin de l'autre ferme refusé (400)" "code == 400"
api GET "/magasin-transferts/disponible-batiment?magasinStockageUniqueId=$AUTRE_MAGASIN&type=OEUFS"
check "disponible d'un stockage de l'autre ferme refusé (400)" "code == 400"
api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$AUTRE_PROJET\",\"magasinStockageUniqueId\":\"$STOCK\",\"date\":\"$JOUR\",\"oeufsCollectes\":1,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
check "collecte sur le projet de l'autre ferme refusée (400)" "code == 400"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
