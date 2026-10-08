package com.diafarms.ml.commons;

// Numéro de téléphone au format international pour un lien WhatsApp (https://wa.me/<numéro>,
// chiffres seuls, sans « + » ni « 00 »). Les fermes saisissent leur numéro librement :
//   "+223 70 12 34 56" -> "22370123456"
//   "0022370123456"    -> "22370123456"
//   "70 12 34 56"      -> "22370123456" (8 chiffres = numéro malien, préfixe 223)
//   "+33 6 12 34 56 78" -> "33612345678"
// null si le numéro est vide ou trop court pour être appelé (moins de 8 chiffres).
public final class Telephone {

    public static final String INDICATIF_MALI = "223";

    private Telephone() {}

    public static String international(String brut) {
        if (brut == null) return null;
        String chiffres = brut.replaceAll("[^0-9]", "");
        if (chiffres.startsWith("00")) chiffres = chiffres.substring(2);
        if (chiffres.length() == 8) return INDICATIF_MALI + chiffres;
        if (chiffres.length() < 8) return null;
        return chiffres;
    }
}
