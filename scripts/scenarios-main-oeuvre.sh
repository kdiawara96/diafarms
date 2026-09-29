#!/usr/bin/env bash
# Main-d'œuvre : répartition des salaires sur les projets (MainOeuvreService).
#
# - Jours du mois où l'employé est affecté à un projet : ce projet paie ces jours-là
#   (affecté le 11 septembre : 20/30 du salaire de septembre).
# - Reste du mois (non affecté) : partagé entre les projets en cours au prorata des
#   sujets vivants à la fin du mois.
# - Une seule affectation à la fois par employé ; terminer une affectation recalcule.
# - La dépense d'un salaire ou d'un investissement ne se rattache plus à un site ou
#   un poulailler depuis la Comptabilité (déjà répartie).
#
# Pré-requis : base seedée par scenarios-circuit-client.sh. Rejouable (employés au nom
# unique ; le second projet de test est remis à la corbeille à la fin).
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
PRJ="$(psql_run "SELECT p.unique_id FROM projets p JOIN occupations_batiments o ON o.projet_id = p.id WHERE p.farm_id = $FARM_ID AND coalesce(p.removed,false) = false ORDER BY p.id LIMIT 1")"
PRJ_ID="$(psql_run "SELECT id FROM projets WHERE unique_id = '$PRJ'")"
P2="$(psql_run "SELECT unique_id FROM projets WHERE farm_id = $FARM_ID AND unique_id <> '$PRJ' ORDER BY id LIMIT 1")"
P2_ID="$(psql_run "SELECT id FROM projets WHERE unique_id = '$P2'")"
MOIS="2026-09"
SUFFIXE="$(uuid | cut -c1-6)"
# Seuls ces deux projets sont en cours pendant le test : les autres de la ferme restent à la corbeille.
psql_run "UPDATE projets SET removed = false, date_debut = '2026-09-01', date_fin_prevue = '2027-12-31', nb_sujets = 1000 WHERE id = $P2_ID" >/dev/null
AUTRES="$(psql_run "SELECT count(*) FROM projets WHERE farm_id = $FARM_ID AND coalesce(removed,false) = false AND id NOT IN ($PRJ_ID, $P2_ID) AND date_debut <= '2026-09-30' AND (date_fin_prevue IS NULL OR date_fin_prevue >= '2026-09-01')")"
check_eq "seuls les deux projets de test sont en cours en septembre" "0" "$AUTRES"
vivants() { psql_run "SELECT p.nb_sujets - coalesce((SELECT sum(nombre_morts) FROM mortalites m WHERE m.projet_id = p.id AND NOT coalesce(m.removed,false) AND m.date <= '2026-09-30'),0) - coalesce((SELECT sum(nombre_sujets) FROM reformes r WHERE r.projet_id = p.id AND NOT coalesce(r.removed,false) AND r.date <= '2026-09-30'),0) FROM projets p WHERE p.id = $1"; }
V1="$(vivants $PRJ_ID)"; V2="$(vivants $P2_ID)"
echo "   sujets vivants fin septembre : projet 1 = $V1, projet 2 = $V2"
attendu() { python3 -c "print(round($1))"; }
ligne() { # $1 projet, $2 employé -> total de la ligne de septembre
  api GET "/main-oeuvre/projets/$1/cout"
  jval "next((l['total'] for l in d['data']['lignes'] if l['employeNom'] == '$2' and l['periode'] == '$MOIS'), 0)"
}

nouvel_employe() { # $1 nom, $2 salaire mensuel -> EMP
  api POST /personnel/create "{\"nom\":\"$1\",\"poste\":\"Ouvrier\"}"
  EMP="$(jval "d['data']['uniqueId']")"
  api POST /salaires/definir "{\"employeUniqueId\":\"$EMP\",\"modePaiement\":\"MENSUEL\",\"tauxBase\":$2}"
}

echo "== 1. Employé affecté au cours du mois (à partir du 11 septembre)"
A="Ouvrier A $SUFFIXE"
nouvel_employe "$A" 30000; EMP_A="$EMP"
api POST "/main-oeuvre/personnel/$EMP_A/affectations" "{\"projetUniqueId\":\"$PRJ\",\"dateDebut\":\"2026-09-11\"}"
check "affectation créée" "code == 201 and d['data']['projetUniqueId'] == '$PRJ' and d['data']['dateFin'] is None"
AFF_A="$(jval "d['data']['uniqueId']")"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP_A\",\"periode\":\"$MOIS\"}"
check "salaire de septembre payé (30000)" "code == 201"
check_eq "projet 1 : 20 jours affectés + part du reste (10 jours) au prorata" \
  "$(attendu "20000 + 10000 * $V1 / ($V1 + $V2)")" "$(python3 -c "print(round($(ligne $PRJ "$A")))")"
check_eq "projet 2 : sa part des 10 jours non affectés" "$(attendu "10000 * $V2 / ($V1 + $V2)")" "$(python3 -c "print(round($(ligne $P2 "$A")))")"
api GET "/main-oeuvre/projets/$PRJ/cout"
check "détail projet 1 : 20 jours sur 30, part affectation 20000" "any(l['employeNom'] == '$A' and l['joursAffectes'] == 20 and l['joursDuMois'] == 30 and l['partAffectation'] == 20000 for l in d['data']['lignes'])"

echo "== 2. Employé non affecté : tout au prorata"
B="Gardien B $SUFFIXE"
nouvel_employe "$B" 12000; EMP_B="$EMP"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP_B\",\"periode\":\"$MOIS\"}"
check "salaire du gardien payé (12000)" "code == 201"
L1="$(ligne $PRJ "$B")"; L2="$(ligne $P2 "$B")"
check_eq "projet 1 : prorata des sujets vivants" "$(attendu "12000 * $V1 / ($V1 + $V2)")" "$(python3 -c "print(round($L1))")"
check_eq "rien ne se perd : projet 1 + projet 2 = salaire" "12000" "$(python3 -c "print(round($L1 + $L2))")"

echo "== 3. Une seule affectation à la fois, puis fin d'affectation"
api POST "/main-oeuvre/personnel/$EMP_A/affectations" "{\"projetUniqueId\":\"$P2\",\"dateDebut\":\"2026-09-20\"}"
check "deuxième affectation qui chevauche : refusée" "code == 400 and 'déjà affecté' in err"
api PUT "/main-oeuvre/affectations/$AFF_A" '{"dateFin":"2026-09-20"}'
check "affectation terminée le 20" "code == 200 and d['data']['dateFin'] == '2026-09-20'"
api POST "/main-oeuvre/personnel/$EMP_A/affectations" "{\"projetUniqueId\":\"$P2\",\"dateDebut\":\"2026-09-21\"}"
check "nouvelle affectation au projet 2 à partir du 21 : acceptée" "code == 201"
check_eq "projet 2 : 10 jours (21 au 30) + sa part des 10 premiers jours" "$(attendu "10000 + 10000 * $V2 / ($V1 + $V2)")" "$(python3 -c "print(round($(ligne $P2 "$A")))")"
# Jours 1 à 10 non affectés : partagés au prorata ; projet 1 en reçoit sa part aussi.
check_eq "projet 1 complet : 10 jours affectés + part des 10 premiers jours" "$(attendu "10000 + 10000 * $V1 / ($V1 + $V2)")" "$(python3 -c "print(round($(ligne $PRJ "$A")))")"

echo "== 4. Salaire et investissement : pas de rattachement site/poulailler en Comptabilité"
PAI_A="$(psql_run "SELECT p.unique_id FROM paiements_salaire p JOIN salaires s ON s.id = p.salaire_id JOIN personnel e ON e.id = s.employe_id WHERE e.unique_id = '$EMP_A'")"
T_SAL="$(psql_run "SELECT unique_id FROM transactions WHERE source_unique_id = '$PAI_A'")"
BAT="$(psql_run "SELECT b.unique_id FROM batiments b WHERE b.farm_id = $FARM_ID AND coalesce(b.removed,false) = false LIMIT 1")"
api PUT "/transactions/update/$T_SAL" "{\"batimentUniqueId\":\"$BAT\"}"
check "rattacher un salaire à un poulailler : refusé" "code == 400 and 'déjà répartie' in err"
api GET "/transactions/list?page=0&size=100&search=$SUFFIXE"
check "salaire : rattachement non modifiable dans la liste" "code == 200 and all(x['rattachementModifiable'] is False for x in d['data']['data'] if x.get('sourceType') == 'SALAIRE')"

echo "== Remise en état"
for a in $(psql_run "SELECT a.unique_id FROM affectations_personnel a JOIN personnel e ON e.id = a.personnel_id WHERE e.nom LIKE '%$SUFFIXE' AND NOT coalesce(a.removed,false)"); do
  api DELETE "/main-oeuvre/affectations/$a"
done
check "affectations de test supprimées" "code == 200"
psql_run "UPDATE projets SET removed = true WHERE id = $P2_ID" >/dev/null

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
