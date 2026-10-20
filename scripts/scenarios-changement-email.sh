#!/usr/bin/env bash
# Changement d'e-mail d'un utilisateur validé par un code envoyé à la NOUVELLE adresse
# (ChangementEmailService, /users/update-prod-finan, /users/confirmer-email, /users/annuler-email),
# 2026-10-20.
#
# Vérifie :
#   - un nouvel e-mail reste en attente : l'ancien sert toujours à se connecter, le nouveau non ;
#   - les autres champs (nom, ville) sont enregistrés tout de suite ;
#   - le code arrive à la nouvelle adresse ; code faux refusé ; bon code = e-mail changé,
#     connexion avec le nouveau, plus avec l'ancien, l'ancienne adresse est prévenue ;
#   - e-mail déjà pris par un autre compte, format invalide : refusés, rien ne change ;
#   - l'ADMIN change l'e-mail d'un employé (code chez l'employé) ; l'ADMIN d'une autre
#     ferme ne peut ni confirmer ni annuler (404) ;
#   - 5 codes faux : changement annulé ; annulation à la main ; e-mail vidé sans code ;
#   - au plus 3 codes envoyés par 15 minutes et par compte.
#
# Pré-requis : Postgres + backend démarrés, super-admin seedé (superadmin / change-me),
# MAIL_SINK_DIR = dossier où le faux SMTP écrit les e-mails (un fichier .eml par e-mail).
# Variables : BASE, PGHOST, PGPORT, PGUSER, PGDATABASE, SUPERADMIN_ID, SUPERADMIN_PWD, MAIL_SINK_DIR.
# Sortie : une ligne OK/ECHEC par vérification ; code 0 si tout est OK, 1 sinon.
set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
SUPERADMIN_ID="${SUPERADMIN_ID:-superadmin}"
SUPERADMIN_PWD="${SUPERADMIN_PWD:-change-me}"
MAIL_SINK_DIR="${MAIL_SINK_DIR:?MAIL_SINK_DIR requis (dossier du faux SMTP)}"
PWD_TEST="Test1234!"

TMP="$(mktemp -d)"
PASS=0
FAIL=0
trap 'rm -rf "$TMP"' EXIT

psql_run() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -Atc "$1"
}

api() { # $1=METHOD $2=chemin $3=JSON (facultatif)
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${3:+-d "$3"} > "$TMP/code"
}

check() { # $1=libellé $2=expression python sur d (corps JSON) et code
  if python3 - "$TMP/body" "$TMP/code" "$2" <<'PY'
import json, sys
body, codef, expr = sys.argv[1:4]
code = int(open(codef).read() or 0)
try: d = json.load(open(body))
except Exception: d = {}
sys.exit(0 if eval(expr) else 1)
PY
  then echo "OK     $1"; PASS=$((PASS+1))
  else echo "ECHEC  $1 ($(cat "$TMP/code")) : $(head -c 400 "$TMP/body")"; FAIL=$((FAIL+1)); fi
}

verifie() { # $1=libellé $2=commande shell (vrai/faux)
  if eval "$2"; then echo "OK     $1"; PASS=$((PASS+1)); else echo "ECHEC  $1"; FAIL=$((FAIL+1)); fi
}

login() { # $1=identifiant -> accessToken ou vide
  curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$PWD_TEST" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"] or "")
except Exception: print("")'
}

# Dernier e-mail reçu par $1 dont le sujet contient $2 : affiche le code à 6 chiffres
# (ou "PRESENT" si $3=presence), rien sinon.
mail_lu() {
  python3 - "$MAIL_SINK_DIR" "$1" "$2" "${3:-code}" <<'PY'
import email, glob, os, re, sys
from email.header import decode_header, make_header
d, dest, sujet, mode = sys.argv[1:5]
for f in sorted(glob.glob(os.path.join(d, "*.eml")), reverse=True):
    m = email.message_from_bytes(open(f, "rb").read())
    if dest.lower() not in (m.get("To") or "").lower(): continue
    if sujet not in str(make_header(decode_header(m.get("Subject") or ""))): continue
    texte = ""
    for p in m.walk():
        if p.get_content_type() == "text/plain":
            texte += p.get_payload(decode=True).decode(p.get_content_charset() or "utf-8", "replace")
    if mode == "presence": print("PRESENT"); break
    r = re.search(r"\b(\d{6})\b", texte)
    if r: print(r.group(1))
    break
PY
}

SUFFIXE="$(date +%H%M%S)$RANDOM"
LETTRES="$(echo "$SUFFIXE" | tr '0-9' 'a-j')"
HASH="$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'$PWD_TEST', bcrypt.gensalt(10)).decode())")"

TOKEN_SA="$(curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' --data-urlencode grantType=password \
  --data-urlencode "identifiant=$SUPERADMIN_ID" --data-urlencode "password=$SUPERADMIN_PWD" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"] or "")
except Exception: print("")')"
[ -n "$TOKEN_SA" ] || { echo "ECHEC  connexion super-admin"; exit 1; }

nouvelle_ferme() { # $1=lettre -> ADMIN_EMAIL_$1, ADMIN_UID_$1
  TOKEN="$TOKEN_SA"
  local mail="admin$1-$SUFFIXE@t.local"
  local tel="7$(python3 -c 'import random; print(random.randint(1000000, 9999999))')"
  api POST /users/create "{\"fullName\":\"Mail$1$LETTRES\",\"email\":\"$mail\",\"telephone\":\"$tel\",\"farmName\":\"FermeMail$1$SUFFIXE\",\"roles\":[\"COMPTABLE\"]}"
  check "création de la ferme $1" 'code in (200, 201)'
  psql_run "UPDATE roles_users SET id_roles=(select id from roles where role='ADMIN') WHERE id_utilisateurs=(select id from utilisateurs where email='$mail')" >/dev/null
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$mail'" >/dev/null
  eval "ADMIN_EMAIL_$1='$mail'"
  eval "ADMIN_TEL_$1='$tel'"
  eval "ADMIN_UID_$1='$(psql_run "select unique_id from utilisateurs where email='$mail'")'"
}

nouvelle_ferme A
nouvelle_ferme B
TOKEN_A="$(login "$ADMIN_EMAIL_A")"
TOKEN_B="$(login "$ADMIN_EMAIL_B")"
verifie "connexion des deux ADMIN" '[ -n "$TOKEN_A" ] && [ -n "$TOKEN_B" ]'

maj() { # $1=uniqueId $2=fullName $3=telephone $4=email $5=ville
  api PUT "/users/update-prod-finan/$1" "{\"fullName\":\"$2\",\"telephone\":\"$3\",\"email\":\"$4\",\"city\":\"$5\",\"region\":\"R\"}"
}

# --- 1. Profil : l'ADMIN A change son propre e-mail
NOUVEAU_A="nouveau-a-$SUFFIXE@t.local"
TOKEN="$TOKEN_A"
maj "$ADMIN_UID_A" "Nom Change $LETTRES" "$ADMIN_TEL_A" "$NOUVEAU_A" "Kati"
check "changement demandé : 200, e-mail inchangé, nouveau en attente" \
  "code == 200 and d['data']['email'] == '$ADMIN_EMAIL_A' and d['data']['emailEnAttente'] == '$NOUVEAU_A'"
check "les autres champs sont enregistrés tout de suite" \
  "d['data']['fullName'] == 'Nom Change $LETTRES' and d['data']['city'] == 'Kati'"
verifie "base : e-mail inchangé" '[ "$(psql_run "select email from utilisateurs where unique_id='"'"'$ADMIN_UID_A'"'"'")" = "$ADMIN_EMAIL_A" ]'
sleep 1
CODE_A="$(mail_lu "$NOUVEAU_A" "code de confirmation")"
verifie "code reçu à la nouvelle adresse" '[ -n "$CODE_A" ]'
verifie "connexion avec le nouvel e-mail refusée tant que le code n'est pas saisi" '[ -z "$(login "$NOUVEAU_A")" ]'
verifie "connexion avec l'ancien e-mail toujours possible" '[ -n "$(login "$ADMIN_EMAIL_A")" ]'

# Ré-enregistrer le même e-mail en attente : pas de nouveau code (code toujours valable)
NB_MAILS="$(ls "$MAIL_SINK_DIR" | wc -l)"
maj "$ADMIN_UID_A" "Nom Change $LETTRES" "$ADMIN_TEL_A" "$NOUVEAU_A" "Kati"
sleep 1
verifie "même e-mail en attente ré-enregistré : pas de nouveau code" '[ "$(ls "$MAIL_SINK_DIR" | wc -l)" = "$NB_MAILS" ]'

FAUX="$( [ "$CODE_A" = "000000" ] && echo 111111 || echo 000000 )"
api POST "/users/confirmer-email/$ADMIN_UID_A" "{\"code\":\"$FAUX\"}"
check "code faux refusé" "code == 400 and 'incorrect' in d['message']"

TOKEN="$TOKEN_B"
api POST "/users/confirmer-email/$ADMIN_UID_A" "{\"code\":\"$CODE_A\"}"
check "ADMIN d'une autre ferme : confirmation refusée (404)" "code == 404"
api POST "/users/annuler-email/$ADMIN_UID_A"
check "ADMIN d'une autre ferme : annulation refusée (404)" "code == 404"

TOKEN="$TOKEN_A"
api POST "/users/confirmer-email/$ADMIN_UID_A" "{\"code\":\"$CODE_A\"}"
check "bon code : e-mail changé, plus rien en attente" \
  "code == 200 and d['data']['email'] == '$NOUVEAU_A' and d['data']['emailEnAttente'] is None"
sleep 1
verifie "connexion avec le nouvel e-mail" '[ -n "$(login "$NOUVEAU_A")" ]'
verifie "connexion avec l'ancien e-mail refusée" '[ -z "$(login "$ADMIN_EMAIL_A")" ]'
verifie "ancienne adresse prévenue" '[ "$(mail_lu "$ADMIN_EMAIL_A" "a été changé" presence)" = "PRESENT" ]'
api POST "/users/confirmer-email/$ADMIN_UID_A" "{\"code\":\"$CODE_A\"}"
check "code déjà utilisé : refusé" "code == 400"

# --- 2. Refus : e-mail déjà pris, format invalide
maj "$ADMIN_UID_A" "Nom Change $LETTRES" "$ADMIN_TEL_A" "$ADMIN_EMAIL_B" "Kati"
check "e-mail d'un autre compte refusé" "code == 400 and 'déjà utilisé' in str(d)"
maj "$ADMIN_UID_A" "Nom Change $LETTRES" "$ADMIN_TEL_A" "pas-un-email" "Kati"
check "format invalide refusé" "code == 400 and 'pas valide' in str(d)"
verifie "base : rien en attente après les refus" '[ -z "$(psql_run "select email_en_attente from utilisateurs where unique_id='"'"'$ADMIN_UID_A'"'"'")" ]'

# --- 3. L'ADMIN change l'e-mail d'un employé
EMP_EMAIL="emp-$SUFFIXE@t.local"
EMP_TEL="6$(python3 -c 'import random; print(random.randint(1000000, 9999999))')"
api POST /users/create-pro-or-finance "{\"fullName\":\"Employe $LETTRES\",\"email\":\"$EMP_EMAIL\",\"telephone\":\"$EMP_TEL\",\"roles\":[\"COMPTABLE\"]}"
check "création d'un employé" 'code in (200, 201)'
EMP_UID="$(psql_run "select unique_id from utilisateurs where email='$EMP_EMAIL'")"
EMP_NOUVEAU="emp-nouveau-$SUFFIXE@t.local"
maj "$EMP_UID" "Employe $LETTRES" "$EMP_TEL" "$EMP_NOUVEAU" "Kati"
check "ADMIN : e-mail de l'employé en attente" "code == 200 and d['data']['emailEnAttente'] == '$EMP_NOUVEAU' and d['data']['email'] == '$EMP_EMAIL'"
sleep 1
CODE_EMP="$(mail_lu "$EMP_NOUVEAU" "code de confirmation")"
verifie "code envoyé à la nouvelle adresse de l'employé" '[ -n "$CODE_EMP" ]'

# 5 codes faux : changement annulé
FAUX="$( [ "$CODE_EMP" = "000000" ] && echo 111111 || echo 000000 )"
for i in 1 2 3 4; do api POST "/users/confirmer-email/$EMP_UID" "{\"code\":\"$FAUX\"}"; done
check "4e code faux : encore « incorrect »" "code == 400 and 'incorrect' in d['message']"
api POST "/users/confirmer-email/$EMP_UID" "{\"code\":\"$FAUX\"}"
check "5e code faux : changement annulé" "code == 400 and 'annulé' in d['message']"
api POST "/users/confirmer-email/$EMP_UID" "{\"code\":\"$CODE_EMP\"}"
check "après annulation, même le bon code est refusé" "code == 400"
verifie "base : e-mail de l'employé inchangé" '[ "$(psql_run "select email from utilisateurs where unique_id='"'"'$EMP_UID'"'"'")" = "$EMP_EMAIL" ]'

# Nouveau code (2e envoi), puis annulation à la main
maj "$EMP_UID" "Employe $LETTRES" "$EMP_TEL" "$EMP_NOUVEAU" "Kati"
check "nouvelle demande acceptée" "code == 200 and d['data']['emailEnAttente'] == '$EMP_NOUVEAU'"
api POST "/users/annuler-email/$EMP_UID"
check "annulation à la main" "code == 200 and d['data']['emailEnAttente'] is None and d['data']['email'] == '$EMP_EMAIL'"

# 3e envoi accepté, 4e refusé (au plus 3 par 15 minutes)
maj "$EMP_UID" "Employe $LETTRES" "$EMP_TEL" "$EMP_NOUVEAU" "Kati"
check "3e code envoyé" "code == 200"
maj "$EMP_UID" "Employe $LETTRES" "$EMP_TEL" "autre-$SUFFIXE@t.local" "Kati"
check "4e code refusé (trop de demandes)" "code == 400 and 'Trop de codes' in str(d)"
sleep 1
CODE_EMP="$(mail_lu "$EMP_NOUVEAU" "code de confirmation")"
api POST "/users/confirmer-email/$EMP_UID" "{\"code\":\"$CODE_EMP\"}"
check "ADMIN confirme avec le code donné par l'employé" "code == 200 and d['data']['email'] == '$EMP_NOUVEAU'"

# --- 4. E-mail vidé : retiré tout de suite, sans code
maj "$EMP_UID" "Employe $LETTRES" "$EMP_TEL" "" "Kati"
check "e-mail vidé : retiré sans code" "code == 200 and d['data'].get('email') in (None, '')"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
