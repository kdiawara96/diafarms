#!/usr/bin/env bash
# Règle « acompte réservé » (2026-09-26) : l'argent d'un paiement rattaché à une commande
# ouverte ne règle QUE les livraisons de cette commande ; il devient une avance libre du
# client quand la commande est terminée (tout livré, clôturée, annulée) ou supprimée.
#
# A. Le client doit une ancienne vente de 20 000 ; commande avec acompte 10 000 : l'acompte
#    reste réservé, l'ancienne vente reste due. Livraison 17 480 : 10 000 pris sur
#    l'acompte, reste 7 480.
# B. Reste d'acompte gardé pour les livraisons suivantes, puis clôture : le reste devient
#    une avance libre qui règle aussitôt l'ancienne dette.
# C. Annulation sans remboursement : l'acompte libéré règle l'ancienne dette.
# D. Paiement général et avance libre pendant qu'un acompte est réservé ; remboursement
#    sans commande refusé sur l'argent réservé, accepté depuis la commande.
# F. Reprise admin (POST /admin/reprise-acompte-reserve) sur un cas faux semé en SQL :
#    simulation (rien d'écrit), exécution réelle, rejeu idempotent.
# G. Acompte 15 000, livraison complète 10 x 1000 puis supprimée : les 15 000 sont de
#    nouveau réservés et l'ancienne dette de nouveau due ; restaurée : libre à nouveau.
# H. Commande supprimée puis récupérée : l'acompte redevient réservé et paie la livraison
#    suivante ; l'argent reçu à une livraison règle d'abord cette livraison.
# M. Refus : paiement sur une commande visant une vente hors commande, changement du
#    client d'une livraison.
#
# Pré-requis (non gérés ici) : Postgres + backend démarrés, base seedée par
# scenarios-circuit-client.sh (ferme + ADMIN admin@t.local / Test1234! + magasin
# « Boutique Scen » avec du stock d'œufs), super-admin seedé. Rejouable : chaque passage
# crée ses propres clients.
#
# Variables : BASE (défaut http://localhost:9199/diafarms/api/v1), PGHOST (127.0.0.1 par défaut, ou dossier
# socket ou hôte), PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen),
# SUPERADMIN_ID / SUPERADMIN_PWD (superadmin / change-me).
#
# Sortie : une ligne OK/ECHEC par assertion ; code de sortie 0 si tout est OK, 1 sinon.
set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
SUPERADMIN_ID="${SUPERADMIN_ID:-superadmin}"
SUPERADMIN_PWD="${SUPERADMIN_PWD:-change-me}"
ADMIN_EMAIL="admin@t.local"
ADMIN_PWD="Test1234!"

FAILURES=0
PASS=0
psql_run() { # $1 = requête SQL (une ligne)
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

# Appel HTTP authentifié. Positionne les globales BODY et HTTP_STATUS :
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
    echo "OK    $libelle (attendu=$attendu, obtenu=$obtenu)"; PASS=$((PASS + 1))
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
    echo "OK    invariants $label"; PASS=$((PASS + 1))
  else
    echo "ECHEC invariants $label : $out"
    FAILURES=$((FAILURES + 1))
  fi
}

# Compteur global de téléphone : le téléphone est obligatoire ET unique par
# ferme côté ClientServiceImpl.create (contrairement au commentaire "optionnel"
# de ClientCreate.java, qui ne reflète pas la validation réelle du service).
# Positionne CLIENT_UID (globale) ; appeler directement, jamais via $(...), le
# compteur ne devrait pas être perdu dans une sous-coquille.
nouveau_client() { # $1=nom -> positionne CLIENT_UID
  CLIENT_TEL_SEQ=$((CLIENT_TEL_SEQ + 1))
  call POST /clients/create "{\"nom\":\"$1\",\"telephone\":\"$CLIENT_TEL_SEQ\"}"
  CLIENT_UID=$(jpath "$BODY" "data.uniqueId")
  if [ -z "$CLIENT_UID" ]; then
    echo "ECHEC : création du client '$1' a échoué ($HTTP_STATUS) : $BODY"
    FAILURES=$((FAILURES + 1))
  fi
}

CLIENT_TEL_SEQ=$((83000000 + (RANDOM % 900) * 1000))
# Suffixe propre à ce passage : le script se rejoue sur la même base.
SUFFIXE="$(date +%H%M%S)-$RANDOM"

# Commande d'œufs pour $1 (client), quantité $2, prix $3, acompte $4 -> CMD_UID
nouvelle_commande() {
  call POST /commandes/create "{\"clientUniqueId\":\"$1\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"type\":\"OEUFS\",\"quantite\":$2,\"prixUnitaireEstime\":$3,\"montantEstime\":$(( $2 * $3 )),\"montantAcompte\":$4}"
  CMD_UID=$(jpath "$BODY" "data.uniqueId")
  [ -z "$CMD_UID" ] && { echo "ECHEC création commande ($HTTP_STATUS) : $BODY"; FAILURES=$((FAILURES + 1)); }
}

# Ancienne vente à crédit de $2 FCFA (œufs à 1000) au client $1, datée du 2026-09-01 -> VENTE_UID
ancienne_vente() {
  call POST /ventes-oeufs/create "{\"date\":\"2026-09-01\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"clientUniqueId\":\"$1\",\"quantiteOeufs\":$(( $2 / 1000 )),\"prixUnitaire\":1000,\"montant\":$2}"
  VENTE_UID=$(jpath "$BODY" "data.uniqueId")
  [ -z "$VENTE_UID" ] && { echo "ECHEC création vente ($HTTP_STATUS) : $BODY"; FAILURES=$((FAILURES + 1)); }
}

commande_dto() { # $1=cmd -> COMMANDE (JSON de la commande)
  call GET "/commandes/list?size=200"
  COMMANDE=$(python3 -c "
import json, sys
items = ((json.load(sys.stdin).get('data') or {}).get('data')) or []
print(json.dumps(next((c for c in items if c.get('uniqueId') == sys.argv[1]), {})))
" "$1" <<<"$BODY")
}

paye_vente() { # $1=vente uid -> Σ imputations actives (SQL)
  psql_run "select coalesce(sum(montant),0) from imputations_paiement where cible_unique_id='$1' and statut='ACTIF'"
}

echo "== Base API : $BASE =="
login "$ADMIN_EMAIL" "$ADMIN_PWD"
if [ -z "$TOKEN" ] || [ "$TOKEN" = "None" ]; then
  echo "ECHEC : connexion ADMIN impossible (base non seedée par scenarios-circuit-client.sh ?)"; exit 1
fi
call GET "/magasins/list"
BOUTIQUE_UID=$(python3 -c "
import json, sys
for m in json.loads(sys.argv[1]).get('data') or []:
    if m.get('nom') == 'Boutique Scen': print(m['uniqueId']); break
" "$BODY")
[ -z "$BOUTIQUE_UID" ] && { echo "ECHEC : magasin Boutique Scen introuvable"; exit 1; }
call GET "/magasins/$BOUTIQUE_UID/stock"
echo "Stock d'œufs dans Boutique Scen : $(jpath "$BODY" "data.oeufsDisponible") (il en faut ~150)."

scenarioA() {
  echo "== A : ancienne dette 20 000, acompte 10 000 réservé, livraison 17 480 =="
  local client ancienne cmd
  nouveau_client "Acompte reserve A $SUFFIXE"; client="$CLIENT_UID"
  ancienne_vente "$client" 20000; ancienne="$VENTE_UID"
  nouvelle_commande "$client" 100 874 10000; cmd="$CMD_UID"

  compte "$client"
  verifier "A resteAPayer (ancienne vente toujours due)" "20000" "$(champ "$COMPTE" resteAPayer)"
  verifier "A avance totale" "10000" "$(champ "$COMPTE" avance)"
  verifier "A avanceLibre" "0" "$(champ "$COMPTE" avanceLibre)"
  verifier "A avanceReservee" "10000" "$(champ "$COMPTE" avanceReservee)"
  verifier "A avance réservée à la commande" "$cmd" "$(jpath "{\"c\":$COMPTE}" "c.avancesReservees.0.commandeUniqueId")"
  verifier "A imputé sur l'ancienne vente" "0" "$(paye_vente "$ancienne")"
  invariants "$COMPTE" "A après acompte"
  commande_dto "$cmd"
  verifier "A commande acompteRecu" "10000" "$(champ "$COMMANDE" acompteRecu)"
  verifier "A commande acompteImpute" "0" "$(champ "$COMMANDE" acompteImpute)"
  verifier "A commande acompteReserve" "10000" "$(champ "$COMMANDE" acompteReserve)"

  call POST "/commandes/$cmd/livrer?quantite=20"
  verifier "A livraison HTTP" "200" "$HTTP_STATUS"
  verifier "A livraison montant" "17480" "$(jpath "$BODY" "data.livraisons.0.montant")"
  verifier "A livraison payée par l'acompte" "10000" "$(jpath "$BODY" "data.livraisons.0.paye")"
  verifier "A reste à payer de la livraison" "7480" "$(jpath "$BODY" "data.resteAPayerLivre")"
  verifier "A acompteImpute" "10000" "$(jpath "$BODY" "data.acompteImpute")"
  verifier "A acompteReserve" "0" "$(jpath "$BODY" "data.acompteReserve")"
  compte "$client"
  verifier "A resteAPayer (20 000 + 7 480)" "27480" "$(champ "$COMPTE" resteAPayer)"
  verifier "A avance après livraison" "0" "$(champ "$COMPTE" avance)"
  verifier "A imputé sur l'ancienne vente après livraison" "0" "$(paye_vente "$ancienne")"
  invariants "$COMPTE" "A après livraison"
}

scenarioB() {
  echo "== B : reste d'acompte gardé pour les livraisons suivantes, puis clôture =="
  local client ancienne cmd
  nouveau_client "Acompte reserve B $SUFFIXE"; client="$CLIENT_UID"
  ancienne_vente "$client" 5000; ancienne="$VENTE_UID"
  nouvelle_commande "$client" 100 1000 30000; cmd="$CMD_UID"
  call POST "/commandes/$cmd/livrer?quantite=10"
  verifier "B livraison 1 HTTP" "200" "$HTTP_STATUS"
  verifier "B livraison 1 payée" "10000" "$(jpath "$BODY" "data.livraisons.0.paye")"
  verifier "B acompteReserve après livraison 1" "20000" "$(jpath "$BODY" "data.acompteReserve")"
  compte "$client"
  verifier "B avanceReservee" "20000" "$(champ "$COMPTE" avanceReservee)"
  verifier "B resteAPayer (ancienne vente)" "5000" "$(champ "$COMPTE" resteAPayer)"
  call POST "/commandes/$cmd/livrer?quantite=5"
  verifier "B livraison 2 HTTP" "200" "$HTTP_STATUS"
  verifier "B acompteReserve après livraison 2" "15000" "$(jpath "$BODY" "data.acompteReserve")"
  verifier "B imputé sur l'ancienne vente avant clôture" "0" "$(paye_vente "$ancienne")"

  call PUT "/commandes/$cmd/cloturer" '{"motif":"Le client ne prendra pas le reste"}'
  verifier "B clôture HTTP" "200" "$HTTP_STATUS"
  verifier "B acompteReserve après clôture" "0" "$(jpath "$BODY" "data.acompteReserve")"
  verifier "B acompteImpute après clôture (livraisons seulement)" "15000" "$(jpath "$BODY" "data.acompteImpute")"
  compte "$client"
  verifier "B ancienne vente réglée par le reste libéré" "5000" "$(paye_vente "$ancienne")"
  verifier "B resteAPayer" "0" "$(champ "$COMPTE" resteAPayer)"
  verifier "B avance libre restante" "10000" "$(champ "$COMPTE" avanceLibre)"
  verifier "B avanceReservee" "0" "$(champ "$COMPTE" avanceReservee)"
  invariants "$COMPTE" "B"
}

scenarioC() {
  echo "== C : annulation sans remboursement, l'acompte libéré règle l'ancienne dette =="
  local client ancienne cmd
  nouveau_client "Acompte reserve C $SUFFIXE"; client="$CLIENT_UID"
  ancienne_vente "$client" 8000; ancienne="$VENTE_UID"
  nouvelle_commande "$client" 50 1000 10000; cmd="$CMD_UID"
  verifier "C ancienne vente non réglée par l'acompte" "0" "$(paye_vente "$ancienne")"
  call PUT "/commandes/$cmd/annuler" '{"motif":"Client renonce","rembourserAcompte":false}'
  verifier "C annulation HTTP" "200" "$HTTP_STATUS"
  compte "$client"
  verifier "C ancienne vente réglée" "8000" "$(paye_vente "$ancienne")"
  verifier "C avanceLibre" "2000" "$(champ "$COMPTE" avanceLibre)"
  verifier "C avanceReservee" "0" "$(champ "$COMPTE" avanceReservee)"
  invariants "$COMPTE" "C"
}

scenarioD() {
  echo "== D : paiement général, avance libre et remboursement pendant qu'un acompte est réservé =="
  local client ancienne cmd
  nouveau_client "Acompte reserve D $SUFFIXE"; client="$CLIENT_UID"
  ancienne_vente "$client" 20000; ancienne="$VENTE_UID"
  nouvelle_commande "$client" 100 1000 10000; cmd="$CMD_UID"
  call POST /paiements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":25000,\"mode\":\"ESPECES\"}"
  verifier "D paiement général HTTP" "201" "$HTTP_STATUS"
  compte "$client"
  verifier "D ancienne vente réglée par le paiement général" "20000" "$(paye_vente "$ancienne")"
  verifier "D avanceLibre" "5000" "$(champ "$COMPTE" avanceLibre)"
  verifier "D avanceReservee" "10000" "$(champ "$COMPTE" avanceReservee)"
  call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":8000,\"mode\":\"ESPECES\",\"motif\":\"Essai sur argent reserve\"}"
  verifier "D remboursement sans commande au-delà de l'avance libre HTTP" "400" "$HTTP_STATUS"
  message_contient "D message" "réservés à des commandes en cours"
  call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":1000,\"mode\":\"ESPECES\",\"motif\":\"Remboursement sur avance libre\"}"
  verifier "D remboursement sur l'avance libre HTTP" "201" "$HTTP_STATUS"
  call POST "/commandes/$cmd/livrer?quantite=16"
  verifier "D livraison HTTP" "200" "$HTTP_STATUS"
  verifier "D livraison payée (acompte 10 000 puis avance libre 4 000)" "14000" "$(jpath "$BODY" "data.livraisons.0.paye")"
  verifier "D acompteImpute" "10000" "$(jpath "$BODY" "data.acompteImpute")"
  compte "$client"
  verifier "D resteAPayer" "2000" "$(champ "$COMPTE" resteAPayer)"
  verifier "D avance" "0" "$(champ "$COMPTE" avance)"
  call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":1000,\"mode\":\"ESPECES\",\"motif\":\"Plus rien\"}"
  verifier "D remboursement sans avance HTTP" "400" "$HTTP_STATUS"
  invariants "$COMPTE" "D"

  # Remboursement depuis la commande : l'argent réservé à CETTE commande peut être rendu.
  local cmd2
  nouvelle_commande "$client" 10 1000 3000; cmd2="$CMD_UID"
  call POST /remboursements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":3000,\"mode\":\"ESPECES\",\"motif\":\"Rendu de l acompte\",\"commandeUniqueId\":\"$cmd2\"}"
  verifier "D remboursement depuis la commande HTTP" "201" "$HTTP_STATUS"
  compte "$client"
  verifier "D avanceReservee après remboursement de la commande" "0" "$(champ "$COMPTE" avanceReservee)"
  invariants "$COMPTE" "D fin"
}

scenarioG() {
  echo "== G : acompte 15 000, livraison complète de 10 x 1000 puis supprimée et restaurée =="
  local client ancienne cmd vente
  nouveau_client "Acompte reserve G $SUFFIXE"; client="$CLIENT_UID"
  ancienne_vente "$client" 20000; ancienne="$VENTE_UID"
  nouvelle_commande "$client" 10 1000 15000; cmd="$CMD_UID"
  call POST "/commandes/$cmd/livrer?quantite=10"
  verifier "G livraison complète HTTP" "200" "$HTTP_STATUS"
  verifier "G statut" "CONVERTIE" "$(jpath "$BODY" "data.statut")"
  vente=$(jpath "$BODY" "data.livraisons.0.venteUniqueId")
  verifier "G livraison payée par l'acompte" "10000" "$(paye_vente "$vente")"
  verifier "G reste de l'acompte (libre) sur l'ancienne vente" "5000" "$(paye_vente "$ancienne")"
  call PUT "/ventes-oeufs/deleteOrRecover/$vente" '{"motif":"Livraison saisie par erreur"}'
  verifier "G suppression de la livraison HTTP" "200" "$HTTP_STATUS"
  compte "$client"
  verifier "G les 15 000 de nouveau réservés" "15000" "$(champ "$COMPTE" avanceReservee)"
  verifier "G ancienne vente de nouveau due (plus rien d'imputé)" "0" "$(paye_vente "$ancienne")"
  verifier "G resteAPayer" "20000" "$(champ "$COMPTE" resteAPayer)"
  invariants "$COMPTE" "G après suppression"
  call PUT "/ventes-oeufs/deleteOrRecover/$vente"
  verifier "G restauration HTTP" "200" "$HTTP_STATUS"
  compte "$client"
  verifier "G livraison restaurée payée par l'acompte" "10000" "$(paye_vente "$vente")"
  verifier "G reste libéré sur l'ancienne vente" "5000" "$(paye_vente "$ancienne")"
  verifier "G avanceReservee après restauration" "0" "$(champ "$COMPTE" avanceReservee)"
  verifier "G resteAPayer" "15000" "$(champ "$COMPTE" resteAPayer)"
  invariants "$COMPTE" "G après restauration"
}

scenarioH() {
  echo "== H : commande supprimée puis récupérée, livraison suivante payée par l'acompte =="
  local client ancienne cmd
  nouveau_client "Acompte reserve H $SUFFIXE"; client="$CLIENT_UID"
  ancienne_vente "$client" 20000; ancienne="$VENTE_UID"
  nouvelle_commande "$client" 100 1000 10000; cmd="$CMD_UID"
  call PUT "/commandes/deleteOrRecover/$cmd"
  verifier "H suppression de la commande HTTP" "200" "$HTTP_STATUS"
  verifier "H acompte libéré sur l'ancienne vente" "10000" "$(paye_vente "$ancienne")"
  call POST "/commandes/$cmd/livrer?quantite=1"
  verifier "H commande supprimée introuvable pour une livraison" "400" "$HTTP_STATUS"
  call PUT "/commandes/deleteOrRecover/$cmd"
  verifier "H récupération de la commande HTTP" "200" "$HTTP_STATUS"
  compte "$client"
  verifier "H acompte de nouveau réservé" "10000" "$(champ "$COMPTE" avanceReservee)"
  verifier "H ancienne vente de nouveau due" "0" "$(paye_vente "$ancienne")"
  invariants "$COMPTE" "H après récupération"
  call POST "/commandes/$cmd/livrer?quantite=6"
  verifier "H livraison HTTP" "200" "$HTTP_STATUS"
  verifier "H livraison payée par l'acompte" "6000" "$(jpath "$BODY" "data.livraisons.0.paye")"
  verifier "H acompteReserve" "4000" "$(jpath "$BODY" "data.acompteReserve")"
  # Argent reçu à la livraison : il règle d'abord SA livraison, l'acompte reste réservé.
  call POST "/commandes/$cmd/livrer?quantite=3&montantRecu=3000&mode=ESPECES"
  verifier "H livraison payée à la livraison HTTP" "200" "$HTTP_STATUS"
  verifier "H livraison réglée par l'argent reçu" "3000" "$(jpath "$BODY" "data.livraisons.1.paye")"
  verifier "H acompte intact pour la suite" "4000" "$(jpath "$BODY" "data.acompteReserve")"
  compte "$client"
  verifier "H ancienne vente toujours due" "20000" "$(champ "$COMPTE" resteAPayer)"
  invariants "$COMPTE" "H"
}

scenarioM() {
  echo "== M : refus (paiement de commande visant une autre vente, client d'une livraison changé) =="
  local client autre ancienne cmd vente
  nouveau_client "Acompte reserve M $SUFFIXE"; client="$CLIENT_UID"
  nouveau_client "Acompte reserve M autre $SUFFIXE"; autre="$CLIENT_UID"
  ancienne_vente "$client" 5000; ancienne="$VENTE_UID"
  nouvelle_commande "$client" 100 1000 2000; cmd="$CMD_UID"
  call POST /paiements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":5000,\"mode\":\"ESPECES\",\"commandeUniqueId\":\"$cmd\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$ancienne\"}"
  verifier "M paiement sur commande visant une autre vente HTTP" "400" "$HTTP_STATUS"
  message_contient "M message" "ne peut régler que les livraisons de cette commande"
  verifier "M ancienne vente inchangée" "0" "$(paye_vente "$ancienne")"
  call POST "/commandes/$cmd/livrer?quantite=2"
  vente=$(jpath "$BODY" "data.livraisons.0.venteUniqueId")
  call POST /paiements-client/create "{\"clientUniqueId\":\"$client\",\"montant\":1000,\"mode\":\"ESPECES\",\"commandeUniqueId\":\"$cmd\",\"venteCibleType\":\"VENTE_OEUFS\",\"venteCibleUniqueId\":\"$vente\"}"
  verifier "M paiement sur commande visant sa livraison HTTP" "201" "$HTTP_STATUS"
  call PUT "/ventes-oeufs/update/$vente" "{\"clientUniqueId\":\"$autre\"}"
  verifier "M changement de client d'une livraison HTTP" "400" "$HTTP_STATUS"
  message_contient "M message client" "livraison de commande"
  compte "$client"
  invariants "$COMPTE" "M"
}

scenarioF() {
  echo "== F : reprise admin sur un cas faux (acompte imputé à une ancienne vente) =="
  local client ancienne cmd paiement farm token_admin rapport
  nouveau_client "Acompte reserve F $SUFFIXE"; client="$CLIENT_UID"
  ancienne_vente "$client" 20000; ancienne="$VENTE_UID"
  nouvelle_commande "$client" 100 1000 10000; cmd="$CMD_UID"
  # Cas faux semé en SQL, comme l'ancienne règle le produisait.
  paiement=$(psql_run "select p.id from paiements_client p join commandes k on k.id=p.commande_id where k.unique_id='$cmd'")
  psql_run "insert into imputations_paiement (unique_id, farm_id, client_id, paiement_id, cible_type, cible_unique_id, montant, statut, created_at, removed, archive)
            select gen_random_uuid()::text, p.farm_id, p.client_id, p.id, 'VENTE_OEUFS', '$ancienne', 10000, 'ACTIF', now(), false, false
            from paiements_client p where p.id=$paiement" >/dev/null
  compte "$client"
  verifier "F cas faux : ancienne vente payée par l'acompte" "10000" "$(paye_vente "$ancienne")"
  verifier "F cas faux : avanceReservee" "0" "$(champ "$COMPTE" avanceReservee)"
  farm=$(psql_run "select f.unique_id from farms f join clients c on c.farm_id=f.id where c.unique_id='$client'")
  token_admin="$TOKEN"

  call POST "/admin/reprise-acompte-reserve?farmUniqueId=$farm"
  verifier "F ADMIN refusé" "403" "$HTTP_STATUS"

  login "$SUPERADMIN_ID" "$SUPERADMIN_PWD"
  call POST "/admin/reprise-acompte-reserve?farmUniqueId=$farm"
  verifier "F simulation HTTP" "200" "$HTTP_STATUS"
  rapport=$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])['data']
l = next((x for x in d['lignes'] if x['clientUniqueId'] == sys.argv[2]), None)
print('%s|%s|%s|%s|%s|%s' % (d['execute'], l and len(l['mouvements']), l and l['avant']['resteAPayer'], l and l['apres']['resteAPayer'], l and l['avant']['avanceReservee'], l and l['apres']['avanceReservee']))
" "$BODY" "$client")
  verifier "F simulation : execute|mouvements|reste avant|reste après|réservé avant|réservé après" "False|1|10000.0|20000.0|0.0|10000.0" "$rapport"
  verifier "F simulation : rien d'écrit" "10000" "$(paye_vente "$ancienne")"

  call POST "/admin/reprise-acompte-reserve?farmUniqueId=$farm&executer=true"
  verifier "F exécution HTTP" "200" "$HTTP_STATUS"
  verifier "F exécution : imputation annulée" "0" "$(paye_vente "$ancienne")"
  verifier "F exécution : motif tracé" "1" "$(psql_run "select count(*) from imputations_paiement where cible_unique_id='$ancienne' and statut='ANNULE' and motif_annulation like 'Reprise acompte réservé%'")"
  call POST "/admin/reprise-acompte-reserve?farmUniqueId=$farm&executer=true"
  verifier "F rejeu : client plus concerné" "0" "$(python3 -c "
import json, sys
d = json.loads(sys.argv[1])['data']
print(sum(1 for x in d['lignes'] if x['clientUniqueId'] == sys.argv[2]))
" "$BODY" "$client")"

  TOKEN="$token_admin"
  compte "$client"
  verifier "F après reprise : resteAPayer" "20000" "$(champ "$COMPTE" resteAPayer)"
  verifier "F après reprise : avanceReservee" "10000" "$(champ "$COMPTE" avanceReservee)"
  invariants "$COMPTE" "F"
}

# Vérifie qu'un message d'erreur ($1 = libellé) contient $2 (dans errors.0 du dernier BODY).
message_contient() {
  local msg
  msg=$(jpath "$BODY" "errors.0")
  if [[ "$msg" == *"$2"* ]]; then
    echo "OK    $1 (contient « $2 »)"; PASS=$((PASS + 1))
  else
    echo "ECHEC $1 : obtenu=$msg"; FAILURES=$((FAILURES + 1))
  fi
}

scenarioA
scenarioB
scenarioC
scenarioD
scenarioF
scenarioG
scenarioH
scenarioM

echo
echo "$PASS OK, $FAILURES échec(s)."
[ "$FAILURES" -eq 0 ] && exit 0 || exit 1
