package com.diafarms.ml.commons;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Une vente (œufs, réformes, fientes ou autre) se fait dans la page Ventes : le stock,
// le compte du client et la facture suivent. Une entrée d'argent manuelle de la
// Comptabilité (web, téléphone, import Excel) qui décrit une vente dans sa catégorie
// ou sa description est donc refusée. Les sorties d'argent ne sont pas concernées
// (« achat d'alvéoles » reste une dépense normale).
public final class VenteHorsVentes {

    public static final String MESSAGE =
            "La vente d'œufs, de réformes, de fientes ou de tout autre produit se fait dans la page Ventes, "
            + "pas dans la Comptabilité. Ainsi le stock et le compte du client restent justes, "
            + "et l'argent de la vente apparaît ici tout seul.";

    // Mots comparés sans accents ni majuscules (« Œufs » -> « oeufs »), mot par mot.
    // Mots exacts : le français, les fautes de frappe courantes et l'anglais.
    static final Set<String> MOTS_EXACTS = Set.of(
            "vente", "ventes", "vendu", "vendus", "vendue", "vendues", "vendre", "vend", "vends",
            "vendons", "vendez", "vendi", "vnte", "vntes", "vete", "vetes", "vnete", "vnetes",
            "vante", "vantes", "venet", "venets", "ventte", "venntes", "veente",
            "sell", "sells", "selling", "sold", "sale", "sales", "sel", "egg", "eggs",
            "chicken", "chickens", "hen", "hens",
            "oeuf", "oeufs", "oef", "oefs", "euf", "eufs", "ouef", "ouefs", "oeus", "oeuff", "eouf", "eoufs",
            "plato", "platos", "poule", "poules", "poulle", "poulles", "poul", "coq", "coqs");
    // Mots longs : acceptés aussi avec une lettre fausse, en plus ou en moins
    // (« alveolle », « refome », « fiantes »...).
    static final List<String> MOTS_APPROCHES = List.of(
            "alveole", "alveoles", "reforme", "reformes", "reformee", "reformees", "fiente", "fientes",
            "poulet", "poulets", "pondeuse", "pondeuses", "plateau", "plateaux", "fumier");

    private VenteHorsVentes() {
    }

    /** true si une ENTRÉE d'argent manuelle décrit une vente. */
    public static boolean estVente(String type, String categorie, String description) {
        if (type == null || !"ENTREE".equalsIgnoreCase(type.trim())) return false;
        return contientMotVente(categorie) || contientMotVente(description);
    }

    static boolean contientMotVente(String texte) {
        if (texte == null || texte.isBlank()) return false;
        String t = Normalizer.normalize(texte.replace("œ", "oe").replace("Œ", "OE"), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        for (String mot : t.split("[^a-z]+")) {
            if (mot.isEmpty()) continue;
            if (MOTS_EXACTS.contains(mot)) return true;
            if (mot.length() >= 5) {
                for (String m : MOTS_APPROCHES) {
                    if (uneLettrePres(mot, m)) return true;
                }
            }
        }
        return false;
    }

    /** Identiques, ou une seule lettre changée, ajoutée ou retirée. */
    static boolean uneLettrePres(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, diff = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++diff > 1) return false;
            if (a.length() > b.length()) i++;
            else if (a.length() < b.length()) j++;
            else { i++; j++; }
        }
        return diff + (a.length() - i) + (b.length() - j) <= 1;
    }
}
