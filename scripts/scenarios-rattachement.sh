#!/usr/bin/env bash
# « Cette dépense concerne » (2026-10-01) : une dépense est rattachée au Projet (poulailler
# facultatif occupé par ce projet, jamais de site), à Un site (ni projet ni poulailler) ou
# à Toute la ferme (rien). Vente de fientes : le Projet ou toute la ferme.
#
# 1. Nouveau format `rattachement` = PROJET | SITE | FERME : chaque choix, contradictions
#    refusées (400).
# 2. Ancien format (APK 1.32 à 1.35 en production, ancien web, import) : jamais refusé
#    pour incohérence, normalisé (voir TransactionServiceImpl.normaliserRattachement).
# 3. Santé / Vétérinaire : toujours le Projet.
# 4. Modification : nouveau format = remplacement complet ; ancien format normalisé.
# 5. Transaction générée (charges du projet) : seul le poulailler du projet se précise.
# 6. Ventes de fientes : Projet ou ferme, la transaction suit.
# 7. Reporting « Dépenses par rattachement » : site et poulailler bien comptés.
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
ADMIN_ID="$(psql_run "SELECT id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
SUFFIXE="$(uuid | cut -c1-8)"
AUJ="$(date +%F)"
# Date propre au script (passé récent, unique par passage) : le reporting de ce jour ne
# contient que les dépenses de ce passage.
JOUR="$(date -d "$((RANDOM % 300 + 400)) days ago" +%F)"

# Dépense de test : $1 = champs JSON du rattachement, $2 = montant, $3 = catégorie.
sortie() {
  api POST /transactions/create "{\"type\":\"SORTIE\",\"date\":\"$JOUR\",\"description\":\"Rattachement $SUFFIXE\",\"montant\":${2:-1000},\"categorie\":\"${3:-Logistique}\"${1:+,$1}}"
}
# projet|site|poulailler|nb projets concernés (« - » = vide) d'une transaction.
etat() {
  psql_run "SELECT coalesce(p.unique_id,'-') || '|' || coalesce(s.unique_id,'-') || '|' || coalesce(b.unique_id,'-') || '|'
    || (SELECT count(*) FROM transaction_projets_concernes c WHERE c.transaction_id = t.id)
    FROM transactions t LEFT JOIN projets p ON p.id = t.projet_id LEFT JOIN sites s ON s.id = t.site_id
    LEFT JOIN batiments b ON b.id = t.batiment_id WHERE t.unique_id = '$1'"
}
tx_id() { jval "d['data']['uniqueId']"; }

echo "== 0. Préparation : site, deux poulaillers, un projet qui occupe le premier"
api POST /sites/create "{\"nom\":\"Site rattachement $SUFFIXE\"}"
check "site créé" "code in (200, 201)"
SITE="$(psql_run "SELECT unique_id FROM sites WHERE farm_id = $FARM_ID AND nom = 'Site rattachement $SUFFIXE'")"
api POST /batiments/create "{\"nom\":\"Poulailler occupé $SUFFIXE\",\"capacite\":500}"
BAT_ID="$(jval "d['data']['id']")"; BAT="$(jval "d['data']['uniqueId']")"
api POST /batiments/create "{\"nom\":\"Poulailler libre $SUFFIXE\",\"capacite\":500}"
LIBRE="$(jval "d['data']['uniqueId']")"
RACE_ID="$(psql_run "SELECT race_id FROM projets WHERE farm_id = $FARM_ID AND race_id IS NOT NULL LIMIT 1")"
api POST /projets/create "{\"titre\":\"Projet rattachement $SUFFIXE\",\"responsableId\":$ADMIN_ID,\"dateDebut\":\"$AUJ\",\"dateFinPrevue\":\"2027-12-31\",\"nbSujets\":100,\"puSujet\":500,\"autresDepense\":2000,\"objectif\":\"PONTE\",\"raceId\":$RACE_ID,\"occupations\":[{\"batimentId\":$BAT_ID,\"dateEntree\":\"$AUJ\",\"nbSujets\":100}]}"
check "projet créé, occupe le premier poulailler" "code in (200, 201)"
P="$(jval "d['data']['uniqueId']")"
P_AUTRE="$(psql_run "SELECT unique_id FROM projets WHERE farm_id = $FARM_ID AND coalesce(removed,false) = false AND unique_id <> '$P' ORDER BY id LIMIT 1")"
[ -n "$SITE" ] && [ -n "$BAT" ] && [ -n "$LIBRE" ] && [ -n "$P" ] && [ -n "$P_AUTRE" ] || { echo "ECHEC  préparation incomplète"; exit 1; }

echo "== 1. Nouveau format : les trois choix"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"batimentUniqueId\":\"$BAT\"" 1100
check "PROJET + poulailler du projet : accepté" "code == 201 and d['data']['rattachement'] == 'PROJET'"
check_eq "PROJET : projet + poulailler, pas de site" "$P|-|$BAT|0" "$(etat "$(tx_id)")"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\"" 1200
check_eq "PROJET sans poulailler : tout le projet" "$P|-|-|0" "$(etat "$(tx_id)")"
sortie "\"rattachement\":\"SITE\",\"siteUniqueId\":\"$SITE\"" 1300
check "SITE : accepté" "code == 201 and d['data']['rattachement'] == 'SITE'"
check_eq "SITE : site seul" "-|$SITE|-|0" "$(etat "$(tx_id)")"
sortie "\"rattachement\":\"FERME\"" 1400
check "FERME : acceptée" "code == 201 and d['data']['rattachement'] == 'FERME'"
check_eq "FERME : rien" "-|-|-|0" "$(etat "$(tx_id)")"
sortie "\"rattachement\":\"ferme\"" 1
check "minuscules acceptées" "code == 201"

echo "== 1b. Nouveau format : contradictions refusées"
avant="$(psql_run "SELECT count(*) FROM transactions WHERE description = 'Rattachement $SUFFIXE'")"
sortie "\"rattachement\":\"PROJET\""
check "PROJET sans projet : 400" "code == 400 and 'projet' in err"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"siteUniqueId\":\"$SITE\""
check "PROJET + site : 400" "code == 400 and 'site' in err"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"batimentUniqueId\":\"$LIBRE\""
check "PROJET + poulailler non occupé par ce projet : 400" "code == 400 and 'occupé' in err"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"commun\":true"
check "PROJET + commun : 400" "code == 400"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"projetsConcernesUniqueIds\":[\"$P_AUTRE\"]"
check "PROJET + projets concernés : 400" "code == 400"
sortie "\"rattachement\":\"SITE\""
check "SITE sans site : 400" "code == 400 and 'site' in err"
sortie "\"rattachement\":\"SITE\",\"siteUniqueId\":\"$SITE\",\"projetUniqueId\":\"$P\""
check "SITE + projet : 400" "code == 400"
sortie "\"rattachement\":\"SITE\",\"siteUniqueId\":\"$SITE\",\"batimentUniqueId\":\"$BAT\""
check "SITE + poulailler : 400" "code == 400 and 'poulailler' in err"
sortie "\"rattachement\":\"FERME\",\"siteUniqueId\":\"$SITE\""
check "FERME + site : 400" "code == 400"
sortie "\"rattachement\":\"FERME\",\"batimentUniqueId\":\"$BAT\""
check "FERME + poulailler : 400" "code == 400"
sortie "\"rattachement\":\"FERME\",\"projetUniqueId\":\"$P\""
check "FERME + projet : 400" "code == 400"
sortie "\"rattachement\":\"LOT\""
check "rattachement inconnu : 400" "code == 400 and 'inconnu' in err"
check_eq "aucune dépense enregistrée par un refus" "$avant" \
  "$(psql_run "SELECT count(*) FROM transactions WHERE description = 'Rattachement $SUFFIXE'")"

echo "== 2. Ancien format (téléphones 1.32 à 1.35) : normalisé, jamais refusé"
# Charge exacte d'un 1.34 : commun + site (+ poulailler), sans rattachement.
sortie "\"commun\":true,\"projetUniqueId\":null,\"projetsConcernesUniqueIds\":[],\"siteUniqueId\":\"$SITE\",\"batimentUniqueId\":null" 2100
check "1.34 commun + site : accepté" "code == 201"
check_eq "commun + site -> Un site" "-|$SITE|-|0" "$(etat "$(tx_id)")"
sortie "\"commun\":true,\"projetsConcernesUniqueIds\":[],\"siteUniqueId\":\"$SITE\",\"batimentUniqueId\":\"$BAT\"" 2200
check "1.34 commun + site + poulailler : accepté" "code == 201"
check_eq "commun + site + poulailler -> Un site (poulailler ignoré)" "-|$SITE|-|0" "$(etat "$(tx_id)")"
sortie "\"commun\":false,\"projetUniqueId\":\"$P\",\"siteUniqueId\":\"$SITE\",\"batimentUniqueId\":\"$BAT\"" 2300
check "1.34 projet + site + poulailler : accepté" "code == 201"
check_eq "projet + site -> Le Projet (site ignoré, poulailler gardé)" "$P|-|$BAT|0" "$(etat "$(tx_id)")"
sortie "\"commun\":false,\"projetUniqueId\":\"$P\",\"batimentUniqueId\":\"$LIBRE\"" 2400
check "1.34 projet + poulailler d'un autre : accepté" "code == 201"
check_eq "poulailler non occupé par le projet ignoré" "$P|-|-|0" "$(etat "$(tx_id)")"
sortie "\"commun\":true,\"projetsConcernesUniqueIds\":[\"$P\",\"$P_AUTRE\"],\"siteUniqueId\":\"$SITE\"" 2500
check "1.34 commun + 2 projets concernés : accepté" "code == 201"
check_eq "commun + plusieurs projets : ancien rattachement multi-projet conservé" "-|$SITE|-|2" "$(etat "$(tx_id)")"
sortie "\"commun\":true,\"projetsConcernesUniqueIds\":[\"$P\"]" 2600
check_eq "commun + un seul projet concerné -> Le Projet" "$P|-|-|0" "$(etat "$(tx_id)")"
sortie "\"commun\":true,\"batimentUniqueId\":\"$BAT\"" 2700
check "1.34 commun + poulailler seul : accepté" "code == 201"
check_eq "commun + poulailler occupé par un seul projet -> Le Projet de ce poulailler" "$P|-|$BAT|0" "$(etat "$(tx_id)")"
sortie "\"commun\":true,\"batimentUniqueId\":\"$LIBRE\"" 2800
check_eq "commun + poulailler vide -> Toute la ferme" "-|-|-|0" "$(etat "$(tx_id)")"
sortie "\"commun\":true,\"projetUniqueId\":\"$P\"" 2900
check_eq "commun coché + projet envoyé quand même -> Toute la ferme (comme avant)" "-|-|-|0" "$(etat "$(tx_id)")"
sortie "\"projetUniqueId\":\"$P\"" 2950
check_eq "projet sans « commun » (import) -> Le Projet" "$P|-|-|0" "$(etat "$(tx_id)")"
sortie "" 2990
check_eq "rien -> Toute la ferme" "-|-|-|0" "$(etat "$(tx_id)")"
sortie "\"commun\":false"
check "non commun sans projet : 400 (inchangé)" "code == 400 and 'projet' in err"

echo "== 3. Santé / Vétérinaire : toujours le Projet"
sortie "\"rattachement\":\"SITE\",\"siteUniqueId\":\"$SITE\",\"quantite\":1" 500 "Santé / Vétérinaire"
check "Santé + SITE : 400" "code == 400 and 'projet' in err"
sortie "\"rattachement\":\"FERME\",\"quantite\":1" 500 "Santé / Vétérinaire"
check "Santé + FERME : 400" "code == 400 and 'projet' in err"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"batimentUniqueId\":\"$BAT\",\"quantite\":1" 500 "Santé / Vétérinaire"
check_eq "Santé + PROJET + poulailler : accepté" "$P|-|$BAT|0" "$(etat "$(tx_id)")"
sortie "\"commun\":false,\"projetUniqueId\":\"$P\",\"siteUniqueId\":\"$SITE\",\"quantite\":1" 500 "Santé / Vétérinaire"
check_eq "Santé ancien format projet + site : site ignoré" "$P|-|-|0" "$(etat "$(tx_id)")"
sortie "\"commun\":true,\"siteUniqueId\":\"$SITE\",\"quantite\":1" 500 "Santé / Vétérinaire"
check "Santé ancien format commun sans projet : 400 (inchangé)" "code == 400 and 'projet' in err"

echo "== 4. Modification d'une dépense"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"batimentUniqueId\":\"$BAT\"" 3000
TM="$(tx_id)"
api PUT "/transactions/update/$TM" "{\"rattachement\":\"SITE\",\"siteUniqueId\":\"$SITE\"}"
check "PROJET -> SITE : accepté" "code == 200 and d['data']['rattachement'] == 'SITE'"
check_eq "SITE : projet et poulailler retirés" "-|$SITE|-|0" "$(etat "$TM")"
api PUT "/transactions/update/$TM" "{\"rattachement\":\"SITE\",\"siteUniqueId\":\"$SITE\",\"batimentUniqueId\":\"$BAT\"}"
check "SITE + poulailler : 400" "code == 400"
api PUT "/transactions/update/$TM" "{\"rattachement\":\"FERME\"}"
check_eq "SITE -> FERME : tout retiré" "-|-|-|0" "$(etat "$TM")"
api PUT "/transactions/update/$TM" "{\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"batimentUniqueId\":\"$LIBRE\"}"
check "-> PROJET avec un poulailler non occupé : 400" "code == 400 and 'occupé' in err"
check_eq "refus : rien n'a changé" "-|-|-|0" "$(etat "$TM")"
api PUT "/transactions/update/$TM" "{\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\"}"
check_eq "FERME -> PROJET" "$P|-|-|0" "$(etat "$TM")"
api PUT "/transactions/update/$TM" "{\"montant\":3100}"
check_eq "montant seul : rattachement inchangé" "$P|-|-|0|3100" "$(etat "$TM")|$(psql_run "SELECT montant::bigint FROM transactions WHERE unique_id = '$TM'")"
api PUT "/transactions/update/$TM" "{\"siteUniqueId\":\"$SITE\"}"
check "ancien format : site ajouté à une dépense du projet, accepté" "code == 200"
check_eq "ancien format projet + site -> Le Projet (site ignoré)" "$P|-|-|0" "$(etat "$TM")"
api PUT "/transactions/update/$TM" "{\"commun\":true,\"projetsConcernesUniqueIds\":[],\"siteUniqueId\":\"$SITE\"}"
check_eq "ancien format commun + site -> Un site" "-|$SITE|-|0" "$(etat "$TM")"
api PUT "/transactions/update/$TM" "{\"commun\":false,\"projetUniqueId\":\"$P\",\"batimentUniqueId\":\"$BAT\"}"
check_eq "ancien format projet + poulailler, site resté -> Le Projet (site retiré)" "$P|-|$BAT|0" "$(etat "$TM")"
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"quantite\":1" 900 "Santé / Vétérinaire"
TS="$(tx_id)"
api PUT "/transactions/update/$TS" "{\"rattachement\":\"SITE\",\"siteUniqueId\":\"$SITE\"}"
check "Santé modifiée vers SITE : 400" "code == 400 and 'projet' in err"
api PUT "/transactions/update/$TS" "{\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\",\"batimentUniqueId\":\"$BAT\"}"
check_eq "Santé : poulailler du projet précisé" "$P|-|$BAT|0" "$(etat "$TS")"
# Ancienne donnée incohérente (projet + site) : non migrée, intacte tant qu'on ne touche
# pas au rattachement.
sortie "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\"" 3200
TL="$(tx_id)"
psql_run "UPDATE transactions SET site_id = (SELECT id FROM sites WHERE unique_id = '$SITE') WHERE unique_id = '$TL'" >/dev/null
api PUT "/transactions/update/$TL" "{\"description\":\"Rattachement $SUFFIXE\",\"montant\":3201}"
check_eq "ancienne donnée projet + site : intacte si le rattachement n'est pas modifié" "$P|$SITE|-|0" "$(etat "$TL")"

echo "== 5. Transaction générée (charges de démarrage du projet) : poulailler du projet seulement"
TC="$(psql_run "SELECT t.unique_id FROM transactions t JOIN projets p ON p.id = t.projet_id WHERE p.unique_id = '$P' AND t.source_type = 'PROJET_CHARGES'")"
if [ -n "$TC" ]; then
  api PUT "/transactions/update/$TC" "{\"batimentUniqueId\":\"$BAT\"}"
  check "poulailler du projet : accepté" "code == 200"
  api PUT "/transactions/update/$TC" "{\"batimentUniqueId\":\"$LIBRE\"}"
  check "poulailler non occupé par le projet : 400" "code == 400 and 'occupé' in err"
  api PUT "/transactions/update/$TC" "{\"siteUniqueId\":\"$SITE\"}"
  check "site sur une dépense du projet : 400" "code == 400 and 'site' in err"
  api PUT "/transactions/update/$TC" "{\"rattachement\":\"FERME\"}"
  check "changer le Projet d'une transaction générée : 400" "code == 400"
  check_eq "transaction générée : projet + poulailler" "$P|-|$BAT|0" "$(etat "$TC")"
  api PUT "/transactions/update/$TC" "{\"batimentUniqueId\":\"\"}"
  check_eq "poulailler retiré" "$P|-|-|0" "$(etat "$TC")"
else
  echo "ECHEC  pas de transaction PROJET_CHARGES pour le projet"; FAIL=$((FAIL+1))
fi

echo "== 6. Ventes de fientes : le Projet ou toute la ferme"
vtx() { psql_run "SELECT coalesce(p.unique_id,'-') || '|' || coalesce((SELECT p2.unique_id FROM ventes_diverses v JOIN projets p2 ON p2.id = v.projet_id WHERE v.unique_id = '$1'),'-')
  FROM transactions t LEFT JOIN projets p ON p.id = t.projet_id WHERE t.source_unique_id = '$1'"; }
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"$JOUR\",\"quantite\":2,\"montant\":4000,\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\"}"
check "fientes du Projet : acceptée" "code in (200, 201) and d['data']['rattachement'] == 'PROJET' and d['data']['projetUniqueId'] == '$P'"
VF="$(jval "d['data']['uniqueId']")"
check_eq "vente et transaction rattachées au projet" "$P|$P" "$(vtx "$VF")"
api PUT "/ventes-diverses/update/$VF" "{\"rattachement\":\"FERME\"}"
check "fientes passée à toute la ferme : acceptée" "code == 200 and d['data']['rattachement'] == 'FERME'"
check_eq "vente et transaction communes" "-|-" "$(vtx "$VF")"
api PUT "/ventes-diverses/update/$VF" "{\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$P\"}"
check_eq "retour au Projet : la transaction suit" "$P|$P" "$(vtx "$VF")"
api PUT "/ventes-diverses/update/$VF" "{\"montant\":4500}"
check_eq "montant seul : projet inchangé" "$P|$P" "$(vtx "$VF")"
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"$JOUR\",\"montant\":3000,\"rattachement\":\"FERME\"}"
check_eq "fientes de toute la ferme" "-|-" "$(vtx "$(jval "d['data']['uniqueId']")")"
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"$JOUR\",\"montant\":3000,\"rattachement\":\"SITE\"}"
check "fientes d'un site : 400" "code == 400 and 'site' in err"
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"$JOUR\",\"montant\":3000,\"rattachement\":\"PROJET\"}"
check "fientes du Projet sans projet : 400" "code == 400 and 'projet' in err"
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"$JOUR\",\"montant\":3000,\"rattachement\":\"FERME\",\"projetUniqueId\":\"$P\"}"
check "fientes de la ferme avec un projet : 400" "code == 400"
# Téléphone 1.34/1.35 : ni rattachement ni projet -> toute la ferme, comme avant.
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"$JOUR\",\"quantite\":1,\"prixUnitaire\":1500,\"montant\":1500,\"description\":null}"
check "1.35 fientes sans rattachement : acceptée" "code in (200, 201)"
check_eq "1.35 : toute la ferme" "-|-" "$(vtx "$(jval "d['data']['uniqueId']")")"
# Très ancien chemin : entrée d'argent « Vente fientes ».
api POST /transactions/create "{\"type\":\"ENTREE\",\"commun\":true,\"date\":\"$JOUR\",\"description\":\"3 sac(s) de fientes\",\"montant\":3000,\"categorie\":\"Vente fientes\"}"
check "ancienne entrée « Vente fientes » commune : acceptée" "code == 201 and d['data'].get('projetUniqueId') is None"

echo "== 7. Reporting « Dépenses par rattachement » du $JOUR"
api GET "/transactions/depenses-par-rattachement?dateDebut=$JOUR&dateFin=$JOUR"
check "reporting : 200" "code == 200"
python3 - "$TMP/body" "$SITE" "$BAT" > "$TMP/rep" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))["data"]
site, bat = sys.argv[2], sys.argv[3]
def tot(s, b): return sum(r["total"] for r in d if r.get("siteUniqueId") == s and r.get("batimentUniqueId") == b)
def nb(s, b): return sum(r["nombre"] for r in d if r.get("siteUniqueId") == s and r.get("batimentUniqueId") == b)
print(f"{int(tot(site, None))}/{nb(site, None)} {int(tot(None, bat))}/{nb(None, bat)} {int(tot(site, bat))}")
PY
# Site seul : 1300 (SITE) + 2100 + 2200 (1.34 commun + site) + 2500 (ancien multi-projet,
# site gardé) + 3201 (ancienne donnée projet + site) = 11301 en 5 dépenses.
# Poulailler seul : 1100 + 2300 + 2700 + 500 (Santé) + 3100 (dépense modifiée, finalement
# au Projet avec poulailler) + 900 (Santé modifiée) = 10600 en 6 dépenses.
check_eq "reporting : totaux par site et par poulailler, jamais site + poulailler ensemble" \
  "11301/5 10600/6 0" "$(cat "$TMP/rep")"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
