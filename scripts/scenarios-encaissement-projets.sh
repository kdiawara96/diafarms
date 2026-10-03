#!/usr/bin/env bash
# Argent des clients vu par Projet (2026-10-01) : chiffres DÉDUITS, le modèle ne change pas.
# Un paiement client reste « Commun » ; il règle des ventes (imputations), et chaque vente
# est partagée entre les Projets par sa répartition. Voir EncaissementProjetService.
#
# Montage : deux Projets neufs (A, B) apportent 60 et 30 œufs dans une boutique neuve.
# A. Commande de 60 œufs pour 5 000 F, acompte 2 000 : rien n'est vendu, l'acompte est
#    « en attente » (ferme), encaissé des Projets = 0.
# B. Livraison des 60 œufs : la vente (5 000) est partagée 2/3 - 1/3 (40 / 20 œufs) ;
#    l'acompte la règle : A encaisse 2/3 de 2 000, B 1/3 ; reste à encaisser 2 000 / 1 000.
# C. Le client paie le reste (3 000) plus 500 : tout est encaissé, 500 d'avance libre.
# D. Vente sans client de 30 œufs (3 000, rapporté 2 400) : A 2/3, B 1/3 du rapporté.
# E. Comptabilité : la ligne de chaque paiement porte sa répartition entre Projets et sa
#    part non attribuée (acompte réservé, avance). Page Ventes : répartition par vente.
# F. Identité, ferme entière : Σ encaissé des Projets + acomptes en attente + avances
#    libres = Σ paiements clients - Σ remboursements + montant rapporté des ventes sans
#    client + ventes diverses (fientes, autres) rattachées à un Projet.
#
# Pré-requis (non gérés ici) : Postgres + backend démarrés, base seedée par
# scenarios-circuit-client.sh (ferme + ADMIN admin@t.local / Test1234!, race « Pondeuse
# Scen »). Rejouable : chaque passage crée ses Projets, ses poulaillers, ses magasins et
# son client.
#
# Variables : BASE (défaut http://localhost:9199/diafarms/api/v1), PGHOST (127.0.0.1),
# PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen).
#
# Sortie : une ligne OK/ECHEC par assertion ; code de sortie 0 si tout est OK, 1 sinon.
set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
ADMIN_EMAIL="admin@t.local"
ADMIN_PWD="Test1234!"

FAILURES=0
PASS=0
psql_run() { # $1 = requête SQL (une ligne)
  local args=(-p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE")
  [ -n "${PGHOST:-}" ] && args=(-h "$PGHOST" "${args[@]}")
  psql "${args[@]}" -v ON_ERROR_STOP=1 -Atc "$1"
}
jpath() {
  python3 - "$1" "$2" <<'PY'
import json, sys
d = json.loads(sys.argv[1])
cur = d
for p in sys.argv[2].split('.'):
    if cur is None:
        break
    if isinstance(cur, list):
        try:
            cur = cur[int(p)]
        except (ValueError, IndexError):
            cur = None
    elif isinstance(cur, dict):
        cur = cur.get(p)
    else:
        cur = None
print(cur if cur is not None else "")
PY
}
call() { # $1=METHOD $2=PATH $3=JSON(optionnel)
  local method="$1" path="$2" data="${3:-}" out
  if [ -n "$data" ]; then
    out=$(curl -sS -w $'\n%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$data")
  else
    out=$(curl -sS -w $'\n%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $TOKEN")
  fi
  HTTP_STATUS="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}
login() { # $1=identifiant $2=password -> positionne TOKEN
  local resp
  resp=$(curl -sS -X POST "$BASE/auth" -H "X-Client-Type: mobile" \
    --data-urlencode "grantType=password" \
    --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2")
  TOKEN=$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])
data = d.get('data') or {}
print(data.get('accessToken') or '')
" "$resp")
}
verifier() { # $1=libelle $2=attendu $3=obtenu
  local libelle="$1" attendu="$2" obtenu="$3" resultat
  resultat=$(python3 -c "
import sys
a, b = sys.argv[1], sys.argv[2]
try:
    print('OK' if abs(float(a) - float(b)) < 0.01 else 'ECHEC')
except ValueError:
    print('OK' if a == b else 'ECHEC')
" "$attendu" "$obtenu")
  if [ "$resultat" = "OK" ]; then
    echo "OK    $libelle (attendu=$attendu, obtenu=$obtenu)"; PASS=$((PASS + 1))
  else
    echo "ECHEC $libelle : attendu=$attendu, obtenu=$obtenu"
    FAILURES=$((FAILURES + 1))
  fi
}

statut_ok() { # 200 ou 201 -> 1, sinon le code et la réponse
  if [ "$HTTP_STATUS" = "200" ] || [ "$HTTP_STATUS" = "201" ]; then echo 1; else echo "$HTTP_STATUS $BODY"; fi
}

SUFFIXE="$(date +%H%M%S)-$RANDOM"
AUJ="$(date +%F)"

login "$ADMIN_EMAIL" "$ADMIN_PWD"
[ -z "$TOKEN" ] && { echo "ECHEC : connexion ADMIN impossible."; exit 1; }
ADMIN_ID=$(psql_run "select id from utilisateurs where email='$ADMIN_EMAIL'")
RACE_ID=$(psql_run "select id from races where nom='Pondeuse Scen' order by id limit 1")
FARM=$(psql_run "select farm_id from utilisateurs where email='$ADMIN_EMAIL'")
[ -z "$RACE_ID" ] && { echo "ECHEC : base non seedée (lancer scenarios-circuit-client.sh)."; exit 1; }

# --- Montage : poulailler, deux Projets, boutique, 60 + 30 œufs ---------------------------
nouveau_projet() { # $1=titre -> PRJ_UID, PRJ_CODE, BAT_UID (un poulailler neuf par Projet)
  call POST /batiments/create "{\"nom\":\"Poulailler $1\",\"capacite\":200}"
  BAT_ID=$(jpath "$BODY" "data.id"); BAT_UID=$(jpath "$BODY" "data.uniqueId")
  [ -z "$BAT_ID" ] && { echo "ECHEC création poulailler ($HTTP_STATUS) : $BODY"; exit 1; }
  call POST /projets/create "{\"titre\":\"$1\",\"responsableId\":$ADMIN_ID,\"dateDebut\":\"2026-01-01\",\"dateFinPrevue\":\"2027-12-31\",\"nbSujets\":100,\"puSujet\":0,\"objectif\":\"PONTE\",\"raceId\":$RACE_ID,\"occupations\":[{\"batimentId\":$BAT_ID,\"dateEntree\":\"2026-01-01\",\"nbSujets\":100}]}"
  PRJ_UID=$(jpath "$BODY" "data.uniqueId"); PRJ_CODE=$(jpath "$BODY" "data.code")
  [ -z "$PRJ_UID" ] && { echo "ECHEC création projet ($HTTP_STATUS) : $BODY"; exit 1; }
}
nouveau_projet "Projet Enc A $SUFFIXE"; PA="$PRJ_UID"; PA_CODE="$PRJ_CODE"; BAT_A="$BAT_UID"
nouveau_projet "Projet Enc B $SUFFIXE"; PB="$PRJ_UID"; PB_CODE="$PRJ_CODE"; BAT_B="$BAT_UID"

# Magasins neufs : un magasin de stockage partagé mélangerait le stock d'autres Projets.
call POST /magasins/create "{\"nom\":\"Stock Enc $SUFFIXE\",\"type\":\"STOCKAGE\"}"
STOCK_UID=$(jpath "$BODY" "data.uniqueId")
[ -z "$STOCK_UID" ] && { echo "ECHEC création magasin de stockage ($HTTP_STATUS) : $BODY"; exit 1; }
call POST /magasins/create "{\"nom\":\"Boutique Enc $SUFFIXE\",\"type\":\"VENTE\"}"
BOUT=$(jpath "$BODY" "data.uniqueId")
[ -z "$BOUT" ] && { echo "ECHEC création boutique ($HTTP_STATUS) : $BODY"; exit 1; }

apport() { # $1=projet $2=œufs $3=poulailler
  call POST /collectes-oeufs/create "{\"projetUniqueId\":\"$1\",\"batimentUniqueId\":\"$3\",\"magasinStockageUniqueId\":\"$STOCK_UID\",\"date\":\"$AUJ\",\"oeufsCollectes\":$2,\"oeufsCasses\":0,\"oeufsNonUtilisables\":0}"
  [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "201" ] && { echo "ECHEC collecte ($HTTP_STATUS) : $BODY"; exit 1; }
  call POST /magasin-transferts/create "{\"magasinUniqueId\":\"$BOUT\",\"projetUniqueId\":\"$1\",\"magasinStockageUniqueId\":\"$STOCK_UID\",\"type\":\"OEUFS\",\"quantite\":$2,\"date\":\"$AUJ\"}"
  [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "201" ] && { echo "ECHEC transfert ($HTTP_STATUS) : $BODY"; exit 1; }
}
apport "$PA" 60 "$BAT_A"
apport "$PB" 30 "$BAT_B"
echo "-- Montage : $PA_CODE (60 œufs) et $PB_CODE (30 œufs) dans Boutique Enc $SUFFIXE --"

call POST /clients/create "{\"nom\":\"Client Enc $SUFFIXE\",\"telephone\":\"8$(date +%s | tail -c 8)\"}"
CLIENT=$(jpath "$BODY" "data.uniqueId")
[ -z "$CLIENT" ] && { echo "ECHEC création client ($HTTP_STATUS) : $BODY"; exit 1; }

encaissement() { # $1=projet -> ENC (JSON)
  call GET "/projets/$1/encaissement"
  ENC=$(python3 -c "import json,sys; print(json.dumps(json.loads(sys.argv[1]).get('data') or {}))" "$BODY")
}
verifier_projet() { # $1=libelle $2=projet $3=vendu $4=encaisse $5=reste
  encaissement "$2"
  verifier "$1 vendu" "$3" "$(jpath "$ENC" vendu)"
  verifier "$1 encaissé" "$4" "$(jpath "$ENC" encaisse)"
  verifier "$1 reste à encaisser" "$5" "$(jpath "$ENC" resteAEncaisser)"
}
stats_attente() { # -> ACOMPTES, AVANCES
  call GET "/transactions/stats"
  ACOMPTES=$(jpath "$BODY" "data.totalAcomptesEnAttente"); AVANCES=$(jpath "$BODY" "data.totalAvancesLibres")
}
# Ligne Comptabilité du paiement $1 (uniqueId du paiement) -> LIGNE (JSON)
ligne_paiement() {
  call GET "/transactions/list?page=0&size=50&search=paiement%20client"
  LIGNE=$(python3 -c "
import json, sys
for t in (json.loads(sys.argv[1]).get('data') or {}).get('data') or []:
    if t.get('sourceUniqueId') == sys.argv[2]:
        print(json.dumps(t)); break
else:
    print('{}')
" "$BODY" "$1")
}
part_projet() { # $1=JSON ligne $2=projetUid -> montant (vide si absent)
  python3 -c "
import json, sys
for p in json.loads(sys.argv[1]).get('repartitionProjets') or []:
    if p.get('projetUniqueId') == sys.argv[2]:
        print(p.get('montant')); break
" "$1" "$2"
}

stats_attente; ACOMPTES0="$ACOMPTES"; AVANCES0="$AVANCES"

# --- A. Commande + acompte 2 000 ----------------------------------------------------------
call POST /commandes/create "{\"clientUniqueId\":\"$CLIENT\",\"magasinUniqueId\":\"$BOUT\",\"type\":\"OEUFS\",\"quantite\":60,\"montantEstime\":5000,\"montantAcompte\":2000}"
CMD=$(jpath "$BODY" "data.uniqueId")
[ -z "$CMD" ] && { echo "ECHEC création commande ($HTTP_STATUS) : $BODY"; exit 1; }
ACOMPTE_PAIEMENT=$(psql_run "select p.unique_id from paiements_client p join commandes k on k.id=p.commande_id where k.unique_id='$CMD' order by p.id limit 1")
verifier "A. acompte enregistré comme paiement" "1" "$([ -n "$ACOMPTE_PAIEMENT" ] && echo 1 || echo 0)"
verifier_projet "A. $PA_CODE" "$PA" 0 0 0
verifier_projet "A. $PB_CODE" "$PB" 0 0 0
stats_attente
verifier "A. acomptes en attente (ferme) +2 000" "$(python3 -c "print($ACOMPTES0 + 2000)")" "$ACOMPTES"
ligne_paiement "$ACOMPTE_PAIEMENT"
verifier "A. ligne acompte : aucun Projet" "0" "$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1]).get('repartitionProjets') or []))" "$LIGNE")"
verifier "A. ligne acompte : non attribué" "2000" "$(jpath "$LIGNE" montantNonAttribue)"
verifier "A. ligne acompte : nature" "ACOMPTE_RESERVE" "$(jpath "$LIGNE" natureNonAttribue)"
verifier "A. ligne acompte : commande" "$CMD" "$(jpath "$LIGNE" commandeUniqueId)"

# --- B. Livraison : vente 5 000 partagée 2/3 - 1/3 ----------------------------------------
call POST "/commandes/$CMD/livrer?quantite=60"
verifier "B. livraison acceptée" "1" "$(statut_ok)"
VENTE=$(psql_run "select unique_id from ventes_oeufs where commande_id=(select id from commandes where unique_id='$CMD')")
PART_A=$(psql_run "select r.montant_attribue from ventes_oeufs_repartitions r join projets p on p.id=r.projet_id where r.vente_oeufs_id=(select id from ventes_oeufs where unique_id='$VENTE') and p.unique_id='$PA'")
PART_B=$(psql_run "select r.montant_attribue from ventes_oeufs_repartitions r join projets p on p.id=r.projet_id where r.vente_oeufs_id=(select id from ventes_oeufs where unique_id='$VENTE') and p.unique_id='$PB'")
QTE_A=$(psql_run "select r.quantite_attribuee from ventes_oeufs_repartitions r join projets p on p.id=r.projet_id where r.vente_oeufs_id=(select id from ventes_oeufs where unique_id='$VENTE') and p.unique_id='$PA'")
verifier "B. $PA_CODE a fourni 40 œufs" "40" "$QTE_A"
verifier "B. parts : somme = 5 000" "5000" "$(python3 -c "print($PART_A + $PART_B)")"
verifier "B. part de $PA_CODE = 2/3" "$(python3 -c "print(round(5000*2/3, 2))")" "$PART_A"
ENC_A=$(python3 -c "print(2000 * $PART_A / 5000)"); ENC_B=$(python3 -c "print(2000 * $PART_B / 5000)")
verifier_projet "B. $PA_CODE" "$PA" "$PART_A" "$ENC_A" "$(python3 -c "print($PART_A - $ENC_A)")"
verifier_projet "B. $PB_CODE" "$PB" "$PART_B" "$ENC_B" "$(python3 -c "print($PART_B - $ENC_B)")"
stats_attente
verifier "B. acompte n'est plus en attente" "$ACOMPTES0" "$ACOMPTES"
ligne_paiement "$ACOMPTE_PAIEMENT"
verifier "B. ligne acompte : part $PA_CODE" "$ENC_A" "$(part_projet "$LIGNE" "$PA")"
verifier "B. ligne acompte : part $PB_CODE" "$ENC_B" "$(part_projet "$LIGNE" "$PB")"
verifier "B. ligne acompte : premier Projet = $PA_CODE" "$PA_CODE" "$(jpath "$LIGNE" repartitionProjets.0.code)"
verifier "B. ligne acompte : plus rien en attente" "0" "$(jpath "$LIGNE" montantNonAttribue)"
# Ventes du jour seulement : la liste complète d'une base de test qui a déjà servi dépasse
# la taille maximale d'un argument de python3 (« Liste d'arguments trop longue »).
call GET "/ventes/list?dateDebut=$AUJ&dateFin=$AUJ"
VLIGNE=$(python3 -c "
import json, sys
for v in json.loads(sys.argv[1]).get('data') or []:
    if v.get('uniqueId') == sys.argv[2]:
        print(json.dumps(v)); break
else:
    print('{}')
" "$BODY" "$VENTE")
verifier "B. page Ventes : 2 Projets dans la répartition" "2" "$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1]).get('repartitionProjets') or []))" "$VLIGNE")"
verifier "B. page Ventes : $PA_CODE 40 œufs" "40" "$(python3 -c "
import json,sys
print(next((p['quantite'] for p in json.loads(sys.argv[1])['repartitionProjets'] if p['projetUniqueId']==sys.argv[2]), ''))" "$VLIGNE" "$PA")"
verifier "B. page Ventes : $PB_CODE montant" "$PART_B" "$(python3 -c "
import json,sys
print(next((p['montant'] for p in json.loads(sys.argv[1])['repartitionProjets'] if p['projetUniqueId']==sys.argv[2]), ''))" "$VLIGNE" "$PB")"

# --- C. Le client paie le reste + 500 ------------------------------------------------------
call POST /paiements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"montant\":3500,\"mode\":\"ESPECES\"}"
verifier "C. paiement 3 500 accepté" "1" "$(statut_ok)"
P2=$(jpath "$BODY" "data.uniqueId")
[ -z "$P2" ] && P2=$(psql_run "select p.unique_id from paiements_client p join clients c on c.id=p.client_id where c.unique_id='$CLIENT' and p.montant=3500 order by p.id desc limit 1")
verifier_projet "C. $PA_CODE" "$PA" "$PART_A" "$PART_A" 0
verifier_projet "C. $PB_CODE" "$PB" "$PART_B" "$PART_B" 0
stats_attente
verifier "C. avance libre +500" "$(python3 -c "print($AVANCES0 + 500)")" "$AVANCES"
ligne_paiement "$P2"
verifier "C. ligne 3 500 : part $PA_CODE" "$(python3 -c "print(round(3000 * $PART_A / 5000, 2))")" "$(part_projet "$LIGNE" "$PA")"
verifier "C. ligne 3 500 : part $PB_CODE" "$(python3 -c "print(round(3000 * $PART_B / 5000, 2))")" "$(part_projet "$LIGNE" "$PB")"
verifier "C. ligne 3 500 : avance du client" "500" "$(jpath "$LIGNE" montantNonAttribue)"
verifier "C. ligne 3 500 : nature" "AVANCE" "$(jpath "$LIGNE" natureNonAttribue)"

# --- D. Vente sans client : 30 œufs, 3 000, rapporté 2 400 ---------------------------------
call POST /ventes-oeufs/create "{\"date\":\"$AUJ\",\"magasinUniqueId\":\"$BOUT\",\"quantiteOeufs\":30,\"prixUnitaire\":100,\"montant\":3000,\"montantRapporte\":2400}"
verifier "D. vente sans client acceptée" "1" "$(statut_ok)"
VSC=$(jpath "$BODY" "data.uniqueId")
SA=$(psql_run "select r.montant_attribue from ventes_oeufs_repartitions r join projets p on p.id=r.projet_id where r.vente_oeufs_id=(select id from ventes_oeufs where unique_id='$VSC') and p.unique_id='$PA'")
SB=$(psql_run "select r.montant_attribue from ventes_oeufs_repartitions r join projets p on p.id=r.projet_id where r.vente_oeufs_id=(select id from ventes_oeufs where unique_id='$VSC') and p.unique_id='$PB'")
verifier "D. part de $PA_CODE = 2/3 (20 œufs sur 30)" "2000" "$SA"
verifier_projet "D. $PA_CODE" "$PA" "$(python3 -c "print($PART_A + $SA)")" "$(python3 -c "print($PART_A + 2400 * $SA / 3000)")" "$(python3 -c "print(600 * $SA / 3000)")"
verifier_projet "D. $PB_CODE" "$PB" "$(python3 -c "print($PART_B + $SB)")" "$(python3 -c "print($PART_B + 2400 * $SB / 3000)")" "$(python3 -c "print(600 * $SB / 3000)")"

# --- E. Remboursement de 200 sur l'avance : il sort de l'avance libre ----------------------
call POST /remboursements-client/create "{\"clientUniqueId\":\"$CLIENT\",\"montant\":200,\"mode\":\"ESPECES\",\"motif\":\"Scénario encaissement\"}"
verifier "E. remboursement 200 accepté" "1" "$(statut_ok)"
stats_attente
verifier "E. avance libre +300" "$(python3 -c "print($AVANCES0 + 300)")" "$AVANCES"
ligne_paiement "$P2"
verifier "E. ligne 3 500 : remboursé 200" "200" "$(jpath "$LIGNE" montantRembourse)"
verifier "E. ligne 3 500 : avance 300" "300" "$(jpath "$LIGNE" montantNonAttribue)"

# --- F. Identité, ferme entière -----------------------------------------------------------
SOMME_PROJETS=0
for P in $(psql_run "select unique_id from projets where farm_id=$FARM"); do
  encaissement "$P"
  SOMME_PROJETS=$(python3 -c "print($SOMME_PROJETS + float('$(jpath "$ENC" encaisse)' or 0))")
done
PAIEMENTS=$(psql_run "select coalesce(sum(montant),0) from paiements_client where farm_id=$FARM and statut='ACTIF'")
REMBOURSEMENTS=$(psql_run "select coalesce(sum(montant),0) from remboursements_client where farm_id=$FARM and statut='ACTIF'")
CASH=$(psql_run "select coalesce(sum(coalesce(v.montant_rapporte, v.montant)),0) from ventes_oeufs v where v.farm_id=$FARM and v.client_id is null and v.removed=false")
CASH_R=$(psql_run "select coalesce(sum(coalesce(v.montant_rapporte, v.montant)),0) from ventes_reforme v where v.farm_id=$FARM and v.client_id is null and v.removed=false")
# Fientes / autres ventes rattachées au Projet : encaissées par ce projet (comptant).
DIVERSES=$(psql_run "select coalesce(sum(v.montant),0) from ventes_diverses v where v.farm_id=$FARM and v.projet_id is not null and v.removed=false
  and exists (select 1 from transactions t where t.source_unique_id = v.unique_id and t.statut='VALIDE' and coalesce(t.removed,false)=false)")
stats_attente
GAUCHE=$(python3 -c "print(round($SOMME_PROJETS + $ACOMPTES + $AVANCES, 2))")
DROITE=$(python3 -c "print(round($PAIEMENTS - $REMBOURSEMENTS + $CASH + $CASH_R + $DIVERSES, 2))")
echo "      Σ Projets $SOMME_PROJETS + acomptes $ACOMPTES + avances $AVANCES ; paiements $PAIEMENTS - remboursements $REMBOURSEMENTS + sans client $CASH + $CASH_R + fientes du Projet $DIVERSES"
verifier "F. Σ encaissé Projets + en attente + avances = paiements - remboursements + sans client + ventes diverses des Projets" "$DROITE" "$GAUCHE"

echo
echo "Résultat : $PASS OK, $FAILURES ECHEC"
[ "$FAILURES" -eq 0 ]
