#!/usr/bin/env bash
# Verrou comptable des transactions générées par une saisie : script de bout en bout.
#
# Une transaction dont la source est un soin, une vaccination, un achat d'aliment, un
# investissement, un paiement de salaire ou les coûts de démarrage d'un projet ne se
# modifie, ne se supprime, ne se rejette et ne fait l'objet d'une demande de suppression
# QUE par sa saisie source (message qui nomme l'écran). Supprimer la saisie source retire
# toujours sa transaction. Une transaction MANUEL reste modifiable depuis la Comptabilité.
#
# Pré-requis : Postgres + backend démarrés, base seedée par scenarios-circuit-client.sh.
# Variables : BASE, PGHOST, PGPORT (55432), PGUSER (postgres), PGDATABASE (diafarms_scen),
# ADMIN_EMAIL / ADMIN_PWD. Sortie : une ligne OK/ECHEC par assertion ; code 0 si tout est OK.

set -uo pipefail

BASE="${BASE:-http://localhost:9199/diafarms/api/v1}"
PGPORT="${PGPORT:-55432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-diafarms_scen}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@t.local}"
ADMIN_PWD="${ADMIN_PWD:-Test1234!}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAIL=0

psql_run() {
  local args=(-p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE")
  [ -n "${PGHOST:-}" ] && args=(-h "$PGHOST" "${args[@]}")
  psql "${args[@]}" -v ON_ERROR_STOP=1 -Atc "$1"
}

uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
jval() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$TMP/body" "$1"; }

check() {
  local label="$1" expr="$2"
  if python3 - "$TMP/body" "$TMP/code" "$expr" <<'PY'
import json, sys
body, codef, expr = sys.argv[1:4]
code = int(open(codef).read().strip() or 0)
try:
    d = json.load(open(body))
except Exception:
    d = {}
err = " ".join(d.get("errors") or []) if isinstance(d, dict) else ""
ok = False
try:
    ok = bool(eval(expr, {"d": d, "code": code, "err": err}))
except Exception as e:
    print("   exception:", e, file=sys.stderr)
if not ok:
    print("   code HTTP:", code, "réponse:", json.dumps(d, ensure_ascii=False)[:600], file=sys.stderr)
sys.exit(0 if ok else 1)
PY
  then echo "OK     $label"; PASS=$((PASS+1))
  else echo "ECHEC  $label"; FAIL=$((FAIL+1))
  fi
}

check_eq() {
  if [ "$2" = "$3" ]; then echo "OK     $1"; PASS=$((PASS+1))
  else echo "ECHEC  $1 (attendu « $2 », obtenu « $3 »)"; FAIL=$((FAIL+1)); fi
}

api() {
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'X-Client-Type: web' ${3:+-d "$3"} > "$TMP/code"
}

login() {
  curl -s -X POST "$BASE/auth" -H 'X-Client-Type: mobile' \
    --data-urlencode grantType=password --data-urlencode "identifiant=$1" \
    --data-urlencode "password=$2" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["accessToken"])
except Exception: print("")'
}

# verrou "libellé" TRANSACTION_UID "écran attendu dans le message"
verrou() {
  local label="$1" t="$2" ecran="$3"
  api PUT "/transactions/update/$t" '{"montant":1,"description":"Retouche comptable"}'
  check "$label : modification refusée, message vers « $ecran »" "code == 400 and 'générée automatiquement' in err and '$ecran' in err"
  api PUT "/transactions/deleteOrRecover/$t" '{"motif":"Essai de suppression"}'
  check "$label : suppression refusée" "code >= 400 and 'générée automatiquement' in err"
  api PUT "/transactions/demander-suppression/$t" '{"motif":"Essai de demande"}'
  check "$label : demande de suppression refusée" "code == 400 and 'générée automatiquement' in err"
  api PUT "/transactions/rejeter/$t" '{"commentaire":"Essai de rejet"}'
  check "$label : rejet refusé" "code == 400 and 'générée automatiquement' in err"
  check_eq "$label : transaction intacte (active, VALIDE, pas de demande)" "f|VALIDE|" \
    "$(psql_run "SELECT coalesce(removed,false) || '|' || statut || '|' || coalesce(demande_suppression_par_id::text,'') FROM transactions WHERE unique_id = '$t'" | sed 's/^false/f/;s/^true/t/')"
}

tx_de_source() { psql_run "SELECT unique_id FROM transactions WHERE source_unique_id = '$1'"; }
tx_removed() { psql_run "SELECT coalesce(removed,false) FROM transactions WHERE source_unique_id = '$1'" | sed 's/^false/f/;s/^true/t/'; }

# ---------------------------------------------------------------------------
TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PWD")"
[ -n "$TOKEN" ] || { echo "ECHEC  connexion admin"; exit 1; }
FARM_ID="$(psql_run "SELECT farm_id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
read -r PROJET BATIMENT < <(psql_run "SELECT p.unique_id || ' ' || b.unique_id FROM projets p
  JOIN occupations_batiments o ON o.projet_id = p.id JOIN batiments b ON b.id = o.batiment_id
  WHERE p.farm_id = $FARM_ID AND p.removed = false ORDER BY p.id LIMIT 1")
PROJET_ID="$(psql_run "SELECT id FROM projets WHERE unique_id = '$PROJET'")"
AUJ="$(date +%F)"
SUFFIXE="$(uuid | cut -c1-8)"

echo "== 1. Achat d'aliment : transaction verrouillée, la suppression de l'achat la retire"
api POST "/alimentations/create/$PROJET" "{\"nomAliment\":\"Maïs verrou $SUFFIXE\",\"sac\":1,\"quantiteKg\":50,\"coutTotal\":12000,\"dateDistribution\":\"$AUJ\"}"
check "achat d'aliment créé" "code in (200, 201)"
ALIM="$(jval "d['data']['uniqueId']")"
T_ALIM="$(tx_de_source "$ALIM")"
check_eq "transaction ALIMENTATION générée" "ALIMENTATION" "$(psql_run "SELECT source_type FROM transactions WHERE unique_id = '$T_ALIM'")"
verrou "aliment" "$T_ALIM" "section Alimentation"
api GET "/transactions/list?page=0&size=200"
check "DTO : saisieSource renseignée pour la transaction générée" "code == 200 and any(x.get('uniqueId') == '$T_ALIM' and 'Alimentation' in (x.get('saisieSource') or '') for x in d['data']['data'])"
api DELETE "/alimentations/delete/$ALIM"
check "suppression de l'achat d'aliment (écran source)" "code == 200"
check_eq "transaction de l'achat retirée avec lui" "t" "$(tx_removed "$ALIM")"

echo "== 2. Soin : transaction verrouillée, la suppression du soin la retire"
api POST /soins/create "{\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"date\":\"$AUJ\",\"type\":\"MEDICAMENT\",\"produit\":\"Antibio $SUFFIXE\",\"quantite\":1,\"coutTotal\":3000}"
check "soin créé" "code in (200, 201)"
SOIN="$(jval "d['data']['uniqueId']")"
T_SOIN="$(tx_de_source "$SOIN")"
verrou "soin" "$T_SOIN" "Santé / Vétérinaire"
MONTANT_SOIN="$(psql_run "SELECT montant FROM transactions WHERE unique_id = '$T_SOIN'")"
api PUT "/transactions/update/$T_SOIN" "{\"montant\":$MONTANT_SOIN,\"categorie\":\"$(psql_run "SELECT categorie FROM transactions WHERE unique_id = '$T_SOIN'")\",\"batimentUniqueId\":\"\"}"
check "soin : modification du SEUL rattachement (poulailler retiré, autres champs identiques) acceptée" "code == 200 and d['data'].get('batimentUniqueId') is None and d['data']['rattachementModifiable'] is True"
api PUT "/transactions/update/$T_SOIN" "{\"batimentUniqueId\":\"$BATIMENT\"}"
check "soin : poulailler remis depuis la Comptabilité" "code == 200 and d['data'].get('batimentUniqueId') == '$BATIMENT'"
api PUT "/transactions/update/$T_SOIN" "{\"montant\":$MONTANT_SOIN,\"batimentUniqueId\":\"\",\"description\":\"Autre texte\"}"
check "soin : rattachement + description modifiée : refusé en bloc" "code == 400 and 'description' in err"
check_eq "soin : rien n'a changé après le refus" "$BATIMENT" "$(psql_run "SELECT b.unique_id FROM transactions t JOIN batiments b ON b.id = t.batiment_id WHERE t.unique_id = '$T_SOIN'")"
api PUT "/transactions/update/$T_SOIN" '{"montant":1}'
check "soin : modification du montant refusée" "code == 400 and 'montant' in err"
api PUT "/transactions/rejeter/$T_SOIN" '{"commentaire":"Essai de rejet"}'
check "soin : message du rejet renvoie vers la suppression de la saisie" "code == 400 and 'pour la rejeter, supprimez cette saisie' in err"
api PUT "/soins/deleteOrRecover/$SOIN"
check "suppression du soin (écran source)" "code == 200"
check_eq "transaction du soin retirée avec lui" "t" "$(tx_removed "$SOIN")"
api PUT "/soins/deleteOrRecover/$SOIN"
check_eq "restauration du soin : transaction restaurée" "f" "$(tx_removed "$SOIN")"

echo "== 3. Autres sources générées (transactions posées en base)"
for src in "VACCINATION:Santé / Vétérinaire" "INVESTISSEMENT:page Investissements" "SALAIRE:page Salaires" \
           "PROJET_ACHAT_SUJETS:page Projets" "PROJET_CHARGES:page Projets"; do
  type="${src%%:*}"; ecran="${src#*:}"; t="$(uuid)"
  psql_run "INSERT INTO transactions (unique_id, ref, type, date, montant, categorie, statut, source_type, source_unique_id, farm_id, projet_id, removed, archive, created_at)
    VALUES ('$t', 'VR-$(uuid | cut -c1-10)', 'SORTIE', current_date, 1000, 'Test verrou', 'VALIDE', '$type', 'verrou-$t', $FARM_ID, $PROJET_ID, false, false, now())" >/dev/null
  verrou "$type" "$t" "$ecran"
done

echo "== 4. Demande faite avant le verrou : seul le refus reste possible"
t="$(uuid)"
ADMIN_ID="$(psql_run "SELECT id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
psql_run "INSERT INTO transactions (unique_id, ref, type, date, montant, categorie, statut, source_type, source_unique_id, farm_id, projet_id, removed, archive, created_at, demande_suppression_par_id, date_demande_suppression, motif_suppression)
  VALUES ('$t', 'VR-$(uuid | cut -c1-10)', 'SORTIE', current_date, 1000, 'Test verrou', 'VALIDE', 'SOINS', 'verrou-$t', $FARM_ID, $PROJET_ID, false, false, now(), $ADMIN_ID, now(), 'ancienne demande')" >/dev/null
api PUT "/transactions/confirmer-suppression/$t"
check "confirmer la suppression : refusé" "code == 400 and 'générée automatiquement' in err"
api PUT "/transactions/annuler-demande-suppression/$t"
check "refuser la demande : permis" "code == 200"

echo "== 5. Transaction manuelle : toujours modifiable depuis la Comptabilité"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Manuelle $SUFFIXE\",\"montant\":700,\"categorie\":\"Divers\"}"
T_MAN="$(jval "d['data']['uniqueId']")"
api PUT "/transactions/update/$T_MAN" '{"montant":800}'
check "modification d'une transaction MANUEL : acceptée" "code == 200 and d['data']['montant'] == 800 and d['data'].get('saisieSource') is None"
api PUT "/transactions/deleteOrRecover/$T_MAN" '{"motif":"Doublon de test"}'
check "suppression d'une transaction MANUEL : acceptée" "code == 200"

echo "== 6. Projet supprimé puis restauré : ses transactions générées suivent"
ADMIN_ID="$(psql_run "SELECT id FROM utilisateurs WHERE email = '$ADMIN_EMAIL'")"
RACE_ID="$(psql_run "SELECT race_id FROM projets WHERE unique_id = '$PROJET'")"
api POST /batiments/create "{\"nom\":\"Poulailler verrou $SUFFIXE\",\"capacite\":500}"
BAT2_ID="$(jval "d['data']['id']")"
api POST /projets/create "{\"titre\":\"Projet verrou $SUFFIXE\",\"responsableId\":$ADMIN_ID,\"dateDebut\":\"$AUJ\",\"dateFinPrevue\":\"2027-12-31\",\"nbSujets\":100,\"puSujet\":500,\"autresDepense\":2000,\"objectif\":\"PONTE\",\"raceId\":$RACE_ID,\"alimentNom\":\"Maïs\",\"sac\":1,\"quantiteKg\":50,\"coutTotalAliment\":3000,\"vaccins\":[{\"nomVaccin\":\"Newcastle\",\"quantite\":10,\"prixUnitaire\":100,\"coutTotal\":1000}],\"occupations\":[{\"batimentId\":$BAT2_ID,\"dateEntree\":\"$AUJ\",\"nbSujets\":100}]}"
check "projet créé (sujets, charges, aliment, vaccin)" "code in (200, 201)"
P2="$(jval "d['data']['uniqueId']")"
P2_ID="$(psql_run "SELECT id FROM projets WHERE unique_id = '$P2'")"
etat_p2() { psql_run "SELECT string_agg(source_type || '=' || CASE WHEN coalesce(removed,false) THEN 'retiree' ELSE 'active' END, ',' ORDER BY source_type) FROM transactions WHERE projet_id = $P2_ID AND source_type <> 'SOINS'"; }
check_eq "transactions générées actives" "ALIMENTATION=active,PROJET_ACHAT_SUJETS=active,PROJET_CHARGES=active,VACCINATION=active" "$(etat_p2)"
api POST /soins/create "{\"projetUniqueId\":\"$P2\",\"date\":\"$AUJ\",\"type\":\"MEDICAMENT\",\"produit\":\"Supprimé à part\",\"quantite\":1,\"coutTotal\":500}"
SOIN2="$(jval "d['data']['uniqueId']")"
api PUT "/soins/deleteOrRecover/$SOIN2"
check_eq "soin supprimé à part : transaction retirée" "t" "$(tx_removed "$SOIN2")"
api DELETE "/projets/delete/$P2"
check "projet supprimé" "code == 200"
check_eq "suppression du projet : ses transactions générées retirées" "ALIMENTATION=retiree,PROJET_ACHAT_SUJETS=retiree,PROJET_CHARGES=retiree,VACCINATION=retiree" "$(etat_p2)"
api DELETE "/projets/delete/$P2"
check "projet restauré" "code == 200"
check_eq "restauration du projet : transactions générées restaurées" "ALIMENTATION=active,PROJET_ACHAT_SUJETS=active,PROJET_CHARGES=active,VACCINATION=active" "$(etat_p2)"
check_eq "le soin supprimé à part reste retiré" "t" "$(tx_removed "$SOIN2")"
api DELETE "/projets/delete/$P2"
check "projet de test remis à la corbeille" "code == 200"

# Nettoyage : les transactions posées en base n'ont pas de saisie source.
psql_run "DELETE FROM transactions WHERE categorie = 'Test verrou' AND ref LIKE 'VR-%'" >/dev/null

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
