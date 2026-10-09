#!/usr/bin/env bash
# Crédit prépayé (CreditService, commons/AbonnementCredit), 2026-10-09.
#
# Règles : la ferme recharge (« J'ai rechargé », validé par le SUPER_ADMIN), bonus de 20 %
# à partir de 50 000 FCFA ; à la fin de chaque mois, mensualité = 6 FCFA × moyenne des poules
# vivantes sur les jours du mois, arrondie au-dessus à 100, minimum 5 000 (au prorata des
# jours payés pour un premier mois partiel) ; tarif spécial à la place ; crédit à zéro ou
# moins après une mensualité : grâce puis blocage ; une recharge paie d'abord ce qui est dû.
# Abonnement.dateFin = dernier jour couvert (estimation), le reste de l'application inchangé.
#
# Vérifie :
#   - recharge sans bonus (30 000) et avec bonus (50 000 -> +10 000) ; déclaration refusée
#     (montant absent ou nul, deuxième déclaration en attente) ; bonus prévu affiché ;
#   - mensualités calculées à la main : ferme de ponte (1 000 puis 700 poules), bande de
#     chair de 45 jours sur deux mois (août et septembre plus chers, octobre redescend),
#     ferme de chair vide (minimum), premier mois partiel (15 jours sur 30) ;
#   - tâche rejouée : aucune mensualité en double (idempotence) ;
#   - crédit négatif : grâce (délai réglé à 10 jours), puis blocage (5 jours) ; recharge qui
#     paie la dette, débloque et fait repartir le crédit d'aujourd'hui ;
#   - estimation (« environ X mois ») et dateFin ; e-mails (recharge, mensualité, crédit bas) ;
#   - tarif spécial ; « à chiffrer » au-dessus de 10 000 poules ; simulation « sur devis » ;
#   - un seul essai par téléphone ou e-mail (variante de numéro, majuscules, compte supprimé) ;
#   - parrainage : 5 000 FCFA de crédit au parrain, une seule fois ; parrain suspendu : en
#     attente, donné à la recharge suivante après réactivation ;
#   - ajustement de la console (+ et -, raison obligatoire) ; recharge saisie dans la console ;
#   - refus pour un compte qui n'est pas SUPER_ADMIN ;
#   - /abonnements/moi garde ses anciens champs ; ferme d'avant le crédit convertie.
#
# Pré-requis : Postgres + backend démarrés, super-admin seedé (superadmin / change-me).
# Variables : BASE (défaut http://localhost:9196/diafarms/api/v1), PGHOST, PGPORT (55432),
# PGUSER (postgres), PGDATABASE (diafarms_credit), SUPERADMIN_ID, SUPERADMIN_PWD,
# MAIL_SINK_DIR (facultatif : dossier où le faux SMTP de test écrit les messages).
# Les dates de test sont calculées à partir d'aujourd'hui (mois précédent et celui d'avant).
# Sortie : une ligne OK/ECHEC par assertion ; code 0 si tout est OK, 1 sinon.
set -uo pipefail

BASE="${BASE:-http://localhost:9196/diafarms/api/v1}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_credit}"
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

# Réglages GLOBAUX touchés ici : remis tels qu'ils étaient en sortant.
CONFIG_INITIALE="$(psql_run "select coalesce(delai_grace_jours::text,'NULL')||','||coalesce(bonus_seuil::text,'NULL')||','||coalesce(bonus_pourcent::text,'NULL')||','||coalesce(seuil_sur_devis::text,'NULL')||','||coalesce(credit_parrainage::text,'NULL')||','||coalesce(prix_par_poule::text,'NULL')||','||coalesce(prix_minimum_mensuel::text,'NULL')||','||coalesce(arrondi_prix::text,'NULL') from abonnement_config order by id limit 1")"
restaurer() {
  if [ -n "$CONFIG_INITIALE" ]; then
    IFS=, read -r g bs bp sd cp pp pm ar <<< "$CONFIG_INITIALE"
    psql_run "update abonnement_config set delai_grace_jours=$g, bonus_seuil=$bs, bonus_pourcent=$bp, seuil_sur_devis=$sd, credit_parrainage=$cp, prix_par_poule=$pp, prix_minimum_mensuel=$pm, arrondi_prix=$ar" >/dev/null
  fi
  rm -rf "$TMP"
}
trap restaurer EXIT
psql_run "update abonnement_config set delai_grace_jours=5, bonus_seuil=null, bonus_pourcent=null, seuil_sur_devis=null, credit_parrainage=null, prix_par_poule=null, prix_minimum_mensuel=null, arrondi_prix=null" >/dev/null

api() { # $1=METHOD $2=chemin $3=JSON (facultatif) ; réponse dans $TMP/body, code dans $TMP/code
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${3:+-d "$3"} > "$TMP/code"
}
public() { # $1=METHOD $2=chemin $3=JSON, sans aucun jeton
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" -H 'Content-Type: application/json' ${3:+-d "$3"} > "$TMP/code"
}
code() { cat "$TMP/code"; }

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
    print("   code HTTP:", code, "réponse:", json.dumps(d, ensure_ascii=False)[:1200], file=sys.stderr)
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
tel_aleatoire() { echo "7$(python3 -c 'import random; print(random.randint(1000000, 9999999))')"; }

TOKEN_SA="$(login_mobile "$SUPERADMIN_ID" "$SUPERADMIN_PWD")"
[ -n "$TOKEN_SA" ] || { echo "ECHEC  connexion super-admin"; exit 1; }

# Mois de test : M1 = mois précédent, M2 = celui d'avant (dates calculées).
M1="$(date -d "$(date +%Y-%m-01) -1 month" +%Y-%m)"
M2="$(date -d "$(date +%Y-%m-01) -2 month" +%Y-%m)"
M3="$(date -d "$(date +%Y-%m-01) -3 month" +%Y-%m)"
J_M1="$(date -d "$M1-01 +1 month -1 day" +%d)"   # jours de M1
J_M2="$(date -d "$M2-01 +1 month -1 day" +%d)"   # jours de M2
CE_MOIS="$(date +%Y-%m)"
JOUR="$(date +%-d)"
J_CE_MOIS="$(date -d "$CE_MOIS-01 +1 month -1 day" +%d)"
FIN_CE_MOIS="$CE_MOIS-$J_CE_MOIS"
fin_mois() { date -d "$1-01 +1 month -1 day" +%F; } # $1=AAAA-MM
plus_mois() { date -d "$CE_MOIS-01 +$1 month" +%Y-%m; }

# Arrondi au-dessus à 100, comme le serveur.
arrondi100() { python3 -c "import math; print(int(math.ceil($1/100 - 1e-9)*100))"; }

nouvelle_ferme() { # $1=lettre -> FARM_ID, ADMIN_EMAIL, TOKEN_ADMIN, FARM_UID, ABO_ID
  TOKEN="$TOKEN_SA"
  ADMIN_EMAIL="admin-cr$1-$SUFFIXE@t.local"
  api POST /users/create "{\"fullName\":\"Cr$1$LETTRES\",\"email\":\"$ADMIN_EMAIL\",\"telephone\":\"$(tel_aleatoire)\",\"farmName\":\"FermeCredit$1$SUFFIXE\",\"roles\":[\"COMPTABLE\"]}"
  ok_cree "création de la ferme $1 et de son ADMIN"
  psql_run "UPDATE roles_users SET id_roles=(select id from roles where role='ADMIN') WHERE id_utilisateurs=(select id from utilisateurs where email='$ADMIN_EMAIL')" >/dev/null
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$ADMIN_EMAIL'" >/dev/null
  TOKEN_ADMIN="$(login_mobile "$ADMIN_EMAIL" "$PWD_TEST")"
  [ -n "$TOKEN_ADMIN" ] || { echo "ECHEC  connexion ADMIN de la ferme $1"; exit 1; }
  FARM_ID="$(psql_run "select farm_id from utilisateurs where email='$ADMIN_EMAIL'")"
  FARM_UID="$(psql_run "select unique_id from farms where id=$FARM_ID")"
  ABO_ID="$(psql_run "select id from abonnements where farm_id=$FARM_ID")"
  psql_run "UPDATE farms SET nom='FermeCredit$1$SUFFIXE' WHERE id=$FARM_ID" >/dev/null
}

RACE="$(psql_run "select id from races order by id limit 1")"
if [ -z "$RACE" ]; then
  RACE="$(psql_run "insert into races (unique_id, nom, origine, type) values ('race-cr-$SUFFIXE','Race crédit','Locale','PONDEUSE') returning id" | head -1)"
fi
uid() { echo "$(date +%s%N)$RANDOM"; }
projet() { # $1=farm $2=sujets $3=date début (AAAA-MM-JJ) $4=objectif [$5=date de clôture] -> id
  local n; n="$(uid)"
  local archive=false cloture=null
  [ -n "${5:-}" ] && { archive=true; cloture="'$5'"; }
  psql_run "insert into projets (unique_id, code, titre, objectif, race_id, farm_id, nb_sujets, date_debut, date_fin_prevue, date_cloture, removed, archive, created_at) values ('pc$n-$SUFFIXE','C${n: -6}','Projet crédit $n','$4',$RACE,$1,$2,'$3',$cloture,$cloture,false,$archive,now()) returning id" | head -1
}
mort() { # $1=projet $2=nombre $3=date
  psql_run "insert into mortalites (unique_id, date, nombre_morts, projet_id, farm_id, removed, archive, created_at) values ('mc$(uid)-$SUFFIXE', '$3', $2, $1, (select farm_id from projets where id=$1), false, false, now())" >/dev/null
}
# Le crédit a commencé à cette date (au lieu du lendemain de l'essai) : simule une ferme
# payante depuis ce jour-là. Crédit positif : aucun jour non couvert.
credit_depuis() { # $1=abonnement $2=date
  psql_run "update abonnements set credit_depuis='$2', credit_epuise_le=null, date_debut=least(date_debut, '$2') where id=$1" >/dev/null
}
solde() { psql_run "select coalesce(sum(montant),0)::bigint from mouvements_credit where abonnement_id=$1"; }
mensualite() { psql_run "select coalesce(string_agg(montant::bigint::text, ',' order by mois), '') from mouvements_credit where abonnement_id=$1 and type='MENSUALITE' and mois='$2'"; }
tache() { TOKEN="$TOKEN_SA"; api POST "/admin/credit/tache?executer=true"; }
ajuster() { # $1=farmUid $2=montant $3=motif
  TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$1/ajustement" "{\"montant\":$2,\"motif\":\"$3\"}"
}
declarer() { # $1=token $2=montant -> uniqueId du paiement dans DECL
  TOKEN="$1"; api POST /abonnements/declarer-paiement "{\"montant\":$2,\"moyenPaiement\":\"Orange Money\",\"reference\":\"REF$RANDOM\"}"
  DECL="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print((d.get("data") or {}).get("uniqueId") or "")' "$TMP/body")"
}
valider() { TOKEN="$TOKEN_SA"; api POST "/abonnements/$1/valider"; }

C="d['data']['credit']"

echo "--- simulation « sur devis » (publique)"
public GET "/abonnements/tarif-simulation?poules=10000"
check "10 000 poules : prix annoncé, pas sur devis (60 000 par mois)" "code == 200 and d['data']['prixMensuel'] == 60000 and not d['data']['surDevis'] and d['data']['seuilSurDevis'] == 10000"
public GET "/abonnements/tarif-simulation?poules=10001"
check "10 001 poules : sur devis" "code == 200 and d['data']['surDevis']"

echo "--- ferme A : recharges, avec et sans bonus"
nouvelle_ferme A; FARM_A="$FARM_ID"; UID_A="$FARM_UID"; TOKEN_A="$TOKEN_ADMIN"; ABO_A="$ABO_ID"; EMAIL_A="$ADMIN_EMAIL"
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "/abonnements/moi : anciens champs toujours là, plus le crédit" \
  "all(k in d['data'] for k in ['uniqueId','farmUniqueId','farmNom','statutEffectif','enGrace','dateFin','joursRestants','periodicite','paiementEnAttente','estEssai','delaiGraceJours','dernierJourAcces','joursGraceRestants','suspendu','motifSuspension','suspenduLe','tarif','credit'])"
check "nouvelle ferme : essai de 14 jours, crédit 0, le crédit commence après l'essai" \
  "d['data']['statutEffectif'] == 'ESSAI' and d['data']['estEssai'] and $C['solde'] == 0 and $C['avantCredit'] and $C['creditDepuis'] == '$(date -d '+15 days' +%F)' and d['data']['dateFin'] == '$(date -d '+14 days' +%F)' and not $C['aRecharger']"
TOKEN="$TOKEN_A"; api POST /abonnements/declarer-paiement '{"moyenPaiement":"Orange Money"}'
check "déclaration sans montant (ni ancienne formule) : refusée" "code == 400"
api POST /abonnements/declarer-paiement '{"montant":0,"moyenPaiement":"Orange Money"}'
check "déclaration de 0 FCFA : refusée" "code == 400"
api POST /abonnements/declarer-paiement '{"montant":30000}'
check "déclaration sans moyen : refusée" "code == 400"
declarer "$TOKEN_A" 30000
check "« J'ai rechargé » 30 000 : en attente, recharge, pas de bonus prévu" "code == 201 and d['data']['statut'] == 'EN_ATTENTE' and d['data']['recharge'] and d['data']['montant'] == 30000 and d['data']['bonusPrevu'] == 0"
D1="$DECL"
declarer "$TOKEN_A" 1000
check "deuxième déclaration pendant la première : refusée" "code == 400"
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "/moi : recharge en attente montrée" "d['data']['paiementEnAttente']['montant'] == 30000 and d['data']['paiementEnAttente']['bonusPrevu'] == 0"
TOKEN="$TOKEN_A"; api POST "/abonnements/$D1/valider"
check "un ADMIN de ferme ne peut pas valider (400)" "code == 400"
check_eq "rien écrit par ce refus" "0" "$(solde "$ABO_A")"
valider "$D1"
check "validation par le SUPER_ADMIN" "code == 200 and d['data']['statut'] == 'VALIDE' and d['data']['bonus'] == 0"
check_eq "compte A : une recharge de 30 000, pas de bonus" "RECHARGE:30000" "$(psql_run "select string_agg(type||':'||montant::bigint, ',' order by id) from mouvements_credit where abonnement_id=$ABO_A")"
TOKEN="$TOKEN_SA"; api GET /abonnements/en-attente
declarer "$TOKEN_A" 50000
check "« J'ai rechargé » 50 000 : bonus prévu 10 000" "code == 201 and d['data']['bonusPrevu'] == 10000"
D2="$DECL"
TOKEN="$TOKEN_SA"; api GET "/abonnements/en-attente?page=0&size=50"
check "liste des recharges à valider : bonus prévu 10 000" "any(p['uniqueId'] == '$D2' and p['bonusPrevu'] == 10000 for p in d['data']['data'])"
valider "$D2"
check "validation de 50 000 : bonus de 10 000" "code == 200 and d['data']['bonus'] == 10000"
check_eq "compte A : recharge, recharge, bonus ; solde 90 000" "RECHARGE:30000,RECHARGE:50000,BONUS:10000|90000" \
  "$(psql_run "select string_agg(type||':'||montant::bigint, ',' order by id)||'|'||sum(montant)::bigint from mouvements_credit where abonnement_id=$ABO_A")"
check_eq "bonus : libellé clair" "1" "$(psql_run "select count(*) from mouvements_credit where abonnement_id=$ABO_A and type='BONUS' and libelle like 'Bonus de 20 % offert sur la recharge de 50 000 FCFA'")"
TOKEN="$TOKEN_A"; api GET /abonnements/moi
check "ferme A après recharge : payante (plus en essai), crédit 90 000, crédit qui commence après l'essai" \
  "d['data']['statutEffectif'] == 'ACTIF' and not d['data']['estEssai'] and $C['solde'] == 90000 and $C['avantCredit'] and not $C['aRecharger']"
# Pas de poule : 5 000 par mois ; le crédit commence dans 15 jours.
check "estimation A : 18 mois au minimum (90 000 / 5 000)" "$C['coutMensuel'] == 5000 and $C['moisRestants'] == 18 and $C['phraseMois'] == 'environ 18 mois'"
TOKEN="$TOKEN_A"; api GET /abonnements/mouvements
check "historique de la ferme : 3 mouvements, plus récent d'abord, sans auteur" "code == 200 and len(d['data']) == 3 and d['data'][0]['type'] == 'BONUS' and all(m['auteurNom'] is None for m in d['data'])"
psql_run "update abonnement_config set bonus_seuil=30000, bonus_pourcent=10" >/dev/null
declarer "$TOKEN_A" 30000; D3="$DECL"
check "réglage du bonus changé (10 % dès 30 000) : bonus prévu 3 000" "d['data']['bonusPrevu'] == 3000"
TOKEN="$TOKEN_SA"; api POST "/abonnements/$D3/rejeter" '{"motif":"test"}'
check "recharge rejetée : rien n'est ajouté" "code == 200"
check_eq "solde A inchangé après le rejet" "90000" "$(solde "$ABO_A")"
psql_run "update abonnement_config set bonus_seuil=null, bonus_pourcent=null" >/dev/null

echo "--- ferme L : ponte, mensualités de $M2 et $M1 (moyenne calculée à la main)"
nouvelle_ferme L; FARM_L="$FARM_ID"; UID_L="$FARM_UID"; TOKEN_L="$TOKEN_ADMIN"; ABO_L="$ABO_ID"; EMAIL_L="$ADMIN_EMAIL"
ajuster "$UID_L" 20000 "Crédit de départ (test)"
check "ajustement +20 000 par le SUPER_ADMIN" "code == 200 and d['data']['credit']['solde'] == 20000"
PL="$(projet "$FARM_L" 1000 "$M3-01" PONTE)"
mort "$PL" 300 "$M1-21"     # 1 000 poules du 1er au 20, 700 ensuite
credit_depuis "$ABO_L" "$M2-01"
# M2 : 1 000 poules chaque jour -> 6 000. M1 : (20 × 1 000 + (J-20) × 700) / J.
ATT_M2=6000
ATT_M1="$(arrondi100 "6*(20*1000+($J_M1-20)*700)/$J_M1")"
TOKEN="$TOKEN_SA"; api POST "/admin/credit/tache"
check "simulation de la tâche : mensualités de L prévues, rien écrit" "code == 200 and any(p['farmUniqueId'] == '$UID_L' and p['mois'] == '$M1' and not p['ecrit'] for p in d['data']['mensualites'])"
check_eq "simulation : aucune mensualité écrite" "" "$(mensualite "$ABO_L" "$M1")"
tache
check "tâche exécutée" "code == 200"
check_eq "mensualité de $M2 (1 000 poules)" "-$ATT_M2" "$(mensualite "$ABO_L" "$M2")"
check_eq "mensualité de $M1 (moyenne avec 300 morts le 21)" "-$ATT_M1" "$(mensualite "$ABO_L" "$M1")"
check_eq "détail enregistré pour $M1 : moyenne, jours" "$(python3 -c "print(('%.2f' % ((20*1000+($J_M1-20)*700)/$J_M1)).rstrip('0').rstrip('.'))")|$J_M1|$J_M1" \
  "$(psql_run "select trim(trailing '.' from trim(trailing '0' from to_char(poules_moyenne, 'FM999999990.00')))||'|'||jours||'|'||jours_mois from mouvements_credit where abonnement_id=$ABO_L and mois='$M1'")"
SOLDE_L=$((20000 - ATT_M2 - ATT_M1))
check_eq "solde L après deux mensualités" "$SOLDE_L" "$(solde "$ABO_L")"
tache
check_eq "tâche rejouée : toujours 2 mensualités (idempotence)" "2" "$(psql_run "select count(*) from mouvements_credit where abonnement_id=$ABO_L and type='MENSUALITE'")"
check_eq "tâche rejouée : solde inchangé" "$SOLDE_L" "$(solde "$ABO_L")"
TOKEN="$TOKEN_L"; api GET /abonnements/moi
# Ce mois-ci : 700 poules -> 4 200, minimum 5 000 par mois.
python3 - "$SOLDE_L" > "$TMP/att" <<'PY'
import sys
s = float(sys.argv[1]); c = 5000
print(round(s / c * 2) / 2)
PY
MOIS_L="$(cat "$TMP/att")"
# Mois couverts : chaque mois qui commence avec un crédit positif.
FIN_L="$(python3 - "$SOLDE_L" "$CE_MOIS" <<'PY'
import sys, datetime, calendar
s = float(sys.argv[1]); y, m = map(int, sys.argv[2].split('-'))
dernier = None
while s > 0:
    s -= 5000; dernier = (y, m); m += 1
    if m == 13: y, m = y + 1, 1
y, m = dernier
print(datetime.date(y, m, calendar.monthrange(y, m)[1]).isoformat())
PY
)"
check "ce mois-ci : 700 poules en moyenne, 5 000 prévus (minimum)" "$C['moyennePoulesMois'] == 700 and $C['mensualitePrevue'] == 5000 and $C['coutMensuel'] == 5000"
check "estimation L : environ $MOIS_L mois, dateFin = fin du dernier mois couvert ($FIN_L)" "$C['moisRestants'] == $MOIS_L and $C['finEstimee'] == '$FIN_L' and d['data']['dateFin'] == '$FIN_L' and d['data']['statutEffectif'] == 'ACTIF'"
TOKEN="$TOKEN_SA"; api GET "/admin/fermes/$UID_L"
check "console, fiche L : crédit, compte complet avec auteur, dernières mensualités" \
  "d['data']['credit']['solde'] == $SOLDE_L and len([m for m in d['data']['mouvements'] if m['type'] == 'MENSUALITE']) == 2 and any(m['type'] == 'AJUSTEMENT' and m['auteurNom'] and m['libelle'] == 'Crédit de départ (test)' for m in d['data']['mouvements']) and d['data']['ferme']['credit'] == $SOLDE_L"

echo "--- ferme B : 2 000 pondeuses + une bande de 10 000 chairs sur 45 jours ($M2 et $M1)"
nouvelle_ferme B; FARM_B="$FARM_ID"; UID_B="$FARM_UID"; TOKEN_B="$TOKEN_ADMIN"; ABO_B="$ABO_ID"
ajuster "$UID_B" 200000 "Crédit de départ (test)"
projet "$FARM_B" 2000 "$M3-01" PONTE >/dev/null
# Bande : du (fin de M2 - 14) au dernier jour de M1 = 15 jours en M2 + J_M1 jours en M1.
DEB_BANDE="$(date -d "$(fin_mois "$M2") -14 days" +%F)"
projet "$FARM_B" 10000 "$DEB_BANDE" REFORME "$(fin_mois "$M1")" >/dev/null
credit_depuis "$ABO_B" "$M2-01"
tache
ATT_B2="$(arrondi100 "6*(2000*$J_M2+10000*15)/$J_M2")"
ATT_B1="$(arrondi100 "6*(2000+10000)")"
check_eq "B, $M2 : (2 000 × $J_M2 + 10 000 × 15) / $J_M2 poules en moyenne" "-$ATT_B2" "$(mensualite "$ABO_B" "$M2")"
check_eq "B, $M1 : 12 000 poules en moyenne, plus cher" "-$ATT_B1" "$(mensualite "$ABO_B" "$M1")"
TOKEN="$TOKEN_B"; api GET /abonnements/moi
check "B ce mois-ci : la bande est finie, on redescend à 2 000 poules (12 000 par mois)" "$C['moyennePoulesMois'] == 2000 and $C['coutMensuel'] == 12000 and $C['mensualitePrevue'] == 12000"
check "B : plus de 10 000 poules sur 30 jours sans tarif spécial = à chiffrer, mensualité selon la règle" "$C['aChiffrer'] and d['data']['tarif']['surDevis']"
TOKEN="$TOKEN_SA"; api GET /admin/fermes
check "console, liste : B « à chiffrer »" "any(f['farmUniqueId'] == '$UID_B' and f['aChiffrer'] for f in d['data'])"
api GET /admin/tableau-de-bord
check "tableau de bord : fermes à chiffrer comptées" "d['data']['aChiffrer'] >= 1"

echo "--- ferme E : chair vide entre deux bandes (minimum), crédit négatif, grâce, blocage, recharge"
nouvelle_ferme E; FARM_E="$FARM_ID"; UID_E="$FARM_UID"; TOKEN_E="$TOKEN_ADMIN"; ABO_E="$ABO_ID"; EMAIL_E="$ADMIN_EMAIL"
ajuster "$UID_E" 3000 "Crédit de départ (test)"
projet "$FARM_E" 5000 "$M3-01" REFORME "$M3-20" >/dev/null   # bande finie avant
credit_depuis "$ABO_E" "$M1-01"
tache
check_eq "E, $M1 : aucune poule, minimum 5 000" "-5000" "$(mensualite "$ABO_E" "$M1")"
check_eq "E : crédit négatif (3 000 - 5 000)" "-2000" "$(solde "$ABO_E")"
check_eq "E : premier jour non couvert = 1er de ce mois, dateFin = dernier jour de $M1" "$CE_MOIS-01|$(fin_mois "$M1")" \
  "$(psql_run "select credit_epuise_le||'|'||date_fin from abonnements where id=$ABO_E")"
psql_run "update abonnement_config set delai_grace_jours=$((JOUR + 2))" >/dev/null
TOKEN="$TOKEN_E"; api GET /abonnements/moi
check "E avec une grâce de $((JOUR + 2)) jours : en grâce, à recharger, dette 2 000" "d['data']['enGrace'] and d['data']['statutEffectif'] == 'ACTIF' and $C['aRecharger'] and $C['dette'] == 2000"
psql_run "update abonnement_config set delai_grace_jours=$((JOUR - 2 > 0 ? JOUR - 2 : 0))" >/dev/null
api GET /abonnements/moi
check "E, grâce passée : bloquée (EXPIRE)" "d['data']['statutEffectif'] == 'EXPIRE' and not d['data']['enGrace']"
declarer "$TOKEN_E" 10000; DE="$DECL"
check "E bloquée peut quand même déclarer une recharge" "code == 201"
valider "$DE"
check "recharge de E validée" "code == 200"
check_eq "E : la recharge paie d'abord les 2 000 dus (solde 8 000)" "8000" "$(solde "$ABO_E")"
check_eq "E : libellé de la recharge" "1" "$(psql_run "select count(*) from mouvements_credit where abonnement_id=$ABO_E and type='RECHARGE' and libelle like '%2 000 FCFA pour payer ce qui était dû%'")"
TOKEN="$TOKEN_E"; api GET /abonnements/moi
check "E débloquée tout de suite, crédit reparti d'aujourd'hui" "d['data']['statutEffectif'] == 'ACTIF' and not d['data']['enGrace'] and $C['creditDepuis'] == '$(date +%F)' and not $C['aRecharger']"
FIN_E="$(python3 - "$JOUR" "$J_CE_MOIS" "$CE_MOIS" <<'PY'
import sys, datetime, calendar
j, jm, cm = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
s = 8000 - 5000 * (jm - j + 1) / jm
y, m = map(int, cm.split('-'))
while s > 0:
    m += 1
    if m == 13: y, m = y + 1, 1
    s -= 5000
print(datetime.date(y, m, calendar.monthrange(y, m)[1]).isoformat())
PY
)"
check "E : nouvelle fin estimée ($FIN_E)" "d['data']['dateFin'] == '$FIN_E'"
psql_run "update abonnement_config set delai_grace_jours=5" >/dev/null

echo "--- ferme P : premier mois partiel (crédit depuis le 16 de $M1)"
nouvelle_ferme P; FARM_P="$FARM_ID"; UID_P="$FARM_UID"; TOKEN_P="$TOKEN_ADMIN"; ABO_P="$ABO_ID"; EMAIL_P="$ADMIN_EMAIL"
ajuster "$UID_P" 6000 "Crédit de départ (test)"
projet "$FARM_P" 1000 "$M3-01" PONTE >/dev/null
credit_depuis "$ABO_P" "$M1-16"
tache
JP=$((10#$J_M1 - 15))
ATT_P="$(arrondi100 "max(6*1000, 5000)*$JP/$J_M1")"
check_eq "P, $M1 : $JP jours sur $J_M1, 1 000 poules -> 6 000 × $JP / $J_M1" "-$ATT_P" "$(mensualite "$ABO_P" "$M1")"
check_eq "P : jours payés enregistrés" "$JP|$J_M1" "$(psql_run "select jours||'|'||jours_mois from mouvements_credit where abonnement_id=$ABO_P and mois='$M1'")"
check_eq "P : rien pour $M2 (avant le crédit)" "" "$(mensualite "$ABO_P" "$M2")"

echo "--- ferme T : tarif spécial"
nouvelle_ferme T; FARM_T="$FARM_ID"; UID_T="$FARM_UID"; TOKEN_T="$TOKEN_ADMIN"; ABO_T="$ABO_ID"
ajuster "$UID_T" 10000 "Crédit de départ (test)"
projet "$FARM_T" 1000 "$M3-01" PONTE >/dev/null
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_T/prix-fixe" '{"prixMensuelFixe":3000,"motif":"Premier client"}'
check "tarif spécial 3 000 fixé" "code == 200"
credit_depuis "$ABO_T" "$M1-01"
tache
check_eq "T, $M1 : tarif spécial 3 000 (au lieu de 6 000)" "-3000" "$(mensualite "$ABO_T" "$M1")"
check_eq "T : mensualité marquée tarif spécial" "t" "$(psql_run "select prix_fixe from mouvements_credit where abonnement_id=$ABO_T and mois='$M1'")"
TOKEN="$TOKEN_T"; api GET /abonnements/moi
FIN_T="$(python3 - "$CE_MOIS" <<'PY'
import sys, datetime, calendar
y, m = map(int, sys.argv[1].split('-')); s = 7000
while True:
    s -= 3000
    if s <= 0: break
    m += 1
    if m == 13: y, m = y + 1, 1
print(datetime.date(y, m, calendar.monthrange(y, m)[1]).isoformat())
PY
)"
check "T : 7 000 restants, 3 000 par mois, environ 2 mois et demi, fin $FIN_T" "$C['solde'] == 7000 and $C['coutMensuel'] == 3000 and $C['prixFixe'] and $C['phraseMois'] == 'environ 2 mois et demi' and d['data']['dateFin'] == '$FIN_T'"
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_T/prix-fixe" '{"prixMensuelFixe":null}'
TOKEN="$TOKEN_T"; api GET /abonnements/moi
check "T, tarif spécial retiré : 1 000 poules, 6 000 par mois, fin recalculée" "$C['coutMensuel'] == 6000 and not $C['prixFixe'] and d['data']['dateFin'] == '$(fin_mois "$(plus_mois 1)")'"

echo "--- crédit bas (rappel) et e-mails"
# P : 6 000 - $ATT_P ; ce mois-ci 6 000 -> le crédit couvre ce mois seulement.
TOKEN="$TOKEN_P"; api GET /abonnements/moi
check "P : crédit bas (moins de 30 jours), fin à la fin du mois" "$C['creditBas'] and d['data']['dateFin'] == '$FIN_CE_MOIS'"
JR_P=$(( ( $(date -d "$FIN_CE_MOIS" +%s) - $(date -d "$(date +%F)" +%s) ) / 86400 ))
TOKEN="$TOKEN_SA"; api POST "/abonnements/rappels?executer=false"
if [ "$JR_P" -gt 7 ] && [ "$JR_P" -lt 30 ]; then
  check "rappels : « crédit bas » prévu pour P, avec le montant du mois et la recharge" "any(r['farmUniqueId'] == '$UID_P' and r['type'] == 'CREDIT_BAS' and 'J\\'ai rechargé' in r['message'] and 'bientôt épuisé' in r['sujet'] for r in d['data'])"
  api POST "/abonnements/rappels?executer=true"
  api POST "/abonnements/rappels?executer=true"
  check_eq "« crédit bas » envoyé une seule fois" "1" "$(psql_run "select count(*) from abonnement_rappels where abonnement_id=$ABO_P and type='CREDIT_BAS'")"
elif [ "$JR_P" -le 7 ]; then
  check "rappels (fin de mois proche) : J-7 ou J-1 au texte du crédit pour P" "any(r['farmUniqueId'] == '$UID_P' and r['type'] in ('J7','J1') and 'crédit' in r['sujet'] for r in d['data'])"
fi

mails_de() {
  python3 - "$MAIL_SINK_DIR" "$1" <<'PY'
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
    res.append([sujet, texte])
print(json.dumps(res, ensure_ascii=False))
PY
}
if [ -n "$MAIL_SINK_DIR" ]; then
  sleep 2
  mails_de "$EMAIL_A" > "$TMP/body"; echo 200 > "$TMP/code"
  check "e-mail à A : recharge de 50 000 validée avec le bonus de 10 000" "any('50 000 FCFA est validée' in m[0] and 'Bonus offert : 10 000 FCFA' in m[1] and 'Crédit restant : 90 000 FCFA' in m[1] for m in d)"
  mails_de "$EMAIL_L" > "$TMP/body"
  check "e-mail à L : mensualités avec le détail (moyenne, jours) et le crédit restant" "any('ensualité' in m[0] and 'poules en moyenne sur' in m[1] and 'Crédit restant' in m[1] for m in d)"
  mails_de "$EMAIL_E" > "$TMP/body"
  check "e-mail à E : crédit épuisé, comment recharger" "any(m[0].startswith('Crédit épuisé') and 'il reste 2 000 FCFA à payer' in m[1] and \"J'ai rechargé\" in m[1] for m in d)"
  check "aucun tiret long ni moyen dans ces e-mails" "all('—' not in m[0]+m[1] and '–' not in m[0]+m[1] for m in d)"
else
  echo "(MAIL_SINK_DIR non défini : vérifications des e-mails ignorées)"
fi

echo "--- un seul essai gratuit par téléphone ou e-mail"
TEL1="$(tel_aleatoire)"
public POST /users/create "{\"fullName\":\"Essai Un\",\"email\":\"essai1-$SUFFIXE@t.local\",\"telephone\":\"$TEL1\",\"farmName\":\"Essai1$SUFFIXE\",\"roles\":[\"ADMIN\"]}"
check "inscription 1 : essai donné" "code in (200, 201) and d['data']['essaiRefuse'] is False"
public POST /users/create "{\"fullName\":\"Essai Deux\",\"email\":\"essai2-$SUFFIXE@t.local\",\"telephone\":\"+223 ${TEL1:0:2} ${TEL1:2:2} ${TEL1:4:2} ${TEL1:6:2}\",\"farmName\":\"Essai2$SUFFIXE\",\"roles\":[\"ADMIN\"]}"
check "inscription 2, même numéro écrit autrement (+223 ..) : pas d'essai" "code in (200, 201) and d['data']['essaiRefuse'] is True"
FARM_R2="$(psql_run "select farm_id from utilisateurs where email='essai2-$SUFFIXE@t.local'")"
check_eq "inscription 2 : abonnement sans essai, tout de suite à recharger" "true|$(date +%F)" "$(psql_run "select essai_refuse||'|'||credit_depuis from abonnements where farm_id=$FARM_R2")"
UID_R2="$(psql_run "select unique_id from farms where id=$FARM_R2")"
# Ferme bloquée : la connexion mobile est refusée (comme avant) ; on regarde par la console.
TOKEN="$TOKEN_SA"; api GET "/admin/fermes/$UID_R2"
check "ferme sans essai : bloquée (page Abonnement seulement), pas présentée comme un essai" "d['data']['ferme']['statut'] == 'EXPIRE' and d['data']['credit']['essaiRefuse'] and d['data']['credit']['aRecharger']"
public POST /users/create "{\"fullName\":\"Essai Trois\",\"email\":\"ESSAI1-$SUFFIXE@T.LOCAL\",\"telephone\":\"$(tel_aleatoire)\",\"farmName\":\"Essai3$SUFFIXE\",\"roles\":[\"ADMIN\"]}"
check "inscription 3, même e-mail en majuscules : pas d'essai" "code in (200, 201) and d['data']['essaiRefuse'] is True"
TEL4="$(tel_aleatoire)"
public POST /users/create "{\"fullName\":\"Essai Quatre\",\"email\":\"essai4-$SUFFIXE@t.local\",\"telephone\":\"$TEL4\",\"farmName\":\"Essai4$SUFFIXE\",\"roles\":[\"ADMIN\"]}"
check "inscription 4 : essai donné" "d['data']['essaiRefuse'] is False"
# Compte supprimé (et sa ferme vidée) : le numéro reste mémorisé.
psql_run "delete from logs where user_id=(select id from utilisateurs where email='essai4-$SUFFIXE@t.local'); delete from roles_users where id_utilisateurs=(select id from utilisateurs where email='essai4-$SUFFIXE@t.local'); delete from utilisateurs where email='essai4-$SUFFIXE@t.local'" >/dev/null
public POST /users/create "{\"fullName\":\"Essai Cinq\",\"email\":\"essai5-$SUFFIXE@t.local\",\"telephone\":\"$TEL4\",\"farmName\":\"Essai5$SUFFIXE\",\"roles\":[\"ADMIN\"]}"
check "inscription 5, numéro d'un compte supprimé : pas d'essai" "code in (200, 201) and d['data']['essaiRefuse'] is True"
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_R2/activer" '{"montant":5000,"moyenPaiement":"Wave"}'
check "ferme sans essai qui recharge 5 000 : débloquée, le crédit commence aujourd'hui" "code == 200 and d['data']['ferme']['statut'] == 'ACTIF' and d['data']['credit']['creditDepuis'] == '$(date +%F)' and d['data']['credit']['solde'] == 5000"

echo "--- parrainage : 5 000 FCFA de crédit au parrain"
nouvelle_ferme R; FARM_R="$FARM_ID"; UID_R="$FARM_UID"; TOKEN_R="$TOKEN_ADMIN"; ABO_R="$ABO_ID"
TOKEN="$TOKEN_R"; api GET /croissance/parrainage
CODE_R="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["code"])' "$TMP/body")"
check "code de parrainage, récompense annoncée 5 000" "code == 200 and d['data']['creditParFerme'] == 5000"
nouvelle_ferme F; FARM_F="$FARM_ID"; TOKEN_F="$TOKEN_ADMIN"
TOKEN="$TOKEN_F"; api POST /croissance/parrainage/code "{\"code\":\"$CODE_R\"}"
check "F saisit le code de R" "code == 200"
TOKEN="$TOKEN_R"; api POST /croissance/parrainage/code "{\"code\":\"$CODE_R\"}"
check "R ne peut pas se parrainer elle-même" "code == 400"
declarer "$TOKEN_F" 20000; valider "$DECL"
check_eq "R : parrainage de 5 000 FCFA dans son compte" "PARRAINAGE:5000" "$(psql_run "select string_agg(type||':'||montant::bigint, ',') from mouvements_credit where abonnement_id=$ABO_R")"
check_eq "parrainage : récompense en crédit enregistrée" "5000|" "$(psql_run "select recompense_credit::bigint||'|'||coalesce(recompense_jours::text,'') from parrainages where filleul_farm_id=$FARM_F")"
declarer "$TOKEN_F" 20000; valider "$DECL"
check_eq "deuxième recharge de F : pas de deuxième récompense" "1" "$(psql_run "select count(*) from mouvements_credit where abonnement_id=$ABO_R and type='PARRAINAGE'")"
TOKEN="$TOKEN_R"; api GET /croissance/parrainage
check "R : 5 000 FCFA gagnés" "d['data']['creditGagne'] == 5000 and d['data']['filleuls'] == 1"
nouvelle_ferme S; UID_S="$FARM_UID"; TOKEN_S="$TOKEN_ADMIN"; ABO_S="$ABO_ID"
TOKEN="$TOKEN_S"; api GET /croissance/parrainage
CODE_S="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["code"])' "$TMP/body")"
nouvelle_ferme G; FARM_G="$FARM_ID"; TOKEN_G="$TOKEN_ADMIN"
TOKEN="$TOKEN_G"; api POST /croissance/parrainage/code "{\"code\":\"$CODE_S\"}"
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_S/suspendre" '{"motif":"Test parrainage"}'
declarer "$TOKEN_G" 10000; valider "$DECL"
check_eq "parrain S suspendu : récompense en attente" "0|" "$(psql_run "select (select count(*) from mouvements_credit where abonnement_id=$ABO_S)||'|'||coalesce(recompense_le::text,'') from parrainages where filleul_farm_id=$FARM_G")"
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_S/reactiver"
declarer "$TOKEN_G" 10000; valider "$DECL"
check_eq "S réactivée : récompense donnée à la recharge suivante, une fois" "PARRAINAGE:5000" "$(psql_run "select string_agg(type||':'||montant::bigint, ',') from mouvements_credit where abonnement_id=$ABO_S")"

echo "--- console : ajustement, recharge reçue, réglages, refus"
TOKEN="$TOKEN_SA"; api POST "/admin/fermes/$UID_A/ajustement" '{"montant":-5000}'
check "ajustement sans raison : refusé" "code == 400"
api POST "/admin/fermes/$UID_A/ajustement" '{"montant":0,"motif":"x"}'
check "ajustement de 0 : refusé" "code == 400"
ajuster "$UID_A" -5000 "Erreur de saisie corrigée"
check "ajustement -5 000 : solde 85 000" "code == 200 and d['data']['credit']['solde'] == 85000 and d['data']['mouvements'][0]['type'] == 'AJUSTEMENT' and d['data']['mouvements'][0]['montant'] == -5000"
api GET "/admin/journal?ferme=$UID_A"
check "journal : ajustement sans montant ni raison" "any(e['categorie'] == 'AJUSTEMENT' and '5000' not in e['action'] and '5 000' not in e['action'] and 'Erreur' not in e['action'] for e in d['data']['data'])"
api POST "/admin/fermes/$UID_A/activer" '{"periodicite":"MENSUEL","mois":1}'
check "« Recharger » dans la console sans montant : refusé (le crédit est en FCFA)" "code == 400"
api POST "/admin/fermes/$UID_A/activer" '{"montant":60000,"moyenPaiement":"Espèces","reference":"Reçu 12"}'
check "recharge reçue hors application : 60 000 + bonus 12 000" "code == 200 and d['data']['credit']['solde'] == 157000 and any(p['horsApplication'] and p['bonus'] == 12000 for p in d['data']['paiements'])"
api PUT /abonnements/config '{"bonusSeuil":-1}'
check "réglage refusé : seuil du bonus négatif" "code == 400"
api PUT /abonnements/config '{"bonusPourcent":150}'
check "réglage refusé : bonus de 150 %" "code == 400"
api PUT /abonnements/config '{"seuilSurDevis":0}'
check "réglage refusé : seuil sur devis 0" "code == 400"
api PUT /abonnements/config '{"seuilSurDevis":20000,"creditParrainage":7000,"bonusSeuil":40000,"bonusPourcent":25}'
check "réglages du crédit changés" "code == 200 and d['data']['seuilSurDevis'] == 20000 and d['data']['creditParrainage'] == 7000 and d['data']['bonusSeuil'] == 40000 and d['data']['bonusPourcent'] == 25"
public GET "/abonnements/tarif-simulation?poules=15000"
check "simulation 15 000 poules avec le seuil à 20 000 : prix annoncé" "not d['data']['surDevis'] and d['data']['seuilSurDevis'] == 20000"
psql_run "update abonnement_config set seuil_sur_devis=null, credit_parrainage=null, bonus_seuil=null, bonus_pourcent=null" >/dev/null
TOKEN="$TOKEN_A"
api POST "/admin/fermes/$UID_A/ajustement" '{"montant":1000,"motif":"x"}'
check "ajustement refusé à un ADMIN de ferme (403)" "code == 403"
api POST "/admin/credit/tache?executer=true"
check "tâche du crédit refusée à un ADMIN de ferme (403)" "code == 403"
api PUT /abonnements/config '{"bonusPourcent":90}'
check "réglages du crédit refusés à un ADMIN de ferme (403)" "code == 403"
api POST "/admin/fermes/$UID_A/activer" '{"montant":1000,"moyenPaiement":"Wave"}'
check "recharge de la console refusée à un ADMIN de ferme (403)" "code == 403"
check_eq "rien écrit par ces refus (solde A)" "157000" "$(solde "$ABO_A")"
TOKEN="$TOKEN_SA"; api GET /admin/tableau-de-bord
check "tableau de bord : revenu estimé = coût des fermes payantes, crédit total, fermes à recharger" "d['data']['revenuMensuelEstime'] > 0 and d['data']['creditTotal'] > 0 and 'aRecharger' in d['data']"

echo "--- ferme d'avant le crédit : convertie par la tâche, sa période payée gardée"
nouvelle_ferme V; FARM_V="$FARM_ID"; TOKEN_V="$TOKEN_ADMIN"; ABO_V="$ABO_ID"
FIN_V="$(date -d '+20 days' +%F)"
psql_run "update abonnements set credit_depuis=null, credit_epuise_le=null, periodicite='ANNUEL', statut='ACTIF', date_fin='$FIN_V' where id=$ABO_V" >/dev/null
tache
check_eq "V convertie : crédit à partir du lendemain de sa fin, fin inchangée" "$(date -d "$FIN_V +1 day" +%F)|$(date -d "$FIN_V +1 day" +%F)|$FIN_V|0" \
  "$(psql_run "select credit_depuis||'|'||credit_epuise_le||'|'||date_fin||'|'||(select count(*) from mouvements_credit where abonnement_id=$ABO_V) from abonnements where id=$ABO_V")"
TOKEN="$TOKEN_V"; api GET /abonnements/moi
check "V : toujours active jusqu'à sa fin payée, crédit pas encore commencé" "d['data']['statutEffectif'] == 'ACTIF' and d['data']['dateFin'] == '$FIN_V' and $C['avantCredit'] and $C['solde'] == 0 and not $C['aRecharger']"

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
