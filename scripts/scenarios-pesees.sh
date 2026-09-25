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
# Utilisateur COMPTABLE de la même ferme (seedé par scenarios-circuit-client.sh).
COMPTA_EMAIL="${COMPTA_EMAIL:-compta@t.local}"
COMPTA_PWD="${COMPTA_PWD:-$ADMIN_PWD}"

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

# jval "expression python sur d" → valeur tirée de la dernière réponse
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

post_sync() { # $1 = corps JSON, $2 = jeton facultatif (défaut : admin)
  curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/pesees/sessions/sync" \
    -H "Authorization: Bearer ${2:-$TOKEN}" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: mobile' -d "$1" > "$TMP/code"
}

web() { # $1 = méthode, $2 = chemin relatif à BASE, $3 = corps JSON (ou ""), $4 = jeton facultatif
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer ${4:-$TOKEN}" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${3:+-d "$3"} > "$TMP/code"
}

get() { # $1 = chemin relatif à BASE
  curl -s -o "$TMP/body" -w '%{http_code}' "$BASE$1" \
    -H "Authorization: Bearer $TOKEN" -H 'X-Client-Type: mobile' > "$TMP/code"
}

# Corps de synchro : $1 session, $2 projet, $3 statut, $4 dateFin (ou ""), $5 pesées,
# $6 dateDebut facultative (défaut 2026-09-25T08:00:00, "NONE" = champ absent)
# au format "uid|nombre|poids|dateHeure|annulee;..." (vide = aucune).
payload() {
  python3 - "$@" <<'PY'
import json, sys
sid, projet, statut, date_fin, pesees = sys.argv[1:6]
date_debut = sys.argv[6] if len(sys.argv) > 6 else "2026-09-25T08:00:00"
items = []
for part in filter(None, pesees.split(";")):
    uid, n, poids, dh, ann = part.split("|")
    items.append({"uniqueId": uid, "nombreSujets": int(n), "poidsKg": float(poids),
                  "dateHeure": dh, "annulee": ann == "true"})
body = {"uniqueId": sid, "projetUniqueId": projet, "nombreParDefaut": 3,
        "dateDebut": date_debut, "statut": statut, "dateFin": date_fin or None, "pesees": items}
if date_debut == "NONE":
    del body["dateDebut"]
print(json.dumps(body))
PY
}

# ---------------------------------------------------------------------------
# Préparation
# ---------------------------------------------------------------------------
login() { # $1 = identifiant, $2 = mot de passe → jeton (vide si échec)
  curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"])
except Exception: print("")'
}
TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }

PROJET="$(psql_run "SELECT p.unique_id FROM projets p JOIN utilisateurs u ON u.farm_id = p.farm_id WHERE u.email = '$ADMIN_EMAIL' ORDER BY p.id LIMIT 1")"
[ -n "$PROJET" ] || { echo "ECHEC  aucun projet dans la ferme de l'admin"; exit 1; }

psql_run "INSERT INTO farms (unique_id) SELECT 'pesee-autre-ferme' WHERE NOT EXISTS (SELECT 1 FROM farms WHERE unique_id = 'pesee-autre-ferme')" >/dev/null
psql_run "INSERT INTO projets (unique_id, code, titre, objectif, race_id, farm_id)
  SELECT 'pesee-autre-projet', 'PRJ-AUTRE', 'Projet autre ferme', p.objectif, p.race_id, (SELECT id FROM farms WHERE unique_id = 'pesee-autre-ferme')
  FROM projets p WHERE p.unique_id = '$PROJET'
  AND NOT EXISTS (SELECT 1 FROM projets WHERE unique_id = 'pesee-autre-projet')" >/dev/null
AUTRE_PROJET="pesee-autre-projet"
# Projet supprimé (initialisation.removed) dans la ferme de l'admin.
psql_run "INSERT INTO projets (unique_id, code, titre, objectif, race_id, farm_id, removed)
  SELECT 'pesee-projet-supprime', 'PRJ-SUPPR', 'Projet supprimé', p.objectif, p.race_id, p.farm_id, true
  FROM projets p WHERE p.unique_id = '$PROJET'
  AND NOT EXISTS (SELECT 1 FROM projets WHERE unique_id = 'pesee-projet-supprime')" >/dev/null
PROJET_SUPPRIME="pesee-projet-supprime"

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
check "200, inchangée, peseesRefusees vide" \
  "code == 200 and d['data']['statut'] == 'TERMINEE' and d['data']['dateFin'].startswith('$FIN') and len(d['data']['pesees']) == 3 and d['data']['nombreTotalSujets'] == 5 and d['data']['peseesRefusees'] == []"
V_S1="$(jval "d['data']['version']")"

echo "== 8. Nouvelle pesée après la fin (depuis le 2026-09-25 web : 200 + peseesRefusees)"
post_sync "$(payload "$S1" "$PROJET" TERMINEE "$FIN" "$PES3A;$P4|2|4.0|$T4|false")"
check "200, peseesRefusees = [P4], rien d'écrit (3 pesées, 5 sujets, version inchangée)" \
  "code == 200 and d['data']['peseesRefusees'] == ['$P4'] and len(d['data']['pesees']) == 3 and d['data']['nombreTotalSujets'] == 5 and d['data']['version'] == $V_S1"
post_sync "$(payload "$S1" "$PROJET" EN_COURS "" "$PES3A")"
check "réouverture demandée (statut EN_COURS) : 200, reste TERMINEE" \
  "code == 200 and d['data']['statut'] == 'TERMINEE' and d['data']['peseesRefusees'] == [] and d['data']['version'] == $V_S1"
PDA="$(uuid)"
post_sync "$(payload "$S1" "$PROJET" TERMINEE "$FIN" "$PES3A;$PDA|2|4.0|$T4|true")"
check "nouvelle pesée DÉJÀ annulée après la fin : 200, dans peseesRefusees (pas 500)" \
  "code == 200 and d['data']['peseesRefusees'] == ['$PDA'] and len(d['data']['pesees']) == 3"

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
check "400 « appartient à une autre session »" \
  "code == 400 and 'appartient à une autre session' in ' '.join(d.get('errors') or [])"

echo "== 10c. Projet supprimé"
post_sync "$(payload "$(uuid)" "$PROJET_SUPPRIME" EN_COURS "" "")"
check "400 « projet supprimé »" "code == 400 and 'supprimé' in ' '.join(d.get('errors') or [])"

echo "== 10d. Rôle non autorisé (COMPTABLE)"
TOKEN_COMPTA="$(login "$COMPTA_EMAIL" "$COMPTA_PWD")"
if [ -z "$TOKEN_COMPTA" ]; then
  echo "ECHEC  connexion $COMPTA_EMAIL (utilisateur COMPTABLE absent ?)"; FAIL=$((FAIL+1))
else
  post_sync "$(payload "$(uuid)" "$PROJET" EN_COURS "" "$(uuid)|3|6.0|$T1|false")" "$TOKEN_COMPTA"
  check "400 « pas autorisé »" "code == 400 and 'autorisé' in ' '.join(d.get('errors') or [])"
fi

echo "== 10e. Dates invalides"
post_sync "$(payload "$(uuid)" "$PROJET" EN_COURS "" "" "pas-une-date")"
check "dateDebut illisible : 400" "code == 400 and 'Date invalide' in ' '.join(d.get('errors') or [])"
post_sync "$(payload "$(uuid)" "$PROJET" EN_COURS "" "$(uuid)|3|6.0|2026-13-45T08:00:00|false")"
check "dateHeure de pesée illisible : 400" "code == 400 and 'Date invalide' in ' '.join(d.get('errors') or [])"
post_sync "$(payload "$(uuid)" "$PROJET" TERMINEE "2026-09-25T07:00:00" "$(uuid)|3|6.0|$T1|false")"
check "dateFin avant dateDebut : 400" "code == 400 and 'précéder' in ' '.join(d.get('errors') or [])"

echo "== 10f. Dates par défaut (dateDebut absente, dateFin absente)"
post_sync "$(payload "$(uuid)" "$PROJET" TERMINEE "" "$(uuid)|3|6.0|$T3|false;$(uuid)|3|6.0|$T1|false;$(uuid)|3|6.0|$T4|true" NONE)"
check "dateDebut = 1re pesée ($T1), dateFin = dernière pesée non annulée ($T3)" \
  "code == 200 and d['data']['dateDebut'].startswith('$T1') and d['data']['dateFin'].startswith('$T3')"

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
# Dates futures (devant les sessions du 25/09/2026 de ce passage).
S5="$(uuid)"; S6="$(uuid)"
D5="$(date -d '+10 years -1 day' +%Y-%m-%dT%H:%M:%S)"; D6="$(date -d '+10 years' +%Y-%m-%dT%H:%M:%S)"
post_sync "$(payload "$S5" "$PROJET" EN_COURS "" "" "$D5")"
post_sync "$(payload "$S6" "$PROJET" EN_COURS "" "" "$D6")"
get "/pesees/sessions/list?projetUniqueId=$PROJET&statut=EN_COURS&page=0&size=100"
check "tri par dateDebut décroissante : S6 avant S5 (un jour plus tôt), tous deux avant les sessions du 25/09/2026" \
  "code == 200 and (lambda ids: '$S6' in ids and '$S5' in ids and ids.index('$S6') < ids.index('$S5') and ids.index('$S5') < ids.index('$S4'))([s['uniqueId'] for s in d['data']['data']])"
get "/pesees/sessions/list?projetUniqueId=$PROJET&page=0&size=1000"
check "taille de page plafonnée à 100" "code == 200 and d['data']['size'] == 100"
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

# ---------------------------------------------------------------------------
# Web + journal (2026-09-25) : session menée depuis le web, puis synchro du téléphone
# ---------------------------------------------------------------------------
echo "== 14. Web : ouverture d'une session"
web POST /pesees/sessions "{\"projetUniqueId\": \"$PROJET\", \"nombreParDefaut\": 3}"
check "200, EN_COURS, origine WEB, version 1, événement CREATION_WEB, uniqueId serveur" \
  "code == 200 and d['data']['statut'] == 'EN_COURS' and d['data']['origine'] == 'WEB' and d['data']['version'] == 1 and [e['type'] for e in d['data']['evenements']] == ['CREATION_WEB'] and len(d['data']['uniqueId']) == 36 and d['data']['nombreParDefaut'] == 3 and d['data']['peseesRefusees'] == []"
WS="$(jval "d['data']['uniqueId']")"

echo "== 15. Web : deux pesées 3/6.3 et 3/6.7"
web POST "/pesees/sessions/$WS/pesees" '{"nombreSujets": 3, "poidsKg": 6.3}'
check "200, 1 pesée origine WEB non modifiée, version 2" \
  "code == 200 and len(d['data']['pesees']) == 1 and d['data']['pesees'][0]['origine'] == 'WEB' and d['data']['pesees'][0]['modifiee'] is False and d['data']['version'] == 2"
W1="$(jval "d['data']['pesees'][0]['uniqueId']")"
web POST "/pesees/sessions/$WS/pesees" '{"nombreSujets": 3, "poidsKg": 6.7}'
check "6 sujets, 13.0 kg, 2.167, version 3, événement « Pesée n°2 ajoutée : 3 sujets 6,7 kg par … »" \
  "code == 200 and d['data']['nombreTotalSujets'] == 6 and d['data']['poidsTotalKg'] == 13.0 and d['data']['poidsMoyenKg'] == 2.167 and d['data']['version'] == 3 and d['data']['evenements'][-1]['type'] == 'AJOUT_WEB' and d['data']['evenements'][-1]['description'].startswith('Pesée n°2 ajoutée : 3 sujets 6,7 kg par ')"
W2="$(jval "[p for p in d['data']['pesees'] if p['uniqueId'] != '$W1'][0]['uniqueId']")"
web POST "/pesees/sessions/$WS/pesees" '{"nombreSujets": 3, "poidsKg": 0.0004}'
check "poids nul après arrondi à 3 déc. : 400" "code == 400 and 'supérieur à 0' in ' '.join(d.get('errors') or [])"
web POST "/pesees/sessions/$WS/pesees" '{"nombreSujets": 0, "poidsKg": 5}'
check "nombreSujets 0 : 400" "code == 400"

echo "== 16. Web : correction de la pesée 1 (6.3 → 6.1)"
web PUT "/pesees/sessions/$WS/pesees/$W1" '{"nombreSujets": 3, "poidsKg": 6.1}'
check "pesée 1 = 6.1, modifiee, 12.8 kg, version 4, événement MODIFICATION_WEB ancien/nouveau + phrase" \
  "code == 200 and (lambda p: p['poidsKg'] == 6.1 and p['modifiee'] is True)([p for p in d['data']['pesees'] if p['uniqueId'] == '$W1'][0]) and d['data']['poidsTotalKg'] == 12.8 and d['data']['version'] == 4 and (lambda e: e['type'] == 'MODIFICATION_WEB' and e['peseeUniqueId'] == '$W1' and e['ancienNombre'] == 3 and e['ancienPoids'] == 6.3 and e['nouveauNombre'] == 3 and e['nouveauPoids'] == 6.1 and e['description'].startswith('Pesée n°1 modifiée : 3 sujets 6,3 kg → 3 sujets 6,1 kg par ') and e['parNom'])(d['data']['evenements'][-1])"
web PUT "/pesees/sessions/$WS/pesees/$W1" '{"nombreSujets": 3, "poidsKg": 6.1}'
check "même valeur renvoyée : 200 sans nouvel événement ni nouvelle version" \
  "code == 200 and d['data']['version'] == 4 and len(d['data']['evenements']) == 4"

echo "== 17. Web : annulation de la pesée 2"
web POST "/pesees/sessions/$WS/pesees/$W2/annuler" ""
check "pesée 2 annulée, 3 sujets 6.1 kg, version 5, événement ANNULATION_WEB" \
  "code == 200 and [p for p in d['data']['pesees'] if p['uniqueId'] == '$W2'][0]['annulee'] is True and d['data']['nombreTotalSujets'] == 3 and d['data']['poidsTotalKg'] == 6.1 and d['data']['version'] == 5 and d['data']['evenements'][-1]['type'] == 'ANNULATION_WEB' and d['data']['evenements'][-1]['peseeUniqueId'] == '$W2'"
web PUT "/pesees/sessions/$WS/pesees/$W2" '{"nombreSujets": 3, "poidsKg": 6.0}'
check "modifier une pesée annulée : 400" "code == 400 and 'annulée' in ' '.join(d.get('errors') or [])"

echo "== 18. Synchro du téléphone sur la session web (valeur ancienne pour la pesée 1, pesée 2 absente, 1 nouvelle)"
M1="$(uuid)"; TM="$(date +%Y-%m-%dT%H:%M:%S)"
post_sync "$(payload "$WS" "$PROJET" EN_COURS "" "$W1|3|6.3|$TM|false;$M1|3|6.5|$TM|false")"
check "200 : pesée 1 garde 6.1 (serveur prime), pesée 2 toujours là et annulée, M1 acceptée (MOBILE), 6 sujets 12.6 kg, version 6, aucun événement ajouté" \
  "code == 200 and [p for p in d['data']['pesees'] if p['uniqueId'] == '$W1'][0]['poidsKg'] == 6.1 and [p for p in d['data']['pesees'] if p['uniqueId'] == '$W2'][0]['annulee'] is True and [p for p in d['data']['pesees'] if p['uniqueId'] == '$M1'][0]['origine'] == 'MOBILE' and len(d['data']['pesees']) == 3 and d['data']['nombreTotalSujets'] == 6 and d['data']['poidsTotalKg'] == 12.6 and d['data']['version'] == 6 and len(d['data']['evenements']) == 5 and d['data']['peseesRefusees'] == [] and d['data']['origine'] == 'WEB'"
post_sync "$(payload "$WS" "$PROJET" EN_COURS "" "$W1|3|6.3|$TM|false;$M1|3|6.5|$TM|false")"
check "renvoi identique : version inchangée (6)" "code == 200 and d['data']['version'] == 6"

echo "== 19. Web : terminer"
web POST "/pesees/sessions/$WS/terminer" ""
check "TERMINEE, dateFin renseignée, version 7, événement TERMINAISON_WEB" \
  "code == 200 and d['data']['statut'] == 'TERMINEE' and d['data']['dateFin'] and d['data']['version'] == 7 and d['data']['evenements'][-1]['type'] == 'TERMINAISON_WEB' and d['data']['evenements'][-1]['description'].startswith('Session terminée : 6 sujets, 12,6 kg, poids moyen 2,1 kg par ')"

echo "== 20. Synchro du téléphone après la terminaison web"
M2="$(uuid)"
post_sync "$(payload "$WS" "$PROJET" EN_COURS "" "$W1|3|6.3|$TM|false;$M1|3|6.5|$TM|false;$M2|2|4.4|$TM|false")"
check "200, peseesRefusees = [M2], reste TERMINEE, 3 pesées, version 7" \
  "code == 200 and d['data']['peseesRefusees'] == ['$M2'] and d['data']['statut'] == 'TERMINEE' and len(d['data']['pesees']) == 3 and d['data']['version'] == 7"
post_sync "$(payload "$WS" "$PROJET" TERMINEE "" "$W1|3|6.3|$TM|false;$M1|3|6.5|$TM|false")"
check "téléphone envoie TERMINEE sur une session déjà TERMINEE : 200, inchangée, peseesRefusees vide" \
  "code == 200 and d['data']['statut'] == 'TERMINEE' and d['data']['peseesRefusees'] == [] and d['data']['version'] == 7"

echo "== 21. Journal et détail"
get "/pesees/sessions/$WS"
check "détail : 6 événements dans l'ordre CREATION, AJOUT, AJOUT, MODIFICATION, ANNULATION, TERMINAISON, dates croissantes, version 7" \
  "code == 200 and [e['type'] for e in d['data']['evenements']] == ['CREATION_WEB', 'AJOUT_WEB', 'AJOUT_WEB', 'MODIFICATION_WEB', 'ANNULATION_WEB', 'TERMINAISON_WEB'] and (lambda ds: ds == sorted(ds))([e['date'] for e in d['data']['evenements']]) and d['data']['version'] == 7 and all(e['uniqueId'] and e['parNom'] and e['description'] for e in d['data']['evenements'])"
get "/pesees/sessions/list?projetUniqueId=$PROJET&statut=TERMINEE&page=0&size=100"
check "liste : version et origine présentes" \
  "code == 200 and any(s['uniqueId'] == '$WS' and s['origine'] == 'WEB' and s['version'] == 7 for s in d['data']['data']) and any(s['uniqueId'] == '$S1' and s['origine'] == 'MOBILE' for s in d['data']['data'])"

echo "== 22. Web : refus"
web PUT "/pesees/sessions/$WS/pesees/$W1" '{"nombreSujets": 3, "poidsKg": 6.0}'
check "modifier sur session TERMINEE : 400 « terminée »" "code == 400 and 'terminée' in ' '.join(d.get('errors') or [])"
web POST "/pesees/sessions/$WS/pesees" '{"nombreSujets": 3, "poidsKg": 6.0}'
check "ajouter sur session TERMINEE : 400" "code == 400 and 'terminée' in ' '.join(d.get('errors') or [])"
web POST "/pesees/sessions/$WS/terminer" ""
check "terminer deux fois : 400" "code == 400"
web POST /pesees/sessions "{\"projetUniqueId\": \"$PROJET\", \"nombreParDefaut\": 2}"
WV="$(jval "d['data']['uniqueId']")"
web POST "/pesees/sessions/$WV/terminer" ""
check "terminer une session web sans pesée : 400" "code == 400 and 'sans aucune pesée' in ' '.join(d.get('errors') or [])"
web POST /pesees/sessions "{\"projetUniqueId\": \"$AUTRE_PROJET\", \"nombreParDefaut\": 2}"
check "ouvrir sur le projet d'une autre ferme : 400" "code == 400"
web POST /pesees/sessions "{\"projetUniqueId\": \"$PROJET_SUPPRIME\", \"nombreParDefaut\": 2}"
check "ouvrir sur un projet supprimé : 400" "code == 400 and 'supprimé' in ' '.join(d.get('errors') or [])"
web POST "/pesees/sessions/inexistante/pesees" '{"nombreSujets": 3, "poidsKg": 6.0}'
check "session inconnue : 400" "code == 400 and 'introuvable' in ' '.join(d.get('errors') or [])"
if [ -n "${TOKEN_COMPTA:-}" ]; then
  web POST /pesees/sessions "{\"projetUniqueId\": \"$PROJET\", \"nombreParDefaut\": 3}" "$TOKEN_COMPTA"
  check "COMPTABLE : ouvrir 400 « pas autorisé »" "code == 400 and 'autorisé' in ' '.join(d.get('errors') or [])"
  web POST "/pesees/sessions/$WV/pesees" '{"nombreSujets": 3, "poidsKg": 6.0}' "$TOKEN_COMPTA"
  check "COMPTABLE : ajouter 400 « pas autorisé »" "code == 400 and 'autorisé' in ' '.join(d.get('errors') or [])"
  web POST "/pesees/sessions/$WV/terminer" "" "$TOKEN_COMPTA"
  check "COMPTABLE : terminer 400 « pas autorisé »" "code == 400 and 'autorisé' in ' '.join(d.get('errors') or [])"
else
  echo "ECHEC  jeton COMPTABLE absent"; FAIL=$((FAIL+1))
fi

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
