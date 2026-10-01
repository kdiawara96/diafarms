#!/usr/bin/env bash
# Cohérence des formulaires mobile / web / serveur (audit du 2026-10-01). Le serveur est
# la seule source de vérité : quel que soit le client (web, APK 1.34/1.35 en production),
# la même saisie donne le même résultat. Pour chaque point d'entrée modifié, une charge
# « téléphone 1.34 » (champs envoyés par cette version) doit toujours être acceptée.
#
# 1. Salaire : montant calculé au taux de LA PÉRIODE (le `montant` envoyé par le téléphone
#    est ignoré), `montantForce` pour une prime, `datePaiement` (passé permis, futur refusé).
# 2. Entrée/Sortie d'argent : montant > 0, catégorie ou description obligatoire,
#    « Autre » + categoriePrecision, montants au franc, Santé quantité x prix.
# 3. Arrondi au franc : ventes d'œufs, ventes diverses, commandes, paiements client,
#    achats d'aliment et de médicament.
# 4. Dates futures : entretien, date de commande (la livraison PRÉVUE peut être future),
#    vente de fientes passée par une entrée d'argent.
# 5. Entretien : poulailler d'une autre ferme refusé, date/heure mal formées = 400.
# 6. Durcissement : mortalité ≤ 0, collecte à 0 œuf, soin sans produit refusés.
#
# Pré-requis : Postgres + backend démarrés, base seedée par scenarios-circuit-client.sh.
# Variables : BASE, PGHOST, PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen),
# ADMIN_EMAIL / ADMIN_PWD. Rejouable. Sortie : une ligne OK/ECHEC par assertion ; code 0
# si tout est OK.

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
FUTUR="$(date -d '+3 days' +%F)"      # au-delà de la tolérance d'un jour (DateSaisie)
HIER="$(date -d '1 day ago' +%F)"
PASSE="$(date -d '5 days ago' +%F)"
MOIS_1="$(date -d "$(date +%Y-%m-15) -2 month" +%Y-%m)"   # deux mois passés, payés à l'ancien taux
MOIS_2="$(date -d "$(date +%Y-%m-15) -3 month" +%Y-%m)"
MOIS_3="$(date -d "$(date +%Y-%m-15) -4 month" +%Y-%m)"
MOIS_4="$(date -d "$(date +%Y-%m-15) -5 month" +%Y-%m)"

echo "== 1. Salaire : taux de la période, date du paiement, montant forcé"
api POST /personnel/create "{\"nom\":\"Coherence $SUFFIXE\",\"poste\":\"Ouvrier\"}"
EMP="$(jval "d['data']['uniqueId']")"
api POST /salaires/definir "{\"employeUniqueId\":\"$EMP\",\"modePaiement\":\"MENSUEL\",\"tauxBase\":30000}"
check "grille : 30000 / mois" "code in (200, 201)"
# L'ancien taux est en vigueur depuis janvier ; la grille passe à 36000 aujourd'hui.
psql_run "UPDATE salaire_historiques SET date_effective = DATE '$(date +%Y)-01-01' - 365
  WHERE salaire_id = (SELECT s.id FROM salaires s JOIN personnel e ON e.id = s.employe_id WHERE e.unique_id = '$EMP')" >/dev/null
api POST /salaires/definir "{\"employeUniqueId\":\"$EMP\",\"modePaiement\":\"MENSUEL\",\"tauxBase\":36000}"
check "grille changée : 36000 / mois" "code in (200, 201)"

# Téléphone 1.34 : envoie toujours `montant` (taux de SA dernière synchro), jamais de date.
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP\",\"periode\":\"$MOIS_1\",\"montant\":36000,\"description\":null}"
check "1.34 : paiement d'un mois passé accepté" "code == 201"
check_eq "1.34 : montant au taux de la période (30000), pas celui envoyé (36000)" "30000|$AUJ" \
  "$(psql_run "SELECT p.montant_paye::bigint || '|' || p.date_paiement FROM paiements_salaire p JOIN salaires s ON s.id = p.salaire_id
     JOIN personnel e ON e.id = s.employe_id WHERE e.unique_id = '$EMP' AND p.periode = '$MOIS_1'")"
check_eq "1.34 : dépense « Salaires » du même montant, datée du jour de réception" "30000|$AUJ" \
  "$(psql_run "SELECT t.montant::bigint || '|' || t.date FROM transactions t JOIN paiements_salaire p ON p.unique_id = t.source_unique_id
     JOIN salaires s ON s.id = p.salaire_id JOIN personnel e ON e.id = s.employe_id WHERE e.unique_id = '$EMP' AND p.periode = '$MOIS_1'")"

api POST /salaires/payer "{\"employeUniqueId\":\"$EMP\",\"periode\":\"$MOIS_2\",\"datePaiement\":\"$FUTUR\"}"
check "date de paiement dans le futur : 400" "code == 400 and 'futur' in err"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP\",\"periode\":\"$MOIS_2\",\"datePaiement\":\"30/09/2026\"}"
check "date de paiement mal formée : 400" "code == 400 and 'Date invalide' in err"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP\",\"periode\":\"$MOIS_2\",\"montant\":99999,\"datePaiement\":\"$PASSE\"}"
check "paiement hors ligne daté du $PASSE : accepté" "code == 201"
check_eq "paiement et dépense gardent la date du paiement ($PASSE), montant au taux de la période" "30000|$PASSE|$PASSE" \
  "$(psql_run "SELECT p.montant_paye::bigint || '|' || p.date_paiement || '|' || t.date FROM paiements_salaire p
     JOIN transactions t ON t.source_unique_id = p.unique_id JOIN salaires s ON s.id = p.salaire_id
     JOIN personnel e ON e.id = s.employe_id WHERE e.unique_id = '$EMP' AND p.periode = '$MOIS_2'")"

api POST /salaires/payer "{\"employeUniqueId\":\"$EMP\",\"periode\":\"$MOIS_3\",\"montantForce\":31000.4}"
check "montant forcé (prime) : accepté" "code == 201"
check_eq "montant forcé arrondi au franc" "31000" \
  "$(psql_run "SELECT p.montant_paye::bigint FROM paiements_salaire p JOIN salaires s ON s.id = p.salaire_id
     JOIN personnel e ON e.id = s.employe_id WHERE e.unique_id = '$EMP' AND p.periode = '$MOIS_3'")"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP\",\"periode\":\"$MOIS_4\",\"montantForce\":0}"
check "montant forcé nul : 400" "code == 400"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP\",\"periode\":\"$(date +%Y-%m)\"}"
check "mois courant sans montant : taux actuel (36000)" "code == 201 and d['data']['montantPaye'] == 36000"

api POST /personnel/create "{\"nom\":\"Journalier $SUFFIXE\",\"poste\":\"Ouvrier\"}"
EMP_J="$(jval "d['data']['uniqueId']")"
api POST /salaires/definir "{\"employeUniqueId\":\"$EMP_J\",\"modePaiement\":\"JOURNALIER\",\"tauxBase\":2500.5}"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP_J\",\"periode\":\"$(date +%Y-%m)\",\"quantite\":3,\"montant\":1}"
check "1.34 journalier (3 jours, montant envoyé 1) : accepté" "code == 201"
check_eq "journalier : taux x jours arrondi au franc (2500,5 x 3 = 7501,5 -> 7502)" "7502" \
  "$(psql_run "SELECT p.montant_paye::bigint FROM paiements_salaire p JOIN salaires s ON s.id = p.salaire_id
     JOIN personnel e ON e.id = s.employe_id WHERE e.unique_id = '$EMP_J'")"

echo "== 2. Entrée / Sortie d'argent"
api POST /transactions/create "{\"type\":\"ENTREE\",\"commun\":true,\"categorie\":\"Don\",\"description\":\"zéro\",\"montant\":0,\"date\":\"$AUJ\"}"
check "montant 0 : 400" "code == 400 and 'supérieur à 0' in err"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"categorie\":\"Logistique\",\"description\":\"négatif\",\"montant\":-500,\"date\":\"$AUJ\"}"
check "montant négatif : 400" "code == 400"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"categorie\":\"\",\"description\":\"  \",\"montant\":500,\"date\":\"$AUJ\"}"
check "ni catégorie ni description : 400" "code == 400 and 'catégorie' in err"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"categorie\":\"Logistique\",\"description\":\"futur\",\"montant\":500,\"date\":\"$FUTUR\"}"
check "date future : 400" "code == 400 and 'futur' in err"

# Téléphone 1.34 : catégorie « Autre » telle quelle, description obligatoire, projets concernés.
api POST /transactions/create "{\"type\":\"ENTREE\",\"commun\":true,\"projetUniqueId\":null,\"projetsConcernesUniqueIds\":[],\"date\":\"$HIER\",\"description\":\"Gardiennage $SUFFIXE\",\"montant\":1500.6,\"categorie\":\"Autre\",\"siteUniqueId\":null,\"batimentUniqueId\":null}"
check "1.34 entrée « Autre » : acceptée" "code == 201"
T134="$(jval "d['data']['uniqueId']")"
check_eq "1.34 : catégorie « Autre » gardée, montant au franc (1501)" "Autre|1501" \
  "$(psql_run "SELECT categorie || '|' || montant::bigint FROM transactions WHERE unique_id = '$T134'")"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"\",\"montant\":800,\"categorie\":\"Autre\",\"categoriePrecision\":\"Gardiennage\"}"
check "« Autre » + précision : acceptée" "code == 201"
check_eq "« Autre » + précision : la précision devient la catégorie" "Gardiennage" \
  "$(psql_run "SELECT categorie FROM transactions WHERE unique_id = '$(jval "d['data']['uniqueId']")'")"

# Santé : 1.34 envoie montant + quantité + prix unitaire ; sans montant, le serveur calcule.
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":false,\"projetUniqueId\":\"$PROJET\",\"date\":\"$AUJ\",\"description\":\"Visite véto $SUFFIXE\",\"montant\":999.99,\"categorie\":\"Santé / Vétérinaire\",\"quantite\":3,\"prixUnitaire\":333.33}"
check "1.34 service Santé : accepté" "code == 201"
check_eq "1.34 Santé : montant au franc (999,99 -> 1000)" "1000" \
  "$(psql_run "SELECT montant::bigint FROM transactions WHERE unique_id = '$(jval "d['data']['uniqueId']")'")"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":false,\"projetUniqueId\":\"$PROJET\",\"date\":\"$AUJ\",\"description\":\"Visite sans total $SUFFIXE\",\"categorie\":\"Santé / Vétérinaire\",\"quantite\":3,\"prixUnitaire\":333.5}"
check "Santé sans montant : calculé par le serveur" "code == 201"
check_eq "Santé : round(3 x 333,5 = 1000,5) = 1001" "1001" \
  "$(psql_run "SELECT montant::bigint FROM transactions WHERE unique_id = '$(jval "d['data']['uniqueId']")'")"

api PUT "/transactions/update/$T134" "{\"montant\":-1}"
check "modification : montant négatif refusé" "code == 400"
api PUT "/transactions/update/$T134" "{\"montant\":2500.4}"
check_eq "modification : montant au franc" "2500" "$(psql_run "SELECT montant::bigint FROM transactions WHERE unique_id = '$T134'")"
api PUT "/transactions/update/$T134" "{\"categorie\":\"Autre\",\"categoriePrecision\":\"Location matériel\"}"
check_eq "modification : « Autre » + précision" "Location matériel" "$(psql_run "SELECT categorie FROM transactions WHERE unique_id = '$T134'")"

echo "== 3. Ventes de fientes et autres ventes"
# Téléphone 1.34 : la vente de fientes est une Entrée d'argent « Vente fientes ».
api POST /transactions/create "{\"type\":\"ENTREE\",\"commun\":true,\"date\":\"$FUTUR\",\"description\":\"10 sacs\",\"montant\":5000,\"categorie\":\"Vente fientes\"}"
check "1.34 fientes datée dans le futur : 400" "code == 400 and 'futur' in err"
api POST /transactions/create "{\"type\":\"ENTREE\",\"commun\":true,\"date\":\"$HIER\",\"description\":\"10 sacs $SUFFIXE\",\"montant\":5000.5,\"categorie\":\"Vente fientes\",\"siteUniqueId\":null,\"batimentUniqueId\":null}"
check "1.34 fientes par une Entrée d'argent : acceptée" "code == 201"
check_eq "1.34 fientes : vraie vente diverse au franc, datée d'hier" "FIENTES|5001|$HIER" \
  "$(psql_run "SELECT produit || '|' || montant::bigint || '|' || date FROM ventes_diverses WHERE description = '10 sacs $SUFFIXE'")"
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"$AUJ\",\"quantite\":3,\"prixUnitaire\":1500.5,\"description\":null}"
check "fientes (contrat mobile) sans montant : accepté" "code == 201"
check_eq "fientes : montant = round(3 x 1500,5 = 4501,5) = 4502" "4502" \
  "$(psql_run "SELECT montant::bigint FROM ventes_diverses WHERE unique_id = '$(jval "d['data']['uniqueId']")'")"
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"$FUTUR\",\"montant\":1000}"
check "fientes datée dans le futur : 400" "code == 400"
api POST /ventes-diverses/create "{\"produit\":\"AUTRE\",\"date\":\"$AUJ\",\"montant\":1000}"
check "autre vente sans description : 400" "code == 400 and 'description' in err"
api POST /ventes-diverses/create "{\"produit\":\"AUTRE\",\"date\":\"$AUJ\",\"montant\":1999.5,\"description\":\"Vieux grillage\"}"
check_eq "autre vente : montant au franc" "2000" "$(psql_run "SELECT montant::bigint FROM ventes_diverses WHERE unique_id = '$(jval "d['data']['uniqueId']")'")"

echo "== 4. Entretien : dates, description, poulailler de la ferme"
ENT_1_34="{\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"heure\":\"08:15\",\"niveau\":\"BATIMENT\",\"type\":\"NETTOYAGE\",\"description\":\"Nettoyage $SUFFIXE\",\"observations\":null}"
api POST /entretiens/create "$ENT_1_34"
check "1.34 entretien : accepté" "code == 201"
ENT="$(jval "d['data']['uniqueId']")"
api POST /entretiens/create "${ENT_1_34/$AUJ/$FUTUR}"
check "entretien daté dans le futur : 400" "code == 400 and 'futur' in err"
api POST /entretiens/create "${ENT_1_34/$AUJ/01-10-2026}"
check "entretien, date mal formée : 400 (plus 500)" "code == 400 and 'Date invalide' in err"
api POST /entretiens/create "${ENT_1_34/08:15/8h}"
check "entretien, heure mal formée : 400" "code == 400 and 'Heure invalide' in err"
api POST /entretiens/create "{\"batimentUniqueId\":\"$BATIMENT\",\"niveau\":\"BATIMENT\",\"type\":\"AUTRE\",\"description\":\" \"}"
check "entretien sans description : 400" "code == 400"
api POST /batiments/create "{\"nom\":\"Poulailler autre ferme $SUFFIXE\",\"capacite\":100}"
AUTRE_BAT="$(jval "d['data']['uniqueId']")"
psql_run "UPDATE batiments SET farm_id = NULL WHERE unique_id = '$AUTRE_BAT'" >/dev/null
api POST /entretiens/create "{\"batimentUniqueId\":\"$AUTRE_BAT\",\"niveau\":\"BATIMENT\",\"type\":\"AUTRE\",\"description\":\"Intrus\"}"
check "entretien sur le poulailler d'une autre ferme : refusé" "code == 400 and 'introuvable' in err"
api PUT "/entretiens/update/$ENT" "{\"batimentUniqueId\":\"$AUTRE_BAT\"}"
check "modification vers le poulailler d'une autre ferme : refusée" "code == 400 and 'introuvable' in err"
api PUT "/entretiens/update/$ENT" "{\"date\":\"$FUTUR\"}"
check "modification vers une date future : 400" "code == 400"
api PUT "/entretiens/update/$ENT" "{\"observations\":\"RAS\"}"
check "modification sans date : date gardée" "code == 200 and d['data']['date'] == '$AUJ'"
psql_run "UPDATE batiments SET removed = true WHERE unique_id = '$AUTRE_BAT'" >/dev/null

echo "== 5. Commandes, livraisons, encaissements"
api POST /magasins/create "{\"nom\":\"Boutique coh $SUFFIXE\",\"type\":\"VENTE\"}"
BOUTIQUE="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Stock coh $SUFFIXE\",\"type\":\"STOCKAGE\",\"magasinVenteParDefautUniqueId\":\"$BOUTIQUE\"}"
STOCK="$(jval "d['data']['uniqueId']")"
api POST /clients/create "{\"nom\":\"Client coh $SUFFIXE\",\"telephone\":\"6$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\"}"
CLIENT="$(jval "d['data']['uniqueId']")"
CMD_1_34="{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"OEUFS\",\"quantite\":10,\"prixUnitaireEstime\":100.04,\"montantEstime\":1000.4,\"montantAcompte\":300.6,\"modePaiement\":\"ESPECES\",\"dateCommande\":\"$PASSE\",\"dateLivraisonPrevue\":\"$FUTUR\"}"
api POST /commandes/create "${CMD_1_34/\"dateCommande\":\"$PASSE\"/\"dateCommande\":\"$FUTUR\"}"
check "date de commande future : 400" "code == 400 and 'futur' in err"
api POST /commandes/create "$CMD_1_34"
check "1.34 commande (date passée, livraison prévue future) : acceptée" "code == 201"
CMD="$(jval "d['data']['uniqueId']")"
check_eq "commande : date gardée, montants au franc (1000, acompte 301)" "$PASSE|$FUTUR|1000|301" \
  "$(psql_run "SELECT date_commande || '|' || date_livraison_prevue || '|' || montant_estime::bigint || '|' || montant_acompte::bigint FROM commandes WHERE unique_id = '$CMD'")"
check_eq "acompte encaissé au franc, daté de la commande" "301|$PASSE" \
  "$(psql_run "SELECT p.montant::bigint || '|' || p.date FROM paiements_client p JOIN commandes c ON c.id = p.commande_id WHERE c.unique_id = '$CMD'")"
api POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUTIQUE\",\"type\":\"REFORME\",\"quantite\":5,\"tarification\":\"KILO\",\"prixKgEstime\":1500,\"poidsEstimeKg\":12.345,\"montantEstime\":18517.5}"
check "commande au kilo : acceptée" "code == 201"
check_eq "commande au kilo : round(12,345 x 1500 = 18517,5) = 18518" "18518" \
  "$(psql_run "SELECT montant_estime::bigint FROM commandes WHERE unique_id = '$(jval "d['data']['uniqueId']")'")"

# Encaissement : sur une commande (argent réservé) ou sur une vente, comme le téléphone.
api POST /paiements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"commandeUniqueId\":\"$CMD\",\"montant\":200.5,\"mode\":\"ESPECES\",\"date\":\"$HIER\",\"observations\":null}"
check "1.34 paiement réservé à une commande : accepté" "code == 201"
check_eq "paiement sur commande : au franc, rattaché à la commande" "201|$HIER" \
  "$(psql_run "SELECT p.montant::bigint || '|' || p.date FROM paiements_client p JOIN commandes c ON c.id = p.commande_id
     WHERE c.unique_id = '$CMD' AND p.origine <> 'ACOMPTE'")"
api POST /paiements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"montant\":100,\"mode\":\"ESPECES\",\"date\":\"$FUTUR\"}"
check "paiement daté dans le futur : 400" "code == 400"
api POST /paiements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"montant\":0.4,\"mode\":\"ESPECES\"}"
check "paiement de 0,4 F (0 au franc) : 400" "code == 400"

api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"magasinStockageUniqueId\":\"$STOCK\",\"date\":\"$PASSE\",\"oeufsCollectes\":60,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
check "1.34 collecte : 60 œufs dans la boutique" "code == 201"
api POST /ventes-oeufs/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"quantiteOeufs\":3,\"prixUnitaire\":33.3333,\"montant\":99.9999,\"montantRapporte\":99.9999,\"typeOeuf\":\"BON\",\"date\":\"$AUJ\"}"
check "1.34 vente d'œufs sans client : acceptée" "code == 201"
check_eq "vente d'œufs : montant et montant rapporté au franc, prix unitaire gardé" "100|100|33.3333" \
  "$(psql_run "SELECT montant::bigint || '|' || montant_rapporte::bigint || '|' || prix_unitaire FROM ventes_oeufs WHERE unique_id = '$(jval "d['data']['uniqueId']")'")"
api POST /ventes-oeufs/create "{\"magasinUniqueId\":\"$BOUTIQUE\",\"clientUniqueId\":\"$CLIENT\",\"quantiteOeufs\":5,\"prixUnitaire\":45.1,\"montant\":225.5,\"montantRapporte\":100.5,\"modePaiement\":\"ESPECES\",\"date\":\"$AUJ\"}"
check "1.34 vente d'œufs à un client avec argent reçu : acceptée" "code == 201"
VENTE="$(jval "d['data']['uniqueId']")"
check_eq "vente à un client : 226, argent reçu 101 (paiement à la vente)" "226|101" \
  "$(psql_run "SELECT v.montant::bigint || '|' || (SELECT sum(p.montant)::bigint FROM paiements_client p WHERE p.vente_cible_unique_id = v.unique_id)
     FROM ventes_oeufs v WHERE v.unique_id = '$VENTE'")"
api POST /paiements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"montant\":50,\"mode\":\"ESPECES\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$VENTE\"}"
check "paiement ciblé sur une vente : accepté" "code == 201"

api POST "/commandes/$CMD/livrer?quantite=3&montantRecu=10.6&mode=ESPECES&date=$HIER&heure=10:00" ""
check "livraison datée d'hier : acceptée" "code == 200"
check_eq "livraison : vente au franc (3 x 100,04 = 300,12 -> 300), datée d'hier" "300|$HIER" \
  "$(psql_run "SELECT v.montant::bigint || '|' || v.date FROM ventes_oeufs v JOIN commandes c ON c.id = v.commande_id WHERE c.unique_id = '$CMD'")"

echo "== 6. Achats (aliment, médicament) au franc"
api POST "/alimentations/create/$PROJET" "{\"typeAliment\":\"PONTE\",\"sac\":2,\"quantiteKg\":100,\"coutTotal\":25000.7,\"dateDistribution\":\"$AUJ\"}"
check "1.34 achat d'aliment : accepté" "code in (200, 201)"
check_eq "achat d'aliment : coût et dépense au franc" "25001|25001" \
  "$(psql_run "SELECT a.cout_total::bigint || '|' || t.montant::bigint FROM alimentations a JOIN transactions t ON t.source_unique_id = a.unique_id
     WHERE a.unique_id = '$(jval "d['data']['uniqueId']")'")"
api POST "/medicaments/create/$PROJET" "{\"nom\":\"Vitamine coh $SUFFIXE\",\"forme\":\"POUDRE\",\"unite\":\"sachet\",\"quantite\":4,\"coutTotal\":1234.5,\"dateAchat\":\"$AUJ\"}"
check "1.34 achat de médicament : accepté" "code in (200, 201)"
check_eq "achat de médicament : coût au franc, prix unitaire à 2 décimales" "1235|308.75" \
  "$(psql_run "SELECT cout_total::bigint || '|' || prix_unitaire FROM achats_medicament WHERE nom = 'Vitamine coh $SUFFIXE'")"

echo "== 7. Durcissement des saisies de production"
api POST /mortalites/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"nombreMorts\":0,\"cause\":\"test\"}"
check "mortalité à 0 : 400" "code == 400"
api POST /mortalites/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"heure\":\"07:00\",\"nombreMorts\":1,\"cause\":\"Chaleur\"}"
check "1.34 mortalité : acceptée" "code == 201"
api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"magasinStockageUniqueId\":\"$STOCK\",\"date\":\"$AUJ\",\"oeufsCollectes\":0,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
check "collecte à 0 œuf : 400" "code == 400"
api POST /soins/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"type\":\"VACCINATION\",\"produit\":\" \",\"quantite\":100}"
check "soin sans produit : 400" "code == 400 and 'produit' in err"
api POST /soins/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"heure\":\"09:00\",\"type\":\"VACCINATION\",\"produit\":\"Newcastle\",\"quantite\":500,\"modeAdministration\":[\"Oral\"],\"observations\":null}"
check "1.34 vaccination : acceptée" "code == 201"
api POST /soins/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"type\":\"MEDICAMENT\",\"produit\":\"Vitamine coh $SUFFIXE\",\"quantite\":1,\"depuisStock\":true,\"unite\":\"sachet\"}"
check "soin pris dans le stock du projet : accepté" "code == 201"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
