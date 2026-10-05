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
#   - tous les nouveaux endpoints refusés (400) à un ADMIN de ferme ;
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
  check "ADMIN de ferme refusé : $ep" "code == 400 and d.get('data') is None"
done
api POST "/admin/fermes/$UID_X/activer" '{"periodicite":"ANNUEL","mois":12,"montant":1,"moyenPaiement":"x"}'
check "ADMIN de ferme refusé : activer" "code == 400"
api POST "/admin/fermes/$UID_X/suspendre" '{"motif":"x"}'
check "ADMIN de ferme refusé : suspendre" "code == 400"
api POST "/admin/fermes/$UID_X/prolonger-essai" '{"jours":10}'
check "ADMIN de ferme refusé : prolonger l'essai" "code == 400"
api POST "/admin/fermes/$UID_X/notes" '{"contenu":"x"}'
check "ADMIN de ferme refusé : note" "code == 400"
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
ATTENDU="$(python3 -c "import datetime; d=datetime.date.fromisoformat('$FIN_ESSAI'); m=d.month+12; y=d.year+(m-1)//12; m=(m-1)%12+1; import calendar; print(datetime.date(y,m,min(d.day,calendar.monthrange(y,m)[1])))")"
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
check "fiche : utilisateurs avec rôles et dernière connexion, rappels envoyés (liste), notes" \
  "[(u['roles'], (u['derniereConnexion'] or '')[:10]) for u in d['data']['utilisateurs']] == [(['ADMIN'], '$AUJ')] and isinstance(d['data']['rappels'], list) and len(d['data']['notes']) == 1"
TOKEN="$TOKEN_X"; api GET /abonnements/moi
check "la note n'apparaît pas côté ferme" "'Appel' not in str(d)"

echo "--- journal"
TOKEN="$TOKEN_SA"
api GET "/admin/journal?ferme=$UID_X&size=50"
check "journal de X : activations, suspension, réactivation, note" \
  "sorted(set(e['categorie'] for e in d['data']['data'])) == ['ACTIVATION', 'NOTE', 'REACTIVATION', 'SUSPENSION'] and all(e['farmUniqueId'] == '$UID_X' for e in d['data']['data'])"
check "journal de X : 5 actions (2 activations), auteur renseigné" \
  "d['data']['totalItems'] == 5 and all(e['auteurNom'] for e in d['data']['data'])"
api GET "/admin/journal?ferme=$UID_Y&categorie=ESSAI"
check "journal filtré par catégorie ESSAI pour Y" "d['data']['totalItems'] == 1 and 'Prolongation' in d['data']['data'][0]['action']"
api GET "/admin/journal?categorie=SUSPENSION&du=$AUJ&au=$AUJ"
check "journal filtré par catégorie et dates" "d['data']['totalItems'] >= 1 and all(e['categorie'] == 'SUSPENSION' for e in d['data']['data'])"
api GET "/admin/journal?du=2001-01-01&au=2001-01-02"
check "journal sur une période vide" "code == 200 and d['data']['totalItems'] == 0"
check_eq "actions SUPER_ADMIN non comptées comme activité de la ferme (farm_id vide)" "0" \
  "$(psql_run "select count(*) from logs where entity_type='AdminFerme' and farm_id is not null")"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
