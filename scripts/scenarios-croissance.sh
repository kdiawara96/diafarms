#!/usr/bin/env bash
# Évolutions « croissance » (2026-10-08) :
#   A. guide « Bien démarrer » (6 étapes calculées, fermeture), e-mails d'essai J+1 / J+3 /
#      J+7 (conditions, une seule fois), console « Essais inactifs », numéro WhatsApp ;
#   B. parrainage : code, inscription avec code, règles (code inconnu, soi-même, une seule
#      fois), 1 mois offert au premier paiement validé seulement, e-mail au parrain, console ;
#   C. résumé de la semaine : chiffres comparés à des données posées à la main, devise de
#      la ferme, points d'attention, réglage Paramètres, ferme hors statistiques, simulation,
#      une seule fois par semaine ;
#   D. nouvelles alertes (mortalité anormale, stock d'aliment bas, ponte en baisse) :
#      déclenchées au-dessus des seuils, pas en dessous, champs lus par le téléphone.
#
# Pré-requis : Postgres + backend démarrés, super-admin seedé (superadmin / change-me).
# Variables : BASE (défaut http://localhost:9199/diafarms/api/v1), PGHOST, PGPORT (55432),
# PGUSER (postgres), PGDATABASE (diafarms_scen), SUPERADMIN_ID, SUPERADMIN_PWD,
# MAIL_SINK_DIR (facultatif : dossier où le faux SMTP écrit les e-mails).
# Sortie : une ligne OK/ECHEC par vérification ; code 0 si tout est OK, 1 sinon.
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
trap 'rm -rf "$TMP"' EXIT

psql_run() {
  local args=(-p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE")
  [ -n "${PGHOST:-}" ] && args=(-h "$PGHOST" "${args[@]}")
  psql "${args[@]}" -v ON_ERROR_STOP=1 -Atc "$1"
}

api() { # $1=METHOD $2=chemin $3=JSON (facultatif) ; réponse dans $TMP/body, code dans $TMP/code
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${3:+-d "$3"} > "$TMP/code"
}
public() { # sans jeton
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" -H 'Content-Type: application/json' ${3:+-d "$3"} > "$TMP/code"
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
    print("   code HTTP:", code, "réponse:", json.dumps(d, ensure_ascii=False)[:1500], file=sys.stderr)
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

login() { # $1=identifiant $2=mot de passe $3=web|mobile -> jeton (corps en mobile, cookie en web)
  curl -s -D "$TMP/entetes" -X POST "$BASE/auth" -H "X-Client-Type: ${3:-web}" \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | python3 -c 'import json,sys,re
try: t = json.load(sys.stdin)["data"].get("accessToken") or ""
except Exception: t = ""
if not t:
    m = re.search(r"diafarms_access_token=([^;\s]+)", open(sys.argv[1]).read())
    t = m.group(1) if m else ""
print(t)' "$TMP/entetes"
}

# Dernier e-mail du faux SMTP pour un destinataire et un texte (décodé) : 0 si trouvé.
mail_contient() { # $1=destinataire $2=texte
  [ -n "$MAIL_SINK_DIR" ] || return 0
  python3 - "$MAIL_SINK_DIR" "$1" "$2" <<'PY'
import email, glob, os, sys
from email import policy
d, dest, texte = sys.argv[1:4]
for f in sorted(glob.glob(os.path.join(d, "*.eml")), key=os.path.getmtime, reverse=True)[:400]:
    m = email.message_from_bytes(open(f, "rb").read(), policy=policy.default)
    if dest.lower() not in str(m.get("To", "")).lower():
        continue
    corps = str(m.get("Subject", ""))
    for part in m.walk():
        if part.get_content_type() == "text/plain":
            corps += "\n" + part.get_content()
    if texte in corps:
        sys.exit(0)
sys.exit(1)
PY
}
mails_pour() { # nombre d'e-mails reçus par $1 contenant $2
  [ -n "$MAIL_SINK_DIR" ] || { echo 0; return; }
  python3 - "$MAIL_SINK_DIR" "$1" "$2" <<'PY'
import email, glob, os, sys
from email import policy
d, dest, texte = sys.argv[1:4]
n = 0
for f in glob.glob(os.path.join(d, "*.eml")):
    m = email.message_from_bytes(open(f, "rb").read(), policy=policy.default)
    if dest.lower() in str(m.get("To", "")).lower() and texte in str(m.get("Subject", "")):
        n += 1
print(n)
PY
}

jour() { date -d "$1 day" +%F; } # jour -3 = il y a 3 jours

SUFFIXE="$(date +%H%M%S)$RANDOM"
LETTRES="$(echo "$SUFFIXE" | tr '0-9' 'a-j')"
HASH="$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'$PWD_TEST', bcrypt.gensalt(10)).decode())")"
AUJ="$(date +%F)"
tel8() { python3 -c 'import random; print(random.choice("5678") + str(random.randint(1000000, 9999999)))'; }

TOKEN_SA="$(login "$SUPERADMIN_ID" "$SUPERADMIN_PWD" web)"
[ -n "$TOKEN_SA" ] || { echo "ECHEC  connexion super-admin"; exit 1; }

# Inscription publique (page /inscription) -> ADMIN_EMAIL, FARM_ID, FARM_UID, TOKEN_ADMIN (web)
inscrire() { # $1=lettre $2=téléphone $3=code de parrainage (facultatif)
  ADMIN_EMAIL="admin-cro$1-$SUFFIXE@t.local"
  local extra=""; [ -n "${3:-}" ] && extra=",\"codeParrainage\":\"$3\""
  public POST /users/create "{\"fullName\":\"Cro$1$LETTRES\",\"email\":\"$ADMIN_EMAIL\",\"telephone\":\"$2\",\"farmName\":\"FermeCro$1$SUFFIXE\",\"region\":\"Bamako\",\"city\":\"Sogoniko\",\"roles\":[\"ADMIN\"]$extra}"
  ok_cree "inscription de la ferme $1"
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$ADMIN_EMAIL'" >/dev/null
  TOKEN_ADMIN="$(login "$ADMIN_EMAIL" "$PWD_TEST" web)"
  [ -n "$TOKEN_ADMIN" ] || { echo "ECHEC  connexion ADMIN de la ferme $1"; exit 1; }
  FARM_ID="$(psql_run "select farm_id from utilisateurs where email='$ADMIN_EMAIL'")"
  FARM_UID="$(psql_run "select unique_id from farms where id=$FARM_ID")"
  ADMIN_ID="$(psql_run "select id from utilisateurs where email='$ADMIN_EMAIL'")"
  TOKEN="$TOKEN_ADMIN"
  api PUT /farm-settings '{"productionMobileEnabled":true,"productionWebEnabled":true,"comptableMobileEnabled":true,"comptableWebEnabled":true,"venteMobileEnabled":true,"venteWebEnabled":true,"responsableWebEnabled":true}'
}

RACE_ID=""
race() {
  api POST /races/create '{"nom":"Pondeuse Cro","type":"PONDEUSE","origine":"Locale","description":"Race de test","esperanceVieAnnees":3,"poidsAdulteKg":2.0,"productionOeufsAn":280,"couleurOeuf":"BRUN"}'
  ok_cree "race"
  RACE_ID="$(jval "d['data']['id']")"
  psql_run "UPDATE races SET temps_croissance='MOYEN', rusticite='MOYENNE', adaptation_climat='CHAUD_SEC', certification_race='AUCUNE', poids_abattage='NON_APPLICABLE' WHERE id=$RACE_ID" >/dev/null
}
nouveau_projet() { # $1=titre $2=objectif $3=sujets $4=début -> PRJ, BAT
  api POST /batiments/create "{\"nom\":\"Poulailler $1\",\"capacite\":5000}"
  ok_cree "poulailler $1"
  local bat_id; bat_id="$(jval "d['data']['id']")"; BAT="$(jval "d['data']['uniqueId']")"
  api POST /projets/create "{\"titre\":\"$1\",\"responsableId\":$ADMIN_ID,\"dateDebut\":\"$4\",\"dateFinPrevue\":\"2027-12-31\",\"nbSujets\":$3,\"puSujet\":0,\"objectif\":\"$2\",\"raceId\":$RACE_ID,\"occupations\":[{\"batimentId\":$bat_id,\"dateEntree\":\"$4\",\"nbSujets\":$3}]}"
  ok_cree "projet $1"
  PRJ="$(jval "d['data']['uniqueId']")"
}
mort() { api POST /mortalites/create "{\"projetUniqueId\":\"$1\",\"batimentUniqueId\":\"$2\",\"date\":\"$3\",\"nombreMorts\":$4,\"cause\":\"Scénario\"}"; ok_cree "mortalité $3 ($4)"; }
collecte() { api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$1\",\"batimentUniqueId\":\"$2\",\"magasinStockageUniqueId\":\"$STOCK\",\"date\":\"$3\",\"oeufsCollectes\":$4,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"; ok_cree "collecte $3 ($4)"; }
achat_aliment() { api POST "/alimentations/create/$1" "{\"typeAliment\":\"PONTE\",\"sac\":1,\"quantiteKg\":$2,\"coutTotal\":$3,\"dateDistribution\":\"$4\"}"; ok_cree "achat d'aliment $2 kg"; }
conso() { api POST /consommations-aliment/create "{\"projetUniqueId\":\"$1\",\"batimentUniqueId\":\"$2\",\"date\":\"$3\",\"quantiteKg\":$4}"; ok_cree "consommation $3 ($4 kg)"; }

console_ferme() { # $1=farmUniqueId -> ligne de /admin/fermes dans $TMP/body (objet seul)
  TOKEN="$TOKEN_SA"; api GET /admin/fermes
  python3 - "$TMP/body" "$1" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
f = [x for x in d["data"] if x["farmUniqueId"] == sys.argv[2]]
json.dump(f[0] if f else {}, open(sys.argv[1], "w"))
PY
}

echo "== A. Guide « Bien démarrer »"
inscrire G "$(tel8)"; FARM_G="$FARM_ID"; UID_G="$FARM_UID"; TOKEN_G="$TOKEN_ADMIN"; ADMIN_G="$ADMIN_ID"; EMAIL_G="$ADMIN_EMAIL"
TOKEN="$TOKEN_G"; api GET /croissance/guide
check "ferme neuve : guide affiché, 0/6" "code == 200 and d['data']['afficher'] and d['data']['faites'] == 0 and d['data']['total'] == 6 and len(d['data']['etapes']) == 6"
check "chaque étape a un lien" "all(e['lien'].startswith('/') and e['titre'] for e in d['data']['etapes'])"
race
api POST /sites/create "{\"nom\":\"Site Cro $SUFFIXE\"}"; ok_cree "site"
api GET /croissance/guide
check "site sans poulailler : étape 1 pas faite" "[e['fait'] for e in d['data']['etapes'] if e['cle']=='SITE'] == [False]"
nouveau_projet "Ponte G" PONTE 500 "$(jour -20)"; PG="$PRJ"; BG="$BAT"
api GET /croissance/guide
check "site + poulailler + Projet : 2/6" "d['data']['faites'] == 2 and [e['cle'] for e in d['data']['etapes'] if e['fait']] == ['SITE', 'PROJET']"
api POST /magasins/create "{\"nom\":\"Stock G\",\"type\":\"STOCKAGE\"}"; ok_cree "magasin de stockage"; STOCK="$(jval "d['data']['uniqueId']")"
api GET /croissance/guide
check "stockage sans point de vente : étape 3 pas faite" "d['data']['faites'] == 2"
api POST /magasins/create "{\"nom\":\"Boutique G\",\"type\":\"VENTE\"}"; ok_cree "point de vente"
COMPTA_G="compta-cro-$SUFFIXE@t.local"
api POST /users/create-pro-or-finance "{\"fullName\":\"Comptable $LETTRES\",\"email\":\"$COMPTA_G\",\"telephone\":\"6$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\",\"roles\":[\"COMPTABLE\"]}"
ok_cree "membre de l'équipe (COMPTABLE)"
api GET /croissance/guide
check "magasins + équipe : 4/6, pas encore le mobile" "d['data']['faites'] == 4 and [e['fait'] for e in d['data']['etapes'] if e['cle']=='MOBILE'] == [False]"
psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$COMPTA_G'" >/dev/null
TOKEN="$(login "$COMPTA_G" "$PWD_TEST" web)"; api GET /croissance/guide
check "COMPTABLE : pas de guide" "code == 200 and d['data']['afficher'] == False"
api POST /croissance/guide/masquer
check "COMPTABLE : ne peut pas fermer le guide" "code == 400"
TOKEN_MOB="$(login "$EMAIL_G" "$PWD_TEST" mobile)"
TOKEN="$TOKEN_G"; api GET /croissance/guide
check "connexion sur l'application mobile : étape 5 faite (5/6)" "d['data']['faites'] == 5 and [e['fait'] for e in d['data']['etapes'] if e['cle']=='MOBILE'] == [True]"
mort "$PG" "$BG" "$(jour -1)" 1
api GET /croissance/guide
check "première saisie : 6/6, guide terminé et caché" "d['data']['faites'] == 6 and d['data']['termine'] and not d['data']['afficher']"

inscrire H "$(tel8)"; FARM_H="$FARM_ID"; UID_H="$FARM_UID"; TOKEN_H="$TOKEN_ADMIN"
api POST /croissance/guide/masquer
check "fermeture du guide par l'ADMIN" "code == 200 and d['data']['masque'] and not d['data']['afficher'] and d['data']['faites'] == 0"
api GET /croissance/guide
check "guide fermé : reste caché" "not d['data']['afficher'] and d['data']['masque']"
check_eq "fermeture enregistrée sur la ferme (colonne)" "t" "$(psql_run "select guide_demarrage_masque_le is not null from farms where id=$FARM_H")"
check_eq "autre ferme non touchée" "t" "$(psql_run "select guide_demarrage_masque_le is null from farms where id=$FARM_G")"

echo "== A. E-mails d'essai J+1 / J+3 / J+7"
# E1 : inscrite il y a 1 jour, aucun Projet -> J1
inscrire E1 "+223 $(tel8)"; E1="$FARM_ID"; UID_E1="$FARM_UID"; MAIL_E1="$ADMIN_EMAIL"
# E1P : il y a 2 jours, a déjà un Projet -> rien (J1 seulement sans Projet)
inscrire EP "$(tel8)"; EP="$FARM_ID"; UID_EP="$FARM_UID"; race; nouveau_projet "Projet EP" PONTE 100 "$(jour -2)"
# E3 : il y a 4 jours, aucune saisie -> J3
TEL_E3="$(tel8)"; inscrire E3 "$TEL_E3"; E3="$FARM_ID"; UID_E3="$FARM_UID"; MAIL_E3="$ADMIN_EMAIL"
# E7 : il y a 8 jours, aucune saisie, essai fini dans 6 jours -> J7
inscrire E7 "0033 6 $(tel8)"; E7="$FARM_ID"; UID_E7="$FARM_UID"; MAIL_E7="$ADMIN_EMAIL"
# EA : il y a 8 jours mais déjà une saisie -> rien (ferme active)
inscrire EA "$(tel8)"; EA="$FARM_ID"; UID_EA="$FARM_UID"; race; nouveau_projet "Projet EA" PONTE 100 "$(jour -8)"; mort "$PRJ" "$BAT" "$(jour -1)" 1
# ED : démonstration (hors statistiques) ; ES : suspendue -> rien
inscrire ED "$(tel8)"; ED="$FARM_ID"; UID_ED="$FARM_UID"
inscrire ES "$(tel8)"; ES="$FARM_ID"; UID_ES="$FARM_UID"
for f in "$E1:1" "$EP:2" "$E3:4" "$E7:8" "$EA:8" "$ED:8" "$ES:8"; do
  psql_run "UPDATE utilisateurs SET created_at = now() - interval '${f#*:} days' WHERE farm_id=${f%%:*}" >/dev/null
done
psql_run "UPDATE abonnements SET date_fin = current_date + 6 WHERE farm_id IN ($E7, $ED, $ES)" >/dev/null
psql_run "UPDATE farms SET exclure_statistiques = true WHERE id=$ED" >/dev/null
psql_run "UPDATE abonnements SET suspendu = true WHERE farm_id=$ES" >/dev/null

types_essai() { python3 - "$TMP/body" "$1" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(",".join(sorted(x["type"] for x in d.get("data") or [] if x["farmUniqueId"] == sys.argv[2])))
PY
}
TOKEN="$TOKEN_SA"; api POST "/admin/emails-essai"
check "simulation (par défaut) : 200, rien n'est envoyé" "code == 200 and all(not x['envoye'] for x in d['data'])"
cp "$TMP/body" "$TMP/simu"
check_eq "J+1 sans Projet" "ESSAI_J1" "$(types_essai "$UID_E1")"
check_eq "J+2 avec un Projet : rien" "" "$(types_essai "$UID_EP")"
check_eq "J+4 sans saisie : J3" "ESSAI_J3" "$(types_essai "$UID_E3")"
check_eq "J+8 sans saisie : J7" "ESSAI_J7" "$(types_essai "$UID_E7")"
check_eq "J+8 avec une saisie (ferme active) : rien" "" "$(types_essai "$UID_EA")"
check_eq "ferme de démonstration : rien" "" "$(types_essai "$UID_ED")"
check_eq "ferme suspendue : rien" "" "$(types_essai "$UID_ES")"
check "J7 : « se termine dans 6 jours, on vous aide ? » + WhatsApp" "[x for x in d['data'] if x['farmUniqueId']=='$UID_E7' and 'dans 6 jours, on vous aide ?' in x['sujet'] and '+223 83 91 86 99' in x['message']]"
check "J3 : aide WhatsApp" "[x for x in d['data'] if x['farmUniqueId']=='$UID_E3' and '+223 83 91 86 99' in x['message']]"
check "J1 : 3 étapes" "[x for x in d['data'] if x['farmUniqueId']=='$UID_E1' and '3 étapes' in x['sujet'] and '1. ' in x['message'] and '3. ' in x['message']]"
check "textes sans tiret long ni « Lot »" "all('\u2014' not in x['message']+x['sujet'] and '\u2013' not in x['message']+x['sujet'] and ' Lot' not in x['message'] for x in d['data'])"
check_eq "simulation : aucune ligne enregistrée" "0" "$(psql_run "select count(*) from envois_automatiques where farm_id in ($E1,$E3,$E7)")"
api POST "/admin/emails-essai?envoyer=true"
check "envoi : J1, J3, J7 envoyés" "code == 200 and len([x for x in d['data'] if x['farmUniqueId'] in ('$UID_E1','$UID_E3','$UID_E7') and x['envoye']]) == 3"
check_eq "lignes enregistrées (une par ferme)" "3" "$(psql_run "select count(*) from envois_automatiques where farm_id in ($E1,$E3,$E7)")"
api POST "/admin/emails-essai?envoyer=true"
check "deuxième passage : rien de renvoyé" "code == 200 and not [x for x in d['data'] if x['farmUniqueId'] in ('$UID_E1','$UID_E3','$UID_E7')]"
api POST "/admin/emails-essai"
check "simulation après envoi : plus rien pour ces fermes" "not [x for x in d['data'] if x['farmUniqueId'] in ('$UID_E1','$UID_E3','$UID_E7')]"
if [ -n "$MAIL_SINK_DIR" ]; then
  sleep 1
  if mail_contient "$MAIL_E1" "Bien démarrer avec Cocorico en 3 étapes"; then echo "OK     e-mail J1 reçu"; PASS=$((PASS+1)); else echo "ECHEC  e-mail J1 non reçu"; FAIL=$((FAIL+1)); fi
  if mail_contient "$MAIL_E7" "L'équipe Cocorico"; then echo "OK     e-mail J7 reçu, signé L'équipe Cocorico"; PASS=$((PASS+1)); else echo "ECHEC  e-mail J7 non reçu"; FAIL=$((FAIL+1)); fi
  check_eq "e-mail J3 reçu une seule fois" "1" "$(mails_pour "$MAIL_E3" "Besoin d'aide pour votre première saisie")"
fi

echo "== A. Console : essais inactifs, WhatsApp"
console_ferme "$UID_E3"
check "E3 : essai inactif, 4 jours, 0/6, WhatsApp 223 + 8 chiffres" "d['essaiInactif'] and d['joursDepuisInscription'] == 4 and d['etapesFaites'] == 0 and d['etapesTotal'] == 6 and d['proprietaireWhatsapp'] == '223$TEL_E3'"
console_ferme "$UID_E1"
check "E1 : +223 et espaces -> chiffres seuls" "d['proprietaireWhatsapp'].startswith('223') and len(d['proprietaireWhatsapp']) == 11 and d['proprietaireWhatsapp'].isdigit()"
console_ferme "$UID_E7"
check "E7 : 0033 -> 33 (étranger gardé)" "d['proprietaireWhatsapp'].startswith('336') and d['proprietaireWhatsapp'].isdigit()"
console_ferme "$UID_G"
check "G : 6/6, pas un essai inactif" "d['etapesFaites'] == 6 and not d['essaiInactif'] and d['aSaisi']"
console_ferme "$UID_EP"
check "EP (Projet sans saisie, sans site) : inactif, 1/6" "d['essaiInactif'] and d['etapesFaites'] == 1"
console_ferme "$UID_ED"
check "ferme de démonstration : jamais dans les essais inactifs" "not d['essaiInactif']"
TOKEN="$TOKEN_SA"; api GET "/admin/fermes/$UID_EP"
check "fiche : les 6 étapes avec leur état" "len(d['data']['etapes']) == 6 and [e['cle'] for e in d['data']['etapes'] if e['fait']] == ['PROJET']"

echo "== B. Parrainage"
inscrire P "$(tel8)"; FARM_P="$FARM_ID"; UID_P="$FARM_UID"; TOKEN_P="$TOKEN_ADMIN"; MAIL_P="$ADMIN_EMAIL"
api GET /abonnements/moi; ok_cree "abonnement du parrain"
api GET /croissance/parrainage
check "code de parrainage créé (6 caractères) avec un texte à partager" "code == 200 and len(d['data']['code']) == 6 and d['data']['code'] in d['data']['texteAPartager'] and d['data']['filleuls'] == 0"
CODE_P="$(jval "d['data']['code']")"
api GET /croissance/parrainage
check_eq "le code ne change pas" "$CODE_P" "$(jval "d['data']['code']")"
public GET "/parrainage/verifier?code=$CODE_P"
check "vérification publique : code connu" "code == 200 and d['data']['valide'] == True and len(d['data']) == 1"
public GET "/parrainage/verifier?code=ZZZZZZ9"
check "vérification publique : code inconnu" "code == 200 and d['data']['valide'] == False"
NB_AVANT="$(psql_run "select count(*) from utilisateurs")"
public POST /users/create "{\"fullName\":\"Inconnu$LETTRES\",\"email\":\"inconnu-$SUFFIXE@t.local\",\"telephone\":\"$(tel8)\",\"farmName\":\"X\",\"region\":\"B\",\"city\":\"B\",\"roles\":[\"ADMIN\"],\"codeParrainage\":\"ZZZZZZ9\"}"
check "inscription avec un code inconnu : refusée, message clair" "code == 400 and 'n\\'existe pas' in str(d)"
check_eq "rien n'est créé" "$NB_AVANT" "$(psql_run "select count(*) from utilisateurs")"
LOWER_CODE="$(echo "$CODE_P" | tr 'A-Z' 'a-z')"
inscrire F "$(tel8)" " $LOWER_CODE "; FARM_F="$FARM_ID"; UID_F="$FARM_UID"; TOKEN_F="$TOKEN_ADMIN"
check_eq "filleul rattaché au parrain (code en minuscules accepté)" "$FARM_P" "$(psql_run "select parrain_farm_id from parrainages where filleul_farm_id=$FARM_F")"
TOKEN="$TOKEN_F"; api GET /croissance/parrainage
check "le filleul voit son parrain, ne peut plus saisir de code" "d['data']['parrainNom'] is not None and not d['data']['peutSaisirCode']"
api POST /croissance/parrainage/code "{\"code\":\"$CODE_P\"}"
check "une ferme n'est parrainée qu'une fois" "code == 400 and 'déjà un parrain' in str(d)"
api GET /croissance/parrainage; CODE_F="$(jval "d['data']['code']")"
TOKEN="$TOKEN_P"; api POST /croissance/parrainage/code "{\"code\":\"$CODE_P\"}"
check "se parrainer soi-même : refusé" "code == 400 and 'elle-même' in str(d)"
api POST /croissance/parrainage/code '{"code":"QQQQQQ"}'
check "code inexistant : refusé" "code == 400 and 'existe pas' in str(d)"
# Une ferme déjà inscrite saisit un code (page Abonnement) : F2.
inscrire F2 "$(tel8)"; FARM_F2="$FARM_ID"; UID_F2="$FARM_UID"
api POST /croissance/parrainage/code "{\"code\":\"$CODE_P\"}"
check "ferme déjà inscrite : code saisi après coup" "code == 200 and d['data']['parrainNom'] is not None"
inscrire F3 "$(tel8)" "$CODE_P"; FARM_F3="$FARM_ID"; UID_F3="$FARM_UID"
FIN_P0="$(psql_run "select date_fin from abonnements where farm_id=$FARM_P")"
# Premier paiement du filleul F : déclaré puis validé par le SUPER_ADMIN.
TOKEN="$TOKEN_F"; api GET /abonnements/moi; ok_cree "abonnement F"
api POST /abonnements/declarer-paiement '{"periodicite":"MENSUEL","moyenPaiement":"Orange Money","reference":"PARRAIN-1"}'
ok_cree "déclaration du paiement de F"; PAI1="$(jval "d['data']['uniqueId']")"
check_eq "avant validation : pas de récompense" "$FIN_P0" "$(psql_run "select date_fin from abonnements where farm_id=$FARM_P")"
TOKEN="$TOKEN_SA"; api POST "/abonnements/$PAI1/valider"; ok_cree "validation du paiement de F"
ATTENDU="$(psql_run "select ('$FIN_P0'::date + 30)::text")"
check_eq "premier paiement validé : parrain +30 jours" "$ATTENDU" "$(psql_run "select date_fin from abonnements where farm_id=$FARM_P")"
check_eq "récompense enregistrée (30 jours, dates avant/après)" "30|$FIN_P0|$ATTENDU" "$(psql_run "select recompense_jours||'|'||parrain_date_fin_avant||'|'||parrain_date_fin_apres from parrainages where filleul_farm_id=$FARM_F and recompense_le is not null")"
if [ -n "$MAIL_SINK_DIR" ]; then
  sleep 1
  if mail_contient "$MAIL_P" "1 mois offert"; then echo "OK     e-mail « 1 mois offert » au parrain"; PASS=$((PASS+1)); else echo "ECHEC  e-mail au parrain non reçu"; FAIL=$((FAIL+1)); fi
fi
# Deuxième paiement de F : rien de plus.
psql_run "UPDATE abonnements SET date_fin = current_date + 3 WHERE farm_id=$FARM_F" >/dev/null
TOKEN="$TOKEN_F"; api POST /abonnements/declarer-paiement '{"periodicite":"MENSUEL","moyenPaiement":"Orange Money","reference":"PARRAIN-2"}'
ok_cree "deuxième déclaration de F"; PAI2="$(jval "d['data']['uniqueId']")"
TOKEN="$TOKEN_SA"; api POST "/abonnements/$PAI2/valider"; ok_cree "validation du deuxième paiement"
check_eq "deuxième paiement : pas de deuxième mois" "$ATTENDU" "$(psql_run "select date_fin from abonnements where farm_id=$FARM_P")"
check_eq "une seule récompense pour F" "1" "$(psql_run "select count(*) from parrainages where filleul_farm_id=$FARM_F and recompense_le is not null")"
# Console : activation SANS montant (F3) -> rien ; AVEC montant (F2) -> +30 jours.
api POST "/admin/fermes/$UID_F3/activer" '{"periodicite":"MENSUEL","mois":1}'; ok_cree "activation de F3 sans paiement"
check_eq "activation sans montant : pas de récompense" "$ATTENDU" "$(psql_run "select date_fin from abonnements where farm_id=$FARM_P")"
api POST "/admin/fermes/$UID_F2/activer" '{"periodicite":"MENSUEL","mois":1,"montant":5000,"moyenPaiement":"Espèces"}'; ok_cree "activation de F2 avec paiement"
ATTENDU2="$(psql_run "select ('$ATTENDU'::date + 30)::text")"
check_eq "activation avec montant : +30 jours au parrain" "$ATTENDU2" "$(psql_run "select date_fin from abonnements where farm_id=$FARM_P")"
api POST "/admin/fermes/$UID_F2/activer" '{"periodicite":"MENSUEL","mois":1,"montant":5000,"moyenPaiement":"Espèces"}'; ok_cree "deuxième activation payée de F2"
check_eq "deuxième activation payée : rien de plus" "$ATTENDU2" "$(psql_run "select date_fin from abonnements where farm_id=$FARM_P")"
TOKEN="$TOKEN_P"; api GET /croissance/parrainage
check "page Abonnement du parrain : 3 filleuls, 2 mois gagnés" "d['data']['filleuls'] == 3 and d['data']['moisGagnes'] == 2"
TOKEN="$TOKEN_SA"; api GET "/admin/fermes/$UID_P"
check "console, fiche du parrain : filleuls et récompenses" "len(d['data']['parrainage']['filleuls']) == 3 and len([x for x in d['data']['parrainage']['filleuls'] if x['recompenseLe']]) == 2 and d['data']['parrainage']['code'] == '$CODE_P'"
check "console, journal : catégorie PARRAINAGE" "len([j for j in d['data']['journal'] if j['categorie'] == 'PARRAINAGE']) == 2"
console_ferme "$UID_P"
check "console, liste : 3 filleuls, 2 mois gagnés" "d['nbFilleuls'] == 3 and d['moisGagnes'] == 2 and d['codeParrainage'] == '$CODE_P'"
console_ferme "$UID_F"
check "console, liste : F est parrainée" "d['parrainee']"
TOKEN="$TOKEN_SA"; api GET /admin/parrainages
check "console : liste de tous les parrainages" "code == 200 and len([x for x in d['data'] if x['parrainUniqueId'] == '$UID_P']) == 3"
TOKEN="$TOKEN_P"; api GET /admin/parrainages
check "liste des parrainages réservée au SUPER_ADMIN" "code == 403"

echo "== C. Résumé de la semaine"
inscrire R "$(tel8)"; FARM_R="$FARM_ID"; UID_R="$FARM_UID"; TOKEN_R="$TOKEN_ADMIN"; MAIL_R="$ADMIN_EMAIL"
api GET /abonnements/moi; ok_cree "abonnement R"
psql_run "UPDATE farms SET devise='EUR' WHERE id=$FARM_R" >/dev/null
race
nouveau_projet "Ponte R" PONTE 1000 2026-09-01; PR="$PRJ"; BR="$BAT"
api POST /magasins/create "{\"nom\":\"Stock R\",\"type\":\"STOCKAGE\"}"; ok_cree "stock R"; STOCK="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Boutique R\",\"type\":\"VENTE\"}"; ok_cree "boutique R"; BOUT_R="$(jval "d['data']['uniqueId']")"
# Semaine d'avant (14-20/09) : 2 morts, 2 collectes de 900 œufs.
mort "$PR" "$BR" 2026-09-15 2
collecte "$PR" "$BR" 2026-09-15 900
collecte "$PR" "$BR" 2026-09-16 900
# Semaine résumée (21-27/09) : 5 + 3 morts, 800 + 780 œufs, 210 kg d'aliment sur 300 achetés.
mort "$PR" "$BR" 2026-09-22 5
mort "$PR" "$BR" 2026-09-24 3
collecte "$PR" "$BR" 2026-09-22 800
collecte "$PR" "$BR" 2026-09-23 780
achat_aliment "$PR" 300 60000 2026-09-20
conso "$PR" "$BR" 2026-09-22 100
conso "$PR" "$BR" 2026-09-25 110
api POST /magasin-transferts/create "{\"magasinUniqueId\":\"$BOUT_R\",\"projetUniqueId\":\"$PR\",\"magasinStockageUniqueId\":\"$STOCK\",\"type\":\"OEUFS\",\"quantite\":60,\"date\":\"2026-09-23\"}"
ok_cree "transfert vers la boutique"
api POST /clients/create "{\"nom\":\"Client R $SUFFIXE\",\"telephone\":\"8$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\"}"; ok_cree "client"; KR="$(jval "d['data']['uniqueId']")"
api POST /ventes-oeufs/create "{\"date\":\"2026-09-23\",\"magasinUniqueId\":\"$BOUT_R\",\"clientUniqueId\":\"$KR\",\"quantiteOeufs\":60,\"prixUnitaire\":100,\"montant\":6000}"
ok_cree "vente de 6 000 au client"; VR="$(jval "d['data']['uniqueId']")"
api POST /paiements-client/create "{\"clientUniqueId\":\"$KR\",\"montant\":2500,\"mode\":\"ESPECES\",\"date\":\"2026-09-24\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$VR\"}"
ok_cree "paiement de 2 500"
api POST /transactions/create "{\"type\":\"SORTIE\",\"date\":\"2026-09-25\",\"description\":\"Résumé $SUFFIXE\",\"montant\":4000,\"categorie\":\"Gardiennage\",\"rattachement\":\"FERME\"}"
ok_cree "dépense de 4 000"
# Ferme H (aucune activité cette semaine) ; ferme G (activité mais pas cette semaine-là).

resume() { TOKEN="$TOKEN_SA"; api POST "/admin/resume-semaine?semaine=2026-09-21&ferme=$1${2:+&envoyer=$2}"; }
resume "$UID_R"
check "simulation (par défaut) : un résumé pour R, non envoyé" "code == 200 and len(d['data']) == 1 and not d['data'][0]['envoye']"
# Calcul à la main : œufs 1580 (52,7 alvéoles) ; ponte 1580 / (993 + 993) = 79,6 % ;
# semaine d'avant 1800 / (998 + 998) = 90,2 % ; morts 8 contre 2 ; aliment 210 kg ;
# Vendu 6 000, Encaissé 2 500, dépenses 4 000, clients 3 500 (en euros : devise EUR).
check "œufs 1580, 52,7 alvéoles" "d['data'][0]['chiffres']['oeufs'] == 1580 and d['data'][0]['chiffres']['alveoles'] == 52.7"
check "taux de ponte 79,6 % contre 90,2 %" "d['data'][0]['chiffres']['tauxPonte'] == 79.6 and d['data'][0]['chiffres']['tauxPontePrecedent'] == 90.2"
check "mortalité 8 contre 2" "d['data'][0]['chiffres']['mortes'] == 8 and d['data'][0]['chiffres']['mortesPrecedent'] == 2"
check "aliment 210 kg" "d['data'][0]['chiffres']['alimentKg'] == 210"
check "Vendu 6 000, Encaissé 2 500, dépenses 4 000" "d['data'][0]['chiffres']['vendu'] == 6000 and d['data'][0]['chiffres']['encaisse'] == 2500 and d['data'][0]['chiffres']['depenses'] == 4000"
check "clients doivent 3 500" "d['data'][0]['chiffres']['clientsDoivent'] == 3500"
check "devise de la ferme (EUR) dans le texte" "d['data'][0]['chiffres']['devise'] == 'EUR' and '6 000,00 €' in d['data'][0]['message'] and '3 500,00 €' in d['data'][0]['message']"
check "3 points d'attention : mortalité, ponte, stock d'aliment" "len(d['data'][0]['pointsAttention']) == 3 and 'mortalité monte' in d['data'][0]['pointsAttention'][0] and 'ponte baisse' in d['data'][0]['pointsAttention'][1] and 'Stock d\\'aliment bas' in d['data'][0]['pointsAttention'][2]"
check "stock : 90 kg à 30 kg par jour = environ 3 jours" "'environ 3 jours' in d['data'][0]['pointsAttention'][2]"
check "texte : alvéoles, semaine, Paramètres, sans tiret long" "'52,7 alvéoles' in d['data'][0]['message'] and '21/09/2026' in d['data'][0]['message'] and 'Paramètres' in d['data'][0]['message'] and '\u2014' not in d['data'][0]['message'] and '\u2013' not in d['data'][0]['message']"
check "destinataire : l'ADMIN" "d['data'][0]['destinataires'] == ['$MAIL_R']"
check_eq "simulation : rien d'enregistré" "0" "$(psql_run "select count(*) from envois_automatiques where farm_id=$FARM_R")"
resume "$UID_H"
check "ferme sans activité cette semaine : pas de résumé" "code == 200 and d['data'] == []"
TOKEN="$TOKEN_R"; api GET /croissance/resume-hebdo
check "réglage par défaut : oui" "code == 200 and d['data']['actif'] == True"
api PUT /croissance/resume-hebdo '{"actif":false}'
check "réglage coupé par l'ADMIN" "code == 200 and d['data']['actif'] == False"
check_eq "colonne resume_hebdo = false" "f" "$(psql_run "select resume_hebdo from farms where id=$FARM_R")"
resume "$UID_R"
check "résumé coupé : rien" "d['data'] == []"
TOKEN="$TOKEN_R"; api PUT /croissance/resume-hebdo '{"actif":true}'
check_eq "réglage remis : colonne à null (= oui)" "" "$(psql_run "select resume_hebdo from farms where id=$FARM_R")"
TOKEN="$(login "$COMPTA_G" "$PWD_TEST" web)"; api PUT /croissance/resume-hebdo '{"actif":false}'
check "COMPTABLE : ne peut pas changer le réglage" "code == 400"
psql_run "UPDATE farms SET exclure_statistiques = true WHERE id=$FARM_R" >/dev/null
resume "$UID_R"
check "ferme hors statistiques : rien" "d['data'] == []"
psql_run "UPDATE farms SET exclure_statistiques = null WHERE id=$FARM_R" >/dev/null
TOKEN="$TOKEN_SA"; api POST "/admin/resume-semaine?semaine=2026-09-22"
check "semaine qui ne commence pas un lundi : refusée" "code == 400"
api POST "/admin/resume-semaine?semaine=$(date -d 'monday' +%F)"
check "semaine pas finie : refusée" "code == 400"
TOKEN="$TOKEN_R"; api POST "/admin/resume-semaine"
check "déclenchement manuel réservé au SUPER_ADMIN" "code == 403"
resume "$UID_R" true
check "envoi : résumé envoyé à 1 adresse" "len(d['data']) == 1 and d['data'][0]['envoye'] and d['data'][0]['emailsEnvoyes'] == 1"
check_eq "ligne enregistrée pour la semaine" "RESUME_SEMAINE|2026-09-21" "$(psql_run "select type||'|'||cle from envois_automatiques where farm_id=$FARM_R")"
resume "$UID_R" true
check "deuxième envoi la même semaine : rien" "d['data'] == []"
if [ -n "$MAIL_SINK_DIR" ]; then
  sleep 1
  check_eq "e-mail du résumé reçu une fois" "1" "$(mails_pour "$MAIL_R" "Votre semaine à la ferme")"
  if mail_contient "$MAIL_R" "Points d'attention"; then echo "OK     e-mail : points d'attention"; PASS=$((PASS+1)); else echo "ECHEC  e-mail sans points d'attention"; FAIL=$((FAIL+1)); fi
fi

echo "== D. Nouvelles alertes"
inscrire D "$(tel8)"; FARM_D="$FARM_ID"; TOKEN_D="$TOKEN_ADMIN"
race
api POST /magasins/create "{\"nom\":\"Stock D\",\"type\":\"STOCKAGE\"}"; ok_cree "stock D"; STOCK="$(jval "d['data']['uniqueId']")"
nouveau_projet "Ponte D" PONTE 1000 "$(jour -30)"; PD="$PRJ"; BD="$BAT"
nouveau_projet "Temoin D" REFORME 1000 "$(jour -30)"; PE="$PRJ"; BE="$BAT"
nouveau_projet "Mixte D" MIXTE 1000 "$(jour -30)"; PM="$PRJ"; BM="$BAT"
# Ponte D : 7 jours d'avant à 900 œufs, 3 derniers jours à 750 (90 % -> 75 %).
for i in 9 8 7 6 5 4 3; do collecte "$PD" "$BD" "$(jour -$i)" 900; done
for i in 2 1 0; do collecte "$PD" "$BD" "$(jour -$i)" 750; done
# Mixte D : 900 puis 860 (baisse de 4 points : pas d'alerte).
for i in 9 8 7 6 5 4 3; do collecte "$PM" "$BM" "$(jour -$i)" 900; done
for i in 2 1 0; do collecte "$PM" "$BM" "$(jour -$i)" 860; done
# Mortalité : Ponte D 1 mort il y a 3 et 5 jours, 4 aujourd'hui (> 3 x 0,29) -> alerte.
mort "$PD" "$BD" "$(jour -3)" 1
mort "$PD" "$BD" "$(jour -5)" 1
mort "$PD" "$BD" "$AUJ" 4
# Témoin : 2 morts par jour depuis 7 jours, 4 aujourd'hui (< 3 x 2 et 0,41 % < 0,5 %) -> rien.
for i in 7 6 5 4 3 2 1; do mort "$PE" "$BE" "$(jour -$i)" 2; done
mort "$PE" "$BE" "$AUJ" 4
# Aliment : Ponte D 1000 kg, 40 kg par jour sur 7 jours = 720 restants (18 jours) -> rien.
achat_aliment "$PD" 1000 100000 "$(jour -10)"
for i in 7 6 5 4 3 2 1; do conso "$PD" "$BD" "$(jour -$i)" 40; done
# Témoin : 400 kg, 40 kg par jour -> 120 restants (3 jours) -> alerte.
achat_aliment "$PE" 400 40000 "$(jour -10)"
for i in 7 6 5 4 3 2 1; do conso "$PE" "$BE" "$(jour -$i)" 40; done

TOKEN="$TOKEN_D"; api GET /notifications/list
cp "$TMP/body" "$TMP/notifs"
cles() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(" ".join(sorted(n["key"] for n in d["data"])))' "$TMP/notifs"; }
check "mortalité anormale (Ponte D) : clé du jour, type MORTALITE" "[n for n in d['data'] if n['key'] == 'mortalite-anormale-$PD-$AUJ' and n['type'] == 'MORTALITE' and n['level'] == 'WARNING' and '4 morts' in n['message']]"
check "mortalité sous les seuils (Témoin) : pas d'alerte" "not [n for n in d['data'] if n['key'].startswith('mortalite-anormale-$PE')]"
check "stock d'aliment bas (Témoin, 3 jours) : alerte" "[n for n in d['data'] if n['key'] == 'stock-aliment-bas-$PE-$AUJ' and n['type'] == 'STOCK' and 'environ 3 jours' in n['message']]"
check "stock de 18 jours (Ponte D) : pas d'alerte" "not [n for n in d['data'] if n['key'].startswith('stock-aliment-bas-$PD')]"
check "ponte en baisse (Ponte D, 90 % -> 75 %) : alerte" "[n for n in d['data'] if n['key'] == 'ponte-baisse-$PD-$AUJ' and n['type'] == 'PONTE' and n['level'] == 'WARNING']"
check "baisse de 4 points (Mixte D) : pas d'alerte" "not [n for n in d['data'] if n['key'].startswith('ponte-baisse-$PM')]"
check "Projet REFORME : jamais d'alerte de ponte" "not [n for n in d['data'] if n['key'].startswith('ponte-baisse-$PE')]"
check "le téléphone lit ces alertes : seulement les champs de NotificationResponse" "all(set(n.keys()) <= {'key','type','level','message','projetCode','projetUniqueId','actionPath','read'} and n['projetCode'] and n['actionPath'].startswith('/projets/') for n in d['data'] if n['key'].endswith('$AUJ'))"
# Stock de Ponte D : 600 kg consommés aujourd'hui -> 120 restants (moins de 5 jours) -> alerte.
conso "$PD" "$BD" "$AUJ" 600
TOKEN="$TOKEN_D"; api GET /notifications/list
check "stock passé sous 5 jours (Ponte D) : alerte" "[n for n in d['data'] if n['key'] == 'stock-aliment-bas-$PD-$AUJ']"
api PUT "/notifications/ponte-baisse-$PD-$AUJ/read"
api GET /notifications/list
check "alerte lue : reste lue le même jour" "[n for n in d['data'] if n['key'] == 'ponte-baisse-$PD-$AUJ' and n['read']]"
api GET "/notifications/projet/$PD"
check "alertes du projet (écran d'accueil mobile) : les 3 nouvelles" "len([n for n in d['data'] if n['key'] in ('mortalite-anormale-$PD-$AUJ','stock-aliment-bas-$PD-$AUJ','ponte-baisse-$PD-$AUJ')]) == 3"
# Projet clôturé : plus d'alerte.
psql_run "UPDATE projets SET date_cloture = current_date, archive = true WHERE unique_id='$PD'" >/dev/null
api GET "/notifications/projet/$PD"
check "Projet clôturé : plus d'alerte d'élevage" "not [n for n in (d.get('data') or []) if n['key'].endswith('$AUJ')]"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
