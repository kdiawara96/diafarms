#!/usr/bin/env bash
# Rappels de fin d'abonnement et délai de grâce (AbonnementEcheance, AbonnementRappelService),
# 2026-10-05.
#
# Trois fermes NEUVES à chaque passage :
#   A : abonnement payé (periodicite MENSUEL posée en SQL), ADMIN + un COMPTABLE ;
#   B : témoin, jamais touchée (essai normal de 14 jours) ;
#   C : en période d'essai (ESSAI).
# Les dates de fin sont posées en SQL par rapport à aujourd'hui (current_date).
#
# Vérifie :
#   - J-7, J-1 et début de grâce : un rappel chacun, une seule fois (2e passage : rien) ;
#   - la simulation (executer=false) n'enregistre et n'envoie rien ;
#   - cloche : notification ABONNEMENT pour l'ADMIN (lien /abonnement), rien pour le COMPTABLE ;
#   - email envoyé à l'ADMIN (si MAIL_SINK_DIR pointe sur le dossier du faux SMTP) ;
#   - un renouvellement (déclaration + validation) réarme les rappels ;
#   - pendant la grâce : statut non EXPIRE (le web ne bloque pas), API et mobile répondent ;
#   - après la grâce : EXPIRE comme avant (blocage du web), l'API reste ouverte au mobile ;
#   - l'essai (ESSAI) suit la même grâce ;
#   - le réglage SUPER_ADMIN change la grâce ; délai null en base = 5 jours ;
#   - la ferme B n'est jamais concernée.
#
# Pré-requis : Postgres + backend démarrés, super-admin seedé (superadmin / change-me).
# Variables : BASE (défaut http://localhost:9199/diafarms/api/v1), PGHOST, PGPORT (55432),
# PGUSER (postgres), PGDATABASE (diafarms_scen), SUPERADMIN_ID, SUPERADMIN_PWD,
# MAIL_SINK_DIR (facultatif : dossier où le faux SMTP de test écrit les messages).
# Sortie : une ligne OK/ECHEC par assertion ; code 0 si tout est OK, 1 sinon.
set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
SUPERADMIN_ID="${SUPERADMIN_ID:-superadmin}"
SUPERADMIN_PWD="${SUPERADMIN_PWD:-change-me}"
MAIL_SINK_DIR="${MAIL_SINK_DIR:-}"
PWD_TEST="Test1234!"

TMP="$(mktemp -d)"
PASS=0
FAIL=0

psql_run() {
  local args=(-p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE")
  [ -n "${PGHOST:-}" ] && args=(-h "$PGHOST" "${args[@]}")
  psql "${args[@]}" -v ON_ERROR_STOP=1 -Atc "$1"
}

# Le délai de grâce est un réglage GLOBAL : on le remet tel qu'il était en sortant.
GRACE_INITIALE="$(psql_run "select coalesce(delai_grace_jours::text, 'NULL') from abonnement_config order by id limit 1")"
restaurer() {
  [ -n "$GRACE_INITIALE" ] && psql_run "update abonnement_config set delai_grace_jours=$GRACE_INITIALE" >/dev/null
  rm -rf "$TMP"
}
trap restaurer EXIT

api() { # $1=METHOD $2=chemin $3=JSON (facultatif) ; réponse dans $TMP/body, code dans $TMP/code
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${3:+-d "$3"} > "$TMP/code"
}
code() { cat "$TMP/code"; }
jval() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$TMP/body" "$1"; }

check() { # $1=libellé $2=expression python sur d (corps JSON) et code
  if python3 - "$TMP/body" "$TMP/code" "$2" <<'PY'
import json, sys
body, codef, expr = sys.argv[1:4]
code = int(open(codef).read().strip() or 0)
try:
    d = json.load(open(body))
except Exception:
    d = {}
ok = False
try:
    ok = bool(eval(expr, {"d": d, "code": code}))
except Exception as e:
    print("   exception:", e, file=sys.stderr)
if not ok:
    print("   code HTTP:", code, "réponse:", json.dumps(d, ensure_ascii=False)[:900], file=sys.stderr)
sys.exit(0 if ok else 1)
PY
  then echo "OK     $1"; PASS=$((PASS+1))
  else echo "ECHEC  $1"; FAIL=$((FAIL+1)); fi
}

check_eq() { # $1=libellé $2=attendu $3=obtenu
  if [ "$2" = "$3" ]; then echo "OK     $1 ($3)"; PASS=$((PASS+1))
  else echo "ECHEC  $1 (attendu « $2 », obtenu « $3 »)"; FAIL=$((FAIL+1)); fi
}

ok_cree() {
  local c; c="$(code)"
  if [ "$c" != "200" ] && [ "$c" != "201" ]; then
    echo "ECHEC  $1 ($c) : $(head -c 600 "$TMP/body")"; exit 1
  fi
}

login_mobile() {
  curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"] or "")
except Exception: print("")'
}

SUFFIXE="$(date +%H%M%S)$RANDOM"
LETTRES="$(echo "$SUFFIXE" | tr '0-9' 'a-j')"
HASH="$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'$PWD_TEST', bcrypt.gensalt(10)).decode())")"

TOKEN_SA="$(login_mobile "$SUPERADMIN_ID" "$SUPERADMIN_PWD")"
[ -n "$TOKEN_SA" ] || { echo "ECHEC  connexion super-admin"; exit 1; }

nouvelle_ferme() { # $1=lettre -> FARM_ID, ADMIN_EMAIL, TOKEN_ADMIN, FARM_UID
  TOKEN="$TOKEN_SA"
  ADMIN_EMAIL="admin-rap$1-$SUFFIXE@t.local"
  api POST /users/create "{\"fullName\":\"Rap$1$LETTRES\",\"email\":\"$ADMIN_EMAIL\",\"telephone\":\"7$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\",\"farmName\":\"FermeRappel$1$SUFFIXE\",\"roles\":[\"COMPTABLE\"]}"
  ok_cree "création de la ferme $1 et de son ADMIN"
  psql_run "UPDATE roles_users SET id_roles=(select id from roles where role='ADMIN') WHERE id_utilisateurs=(select id from utilisateurs where email='$ADMIN_EMAIL')" >/dev/null
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$ADMIN_EMAIL'" >/dev/null
  TOKEN_ADMIN="$(login_mobile "$ADMIN_EMAIL" "$PWD_TEST")"
  [ -n "$TOKEN_ADMIN" ] || { echo "ECHEC  connexion ADMIN de la ferme $1"; exit 1; }
  FARM_ID="$(psql_run "select farm_id from utilisateurs where email='$ADMIN_EMAIL'")"
  FARM_UID="$(psql_run "select unique_id from farms where id=$FARM_ID")"
  TOKEN="$TOKEN_ADMIN"
  api GET /abonnements/moi   # crée l'abonnement d'essai s'il n'existe pas encore
  ok_cree "abonnement de la ferme $1"
}

nouvelle_ferme A; FARM_A="$FARM_ID"; UID_A="$FARM_UID"; EMAIL_A="$ADMIN_EMAIL"; TOKEN_A="$TOKEN_ADMIN"
COMPTA_EMAIL="compta-rap-$SUFFIXE@t.local"
TOKEN="$TOKEN_A"
api PUT /farm-settings '{"comptableMobileEnabled":true,"comptableWebEnabled":true}'
api POST /users/create-pro-or-finance "{\"fullName\":\"Comptable $LETTRES\",\"email\":\"$COMPTA_EMAIL\",\"telephone\":\"6$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\",\"roles\":[\"COMPTABLE\"]}"
ok_cree "COMPTABLE de la ferme A"
psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$COMPTA_EMAIL'" >/dev/null
TOKEN_COMPTA="$(login_mobile "$COMPTA_EMAIL" "$PWD_TEST")"
nouvelle_ferme B; FARM_B="$FARM_ID"; UID_B="$FARM_UID"; TOKEN_B="$TOKEN_ADMIN"
nouvelle_ferme C; FARM_C="$FARM_ID"; UID_C="$FARM_UID"; TOKEN_C="$TOKEN_ADMIN"
echo "-- Fermes A=$FARM_A B=$FARM_B C=$FARM_C"

ABO_A="$(psql_run "select id from abonnements where farm_id=$FARM_A")"
ABO_B="$(psql_run "select id from abonnements where farm_id=$FARM_B")"
ABO_C="$(psql_run "select id from abonnements where farm_id=$FARM_C")"
# A devient un abonnement payé (comme après une première validation).
psql_run "update abonnements set periodicite='MENSUEL', statut='ACTIF' where id=$ABO_A" >/dev/null

fin() { # $1=abonnement id $2=décalage en jours par rapport à aujourd'hui
  psql_run "update abonnements set date_fin=current_date + ($2) where id=$1" >/dev/null
}
nb_rappels() { # $1=abonnement id [$2=type]
  psql_run "select count(*) from abonnement_rappels where abonnement_id=$1 ${2:+and type='$2'}"
}
rappels() { # $1=true|false -> corps = liste des rappels
  TOKEN="$TOKEN_SA"; api POST "/abonnements/rappels?executer=$1"
}
# Rappels de la réponse pour une ferme donnée (python) : R(uid)
R="lambda u: [r for r in (d.get('data') or []) if r['farmUniqueId'] == u]"
nb_mails() { # $1=adresse
  [ -n "$MAIL_SINK_DIR" ] || { echo "-"; return; }
  grep -l "$1" "$MAIL_SINK_DIR"/*.eml 2>/dev/null | wc -l
}
grace() { psql_run "update abonnement_config set delai_grace_jours=$1" >/dev/null; }

grace 5

# --- J-7 : simulation puis envoi -----------------------------------------------------------
fin "$ABO_A" 7
rappels false
check "simulation : rappel J7 prévu pour A" "code == 200 and [r['type'] for r in ($R)('$UID_A')] == ['J7'] and not ($R)('$UID_A')[0]['envoye']"
check "simulation : message du rappel J7 (date, prix par poule, page Abonnement)" "'se termine le' in ($R)('$UID_A')[0]['message'] and 'Montant : 5 000 FCFA par mois (0 poule, prix minimum) ou 50 000 FCFA par an.' in ($R)('$UID_A')[0]['message'] and 'J\\'ai payé' in ($R)('$UID_A')[0]['message'] and '5 jours pour renouveler' in ($R)('$UID_A')[0]['message']"
check "simulation : destinataire = l'ADMIN de A seulement" "($R)('$UID_A')[0]['destinataires'] == ['$EMAIL_A']"
check "simulation : rien pour la ferme témoin B" "($R)('$UID_B') == []"
check_eq "simulation : rien enregistré" "0" "$(nb_rappels "$ABO_A")"
MAILS_AVANT="$(nb_mails "$EMAIL_A")"
TOKEN="$TOKEN_A"; api GET /notifications/list
check "simulation : rien dans la cloche de l'ADMIN" "not any(n['type'] == 'ABONNEMENT' for n in d['data'])"

rappels true
check "envoi : rappel J7 envoyé à A, 1 email" "[(r['type'], r['envoye'], r['emailsEnvoyes']) for r in ($R)('$UID_A')] == [('J7', True, 1)]"
check_eq "envoi : une ligne J7 enregistrée" "1" "$(nb_rappels "$ABO_A" J7)"
if [ -n "$MAIL_SINK_DIR" ]; then
  sleep 1
  check_eq "envoi : email reçu par l'ADMIN de A" "$((MAILS_AVANT + 1))" "$(nb_mails "$EMAIL_A")"
fi
rappels true
check "2e passage le même jour : rien pour A" "($R)('$UID_A') == []"
check_eq "2e passage : toujours une seule ligne J7" "1" "$(nb_rappels "$ABO_A" J7)"

TOKEN="$TOKEN_A"; api GET /notifications/list
DATE_A="$(psql_run "select date_fin from abonnements where id=$ABO_A")"
check "cloche ADMIN : rappel J7 avec lien vers /abonnement" "[(n['key'], n['actionPath'], n['level']) for n in d['data'] if n['type'] == 'ABONNEMENT'] == [('abonnement-j7-$DATE_A', '/abonnement', 'WARNING')]"
check "cloche ADMIN : texte « se termine le … (dans 7 jours) »" "any('se termine le' in n['message'] and 'dans 7 jours' in n['message'] for n in d['data'] if n['type'] == 'ABONNEMENT')"
if [ -n "$TOKEN_COMPTA" ]; then
  TOKEN="$TOKEN_COMPTA"; api GET /notifications/list
  check "cloche COMPTABLE : pas de rappel d'abonnement" "code == 200 and not any(n['type'] == 'ABONNEMENT' for n in d['data'])"
fi

# --- J-1 ------------------------------------------------------------------------------------
fin "$ABO_A" 1
rappels true
check "J-1 : rappel J1 envoyé à A" "[(r['type'], r['envoye']) for r in ($R)('$UID_A')] == [('J1', True)]"
check "J-1 : texte « se termine demain »" "'se termine demain' in ($R)('$UID_A')[0]['message']"
rappels true
check "J-1 : 2e passage, rien" "($R)('$UID_A') == []"
check_eq "J-1 : une seule ligne J1" "1" "$(nb_rappels "$ABO_A" J1)"

# --- Début de la grâce ------------------------------------------------------------------------
fin "$ABO_A" -1
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "grâce J+1 : statut ACTIF, en grâce, 5 jours restants, pas bloqué" "d['data']['statutEffectif'] == 'ACTIF' and d['data']['enGrace'] and d['data']['joursGraceRestants'] == 5 and d['data']['delaiGraceJours'] == 5"
rappels true
check "grâce : rappel GRACE envoyé à A" "[(r['type'], r['envoye']) for r in ($R)('$UID_A')] == [('GRACE', True)]"
check "grâce : texte « terminé depuis le … il vous reste 5 jours »" "'est terminé depuis le' in ($R)('$UID_A')[0]['message'] and 'Il vous reste 5 jours pour renouveler' in ($R)('$UID_A')[0]['message']"
rappels true
check "grâce : 2e passage, rien" "($R)('$UID_A') == []"
check_eq "grâce : une seule ligne GRACE" "1" "$(nb_rappels "$ABO_A" GRACE)"
TOKEN="$TOKEN_A"; api GET /notifications/list
check "cloche ADMIN : rappel de grâce CRITIQUE, nouvelle clé non lue" "[(n['key'].startswith('abonnement-grace-'), n['level'], n['read']) for n in d['data'] if n['type'] == 'ABONNEMENT'] == [(True, 'CRITIQUE', False)]"

# Accès pendant la grâce : web non bloqué (statut non EXPIRE), API + mobile répondent.
fin "$ABO_A" -5
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "grâce dernier jour (J+5) : toujours non bloqué, 1 jour restant" "d['data']['statutEffectif'] == 'ACTIF' and d['data']['enGrace'] and d['data']['joursGraceRestants'] == 1"
api GET /projets/select
check "grâce : l'API répond (synchro mobile)" "code == 200"
check_eq "grâce : connexion mobile possible" "1" "$([ -n "$(login_mobile "$EMAIL_A" "$PWD_TEST")" ] && echo 1 || echo 0)"
# (Pas de passage de la tâche ici : déplacer date_fin en SQL simulerait une NOUVELLE période,
# alors qu'en vrai la date de fin ne bouge pas pendant la grâce.)

# --- Après la grâce : bloqué comme avant -----------------------------------------------------
fin "$ABO_A" -6
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "après la grâce (J+6) : EXPIRE (écran de blocage du web)" "d['data']['statutEffectif'] == 'EXPIRE' and not d['data']['enGrace']"
api GET /projets/select
check "après la grâce : l'API reste ouverte au mobile (comme avant)" "code == 200"
api GET /notifications/list
check "après la grâce : plus de rappel dans la cloche" "not any(n['type'] == 'ABONNEMENT' for n in d['data'])"
rappels true
check "après la grâce : aucun rappel" "($R)('$UID_A') == []"

# --- Renouvellement : réarme les rappels -----------------------------------------------------
fin "$ABO_A" -1
TOKEN="$TOKEN_A"
api POST /abonnements/declarer-paiement '{"periodicite":"MENSUEL","moyenPaiement":"Orange Money","reference":"TEST-RAPPEL"}'
ok_cree "déclaration du paiement de A"
PAIEMENT="$(jval "d['data']['uniqueId']")"
TOKEN="$TOKEN_SA"; api POST "/abonnements/$PAIEMENT/valider"
ok_cree "validation du paiement de A"
NOUVELLE_FIN="$(psql_run "select date_fin from abonnements where id=$ABO_A")"
# Payé pendant la grâce : on repart de l'échéance (hier), pas d'aujourd'hui.
check_eq "renouvellement : nouvelle date de fin = échéance + 30" "$(psql_run "select current_date - 1 + 30")" "$NOUVELLE_FIN"
TOKEN="$TOKEN_A"; api GET /notifications/list
check "renouvellement : le rappel disparaît de la cloche" "not any(n['type'] == 'ABONNEMENT' for n in d['data'])"
api GET /abonnements/moi
check "renouvellement : ACTIF, plus en grâce" "d['data']['statutEffectif'] == 'ACTIF' and not d['data']['enGrace'] and d['data']['joursRestants'] == 29"
# La nouvelle période arrive à son tour à J-7 (date de fin différente de la précédente) :
fin "$ABO_A" 6
rappels true
check "nouvelle période : le rappel J7 repart" "[(r['type'], r['envoye']) for r in ($R)('$UID_A')] == [('J7', True)]"
check_eq "nouvelle période : deux lignes J7 au total (une par période)" "2" "$(nb_rappels "$ABO_A" J7)"

# --- Essai (ESSAI) : même grâce ----------------------------------------------------------------
fin "$ABO_C" 7
rappels true
check "essai J-7 : rappel « période d'essai » envoyé à C" "[(r['type'], r['envoye']) for r in ($R)('$UID_C')] == [('J7', True)] and 'période d\\'essai' in ($R)('$UID_C')[0]['message']"
fin "$ABO_C" -1
TOKEN="$TOKEN_C"; api GET /abonnements/moi
check "essai terminé hier : ESSAI en grâce, non bloqué" "d['data']['statutEffectif'] == 'ESSAI' and d['data']['estEssai'] and d['data']['enGrace'] and d['data']['joursGraceRestants'] == 5"
rappels true
check "essai : rappel GRACE « période d'essai … terminée »" "[(r['type'], r['envoye']) for r in ($R)('$UID_C')] == [('GRACE', True)] and 'est terminée depuis le' in ($R)('$UID_C')[0]['message']"
fin "$ABO_C" -6
TOKEN="$TOKEN_C"; api GET /abonnements/moi
check "essai après la grâce : EXPIRE" "d['data']['statutEffectif'] == 'EXPIRE'"

# --- Réglage SUPER_ADMIN du délai de grâce --------------------------------------------------
TOKEN="$TOKEN_SA"; api PUT /abonnements/config '{"delaiGraceJours":2}'
check "réglage : délai de grâce passé à 2 jours" "code == 200 and d['data']['delaiGraceJours'] == 2"
api PUT /abonnements/config '{"delaiGraceJours":-1}'
check "réglage : délai négatif refusé" "code == 400"
TOKEN="$TOKEN_A"; api PUT /abonnements/config '{"delaiGraceJours":9}'
check "réglage : refusé à un ADMIN de ferme (403)" "code == 403"
fin "$ABO_A" -2
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "grâce de 2 jours : J+2 encore ouvert" "d['data']['statutEffectif'] == 'ACTIF' and d['data']['enGrace'] and d['data']['joursGraceRestants'] == 1"
fin "$ABO_A" -3
api GET /abonnements/moi
check "grâce de 2 jours : J+3 EXPIRE" "d['data']['statutEffectif'] == 'EXPIRE'"
grace 0
fin "$ABO_A" -1
api GET /abonnements/moi
check "grâce de 0 jour : bloqué dès le lendemain de la fin" "d['data']['statutEffectif'] == 'EXPIRE'"

# --- Délai null en base = 5 jours -------------------------------------------------------------
grace NULL
TOKEN="$TOKEN_SA"; api GET /abonnements/config
check "config null en base : 5 jours affichés" "d['data']['delaiGraceJours'] == 5"
fin "$ABO_A" -5
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "config null : J+5 encore en grâce" "d['data']['enGrace'] and d['data']['delaiGraceJours'] == 5"
fin "$ABO_A" -6
api GET /abonnements/moi
check "config null : J+6 EXPIRE" "d['data']['statutEffectif'] == 'EXPIRE'"

# --- Portail SUPER_ADMIN : fermes en grâce ----------------------------------------------------
fin "$ABO_A" -2
TOKEN="$TOKEN_SA"; api GET /abonnements/fermes
check "portail : A listée en grâce, B non" "any(a['farmUniqueId'] == '$UID_A' and a['enGrace'] for a in d['data']) and any(a['farmUniqueId'] == '$UID_B' and not a['enGrace'] and a['statutEffectif'] == 'ESSAI' for a in d['data'])"
TOKEN="$TOKEN_A"; api GET /abonnements/fermes
check "portail : refusé à un ADMIN de ferme" "code == 400"
TOKEN="$TOKEN_A"; api POST "/abonnements/rappels?executer=true"
check "déclenchement manuel : refusé à un ADMIN de ferme" "code == 400"

# --- Ferme témoin B -------------------------------------------------------------------------
check_eq "ferme B : aucun rappel enregistré" "0" "$(nb_rappels "$ABO_B")"
TOKEN="$TOKEN_B"; api GET /abonnements/moi
check "ferme B : essai normal, ni grâce ni blocage" "d['data']['statutEffectif'] == 'ESSAI' and not d['data']['enGrace'] and d['data']['joursRestants'] == 14"
api GET /notifications/list
check "ferme B : rien dans la cloche" "not any(n['type'] == 'ABONNEMENT' for n in d['data'])"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
