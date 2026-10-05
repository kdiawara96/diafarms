#!/usr/bin/env bash
# Console d'administration SUPER_ADMIN (AdminConsoleService, /admin/**), 2026-10-05.
#
# Deux fermes NEUVES à chaque passage :
#   X : ferme sur laquelle on agit (activer, suspendre, réactiver, notes), avec un projet
#       en cours de 500 sujets dont 20 morts et un projet clôturé (ignoré) ;
#   Y : en essai, pour « prolonger l'essai ».
# Les chiffres globaux (tableau de bord, finances) sont vérifiés en ÉCART avant/après :
# la base de test contient déjà d'autres fermes.
#
# Vérifie :
#   - tous les nouveaux endpoints refusés (403) à un ADMIN de ferme ;
#   - aucune donnée confidentielle (note, motif, montant, moyen) dans logs.action ;
#   - /logs (liste, recherche, by-class, by-action) limité à la ferme de l'appelant, jamais
#     les actions de la console ; /logs/delete : SUPER_ADMIN, ou ADMIN sur sa ferme ;
#   - durées : 12 mois = 365 jours ; date de fin avant la fin actuelle refusée ; montant
#     sans moyen refusé sans rien modifier ;
#   - ferme suspendue : déclaration de paiement refusée (WhatsApp), validation sans levée ;
#   - 3 prolongations simultanées : aucune perdue (verrou) ;
#   - hors statistiques : ferme exclue de tous les chiffres, toujours listée, réintégrable ;
#   - e-mails à la ferme (si MAIL_SINK_DIR) : essai prolongé, suspension, réactivation, activation ;
#   - connexion mobile refusée (403) pour une ferme expirée après la grâce ou suspendue,
#     acceptée pendant la grâce ; web, refresh et token déjà émis toujours acceptés ;
#     nouveau QR et scan serveur refusés ; tout revient au renouvellement ;
#   - comptes : aucune action sur un compte d'une autre ferme (404) ; même ferme, seul
#     l'ADMIN agit (403 sinon) ; soi-même pour la page Profil ; jamais le rôle SUPER_ADMIN ;
#   - option A (si AES_SECRET_KEY ou ENV_FILE) : téléphone d'une ferme bloquée, avec le vrai
#     jeton de QR : lectures refusées (403 + message), alertes = le message, saisies
#     acceptées et enregistrées ; grâce et web inchangés ; suspension ; réactivation ;
#   - tableau de bord : +2 fermes, +2 nouvelles ce mois, +480 sujets vivants, +1 essai ;
#   - liste des fermes : propriétaire, utilisateurs, projets en cours, sujets vivants,
#     dernière activité, statut ;
#   - activer / prolonger avec paiement hors application : ACTIF, date de fin, paiement
#     VALIDE horsApplication compté dans le revenu du mois et dans les finances ;
#   - date de fin explicite ; erreurs (durée absente, date passée, montant négatif) ;
#   - suspendre : /abonnements/moi de la ferme = EXPIRE + suspendu + motif, API toujours
#     ouverte, aucun rappel prévu pour X, statut SUSPENDU dans la liste ;
#   - réactiver : de nouveau ACTIF, plus de motif ;
#   - prolonger l'essai de Y ; refusé sur une ferme payante ;
#   - notes internes (fiche), invisibles côté ferme ;
#   - journal : activation, suspension, réactivation, essai, note, filtrés par ferme/catégorie ;
#   - anciens champs de /abonnements/moi toujours présents.
#
# Pré-requis : Postgres + backend démarrés, super-admin seedé (superadmin / change-me).
# Variables : BASE, PGHOST, PGPORT, PGUSER, PGDATABASE, SUPERADMIN_ID, SUPERADMIN_PWD.
# Sortie : une ligne OK/ECHEC par assertion ; code 0 si tout est OK, 1 sinon.
set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
SUPERADMIN_ID="${SUPERADMIN_ID:-superadmin}"
SUPERADMIN_PWD="${SUPERADMIN_PWD:-change-me}"
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
    ok = bool(eval(expr, {"d": d, "code": code, "json": json}))
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
AUJ="$(date +%F)"
MOIS="$(date +%Y-%m)"

TOKEN_SA="$(login_mobile "$SUPERADMIN_ID" "$SUPERADMIN_PWD")"
[ -n "$TOKEN_SA" ] || { echo "ECHEC  connexion super-admin"; exit 1; }

# Chiffres AVANT (écarts vérifiés ensuite).
TOKEN="$TOKEN_SA"
api GET /admin/tableau-de-bord; ok_cree "tableau de bord initial"
cp "$TMP/body" "$TMP/tdb0"
api GET "/admin/finances"; ok_cree "finances initiales"
cp "$TMP/body" "$TMP/fin0"

nouvelle_ferme() { # $1=lettre -> FARM_ID, ADMIN_EMAIL, TOKEN_ADMIN, FARM_UID, ADMIN_TEL
  TOKEN="$TOKEN_SA"
  ADMIN_EMAIL="admin-con$1-$SUFFIXE@t.local"
  ADMIN_TEL="7$(python3 -c 'import random; print(random.randint(1000000, 9999999))')"
  api POST /users/create "{\"fullName\":\"Con$1$LETTRES\",\"email\":\"$ADMIN_EMAIL\",\"telephone\":\"$ADMIN_TEL\",\"farmName\":\"FermeConsole$1$SUFFIXE\",\"roles\":[\"COMPTABLE\"]}"
  ok_cree "création de la ferme $1 et de son ADMIN"
  psql_run "UPDATE roles_users SET id_roles=(select id from roles where role='ADMIN') WHERE id_utilisateurs=(select id from utilisateurs where email='$ADMIN_EMAIL')" >/dev/null
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$ADMIN_EMAIL'" >/dev/null
  TOKEN_ADMIN="$(login_mobile "$ADMIN_EMAIL" "$PWD_TEST")"
  [ -n "$TOKEN_ADMIN" ] || { echo "ECHEC  connexion ADMIN de la ferme $1"; exit 1; }
  FARM_ID="$(psql_run "select farm_id from utilisateurs where email='$ADMIN_EMAIL'")"
  FARM_UID="$(psql_run "select unique_id from farms where id=$FARM_ID")"
  TOKEN="$TOKEN_ADMIN"
  api GET /abonnements/moi   # crée l'abonnement d'essai
  ok_cree "abonnement de la ferme $1"
}

nouvelle_ferme X; FARM_X="$FARM_ID"; UID_X="$FARM_UID"; TOKEN_X="$TOKEN_ADMIN"; EMAIL_X="$ADMIN_EMAIL"; TEL_X="$ADMIN_TEL"
nouvelle_ferme Y; FARM_Y="$FARM_ID"; UID_Y="$FARM_UID"; TOKEN_Y="$TOKEN_ADMIN"
COMPTA_Y_EMAIL="compta-conY-$SUFFIXE@t.local"
TOKEN="$TOKEN_Y"
api PUT /farm-settings '{"comptableMobileEnabled":true,"comptableWebEnabled":true}'
api POST /users/create-pro-or-finance "{\"fullName\":\"Comptable $LETTRES\",\"email\":\"$COMPTA_Y_EMAIL\",\"telephone\":\"6$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\",\"roles\":[\"COMPTABLE\"]}"
ok_cree "COMPTABLE de la ferme Y"
psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$COMPTA_Y_EMAIL'" >/dev/null
TOKEN_COMPTA_Y="$(login_mobile "$COMPTA_Y_EMAIL" "$PWD_TEST")"
[ -n "$TOKEN_COMPTA_Y" ] || { echo "ECHEC  connexion COMPTABLE de Y"; exit 1; }

# Projets de X en SQL : un en cours (500 sujets, 20 morts, 1 mortalité supprimée ignorée)
# et un clôturé (archive=true, ignoré).
RACE="$(psql_run "select id from races order by id limit 1")"
if [ -z "$RACE" ]; then
  RACE="$(psql_run "insert into races (unique_id, nom, origine, type) values ('race-con-$SUFFIXE','Race console','Locale','PONDEUSE') returning id" | head -1)"
fi
P1="$(psql_run "insert into projets (unique_id, code, titre, objectif, race_id, farm_id, nb_sujets, date_debut, removed, archive, created_at) values ('pc1-$SUFFIXE','C1','Projet console 1','PONTE',$RACE,$FARM_X,500,current_date - 30,false,false,now()) returning id" | head -1)"
psql_run "insert into projets (unique_id, code, titre, objectif, race_id, farm_id, nb_sujets, date_debut, removed, archive, created_at) values ('pc2-$SUFFIXE','C2','Projet console 2','PONTE',$RACE,$FARM_X,300,current_date - 300,false,true,now())" >/dev/null
psql_run "insert into mortalites (unique_id, date, nombre_morts, projet_id, farm_id, removed, archive, created_at) values ('mc1-$SUFFIXE', current_date, 20, $P1, $FARM_X, false, false, now()), ('mc2-$SUFFIXE', current_date, 7, $P1, $FARM_X, true, false, now())" >/dev/null

echo "--- refus pour un compte qui n'est pas SUPER_ADMIN"
TOKEN="$TOKEN_X"
for ep in "GET /admin/tableau-de-bord" "GET /admin/fermes" "GET /admin/fermes/$UID_X" "GET /admin/finances" \
          "GET /admin/paiements" "GET /admin/journal" "POST /admin/fermes/$UID_X/reactiver"; do
  api ${ep% *} "${ep#* }"
  check "ADMIN de ferme refusé (403) : $ep" "code == 403 and d.get('data') is None"
done
api POST "/admin/fermes/$UID_X/activer" '{"periodicite":"ANNUEL","mois":12,"montant":1,"moyenPaiement":"x"}'
check "ADMIN de ferme refusé (403) : activer" "code == 403"
api POST "/admin/fermes/$UID_X/suspendre" '{"motif":"x"}'
check "ADMIN de ferme refusé (403) : suspendre" "code == 403"
api POST "/admin/fermes/$UID_X/prolonger-essai" '{"jours":10}'
check "ADMIN de ferme refusé (403) : prolonger l'essai" "code == 403"
api POST "/admin/fermes/$UID_X/notes" '{"contenu":"x"}'
check "ADMIN de ferme refusé (403) : note" "code == 403"
check_eq "rien n'a été modifié par l'ADMIN de ferme" "false|" "$(psql_run "select coalesce(suspendu,false)::text || '|' || coalesce(periodicite,'') from abonnements where farm_id=$FARM_X")"
check_eq "aucune note créée par l'ADMIN de ferme" "0" "$(psql_run "select count(*) from notes_admin_ferme where farm_id=$FARM_X")"

echo "--- vue d'ensemble"
TOKEN="$TOKEN_SA"
api GET /admin/tableau-de-bord
cp "$TMP/body" "$TMP/tdb1"
ecart() { python3 -c 'import json,sys; a=json.load(open(sys.argv[1]))["data"]; b=json.load(open(sys.argv[2]))["data"]; print(round(b[sys.argv[3]]-a[sys.argv[3]],2))' "$1" "$2" "$3"; }
check_eq "tableau de bord : +2 fermes" "2" "$(ecart "$TMP/tdb0" "$TMP/tdb1" totalFermes)"
check_eq "tableau de bord : +2 en essai" "2" "$(ecart "$TMP/tdb0" "$TMP/tdb1" enEssai)"
check_eq "tableau de bord : +2 nouvelles fermes ce mois" "2" "$(ecart "$TMP/tdb0" "$TMP/tdb1" nouvellesCeMois)"
check_eq "tableau de bord : +480 sujets vivants (500 - 20, projet clôturé ignoré)" "480" "$(ecart "$TMP/tdb0" "$TMP/tdb1" totalSujetsVivants)"
check_eq "tableau de bord : +2 fermes actives sur 7 jours" "2" "$(ecart "$TMP/tdb0" "$TMP/tdb1" actives7Jours)"
check "tableau de bord : 12 mois de revenus et de nouvelles fermes, mois courant en dernier" \
  "len(d['data']['revenusParMois']) == 12 and len(d['data']['nouvellesFermesParMois']) == 12 and d['data']['revenusParMois'][-1]['mois'] == '$MOIS'"
check "tableau de bord : nouvelles fermes du mois >= 2 dans le graphique" "d['data']['nouvellesFermesParMois'][-1]['nombre'] >= 2"

echo "--- liste des fermes"
api GET /admin/fermes
check "liste : ferme X avec propriétaire, téléphone, email" \
  "[(f['proprietaireEmail'], f['proprietaireTelephone'], f['nom']) for f in d['data'] if f['farmUniqueId']=='$UID_X'] == [('$EMAIL_X', '$TEL_X', 'FermeConsoleX$SUFFIXE')]"
check "liste : X en essai, 1 utilisateur, 1 projet en cours, 480 sujets, inscrite aujourd'hui, activité récente" \
  "[(f['statut'], f['nbUtilisateurs'], f['projetsEnCours'], f['sujetsVivants'], f['inscriteLe'], f['derniereActivite'][:10], f['totalPaye']) for f in d['data'] if f['farmUniqueId']=='$UID_X'] == [('ESSAI', 1, 1, 480, '$AUJ', '$AUJ', 0.0)]"

echo "--- activer / prolonger"
api POST "/admin/fermes/$UID_X/activer" '{"periodicite":"ANNUEL"}'
check "activer sans durée ni date : refusé" "code == 400"
api POST "/admin/fermes/$UID_X/activer" '{"periodicite":"ANNUEL","dateFin":"2020-01-01"}'
check "activer avec date passée : refusé" "code == 400"
api POST "/admin/fermes/$UID_X/activer" '{"periodicite":"ANNUEL","mois":12,"montant":-5,"moyenPaiement":"Espèces"}'
check "activer avec montant négatif : refusé" "code == 400"
api POST "/admin/fermes/$UID_X/activer" '{"periodicite":"BIMENSUEL","mois":1}'
check "activer avec formule inconnue : refusé" "code == 400"
check_eq "aucun paiement créé par les refus" "0" "$(psql_run "select count(*) from paiements_abonnement p join abonnements a on a.id=p.abonnement_id where a.farm_id=$FARM_X")"

FIN_ESSAI="$(psql_run "select date_fin from abonnements where farm_id=$FARM_X")"
# 12 mois = 365 jours (même convention que la validation d'un paiement : 1 mois = 30 jours).
ATTENDU="$(python3 -c "import datetime; print(datetime.date.fromisoformat('$FIN_ESSAI') + datetime.timedelta(days=365))")"
# Vérifications AVANT toute modification : paiement sans moyen refusé, rien n'a changé.
api POST "/admin/fermes/$UID_X/activer" '{"periodicite":"ANNUEL","mois":12,"montant":5000}'
check "activer avec montant mais sans moyen de paiement : refusé" "code == 400"
check_eq "refus sans moyen : abonnement inchangé (toujours en essai, même fin)" "$FIN_ESSAI|" \
  "$(psql_run "select date_fin || '|' || coalesce(periodicite,'') from abonnements where farm_id=$FARM_X")"
api POST "/admin/fermes/$UID_X/activer" '{"periodicite":"ANNUEL","mois":12,"montant":123456,"moyenPaiement":"Espèces","reference":"REC-ADMIN-1"}'
check "activer 12 mois + paiement hors application : ACTIF annuel" \
  "code == 200 and d['data']['ferme']['statut'] == 'ACTIF' and d['data']['ferme']['periodicite'] == 'ANNUEL' and d['data']['ferme']['dateFin'] == '$ATTENDU'"
check "activer : paiement VALIDE hors application dans la fiche" \
  "[(p['montant'], p['statut'], p['horsApplication'], p['reference']) for p in d['data']['paiements']] == [(123456.0, 'VALIDE', True, 'REC-ADMIN-1')]"
check "activer : total payé de la ferme" "d['data']['ferme']['totalPaye'] == 123456.0"

api GET /admin/tableau-de-bord
cp "$TMP/body" "$TMP/tdb2"
check_eq "revenu du mois : +123456" "123456.0" "$(ecart "$TMP/tdb1" "$TMP/tdb2" revenuCeMois)"
check_eq "revenu de l'année : +123456" "123456.0" "$(ecart "$TMP/tdb1" "$TMP/tdb2" revenuCetteAnnee)"
check_eq "revenu mensuel estimé : +10288 (annuel / 12)" "10288.0" "$(ecart "$TMP/tdb1" "$TMP/tdb2" revenuMensuelEstime)"
check_eq "fermes actives payantes : +1" "1" "$(ecart "$TMP/tdb1" "$TMP/tdb2" activesPayantes)"

FIN_ACTUELLE="$(psql_run "select date_fin from abonnements where farm_id=$FARM_X")"
api POST "/admin/fermes/$UID_X/activer" "{\"periodicite\":\"MENSUEL\",\"dateFin\":\"$(date -d '+10 days' +%F)\"}"
check "activer avec une date de fin avant la fin actuelle : refusé (pas de raccourcissement)" "code == 400 and 'raccourci' in str(d)"
check_eq "refus du raccourcissement : fin inchangée" "$FIN_ACTUELLE" "$(psql_run "select date_fin from abonnements where farm_id=$FARM_X")"
api POST "/admin/fermes/$UID_X/activer" "{\"periodicite\":\"MENSUEL\",\"dateFin\":\"2030-06-30\"}"
check "activer avec date de fin explicite, sans paiement" \
  "code == 200 and d['data']['ferme']['dateFin'] == '2030-06-30' and d['data']['ferme']['periodicite'] == 'MENSUEL' and len(d['data']['paiements']) == 1"
TOKEN="$TOKEN_X"; api GET /abonnements/historique
check "la ferme voit le paiement hors application dans son historique" "len(d['data']) == 1 and d['data'][0]['montant'] == 123456.0"

echo "--- finances"
TOKEN="$TOKEN_SA"
api GET "/admin/finances"
cp "$TMP/body" "$TMP/fin1"
python3 - "$TMP/fin0" "$TMP/fin1" "$MOIS" > "$TMP/finchk" <<'PY'
import json, sys
a = json.load(open(sys.argv[1]))["data"]; b = json.load(open(sys.argv[2]))["data"]; mois = sys.argv[3]
m = lambda x: [v for v in x["parMois"] if v["mois"] == mois][0]
cle = lambda x, l, k: next((v for v in x[l] if (v["cle"] or "").lower() == k.lower()), {"montant": 0, "nombre": 0})
print(round(m(b)["montant"] - m(a)["montant"], 2), m(b)["nombre"] - m(a)["nombre"],
      round(b["totalAnnee"] - a["totalAnnee"], 2),
      round(cle(b, "parPeriodicite", "ANNUEL")["montant"] - cle(a, "parPeriodicite", "ANNUEL")["montant"], 2),
      round(cle(b, "parMoyen", "Espèces")["montant"] - cle(a, "parMoyen", "Espèces")["montant"], 2),
      b["essaisConvertis"] - a["essaisConvertis"], len(b["parMois"]))
PY
check_eq "finances : mois courant +123456 (1 paiement), année +123456, annuel +123456, espèces +123456, +1 conversion, 12 mois" \
  "123456.0 1 123456.0 123456.0 123456.0 1 12" "$(cat "$TMP/finchk")"
api GET "/admin/paiements?ferme=$UID_X&statut=VALIDE&du=$AUJ&au=$AUJ"
check "paiements filtrés (ferme, statut, dates) : le paiement de X" \
  "code == 200 and [(p['montant'], p['farmUniqueId'], p['horsApplication']) for p in d['data']] == [(123456.0, '$UID_X', True)]"
api GET "/admin/paiements?ferme=$UID_X&statut=EN_ATTENTE"
check "paiements filtrés EN_ATTENTE : aucun pour X" "code == 200 and d['data'] == []"
api GET "/admin/paiements?du=2001-01-01&au=2001-01-31"
check "paiements filtrés sur une période vide" "code == 200 and d['data'] == []"
api GET "/admin/paiements?statut=PAYE"
check "paiements : statut inconnu refusé" "code == 400"
api GET "/admin/finances?annee=2001"
check "finances d'une année sans paiement : 0" "code == 200 and d['data']['totalAnnee'] == 0 and d['data']['annee'] == 2001"

echo "--- suspendre"
# X déclare un paiement AVANT la suspension (validé pendant la suspension plus bas).
TOKEN="$TOKEN_X"
api POST /abonnements/declarer-paiement '{"periodicite":"MENSUEL","moyenPaiement":"Orange Money","reference":"AVANT-SUSP"}'
ok_cree "déclaration de paiement de X avant suspension"
PAIEMENT_X="$(jval "d['data']['uniqueId']")"
TOKEN="$TOKEN_SA"
api POST "/admin/fermes/$UID_X/suspendre" '{"motif":"   "}'
check "suspendre sans motif : refusé" "code == 400"
api POST "/admin/fermes/$UID_X/suspendre" '{"motif":"Paiement contesté"}'
check "suspendre : statut SUSPENDU, motif" \
  "code == 200 and d['data']['ferme']['statut'] == 'SUSPENDU' and d['data']['ferme']['suspendu'] and d['data']['ferme']['motifSuspension'] == 'Paiement contesté'"
api POST "/admin/fermes/$UID_X/suspendre" '{"motif":"encore"}'
check "suspendre une ferme déjà suspendue : refusé" "code == 400"
TOKEN="$TOKEN_X"; api GET /abonnements/moi
check "ferme suspendue : statutEffectif EXPIRE (web bloqué), suspendu + motif visibles" \
  "d['data']['statutEffectif'] == 'EXPIRE' and d['data']['suspendu'] is True and d['data']['motifSuspension'] == 'Paiement contesté'"
check "ferme suspendue : anciens champs de /abonnements/moi présents" \
  "all(k in d['data'] for k in ['uniqueId','farmUniqueId','farmNom','statutEffectif','enGrace','dateFin','joursRestants','periodicite','paiementEnAttente','estEssai','delaiGraceJours','dernierJourAcces','joursGraceRestants'])"
api GET /notifications/list
check "ferme suspendue : l'API répond toujours (pas de blocage serveur)" "code == 200"
api POST /abonnements/declarer-paiement '{"periodicite":"MENSUEL","moyenPaiement":"Orange Money"}'
check "ferme suspendue : déclarer un paiement refusé, message WhatsApp" "code == 400 and '+223 83 91 86 99' in str(d)"
TOKEN="$TOKEN_SA"; api POST "/abonnements/$PAIEMENT_X/valider"
check "valider le paiement d'une ferme suspendue : validé" "code == 200 and d['data']['statut'] == 'VALIDE'"
TOKEN="$TOKEN_X"; api GET /abonnements/moi
check "la validation ne lève pas la suspension" "d['data']['statutEffectif'] == 'EXPIRE' and d['data']['suspendu'] is True"
# Aucun rappel pour une ferme suspendue, même dans la fenêtre J-7.
psql_run "update abonnements set date_fin = current_date + 5 where farm_id=$FARM_X" >/dev/null
TOKEN="$TOKEN_SA"; api POST "/abonnements/rappels?executer=false"
check "ferme suspendue à J-5 : aucun rappel prévu" "code == 200 and not [r for r in d['data'] if r['farmUniqueId'] == '$UID_X']"
api GET /admin/tableau-de-bord
check "tableau de bord : au moins 1 ferme suspendue" "d['data']['suspendues'] >= 1"

echo "--- réactiver"
api POST "/admin/fermes/$UID_X/reactiver"
check "réactiver : de nouveau ACTIF, sans motif" \
  "code == 200 and d['data']['ferme']['statut'] == 'ACTIF' and not d['data']['ferme']['suspendu'] and d['data']['ferme']['motifSuspension'] is None"
api POST "/admin/fermes/$UID_X/reactiver"
check "réactiver une ferme non suspendue : refusé" "code == 400"
TOKEN="$TOKEN_X"; api GET /abonnements/moi
check "ferme réactivée : statutEffectif ACTIF, suspendu false" "d['data']['statutEffectif'] == 'ACTIF' and d['data']['suspendu'] is False"
TOKEN="$TOKEN_SA"; api POST "/abonnements/rappels?executer=false"
check "ferme réactivée à J-5 : rappel J7 prévu de nouveau" "[r['type'] for r in d['data'] if r['farmUniqueId'] == '$UID_X'] == ['J7']"

echo "--- prolonger l'essai"
FIN_Y="$(psql_run "select date_fin from abonnements where farm_id=$FARM_Y")"
api POST "/admin/fermes/$UID_Y/prolonger-essai" '{"jours":0}'
check "prolonger l'essai de 0 jour : refusé" "code == 400"
api POST "/admin/fermes/$UID_Y/prolonger-essai" '{"jours":10}'
check "prolonger l'essai de Y de 10 jours" \
  "code == 200 and d['data']['ferme']['statut'] == 'ESSAI' and d['data']['ferme']['dateFin'] == '$(python3 -c "import datetime; print(datetime.date.fromisoformat('$FIN_Y') + datetime.timedelta(days=10))")'"
api POST "/admin/fermes/$UID_X/prolonger-essai" '{"jours":10}'
check "prolonger l'essai d'une ferme payante : refusé" "code == 400"

echo "--- notes internes"
api POST "/admin/fermes/$UID_X/notes" '{"contenu":""}'
check "note vide : refusée" "code == 400"
api POST "/admin/fermes/$UID_X/notes" '{"contenu":"Appelé le propriétaire, paiement promis lundi."}'
check "note ajoutée avec auteur et date" \
  "code == 200 and [(n['contenu'], n['auteurNom'] is not None, n['creeLe'][:10]) for n in d['data']['notes']] == [('Appelé le propriétaire, paiement promis lundi.', True, '$AUJ')]"
api GET "/admin/fermes/$UID_X"
check "fiche (agrégats d'une seule ferme) : 480 sujets, 1 projet en cours, total payé 123456 + 1 mois" \
  "d['data']['ferme']['sujetsVivants'] == 480 and d['data']['ferme']['projetsEnCours'] == 1 and d['data']['ferme']['totalPaye'] > 123456"
check "fiche : utilisateurs avec rôles et dernière connexion, rappels envoyés (liste), notes" \
  "[(u['roles'], (u['derniereConnexion'] or '')[:10]) for u in d['data']['utilisateurs']] == [(['ADMIN'], '$AUJ')] and isinstance(d['data']['rappels'], list) and len(d['data']['notes']) == 1"
TOKEN="$TOKEN_X"; api GET /abonnements/moi
check "la note n'apparaît pas côté ferme" "'Appel' not in str(d)"

echo "--- journal"
TOKEN="$TOKEN_SA"
api GET "/admin/journal?ferme=$UID_X&size=50"
check "journal de X : activations, validation, suspension, réactivation, note" \
  "sorted(set(e['categorie'] for e in d['data']['data'])) == ['ACTIVATION', 'NOTE', 'REACTIVATION', 'SUSPENSION', 'VALIDATION'] and all(e['farmUniqueId'] == '$UID_X' for e in d['data']['data'])"
check "journal de X : 6 actions (2 activations), auteur renseigné" \
  "d['data']['totalItems'] == 6 and all(e['auteurNom'] for e in d['data']['data'])"
api GET "/admin/journal?ferme=$UID_Y&categorie=ESSAI"
check "journal filtré par catégorie ESSAI pour Y" "d['data']['totalItems'] == 1 and 'Prolongation' in d['data']['data'][0]['action']"
api GET "/admin/journal?categorie=SUSPENSION&du=$AUJ&au=$AUJ"
check "journal filtré par catégorie et dates" "d['data']['totalItems'] >= 1 and all(e['categorie'] == 'SUSPENSION' for e in d['data']['data'])"
api GET "/admin/journal?du=2001-01-01&au=2001-01-02"
check "journal sur une période vide" "code == 200 and d['data']['totalItems'] == 0"
check_eq "actions SUPER_ADMIN non comptées comme activité de la ferme (farm_id vide)" "0" \
  "$(psql_run "select count(*) from logs where entity_type='AdminFerme' and farm_id is not null")"

echo "--- aucune donnée confidentielle dans les logs"
check_eq "logs de la console : ni contenu de note, ni motif, ni montant, ni moyen" "0" \
  "$(psql_run "select count(*) from logs where entity_type='AdminFerme' and entity_id in ($FARM_X, $FARM_Y) and (action like '%Appel%' or action like '%contesté%' or action like '%123%456%' or action like '%Espèces%' or action like '%FCFA%')")"
check_eq "logs de la console : note et suspension journalisées sans détail" "2" \
  "$(psql_run "select count(*) from logs where entity_type='AdminFerme' and entity_id=$FARM_X and action in ('Note interne ajoutée sur la ferme FermeConsoleX$SUFFIXE', 'Suspension de la ferme FermeConsoleX$SUFFIXE')")"

echo "--- /logs : chaque ferme ne voit que ses propres logs"
TOKEN="$TOKEN_Y"
for q in "/logs/list?page=0&size=200&search=FermeConsoleX$SUFFIXE" "/logs/list?page=0&size=200&search=Note%20interne" \
         "/logs/list?page=0&size=200&search=Suspension" "/logs/list/by-class?nomClass=AdminFerme&page=0&size=200" \
         "/logs/list/by-action?idAction=$FARM_X&page=0&size=200" "/logs/list/by-class?nomClass=PaiementAbonnement&page=0&size=200"; do
  api GET "$q"
  check "ADMIN de Y : $q ne renvoie rien de X ni de la console" \
    "code == 200 and 'FermeConsoleX' not in str(d) and 'AdminFerme' not in str(d) and 'contesté' not in str(d)"
done
api GET "/logs/list?page=0&size=200"
python3 -c 'import json,sys; print("\n".join(l["uniqueId"] for l in json.load(open(sys.argv[1]))["data"]["data"]))' "$TMP/body" > "$TMP/uids_y"
NB_Y="$(wc -l < "$TMP/uids_y" | tr -d ' ')"
LISTE_Y="$(sed "s/.*/'&'/" "$TMP/uids_y" | paste -sd, -)"
check_eq "ADMIN de Y : /logs/list ne contient que des logs de la ferme Y" "$NB_Y" \
  "$( [ "$NB_Y" = "0" ] && echo 0 || psql_run "select count(*) from logs where unique_id in ($LISTE_Y) and farm_id=$FARM_Y and coalesce(entity_type,'') <> 'AdminFerme'")"
check "ADMIN de Y : ses propres logs sont bien listés" "code == 200 and d['data']['totalItems'] >= 1"

echo "--- /logs/delete"
LOG_X="$(psql_run "select unique_id from logs where farm_id=$FARM_X order by id limit 1")"
LOG_ADMIN="$(psql_run "select unique_id from logs where entity_type='AdminFerme' and entity_id=$FARM_X order by id limit 1")"
LOG_Y="$(psql_run "select unique_id from logs where farm_id=$FARM_Y order by id limit 1")"
TOKEN="$TOKEN_Y"
api DELETE "/logs/delete?uniqueId=$LOG_X"
check "ADMIN de Y ne peut pas supprimer un log de X" "code == 404"
api DELETE "/logs/delete?uniqueId=$LOG_ADMIN"
check "ADMIN de Y ne peut pas supprimer un log de la console" "code == 404"
check_eq "logs de X et de la console intacts" "0" "$(psql_run "select count(*) from logs where unique_id in ('$LOG_X','$LOG_ADMIN') and removed = true")"
TOKEN="$TOKEN_COMPTA_Y"
api DELETE "/logs/delete?uniqueId=$LOG_Y"
check "COMPTABLE de Y ne peut pas supprimer un log de sa ferme (403)" "code == 403"
TOKEN="$TOKEN_Y"
api DELETE "/logs/delete?uniqueId=$LOG_Y"
check "ADMIN de Y supprime un log de sa ferme" "code == 200 and d['data'] == 'SUCCESS_DELETE'"
api DELETE "/logs/delete?uniqueId=$LOG_Y"
check "ADMIN de Y le restaure" "code == 200 and d['data'] == 'SUCCESS_RESTORE'"

echo "--- actions simultanées : aucune prolongation perdue"
FIN_AVANT="$(psql_run "select date_fin from abonnements where farm_id=$FARM_X")"
for i in 1 2 3; do
  curl -s -o /dev/null -X POST "$BASE/admin/fermes/$UID_X/activer" -H "Authorization: Bearer $TOKEN_SA" \
    -H 'Content-Type: application/json' -H 'X-Client-Type: web' -d '{"periodicite":"MENSUEL","jours":10}' &
done
wait
check_eq "3 prolongations de 10 jours en même temps : +30 jours" \
  "$(python3 -c "import datetime; print(datetime.date.fromisoformat('$FIN_AVANT') + datetime.timedelta(days=30))")" \
  "$(psql_run "select date_fin from abonnements where farm_id=$FARM_X")"

echo "--- hors statistiques"
TOKEN="$TOKEN_SA"
api GET /admin/tableau-de-bord; cp "$TMP/body" "$TMP/tdbS0"
api GET /admin/finances; cp "$TMP/body" "$TMP/finS0"
PAYE_X_ANNEE="$(psql_run "select coalesce(sum(p.montant),0)::numeric(14,1) from paiements_abonnement p join abonnements a on a.id=p.abonnement_id where a.farm_id=$FARM_X and p.statut='VALIDE' and date_part('year', p.date_validation)=date_part('year', now())")"
PAYE_X_MOIS="$(psql_run "select coalesce(sum(p.montant),0)::numeric(14,1) from paiements_abonnement p join abonnements a on a.id=p.abonnement_id where a.farm_id=$FARM_X and p.statut='VALIDE' and date_trunc('month', p.date_validation)=date_trunc('month', now())")"
TOKEN="$TOKEN_X"; api POST "/admin/fermes/$UID_X/statistiques" '{"exclure":true}'
check "hors statistiques : refusé (403) à un ADMIN de ferme" "code == 403"
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_X/statistiques" '{}'
check "hors statistiques sans valeur : refusé" "code == 400"
api POST "/admin/fermes/$UID_X/statistiques" '{"exclure":true}'
check "exclure X des statistiques" "code == 200 and d['data']['ferme']['exclureStatistiques'] is True"
api POST "/admin/fermes/$UID_X/statistiques" '{"exclure":true}'
check "exclure deux fois : refusé" "code == 400"
api GET /admin/tableau-de-bord; cp "$TMP/body" "$TMP/tdbS1"
check_eq "X hors stats : -1 ferme" "-1" "$(ecart "$TMP/tdbS0" "$TMP/tdbS1" totalFermes)"
check_eq "X hors stats : -480 sujets vivants" "-480" "$(ecart "$TMP/tdbS0" "$TMP/tdbS1" totalSujetsVivants)"
check_eq "X hors stats : -1 nouvelle ferme du mois" "-1" "$(ecart "$TMP/tdbS0" "$TMP/tdbS1" nouvellesCeMois)"
check_eq "X hors stats : revenu de l'année sans les paiements de X" "-$PAYE_X_ANNEE" "$(ecart "$TMP/tdbS0" "$TMP/tdbS1" revenuCetteAnnee)"
check_eq "X hors stats : revenu du mois sans les paiements de X" "-$PAYE_X_MOIS" "$(ecart "$TMP/tdbS0" "$TMP/tdbS1" revenuCeMois)"
check "X hors stats : revenu mensuel estimé en baisse" "json.load(open('$TMP/tdbS0'))['data']['revenuMensuelEstime'] > d['data']['revenuMensuelEstime']"
check "X hors stats : graphique des nouvelles fermes du mois -1" \
  "json.load(open('$TMP/tdbS0'))['data']['nouvellesFermesParMois'][-1]['nombre'] - d['data']['nouvellesFermesParMois'][-1]['nombre'] == 1"
api GET /admin/finances; cp "$TMP/body" "$TMP/finS1"
check "X hors stats : finances de l'année et conversion sans X" \
  "abs((json.load(open('$TMP/finS0'))['data']['totalAnnee'] - d['data']['totalAnnee']) - $PAYE_X_ANNEE) < 0.01 and json.load(open('$TMP/finS0'))['data']['essaisConvertis'] - d['data']['essaisConvertis'] == 1 and json.load(open('$TMP/finS0'))['data']['fermesAyantPaye'] - d['data']['fermesAyantPaye'] == 1"
api GET /admin/fermes
check "X hors stats : toujours dans la liste des fermes, marquée" "[f['exclureStatistiques'] for f in d['data'] if f['farmUniqueId'] == '$UID_X'] == [True]"
api GET "/admin/journal?ferme=$UID_X&categorie=STATISTIQUES"
check "journal : exclusion des statistiques, sans détail" "d['data']['totalItems'] == 1 and d['data']['data'][0]['action'] == 'Ferme FermeConsoleX$SUFFIXE exclue des statistiques'"
api POST "/admin/fermes/$UID_X/statistiques" '{"exclure":false}'
check "réintégrer X dans les statistiques" "code == 200 and d['data']['ferme']['exclureStatistiques'] is False"
api GET /admin/tableau-de-bord
check "X réintégrée : mêmes chiffres qu'avant" \
  "all(json.load(open('$TMP/tdbS0'))['data'][k] == d['data'][k] for k in ['totalFermes','totalSujetsVivants','revenuCetteAnnee','revenuCeMois','revenuMensuelEstime'])"

echo "--- e-mails à la ferme"
MAILS_DIR="${MAIL_SINK_DIR:-}"
# E-mails reçus par une adresse : JSON [[sujet, texte], ...] (décodés : quoted-printable, UTF-8).
mails_de() {
  python3 - "$MAILS_DIR" "$1" <<'PY'
import email, email.header, glob, json, os, sys
d, adr = sys.argv[1], sys.argv[2]
res = []
for fn in sorted(glob.glob(os.path.join(d, "*.eml"))):
    m = email.message_from_bytes(open(fn, "rb").read())
    if adr not in (m.get("To") or ""):
        continue
    sujet = str(email.header.make_header(email.header.decode_header(m.get("Subject") or "")))
    texte = ""
    for part in m.walk():
        if part.get_content_type() == "text/plain":
            texte = part.get_payload(decode=True).decode("utf-8", "replace")
            break
    res.append([sujet, texte, m.get("From") or ""])
print(json.dumps(res, ensure_ascii=False))
PY
}
EMAIL_Y="$(psql_run "select email from utilisateurs where farm_id=$FARM_Y and email like 'admin-conY-%'")"
if [ -n "$MAILS_DIR" ]; then
  N0="$(mails_de "$EMAIL_Y" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
  api POST "/admin/fermes/$UID_Y/prolonger-essai" '{"jours":3}'
  api POST "/admin/fermes/$UID_Y/suspendre" '{"motif":"Test des e-mails"}'
  api POST "/admin/fermes/$UID_Y/reactiver"
  api POST "/admin/fermes/$UID_Y/activer" '{"periodicite":"MENSUEL","jours":5,"montant":7777,"moyenPaiement":"Wave"}'
  check "activer avec e-mail : action réussie" "code == 200"
  sleep 2
  mails_de "$EMAIL_Y" > "$TMP/body"; echo 200 > "$TMP/code"
  check "e-mails de Y : 4 nouveaux, signés Cocorico" "len(d) == $N0 + 4 and all('Cocorico' in m[2] and \"L'équipe Cocorico\" in m[1] for m in d[$N0:])"
  check "e-mail d'essai prolongé : nouvelle date" "\"essai Cocorico est prolongée\" in d[$N0][0] and 'prolongée jusqu' in d[$N0][1]"
  check "e-mail de suspension : motif et WhatsApp" "'suspendu' in d[$N0+1][0] and 'Motif : Test des e-mails' in d[$N0+1][1] and '+223 83 91 86 99' in d[$N0+1][1]"
  check "e-mail de réactivation" "'rétabli' in d[$N0+2][0]"
  check "e-mail d'activation : fin et montant" "'actif jusqu' in d[$N0+3][0] and '7 777 FCFA' in d[$N0+3][1]"
  mails_de "$COMPTA_Y_EMAIL" > "$TMP/body"
  check "le COMPTABLE de Y ne reçoit pas ces e-mails" "not [m for m in d if 'Cocorico est' in m[0] and ('suspendu' in m[0] or 'rétabli' in m[0])]"
else
  echo "(MAIL_SINK_DIR non défini : vérifications des e-mails ignorées)"
  api POST "/admin/fermes/$UID_Y/activer" '{"periodicite":"MENSUEL","jours":5,"montant":7777,"moyenPaiement":"Wave"}'
fi
# Un serveur d'e-mails en panne ne fait jamais échouer l'action : vérifié par le code
# (envoi après commit, erreurs seulement journalisées).

echo "--- connexion mobile d'une ferme bloquée"
GRACE="$(psql_run "select coalesce(delai_grace_jours, 5) from abonnement_config order by id limit 1")"
ADMIN_Y_EMAIL="$EMAIL_Y"
auth() { # $1=client $2=identifiant -> code dans $TMP/code, corps dans $TMP/body
  curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/auth" -H "X-Client-Type: $1" \
    --data-urlencode grantType=password --data-urlencode "identifiant=$2" --data-urlencode "password=$PWD_TEST" \
    --data-urlencode ouiRefresh=true > "$TMP/code"
}
auth mobile "$ADMIN_Y_EMAIL"
check "Y active : connexion mobile OK" "code == 200 and d['data']['accessToken']"
REFRESH_Y="$(jval "d['data']['refreshToken']")"
TOKEN="$TOKEN_Y"; api POST /qrcode/generate "{\"uniqueId\":\"$(psql_run "select unique_id from utilisateurs where email='$COMPTA_Y_EMAIL'")\",\"duration\":\"30d\"}"
check "Y active : QR du COMPTABLE généré" "code == 200"
QR_Y="$(jval "d['data']['encryptedQr']")"
psql_run "update abonnements set date_fin = current_date - 1, suspendu = null, motif_suspension = null, suspendu_le = null where farm_id=$FARM_Y" >/dev/null
if [ "$GRACE" -ge 1 ]; then
  auth mobile "$ADMIN_Y_EMAIL"
  check "Y en grâce : connexion mobile OK" "code == 200"
fi
psql_run "update abonnements set date_fin = current_date - $((GRACE + 1)) where farm_id=$FARM_Y" >/dev/null
auth mobile "$ADMIN_Y_EMAIL"
check "Y expirée : connexion mobile refusée (403) avec le message" \
  "code == 403 and 'abonnement de votre ferme est terminé' in d['message'] and 'saisies déjà faites' in d['message'] and '+223 83 91 86 99' in d['data']['errorMessage']"
auth mobile "$COMPTA_Y_EMAIL"
check "Y expirée : connexion mobile du COMPTABLE refusée" "code == 403"
auth web "$ADMIN_Y_EMAIL"
check "Y expirée : connexion web OK (page Abonnement)" "code == 200"
curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/auth" -H 'X-Client-Type: mobile' --data-urlencode grantType=refreshToken \
  --data-urlencode identifiant= --data-urlencode password= --data-urlencode "refreshToken=$REFRESH_Y" --data-urlencode ouiRefresh=true > "$TMP/code"
check "Y expirée : rafraîchissement du token mobile OK" "code == 200 and d['data']['accessToken']"
TOKEN="$TOKEN_Y"; api GET /projets/select
check "Y expirée : un token déjà émis lit toujours (projets)" "code == 200"
api PUT /farm-settings '{"comptableMobileEnabled":true,"comptableWebEnabled":true}'
check "Y expirée : un token déjà émis écrit toujours (aucune saisie perdue)" "code == 200"
api POST /qrcode/generate "{\"uniqueId\":\"$(psql_run "select unique_id from utilisateurs where email='$COMPTA_Y_EMAIL'")\",\"duration\":\"30d\"}"
check "Y expirée : nouveau QR refusé avec le message" "code == 400 and 'terminé' in str(d)"
curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/qrcode/scan" -H "Authorization: Bearer $TOKEN_SA" -H 'Content-Type: application/json' -d "{\"encryptedQr\":\"$QR_Y\"}" > "$TMP/code"
check "Y expirée : scan du QR refusé par le serveur avec le message" "code == 401 and 'terminé' in str(d)"
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_Y/suspendre" '{"motif":"Test mobile"}'
psql_run "update abonnements set date_fin = current_date + 30 where farm_id=$FARM_Y" >/dev/null
auth mobile "$ADMIN_Y_EMAIL"
check "Y suspendue (date valide) : connexion mobile refusée, message de suspension" \
  "code == 403 and d['message'] == \"L'accès de votre ferme est suspendu. Contactez-nous sur WhatsApp au +223 83 91 86 99.\""
auth web "$ADMIN_Y_EMAIL"
check "Y suspendue : connexion web OK" "code == 200"
curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/auth" -H 'X-Client-Type: mobile' --data-urlencode grantType=password \
  --data-urlencode "identifiant=$SUPERADMIN_ID" --data-urlencode "password=$SUPERADMIN_PWD" > "$TMP/code"
check "SUPER_ADMIN : connexion mobile OK" "code == 200"
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_Y/reactiver"
psql_run "update abonnements set date_fin = current_date - $((GRACE + 1)) where farm_id=$FARM_Y" >/dev/null
api POST "/admin/fermes/$UID_Y/activer" '{"periodicite":"MENSUEL","mois":1}'
check "renouvellement de Y" "code == 200 and d['data']['ferme']['statut'] == 'ACTIF'"
auth mobile "$ADMIN_Y_EMAIL"
check "Y renouvelée : connexion mobile de nouveau OK" "code == 200"
curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/qrcode/scan" -H "Authorization: Bearer $TOKEN_SA" -H 'Content-Type: application/json' -d "{\"encryptedQr\":\"$QR_Y\"}" > "$TMP/code"
check "Y renouvelée : scan du QR de nouveau accepté" "code == 200 and d['data']['valid']"

echo "--- comptes : jamais d'action sur un compte d'une autre ferme"
UID_ADMIN_X="$(psql_run "select unique_id from utilisateurs where email='$EMAIL_X'")"
UID_ADMIN_Y="$(psql_run "select unique_id from utilisateurs where email='$ADMIN_Y_EMAIL'")"
UID_COMPTA_Y="$(psql_run "select unique_id from utilisateurs where email='$COMPTA_Y_EMAIL'")"
ETAT_X_AVANT="$(psql_run "select unique_id || '|' || password || '|' || coalesce(statut::text,'') || '|' || coalesce(info_qrcode_encrypte,'') || '|' || full_name || '|' || coalesce(archive::text,'') from utilisateurs where email='$EMAIL_X'")"
TOKEN="$TOKEN_Y"
api GET "/users/detail/$UID_ADMIN_X"; check "ADMIN de Y : fiche d'un compte de X refusée (404)" "code == 404"
api PUT "/users/update-prod-finan/$UID_ADMIN_X" '{"fullName":"Pirate","telephone":"70000000","email":"pirate@t.local","roles":["ADMIN"]}'
check "ADMIN de Y : modifier un compte de X refusé (404)" "code == 404"
api POST "/users/reset-password/$UID_ADMIN_X"; check "ADMIN de Y : réinitialiser le mot de passe d'un compte de X refusé (404)" "code == 404"
api POST "/users/regenerate-qr/$UID_ADMIN_X"; check "ADMIN de Y : régénérer l'identifiant d'un compte de X refusé (404)" "code == 404"
api PUT "/users/revoke-prod-finan/$UID_ADMIN_X"; check "ADMIN de Y : désactiver un compte de X refusé (404)" "code == 404"
api PUT "/users/delete-or-archive/$UID_ADMIN_X"; check "ADMIN de Y : supprimer un compte de X refusé (404)" "code == 404"
api PUT "/users/restaurer/$UID_ADMIN_X"; check "ADMIN de Y : restaurer un compte de X refusé (404)" "code == 404"
api POST /qrcode/generate "{\"uniqueId\":\"$UID_ADMIN_X\",\"duration\":\"30d\"}"
check "ADMIN de Y : QR d'un compte de X refusé (404, pas de jeton)" "code == 404 and d.get('data') is None"
api POST "/qrcode/revoke/$UID_ADMIN_X"; check "ADMIN de Y : révoquer le QR d'un compte de X refusé (404)" "code == 404"
TOKEN="$TOKEN_COMPTA_Y"
api POST /qrcode/generate "{\"uniqueId\":\"$UID_ADMIN_X\",\"duration\":\"30d\"}"
check "COMPTABLE de Y : QR d'un compte de X refusé (404)" "code == 404"
check_eq "compte de X inchangé (identifiant, mot de passe, statut, QR, nom, archive)" "$ETAT_X_AVANT" \
  "$(psql_run "select unique_id || '|' || password || '|' || coalesce(statut::text,'') || '|' || coalesce(info_qrcode_encrypte,'') || '|' || full_name || '|' || coalesce(archive::text,'') from utilisateurs where email='$EMAIL_X'")"

echo "--- comptes : même ferme, seul l'ADMIN agit"
TOKEN="$TOKEN_COMPTA_Y"
api POST /qrcode/generate "{\"uniqueId\":\"$UID_ADMIN_Y\",\"duration\":\"30d\"}"
check "COMPTABLE de Y : QR de l'ADMIN de Y refusé (403)" "code == 403"
api POST "/users/reset-password/$UID_ADMIN_Y"; check "COMPTABLE de Y : réinitialiser le mot de passe de l'ADMIN refusé (403)" "code == 403"
api GET "/users/detail/$UID_ADMIN_Y"; check "COMPTABLE de Y : fiche de l'ADMIN refusée (403)" "code == 403"
api PUT "/users/revoke-prod-finan/$UID_ADMIN_Y"; check "COMPTABLE de Y : désactiver l'ADMIN refusé (403)" "code == 403"
api POST /users/create-pro-or-finance "{\"fullName\":\"Intrus $LETTRES\",\"email\":\"intrus-$SUFFIXE@t.local\",\"telephone\":\"65$(python3 -c 'import random; print(random.randint(100000, 999999))')\",\"roles\":[\"ADMIN\"]}"
check "COMPTABLE de Y : créer un compte refusé (403)" "code == 403"
api GET "/users/detail/$UID_COMPTA_Y"; check "COMPTABLE de Y : sa propre fiche (page Profil) OK" "code == 200"
api PUT "/users/update-prod-finan/$UID_COMPTA_Y" "{\"fullName\":\"Comptable Profil $LETTRES\",\"telephone\":\"$(psql_run "select telephone from utilisateurs where email='$COMPTA_Y_EMAIL'")\",\"email\":\"$COMPTA_Y_EMAIL\"}"
check "COMPTABLE de Y : modifier son propre profil sans rôles (page Profil) OK" "code == 200"
api PUT "/users/update-prod-finan/$UID_COMPTA_Y" "{\"fullName\":\"Comptable Profil $LETTRES\",\"telephone\":\"$(psql_run "select telephone from utilisateurs where email='$COMPTA_Y_EMAIL'")\",\"email\":\"$COMPTA_Y_EMAIL\",\"roles\":[\"ADMIN\"]}"
check "COMPTABLE de Y : se donner le rôle ADMIN refusé (403)" "code == 403"
check_eq "COMPTABLE de Y toujours COMPTABLE seulement" "COMPTABLE" "$(psql_run "select string_agg(r.role, ',') from roles_users ru join roles r on r.id=ru.id_roles join utilisateurs u on u.id=ru.id_utilisateurs where u.email='$COMPTA_Y_EMAIL'")"
TOKEN="$TOKEN_Y"
api GET "/users/detail/$UID_COMPTA_Y"; check "ADMIN de Y : fiche de son COMPTABLE OK" "code == 200"
api POST /qrcode/generate "{\"uniqueId\":\"$UID_COMPTA_Y\",\"duration\":\"30d\"}"
check "ADMIN de Y : QR de son COMPTABLE OK (parcours web)" "code == 200 and d['data']['encryptedQr']"
api POST "/qrcode/revoke/$UID_COMPTA_Y"; check "ADMIN de Y : révoquer le QR de son COMPTABLE OK" "code == 200"
api PUT "/users/update-prod-finan/$UID_COMPTA_Y" "{\"fullName\":\"Comptable $LETTRES\",\"telephone\":\"$(psql_run "select telephone from utilisateurs where email='$COMPTA_Y_EMAIL'")\",\"email\":\"$COMPTA_Y_EMAIL\",\"roles\":[\"SUPER_ADMIN\"]}"
check "ADMIN de Y : donner le rôle SUPER_ADMIN refusé (403)" "code == 403"
api PUT "/users/update-prod-finan/$UID_COMPTA_Y" "{\"fullName\":\"Comptable $LETTRES\",\"telephone\":\"$(psql_run "select telephone from utilisateurs where email='$COMPTA_Y_EMAIL'")\",\"email\":\"$COMPTA_Y_EMAIL\",\"roles\":[\"COMPTABLE\"]}"
check "ADMIN de Y : modifier son COMPTABLE (rôles) OK" "code == 200"
api POST /users/create-pro-or-finance "{\"fullName\":\"Super $LETTRES\",\"email\":\"super-$SUFFIXE@t.local\",\"telephone\":\"64$(python3 -c 'import random; print(random.randint(100000, 999999))')\",\"roles\":[\"SUPER_ADMIN\"]}"
check "ADMIN de Y : créer un SUPER_ADMIN refusé (403)" "code == 403"
VENTE_ROLE="$(psql_run "select unique_id from roles where role='VENTE'")"
api PUT "/roles/update/$VENTE_ROLE" '{"role":"PIRATE"}'
check "ADMIN de Y : modifier un rôle global refusé (403)" "code == 403"
check_eq "rôle VENTE intact" "VENTE" "$(psql_run "select role from roles where unique_id='$VENTE_ROLE'")"
curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/users/create" -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"Inscrit $LETTRES\",\"email\":\"inscrit-sa-$SUFFIXE@t.local\",\"telephone\":\"63$(python3 -c 'import random; print(random.randint(100000, 999999))')\",\"farmName\":\"FermeIntrus$SUFFIXE\",\"roles\":[\"SUPER_ADMIN\"]}" > "$TMP/code"
check "inscription publique avec le rôle SUPER_ADMIN refusée (403)" "code == 403"
check_eq "aucun compte créé par cette inscription" "0" "$(psql_run "select count(*) from utilisateurs where email='inscrit-sa-$SUFFIXE@t.local'")"
TOKEN="$TOKEN_X"; api POST /personnel/create '{"nom":"Employé X"}'
PERS_X="$(psql_run "select unique_id from personnel where nom='Employé X' and farm_id=$FARM_X order by id desc limit 1" 2>/dev/null || true)"
if [ -n "$PERS_X" ]; then
  TOKEN="$TOKEN_Y"; api PUT "/personnel/update/$PERS_X" '{"nom":"Pirate"}'
  check "ADMIN de Y : modifier un employé de X refusé" "code >= 400"
  check_eq "employé de X intact" "Employé X" "$(psql_run "select nom from personnel where unique_id='$PERS_X'")"
fi

echo "--- option A : téléphone déjà connecté d'une ferme bloquée"
# Le téléphone (APK 1.34) utilise le jeton de son QR (claim type = QR_CODE) et n'envoie pas
# d'en-tête X-Client-Type. On déchiffre un vrai QR (clé AES_SECRET_KEY, ou ENV_FILE) pour
# envoyer exactement ce jeton.
AES_KEY="${AES_SECRET_KEY:-}"
if [ -z "$AES_KEY" ] && [ -n "${ENV_FILE:-}" ] && [ -f "$ENV_FILE" ]; then
  AES_KEY="$(grep -E '^AES_SECRET_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '"'"'"'\r')"
fi
qr_jeton() { # $1=QR chiffré -> jeton JWT du QR
  AES_KEY="$AES_KEY" python3 - "$1" <<'PY'
import base64, json, os, sys
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding
k = os.environ["AES_KEY"].strip().encode()
k = (k + b"\0" * 32)[:32]
raw = base64.b64decode(sys.argv[1])
d = Cipher(algorithms.AES(k), modes.CBC(raw[:16])).decryptor()
p = d.update(raw[16:]) + d.finalize()
u = padding.PKCS7(128).unpadder()
print(json.loads((u.update(p) + u.finalize()).decode())["token"])
PY
}
mob() { # $1=METHOD $2=chemin $3=jeton $4=JSON ; appel « téléphone » : jeton QR, sans X-Client-Type
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" -H "Authorization: Bearer $3" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: opta-$SUFFIXE-$RANDOM$RANDOM" ${4:+-d "$4"} > "$TMP/code"
}
if [ -z "$AES_KEY" ]; then
  echo "(AES_SECRET_KEY ou ENV_FILE non défini : vérifications de l'option A ignorées)"
else
  TOKEN="$TOKEN_Y"; api POST /qrcode/generate "{\"uniqueId\":\"$UID_COMPTA_Y\",\"duration\":\"30d\"}"
  ok_cree "QR du COMPTABLE de Y"
  JWT_QR_COMPTA="$(qr_jeton "$(jval "d['data']['encryptedQr']")")"
  api POST /qrcode/generate "{\"uniqueId\":\"$UID_ADMIN_Y\",\"duration\":\"30d\"}"
  ok_cree "QR de l'ADMIN de Y"
  JWT_QR_ADMIN="$(qr_jeton "$(jval "d['data']['encryptedQr']")")"
  python3 -c "import base64,json,sys; p=sys.argv[1].split('.')[1]; p+='='*(-len(p)%4); sys.exit(0 if json.loads(base64.urlsafe_b64decode(p)).get('type')=='QR_CODE' else 1)" "$JWT_QR_COMPTA" \
    && check_eq "le jeton déchiffré est bien un jeton de QR" "ok" "ok" || check_eq "le jeton déchiffré est bien un jeton de QR" "ok" "non"

  # Grâce : tout fonctionne (ADMIN de Y, jamais lu par le téléphone avant : pas de cache).
  if [ "$GRACE" -ge 1 ]; then
    psql_run "update abonnements set date_fin = current_date - 1 where farm_id=$FARM_Y" >/dev/null
    mob GET /projets/select "$JWT_QR_ADMIN"; check "grâce : lecture du téléphone OK" "code == 200"
    mob GET /notifications/list "$JWT_QR_ADMIN"; check "grâce : alertes normales (pas de message de blocage)" "code == 200 and 'plus mises à jour' not in str(d)"
  fi

  # Expirée après la grâce (le COMPTABLE n'a encore rien lu : pas de cache).
  psql_run "update abonnements set date_fin = current_date - $((GRACE + 1)) where farm_id=$FARM_Y" >/dev/null
  for g in /projets/select /farm-settings /clients/select /ventes-oeufs/stock /magasins/list?type=VENTE /auth/me; do
    mob GET "$g" "$JWT_QR_COMPTA"
    check "expirée : lecture $g refusée (403) avec le message" "code == 403 and 'plus mises à jour' in d['errors'][0] and 'consultation seule' not in str(d).lower()"
  done
  mob GET /notifications/list "$JWT_QR_COMPTA"
  check "expirée : /notifications/list = une alerte CRITIQUE avec le message" \
    "code == 200 and len(d['data']) == 1 and d['data'][0]['level'] == 'CRITIQUE' and d['data'][0]['read'] is False and 'rien n\\'est perdu' in d['data'][0]['message']"
  mob GET "/notifications/projet/xyz" "$JWT_QR_COMPTA"
  check "expirée : alertes d'un projet = la même alerte (carte de l'accueil)" "code == 200 and d['data'][0]['key'].startswith('abonnement-bloque-')"
  mob GET /abonnements/moi "$JWT_QR_COMPTA"; check "expirée : /abonnements/moi reste lisible" "code == 200"
  NB_CLIENTS_AVANT="$(psql_run "select count(*) from clients where farm_id=$FARM_Y")"
  mob POST /clients/create "$JWT_QR_ADMIN" "{\"nom\":\"Client tel $LETTRES\",\"telephone\":\"66$(python3 -c 'import random; print(random.randint(100000, 999999))')\"}"
  check "expirée : saisie client du téléphone acceptée" "code in (200, 201)"
  check_eq "expirée : client enregistré" "$((NB_CLIENTS_AVANT + 1))" "$(psql_run "select count(*) from clients where farm_id=$FARM_Y")"
  mob POST /transactions/create "$JWT_QR_COMPTA" "{\"type\":\"ENTREE\",\"commun\":true,\"categorie\":\"Don\",\"description\":\"Saisie téléphone $SUFFIXE\",\"montant\":500,\"date\":\"$AUJ\"}"
  check "expirée : saisie de transaction du téléphone acceptée" "code in (200, 201)"
  check_eq "expirée : transaction enregistrée" "1" "$(psql_run "select count(*) from transactions where description='Saisie téléphone $SUFFIXE'")"
  mob PUT "/notifications/abonnement-bloque-$AUJ/read" "$JWT_QR_COMPTA"
  check "expirée : marquer l'alerte comme lue accepté" "code == 200"
  curl -s -o "$TMP/body" -w '%{http_code}' "$BASE/projets/select" -H "Authorization: Bearer $TOKEN_COMPTA_Y" -H 'X-Client-Type: mobile' > "$TMP/code"
  check "expirée : jeton de connexion + X-Client-Type mobile, lecture refusée aussi" "code == 403"
  TOKEN="$TOKEN_Y"; api GET /projets/select
  check "expirée : application web non touchée par ce filtre" "code == 200"

  # Suspension (console : état mis à jour tout de suite pour les téléphones).
  TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_Y/suspendre" '{"motif":"Option A"}'
  psql_run "update abonnements set date_fin = current_date + 20 where farm_id=$FARM_Y" >/dev/null
  mob GET /projets/select "$JWT_QR_ADMIN"
  check "suspendue : lecture refusée avec le message de suspension" "code == 403 and 'suspendu' in d['errors'][0]"
  mob POST /clients/create "$JWT_QR_ADMIN" "{\"nom\":\"Client susp $LETTRES\",\"telephone\":\"67$(python3 -c 'import random; print(random.randint(100000, 999999))')\"}"
  check "suspendue : saisie du téléphone acceptée" "code in (200, 201)"

  # Renouvellement : lectures rétablies tout de suite (cache vidé par la console).
  api POST "/admin/fermes/$UID_Y/reactiver"
  mob GET /projets/select "$JWT_QR_ADMIN"; check "réactivée : lecture du téléphone rétablie tout de suite" "code == 200"
  mob GET /projets/select "$JWT_QR_COMPTA"; check "réactivée : lecture rétablie aussi pour le COMPTABLE" "code == 200"
  mob GET /notifications/list "$JWT_QR_COMPTA"; check "réactivée : plus d'alerte de blocage" "code == 200 and 'plus mises à jour' not in str(d)"
fi

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
