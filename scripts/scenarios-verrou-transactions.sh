#!/usr/bin/env bash
# Verrou comptable des transactions générées par une saisie : script de bout en bout.
#
# Une transaction dont la source est un soin, une vaccination, un achat d'aliment, un
# investissement, un paiement de salaire ou les coûts de démarrage d'un projet ne se
# modifie, ne se supprime, ne se rejette et ne fait l'objet d'une demande de suppression
# QUE par sa saisie source (message qui nomme l'écran). Supprimer la saisie source retire
# toujours sa transaction. Une transaction MANUEL reste modifiable depuis la Comptabilité.
# Section 7 : l'achat d'aliment (catégorie de la sortie d'argent) crée stock + dépense en
# une saisie ; une sortie manuelle catégorie Aliment / Achat d'aliment est refusée.
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
verrou "aliment" "$T_ALIM" "crayon de la dépense"
api GET "/transactions/list?page=0&size=200"
check "DTO : saisieSource renseignée pour la transaction générée" "code == 200 and any(x.get('uniqueId') == '$T_ALIM' and 'achat d\x27aliment' in (x.get('saisieSource') or '') for x in d['data']['data'])"
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

echo "== 7. Achat d'aliment = catégorie de la sortie d'argent (une saisie : dépense + stock)"
stock_kg() { api GET "/consommations-aliment/stock/$PROJET"; jval "(d.get('data') or d)['totalAchete']"; }
AVANT="$(stock_kg)"
api POST "/alimentations/create/$PROJET" "{\"typeAliment\":\"PONTE\",\"sac\":10,\"poidsSacKg\":50,\"coutTotal\":175000,\"dateDistribution\":\"$AUJ\",\"fournisseur\":\"Sedima $SUFFIXE\"}"
check "achat typé (10 sacs de 50 kg, sans kg ni nom) créé" "code in (200, 201) and d['data']['typeAliment'] == 'PONTE' and d['data']['quantiteKg'] == 500 and d['data']['nomAliment'] == 'Aliment ponte'"
ALIM2="$(jval "d['data']['uniqueId']")"
APRES="$(stock_kg)"
check_eq "stock acheté du projet : +500 kg exactement (pas d'étape de réception)" "500.0" "$(python3 -c "print(round(float('$APRES') - float('$AVANT'), 1))")"
check_eq "une seule sortie générée : 175000, catégorie Aliment, source ALIMENTATION" "1|175000|Aliment|ALIMENTATION|SORTIE" \
  "$(psql_run "SELECT count(*) || '|' || max(montant)::bigint || '|' || max(categorie) || '|' || max(source_type) || '|' || max(type) FROM transactions WHERE source_unique_id = '$ALIM2' AND coalesce(removed,false) = false")"
api GET "/alimentations/list-by-projet/$PROJET"
check "liste des achats : typeAliment renvoyé" "code == 200 and any(a['uniqueId'] == '$ALIM2' and a['typeAliment'] == 'PONTE' for a in d['data'])"
api PUT "/alimentations/update/$ALIM2" '{"typeAliment":"CROISSANCE"}'
check "modification du type d'aliment" "code == 200 and d['data']['typeAliment'] == 'CROISSANCE'"
api PUT "/alimentations/update/$ALIM2" '{"observations":"sans type envoyé"}'
check "typeAliment absent (null) : type inchangé" "code == 200 and d['data']['typeAliment'] == 'CROISSANCE'"
api PUT "/alimentations/update/$ALIM2" '{"typeAliment":""}'
check "typeAliment vide (Non précisé) : type retiré" "code == 200 and d['data']['typeAliment'] is None"
api POST "/alimentations/create/$PROJET" "{\"typeAliment\":\"FINITION\",\"sac\":1,\"coutTotal\":1000,\"dateDistribution\":\"$AUJ\"}"
check "type d'aliment inconnu : 400" "code == 400 and \"Type d'aliment inconnu\" in err"
api POST "/alimentations/create/$PROJET" "{\"sac\":2,\"coutTotal\":30000,\"dateDistribution\":\"$AUJ\",\"nomAliment\":\"Maïs concassé\"}"
check "sans type ni poids : 2 sacs x 50 kg par défaut, nom saisi gardé" "code in (200, 201) and d['data']['typeAliment'] is None and d['data']['quantiteKg'] == 100 and d['data']['nomAliment'] == 'Maïs concassé'"
ALIM3="$(jval "d['data']['uniqueId']")"
api POST "/alimentations/create/$PROJET" "{\"quantiteKg\":75,\"coutTotal\":9000,\"dateDistribution\":\"$AUJ\",\"nomAliment\":\"Sans sacs $SUFFIXE\"}"
check "APK 1.29/1.30 : sans sacs mais avec kg, accepté (sac 0, 75 kg)" "code in (200, 201) and d['data']['quantiteKg'] == 75 and d['data']['sac'] == 0"
ALIM4="$(jval "d['data']['uniqueId']")"
api PUT "/alimentations/update/$ALIM4" '{"quantiteKg":80}'
check "modification sans sacs : acceptée" "code == 200 and d['data']['quantiteKg'] == 80"
api POST "/alimentations/create/$PROJET" "{\"coutTotal\":9000,\"dateDistribution\":\"$AUJ\",\"nomAliment\":\"Rien $SUFFIXE\"}"
check "ni sacs ni kg : 400" "code == 400 and 'quantité achetée' in err"
api POST "/alimentations/create/$PROJET" "{\"quantiteKg\":0,\"coutTotal\":9000,\"dateDistribution\":\"$AUJ\",\"nomAliment\":\"Zéro $SUFFIXE\"}"
check "sans sacs et kg = 0 : 400" "code == 400"
NB_TX="$(psql_run "SELECT count(*) FROM transactions WHERE farm_id = $FARM_ID")"
for cat in "Aliment" "Achat d'aliment" "achat d’aliment" "ALIMENTS"; do
  api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Contournement $SUFFIXE\",\"montant\":5000,\"categorie\":\"$cat\"}"
  check "sortie manuelle catégorie « $cat » : refusée, renvoi vers la catégorie Achat d'aliment" "code == 400 and \"Utilisez la catégorie Achat d'aliment du formulaire de sortie d'argent : elle enregistre aussi le stock\" in err"
done
check_eq "aucune transaction créée par ces refus" "$NB_TX" "$(psql_run "SELECT count(*) FROM transactions WHERE farm_id = $FARM_ID")"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Sacs vides $SUFFIXE\",\"montant\":900,\"categorie\":\"Matériels\"}"
T_MAT="$(jval "d['data']['uniqueId']")"
api PUT "/transactions/update/$T_MAT" '{"categorie":"Aliment"}'
check "sortie manuelle : passage à la catégorie Aliment refusé" "code == 400 and 'Achat d' in err"
api POST /transactions/create "{\"type\":\"ENTREE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Entrée aliment $SUFFIXE\",\"montant\":300,\"categorie\":\"Aliment\"}"
T_ENT="$(jval "d['data']['uniqueId']")"
api PUT "/transactions/update/$T_ENT" '{"type":"SORTIE"}'
check "entrée « Aliment » passée en sortie (type seul) : refusée" "code == 400 and 'Achat d' in err"
check_eq "entrée « Aliment » inchangée après le refus" "ENTREE" "$(psql_run "SELECT type FROM transactions WHERE unique_id = '$T_ENT'")"
api PUT "/transactions/deleteOrRecover/$T_ENT" '{"motif":"Test"}'
t="$(uuid)"
psql_run "INSERT INTO transactions (unique_id, ref, type, date, montant, categorie, statut, source_type, farm_id, removed, archive, created_at)
  VALUES ('$t', 'VR-$(uuid | cut -c1-10)', 'SORTIE', current_date, 4000, 'Aliment', 'VALIDE', 'MANUEL', $FARM_ID, false, false, now())" >/dev/null
api PUT "/transactions/update/$t" '{"montant":4500,"categorie":"Aliment","description":"Ancienne sortie aliment"}'
check "ancienne sortie manuelle « Aliment » : reste modifiable (catégorie gardée)" "code == 200 and d['data']['montant'] == 4500"
psql_run "DELETE FROM transactions WHERE unique_id = '$t'" >/dev/null
api PUT "/transactions/deleteOrRecover/$T_MAT" '{"motif":"Test"}'
for a in "$ALIM2" "$ALIM3" "$ALIM4"; do api DELETE "/alimentations/delete/$a"; done
check_eq "achats de test supprimés : stock revenu à l'état initial" "$AVANT" "$(stock_kg)"

echo "== 7b. Achat d'aliment : modifier prix/quantité/projet tant que la consommation reste couverte"
stock_json() { api GET "/consommations-aliment/stock/$PROJET"; }
api POST "/alimentations/create/$PROJET" "{\"typeAliment\":\"PONTE\",\"sac\":2,\"quantiteKg\":100,\"coutTotal\":35000,\"dateDistribution\":\"$AUJ\",\"batimentUniqueId\":\"$BATIMENT\"}"
check "achat de 100 kg créé (poulailler envoyé : ignoré)" "code in (200, 201) and d['data']['batimentUniqueId'] is None"
ACH="$(jval "d['data']['uniqueId']")"
check_eq "dépense de l'achat : pas de poulailler, site du projet" "|$(psql_run "SELECT coalesce(s.unique_id,'') FROM projets p LEFT JOIN sites s ON s.id = p.site_id WHERE p.unique_id = '$PROJET'")" \
  "$(psql_run "SELECT coalesce(b.unique_id,'') || '|' || coalesce(s.unique_id,'') FROM transactions t LEFT JOIN batiments b ON b.id = t.batiment_id LEFT JOIN sites s ON s.id = t.site_id WHERE t.source_unique_id = '$ACH'")"
api GET "/transactions/list?page=0&size=200"
check "dépense d'un achat : rattachement non modifiable depuis la Comptabilité" "code == 200 and any(x['sourceUniqueId'] == '$ACH' and x['rattachementModifiable'] is False for x in d['data']['data'])"
T_ACH="$(tx_de_source "$ACH")"
api PUT "/transactions/update/$T_ACH" "{\"batimentUniqueId\":\"$BATIMENT\"}"
check "rattacher la dépense d'un achat à un poulailler : refusé" "code == 400 and \"modifiez l'achat\" in err"
# Le projet consomme 60 kg de cet achat (consommation posée en base).
read -r ACHETE CONSO < <(psql_run "SELECT (SELECT coalesce(sum(quantite_kg),0) FROM alimentations WHERE projet_id = $PROJET_ID AND NOT coalesce(removed,false)) || ' ' || (SELECT coalesce(sum(quantite_kg),0) FROM consommations_aliment WHERE projet_id = $PROJET_ID AND NOT coalesce(removed,false))")
EN_PLUS="$(python3 -c "print(round($ACHETE - 100 - $CONSO + 60, 3))")"
psql_run "INSERT INTO consommations_aliment (unique_id, date, quantite_kg, projet_id, farm_id, removed, archive, created_at)
  VALUES ('conso-7b-$SUFFIXE', current_date, $EN_PLUS, $PROJET_ID, $FARM_ID, false, false, now())" >/dev/null
api PUT "/alimentations/update/$ACH" '{"coutTotal":36000}'
check "prix modifié (achat entamé)" "code == 200 and d['data']['coutTotal'] == 36000"
check_eq "la dépense suit le nouveau prix" "36000" "$(psql_run "SELECT montant::bigint FROM transactions WHERE source_unique_id = '$ACH'")"
api PUT "/alimentations/update/$ACH" '{"quantiteKg":50}'
check "quantité réduite sous le déjà consommé (60 kg) : refusée, minimum indiqué" "code == 400 and 'minimum pour cet achat est 60 kg' in err"
api PUT "/alimentations/update/$ACH" '{"quantiteKg":70,"sac":1.4}'
check "quantité réduite à 70 kg (>= 60 consommés) : acceptée" "code == 200 and d['data']['quantiteKg'] == 70"
AUTRE_P="$(psql_run "SELECT unique_id FROM projets WHERE farm_id = $FARM_ID AND unique_id <> '$PROJET' ORDER BY id LIMIT 1")"
psql_run "UPDATE projets SET removed = false WHERE unique_id = '$AUTRE_P'" >/dev/null
api PUT "/alimentations/update/$ACH" "{\"projetUniqueId\":\"$AUTRE_P\"}"
check "changer de projet un achat entamé : refusé" "code == 400 and 'changer le projet' in err"
api DELETE "/alimentations/delete/$ACH"
check "supprimer un achat entamé : refusé" "code == 400 and 'Impossible de supprimer cet achat' in err"
psql_run "UPDATE consommations_aliment SET removed = true WHERE unique_id = 'conso-7b-$SUFFIXE'" >/dev/null
api PUT "/alimentations/update/$ACH" "{\"projetUniqueId\":\"$AUTRE_P\"}"
check "achat non entamé : changement de projet accepté" "code == 200 and d['data']['projetUniqueId'] == '$AUTRE_P'"
check_eq "la dépense suit le nouveau projet" "$AUTRE_P" "$(psql_run "SELECT p.unique_id FROM transactions t JOIN projets p ON p.id = t.projet_id WHERE t.source_unique_id = '$ACH'")"
api DELETE "/alimentations/delete/$ACH"
check "achat non entamé : suppression acceptée" "code == 200"
psql_run "UPDATE projets SET removed = true WHERE unique_id = '$AUTRE_P'" >/dev/null
psql_run "DELETE FROM consommations_aliment WHERE unique_id = 'conso-7b-$SUFFIXE'" >/dev/null

echo "== 8. Santé / Vétérinaire : toujours liée à un projet (poulailler facultatif)"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Vaccin commun $SUFFIXE\",\"montant\":2000,\"categorie\":\"Santé / Vétérinaire\"}"
check "soin sans projet (commune) : refusé" "code == 400 and 'doit être liée à un projet' in err"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Vaccin un projet $SUFFIXE\",\"montant\":2000,\"categorie\":\"Santé / Vétérinaire\",\"quantite\":4,\"projetsConcernesUniqueIds\":[\"$PROJET\"]}"
check "soin « commune » avec un seul projet : enregistré pour ce projet, prix unitaire déduit (2000 / 4)" "code in (200, 201) and d['data'].get('projetUniqueId') == '$PROJET' and d['data'].get('quantite') == 4 and d['data'].get('prixUnitaire') == 500"
T_S1="$(jval "d['data']['uniqueId']")"
SITE="$(psql_run "SELECT unique_id FROM sites WHERE farm_id = $FARM_ID AND coalesce(removed,false) = false LIMIT 1")"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":false,\"projetUniqueId\":\"$PROJET\",\"batimentUniqueId\":\"$BATIMENT\",\"siteUniqueId\":\"$SITE\",\"date\":\"$AUJ\",\"description\":\"Vaccin poulailler $SUFFIXE\",\"montant\":1500,\"categorie\":\"Santé / Vétérinaire\",\"quantite\":3,\"prixUnitaire\":500}"
check "soin projet + poulailler : accepté, sans site" "code in (200, 201) and d['data'].get('projetUniqueId') == '$PROJET' and d['data'].get('batimentUniqueId') == '$BATIMENT' and d['data'].get('siteUniqueId') is None"
T_S2="$(jval "d['data']['uniqueId']")"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":false,\"projetUniqueId\":\"$PROJET\",\"date\":\"$AUJ\",\"description\":\"Vaccin sans quantité $SUFFIXE\",\"montant\":1500,\"categorie\":\"Santé / Vétérinaire\"}"
check "soin sans quantité : refusé" "code == 400 and 'quantité' in err"
api PUT "/transactions/update/$T_S2" '{"commun":true}'
check "soin passé en commun : refusé" "code == 400 and 'doit être liée à un projet' in err"
api POST /transactions/create "{\"type\":\"SORTIE\",\"commun\":true,\"date\":\"$AUJ\",\"description\":\"Électricité $SUFFIXE\",\"montant\":800,\"categorie\":\"Électricité / Eau\"}"
T_S3="$(jval "d['data']['uniqueId']")"
api PUT "/transactions/update/$T_S3" '{"categorie":"Santé / Vétérinaire"}'
check "dépense commune passée en Santé sans projet : refusée" "code == 400 and 'doit être liée à un projet' in err"
for t in "$T_S1" "$T_S2" "$T_S3"; do psql_run "DELETE FROM transactions WHERE unique_id = '$t'" >/dev/null; done

# Nettoyage : les transactions posées en base n'ont pas de saisie source.
psql_run "DELETE FROM transactions WHERE categorie = 'Test verrou' AND ref LIKE 'VR-%'" >/dev/null

echo
echo "Résultat : $PASS OK, $FAIL ECHEC"
[ "$FAIL" -eq 0 ]
