#!/usr/bin/env bash
# Reporting calculé côté serveur (GET /reporting, voir ReportingService), 2026-10-03.
#
# Une ferme NEUVE est créée à chaque passage (chiffres de toute la ferme calculables à la
# main) avec deux Projets :
#   A : PONTE, 1 000 sujets, depuis le 2026-01-01 ;
#   C : REFORME (chair), 500 sujets, depuis le 2026-02-01.
# Période testée : mars 2026 (31 jours), période précédente : 2026-01-29 au 2026-02-28.
#
#   Février : A 10 morts (10/02), 500 œufs (15/02), dépense A 3 000 (20/02).
#   Mars    : A 20 morts (05/03) + 5 (20/03), œufs 600 dont 10 cassés (02/03) + 630 (03/03),
#             achat d'aliment A 300 kg 45 000 (01/03), consommé 120 kg (04/03) + 126 kg (06/03) ;
#             C 5 morts (10/03), achat d'aliment C 100 kg 10 000 (01/03), consommé 50 kg (11/03).
#             Vente à un client K1 de 60 œufs de A : 6 000 (10/03), K1 paie 2 500 (18/03).
#             Vente sans client de 30 œufs : 3 000, rapporté 2 700 (12/03).
#             Fientes de A : 1 200 (14/03). Entrée « Subvention » de A : 1 500 (06/03).
#             Dépenses : A 8 000 (08/03), un site 4 000 (09/03), toute la ferme 6 000 (11/03),
#             C 2 000 (15/03). Client K2 : avance 1 000 (19/03), remboursé 400 (25/03).
#             Salaires de mars payés le 31/03 : 31 000 (employé affecté à A tout le mois)
#             et 15 500 (non affecté : partagé entre A et C au prorata des sujets vivants
#             fin mars, 965 et 495).
#
# Vérifie : chaque chiffre calculé à la main, le filtre Projet, le périmètre RESPONSABLE,
# la cohérence avec la fiche Projet (/projets/{uid}/encaissement, transactions du
# Projet, /main-oeuvre/projets/{uid}/cout) sur toutes les dates, la période précédente,
# une période vide et un Projet sans ponte (taux de ponte vide).
#
# Pré-requis : Postgres + backend démarrés, super-admin seedé (superadmin / change-me).
# Variables : BASE (défaut http://localhost:9199/diafarms/api/v1), PGHOST, PGPORT (55432),
# PGUSER (postgres), PGDATABASE (diafarms_scen), SUPERADMIN_ID, SUPERADMIN_PWD.
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
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAIL=0

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
    print("   code HTTP:", code, "réponse:", json.dumps(d, ensure_ascii=False)[:700], file=sys.stderr)
sys.exit(0 if ok else 1)
PY
  then echo "OK     $1"; PASS=$((PASS+1))
  else echo "ECHEC  $1"; FAIL=$((FAIL+1)); fi
}

check_num() { # $1=libellé $2=attendu $3=obtenu (à 0,01 près ; "None" = vide)
  if python3 -c "
import sys
a, b = sys.argv[1], sys.argv[2]
try:
    sys.exit(0 if abs(float(a) - float(b)) < 0.01 else 1)
except ValueError:
    sys.exit(0 if a == b else 1)" "$2" "$3"
  then echo "OK     $1 ($3)"; PASS=$((PASS+1))
  else echo "ECHEC  $1 (attendu « $2 », obtenu « $3 »)"; FAIL=$((FAIL+1)); fi
}

ok_cree() { # $1=libellé de l'étape (arrêt si refus : la suite n'aurait aucun sens)
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
# Client web (seul accès d'un RESPONSABLE) : jeton dans le cookie diafarms_access_token.
login_web() {
  curl -s -i -X POST "$BASE/auth" -H 'X-Client-Type: web' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | sed -n 's/^[Ss]et-[Cc]ookie: diafarms_access_token=\([^;]*\).*/\1/p' | head -1
}

# Reporting -> $TMP/body ; r 'expr' lit une valeur (d = data)
rep() { api GET "/reporting?dateDebut=$1&dateFin=$2${3:+&projetUniqueId=$3}"; }
r() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["data"]; print(eval(sys.argv[2]))' "$TMP/body" "$1"; }
ligne() { # $1=projet uid $2=chemin python dans la ligne (ex. argent["vendu"])
  r "next(l for l in d['parProjet'] if l['projetUniqueId'] == '$1')$2"
}

SUFFIXE="$(date +%H%M%S)$RANDOM"
# En lettres : /users/create bâtit l'identifiant sur les 10 premières lettres du nom
# (UtilisateurImpl.generateUsername plante si le nom a moins de lettres que de caractères).
LETTRES="$(echo "$SUFFIXE" | tr '0-9' 'a-j')"
HASH="$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'$PWD_TEST', bcrypt.gensalt(10)).decode())")"

# --- Ferme neuve + ADMIN ---------------------------------------------------------------
TOKEN="$(login_mobile "$SUPERADMIN_ID" "$SUPERADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion super-admin"; exit 1; }
ADMIN_EMAIL="admin-rep-$SUFFIXE@t.local"
api POST /users/create "{\"fullName\":\"Rep$LETTRES\",\"email\":\"$ADMIN_EMAIL\",\"telephone\":\"7$(date +%s | tail -c 8)\",\"farmName\":\"FermeReporting$SUFFIXE\",\"roles\":[\"COMPTABLE\"]}"
ok_cree "création de la ferme et de son ADMIN"
psql_run "UPDATE roles_users SET id_roles=(select id from roles where role='ADMIN') WHERE id_utilisateurs=(select id from utilisateurs where email='$ADMIN_EMAIL')" >/dev/null
psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$ADMIN_EMAIL'" >/dev/null
TOKEN="$(login_mobile "$ADMIN_EMAIL" "$PWD_TEST")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion ADMIN de la ferme neuve"; exit 1; }
TOKEN_ADMIN="$TOKEN"
ADMIN_ID="$(psql_run "select id from utilisateurs where email='$ADMIN_EMAIL'")"
FARM="$(psql_run "select farm_id from utilisateurs where email='$ADMIN_EMAIL'")"
api PUT /farm-settings '{"productionMobileEnabled":true,"productionWebEnabled":true,"comptableMobileEnabled":true,"comptableWebEnabled":true,"venteMobileEnabled":true,"venteWebEnabled":true,"responsableWebEnabled":true}'
echo "-- Ferme $FARM (FermeReporting$SUFFIXE)"

api POST /races/create '{"nom":"Pondeuse Rep","type":"PONDEUSE","origine":"Locale","description":"Race de test reporting","esperanceVieAnnees":3,"poidsAdulteKg":2.0,"productionOeufsAn":280,"couleurOeuf":"BRUN"}'
ok_cree "race"
RACE_ID="$(jval "d['data']['id']")"
psql_run "UPDATE races SET temps_croissance='MOYEN', rusticite='MOYENNE', adaptation_climat='CHAUD_SEC', certification_race='AUCUNE', poids_abattage='NON_APPLICABLE' WHERE id=$RACE_ID" >/dev/null

nouveau_projet() { # $1=titre $2=objectif $3=sujets $4=début -> PRJ, BAT
  api POST /batiments/create "{\"nom\":\"Poulailler $1\",\"capacite\":2000}"
  ok_cree "poulailler $1"
  local bat_id; bat_id="$(jval "d['data']['id']")"; BAT="$(jval "d['data']['uniqueId']")"
  api POST /projets/create "{\"titre\":\"$1\",\"responsableId\":$ADMIN_ID,\"dateDebut\":\"$4\",\"dateFinPrevue\":\"2027-12-31\",\"nbSujets\":$3,\"puSujet\":0,\"objectif\":\"$2\",\"raceId\":$RACE_ID,\"occupations\":[{\"batimentId\":$bat_id,\"dateEntree\":\"$4\",\"nbSujets\":$3}]}"
  ok_cree "projet $1"
  PRJ="$(jval "d['data']['uniqueId']")"
}
nouveau_projet "Ponte Rep" PONTE 1000 2026-01-01; PA="$PRJ"; BA="$BAT"
nouveau_projet "Chair Rep" REFORME 500 2026-02-01; PC="$PRJ"; BC="$BAT"
api POST /sites/create "{\"nom\":\"Site Rep $SUFFIXE\"}"; ok_cree "site"; SITE="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Stock Rep\",\"type\":\"STOCKAGE\"}"; ok_cree "magasin de stockage"; STOCK="$(jval "d['data']['uniqueId']")"
api POST /magasins/create "{\"nom\":\"Boutique Rep\",\"type\":\"VENTE\"}"; ok_cree "boutique"; BOUT="$(jval "d['data']['uniqueId']")"

# --- Élevage -----------------------------------------------------------------------------
mort() { api POST /mortalites/create "{\"projetUniqueId\":\"$1\",\"batimentUniqueId\":\"$2\",\"date\":\"$3\",\"nombreMorts\":$4,\"cause\":\"Scénario\"}"; ok_cree "mortalité $3"; }
collecte() { api POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PA\",\"batimentUniqueId\":\"$BA\",\"magasinStockageUniqueId\":\"$STOCK\",\"date\":\"$1\",\"oeufsCollectes\":$2,\"oeufsCasses\":$3,\"oeufsNonUtilisables\":0}"; ok_cree "collecte $1"; }
achat_aliment() { api POST "/alimentations/create/$1" "{\"typeAliment\":\"PONTE\",\"sac\":1,\"quantiteKg\":$2,\"coutTotal\":$3,\"dateDistribution\":\"2026-03-01\"}"; ok_cree "achat d'aliment"; }
conso() { api POST /consommations-aliment/create "{\"projetUniqueId\":\"$1\",\"batimentUniqueId\":\"$2\",\"date\":\"$3\",\"quantiteKg\":$4}"; ok_cree "consommation $3"; }

mort "$PA" "$BA" 2026-02-10 10
mort "$PA" "$BA" 2026-03-05 20
mort "$PA" "$BA" 2026-03-20 5
mort "$PC" "$BC" 2026-03-10 5
collecte 2026-02-15 500 0
collecte 2026-03-02 600 10
collecte 2026-03-03 630 0
achat_aliment "$PA" 300 45000
achat_aliment "$PC" 100 10000
conso "$PA" "$BA" 2026-03-04 120
conso "$PA" "$BA" 2026-03-06 126
conso "$PC" "$BC" 2026-03-11 50

# --- Argent ------------------------------------------------------------------------------
api POST /magasin-transferts/create "{\"magasinUniqueId\":\"$BOUT\",\"projetUniqueId\":\"$PA\",\"magasinStockageUniqueId\":\"$STOCK\",\"type\":\"OEUFS\",\"quantite\":90,\"date\":\"2026-03-04\"}"
ok_cree "transfert de 90 œufs vers la boutique"
api POST /clients/create "{\"nom\":\"Client K1 $SUFFIXE\",\"telephone\":\"8$(date +%s | tail -c 8)\"}"; ok_cree "client K1"; K1="$(jval "d['data']['uniqueId']")"
api POST /clients/create "{\"nom\":\"Client K2 $SUFFIXE\",\"telephone\":\"9$(date +%s | tail -c 8)\"}"; ok_cree "client K2"; K2="$(jval "d['data']['uniqueId']")"
api POST /ventes-oeufs/create "{\"date\":\"2026-03-10\",\"magasinUniqueId\":\"$BOUT\",\"clientUniqueId\":\"$K1\",\"quantiteOeufs\":60,\"prixUnitaire\":100,\"montant\":6000}"
ok_cree "vente à K1"; VK1="$(jval "d['data']['uniqueId']")"
api POST /paiements-client/create "{\"clientUniqueId\":\"$K1\",\"montant\":2500,\"mode\":\"ESPECES\",\"date\":\"2026-03-18\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$VK1\"}"
ok_cree "paiement partiel de K1"
api POST /ventes-oeufs/create "{\"date\":\"2026-03-12\",\"magasinUniqueId\":\"$BOUT\",\"quantiteOeufs\":30,\"prixUnitaire\":100,\"montant\":3000,\"montantRapporte\":2700}"
ok_cree "vente sans client"
api POST /ventes-diverses/create "{\"produit\":\"FIENTES\",\"date\":\"2026-03-14\",\"quantite\":4,\"montant\":1200,\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$PA\"}"
ok_cree "fientes du Projet A"
tx() { # $1=type $2=date $3=montant $4=catégorie $5=rattachement JSON
  api POST /transactions/create "{\"type\":\"$1\",\"date\":\"$2\",\"description\":\"Reporting $SUFFIXE\",\"montant\":$3,\"categorie\":\"$4\",$5}"
  ok_cree "transaction $1 $2 $3"
}
tx ENTREE 2026-03-06 1500 "Subvention" "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$PA\""
tx SORTIE 2026-02-20 3000 "Logistique" "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$PA\""
tx SORTIE 2026-03-08 8000 "Logistique" "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$PA\""
tx SORTIE 2026-03-09 4000 "Électricité" "\"rattachement\":\"SITE\",\"siteUniqueId\":\"$SITE\""
tx SORTIE 2026-03-11 6000 "Gardiennage" "\"rattachement\":\"FERME\""
tx SORTIE 2026-03-15 2000 "Logistique" "\"rattachement\":\"PROJET\",\"projetUniqueId\":\"$PC\""
api POST /paiements-client/create "{\"clientUniqueId\":\"$K2\",\"montant\":1000,\"mode\":\"ESPECES\",\"date\":\"2026-03-19\"}"
ok_cree "avance de K2"
api POST /remboursements-client/create "{\"clientUniqueId\":\"$K2\",\"montant\":400,\"mode\":\"ESPECES\",\"motif\":\"Scénario reporting\"}"
ok_cree "remboursement de K2"
# Le remboursement n'a pas de date de saisie (jour même) : ramené dans la période testée
# pour vérifier qu'il ne compte pas comme une dépense.
psql_run "UPDATE transactions SET date='2026-03-25' WHERE farm_id=$FARM AND source_type='REMBOURSEMENT_CLI'" >/dev/null

# Salaires de mars (31 000 affecté à A tout le mois ; 15 500 non affecté).
api POST /personnel/create "{\"nom\":\"Ouvrier Rep $SUFFIXE\",\"poste\":\"Ouvrier\"}"; ok_cree "employé 1"; EMP1="$(jval "d['data']['uniqueId']")"
api POST /salaires/definir "{\"employeUniqueId\":\"$EMP1\",\"modePaiement\":\"MENSUEL\",\"tauxBase\":31000}"; ok_cree "salaire employé 1"
api POST "/main-oeuvre/personnel/$EMP1/affectations" "{\"projetUniqueId\":\"$PA\",\"dateDebut\":\"2026-03-01\",\"dateFin\":\"2026-03-31\"}"; ok_cree "affectation à A"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP1\",\"periode\":\"2026-03\",\"montantForce\":31000,\"datePaiement\":\"2026-03-31\"}"; ok_cree "paie employé 1"
api POST /personnel/create "{\"nom\":\"Gardien Rep $SUFFIXE\",\"poste\":\"Gardien\"}"; ok_cree "employé 2"; EMP2="$(jval "d['data']['uniqueId']")"
api POST /salaires/definir "{\"employeUniqueId\":\"$EMP2\",\"modePaiement\":\"MENSUEL\",\"tauxBase\":15500}"; ok_cree "salaire employé 2"
api POST /salaires/payer "{\"employeUniqueId\":\"$EMP2\",\"periode\":\"2026-03\",\"montantForce\":15500,\"datePaiement\":\"2026-03-31\"}"; ok_cree "paie employé 2"

# Contrôles du montage (les chiffres attendus ci-dessous en dépendent).
check_num "montage : dépense d'aliment de A au 01/03" "45000" "$(psql_run "select coalesce(sum(t.montant),0) from transactions t join projets p on p.id=t.projet_id where p.unique_id='$PA' and t.source_type='ALIMENTATION' and t.date='2026-03-01' and t.statut='VALIDE'")"
check_num "montage : vente à K1 entièrement à A" "6000" "$(psql_run "select coalesce(sum(r.montant_attribue),0) from ventes_oeufs_repartitions r join projets p on p.id=r.projet_id where p.unique_id='$PA' and r.vente_oeufs_id=(select id from ventes_oeufs where unique_id='$VK1')")"
check_num "montage : salaires datés du 31/03" "46500" "$(psql_run "select coalesce(sum(montant),0) from transactions where farm_id=$FARM and source_type='SALAIRE' and date='2026-03-31' and statut='VALIDE'")"

# Main-d'œuvre attendue : 31 000 à A ; 15 500 au prorata des vivants fin mars (965 / 495).
MO_C="$(python3 -c "print(round(15500 * 495 / 1460))")"
MO_A="$(python3 -c "print(31000 + round(15500 * 965 / 1460))")"

echo "== 1. Toute la ferme, mars 2026"
rep 2026-03-01 2026-03-31
check "réponse 200, vue ferme" "code == 200 and d['data']['vueFerme'] is True and d['data']['mainOeuvreVisible'] is True"
check_num "Vendu (6 000 + 3 000 + 1 200)" 10200 "$(r "d['periode']['argent']['vendu']")"
check_num "Encaissé (paiements 3 500 + rapporté 2 700 + fientes 1 200 + subvention 1 500)" 8900 "$(r "d['periode']['argent']['encaisse']")"
check_num "Reste à encaisser (6 000 - 2 500 + 3 000 - 2 700)" 3800 "$(r "d['periode']['argent']['resteAEncaisser']")"
check_num "Autres entrées (subvention)" 1500 "$(r "d['periode']['argent']['autresEntrees']")"
check_num "Dépenses (sans le remboursement de 400)" 121500 "$(r "d['periode']['argent']['depenses']")"
check_num "Résultat = 10 200 + 1 500 - 121 500" -109800 "$(r "d['periode']['argent']['resultat']")"
check_num "Main-d'œuvre répartie (tous les salaires)" 46500 "$(r "d['periode']['argent']['mainOeuvre']")"
check_num "Dépenses communes non réparties (site 4 000 + ferme 6 000)" 10000 "$(r "d['periode']['argent']['depensesCommunes']")"
api GET "/transactions/stats?dateDebut=2026-03-01&dateFin=2026-03-31"
VENDU_STATS="$(jval "d['data']['totalVendu']")"; ENC_STATS="$(jval "d['data']['totalEncaisse']")"
rep 2026-03-01 2026-03-31
check_num "Vendu = Comptabilité (totalVendu)" "$VENDU_STATS" "$(r "d['periode']['argent']['vendu']")"
check_num "Encaissé = Comptabilité (totalEncaisse)" "$ENC_STATS" "$(r "d['periode']['argent']['encaisse']")"
check_num "effectif au début (A 990 + C 500)" 1490 "$(r "d['periode']['elevage']['effectifDebut']")"
check_num "effectif à la fin (A 965 + C 495)" 1460 "$(r "d['periode']['elevage']['effectifFin']")"
check_num "morts (25 + 5)" 30 "$(r "d['periode']['elevage']['mortes']")"
check_num "mortalité % = 30 / 1 490" 2.01 "$(r "d['periode']['elevage']['mortalitePct']")"
check_num "œufs collectés" 1230 "$(r "d['periode']['elevage']['oeufsCollectes']")"
check_num "œufs cassés" 10 "$(r "d['periode']['elevage']['oeufsCasses']")"
check_num "alvéoles = 1 230 / 30" 41 "$(r "d['periode']['elevage']['alveoles']")"
check_num "taux de ponte = 1 230 / (990 + 990)" 62.1 "$(r "d['periode']['elevage']['tauxPonteMoyen']")"
check_num "aliment consommé (120 + 126 + 50)" 296 "$(r "d['periode']['elevage']['alimentKg']")"
check_num "aliment par alvéole (ponte : 246 / 41)" 6 "$(r "d['periode']['elevage']['alimentParAlveoleKg']")"
check_num "coût d'une alvéole (dépenses de A / 41)" "$(python3 -c "print(round((45000 + 8000 + $MO_A) / 41))")" "$(r "d['periode']['elevage']['coutAlveole']")"
check "ponte du 02/03 : 600 œufs, 60,6 %" "code == 200 and any(j['date'] == '2026-03-02' and j['oeufs'] == 600 and j['tauxPonte'] == 60.6 for j in d['data']['seriesJour'])"
check "jour sans collecte : taux de ponte vide" "any(j['date'] == '2026-03-04' and j['tauxPonte'] is None and j['alimentKg'] == 120 for j in d['data']['seriesJour'])"
check "mortalité du 05/03 : 20" "any(j['date'] == '2026-03-05' and j['mortes'] == 20 for j in d['data']['seriesJour'])"
check "31 jours dans la série" "len(d['data']['seriesJour']) == 31"
check "semaines : encaissé 1 500 / 3 900 / 3 500 / 0 / 0" "[s['encaisse'] for s in d['data']['seriesSemaine']] == [1500, 3900, 3500, 0, 0]"
check "semaines : dépenses 55 000 / 18 000 / 2 000 / 0 / 46 500" "[s['depenses'] for s in d['data']['seriesSemaine']] == [55000, 18000, 2000, 0, 46500]"
check "rattachement : Projet 65 000, site 4 000, ferme 52 500" "{m['cle']: m['montant'] for m in d['data']['depensesParRattachement']} == {'PROJET': 65000, 'SITE': 4000, 'FERME': 52500}"
check "catégories : Salaires 46 500, Aliment 55 000, pas de remboursement" "(lambda c: c.get('Salaires') == 46500 and c.get('Aliment') == 55000 and not any('embours' in k for k in c))({m['libelle']: m['montant'] for m in d['data']['depensesParCategorie']})"
check "vendeur (par identifiant) : 3 ventes, 10 200" "len(d['data']['parVendeur']) == 1 and d['data']['parVendeur'][0]['nbVentes'] == 3 and d['data']['parVendeur'][0]['vendu'] == 10200 and d['data']['parVendeur'][0]['vendeurUniqueId']"
check "deux lignes Projet" "len(d['data']['parProjet']) == 2"
check_num "A : vendu" 10200 "$(ligne "$PA" "['argent']['vendu']")"
check_num "A : encaissé (2 500 + 2 700 + 1 200 + 1 500)" 7900 "$(ligne "$PA" "['argent']['encaisse']")"
check_num "A : main-d'œuvre (31 000 + part du gardien)" "$MO_A" "$(ligne "$PA" "['argent']['mainOeuvre']")"
check_num "A : dépenses (45 000 + 8 000 + main-d'œuvre)" "$(python3 -c "print(53000 + $MO_A)")" "$(ligne "$PA" "['argent']['depenses']")"
check_num "A : résultat" "$(python3 -c "print(10200 + 1500 - 53000 - $MO_A)")" "$(ligne "$PA" "['argent']['resultat']")"
check_num "A : taux de ponte" 62.1 "$(ligne "$PA" "['elevage']['tauxPonteMoyen']")"
check_num "A : mortalité % = 25 / 990" 2.53 "$(ligne "$PA" "['elevage']['mortalitePct']")"
check_num "C : main-d'œuvre (part du gardien)" "$MO_C" "$(ligne "$PC" "['argent']['mainOeuvre']")"
check_num "C : dépenses (10 000 + 2 000 + main-d'œuvre)" "$(python3 -c "print(12000 + $MO_C)")" "$(ligne "$PC" "['argent']['depenses']")"
check "C (chair) : pas de taux de ponte, ni d'aliment par alvéole, ni de coût d'alvéole" "(lambda e: e['tauxPonteMoyen'] is None and e['alimentParAlveoleKg'] is None and e['coutAlveole'] is None and e['mortalitePct'] == 1.0)(next(l for l in d['data']['parProjet'] if l['projetUniqueId'] == '$PC')['elevage'])"
check "ligne Commun : encaissé 1 000 (avance de K2), dépenses 10 000" "d['data']['commun']['encaisse'] == 1000 and d['data']['commun']['depenses'] == 10000 and d['data']['commun']['vendu'] == 0"
check "Σ Projets + Commun = total (vendu, encaissé, dépenses, résultat)" "all(abs(sum(l['argent'][k] for l in d['data']['parProjet']) + d['data']['commun'][k] - d['data']['periode']['argent'][k]) < 0.01 for k in ('vendu', 'encaisse', 'depenses', 'resultat', 'autresEntrees', 'resteAEncaisser'))"

echo "== 2. Période précédente (2026-01-29 au 2026-02-28)"
check "dates de la période précédente" "d['data']['precedentDebut'] == '2026-01-29' and d['data']['precedentFin'] == '2026-02-28'"
check "précédente : dépenses 3 000, vendu 0, résultat -3 000" "(lambda a: a['depenses'] == 3000 and a['vendu'] == 0 and a['resultat'] == -3000)(d['data']['precedent']['argent'])"
check "précédente : 500 œufs, 10 morts, effectif début 1 500, mortalité 0,67 %" "(lambda e: e['oeufsCollectes'] == 500 and e['mortes'] == 10 and e['effectifDebut'] == 1500 and e['mortalitePct'] == 0.67)(d['data']['precedent']['elevage'])"
check "précédente : taux de ponte 500 / 990 = 50,5 %" "d['data']['precedent']['elevage']['tauxPonteMoyen'] == 50.5"
rep 2026-02-01 2026-02-28
check "février seul = même chiffres d'élevage que la période précédente de mars (pas de saisie fin janvier)" "d['data']['periode']['elevage']['oeufsCollectes'] == 500 and d['data']['periode']['argent']['depenses'] == 3000"

echo "== 3. Filtre Projet"
rep 2026-03-01 2026-03-31 "$PA"
check "filtre A : pas la vue ferme, pas de ligne Commun" "code == 200 and d['data']['vueFerme'] is False and d['data']['commun'] is None and len(d['data']['parProjet']) == 1"
check_num "filtre A : vendu" 10200 "$(r "d['periode']['argent']['vendu']")"
check_num "filtre A : encaissé" 7900 "$(r "d['periode']['argent']['encaisse']")"
check_num "filtre A : dépenses" "$(python3 -c "print(53000 + $MO_A)")" "$(r "d['periode']['argent']['depenses']")"
check_num "filtre A : dépenses communes de la ferme (non comptées dans A)" 10000 "$(r "d['periode']['argent']['depensesCommunes']")"
check_num "filtre A : effectif début" 990 "$(r "d['periode']['elevage']['effectifDebut']")"
check_num "filtre A : morts" 25 "$(r "d['periode']['elevage']['mortes']")"
check "filtre A : semaines (encaissé)" "[s['encaisse'] for s in d['data']['seriesSemaine']] == [1500, 3900, 2500, 0, 0]"
check "filtre A : semaines (dépenses)" "[s['depenses'] for s in d['data']['seriesSemaine']] == [45000, 8000, 0, 0, $MO_A]"
check "filtre A : rattachement Projet 53 000 + main-d'œuvre" "{m['cle']: m['montant'] for m in d['data']['depensesParRattachement']} == {'PROJET': 53000, 'MAIN_OEUVRE': $MO_A}"
rep 2026-03-01 2026-03-31 "$PC"
check "filtre C : aucun œuf, taux de ponte vide" "d['data']['periode']['elevage']['oeufsCollectes'] == 0 and d['data']['periode']['elevage']['tauxPonteMoyen'] is None and d['data']['periode']['elevage']['coutAlveole'] is None"
check_num "filtre C : aliment" 50 "$(r "d['periode']['elevage']['alimentKg']")"
rep 2026-03-01 2026-03-31 "projet-inconnu-$SUFFIXE"
check "Projet inconnu : 400" "code == 400 and 'Projet introuvable' in ' '.join(d.get('errors') or [])"
api GET "/reporting?dateDebut=2026-03-31&dateFin=2026-03-01"
check "dates inversées : 400" "code == 400"
api GET "/reporting?dateDebut=31-03-2026"
check "date illisible : 400" "code == 400"

echo "== 4. Période vide (juin 2025, avant les Projets)"
rep 2025-06-01 2025-06-30
check "tout à zéro, indicateurs vides" "code == 200 and (lambda a, e: a['vendu'] == 0 and a['encaisse'] == 0 and a['depenses'] == 0 and a['resultat'] == 0 and e['effectifDebut'] == 0 and e['tauxPonteMoyen'] is None and e['mortalitePct'] is None and e['coutAlveole'] is None)(d['data']['periode']['argent'], d['data']['periode']['elevage'])"
check "période vide : aucune ligne Projet, 30 jours à zéro" "d['data']['parProjet'] == [] and len(d['data']['seriesJour']) == 30 and all(j['oeufs'] == 0 and j['tauxPonte'] is None for j in d['data']['seriesJour'])"

echo "== 5. Cohérence avec la fiche Projet (toutes les dates)"
AUJ="$(date +%F)"
for P in "$PA" "$PC"; do
  NOM=$([ "$P" = "$PA" ] && echo A || echo C)
  api GET "/projets/$P/encaissement"
  F_VENDU="$(jval "d['data']['vendu']")"; F_ENC="$(jval "d['data']['encaisse']")"; F_RESTE="$(jval "d['data']['resteAEncaisser']")"
  api GET "/main-oeuvre/projets/$P/cout"
  F_MO="$(jval "d['data']['total']")"
  # Résultat net de la fiche (ProjetDetail.tsx) : Σ montantReel des entrées validées - Σ sorties validées.
  api GET "/transactions/list?page=0&size=500&projetUniqueId=$P"
  F_RECETTES="$(jval "sum(t['montantReel'] for t in d['data']['data'] if t['type'] == 'ENTREE' and t['statut'] == 'VALIDE')")"
  F_SORTIES="$(jval "sum(t['montant'] for t in d['data']['data'] if t['type'] == 'SORTIE' and t['statut'] == 'VALIDE')")"
  F_NET="$(python3 -c "print($F_RECETTES - $F_SORTIES)")"
  rep 2026-01-01 "$AUJ" "$P"
  check_num "$NOM : Vendu = fiche Projet" "$F_VENDU" "$(r "d['periode']['argent']['vendu']")"
  check_num "$NOM : Encaissé - autres entrées = fiche Projet" "$F_ENC" "$(r "d['periode']['argent']['encaisse'] - d['periode']['argent']['autresEntrees']")"
  check_num "$NOM : Reste à encaisser = fiche Projet" "$F_RESTE" "$(r "d['periode']['argent']['resteAEncaisser']")"
  check_num "$NOM : main-d'œuvre = fiche Projet" "$F_MO" "$(r "d['periode']['argent']['mainOeuvre']")"
  check_num "$NOM : dépenses hors main-d'œuvre = dépenses validées de la fiche" "$F_SORTIES" "$(r "d['periode']['argent']['depenses'] - d['periode']['argent']['mainOeuvre']")"
  # Fiche : « Résultat complet » = résultat net - main-d'œuvre (pas d'amortissement ici),
  # ventes à crédit comptées pour ce qui est payé ; Reporting : pour leur valeur vendue.
  check_num "$NOM : Résultat = résultat complet de la fiche + reste à encaisser" "$(python3 -c "print($F_NET - $F_MO + $F_RESTE)")" "$(r "d['periode']['argent']['resultat']")"
done

echo "== 6. RESPONSABLE : seulement ses Projets"
RESP_EMAIL="resp-rep-$SUFFIXE@t.local"
api POST /users/create-pro-or-finance "{\"fullName\":\"Responsable $LETTRES\",\"email\":\"$RESP_EMAIL\",\"telephone\":\"6$(date +%s | tail -c 8)\",\"roles\":[\"RESPONSABLE\"]}"
ok_cree "utilisateur RESPONSABLE"
psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$RESP_EMAIL'" >/dev/null
RESP_ID="$(psql_run "select id from utilisateurs where email='$RESP_EMAIL'")"
psql_run "UPDATE projets SET responsable_user_id=$RESP_ID WHERE unique_id='$PC'" >/dev/null
TOKEN="$(login_web "$RESP_EMAIL" "$PWD_TEST")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion RESPONSABLE"; FAIL=$((FAIL+1)); }
rep 2026-03-01 2026-03-31
check "RESPONSABLE : une seule ligne (C), pas de vue ferme ni de Commun" "code == 200 and d['data']['vueFerme'] is False and d['data']['commun'] is None and [l['projetUniqueId'] for l in d['data']['parProjet']] == ['$PC']"
check_num "RESPONSABLE : dépenses = celles de C" "$(python3 -c "print(12000 + $MO_C)")" "$(r "d['periode']['argent']['depenses']")"
check "RESPONSABLE : vendu 0, dépenses communes non visibles, pas de vendeur" "d['data']['periode']['argent']['vendu'] == 0 and d['data']['periode']['argent']['depensesCommunes'] is None and d['data']['parVendeur'] == []"
check_num "RESPONSABLE : effectif début (C seul)" 500 "$(r "d['periode']['elevage']['effectifDebut']")"
rep 2026-03-01 2026-03-31 "$PA"
check "RESPONSABLE : Projet A (pas le sien) -> 400" "code == 400 and 'Projet introuvable' in ' '.join(d.get('errors') or [])"
rep 2026-03-01 2026-03-31 "$PC"
check "RESPONSABLE : son Projet C filtré -> 200" "code == 200 and d['data']['projetUniqueId'] == '$PC'"

echo "== 7. Autre ferme : rien ne fuit"
TOKEN="$(login_mobile admin@t.local "$PWD_TEST")"
if [ -n "$TOKEN" ]; then
  rep 2026-03-01 2026-03-31 "$PA"
  check "ADMIN d'une autre ferme : Projet A introuvable" "code == 400"
else
  echo "(admin@t.local absent : contrôle inter-fermes sauté)"
fi

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
