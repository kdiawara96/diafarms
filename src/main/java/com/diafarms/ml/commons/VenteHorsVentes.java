package com.diafarms.ml.commons;

import java.text.Normalizer;
import java.util.regex.Pattern;

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

    // Mots comparés sans accents ni majuscules (« Œufs » -> « oeufs »).
    private static final Pattern MOTS_VENTE = Pattern.compile(
            "\\b(ventes?|vendus?|vendues?|vendre|vend|vendons|vendez"
            + "|oeufs?|alveoles?|plateaux?|reformes?|reformees?"
            + "|poules?|poulets?|pondeuses?|coqs?|fientes?|fumier)\\b");

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
                .toLowerCase(java.util.Locale.ROOT);
        return MOTS_VENTE.matcher(t).find();
    }
}
