package com.diafarms.ml.commons;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

// Devise de la ferme (ISO 4217) : simple UNITÉ des montants, jamais de conversion ni
// de taux de change. Chaque ferme choisit la sienne dans Paramètres (Farm.devise, null
// = XOF pour les fermes d'avant la fonctionnalité). Les montants enregistrés ne
// changent jamais quand la devise change.
//
// La devise courante est celle de la ferme de l'utilisateur de la requête : résolue
// une seule fois par requête (ThreadLocal, voir DeviseContexteFilter qui la vide en fin
// de requête) puis utilisée par Franc (arrondi au nombre de décimales de la devise) et
// par les textes générés (descriptions, PDF : Devise.montant). Hors requête
// authentifiée (tâches planifiées), XOF : comportement historique.
public final class Devise {

    public record Info(String code, String symbole, String nom, int decimales) {
        /** Unité pour un texte généré côté serveur (PDF en police standard Latin-1,
         * descriptions) : le symbole s'il s'écrit dans cette police, sinon le code ISO
         * (₦, GH₵ deviennent NGN, GHS). */
        public String unite() {
            for (char ch : symbole.toCharArray()) {
                if (ch > 0xFF && ch != '€') return code;
            }
            return symbole;
        }
    }

    public static final String DEFAUT = "XOF";

    // Ordre = ordre d'affichage dans Paramètres.
    private static final Map<String, Info> CATALOGUE = new LinkedHashMap<>();
    static {
        ajouter("XOF", "FCFA", "Franc CFA (BCEAO)", 0);
        ajouter("XAF", "FCFA", "Franc CFA (BEAC)", 0);
        ajouter("GNF", "GNF", "Franc guinéen", 0);
        ajouter("NGN", "₦", "Naira nigérian", 2);
        ajouter("GHS", "GH₵", "Cedi ghanéen", 2);
        ajouter("MAD", "DH", "Dirham marocain", 2);
        ajouter("MRU", "MRU", "Ouguiya mauritanien", 2);
        ajouter("CDF", "FC", "Franc congolais", 2);
        ajouter("EUR", "€", "Euro", 2);
        ajouter("USD", "$", "Dollar américain", 2);
    }

    private static void ajouter(String code, String symbole, String nom, int decimales) {
        CATALOGUE.put(code, new Info(code, symbole, nom, decimales));
    }

    private Devise() {}

    public static List<Info> catalogue() {
        return List.copyOf(CATALOGUE.values());
    }

    public static boolean existe(String code) {
        return code != null && CATALOGUE.containsKey(code.trim().toUpperCase());
    }

    /** Info d'une devise, XOF si code null ou inconnu. */
    public static Info info(String code) {
        if (code == null) return CATALOGUE.get(DEFAUT);
        Info i = CATALOGUE.get(code.trim().toUpperCase());
        return i != null ? i : CATALOGUE.get(DEFAUT);
    }

    // ---------- devise courante (ferme de l'utilisateur de la requête) ----------

    private static final ThreadLocal<Info> COURANTE = new ThreadLocal<>();

    // uniqueId utilisateur -> code devise de sa ferme (null si aucune). Branché au
    // démarrage par DeviseContexteFilter (accès base), absent dans les tests unitaires.
    private static volatile Function<String, String> resolveur;

    public static void brancherResolveur(Function<String, String> r) {
        resolveur = r;
    }

    public static Info courante() {
        Info i = COURANTE.get();
        if (i != null) return i;
        String uid = null;
        try { uid = SecurityUtils.getCurrentUserUniqueId(); } catch (Exception ignored) { }
        if (uid == null || resolveur == null) return info(null); // pas mis en cache : l'authentification peut arriver après
        String code = null;
        try { code = resolveur.apply(uid); } catch (Exception ignored) { }
        i = info(code);
        COURANTE.set(i);
        return i;
    }

    /** Force la devise du thread courant (ferme connue explicitement). */
    public static void definir(String code) {
        COURANTE.set(info(code));
    }

    public static void effacer() {
        COURANTE.remove();
    }

    public static int decimales() {
        return courante().decimales();
    }

    /** Unité de la devise courante pour un texte serveur ("FCFA", "€", "NGN"...). */
    public static String unite() {
        return courante().unite();
    }

    /** "12 500 FCFA", "12 500,50 €" : groupage par espace simple (police PDF standard),
     * décimales de la devise courante. */
    public static String montant(Double v) {
        return montant(v, courante());
    }

    public static String montant(Double v, Info d) {
        return nombre(v, d) + " " + d.unite();
    }

    /** Nombre seul, au format de la devise (sans unité). */
    public static String nombre(Double v, Info d) {
        double x = v == null ? 0 : v;
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.FRANCE);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator(',');
        int dec = d.decimales();
        DecimalFormat f = new DecimalFormat(dec > 0 ? "#,##0." + "0".repeat(dec) : "#,##0", sym);
        f.setGroupingUsed(true);
        return f.format(arrondi(x, dec));
    }

    /** Prix unitaire (sans unité) : garde jusqu'à 2 décimales (62,5 l'œuf), groupage
     * par espace simple. */
    public static String prix(Double v) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.FRANCE);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat("#,##0.##", sym);
        return f.format(v == null ? 0 : v);
    }

    /** Arrondi d'un montant à n décimales. n = 0 : Math.round (comportement historique
     * du FCFA, 0,5 au-dessus) ; sinon demi au-dessus en décimal exact. */
    public static double arrondi(double montant, int decimales) {
        if (decimales <= 0) return (double) Math.round(montant);
        return BigDecimal.valueOf(montant).setScale(decimales, RoundingMode.HALF_UP).doubleValue();
    }
}
