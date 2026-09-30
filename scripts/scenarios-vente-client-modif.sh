#!/usr/bin/env bash
# Modifier une vente d'œufs : ajouter / retirer son client, œufs normaux <-> cassés.
#
# Règles vérifiées (VenteOeufsImpl.update) :
# - ajout d'un client à une vente sans client : l'écart du vendeur est annulé, ce qu'il
#   avait rapporté devient un paiement du client sur cette vente ;
# - retrait du client : l'argent reçu À LA VENTE (paiements d'origine VENTE) est annulé et
#   repasse en montant rapporté (valeur par défaut) ; les autres paiements du client sur la
#   vente redeviennent une avance ; refus si une partie de l'argent reçu à la vente a été
#   remboursée ou règle une autre vente ; l'écart repasse au vendeur ;
# - vente sur une facture active : client, montant, quantité et type refusés ; une vente
#   supprimée ne se modifie pas ;
# - type d'œufs : les œufs changent de stock (normaux <-> cassés), refus si le stock visé
#   est insuffisant.
#
# Pré-requis : Postgres + backend démarrés, base seedée par scenarios-circuit-client.sh
# (Boutique Scen, Stock Scen, projet et poulailler). Rejouable : client au nom unique,
# ventes de test supprimées à la fin.
# Variables : BASE, PGHOST, PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen).

set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@t.local}"
ADMIN_PWD="${ADMIN_PWD:-Test1234!}"
FAILURES=0

psql_run() {
  local args=(-p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE")
  [ -n "${PGHOST:-}" ] && args=(-h "$PGHOST" "${args[@]}")
  psql "${args[@]}" -v ON_ERROR_STOP=1 -Atc "$1"
}

# Extrait un champ d'un JSON par chemin pointé (ex: "data.compte.totalVendu",
# "data.data.0.livraisons.1.statutPaiement"). Imprime "" si absent.
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

# Appel HTTP authentifié. Positionne les globales BODY et HTTP_STATUS —
# appeler directement (jamais via $(...), qui perdrait les globales dans une
# sous-coquille).
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

# Lit /clients/{uid}/compte et positionne COMPTE (JSON du sous-objet "compte").
compte() { # $1 = clientUid
  call GET "/clients/$1/compte"
  COMPTE=$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])
print(json.dumps((d.get('data') or {}).get('compte') or {}))
" "$BODY")
}

champ() { # $1=json-objet $2=cle -> valeur (ou vide)
  python3 -c "
import json, sys
d = json.loads(sys.argv[1])
v = d.get(sys.argv[2])
print(v if v is not None else '')
" "$1" "$2"
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
    echo "OK    $libelle (attendu=$attendu, obtenu=$obtenu)"
  else
    echo "ECHEC $libelle : attendu=$attendu, obtenu=$obtenu"
    FAILURES=$((FAILURES + 1))
  fi
}

# Les deux invariants comptables du Step 2 du brief, appliqués à $1 = JSON du
# compte (voir compte() ci-dessus).
invariants() { # $1=JSON-compte $2=libelle
  local json="$1" label="$2" out rc
  out=$(python3 - "$json" <<'PY'
import json, sys
c = json.loads(sys.argv[1])
ok1 = abs((c["totalPaye"] - c["totalRembourse"]) - (c["totalImputeVentes"] + c["avance"])) < 0.01
ok2 = abs(c["totalVendu"] - (c["totalImputeVentes"] + c["resteAPayer"])) < 0.01
print("OK" if ok1 and ok2 else "ECHEC invariants", c)
sys.exit(0 if ok1 and ok2 else 1)
PY
)
  rc=$?
  if [ $rc -eq 0 ]; then
    echo "OK    invariants $label ($out)"
  else
    echo "ECHEC invariants $label : $out"
    FAILURES=$((FAILURES + 1))
  fi
}

# Compteur global de téléphone : le téléphone est obligatoire ET unique par
# ferme côté ClientServiceImpl.create (contrairement au commentaire "optionnel"
# de ClientCreate.java, qui ne reflète pas la validation réelle du service).
# Positionne CLIENT_UID (globale) — appeler directement, jamais via $(...), le
# compteur ne devrait pas être perdu dans une sous-coquille.
CLIENT_TEL_SEQ=$((70000000 + (RANDOM % 9000) * 1000))
nouveau_client() { # $1=nom -> positionne CLIENT_UID
  CLIENT_TEL_SEQ=$((CLIENT_TEL_SEQ + 1))
  call POST /clients/create "{\"nom\":\"$1\",\"telephone\":\"$CLIENT_TEL_SEQ\"}"
  CLIENT_UID=$(jpath "$BODY" "data.uniqueId")
  if [ -z "$CLIENT_UID" ]; then
    echo "ECHEC : création du client '$1' a échoué ($HTTP_STATUS) : $BODY"
    FAILURES=$((FAILURES + 1))
  fi
}

message_contient() {
  local msg
  msg=$(jpath "$BODY" "errors.0")
  if [[ "$msg" == *"$2"* ]]; then
    echo "OK    $1 (contient « $2 »)"
  else
    echo "ECHEC $1 : obtenu=$msg"
    FAILURES=$((FAILURES + 1))
  fi
}

login "$ADMIN_EMAIL" "$ADMIN_PWD"
[ -n "$TOKEN" ] && [ "$TOKEN" != "None" ] || { echo "ECHEC connexion admin"; exit 1; }
FARM_ID=$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")
BOUTIQUE_UID=$(psql_run "SELECT unique_id FROM magasins_vente WHERE nom = 'Boutique Scen' AND farm_id = $FARM_ID AND coalesce(removed,false) = false LIMIT 1")
STOCK_UID=$(psql_run "SELECT unique_id FROM magasins_vente WHERE nom = 'Stock Scen' AND farm_id = $FARM_ID AND coalesce(removed,false) = false LIMIT 1")
read -r PROJET_UID BATIMENT_UID < <(psql_run "SELECT p.unique_id || ' ' || b.unique_id FROM projets p JOIN occupations_batiments o ON o.projet_id = p.id JOIN batiments b ON b.id = o.batiment_id WHERE p.farm_id = $FARM_ID AND coalesce(p.removed,false) = false AND b.nom = 'Poulailler Scen' LIMIT 1")
[ -n "$BOUTIQUE_UID" ] && [ -n "$STOCK_UID" ] && [ -n "$PROJET_UID" ] || { echo "ECHEC : base non seedée (lancer scenarios-circuit-client.sh)"; exit 1; }
SUFFIXE=$(python3 -c 'import uuid; print(uuid.uuid4().hex[:6])')

solde_vendeur() { psql_run "SELECT coalesce((SELECT solde FROM soldes_vendeur s JOIN utilisateurs u ON u.id = s.vendeur_id WHERE u.email = '$ADMIN_EMAIL' AND coalesce(s.removed,false) = false LIMIT 1), 0)"; }

echo "== 1. Vente sans client puis ajout du client =="
nouveau_client "Modif vente $SUFFIXE"
client="$CLIENT_UID"
solde_avant=$(solde_vendeur)
call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"quantiteOeufs\":3,\"prixUnitaire\":1000,\"montant\":3000,\"montantRapporte\":2500}"
verifier "vente sans client HTTP" "201" "$HTTP_STATUS"
vente=$(jpath "$BODY" "data.uniqueId")
verifier "écart de 500 au solde du vendeur" "$(python3 -c "print($solde_avant + 500)")" "$(solde_vendeur)"
call PUT "/ventes-oeufs/update/$vente" "{\"clientUniqueId\":\"$client\"}"
verifier "ajout du client HTTP" "200" "$HTTP_STATUS"
compte "$client"
verifier "client : totalVendu" "3000" "$(champ "$COMPTE" totalVendu)"
verifier "client : totalPaye (montant rapporté repris)" "2500" "$(champ "$COMPTE" totalPaye)"
verifier "client : resteAPayer" "500" "$(champ "$COMPTE" resteAPayer)"
invariants "$COMPTE" "après ajout"
verifier "solde vendeur revenu à l'initial" "$solde_avant" "$(solde_vendeur)"
verifier "plus de montant rapporté sur la vente" "" "$(psql_run "SELECT coalesce(montant_rapporte::text,'') FROM ventes_oeufs WHERE unique_id = '$vente'")"
verifier "paiement client comptabilisé une seule fois" "1" "$(psql_run "SELECT count(*) FROM paiements_client p JOIN clients c ON c.id = p.client_id WHERE c.unique_id = '$client' AND coalesce(p.removed,false) = false")"

encaisse_jour() { call GET "/transactions/stats?dateDebut=$(date +%F)&dateFin=$(date +%F)"; jpath "$BODY" "data.totalEncaisse"; }
paiement_vente_statut() { psql_run "SELECT string_agg(statut, ',' ORDER BY id) FROM paiements_client WHERE vente_cible_unique_id = '$vente' AND origine = 'VENTE'"; }

echo "== 2. Retrait du client : l'argent reçu à la vente repasse en montant rapporté =="
encaisse_avant=$(encaisse_jour)
call GET "/ventes/list?dateDebut=$(date +%F)&dateFin=$(date +%F)"
verifier "liste des ventes : payé à la vente exposé (2500)" "2500.0" "$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])['data']
items = d.get('data') if isinstance(d, dict) else d
print(next((x.get('payeALaVente') for x in items if x.get('uniqueId') == '$vente'), ''))" "$BODY")"
call PUT "/ventes-oeufs/update/$vente" '{"clientUniqueId":""}'
verifier "retrait sans montant rapporté HTTP (repris par défaut)" "200" "$HTTP_STATUS"
verifier "montant rapporté par défaut = reçu à la vente" "2500" "$(psql_run "SELECT montant_rapporte FROM ventes_oeufs WHERE unique_id = '$vente'")"
verifier "paiement reçu à la vente annulé" "ANNULE" "$(paiement_vente_statut)"
message_ok=$(psql_run "SELECT count(*) FROM paiements_client WHERE vente_cible_unique_id = '$vente' AND origine = 'VENTE' AND motif_annulation LIKE 'Client retiré de la vente%'")
verifier "motif d'annulation tracé" "1" "$message_ok"
verifier "transaction du paiement retirée" "t" "$(psql_run "SELECT bool_and(coalesce(t.removed,false)) FROM transactions t JOIN paiements_client p ON p.unique_id = t.source_unique_id WHERE p.vente_cible_unique_id = '$vente' AND p.origine = 'VENTE'")"
compte "$client"
verifier "client : totalVendu après retrait" "0" "$(champ "$COMPTE" totalVendu)"
verifier "client : totalPaye (paiement annulé)" "0" "$(champ "$COMPTE" totalPaye)"
verifier "client : pas d'avance" "0" "$(champ "$COMPTE" avance)"
invariants "$COMPTE" "après retrait"
verifier "vendeur : écart de 500 revenu (2500 rapportés sur 3000)" "$(python3 -c "print($solde_avant + 500)")" "$(solde_vendeur)"
verifier "vente sans client" "" "$(psql_run "SELECT coalesce(client_id::text,'') FROM ventes_oeufs WHERE unique_id = '$vente'")"
verifier "encaissé du jour inchangé (2500 au vendeur au lieu du client)" "$encaisse_avant" "$(encaisse_jour)"

echo "== 2b. Retrait avec un règlement du client en plus : le règlement redevient une avance =="
call PUT "/ventes-oeufs/update/$vente" "{\"clientUniqueId\":\"$client\"}"
verifier "client ré-ajouté HTTP" "200" "$HTTP_STATUS"
call POST /paiements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":500,\"mode\":\"ESPECES\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$vente\"}"
verifier "règlement de 500 HTTP" "201" "$HTTP_STATUS"
call PUT "/ventes-oeufs/update/$vente" '{"clientUniqueId":"","montantRapporte":2000}'
verifier "retrait avec montant rapporté explicite HTTP" "200" "$HTTP_STATUS"
verifier "montant rapporté explicite gardé" "2000" "$(psql_run "SELECT montant_rapporte FROM ventes_oeufs WHERE unique_id = '$vente'")"
compte "$client"
verifier "client : avance = règlement de 500" "500" "$(champ "$COMPTE" avance)"
invariants "$COMPTE" "après retrait 2b"
verifier "vendeur : écart de 1000 (2000 rapportés sur 3000)" "$(python3 -c "print($solde_avant + 1000)")" "$(solde_vendeur)"

echo "== 2c. Retrait refusé si l'argent reçu à la vente a été remboursé ou règle une autre vente =="
# Le client a 500 d'avance (2b) : on les lui rembourse d'abord pour repartir de zéro.
call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":500,\"mode\":\"ESPECES\",\"motif\":\"Avance rendue (test)\"}"
verifier "remboursement de l'avance HTTP" "201" "$HTTP_STATUS"
call PUT "/ventes-oeufs/update/$vente" "{\"clientUniqueId\":\"$client\"}"
verifier "client ré-ajouté (2000 repris) HTTP" "200" "$HTTP_STATUS"
call PUT "/ventes-oeufs/update/$vente" '{"montant":1500}'
verifier "montant ramené à 1500 : 500 du paiement à la vente en avance HTTP" "200" "$HTTP_STATUS"
call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":500,\"mode\":\"ESPECES\",\"motif\":\"Trop percu rendu (test)\"}"
verifier "remboursement de 500 HTTP" "201" "$HTTP_STATUS"
remb=$(jpath "$BODY" "data.uniqueId")
call PUT "/ventes-oeufs/update/$vente" '{"clientUniqueId":""}'
verifier "retrait refusé (remboursé) HTTP" "400" "$HTTP_STATUS"
message_contient "message remboursement" "remboursée au client"
call PUT "/remboursements-client/annuler/$remb" '{"motif":"Annulation du test"}'
verifier "remboursement annulé HTTP" "200" "$HTTP_STATUS"
call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"clientUniqueId\":\"$client\",\"quantiteOeufs\":1,\"prixUnitaire\":1000,\"montant\":1000}"
verifier "autre vente au client (réglée par l'avance) HTTP" "201" "$HTTP_STATUS"
vente_autre=$(jpath "$BODY" "data.uniqueId")
call PUT "/ventes-oeufs/update/$vente" '{"clientUniqueId":""}'
verifier "retrait refusé (règle une autre vente) HTTP" "400" "$HTTP_STATUS"
message_contient "message autre vente" "règle une autre vente"
verifier "rien n'a bougé : client toujours sur la vente" "$client" "$(psql_run "SELECT c.unique_id FROM ventes_oeufs v JOIN clients c ON c.id = v.client_id WHERE v.unique_id = '$vente'")"
call PUT "/ventes-oeufs/deleteOrRecover/$vente_autre" '{"motif":"Vente de test (retrait client)"}'
verifier "autre vente supprimée HTTP" "200" "$HTTP_STATUS"
call PUT "/ventes-oeufs/update/$vente" '{"montant":3000}'
call PUT "/ventes-oeufs/update/$vente" '{"clientUniqueId":"","montantRapporte":3000}'
verifier "retrait possible une fois l'autre vente supprimée HTTP" "200" "$HTTP_STATUS"
compte "$client"
invariants "$COMPTE" "après 2c"
verifier "vendeur : pas d'écart (3000 rapportés sur 3000)" "$solde_avant" "$(solde_vendeur)"

echo "== 3. Œufs normaux -> cassés =="
call POST /collectes-oeufs/create "{\"projetUniqueId\":\"$PROJET_UID\",\"batimentUniqueId\":\"$BATIMENT_UID\",\"magasinStockageUniqueId\":\"$STOCK_UID\",\"date\":\"$(date -d yesterday +%F)\",\"oeufsCollectes\":10,\"oeufsCasses\":3,\"oeufsNonUtilisables\":0}"
verifier "collecte avec 3 cassés HTTP" "201" "$HTTP_STATUS"
call POST /magasin-transferts/create "{\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"projetUniqueId\":\"$PROJET_UID\",\"magasinStockageUniqueId\":\"$STOCK_UID\",\"type\":\"OEUFS_CASSES\",\"quantite\":3,\"date\":\"$(date +%F)\"}"
verifier "transfert de 3 œufs cassés HTTP" "201" "$HTTP_STATUS"
call GET "/magasins/$BOUTIQUE_UID/stock"
casses_avant=$(jpath "$BODY" "data.oeufsCassesDisponible")
bons_avant=$(jpath "$BODY" "data.oeufsDisponible")
call PUT "/ventes-oeufs/update/$vente" '{"typeOeuf":"CASSE","prixUnitaire":500,"montant":1500}'
verifier "passage en œufs cassés HTTP" "200" "$HTTP_STATUS"
verifier "type enregistré" "CASSE" "$(psql_run "SELECT type_oeuf FROM ventes_oeufs WHERE unique_id = '$vente'")"
call GET "/magasins/$BOUTIQUE_UID/stock"
verifier "stock cassés -3" "$((casses_avant - 3))" "$(jpath "$BODY" "data.oeufsCassesDisponible")"
verifier "stock normaux +3" "$((bons_avant + 3))" "$(jpath "$BODY" "data.oeufsDisponible")"
verifier "écart vendeur recalculé (1500 vendus, 3000 rapportés)" "$(python3 -c "print($solde_avant - 1500)")" "$(solde_vendeur)"
call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"quantiteOeufs\":40,\"prixUnitaire\":1000,\"montant\":40000,\"montantRapporte\":40000}"
vente2=$(jpath "$BODY" "data.uniqueId")
call PUT "/ventes-oeufs/update/$vente2" '{"typeOeuf":"CASSE"}'
verifier "cassés au-delà du stock HTTP" "400" "$HTTP_STATUS"
message_contient "message stock cassés" "Stock d'œufs cassés insuffisant"
verifier "vente 2 restée en œufs normaux" "BON" "$(psql_run "SELECT type_oeuf FROM ventes_oeufs WHERE unique_id = '$vente2'")"

echo "== 4. Vente sur une facture active : client, montant, quantité et type refusés =="
nouveau_client "Facture modif $SUFFIXE"
client_f="$CLIENT_UID"
nouveau_client "Autre client $SUFFIXE"
client_b="$CLIENT_UID"
call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"clientUniqueId\":\"$client_f\",\"quantiteOeufs\":2,\"prixUnitaire\":1000,\"montant\":2000}"
verifier "vente au client HTTP" "201" "$HTTP_STATUS"
vente_f=$(jpath "$BODY" "data.uniqueId")
call POST /factures/generer "{\"ventes\":[{\"type\":\"VENTE_OEUFS\",\"uniqueId\":\"$vente_f\"}]}"
verifier "facture générée HTTP" "201" "$HTTP_STATUS"
facture=$(jpath "$BODY" "data.uniqueId")
numero=$(jpath "$BODY" "data.numeroFacture")
for essai in "{\"clientUniqueId\":\"$client_b\"}|client A -> B" '{"clientUniqueId":""}|retrait du client' '{"montant":2500}|montant' '{"quantiteOeufs":3}|quantité' '{"typeOeuf":"CASSE"}|type'; do
  call PUT "/ventes-oeufs/update/$vente_f" "${essai%%|*}"
  verifier "facturée : ${essai##*|} refusé HTTP" "400" "$HTTP_STATUS"
  message_contient "facturée : ${essai##*|}, message" "sur la facture $numero : annulez d'abord la facture"
done
call PUT "/ventes-oeufs/update/$vente_f" '{"montant":2000,"quantiteOeufs":2}'
verifier "facturée : valeurs renvoyées identiques acceptées HTTP" "200" "$HTTP_STATUS"
call PUT "/ventes-oeufs/update/$vente_f" "{\"date\":\"$(date -d yesterday +%F)\"}"
verifier "facturée : date modifiable HTTP" "200" "$HTTP_STATUS"
call PUT "/factures/$facture/annuler" '{"motif":"Facture de test annulée"}'
verifier "facture annulée HTTP" "200" "$HTTP_STATUS"
call PUT "/ventes-oeufs/update/$vente_f" "{\"clientUniqueId\":\"$client_b\"}"
verifier "facture annulée : client A -> B accepté HTTP" "200" "$HTTP_STATUS"

echo "== 5. Vente supprimée : modification refusée =="
call PUT "/ventes-oeufs/deleteOrRecover/$vente_f" '{"motif":"Vente de test (facture)"}'
verifier "vente supprimée HTTP" "200" "$HTTP_STATUS"
call PUT "/ventes-oeufs/update/$vente_f" '{"montant":1000}'
verifier "modification d'une vente supprimée HTTP" "400" "$HTTP_STATUS"
message_contient "message vente supprimée" "restaurez-la avant de la modifier"

echo "== Remise en état =="
call PUT "/ventes-oeufs/update/$vente" '{"montantRapporte":1500}'
call PUT "/ventes-oeufs/deleteOrRecover/$vente" '{"motif":"Vente de test (modification client/type)"}'
call PUT "/ventes-oeufs/deleteOrRecover/$vente2" '{"motif":"Vente de test (modification client/type)"}'
verifier "solde vendeur final" "$solde_avant" "$(solde_vendeur)"

echo
if [ "$FAILURES" -eq 0 ]; then echo "Tous les contrôles sont OK (0 échec)."; exit 0; else echo "$FAILURES échec(s)."; exit 1; fi
