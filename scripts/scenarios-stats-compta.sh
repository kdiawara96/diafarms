#!/usr/bin/env bash
# Chiffres de la Comptabilité (/transactions/stats) : script de bout en bout.
#
# Vue ferme entière : "Entrées validées" = argent vraiment rentré (= Encaissé) :
# paiements clients + ventes sans client au montant rapporté + ventes diverses + autres
# entrées. Une vente à un client et le paiement de ce client ne comptent qu'UNE fois (par
# le paiement). Vue par projet (admin "voir comme" un comptable) : valeur des ventes des
# projets, inchangée. Les filtres (période, transactions VALIDES) s'appliquent pareil à
# toutes les parties de l'encaissé.
#
# Les vérifications portent sur des ÉCARTS (avant / après) sur une période donnée : le
# script est rejouable sur une base qui contient déjà des données.
#
# Pré-requis : Postgres + backend démarrés, base seedée par scenarios-circuit-client.sh
# (admin@t.local, compta@t.local, magasin « Boutique Scen » avec du stock d'œufs).
# Variables : BASE, PGHOST, PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen),
# ADMIN_EMAIL / ADMIN_PWD. Sortie : une ligne OK/ECHEC par assertion ; code 0 si tout est OK.

set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@t.local}"
ADMIN_PWD="${ADMIN_PWD:-Test1234!}"
COMPTA_EMAIL="${COMPTA_EMAIL:-compta@t.local}"

TMP="$(mktemp -d)"
PASS=0
FAIL=0

psql_run() {
  local args=(-p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE")
  [ -n "${PGHOST:-}" ] && args=(-h "$PGHOST" "${args[@]}")
  psql "${args[@]}" -v ON_ERROR_STOP=1 -Atc "$1"
}

jval() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$TMP/body" "$1"; }

check_code() { # libellé, codes acceptés (ex: "200 201")
  local code; code="$(cat "$TMP/code")"
  if [[ " $2 " == *" $code "* ]]; then echo "OK     $1"; PASS=$((PASS+1))
  else echo "ECHEC  $1 (HTTP $code : $(head -c 400 "$TMP/body"))"; FAIL=$((FAIL+1)); fi
}

check_num() { # libellé, attendu, obtenu
  if python3 -c 'import sys; sys.exit(0 if abs(float(sys.argv[1]) - float(sys.argv[2])) < 0.01 else 1)' "$2" "$3" 2>/dev/null
  then echo "OK     $1 ($3)"; PASS=$((PASS+1))
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

# stats DEBUT FIN [financierUniqueId] -> "entrees encaisse vendu recuVentes vueParProjet"
stats() {
  api GET "/transactions/stats?dateDebut=$1&dateFin=$2${3:+&financierUniqueId=$3}"
  python3 -c 'import json,sys
d = json.load(open(sys.argv[1]))["data"]
print(d["totalEntreesValidees"], d["totalEncaisse"], d["totalVendu"], d["totalMontantRecuVentes"], d["vueParProjet"])' "$TMP/body"
}
delta() { python3 -c 'import sys; print(round(float(sys.argv[2]) - float(sys.argv[1]), 2))' "$1" "$2"; }

# ---------------------------------------------------------------------------
TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }
FARM_ID="$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
BOUTIQUE="$(psql_run "SELECT unique_id FROM magasins_vente WHERE nom = 'Boutique Scen' AND farm_id = $FARM_ID LIMIT 1")"
COMPTA_ID="$(psql_run "SELECT id FROM utilisateurs WHERE email = '$COMPTA_EMAIL'")"
COMPTA_UID="$(psql_run "SELECT unique_id FROM utilisateurs WHERE email = '$COMPTA_EMAIL'")"
[ -n "$BOUTIQUE" ] && [ -n "$COMPTA_UID" ] || { echo "ECHEC  base non seedée (lancer scenarios-circuit-client.sh)"; exit 1; }

# Vue par projet : le comptable devient responsable finance de TOUS les projets de la
# ferme le temps du script (restauré à la sortie), pour que la vue projet couvre toutes
# les parts de vente quel que soit le projet qui a fourni les œufs.
psql_run "CREATE TABLE IF NOT EXISTS _scen_finance_backup (id bigint primary key, finance_user_id bigint)" >/dev/null
psql_run "INSERT INTO _scen_finance_backup SELECT id, finance_user_id FROM projets WHERE farm_id = $FARM_ID ON CONFLICT (id) DO NOTHING" >/dev/null
psql_run "UPDATE projets SET finance_user_id = $COMPTA_ID WHERE farm_id = $FARM_ID" >/dev/null
restaurer() {
  psql_run "UPDATE projets p SET finance_user_id = b.finance_user_id FROM _scen_finance_backup b WHERE b.id = p.id" >/dev/null
  psql_run "DROP TABLE _scen_finance_backup" >/dev/null
  rm -rf "$TMP"
}
trap restaurer EXIT

AUJ="$(date +%F)"
# Période passée propre au script : un jour tiré au hasard en 2021 (aucune vente ni
# collecte n'y est jamais créée par les autres scénarios).
PASSE="2021-$(printf '%02d' $((RANDOM % 12 + 1)))-$(printf '%02d' $((RANDOM % 28 + 1)))"
SUFFIXE="$(python3 -c 'import uuid; print(uuid.uuid4().hex[:8])')"
TEL="7$(python3 -c 'import random; print(random.randint(1000000, 9999999))')"

echo "== 0. Situation de départ (période du jour $AUJ, période passée $PASSE)"
read -r E0 C0 V0 R0 P0 < <(stats "$AUJ" "$AUJ")
read -r PE0 PC0 PV0 PR0 PP0 < <(stats "$AUJ" "$AUJ" "$COMPTA_UID")
read -r QE0 QC0 QV0 QR0 QP0 < <(stats "$PASSE" "$PASSE")
check_num "vue ferme entière : entrées validées = encaissé (départ)" "$C0" "$E0"
[ "$P0" = "False" ] && [ "$PP0" = "True" ] && { echo "OK     vues ferme entière / par projet reconnues"; PASS=$((PASS+1)); } \
  || { echo "ECHEC  vueParProjet inattendu (ferme=$P0, projet=$PP0)"; FAIL=$((FAIL+1)); }

echo "== 1. Vente 10 000 à un client + son paiement 10 000, vente directe 5 000, entrée manuelle 2 000"
api POST /clients/create "{\"nom\":\"Stats compta $SUFFIXE\",\"telephone\":\"$TEL\"}"
check_code "client créé" "200 201"
CLIENT="$(jval "d['data']['uniqueId']")"
api POST /ventes-oeufs/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$BOUTIQUE\",\"clientUniqueId\":\"$CLIENT\",\"quantiteOeufs\":10,\"prixUnitaire\":1000,\"montant\":10000}"
check_code "vente à client 10 000 créée" "200 201"
VENTE_CLIENT="$(jval "d['data']['uniqueId']")"
api POST /paiements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"montant\":10000,\"mode\":\"ESPECES\",\"date\":\"$AUJ\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$VENTE_CLIENT\"}"
check_code "paiement client 10 000 enregistré" "200 201"
api POST /ventes-oeufs/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$BOUTIQUE\",\"quantiteOeufs\":5,\"prixUnitaire\":1000,\"montant\":5000,\"montantRapporte\":5000}"
check_code "vente directe 5 000 créée" "200 201"
VENTE_DIRECTE="$(jval "d['data']['uniqueId']")"
api POST /transactions/create "{\"type\":\"ENTREE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Apport stats $SUFFIXE\",\"montant\":2000,\"categorie\":\"Divers\"}"
check_code "entrée manuelle 2 000 créée" "200 201"

read -r E1 C1 V1 R1 P1 < <(stats "$AUJ" "$AUJ")
check_num "ferme entière : entrées validées +17 000 (pas 27 000)" "17000" "$(delta "$E0" "$E1")"
check_num "ferme entière : encaissé +17 000" "17000" "$(delta "$C0" "$C1")"
check_num "ferme entière : entrées validées = encaissé" "$C1" "$E1"
check_num "ferme entière : vendu +15 000 (valeur des ventes)" "15000" "$(delta "$V0" "$V1")"
check_num "montant reçu des ventes +15 000 (paiement + vente directe)" "15000" "$(delta "$R0" "$R1")"
read -r PE1 PC1 PV1 PR1 PP1 < <(stats "$AUJ" "$AUJ" "$COMPTA_UID")
check_num "vue par projet : entrées validées +15 000 (valeur des ventes, sans paiement ni entrée commune)" "15000" "$(delta "$PE0" "$PE1")"
check_num "vue par projet : vendu +15 000" "15000" "$(delta "$PV0" "$PV1")"
check_num "vue par projet : encaissé non calculé (0)" "0" "$PC1"

echo "== 2. Vente directe rapportée en partie : l'encaissé suit le montant rapporté"
api PUT "/ventes-oeufs/update/$VENTE_DIRECTE" '{"montantRapporte":4000}'
check_code "montant rapporté ramené à 4 000" "200"
read -r E2 C2 V2 R2 P2 < <(stats "$AUJ" "$AUJ")
check_num "ferme entière : entrées validées +16 000" "16000" "$(delta "$E0" "$E2")"
check_num "ferme entière : vendu toujours +15 000" "15000" "$(delta "$V0" "$V2")"
read -r PE2 PC2 PV2 PR2 PP2 < <(stats "$AUJ" "$AUJ" "$COMPTA_UID")
check_num "vue par projet : toujours la valeur des ventes (+15 000)" "15000" "$(delta "$PE0" "$PE2")"
api PUT "/ventes-oeufs/update/$VENTE_DIRECTE" '{"montantRapporte":5000}'
check_code "montant rapporté remis à 5 000" "200"

echo "== 3. Statut : une vente directe dont la transaction n'est pas VALIDE ne compte plus"
T_DIRECTE="$(psql_run "SELECT string_agg(t.unique_id, ',') FROM transactions t JOIN ventes_oeufs_repartitions r ON r.unique_id = t.source_unique_id JOIN ventes_oeufs v ON v.id = r.vente_oeufs_id WHERE v.unique_id = '$VENTE_DIRECTE' AND coalesce(t.removed,false) = false")"
IN_LIST="'${T_DIRECTE//,/\',\'}'"
psql_run "UPDATE transactions SET statut = 'EN_ATTENTE' WHERE unique_id IN ($IN_LIST)" >/dev/null
read -r E3 C3 V3 R3 P3 < <(stats "$AUJ" "$AUJ")
check_num "transaction en attente : entrées validées +12 000" "12000" "$(delta "$E0" "$E3")"
check_num "transaction en attente : montant reçu des ventes +10 000" "10000" "$(delta "$R0" "$R3")"
psql_run "UPDATE transactions SET statut = 'VALIDE' WHERE unique_id IN ($IN_LIST)" >/dev/null
read -r E3 C3 V3 R3 P3 < <(stats "$AUJ" "$AUJ")
check_num "transaction revalidée : entrées validées +17 000" "17000" "$(delta "$E0" "$E3")"

echo "== 4. Période : paiement et entrée datés dans le passé, comptés sur LEUR période seulement"
api POST /paiements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"montant\":3000,\"mode\":\"ESPECES\",\"date\":\"$PASSE\"}"
check_code "paiement client 3 000 daté du $PASSE" "200 201"
api POST /transactions/create "{\"type\":\"ENTREE\",\"commun\":true,\"date\":\"$PASSE\",\"description\":\"Apport passé stats $SUFFIXE\",\"montant\":700,\"categorie\":\"Divers\"}"
check_code "entrée manuelle 700 datée du $PASSE" "200 201"
read -r Q1 QC1 QV1 QR1 QP1 < <(stats "$PASSE" "$PASSE")
check_num "période passée : entrées validées +3 700" "3700" "$(delta "$QE0" "$Q1")"
check_num "période passée : encaissé +3 700" "3700" "$(delta "$QC0" "$QC1")"
check_num "période passée : vendu inchangé" "0" "$(delta "$QV0" "$QV1")"
read -r E4 C4 V4 R4 P4 < <(stats "$AUJ" "$AUJ")
check_num "période du jour : toujours +17 000 (rien du passé)" "17000" "$(delta "$E0" "$E4")"

echo "== 5. Suppression de la vente directe : retirée de l'encaissé"
api PUT "/ventes-oeufs/deleteOrRecover/$VENTE_DIRECTE" '{"motif":"Vente de test (stats comptabilité)"}'
check_code "vente directe supprimée" "200"
read -r E5 C5 V5 R5 P5 < <(stats "$AUJ" "$AUJ")
check_num "après suppression : entrées validées +12 000" "12000" "$(delta "$E0" "$E5")"
check_num "après suppression : vendu +10 000" "10000" "$(delta "$V0" "$V5")"

echo
echo "Résultat : $PASS OK, $FAIL échec(s)."
[ "$FAIL" -eq 0 ]
