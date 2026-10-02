#!/usr/bin/env bash
# Pays, devise et modes de paiement par ferme (2026-10-02).
#
# A. Ferme existante (seedée par scenarios-circuit-client.sh) : ML / XOF par défaut, modes
#    du Mali. Passage SN / XOF : liste du Sénégal proposée (Wave, Free Money). Passage CI.
# B. Modes configurés (CI) : Orange Money décoché, « Paiement Taptap » ajouté. Liste des
#    modes actifs ; paiement avec le mode ajouté (AUTRE + libellé, visible dans
#    l'historique du client et la description comptable) ; MTN (standard non historique)
#    accepté ; Free Money (non coché) refusé ; ORANGE_MONEY décoché envoyé par un ancien
#    téléphone (paiement et vente d'œufs) toujours accepté.
# C. Deuxième ferme en EUR : arrondi au centime (100,255 -> 100,26) pendant que la ferme
#    XOF garde l'arrondi au franc (1000,5 -> 1001) ; description en €.
# D. Ferme principale en NGN : facture PDF et rapport projet PDF écrits en NGN, vente
#    d'œufs au centime ; retour ML / XOF à la fin (les autres scripts tournent en XOF).
# E. Droits : un COMPTABLE ne peut pas changer devise ni modes ; pays/devise inconnus refusés.
#
# Pré-requis : backend + Postgres démarrés, base seedée par scenarios-circuit-client.sh
# (ADMIN admin@t.local / Test1234!, COMPTABLE compta@t.local, magasin « Boutique Scen »
# avec du stock d'œufs, projet « Projet Scenarios Circuit Client »), super-admin seedé.
# Rejouable : chaque passage crée ses clients, la ferme EUR est réutilisée.
#
# Variables : BASE, PGHOST, PGPORT, PGUSER, PGDATABASE, SUPERADMIN_ID / SUPERADMIN_PWD.
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
COMPTA_EMAIL="compta@t.local"
EUR_EMAIL="admin-eur@t.local"

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

contient() { # $1=libelle $2=aiguille $3=texte
  if printf '%s' "$3" | grep -qF -- "$2"; then
    echo "OK    $1 (contient « $2 »)"; PASS=$((PASS + 1))
  else
    echo "ECHEC $1 : « $2 » absent de : $(printf '%s' "$3" | head -c 300)"
    FAILURES=$((FAILURES + 1))
  fi
}

CLIENT_TEL_SEQ=$((86000000 + RANDOM))
nouveau_client() { # $1=nom -> CLIENT_UID
  CLIENT_TEL_SEQ=$((CLIENT_TEL_SEQ + 1))
  call POST /clients/create "{\"nom\":\"$1 $CLIENT_TEL_SEQ\",\"telephone\":\"$CLIENT_TEL_SEQ\"}"
  CLIENT_UID=$(jpath "$BODY" "data.uniqueId")
  [ -z "$CLIENT_UID" ] && { echo "ECHEC création client '$1' ($HTTP_STATUS) : $BODY"; FAILURES=$((FAILURES + 1)); }
}

codes_actifs() { # BODY = réponse /farm-settings/modes-paiement -> "CODE1,CODE2"
  python3 -c "
import json, sys
print(','.join(m['code'] for m in (json.loads(sys.argv[1]).get('data') or [])))" "$BODY"
}

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT
echo "== Base API : $BASE =="
login "$ADMIN_EMAIL" "$ADMIN_PWD"
[ -z "$TOKEN" ] && { echo "ECHEC : connexion ADMIN impossible (lancer d'abord scenarios-circuit-client.sh)"; exit 1; }
FARM_ID=$(psql_run "select farm_id from utilisateurs where email='$ADMIN_EMAIL'")
# Rejouable : on repart de la configuration d'origine.
psql_run "update farms set pays_code=null, devise=null where id=$FARM_ID; delete from modes_paiement_ferme where farm_id=$FARM_ID" >/dev/null

echo "== A. Défauts et changement de pays =="
call GET /farm-settings/devise
verifier "A défaut pays" "ML" "$(jpath "$BODY" data.pays)"
verifier "A défaut devise" "XOF" "$(jpath "$BODY" data.devise)"
verifier "A défaut symbole" "FCFA" "$(jpath "$BODY" data.symbole)"
verifier "A défaut décimales" "0" "$(jpath "$BODY" data.decimales)"
call GET /farms/me
verifier "A /farms/me devise" "XOF" "$(jpath "$BODY" data.devise)"
call GET /farm-settings/modes-paiement
verifier "A modes Mali" "ESPECES,ORANGE_MONEY,MOOV_MONEY,WAVE,VIREMENT,CHEQUE,AUTRE" "$(codes_actifs)"
call GET /farm-settings/catalogue-pays
verifier "A catalogue SN -> XOF" "XOF" "$(python3 -c "
import json,sys
print(next(p['devise'] for p in json.loads(sys.argv[1])['data']['pays'] if p['code']=='SN'))" "$BODY")"

call PUT /farm-settings/devise '{"pays":"SN","devise":"XOF"}'
verifier "A passage SN HTTP" "200" "$HTTP_STATUS"
verifier "A SN pays" "SN" "$(jpath "$BODY" data.pays)"
call GET /farm-settings/modes-paiement
verifier "A modes Sénégal" "ESPECES,WAVE,ORANGE_MONEY,FREE_MONEY,VIREMENT,CHEQUE,AUTRE" "$(codes_actifs)"
verifier "A libellé Free Money" "Free Money" "$(jpath "$BODY" data.3.libelle)"

call PUT /farm-settings/devise '{"pays":"CI"}'
verifier "A passage CI (devise proposée)" "XOF" "$(jpath "$BODY" data.devise)"
call GET /farm-settings/modes-paiement
verifier "A modes Côte d'Ivoire" "ESPECES,ORANGE_MONEY,MTN_MONEY,MOOV_MONEY,WAVE,VIREMENT,CHEQUE,AUTRE" "$(codes_actifs)"

echo "== E. Droits et validation =="
call PUT /farm-settings/devise '{"pays":"ZZ","devise":"XOF"}'
verifier "E pays inconnu" "400" "$HTTP_STATUS"
call PUT /farm-settings/devise '{"pays":"CI","devise":"BTC"}'
verifier "E devise inconnue" "400" "$HTTP_STATUS"
call PUT /farm-settings/modes-paiement '[{"code":"ESPECES","actif":false}]'
verifier "E aucun mode coché refusé" "400" "$HTTP_STATUS"
ADMIN_TOKEN="$TOKEN"
login "$COMPTA_EMAIL" "$ADMIN_PWD"
call PUT /farm-settings/devise '{"pays":"SN","devise":"XOF"}'
verifier "E COMPTABLE devise refusé" "403" "$HTTP_STATUS"
call PUT /farm-settings/modes-paiement '[{"code":"ESPECES","actif":true}]'
verifier "E COMPTABLE modes refusé" "403" "$HTTP_STATUS"
call GET /farm-settings/modes-paiement
verifier "E COMPTABLE lit les modes" "200" "$HTTP_STATUS"
TOKEN="$ADMIN_TOKEN"

echo "== B. Modes configurés =="
call PUT /farm-settings/modes-paiement '[{"code":"ESPECES","actif":true},{"code":"ORANGE_MONEY","actif":false},{"code":"MTN_MONEY","actif":true},{"code":"WAVE","actif":true},{"code":"FREE_MONEY","actif":false},{"code":"","libelle":"Paiement Taptap","actif":true}]'
verifier "B configuration HTTP" "200" "$HTTP_STATUS"
call GET /farm-settings/modes-paiement
verifier "B modes actifs" "ESPECES,MTN_MONEY,WAVE,PERSO_PAIEMENT_TAPTAP" "$(codes_actifs)"
verifier "B mode ajouté personnalisé" "True" "$(jpath "$BODY" data.3.personnalise)"
call GET /farm-settings/modes-paiement/configuration
verifier "B configuration complète (6)" "6" "$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])['data']))" "$BODY")"

nouveau_client "Devise B"; CB="$CLIENT_UID"
call POST /paiements-client/create "{\"clientUniqueId\":\"$CB\",\"montant\":1000,\"mode\":\"PERSO_PAIEMENT_TAPTAP\"}"
verifier "B paiement mode ajouté HTTP" "201" "$HTTP_STATUS"
verifier "B paiement mode ajouté = AUTRE" "AUTRE" "$(jpath "$BODY" data.mode)"
verifier "B paiement mode ajouté libellé" "Paiement Taptap" "$(jpath "$BODY" data.modeLibelle)"
P1=$(jpath "$BODY" data.uniqueId)
verifier "B libellé stocké" "AUTRE|Paiement Taptap" "$(psql_run "select mode||'|'||mode_libelle from paiements_client where unique_id='$P1'")"
contient "B description comptable" "Paiement Taptap" "$(psql_run "select description from transactions where source_unique_id='$P1'")"

call POST /paiements-client/create "{\"clientUniqueId\":\"$CB\",\"montant\":500,\"mode\":\"MTN_MONEY\"}"
verifier "B MTN accepté" "201" "$HTTP_STATUS"
verifier "B MTN libellé" "MTN Mobile Money" "$(jpath "$BODY" data.modeLibelle)"
call POST /paiements-client/create "{\"clientUniqueId\":\"$CB\",\"montant\":500,\"mode\":\"FREE_MONEY\"}"
verifier "B Free Money non coché refusé" "400" "$HTTP_STATUS"
call POST /paiements-client/create "{\"clientUniqueId\":\"$CB\",\"montant\":500,\"mode\":\"BITCOIN\"}"
verifier "B mode inconnu refusé" "400" "$HTTP_STATUS"
# Ancien téléphone (APK 1.34/1.35) : valeur historique décochée, jamais refusée.
call POST /paiements-client/create "{\"clientUniqueId\":\"$CB\",\"montant\":700,\"mode\":\"ORANGE_MONEY\"}"
verifier "B ORANGE_MONEY décoché accepté" "201" "$HTTP_STATUS"
verifier "B ORANGE_MONEY stocké tel quel" "ORANGE_MONEY" "$(jpath "$BODY" data.mode)"
verifier "B ORANGE_MONEY libellé" "Orange Money" "$(jpath "$BODY" data.modeLibelle)"
call GET "/magasins/list"
BOUTIQUE_UID=$(python3 -c "
import json, sys
print(next((m['uniqueId'] for m in (json.loads(sys.argv[1]).get('data') or []) if m.get('nom')=='Boutique Scen'), ''))" "$BODY")
call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"clientUniqueId\":\"$CB\",\"quantiteOeufs\":4,\"prixUnitaire\":100,\"montant\":400,\"montantRapporte\":400,\"modePaiement\":\"ORANGE_MONEY\"}"
verifier "B vente téléphone ORANGE_MONEY décoché" "201" "$HTTP_STATUS"
call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"clientUniqueId\":\"$CB\",\"quantiteOeufs\":3,\"prixUnitaire\":100,\"montant\":300,\"montantRapporte\":300,\"modePaiement\":\"PERSO_PAIEMENT_TAPTAP\"}"
verifier "B vente avec mode ajouté" "201" "$HTTP_STATUS"
V2=$(jpath "$BODY" data.uniqueId)
verifier "B paiement de la vente en mode ajouté" "AUTRE|Paiement Taptap" "$(psql_run "select mode||'|'||mode_libelle from paiements_client where vente_cible_unique_id='$V2'")"
call POST /remboursements-client/create "{\"clientUniqueId\":\"$CB\",\"montant\":100,\"mode\":\"PERSO_PAIEMENT_TAPTAP\",\"motif\":\"Test devise\"}"
verifier "B remboursement mode ajouté" "Paiement Taptap" "$(jpath "$BODY" data.modeLibelle)"
call GET "/clients/$CB/report"
contient "B historique client avec libellé" "Paiement Taptap" "$BODY"

echo "== C. Deuxième ferme en EUR =="
login "$EUR_EMAIL" "$ADMIN_PWD"
if [ -z "$TOKEN" ]; then
  login "$SUPERADMIN_ID" "$SUPERADMIN_PWD"
  call POST /users/create "{\"fullName\":\"AdminEur\",\"email\":\"$EUR_EMAIL\",\"telephone\":\"70000091\",\"farmName\":\"FermeScenariosEuro\",\"roles\":[\"COMPTABLE\"]}"
  psql_run "UPDATE roles_users SET id_roles=(select id from roles where role='ADMIN') WHERE id_utilisateurs=(select id from utilisateurs where email='$EUR_EMAIL')" >/dev/null
  HASH=$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'$ADMIN_PWD', bcrypt.gensalt(10)).decode())")
  psql_run "UPDATE utilisateurs SET password='$HASH', must_change_password=false WHERE email='$EUR_EMAIL'" >/dev/null
  login "$EUR_EMAIL" "$ADMIN_PWD"
fi
EUR_TOKEN="$TOKEN"
call PUT /farm-settings/devise '{"pays":"AUTRE","devise":"EUR"}'
verifier "C ferme EUR HTTP" "200" "$HTTP_STATUS"
verifier "C décimales EUR" "2" "$(jpath "$BODY" data.decimales)"
verifier "C symbole EUR" "€" "$(jpath "$BODY" data.symbole)"
call GET /farm-settings/modes-paiement
verifier "C modes autre pays" "ESPECES,VIREMENT,MOBILE_MONEY,CARTE,CHEQUE,AUTRE" "$(codes_actifs)"
nouveau_client "Devise EUR"; CE="$CLIENT_UID"
call POST /paiements-client/create "{\"clientUniqueId\":\"$CE\",\"montant\":100.255,\"mode\":\"CARTE\"}"
verifier "C paiement EUR HTTP" "201" "$HTTP_STATUS"
verifier "C arrondi au centime" "100.26" "$(jpath "$BODY" data.montant)"
verifier "C carte = AUTRE + libellé" "Carte bancaire" "$(jpath "$BODY" data.modeLibelle)"
PE=$(jpath "$BODY" data.uniqueId)
contient "C journal en euros" "100,26 €" "$(psql_run "select action from logs where entity_type='PaiementClient' order by id desc limit 5")"
call POST /remboursements-client/create "{\"clientUniqueId\":\"$CE\",\"montant\":0.125,\"mode\":\"ESPECES\",\"motif\":\"Test centimes\"}"
verifier "C remboursement au centime" "0.13" "$(jpath "$BODY" data.montant)"
compte "$CE"
verifier "C avance au centime" "100.13" "$(champ "$COMPTE" avance)"
# Ferme XOF au même moment : arrondi au franc inchangé.
TOKEN="$ADMIN_TOKEN"
call POST /paiements-client/create "{\"clientUniqueId\":\"$CB\",\"montant\":1000.5,\"mode\":\"ESPECES\"}"
verifier "C ferme XOF arrondi au franc" "1001" "$(jpath "$BODY" data.montant)"
call GET /farm-settings/devise
verifier "C ferme XOF non touchée" "XOF" "$(jpath "$BODY" data.devise)"

echo "== D. Textes générés dans la devise =="
call PUT /farm-settings/devise '{"pays":"NG","devise":"NGN"}'
verifier "D passage NGN" "NGN" "$(jpath "$BODY" data.devise)"
nouveau_client "Devise NGN"; CN="$CLIENT_UID"
call POST /ventes-oeufs/create "{\"date\":\"$(date +%F)\",\"magasinUniqueId\":\"$BOUTIQUE_UID\",\"clientUniqueId\":\"$CN\",\"quantiteOeufs\":3,\"prixUnitaire\":1.255,\"montant\":3.765,\"montantRapporte\":0}"
verifier "D vente NGN HTTP" "201" "$HTTP_STATUS"
VN=$(jpath "$BODY" data.uniqueId)
verifier "D vente arrondie au centime" "3.77" "$(jpath "$BODY" data.montant)"
call POST /factures/generer "{\"sourceType\":\"VENTE_OEUFS\",\"sourceUniqueId\":\"$VN\"}"
FN=$(jpath "$BODY" data.uniqueId)
verifier "D facture générée" "201" "$HTTP_STATUS"
curl -sS -o "$TMPD/facture.pdf" "$BASE/factures/$FN/pdf" -H "Authorization: Bearer $TOKEN"
contient "D facture PDF en NGN" "3,77 NGN" "$(pdftotext "$TMPD/facture.pdf" - 2>/dev/null)"
call GET "/projets/list?page=0&size=50"
PROJET_UID=$(python3 -c "
import json, sys
items = (json.loads(sys.argv[1]).get('data') or {}).get('data') or []
print(next((p['uniqueId'] for p in items if p.get('titre') == 'Projet Scenarios Circuit Client'), ''))" "$BODY")
curl -sS -o "$TMPD/rapport.pdf" "$BASE/projets/$PROJET_UID/rapport-pdf?dateDebut=2026-01-01&dateFin=$(date +%F)" -H "Authorization: Bearer $TOKEN"
contient "D rapport projet PDF en NGN" " NGN" "$(pdftotext "$TMPD/rapport.pdf" - 2>/dev/null)"
call PUT "/factures/$FN/annuler" '{"motif":"test devise"}'

# Retour à la configuration d'origine (les autres scripts tournent en XOF).
call PUT /farm-settings/devise '{"pays":"ML","devise":"XOF"}'
verifier "D retour ML/XOF" "XOF" "$(jpath "$BODY" data.devise)"
psql_run "delete from modes_paiement_ferme where farm_id=$FARM_ID" >/dev/null
call GET /farm-settings/modes-paiement
verifier "D modes Mali rétablis" "ESPECES,ORANGE_MONEY,MOOV_MONEY,WAVE,VIREMENT,CHEQUE,AUTRE" "$(codes_actifs)"

echo
echo "Résultat : $PASS OK, $FAILURES ECHEC"
[ "$FAILURES" -eq 0 ]
