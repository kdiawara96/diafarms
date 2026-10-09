#!/usr/bin/env bash
# Prix de l'abonnement par poule (AbonnementTarif, AbonnementTarifService), 2026-10-05.
#
# Règle : prix du mois = 6 FCFA × poules comptées, arrondi au-dessus à 100 FCFA, minimum
# 5 000 FCFA ; prix de l'an = 10 × prix du mois ; poules comptées = le plus grand nombre de
# sujets vivants des Projets en cours sur les 30 derniers jours. Réglable par le
# SUPER_ADMIN ; tarif spécial (prix fixe) par ferme.
#
# Vérifie :
#   - simulation publique (sans connexion) : 0, 500, 834, 1 000, 1 350, 2 000, 5 000,
#     10 000 poules, arrondi et minimum ; N invalide refusé ;
#   - ferme sans Projet : 0 poule, minimum ; /abonnements/moi garde ses anciens champs ;
#   - mortalité et réformes qui font baisser le compte ; une mortalité supprimée ignorée ;
#     le maximum des 30 jours (des morts récents ne baissent pas le prix) ;
#   - bande de chair clôturée il y a 10 jours (comptée) puis il y a 40 jours (0), par la
#     fin prévue et par la libération du poulailler ; Projet supprimé ignoré ;
#   - plusieurs Projets additionnés ;
#   - réglages SUPER_ADMIN (prix par poule, minimum, mois offerts, arrondi) et leurs refus ;
#   - tarif spécial : fixé, utilisé partout, retiré ; refus ;
#   - montant attendu et poules enregistrés à la déclaration, inchangés ensuite ;
#   - rappels : le message contient le montant ; cloche aussi ;
#   - un compte qui n'est pas SUPER_ADMIN est refusé (réglages, tarif spécial) ;
#   - console : poules et prix dans la liste et la fiche, revenu mensuel estimé = tarifs ;
#   - revue du 2026-10-06 : Projet clôturé (date_cloture) compté 30 jours après la clôture,
#     même avant sa fin prévue, et encore compté s'il est clôturé après sa fin prévue ;
#     raison du tarif spécial absente de /abonnements/moi ; montant affiché différent du
#     prix actuel refusé ; réglages refusés en 403 ; comptage en échec (colonne renommée
#     quelques secondes) : page sans erreur, déclaration refusée, aucun montant dans les
#     rappels ni la cloche, revenu estimé signalé incomplet, prix fixe toujours annoncé.
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

# Les réglages de prix sont GLOBAUX : remis tels qu'ils étaient en sortant.
CONFIG_INITIALE="$(psql_run "select coalesce(prix_par_poule::text,'NULL')||','||coalesce(prix_minimum_mensuel::text,'NULL')||','||coalesce(mois_offerts_annuel::text,'NULL')||','||coalesce(arrondi_prix::text,'NULL') from abonnement_config order by id limit 1")"
# Comptage en échec simulé en renommant une colonne : remise en place en sortant, quoi qu'il arrive.
remettre_colonne() {
  psql_run "do \$\$ begin if exists (select 1 from information_schema.columns where table_name='occupations_batiments' and column_name='date_sortie_essai_tarif') then alter table occupations_batiments rename column date_sortie_essai_tarif to date_sortie; end if; end \$\$" >/dev/null
}
restaurer() {
  remettre_colonne
  if [ -n "$CONFIG_INITIALE" ]; then
    IFS=, read -r a b c e <<< "$CONFIG_INITIALE"
    psql_run "update abonnement_config set prix_par_poule=$a, prix_minimum_mensuel=$b, mois_offerts_annuel=$c, arrondi_prix=$e" >/dev/null
  fi
  rm -rf "$TMP"
}
trap restaurer EXIT
defauts() { psql_run "update abonnement_config set prix_par_poule=null, prix_minimum_mensuel=null, mois_offerts_annuel=null, arrondi_prix=null" >/dev/null; }

api() { # $1=METHOD $2=chemin $3=JSON (facultatif) ; réponse dans $TMP/body, code dans $TMP/code
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${3:+-d "$3"} > "$TMP/code"
}
public() { # $1=chemin, sans aucun jeton
  curl -s -o "$TMP/body" -w '%{http_code}' "$BASE$1" > "$TMP/code"
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

TOKEN_SA="$(login_mobile "$SUPERADMIN_ID" "$SUPERADMIN_PWD")"
[ -n "$TOKEN_SA" ] || { echo "ECHEC  connexion super-admin"; exit 1; }
defauts

# T = tarif.poulesComptees, prixMensuel, prixAnnuel de /abonnements/moi (python)
T="d['data']['tarif']"

echo "--- simulation publique (sans connexion)"
simu() { # $1=poules $2=mois attendu $3=an attendu $4=minimum attendu (True/False)
  public "/abonnements/tarif-simulation?poules=$1"
  check "simulation $1 poules : $2 FCFA par mois, $3 par an" \
    "code == 200 and d['data']['prixMensuel'] == $2 and d['data']['prixAnnuel'] == $3 and d['data']['minimumApplique'] == $4 and d['data']['poulesComptees'] == $1"
}
simu 0 5000 50000 True
simu 500 5000 50000 True
simu 834 5100 51000 False
simu 1000 6000 60000 False
simu 1350 8100 81000 False
simu 2000 12000 120000 False
simu 5000 30000 300000 False
simu 10000 60000 600000 False
simu 1000000 6000000 60000000 False
public "/abonnements/tarif-simulation?poules=834"
check "simulation 834 : détail (5 004 brut, arrondi 5 100, 6 FCFA, 2 mois offerts)" \
  "d['data']['montantParPoules'] == 5004 and d['data']['montantArrondi'] == 5100 and d['data']['prixParPoule'] == 6 and d['data']['moisOffertsAnnuel'] == 2 and d['data']['arrondi'] == 100 and not d['data']['prixFixe']"
for n in -1 abc 1000001 1.5 "" "%20"; do
  public "/abonnements/tarif-simulation?poules=$n"
  check "simulation refusée pour poules=« $n » (400)" "code == 400 and d['data'] is None"
done
public "/abonnements/tarif-simulation"
check "simulation sans nombre : refusée (400)" "code == 400"

nouvelle_ferme() { # $1=lettre -> FARM_ID, ADMIN_EMAIL, TOKEN_ADMIN, FARM_UID
  TOKEN="$TOKEN_SA"
  ADMIN_EMAIL="admin-tar$1-$SUFFIXE@t.local"
  api POST /users/create "{\"fullName\":\"Tar$1$LETTRES\",\"email\":\"$ADMIN_EMAIL\",\"telephone\":\"7$(python3 -c 'import random; print(random.randint(1000000, 9999999))')\",\"farmName\":\"FermeTarif$1$SUFFIXE\",\"roles\":[\"COMPTABLE\"]}"
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

RACE="$(psql_run "select id from races order by id limit 1")"
if [ -z "$RACE" ]; then
  RACE="$(psql_run "insert into races (unique_id, nom, origine, type) values ('race-tar-$SUFFIXE','Race tarif','Locale','PONDEUSE') returning id" | head -1)"
fi
# Identifiant unique même dans un sous-shell $(...) (un compteur n'y survivrait pas).
uid() { echo "$(date +%s%N)$RANDOM"; }
projet() { # $1=farm $2=sujets $3=début (jours avant aujourd'hui) $4=objectif [$5=archive $6=fin prévue (jours avant)] -> id
  local n; n="$(uid)"
  local fin="null"; [ -n "${6:-}" ] && fin="current_date - ($6)"
  psql_run "insert into projets (unique_id, code, titre, objectif, race_id, farm_id, nb_sujets, date_debut, date_fin_prevue, removed, archive, created_at) values ('pt$n-$SUFFIXE','T${n: -6}','Projet tarif $n','$4',$RACE,$1,$2,current_date - ($3),$fin,false,${5:-false},now()) returning id" | head -1
}
mort() { # $1=projet $2=nombre $3=jours avant aujourd'hui [$4=removed]
  psql_run "insert into mortalites (unique_id, date, nombre_morts, projet_id, farm_id, removed, archive, created_at) values ('mt$(uid)-$SUFFIXE', current_date - ($3), $2, $1, (select farm_id from projets where id=$1), ${4:-false}, false, now())" >/dev/null
}
reforme() { # $1=projet $2=nombre $3=jours avant aujourd'hui
  psql_run "insert into reformes (unique_id, date, nombre_sujets, projet_id, farm_id, removed, archive, created_at) values ('rt$(uid)-$SUFFIXE', current_date - ($3), $2, $1, (select farm_id from projets where id=$1), false, false, now())" >/dev/null
}

echo "--- ferme A : sans Projet, puis 1 350 sujets, mortalité et réformes"
nouvelle_ferme A; FARM_A="$FARM_ID"; UID_A="$FARM_UID"; TOKEN_A="$TOKEN_ADMIN"; EMAIL_A="$ADMIN_EMAIL"
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "/abonnements/moi : anciens champs toujours là" \
  "all(k in d['data'] for k in ['uniqueId','farmUniqueId','farmNom','statutEffectif','enGrace','dateFin','joursRestants','periodicite','paiementEnAttente','estEssai','delaiGraceJours','dernierJourAcces','joursGraceRestants','suspendu','motifSuspension','suspenduLe'])"
check "sans Projet : 0 poule, minimum 5 000 / 50 000" \
  "$T['poulesComptees'] == 0 and $T['prixMensuel'] == 5000 and $T['prixAnnuel'] == 50000 and $T['minimumApplique'] and $T['dateMax'] is None and not $T['prixFixe'] and not $T['calculEnErreur']"

PA="$(projet "$FARM_A" 1350 60 PONTE)"
api GET /abonnements/moi
check "1 350 sujets : 8 100 par mois, 81 000 par an, max aujourd'hui" \
  "$T['poulesComptees'] == 1350 and $T['prixMensuel'] == 8100 and $T['prixAnnuel'] == 81000 and not $T['minimumApplique'] and $T['dateMax'] == '$(date +%F)'"

mort "$PA" 100 40
mort "$PA" 999 3 true    # mortalité supprimée : ignorée
api GET /abonnements/moi
check "100 morts il y a 40 jours (et une mortalité supprimée) : 1 250 poules, 7 500" \
  "$T['poulesComptees'] == 1250 and $T['prixMensuel'] == 7500"
reforme "$PA" 250 35
api GET /abonnements/moi
check "250 réformés il y a 35 jours : 1 000 poules, 6 000 / 60 000" \
  "$T['poulesComptees'] == 1000 and $T['prixMensuel'] == 6000 and $T['prixAnnuel'] == 60000"
mort "$PA" 200 5
reforme "$PA" 100 2
api GET /abonnements/moi
check "morts et réformés RÉCENTS : le plus haut des 30 jours reste 1 000 (atteint il y a 6 jours)" \
  "$T['poulesComptees'] == 1000 and $T['prixMensuel'] == 6000 and $T['dateMax'] == '$(date -d '-6 days' +%F)'"

echo "--- ferme B : bande de chair clôturée"
nouvelle_ferme B; FARM_B="$FARM_ID"; UID_B="$FARM_UID"; TOKEN_B="$TOKEN_ADMIN"
PB="$(projet "$FARM_B" 2000 50 REFORME true 10)"
mort "$PB" 30 35
TOKEN="$TOKEN_B"; api GET /abonnements/moi
check "chair clôturée il y a 10 jours (fin prévue) : encore comptée, 1 970 poules, 11 900" \
  "$T['poulesComptees'] == 1970 and $T['prixMensuel'] == 11900 and $T['dateMax'] == '$(date -d '-10 days' +%F)'"
psql_run "update projets set date_fin_prevue = current_date - 40 where id=$PB" >/dev/null
api GET /abonnements/moi
check "chair clôturée il y a 40 jours : 0 poule, minimum" "$T['poulesComptees'] == 0 and $T['prixMensuel'] == 5000 and $T['minimumApplique']"
BAT="$(psql_run "insert into batiments (unique_id, nom, capacite, statut, farm_id, removed, archive, created_at) values ('bt-$SUFFIXE','Poulailler tarif',3000,'DISPONIBLE',$FARM_B,false,false,now()) returning id" | head -1)"
psql_run "insert into occupations_batiments (date_entree, date_sortie, nb_sujets_dans_batiment, batiment_id, projet_id) values (current_date - 50, current_date - 10, 2000, $BAT, $PB)" >/dev/null
api GET /abonnements/moi
check "poulailler libéré il y a 10 jours (fin prévue plus ancienne) : comptée, 1 970" "$T['poulesComptees'] == 1970"
psql_run "update occupations_batiments set date_sortie = current_date - 40 where projet_id=$PB" >/dev/null
api GET /abonnements/moi
check "poulailler libéré il y a 40 jours : 0 poule" "$T['poulesComptees'] == 0"
P_FUTUR="$(projet "$FARM_B" 3000 -3 REFORME)"
P_SUPPR="$(projet "$FARM_B" 4000 10 REFORME)"
psql_run "update projets set removed=true where id=$P_SUPPR" >/dev/null
api GET /abonnements/moi
check "Projet qui commence dans 3 jours et Projet supprimé : pas comptés" "$T['poulesComptees'] == 0"
P_EN_COURS="$(projet "$FARM_B" 1500 20 REFORME false 5)"
api GET /abonnements/moi
check "Projet NON clôturé dont la fin prévue est passée : toujours en cours, 1 500" "$T['poulesComptees'] == 1500 and $T['prixMensuel'] == 9000"

echo "--- ferme C : deux Projets additionnés"
nouvelle_ferme C; FARM_C="$FARM_ID"; UID_C="$FARM_UID"; TOKEN_C="$TOKEN_ADMIN"
projet "$FARM_C" 300 100 PONTE >/dev/null
projet "$FARM_C" 534 10 REFORME >/dev/null
TOKEN="$TOKEN_C"; api GET /abonnements/moi
check "300 + 534 = 834 poules : 5 004 arrondi à 5 100" \
  "$T['poulesComptees'] == 834 and $T['montantParPoules'] == 5004 and $T['prixMensuel'] == 5100 and $T['prixAnnuel'] == 51000"

echo "--- réglages du SUPER_ADMIN"
TOKEN="$TOKEN_A"; api PUT /abonnements/config '{"prixParPoule":100}'
check "réglages refusés à un ADMIN de ferme (403)" "code == 403"
check_eq "réglages inchangés après le refus" "" "$(psql_run "select coalesce(prix_par_poule::text,'') from abonnement_config order by id limit 1")"
TOKEN="$TOKEN_SA"
api GET /abonnements/config
check "config : valeurs par défaut quand vides (6, 5 000, 2 mois, 100)" \
  "d['data']['prixParPoule'] == 6 and d['data']['prixMinimumMensuel'] == 5000 and d['data']['moisOffertsAnnuel'] == 2 and d['data']['arrondi'] == 100"
for corps in '{"prixParPoule":0}' '{"prixParPoule":-2}' '{"prixMinimumMensuel":-1}' '{"moisOffertsAnnuel":12}' '{"moisOffertsAnnuel":-1}' '{"arrondi":0}'; do
  api PUT /abonnements/config "$corps"
  check "réglage refusé : $corps" "code == 400"
done
api PUT /abonnements/config '{"prixParPoule":10,"prixMinimumMensuel":3000,"moisOffertsAnnuel":1,"arrondi":500}'
check "réglages changés : 10 FCFA, minimum 3 000, 1 mois offert, arrondi 500" \
  "code == 200 and d['data']['prixParPoule'] == 10 and d['data']['prixMinimumMensuel'] == 3000 and d['data']['moisOffertsAnnuel'] == 1 and d['data']['arrondi'] == 500"
TOKEN="$TOKEN_C"; api GET /abonnements/moi
check "ferme C avec les nouveaux réglages : 8 340 arrondi à 8 500, an = 11 mois" "$T['prixMensuel'] == 8500 and $T['prixAnnuel'] == 93500"
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "ferme A : 1 000 × 10 = 10 000, an 110 000" "$T['prixMensuel'] == 10000 and $T['prixAnnuel'] == 110000"
public "/abonnements/tarif-simulation?poules=200"
check "simulation avec les nouveaux réglages : 200 poules = 2 000, minimum 3 000" "d['data']['prixMensuel'] == 3000 and d['data']['minimumApplique']"
defauts
public "/abonnements/tarif-simulation?poules=1350"
check "réglages vidés : retour à 8 100" "d['data']['prixMensuel'] == 8100"

echo "--- tarif spécial (prix fixe)"
TOKEN="$TOKEN_A"; api POST "/admin/fermes/$UID_A/prix-fixe" '{"prixMensuelFixe":1000,"motif":"x"}'
check "tarif spécial refusé à un ADMIN de ferme (403)" "code == 403"
check_eq "tarif spécial : rien écrit par l'ADMIN de ferme" "" "$(psql_run "select coalesce(prix_mensuel_fixe::text,'') from abonnements where farm_id=$FARM_A")"
TOKEN="$TOKEN_SA"
api POST "/admin/fermes/$UID_A/prix-fixe" '{"prixMensuelFixe":7000}'
check "tarif spécial sans raison : refusé" "code == 400"
api POST "/admin/fermes/$UID_A/prix-fixe" '{"prixMensuelFixe":-5,"motif":"Premier client"}'
check "tarif spécial négatif : refusé" "code == 400"
api POST "/admin/fermes/$UID_A/prix-fixe" '{"prixMensuelFixe":null}'
check "retirer un tarif spécial absent : refusé" "code == 400"
api POST "/admin/fermes/$UID_A/prix-fixe" '{"prixMensuelFixe":7000,"motif":"Premier client, prix garanti"}'
check "tarif spécial fixé : 7 000 par mois, 70 000 par an, détail par poule gardé" \
  "code == 200 and d['data']['tarif']['prixFixe'] and d['data']['tarif']['prixMensuel'] == 7000 and d['data']['tarif']['prixAnnuel'] == 70000 and d['data']['tarif']['prixMensuelSelonPoules'] == 6000 and d['data']['tarif']['motifPrixFixe'] == 'Premier client, prix garanti' and d['data']['ferme']['prixFixe'] and d['data']['ferme']['prixMensuel'] == 7000"
check "journal : « Tarif spécial fixé », sans montant ni raison" \
  "any(e['categorie'] == 'TARIF' and e['action'].startswith('Tarif spécial fixé') and '7000' not in e['action'] and '7 000' not in e['action'] and 'Premier' not in e['action'] for e in d['data']['journal'])"
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "la ferme voit son tarif spécial" "$T['prixFixe'] and $T['prixMensuel'] == 7000 and $T['poulesComptees'] == 1000"
check "la raison du tarif spécial n'est PAS envoyée à la ferme (console seulement)" "$T['motifPrixFixe'] is None and 'Premier' not in json.dumps(d)"

echo "--- montant affiché différent du prix actuel"
api POST /abonnements/declarer-paiement '{"periodicite":"ANNUEL","moyenPaiement":"Orange Money","montantAffiche":60000}'
check "montant affiché (60 000) différent du prix actuel (70 000) : refusé" \
  "code == 400 and 'Le prix a été mis à jour, rechargez la page.' in str(d.get('errors'))"
check_eq "rien d'enregistré après ce refus" "0" "$(psql_run "select count(*) from paiements_abonnement p join abonnements a on a.id=p.abonnement_id where a.farm_id=$FARM_A")"

echo "--- déclaration : montant attendu enregistré"
api POST /abonnements/declarer-paiement '{"periodicite":"ANNUEL","moyenPaiement":"Orange Money","reference":"TAR-1","montantAffiche":70000}'
ok_cree "déclaration annuelle de A (tarif spécial, montant affiché identique)"
check "déclaration : montant = 70 000 (an du tarif spécial), attendu enregistré" \
  "d['data']['montant'] == 70000 and d['data']['montantAttendu'] == 70000 and d['data']['poulesComptees'] == 1000"
PAIEMENT_A="$(jval "d['data']['uniqueId']")"
check_eq "base : montant, montant attendu, poules" "70000|70000|1000" \
  "$(psql_run "select montant::int || '|' || montant_attendu::int || '|' || poules_comptees from paiements_abonnement where unique_id='$PAIEMENT_A'")"
TOKEN="$TOKEN_SA"; api GET "/abonnements/en-attente?page=0&size=200"
check "liste des paiements à valider : montant attendu et poules" \
  "any(p['uniqueId'] == '$PAIEMENT_A' and p['montantAttendu'] == 70000 and p['poulesComptees'] == 1000 for p in d['data']['data'])"
api POST "/admin/fermes/$UID_A/prix-fixe" '{"prixMensuelFixe":null}'
check "tarif spécial retiré : retour au prix par poule (6 000)" "code == 200 and not d['data']['tarif']['prixFixe'] and d['data']['tarif']['prixMensuel'] == 6000"
check_eq "déclaration en attente : montant inchangé après le retrait" "70000|70000" \
  "$(psql_run "select montant::int || '|' || montant_attendu::int from paiements_abonnement where unique_id='$PAIEMENT_A'")"
api POST "/abonnements/$PAIEMENT_A/valider"
ok_cree "validation du paiement de A"
check "validation : comportement inchangé (VALIDE, montant 70 000)" "d['data']['statut'] == 'VALIDE' and d['data']['montant'] == 70000"
# Crédit prépayé (2026-10-09) : une déclaration annuelle faite par un ancien client est
# validée comme une RECHARGE de son montant (70 000), avec le bonus de 20 % (dès 50 000) ;
# la formule « annuel » n'existe plus (periodicite reste MENSUEL, sans signification).
check_eq "abonnement de A : 70 000 + bonus 14 000 ajoutés au crédit (au lieu de « annuel »)" "MENSUEL|84000" \
  "$(psql_run "select a.periodicite||'|'||(select sum(montant)::bigint from mouvements_credit m where m.abonnement_id=a.id) from abonnements a where farm_id=$FARM_A")"

TOKEN="$TOKEN_C"
api POST /abonnements/declarer-paiement '{"periodicite":"MENSUEL","moyenPaiement":"Wave"}'
ok_cree "déclaration mensuelle de C"
check "déclaration mensuelle de C : 5 100 pour 834 poules" "d['data']['montant'] == 5100 and d['data']['montantAttendu'] == 5100 and d['data']['poulesComptees'] == 834"
PAIEMENT_C="$(jval "d['data']['uniqueId']")"
projet "$FARM_C" 5000 1 PONTE >/dev/null
api GET /abonnements/moi
check "C passe à 5 834 poules : nouveau prix 35 100 pour le PROCHAIN renouvellement" "$T['poulesComptees'] == 5834 and $T['prixMensuel'] == 35100"
check_eq "déclaration déjà faite : montant inchangé (5 100)" "5100|5100|834" \
  "$(psql_run "select montant::int || '|' || montant_attendu::int || '|' || poules_comptees from paiements_abonnement where unique_id='$PAIEMENT_C'")"
TOKEN="$TOKEN_SA"; api POST "/abonnements/$PAIEMENT_C/rejeter" '{"motif":"test"}'
ok_cree "rejet de la déclaration de C"

echo "--- rappels : le message contient le montant"
# Crédit prépayé : date_fin découle du crédit. Fin dans 7 jours, posée de façon cohérente :
# A (payante) a utilisé tout son crédit, dernier jour couvert dans 7 jours ; B reste en essai,
# son crédit commencerait au lendemain de la fin de l'essai.
psql_run "delete from mouvements_credit where abonnement_id=(select id from abonnements where farm_id=$FARM_A)" >/dev/null
psql_run "update abonnements set periodicite='MENSUEL', statut='ACTIF', date_fin=current_date + 7, credit_depuis=current_date - 30, credit_epuise_le=current_date + 8 where farm_id=$FARM_A" >/dev/null
psql_run "update abonnements set date_fin=current_date + 7, credit_depuis=current_date + 8, credit_epuise_le=current_date + 8 where farm_id=$FARM_B" >/dev/null
api POST "/abonnements/rappels?executer=false"
R="lambda u: [r for r in (d.get('data') or []) if r['farmUniqueId'] == u]"
# Crédit prépayé : A paie au crédit, le rappel parle de crédit épuisé, de son coût par mois
# au rythme du mois (moyenne des poules) et de « J'ai rechargé ».
check "rappel J7 de A (crédit) : crédit épuisé vers la date, coût par mois, « J'ai rechargé »" \
  "code == 200 and 'sera épuisé vers le' in ($R)('$UID_A')[0]['message'] and 'votre ferme coûte environ' in ($R)('$UID_A')[0]['message'] and 'J\\'ai rechargé' in ($R)('$UID_A')[0]['message']"
check "rappel J7 de B (essai, 1 500 poules) : 9 000 / 90 000" \
  "'Montant : 9 000 FCFA par mois (1 500 poules) ou 90 000 FCFA par an.' in ($R)('$UID_B')[0]['message']"
psql_run "update projets set removed=true where id=$P_EN_COURS" >/dev/null
api POST "/abonnements/rappels?executer=false"
check "rappel au minimum : « (0 poule, prix minimum) »" \
  "'Montant : 5 000 FCFA par mois (0 poule, prix minimum) ou 50 000 FCFA par an.' in ($R)('$UID_B')[0]['message']"
api POST "/admin/fermes/$UID_B/prix-fixe" '{"prixMensuelFixe":4000,"motif":"Ferme pilote"}'
ok_cree "tarif spécial de B"
api POST "/abonnements/rappels?executer=false"
check "rappel avec tarif spécial : « (tarif spécial) »" \
  "'Montant : 4 000 FCFA par mois (tarif spécial) ou 40 000 FCFA par an.' in ($R)('$UID_B')[0]['message']"
MAILS_AVANT=0
[ -n "$MAIL_SINK_DIR" ] && MAILS_AVANT="$(grep -l "$EMAIL_A" "$MAIL_SINK_DIR"/*.eml 2>/dev/null | wc -l)"
api POST "/abonnements/rappels?executer=true"
check "envoi : rappel J7 de A envoyé" "[(r['type'], r['envoye']) for r in ($R)('$UID_A')] == [('J7', True)]"
if [ -n "$MAIL_SINK_DIR" ]; then
  sleep 1
  DERNIER="$(grep -l "$EMAIL_A" "$MAIL_SINK_DIR"/*.eml 2>/dev/null | xargs -r ls -t | head -1)"
  check_eq "email de rappel reçu par l'ADMIN de A" "$((MAILS_AVANT + 1))" "$(grep -l "$EMAIL_A" "$MAIL_SINK_DIR"/*.eml 2>/dev/null | wc -l)"
  check_eq "email de rappel : le montant y figure" "1" "$(python3 - "$DERNIER" <<'PY'
import email, sys
from email import policy
m = email.message_from_file(open(sys.argv[1]), policy=policy.default)
texte = ""
for part in m.walk():
    if part.get_content_type() in ("text/plain", "text/html"):
        texte += part.get_content()
print(1 if "FCFA par mois" in texte and "coûte environ" in texte else 0)
PY
)"
fi
TOKEN="$TOKEN_A"; api GET /notifications/list
check "cloche de A (crédit) : rappel « crédit épuisé », invitation à recharger" \
  "any(n['type'] == 'ABONNEMENT' and 'crédit Cocorico sera épuisé' in n['message'] and 'Rechargez votre crédit' in n['message'] for n in d['data'])"

echo "--- clôture d'un Projet (date_cloture)"
nouvelle_ferme D; FARM_D="$FARM_ID"; UID_D="$FARM_UID"; TOKEN_D="$TOKEN_ADMIN"
PD="$(projet "$FARM_D" 800 60 REFORME false -30)"   # fin prévue dans 30 jours
PD_UID="$(psql_run "select unique_id from projets where id=$PD")"
TOKEN="$TOKEN_D"; api PUT "/projets/cloturer/$PD_UID"
check "clôture du Projet par l'API" "code == 200"
check_eq "date_cloture posée au jour de la clôture" "$(date +%F)" "$(psql_run "select date_cloture from projets where id=$PD")"
api GET /abonnements/moi
check "Projet clôturé aujourd'hui : compté (800)" "$T['poulesComptees'] == 800"
api PUT "/projets/rouvrir/$PD_UID"
check_eq "réouverture : date_cloture remise à vide" "" "$(psql_run "select coalesce(date_cloture::text,'') from projets where id=$PD")"
api PUT "/projets/cloturer/$PD_UID"
psql_run "update projets set date_cloture = current_date - 31, updated_at = now() where id=$PD" >/dev/null
api GET /abonnements/moi
check "clôturé il y a 31 jours, sans poulailler, fin prévue dans 30 jours : plus compté (0)" "$T['poulesComptees'] == 0 and $T['prixMensuel'] == 5000"
psql_run "update projets set date_cloture = current_date - 29 where id=$PD" >/dev/null
api GET /abonnements/moi
check "clôturé il y a 29 jours : encore compté (800)" "$T['poulesComptees'] == 800"
psql_run "update projets set date_fin_prevue = current_date - 60, date_cloture = current_date - 10 where id=$PD" >/dev/null
api GET /abonnements/moi
check "clôturé il y a 10 jours APRÈS sa fin prévue (il y a 60 jours) : encore compté (800)" "$T['poulesComptees'] == 800"
psql_run "update projets set date_cloture = null, date_fin_prevue = current_date + 30, updated_at = now() - interval '40 days' where id=$PD" >/dev/null
api GET /abonnements/moi
check "ancien Projet clôturé sans date_cloture : fin = la plus tôt entre fin prévue et dernière modification (0)" "$T['poulesComptees'] == 0"

echo "--- comptage des poules en échec (colonne renommée quelques secondes)"
TOKEN="$TOKEN_SA"; api GET /admin/tableau-de-bord
check "tableau de bord : aucun tarif en erreur avant le test" "d['data']['tarifsEnErreur'] == 0"
psql_run "alter table occupations_batiments rename column date_sortie to date_sortie_essai_tarif" >/dev/null
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "échec : /abonnements/moi répond quand même (point de sauvegarde), tarif marqué en erreur" \
  "code == 200 and d['data']['statutEffectif'] in ('ACTIF','ESSAI') and $T['calculEnErreur'] and not $T['prixFixe']"
api POST /abonnements/declarer-paiement '{"periodicite":"MENSUEL","moyenPaiement":"Wave"}'
check "échec : déclaration refusée, jamais le minimum enregistré en silence" \
  "code == 400 and \"Le prix n'a pas pu être calculé, réessayez dans un instant.\" in str(d.get('errors'))"
api GET /notifications/list
check "échec : la cloche garde le rappel, sans montant" \
  "code == 200 and any(n['type'] == 'ABONNEMENT' for n in d['data']) and not any('Montant' in n['message'] for n in d['data'] if n['type'] == 'ABONNEMENT')"
# Nouvelle période pour A et B (date de fin changée) : leur rappel J7 redevient prévu.
psql_run "update abonnements set date_fin = current_date + 6, credit_epuise_le = current_date + 7 where farm_id in ($FARM_A, $FARM_B)" >/dev/null
psql_run "update abonnements set credit_depuis = current_date + 7 where farm_id = $FARM_B" >/dev/null
TOKEN="$TOKEN_SA"; api POST "/abonnements/rappels?executer=false"
check "échec : rappel de A prévu, sans ligne de montant (crédit : « J'ai rechargé »)" "len(($R)('$UID_A')) == 1 and 'Montant' not in ($R)('$UID_A')[0]['message'] and 'coûte environ' not in ($R)('$UID_A')[0]['message'] and 'J\\'ai rechargé' in ($R)('$UID_A')[0]['message']"
check "échec : rappel de B (tarif spécial) garde son prix fixe" \
  "'Montant : 4 000 FCFA par mois (tarif spécial) ou 40 000 FCFA par an.' in ($R)('$UID_B')[0]['message']"
api GET /admin/tableau-de-bord
check "échec : revenu mensuel estimé signalé incomplet" "code == 200 and d['data']['tarifsEnErreur'] >= 1"
remettre_colonne
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "colonne remise : prix de nouveau calculé (6 000)" "not $T['calculEnErreur'] and $T['prixMensuel'] == 6000"

echo "--- console SUPER_ADMIN"
TOKEN="$TOKEN_SA"; api GET /admin/fermes
check "liste des fermes : poules comptées et prix mensuel" \
  "any(f['farmUniqueId'] == '$UID_A' and f['poulesComptees'] == 1000 and f['prixMensuel'] == 6000 and f['prixAnnuel'] == 60000 and not f['prixFixe'] for f in d['data']) and any(f['farmUniqueId'] == '$UID_C' and f['poulesComptees'] == 5834 and f['prixMensuel'] == 35100 for f in d['data']) and any(f['farmUniqueId'] == '$UID_B' and f['prixFixe'] and f['prixMensuel'] == 4000 for f in d['data'])"
check "liste des fermes : chaque ferme a un prix (jamais sous le minimum sans tarif spécial)" \
  "all(f['prixMensuel'] >= 5000 or f['prixFixe'] for f in d['data'])"
api GET "/admin/fermes/$UID_C"
check "fiche de C : détail du calcul (5 834 poules × 6 = 35 004, arrondi 35 100)" \
  "d['data']['tarif']['poulesComptees'] == 5834 and d['data']['tarif']['montantParPoules'] == 35004 and d['data']['tarif']['montantArrondi'] == 35100 and d['data']['tarif']['fenetreJours'] == 30"
api GET /abonnements/fermes
check "portail des abonnements : tarif de chaque ferme" "any(a['farmUniqueId'] == '$UID_C' and a['tarif']['prixMensuel'] == 35100 for a in d['data'])"

api GET /admin/tableau-de-bord; cp "$TMP/body" "$TMP/tdb0"
# Crédit prépayé : « Activer » de la console devient une recharge (montant obligatoire).
api POST "/admin/fermes/$UID_C/activer" '{"montant":35100,"moyenPaiement":"Wave"}'
ok_cree "recharge de C saisie dans la console (au lieu de l'activation mensuelle)"
api GET /admin/tableau-de-bord; cp "$TMP/body" "$TMP/tdb1"
ecart() { python3 -c 'import json,sys; a=json.load(open(sys.argv[1]))["data"]; b=json.load(open(sys.argv[2]))["data"]; print(round(b[sys.argv[3]]-a[sys.argv[3]],2))' "$1" "$2" "$3"; }
# Revenu estimé = coût d'un mois au rythme du MOIS EN COURS (moyenne des poules depuis le 1er,
# et non plus le plus haut des 30 jours) : 834 poules chaque jour + 5 000 depuis hier.
JOUR_DU_MOIS="$(date +%-d)"
COUT_C="$(python3 -c "import math; j=$JOUR_DU_MOIS; moy=834+5000*min(2,j)/j; print(float(max(math.ceil(6*moy/100-1e-9)*100, 5000)))")"
check_eq "revenu mensuel estimé : + coût de C au rythme du mois ($COUT_C)" "$COUT_C" "$(ecart "$TMP/tdb0" "$TMP/tdb1" revenuMensuelEstime)"
api POST "/admin/fermes/$UID_C/prix-fixe" '{"prixMensuelFixe":20000,"motif":"Remise"}'
ok_cree "tarif spécial de C"
api GET /admin/tableau-de-bord; cp "$TMP/body" "$TMP/tdb2"
check_eq "revenu mensuel estimé : tarif spécial de C (20 000 au lieu de $COUT_C)" "$(python3 -c "print(20000-$COUT_C)")" "$(ecart "$TMP/tdb1" "$TMP/tdb2" revenuMensuelEstime)"
# Crédit prépayé : la formule (mensuelle ou annuelle) ne compte plus pour le revenu estimé.
psql_run "update abonnements set periodicite='ANNUEL' where farm_id=$FARM_A" >/dev/null
api GET /admin/tableau-de-bord; cp "$TMP/body" "$TMP/tdb3"
check_eq "A passée en annuel : aucun effet sur le revenu estimé (crédit)" "0.0" "$(ecart "$TMP/tdb2" "$TMP/tdb3" revenuMensuelEstime)"

DEBUT=$(date +%s%N)
api GET /admin/fermes
FIN=$(date +%s%N)
NB_FERMES="$(jval "len(d['data'])")"
echo "-- /admin/fermes : $NB_FERMES fermes en $(( (FIN - DEBUT) / 1000000 )) ms"
check "liste des fermes en moins de 5 secondes" "code == 200 and $(( (FIN - DEBUT) / 1000000 )) < 5000"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
