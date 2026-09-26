#!/usr/bin/env bash
# Réformes au kilo : commande tarifée au kilo, livraison pesée, statistiques réforme,
# estimation du poids par la dernière session de pesée.
#
# 1. Commande REFORME tarification KILO (prixKgEstime, poidsEstimeKg -> montantEstime
#    calculé), acompte, livraison partielle de 10 sujets pesant 18,5 kg : la vente
#    générée est une VenteReforme KILO, montant = 18,5 x prix/kg, le stock du magasin
#    baisse de 10 SUJETS, le reste à livrer reste en sujets. Deuxième livraison avec un
#    prix/kg surchargé. Validations (KILO réservé à la réforme, poids obligatoire...).
# 2. GET /ventes-reforme/stats : valeurs exactes sur une date unique propre à ce passage
#    (ventes directes TETE + KILO), ferme entière et par projet ; delta exact du jour
#    pour les livraisons de la commande.
# 3. GET /pesees/dernier-poids-moyen : dernière session TERMINEE du projet, data null
#    pour un projet sans pesée terminée, 400 pour un projet d'une autre ferme.
#
# Pré-requis (non gérés ici) : Postgres + backend démarrés, base seedée par
# scenarios-circuit-client.sh (ferme + ADMIN admin@t.local / Test1234! + projet avec
# poulailler). Rejouable : chaque passage crée ses propres magasin, client, réforme.
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
    ok = bool(eval(expr, {"d": d, "code": code, "abs": abs, "len": len, "round": round, "sum": sum, "any": any}))
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

# Client web (seul accès d'un RESPONSABLE) : le jeton arrive dans le cookie
# diafarms_access_token, pas dans le corps ; il reste utilisable en Bearer.
login_web() { # $1 = identifiant, $2 = mot de passe
  curl -s -i -X POST "$BASE/auth" -H 'X-Client-Type: web' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | sed -n 's/^[Ss]et-[Cc]ookie: diafarms_access_token=\([^;]*\).*/\1/p' | head -1
}

login() {
  curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"])
except Exception: print("")'
}


err() { echo "' '.join(d.get('errors') or [])"; }

api_as() { # $1 = jeton, $2 = méthode, $3 = chemin, $4 = corps
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$2" "$BASE$3" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${4:+-d "$4"} > "$TMP/code"
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

# Projet sans aucune pesée (même ferme) et projet d'une autre ferme (SQL, idempotent).
psql_run "INSERT INTO projets (unique_id, code, titre, objectif, race_id, farm_id)
  SELECT 'kilo-projet-sans-pesee', 'PRJ-SPESEE', 'Projet sans pesée', p.objectif, p.race_id, p.farm_id
  FROM projets p WHERE p.unique_id = '$PROJET'
  AND NOT EXISTS (SELECT 1 FROM projets WHERE unique_id = 'kilo-projet-sans-pesee')" >/dev/null
psql_run "INSERT INTO farms (unique_id) SELECT 'pesee-autre-ferme' WHERE NOT EXISTS (SELECT 1 FROM farms WHERE unique_id = 'pesee-autre-ferme')" >/dev/null
psql_run "INSERT INTO projets (unique_id, code, titre, objectif, race_id, farm_id)
  SELECT 'pesee-autre-projet', 'PRJ-AUTRE', 'Projet autre ferme', p.objectif, p.race_id, (SELECT id FROM farms WHERE unique_id = 'pesee-autre-ferme')
  FROM projets p WHERE p.unique_id = '$PROJET'
  AND NOT EXISTS (SELECT 1 FROM projets WHERE unique_id = 'pesee-autre-projet')" >/dev/null

SUFFIXE="$(uuid | cut -c1-8)"
AUJ="$(date +%Y-%m-%d)"

api POST /magasins/create "{\"nom\":\"Boutique kilo $SUFFIXE\",\"type\":\"VENTE\"}"
BOUTIQUE="$(jval "d['data']['uniqueId']")"
check "magasin de vente créé" "code in (200, 201) and d['data']['uniqueId']"

TEL="7$(python3 -c 'import random; print(random.randint(1000000, 9999999))')"
api POST /clients/create "{\"nom\":\"Client kilo $SUFFIXE\",\"telephone\":\"$TEL\"}"
CLIENT="$(jval "d['data']['uniqueId']")"
check "client créé" "code in (200, 201) and d['data']['uniqueId']"

api POST /reformes/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"nombreSujets\":40}"
check "réforme de 40 sujets" "code in (200, 201)"
api POST /magasin-transferts/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"projetUniqueId\":\"$PROJET\",\"type\":\"REFORME\",\"quantite\":40,\"date\":\"$AUJ\"}"
check "transfert de 40 sujets réformés vers la boutique" "code in (200, 201)"

# ---------------------------------------------------------------------------
echo "== 1. Validations à la création"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"OEUFS\",\"quantite\":100,\"montantEstime\":10000,\"tarification\":\"KILO\",\"prixKgEstime\":2500}"
check "KILO sur une commande d'œufs : 400" "code == 400 and 'que les commandes de réformes' in $(err)"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"REFORME\",\"quantite\":30,\"tarification\":\"KILO\",\"poidsEstimeKg\":60}"
check "KILO sans prix au kilo : 400" "code == 400 and 'prix au kilo est obligatoire' in $(err)"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"REFORME\",\"quantite\":30,\"tarification\":\"KILO\",\"prixKgEstime\":2500}"
check "KILO sans poids estimé ni montant estimé : 400" "code == 400 and 'montant estimé' in $(err)"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"REFORME\",\"quantite\":30,\"tarification\":\"GRAMME\",\"montantEstime\":1000}"
check "tarification inconnue : 400" "code == 400 and 'Tarification invalide' in $(err)"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"REFORME\",\"quantite\":30,\"tarification\":\"KILO\",\"prixKgEstime\":2500,\"montantEstime\":140000}"
check "KILO sans poids estimé mais avec montant estimé : montant gardé tel quel" \
  "code == 201 and d['data']['tarification'] == 'KILO' and d['data']['prixKgEstime'] == 2500 and d['data']['poidsEstimeKg'] is None and d['data']['montantEstime'] == 140000"
CMD_SANS_POIDS="$(jval "d['data']['uniqueId']")"
api PUT "/commandes/update/$CMD_SANS_POIDS" '{"poidsEstimeKg":50}'
check "modification : poids estimé 50 kg -> montant estimé recalculé 125000" \
  "code == 200 and d['data']['poidsEstimeKg'] == 50 and d['data']['montantEstime'] == 125000"
api PUT "/commandes/update/$CMD_SANS_POIDS" '{"tarification":"TETE","prixUnitaireEstime":4000,"montantEstime":120000}'
check "modification : retour à TETE efface prix/kg et poids estimé" \
  "code == 200 and d['data']['tarification'] == 'TETE' and d['data']['prixKgEstime'] is None and d['data']['poidsEstimeKg'] is None and d['data']['montantEstime'] == 120000"
api POST "/commandes/$CMD_SANS_POIDS/livrer?quantite=1&poidsTotalKg=2" ""
check "livrer une commande TETE avec un poids : 400" "code == 400 and 'tarifée par sujet' in $(err)"
api PUT "/commandes/update/$CMD_SANS_POIDS" '{"prixKgEstime":2500}'
check "prix/kg sur une commande TETE : 400" "code == 400 and 'que les commandes au kilo' in $(err)"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"REFORME\",\"quantite\":30,\"montantEstime\":90000,\"poidsEstimeKg\":50}"
check "poids estimé sur une commande TETE (création) : 400" "code == 400 and 'que les commandes au kilo' in $(err)"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"REFORME\",\"quantite\":20,\"tarification\":\"KILO\",\"prixKgEstime\":2000,\"poidsEstimeKg\":40}"
CMD_M1="$(jval "d['data']['uniqueId']")"
api PUT "/commandes/update/$CMD_M1" '{"montantEstime":1000}'
check "commande KILO avec poids estimé : montantEstime envoyé seul ignoré, reste 40 x 2000 = 80000" "code == 200 and d['data']['montantEstime'] == 80000"

api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"OEUFS\",\"quantite\":10,\"montantEstime\":1000}"
check "commande d'œufs classique : tarification TETE par défaut" "code == 201 and d['data']['tarification'] == 'TETE' and d['data']['poidsLivreKg'] is None"

# ---------------------------------------------------------------------------
echo "== 2. Commande au kilo + acompte"
api GET "/ventes-reforme/stats?dateDebut=$AUJ&dateFin=$AUJ" ""
AVANT="$(cat "$TMP/body")"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"REFORME\",\"quantite\":30,\"tarification\":\"KILO\",\"prixKgEstime\":2500,\"poidsEstimeKg\":60,\"montantEstime\":1,\"montantAcompte\":20000,\"modePaiement\":\"ESPECES\"}"
check "commande KILO : montantEstime = 60 x 2500 = 150000 (calculé serveur), acompte 20000" \
  "code == 201 and d['data']['tarification'] == 'KILO' and d['data']['montantEstime'] == 150000 and d['data']['acompteRecu'] == 20000 and d['data']['poidsLivreKg'] == 0 and d['data']['resteALivrer'] == 30"
CMD="$(jval "d['data']['uniqueId']")"

api POST "/commandes/$CMD/livrer?quantite=10" ""
check "livraison KILO sans poids : 400" "code == 400 and 'poids total pesé' in $(err)"
api POST "/commandes/$CMD/livrer?poidsTotalKg=18.5" ""
check "livraison KILO sans nombre de sujets : 400" "code == 400 and 'nombre de sujets' in $(err)"
api POST "/commandes/$CMD/livrer?quantite=10&poidsTotalKg=18.5&prixKg=0" ""
check "livraison KILO prix/kg nul : 400" "code == 400 and 'prix au kilo' in $(err)"
api POST "/commandes/$CMD/convertir-en-vente" ""
check "convertir-en-vente d'une commande KILO (sans poids) : 400" "code == 400 and 'poids total pesé' in $(err)"
api PUT "/commandes/update/$CMD" '{"tarification":"KILO","prixKgEstime":2400}'
check "prix/kg modifiable avant livraison (EN_ATTENTE) : montantEstime = 60 x 2400" "code == 200 and d['data']['montantEstime'] == 144000"
api PUT "/commandes/update/$CMD" '{"prixKgEstime":2500}'
check "retour au prix 2500 : montantEstime 150000" "code == 200 and d['data']['montantEstime'] == 150000"

echo "== 3. Livraison partielle : 10 sujets, 18,5 kg"
api POST "/commandes/$CMD/livrer?quantite=10&poidsTotalKg=18.5" ""
check "commande EN_LIVRAISON, livré 10, reste 20 SUJETS, poids livré 18.5, montant livré 46250, acompte imputé 20000, reste à payer 26250" \
  "code == 200 and d['data']['statut'] == 'EN_LIVRAISON' and d['data']['quantiteLivree'] == 10 and d['data']['resteALivrer'] == 20 and d['data']['quantiteRestante'] == 20 and d['data']['poidsLivreKg'] == 18.5 and d['data']['montantLivre'] == 46250 and d['data']['payeSurCommande'] == 20000 and d['data']['resteAPayerLivre'] == 26250"
check "livraison : typeVente KILO, 10 sujets, 18.5 kg, prix 2500/kg, montant 46250" \
  "(lambda l: l['typeVente'] == 'KILO' and l['quantite'] == 10 and l['poidsTotalKg'] == 18.5 and l['prixUnitaire'] == 2500 and l['montant'] == 46250 and l['statutPaiement'] == 'PARTIELLE')(d['data']['livraisons'][0])"
VENTE1="$(jval "d['data']['livraisons'][0]['venteUniqueId']")"

api GET "/ventes-reforme/$VENTE1" ""
check "détail vente : KILO, montant 46250, poids moyen 1.85 kg/sujet, 2500/kg, 4625/tête, rattachée à la commande" \
  "code == 200 and d['data']['typeVente'] == 'KILO' and d['data']['montant'] == 46250 and d['data']['poidsTotalKg'] == 18.5 and d['data']['prixUnitaire'] == 2500 and d['data']['poidsMoyenParSujet'] == 1.85 and d['data']['prixParKg'] == 2500 and d['data']['prixParTete'] == 4625 and d['data']['commandeUniqueId'] == '$CMD' and d['data']['nombreSujets'] == 10"
api GET "/ventes-reforme/pas-une-vente" ""
check "détail d'une vente inexistante : 400 introuvable" "code == 400 and 'introuvable' in $(err)"

api GET "/ventes/list?dateDebut=$AUJ&dateFin=$AUJ" ""
check "liste des ventes : ligne REFORME avec typeVente, poids, poids moyen, prix/kg et prix/tête" \
  "code == 200 and any(l['uniqueId'] == '$VENTE1' and l['typeVente'] == 'KILO' and l['poidsTotalKg'] == 18.5 and l['poidsMoyenParSujet'] == 1.85 and l['prixParKg'] == 2500 and l['prixParTete'] == 4625 and l['quantite'] == 10 for l in d['data'])"
api GET "/ventes-reforme/list?size=200" ""
check "ventes-reforme/list : champs calculés présents" \
  "code == 200 and any(v['uniqueId'] == '$VENTE1' and v['poidsMoyenParSujet'] == 1.85 and v['prixParTete'] == 4625 for v in d['data']['data'])"

check_sql "stock de la boutique : 40 transférés - 10 vendus = 30 sujets" \
  "SELECT (SELECT COALESCE(SUM(quantite),0) FROM magasin_transferts t JOIN magasins_vente m ON m.id = t.magasin_id WHERE m.unique_id = '$BOUTIQUE' AND t.type = 'REFORME') - (SELECT COALESCE(SUM(r.nombre_sujets_attribue),0) FROM ventes_reforme_repartitions r JOIN ventes_reforme v ON v.id = r.vente_reforme_id JOIN magasins_vente m ON m.id = v.magasin_id WHERE m.unique_id = '$BOUTIQUE' AND v.removed = false)" "30"

echo "== 4. Deuxième livraison avec prix/kg surchargé : 5 sujets, 9,2 kg à 2600"
api POST "/commandes/$CMD/livrer?quantite=5&poidsTotalKg=9.2&prixKg=2600&montantRecu=10000&mode=ESPECES" ""
check "livré 15, reste 15 sujets, poids livré 27.7, montant livré 46250 + 23920 = 70170" \
  "code == 200 and d['data']['quantiteLivree'] == 15 and d['data']['resteALivrer'] == 15 and d['data']['poidsLivreKg'] == 27.7 and d['data']['montantLivre'] == 70170 and d['data']['livraisons'][1]['prixUnitaire'] == 2600 and d['data']['livraisons'][1]['montant'] == 23920"
api PUT "/commandes/update/$CMD" '{"prixKgEstime":3000}'
check "commande livrée en partie : prix/kg non modifiable (400)" "code == 400"
psql_run "UPDATE commandes SET statut = 'EN_ATTENTE' WHERE unique_id = '$CMD'" >/dev/null
api PUT "/commandes/update/$CMD" "{\"tarification\":\"KILO\",\"dateLivraisonPrevue\":\"$AUJ\"}"
check "commande livrée en partie : tarification identique renvoyée n'est pas un changement (200)" "code == 200 and d['data']['montantEstime'] == 150000"
api PUT "/commandes/update/$CMD" '{"tarification":"TETE"}'
check "commande livrée en partie : changer la tarification reste refusé (400)" "code == 400 and 'déjà commencé' in $(err)"
psql_run "UPDATE commandes SET statut = 'EN_LIVRAISON' WHERE unique_id = '$CMD'" >/dev/null

api GET "/ventes-reforme/stats?dateDebut=$AUJ&dateFin=$AUJ" ""
APRES="$(cat "$TMP/body")"
if python3 - "$AVANT" "$APRES" <<'PY'
import json, sys
a = json.loads(sys.argv[1])['data']['total']; b = json.loads(sys.argv[2])['data']['total']
ok = (b['nombreSujetsVendus'] - a['nombreSujetsVendus'] == 15
      and b['nombreSujetsVendusAuKilo'] - a['nombreSujetsVendusAuKilo'] == 15
      and abs(b['poidsTotalVenduKg'] - a['poidsTotalVenduKg'] - 27.7) < 1e-6
      and abs(b['montantVenduAuKilo'] - a['montantVenduAuKilo'] - 70170) < 1e-6
      and abs(b['montantTotal'] - a['montantTotal'] - 70170) < 1e-6
      and b['nombreVentes'] - a['nombreVentes'] == 2)
if not ok: print("   avant:", a, "\n   après:", b, file=sys.stderr)
sys.exit(0 if ok else 1)
PY
then echo "OK     stats du jour : +2 ventes, +15 sujets (au kilo), +27.7 kg, +70170 FCFA"; PASS=$((PASS+1))
else echo "ECHEC  stats du jour : deltas des livraisons"; FAIL=$((FAIL+1)); fi

# ---------------------------------------------------------------------------
echo "== 4b. Modification d'une vente : livraison de commande verrouillée, vente KILO recalculée"
for corps in '{"nombreSujets":9}' '{"poidsTotalKg":19}' '{"prixUnitaire":2600}' '{"typeVente":"TETE"}'; do
  api PUT "/ventes-reforme/update/$VENTE1" "$corps"
  check "livraison de commande, $corps : 400" "code == 400 and \"supprimez la livraison puis relivrez\" in $(err)"
done
api PUT "/ventes-reforme/update/$VENTE1" "{\"nombreSujets\":10,\"typeVente\":\"KILO\",\"poidsTotalKg\":18.5,\"prixUnitaire\":2500,\"date\":\"$AUJ\"}"
check "livraison de commande, mêmes valeurs renvoyées + date : 200, montant inchangé" "code == 200 and d['data']['montant'] == 46250 and d['data']['nombreSujets'] == 10"

api POST /ventes-reforme/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"date\":\"$AUJ\",\"nombreSujets\":3,\"typeVente\":\"KILO\",\"poidsTotalKg\":6,\"prixUnitaire\":2500,\"montant\":15000,\"montantRapporte\":15000}"
VD="$(jval "d['data']['uniqueId']")"
check "vente directe KILO : 3 sujets, 6 kg, 15000" "code == 201"
api PUT "/ventes-reforme/update/$VD" '{"poidsTotalKg":6.4}'
check "poids modifié sans montant : montant recalculé 6.4 x 2500 = 16000, répartition suivie" "code == 200 and d['data']['montant'] == 16000 and sum(r['montantAttribue'] for r in d['data']['repartitions']) == 16000"
api PUT "/ventes-reforme/update/$VD" '{"prixUnitaire":2600}'
check "prix/kg modifié sans montant : 6.4 x 2600 = 16640" "code == 200 and d['data']['montant'] == 16640"
api PUT "/ventes-reforme/update/$VD" '{"poidsTotalKg":7,"montant":17000}'
check "montant explicite : il prime (17000, pas 18200)" "code == 200 and d['data']['montant'] == 17000 and d['data']['poidsTotalKg'] == 7"
api PUT "/ventes-reforme/update/$VD" '{"poidsTotalKg":0}'
check "poids nul : 400" "code == 400 and 'positif' in $(err)"

# ---------------------------------------------------------------------------
echo "== 5. Statistiques exactes sur une date propre à ce passage"
for _ in 1 2 3 4 5; do
  JOUR="$(python3 -c 'import random,datetime; print(datetime.date(1950,1,1)+datetime.timedelta(days=random.randint(0,36000)))')"
  api GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR" ""
  python3 -c "import json;import sys; sys.exit(0 if json.load(open('$TMP/body'))['data']['total']['nombreVentes'] == 0 else 1)" && break
done
api POST /ventes-reforme/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"date\":\"$JOUR\",\"nombreSujets\":5,\"typeVente\":\"KILO\",\"poidsTotalKg\":10,\"prixUnitaire\":2500,\"montant\":25000,\"montantRapporte\":25000}"
check "vente directe KILO : 5 sujets, 10 kg, 25000" "code == 201 and d['data']['poidsMoyenParSujet'] == 2.0 and d['data']['prixParKg'] == 2500 and d['data']['prixParTete'] == 5000"
api POST /ventes-reforme/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"date\":\"$JOUR\",\"nombreSujets\":4,\"typeVente\":\"TETE\",\"prixUnitaire\":3000,\"montant\":12000,\"montantRapporte\":12000}"
check "vente directe TETE : 4 sujets, 12000 (pas de poids, pas de prix/kg)" "code == 201 and d['data']['typeVente'] == 'TETE' and d['data']['poidsMoyenParSujet'] is None and d['data']['prixParKg'] is None and d['data']['prixParTete'] == 3000"
api POST /ventes-reforme/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"date\":\"$JOUR\",\"nombreSujets\":17,\"prixUnitaire\":3000,\"montant\":51000,\"montantRapporte\":51000}"
check "stock en SUJETS : 40 - 15 - 3 - 9 = 13 restants, 17 refusés" "code == 400 and '13 sujet(s) restants' in $(err)"

TOTAL_ATTENDU="t['nombreVentes'] == 2 and t['nombreSujetsVendus'] == 9 and t['montantTotal'] == 37000 and t['prixMoyenParTete'] == 4111.11 and t['nombreSujetsVendusAuKilo'] == 5 and t['poidsTotalVenduKg'] == 10 and t['montantVenduAuKilo'] == 25000 and t['prixMoyenKg'] == 2500 and t['poidsMoyenParSujetKg'] == 2.0"
api GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR" ""
check "stats ferme ($JOUR) : 9 sujets, 37000, 4111.11/tête, 10 kg, 2500/kg, 2 kg/sujet ; un seul projet détaillé" \
  "code == 200 and (lambda t: $TOTAL_ATTENDU)(d['data']['total']) and len(d['data']['parProjet']) == 1 and (lambda t: t['projetUniqueId'] == '$PROJET' and $TOTAL_ATTENDU)(d['data']['parProjet'][0]) and d['data']['projetUniqueId'] is None"
api GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR&projetUniqueId=$PROJET" ""
check "stats par projet : mêmes valeurs, projet renseigné" \
  "code == 200 and d['data']['projetUniqueId'] == '$PROJET' and (lambda t: $TOTAL_ATTENDU)(d['data']['total'])"
api GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR&projetUniqueId=kilo-projet-sans-pesee" ""
check "stats d'un projet sans vente : zéros, moyennes nulles" \
  "code == 200 and d['data']['total']['nombreSujetsVendus'] == 0 and d['data']['total']['prixMoyenKg'] is None and d['data']['total']['prixMoyenParTete'] is None and d['data']['parProjet'] == []"

echo "== 5b. Statistiques selon le rôle (RESPONSABLE : ses projets ; VENTE : ses ventes)"
HASH="$(psql_run "SELECT password FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
RESP_EMAIL="resp-kilo@t.local"
if [ -z "$(psql_run "SELECT id FROM utilisateurs WHERE email = '$RESP_EMAIL'")" ]; then
  api POST /users/create-pro-or-finance "{\"fullName\":\"Responsable Kilo\",\"email\":\"$RESP_EMAIL\",\"telephone\":\"6$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\",\"roles\":[\"RESPONSABLE\"]}"
  check "utilisateur RESPONSABLE créé" "code in (200, 201)"
fi
psql_run "UPDATE utilisateurs SET password = '$HASH', must_change_password = false WHERE email = '$RESP_EMAIL'" >/dev/null
RESP_ID="$(psql_run "SELECT id FROM utilisateurs WHERE email = '$RESP_EMAIL'")"
TOKEN_RESP="$(login_web "$RESP_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN_RESP" ] || { echo "ECHEC  connexion $RESP_EMAIL"; FAIL=$((FAIL+1)); }
ANCIEN_RESP="$(psql_run "SELECT COALESCE(responsable_user_id::text, 'NULL') FROM projets WHERE unique_id = '$PROJET'")"

api_as "$TOKEN_RESP" GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR" ""
check "RESPONSABLE sans projet : rien de visible (0 sujet, aucun projet)" "code == 200 and d['data']['total']['nombreSujetsVendus'] == 0 and d['data']['parProjet'] == []"
api_as "$TOKEN_RESP" GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR&projetUniqueId=$PROJET" ""
check "RESPONSABLE : projet qui n'est pas le sien -> 400 introuvable" "code == 400 and 'Projet introuvable' in $(err)"
psql_run "UPDATE projets SET responsable_user_id = $RESP_ID WHERE unique_id = '$PROJET'" >/dev/null
api_as "$TOKEN_RESP" GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR" ""
check "RESPONSABLE de ce projet : mêmes chiffres exacts" "code == 200 and (lambda t: $TOTAL_ATTENDU)(d['data']['total']) and len(d['data']['parProjet']) == 1"
api_as "$TOKEN_RESP" GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR&projetUniqueId=kilo-projet-sans-pesee" ""
check "RESPONSABLE : autre projet de la ferme (pas le sien) -> 400" "code == 400 and 'Projet introuvable' in $(err)"
psql_run "UPDATE projets SET responsable_user_id = $ANCIEN_RESP WHERE unique_id = '$PROJET'" >/dev/null

TOKEN_VENTE="$(login "vente@t.local" "$ADMIN_PWD")"
if [ -z "$TOKEN_VENTE" ]; then
  echo "ECHEC  connexion vente@t.local (utilisateur VENTE absent ?)"; FAIL=$((FAIL+1))
else
  api_as "$TOKEN_VENTE" GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR" ""
  check "VENTE : aucune vente saisie par lui ce jour-là -> 0" "code == 200 and d['data']['total']['nombreVentes'] == 0"
  api_as "$TOKEN_VENTE" POST /ventes-reforme/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"date\":\"$JOUR\",\"nombreSujets\":2,\"prixUnitaire\":3000,\"montant\":6000,\"montantRapporte\":6000}"
  check "VENTE : vente de 2 sujets saisie" "code == 201"
  api_as "$TOKEN_VENTE" GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR" ""
  check "VENTE : ne voit que sa vente (1 vente, 2 sujets, 6000, 3000/tête)" \
    "code == 200 and d['data']['total']['nombreVentes'] == 1 and d['data']['total']['nombreSujetsVendus'] == 2 and d['data']['total']['montantTotal'] == 6000 and d['data']['total']['prixMoyenParTete'] == 3000"
  api GET "/ventes-reforme/stats?dateDebut=$JOUR&dateFin=$JOUR" ""
  check "ADMIN : voit les 3 ventes (11 sujets, 43000)" "code == 200 and d['data']['total']['nombreVentes'] == 3 and d['data']['total']['nombreSujetsVendus'] == 11 and d['data']['total']['montantTotal'] == 43000"
fi

api GET "/ventes-reforme/stats?projetUniqueId=pesee-autre-projet" ""
check "stats d'un projet d'une autre ferme : 400 introuvable" "code == 400 and 'introuvable' in $(err)"
api GET "/ventes-reforme/stats?dateDebut=2026-02-30" ""
check "stats date invalide : 400" "code == 400 and 'Date invalide' in $(err)"
api GET "/ventes-reforme/stats?dateDebut=2026-09-02&dateFin=2026-09-01" ""
check "stats fin avant début : 400" "code == 400"

# ---------------------------------------------------------------------------
echo "== 6. Dernier poids moyen (pesées)"
api GET "/pesees/dernier-poids-moyen?projetUniqueId=kilo-projet-sans-pesee" ""
check "projet sans session terminée : 200, data null" "code == 200 and d.get('data') is None"
api GET "/pesees/dernier-poids-moyen?projetUniqueId=pesee-autre-projet" ""
check "projet d'une autre ferme : 400 introuvable" "code == 400 and 'introuvable' in $(err)"
api GET "/pesees/dernier-poids-moyen" ""
check "sans projet : 400" "code == 400"

# Session web neuve terminée maintenant (dateFin la plus récente si aucune autre n'est
# dans le futur) ; l'attendu est de toute façon relu en base.
api POST /pesees/sessions "{\"projetUniqueId\":\"$PROJET\",\"nombreParDefaut\":4}"
WS="$(jval "d['data']['uniqueId']" 2>/dev/null)"
if [ "$(cat "$TMP/code")" = "200" ]; then
  api POST "/pesees/sessions/$WS/pesees" '{"nombreSujets":4,"poidsKg":7.4}'
  api POST "/pesees/sessions/$WS/pesees" '{"nombreSujets":4,"poidsKg":7.6}'
  api POST "/pesees/sessions/$WS/terminer" ""
  check "session web terminée : 8 sujets, 15 kg, moyenne 1.875" "code == 200 and d['data']['statut'] == 'TERMINEE' and d['data']['poidsMoyenKg'] == 1.875"
else
  echo "   (session web non ouverte : une session est peut-être déjà EN_COURS sur ce projet)"
fi
read -r ATT_SESSION ATT_POIDS < <(psql_run "SELECT s.unique_id || ' ' || s.poids_moyen_kg FROM sessions_pesee s JOIN projets p ON p.id = s.projet_id
  WHERE p.unique_id = '$PROJET' AND s.statut = 'TERMINEE' AND s.removed = false ORDER BY s.date_fin DESC, s.id DESC LIMIT 1")
api GET "/pesees/dernier-poids-moyen?projetUniqueId=$PROJET" ""
check "dernière session TERMINEE du projet ($ATT_SESSION, $ATT_POIDS kg)" \
  "code == 200 and d['data']['sessionUniqueId'] == '$ATT_SESSION' and abs(d['data']['poidsMoyenKg'] - $ATT_POIDS) < 1e-9 and d['data']['dateFin'] and d['data']['projetUniqueId'] == '$PROJET'"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
