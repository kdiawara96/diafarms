#!/usr/bin/env bash
# Sessions de pesée : script de bout en bout (API /diafarms/api/v1/pesees).
#
# Pré-requis (non gérés ici) : Postgres + backend démarrés, base seedée avec une
# ferme, un ADMIN (admin@t.local / Test1234!) et au moins un projet dans sa ferme.
# Le script crée lui-même (idempotent) une AUTRE ferme + un projet par SQL pour le
# test « projet d'une autre ferme ». Chaque passage utilise de nouveaux UUID : il est
# rejouable sur la même base.
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

post_sync() { # $1 = corps JSON
  curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/pesees/sessions/sync" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: mobile' -d "$1" > "$TMP/code"
}

get() { # $1 = chemin relatif à BASE
  curl -s -o "$TMP/body" -w '%{http_code}' "$BASE$1" \
    -H "Authorization: Bearer $TOKEN" -H 'X-Client-Type: mobile' > "$TMP/code"
}

# Corps de synchro : $1 session, $2 projet, $3 statut, $4 dateFin (ou ""), $5 pesées
# au format "uid|nombre|poids|dateHeure|annulee;..." (vide = aucune).
payload() {
  python3 - "$@" <<'PY'
import json, sys
sid, projet, statut, date_fin, pesees = sys.argv[1:6]
items = []
for part in filter(None, pesees.split(";")):
    uid, n, poids, dh, ann = part.split("|")
    items.append({"uniqueId": uid, "nombreSujets": int(n), "poidsKg": float(poids),
                  "dateHeure": dh, "annulee": ann == "true"})
print(json.dumps({"uniqueId": sid, "projetUniqueId": projet, "nombreParDefaut": 3,
                  "dateDebut": "2026-09-25T08:00:00", "statut": statut,
                  "dateFin": date_fin or None, "pesees": items}))
PY
}

# ---------------------------------------------------------------------------
# Préparation
# ---------------------------------------------------------------------------
TOKEN="$(curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
  --data-urlencode grantType=password --data-urlencode "identifiant=$ADMIN_EMAIL" \
  --data-urlencode "password=$ADMIN_PWD" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])')"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }

PROJET="$(psql_run "SELECT p.unique_id FROM projets p JOIN utilisateurs u ON u.farm_id = p.farm_id WHERE u.email = '$ADMIN_EMAIL' ORDER BY p.id LIMIT 1")"
[ -n "$PROJET" ] || { echo "ECHEC  aucun projet dans la ferme de l'admin"; exit 1; }

psql_run "INSERT INTO farms (unique_id) SELECT 'pesee-autre-ferme' WHERE NOT EXISTS (SELECT 1 FROM farms WHERE unique_id = 'pesee-autre-ferme')" >/dev/null
psql_run "INSERT INTO projets (unique_id, code, titre, objectif, race_id, farm_id)
  SELECT 'pesee-autre-projet', 'PRJ-AUTRE', 'Projet autre ferme', p.objectif, p.race_id, (SELECT id FROM farms WHERE unique_id = 'pesee-autre-ferme')
  FROM projets p WHERE p.unique_id = '$PROJET'
  AND NOT EXISTS (SELECT 1 FROM projets WHERE unique_id = 'pesee-autre-projet')" >/dev/null
AUTRE_PROJET="pesee-autre-projet"

S1="$(uuid)"; P1="$(uuid)"; P2="$(uuid)"; P3="$(uuid)"; P4="$(uuid)"
T1="2026-09-25T08:05:00"; T2="2026-09-25T08:10:00"; T3="2026-09-25T08:20:00"; T4="2026-09-25T08:40:00"
FIN="2026-09-25T09:00:00"

# ---------------------------------------------------------------------------
echo "== 1. Ouverture d'une session (nombreParDefaut 3)"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "")"
check "200, EN_COURS, nombreParDefaut 3, totaux à 0" \
  "code == 200 and d['data']['statut'] == 'EN_COURS' and d['data']['nombreParDefaut'] == 3 and d['data']['nombreTotalSujets'] == 0 and d['data']['poidsTotalKg'] == 0 and d['data']['poidsMoyenKg'] == 0 and d['data']['dateFin'] is None"

echo "== 2. Deux pesées 3/6.3 et 3/6.7"
PES2="$P1|3|6.3|$T1|false;$P2|3|6.7|$T2|false"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "$PES2")"
check "6 sujets, 13.0 kg, moyenne 2.167, 2 pesées" \
  "code == 200 and d['data']['nombreTotalSujets'] == 6 and d['data']['poidsTotalKg'] == 13.0 and d['data']['poidsMoyenKg'] == 2.167 and d['data']['nombrePesees'] == 2 and len(d['data']['pesees']) == 2"
check "derniereDatePesee = dateHeure de la 2e pesée" "d['data']['derniereDatePesee'].startswith('$T2')"

echo "== 3. Renvoi du MÊME corps (réponse perdue)"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "$PES2")"
check "pas de doublon : toujours 2 pesées, mêmes totaux" \
  "code == 200 and len(d['data']['pesees']) == 2 and d['data']['nombreTotalSujets'] == 6 and d['data']['poidsTotalKg'] == 13.0"

echo "== 4. 3e pesée 2/4.1"
PES3="$PES2;$P3|2|4.1|$T3|false"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "$PES3")"
# 17.1 / 8 = 2.1375 → arrondi à 3 décimales (HALF_UP) = 2.138
check "8 sujets, 17.1 kg, moyenne 2.138 (2.1375 arrondi à 3 déc.)" \
  "code == 200 and d['data']['nombreTotalSujets'] == 8 and d['data']['poidsTotalKg'] == 17.1 and d['data']['poidsMoyenKg'] == 2.138 and d['data']['nombrePesees'] == 3"

echo "== 5. Annulation de la pesée 2"
PES3A="$P1|3|6.3|$T1|false;$P2|3|6.7|$T2|true;$P3|2|4.1|$T3|false"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "$PES3A")"
check "totaux sans la pesée 2 : 5 sujets, 10.4 kg, 2.08, 2 pesées actives, 3 listées" \
  "code == 200 and d['data']['nombreTotalSujets'] == 5 and d['data']['poidsTotalKg'] == 10.4 and d['data']['poidsMoyenKg'] == 2.08 and d['data']['nombrePesees'] == 2 and len(d['data']['pesees']) == 3"

echo "== 5b. Tentative de dé-annulation et de modification d'une pesée existante"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "$P1|9|99|$T1|false;$P2|3|6.7|$T2|false;$P3|2|4.1|$T3|false")"
check "ignorées : pesée 2 toujours annulée, pesée 1 inchangée, totaux inchangés" \
  "code == 200 and [p for p in d['data']['pesees'] if p['uniqueId'] == '$P2'][0]['annulee'] is True and [p for p in d['data']['pesees'] if p['uniqueId'] == '$P1'][0]['poidsKg'] == 6.3 and d['data']['nombreTotalSujets'] == 5"

echo "== 5c. Pesée invalide (poids 0)"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "$PES3A;$P4|2|0|$T4|false")"
check "400" "code == 400"

echo "== 6. Terminer la session"
post_sync "$(payload "$S1" "$PROJET" TERMINEE "$FIN" "$PES3A")"
check "TERMINEE avec dateFin $FIN" \
  "code == 200 and d['data']['statut'] == 'TERMINEE' and d['data']['dateFin'].startswith('$FIN') and d['data']['poidsMoyenKg'] == 2.08"

echo "== 7. Renvoi identique de la session terminée"
post_sync "$(payload "$S1" "$PROJET" TERMINEE "$FIN" "$PES3A")"
check "200, inchangée" \
  "code == 200 and d['data']['statut'] == 'TERMINEE' and d['data']['dateFin'].startswith('$FIN') and len(d['data']['pesees']) == 3 and d['data']['nombreTotalSujets'] == 5"

echo "== 8. Nouvelle pesée après la fin"
post_sync "$(payload "$S1" "$PROJET" TERMINEE "$FIN" "$PES3A;$P4|2|4.0|$T4|false")"
check "400 « session terminée »" \
  "code == 400 and 'terminée' in ' '.join(d.get('errors') or [])"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "$PES3A")"
check "400 aussi pour une réouverture (statut EN_COURS)" "code == 400"

echo "== 9. Terminer une session vide"
S2="$(uuid)"
post_sync "$(payload "$S2" "$PROJET" TERMINEE "$FIN" "")"
check "400" "code == 400"
S3="$(uuid)"
post_sync "$(payload "$S3" "$PROJET" TERMINEE "$FIN" "$(uuid)|2|4.0|$T1|true")"
check "400 quand toutes les pesées sont annulées" "code == 400"

echo "== 10. Projet d'une autre ferme"
post_sync "$(payload "$(uuid)" "$AUTRE_PROJET" EN_COURS "" "")"
check "400" "code == 400"

echo "== 10b. uniqueId de pesée déjà utilisé par une autre session"
post_sync "$(payload "$(uuid)" "$PROJET" EN_COURS "" "$P1|3|6.3|$T1|false")"
check "400" "code == 400"

echo "== 11. Liste par projet et statut"
S4="$(uuid)"
post_sync "$(payload "$S4" "$PROJET" EN_COURS "" "$(uuid)|3|6.0|$T1|false")"
check "session S4 ouverte (EN_COURS)" "code == 200 and d['data']['statut'] == 'EN_COURS'"
get "/pesees/sessions/list?projetUniqueId=$PROJET&statut=TERMINEE&page=0&size=100"
check "statut=TERMINEE : contient S1, pas S4, pesées vides, nombrePesees 2" \
  "code == 200 and any(s['uniqueId'] == '$S1' and s['nombrePesees'] == 2 and s['pesees'] == [] for s in d['data']['data']) and all(s['statut'] == 'TERMINEE' for s in d['data']['data']) and not any(s['uniqueId'] == '$S4' for s in d['data']['data'])"
get "/pesees/sessions/list?projetUniqueId=$PROJET&statut=EN_COURS&page=0&size=100"
check "statut=EN_COURS : contient S4, pas S1" \
  "code == 200 and any(s['uniqueId'] == '$S4' for s in d['data']['data']) and not any(s['uniqueId'] == '$S1' for s in d['data']['data'])"
get "/pesees/sessions/list?projetUniqueId=$PROJET&page=0&size=100"
check "sans statut : EN_COURS d'abord, puis TERMINEE" \
  "code == 200 and (lambda st: st == sorted(st, key=lambda x: 0 if x == 'EN_COURS' else 1))([s['statut'] for s in d['data']['data']]) and d['data']['data'][0]['statut'] == 'EN_COURS'"
get "/pesees/sessions/list?projetUniqueId=$AUTRE_PROJET&page=0&size=100"
check "projet d'une autre ferme : liste vide" "code == 200 and d['data']['data'] == []"

echo "== 12. Détail"
get "/pesees/sessions/$S1"
check "3 pesées triées par dateHeure, pesée 2 annulée, nombrePesees 2, creeParNom renseigné" \
  "code == 200 and [p['uniqueId'] for p in d['data']['pesees']] == ['$P1', '$P2', '$P3'] and [p['annulee'] for p in d['data']['pesees']] == [False, True, False] and d['data']['nombrePesees'] == 2 and d['data']['projetUniqueId'] == '$PROJET' and d['data']['creeParNom']"

echo "== 13. Évolution du poids"
get "/pesees/evolution?projetUniqueId=$PROJET"
check "contient S1 (date = dateFin, moyenne 2.08, 5 sujets), uniquement des sessions terminées" \
  "code == 200 and any(e['sessionUniqueId'] == '$S1' and e['date'].startswith('$FIN') and e['poidsMoyenKg'] == 2.08 and e['nombreTotalSujets'] == 5 for e in d['data']) and not any(e['sessionUniqueId'] == '$S4' for e in d['data'])"
get "/pesees/evolution?projetUniqueId=$AUTRE_PROJET"
check "projet d'une autre ferme : 400" "code == 400"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
