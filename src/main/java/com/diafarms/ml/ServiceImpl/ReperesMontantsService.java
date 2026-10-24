package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.models.Utilisateurs;

import lombok.RequiredArgsConstructor;

// Repères de la ferme pour repérer une faute de frappe à la saisie (le web demande
// confirmation, ne bloque jamais) :
//  - prix : prix unitaire habituel = médiane des 10 dernières saisies (aliment au kg,
//    sujet acheté, œuf vendu par type, réforme à la tête ou au kilo) ;
//  - maxParCategorie : plus gros montant validé par catégorie sur les 90 derniers jours.
//    Sans salaires ni argent des clients (paiements, remboursements) : rien à contrôler
//    et pas à montrer à tous les rôles.
@Service
@RequiredArgsConstructor
public class ReperesMontantsService {

    private static final int DERNIERES = 10;
    private static final int JOURS_MAX = 90;

    private final JdbcTemplate jdbc;
    private final OtherService otherService;

    @Transactional(readOnly = true)
    public Map<String, Object> reperes() {
        Utilisateurs u = otherService.getCurrentUser();
        if (u == null || u.getFarm() == null) throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        Long farmId = u.getFarm().getId();

        Map<String, Double> prix = new LinkedHashMap<>();
        mediane(prix, "ALIMENT_KG", "SELECT cout_total / quantite_kg FROM alimentations WHERE farm_id = ? AND removed = false "
                + "AND quantite_kg > 0 AND cout_total > 0 ORDER BY id DESC LIMIT " + DERNIERES, farmId);
        mediane(prix, "SUJET", "SELECT pu_sujet FROM projets WHERE farm_id = ? AND removed = false AND pu_sujet > 0 "
                + "ORDER BY id DESC LIMIT " + DERNIERES, farmId);
        for (String type : jdbc.queryForList("SELECT DISTINCT type_oeuf FROM ventes_oeufs WHERE farm_id = ? AND removed = false "
                + "AND type_oeuf IS NOT NULL", String.class, farmId)) {
            mediane(prix, "OEUF_" + type, "SELECT prix_unitaire FROM ventes_oeufs WHERE farm_id = ? AND removed = false "
                    + "AND type_oeuf = ? AND prix_unitaire > 0 ORDER BY id DESC LIMIT " + DERNIERES, farmId, type);
        }
        for (String type : List.of("TETE", "KILO")) {
            mediane(prix, "REFORME_" + type, "SELECT prix_unitaire FROM ventes_reforme WHERE farm_id = ? AND removed = false "
                    + "AND type_vente = ? AND prix_unitaire > 0 ORDER BY id DESC LIMIT " + DERNIERES, farmId, type);
        }

        Map<String, Double> maxParCategorie = new LinkedHashMap<>();
        jdbc.query("SELECT categorie, MAX(montant) FROM transactions WHERE farm_id = ? AND removed = false AND statut = 'VALIDE' "
                + "AND date >= ? AND categorie IS NOT NULL AND categorie <> '' "
                + "AND COALESCE(source_type, '') NOT IN ('SALAIRE', 'PAIEMENT_CLIENT', 'REMBOURSEMENT_CLI') GROUP BY categorie",
                rs -> { maxParCategorie.put(rs.getString(1), rs.getDouble(2)); },
                farmId, LocalDate.now().minusDays(JOURS_MAX));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prix", prix);
        out.put("maxParCategorie", maxParCategorie);
        return out;
    }

    private void mediane(Map<String, Double> out, String cle, String sql, Object... args) {
        List<Double> v = new ArrayList<>(jdbc.queryForList(sql, Double.class, args));
        v.removeIf(x -> x == null || x <= 0);
        if (v.isEmpty()) return;
        Collections.sort(v);
        int n = v.size();
        out.put(cle, n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2);
    }
}
