package com.diafarms.ml.request.others;

import lombok.Data;

// Corps d'une demande de suppression (vente ou transaction) : le motif est obligatoire,
// il reste attaché à l'enregistrement (motifSuppression) et au journal, pour que celui
// qui confirme sache pourquoi, et qu'on le retrouve plus tard.
@Data
public class MotifSuppressionRequest {
    private String motif;

    /** Motif nettoyé, ou IllegalArgumentException s'il est absent ou trop court. */
    public static String exiger(MotifSuppressionRequest req) {
        return exiger(req != null ? req.getMotif() : null);
    }

    public static String exiger(String brut) {
        String motif = brut != null ? brut.trim() : "";
        if (motif.length() < 3) {
            throw new IllegalArgumentException("Le motif de la suppression est obligatoire.");
        }
        return motif;
    }
}
