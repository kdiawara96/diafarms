package com.diafarms.ml.commons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.diafarms.ml.enums.ModePaiement;

// Pays proposés dans Paramètres > Pays et devise : devise proposée et modes de
// paiement courants du pays. Ce ne sont que des PROPOSITIONS : le propriétaire garde
// la main sur la devise et coche/décoche/ajoute ses modes (voir ModesPaiementService).
public final class CataloguePays {

    public record Pays(String code, String nom, String devise, List<String> modes) {}

    public record ModeStandard(String code, String libelle) {
        /** Valeur de l'enum ModePaiement historique (stockée telle quelle), sinon
         * enregistrée en AUTRE + libellé. */
        public boolean historique() { return CataloguePays.estHistorique(code); }
    }

    public static final String PAYS_DEFAUT = "ML";
    public static final String PAYS_AUTRE = "AUTRE";

    // Libellés des modes standard. Les 7 premiers sont les valeurs de l'enum
    // ModePaiement (connues des téléphones d'avant la fonctionnalité).
    private static final Map<String, ModeStandard> MODES = new LinkedHashMap<>();
    static {
        mode("ESPECES", "Espèces");
        mode("ORANGE_MONEY", "Orange Money");
        mode("MOOV_MONEY", "Moov Money");
        mode("WAVE", "Wave");
        mode("VIREMENT", "Virement");
        mode("CHEQUE", "Chèque");
        mode("AUTRE", "Autre");
        mode("FREE_MONEY", "Free Money");
        mode("MTN_MONEY", "MTN Mobile Money");
        mode("AIRTEL_MONEY", "Airtel Money");
        mode("TMONEY", "T-Money");
        mode("FLOOZ", "Flooz");
        mode("MOBILE_MONEY", "Mobile money");
        mode("CARTE", "Carte bancaire");
    }

    private static void mode(String code, String libelle) {
        MODES.put(code, new ModeStandard(code, libelle));
    }

    private static final Map<String, Pays> PAYS = new LinkedHashMap<>();
    static {
        pays("ML", "Mali", "XOF", "ESPECES", "ORANGE_MONEY", "MOOV_MONEY", "WAVE", "VIREMENT", "CHEQUE", "AUTRE");
        pays("SN", "Sénégal", "XOF", "ESPECES", "WAVE", "ORANGE_MONEY", "FREE_MONEY", "VIREMENT", "CHEQUE", "AUTRE");
        pays("CI", "Côte d'Ivoire", "XOF", "ESPECES", "ORANGE_MONEY", "MTN_MONEY", "MOOV_MONEY", "WAVE", "VIREMENT", "CHEQUE", "AUTRE");
        pays("BF", "Burkina Faso", "XOF", "ESPECES", "ORANGE_MONEY", "MOOV_MONEY", "WAVE", "VIREMENT", "CHEQUE", "AUTRE");
        pays("NE", "Niger", "XOF", "ESPECES", "AIRTEL_MONEY", "MOOV_MONEY", "VIREMENT", "CHEQUE", "AUTRE");
        pays("BJ", "Bénin", "XOF", "ESPECES", "MTN_MONEY", "MOOV_MONEY", "VIREMENT", "CHEQUE", "AUTRE");
        pays("TG", "Togo", "XOF", "ESPECES", "TMONEY", "FLOOZ", "VIREMENT", "CHEQUE", "AUTRE");
        pays("GN", "Guinée", "GNF", "ESPECES", "ORANGE_MONEY", "MTN_MONEY", "VIREMENT", "AUTRE");
        pays("CM", "Cameroun", "XAF", "ESPECES", "MTN_MONEY", "ORANGE_MONEY", "VIREMENT", "CHEQUE", "AUTRE");
        pays("GA", "Gabon", "XAF", "ESPECES", "AIRTEL_MONEY", "MOOV_MONEY", "VIREMENT", "CHEQUE", "AUTRE");
        pays("CG", "Congo", "XAF", "ESPECES", "MTN_MONEY", "AIRTEL_MONEY", "VIREMENT", "AUTRE");
        pays("TD", "Tchad", "XAF", "ESPECES", "AIRTEL_MONEY", "MOOV_MONEY", "VIREMENT", "AUTRE");
        pays("NG", "Nigeria", "NGN", "ESPECES", "VIREMENT", "MOBILE_MONEY", "CARTE", "AUTRE");
        pays("GH", "Ghana", "GHS", "ESPECES", "MTN_MONEY", "MOBILE_MONEY", "VIREMENT", "CARTE", "AUTRE");
        pays(PAYS_AUTRE, "Autre pays", null, "ESPECES", "VIREMENT", "MOBILE_MONEY", "CARTE", "CHEQUE", "AUTRE");
    }

    private static void pays(String code, String nom, String devise, String... modes) {
        PAYS.put(code, new Pays(code, nom, devise, List.of(modes)));
    }

    private static final Set<String> HISTORIQUES = Set.of(
            java.util.Arrays.stream(ModePaiement.values()).map(Enum::name).toArray(String[]::new));

    private CataloguePays() {}

    public static List<Pays> pays() { return List.copyOf(PAYS.values()); }

    public static List<ModeStandard> modesStandard() { return List.copyOf(MODES.values()); }

    public static boolean paysExiste(String code) {
        return code != null && PAYS.containsKey(code.trim().toUpperCase());
    }

    /** Pays (ML si null/inconnu). */
    public static Pays paysOuDefaut(String code) {
        Pays p = code == null ? null : PAYS.get(code.trim().toUpperCase());
        return p != null ? p : PAYS.get(PAYS_DEFAUT);
    }

    public static ModeStandard modeStandard(String code) {
        return code == null ? null : MODES.get(code.trim().toUpperCase());
    }

    public static boolean estHistorique(String code) {
        return code != null && HISTORIQUES.contains(code.trim().toUpperCase());
    }

    /** Libellé affiché d'une valeur de l'enum (ESPECES -> Espèces). */
    public static String libelle(ModePaiement m) {
        if (m == null) return null;
        ModeStandard s = MODES.get(m.name());
        return s != null ? s.libelle() : m.name();
    }
}
