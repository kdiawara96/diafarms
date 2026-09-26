package com.diafarms.ml.commons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// Répartition pure (sans base de données) de l'argent disponible des paiements sur ce
// qui reste à régler. CompteClientService charge les données, appelle ceci, enregistre
// le résultat. Toute la règle métier d'ordre de priorité vit ici, testée à part.
public final class CalculImputation {

    private CalculImputation() {}

    /** reservee : paiement rattaché à une commande encore ouverte (voir
     * CompteClientService.estReservee). Son argent ne règle QUE les ventes de cette
     * commande (ses livraisons) ; il n'est jamais pris par une autre vente ni par un
     * remboursement qui ne vise pas cette commande. */
    public record Source(String paiementUniqueId, double reste, String commandeUniqueId, String venteCibleUniqueId,
                         boolean reservee) {
        public Source(String paiementUniqueId, double reste, String commandeUniqueId, String venteCibleUniqueId) {
            this(paiementUniqueId, reste, commandeUniqueId, venteCibleUniqueId, false);
        }
    }
    public record Besoin(String cibleType, String cibleUniqueId, double reste, String commandeUniqueId) {}
    public record Affectation(String paiementUniqueId, String cibleType, String cibleUniqueId, double montant) {}

    public static double arrondi(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Sources et besoins triés du plus ancien au plus récent. D'abord l'argent réservé
     * (acomptes des commandes ouvertes, plus anciens d'abord), qui ne règle que les
     * ventes de sa commande ; puis l'argent libre, qui peut tout régler. Pour chaque
     * paiement : d'abord la vente qu'il vise, puis les ventes de sa commande, puis les
     * autres (argent libre seulement). */
    public static List<Affectation> repartir(List<Source> sources, List<Besoin> besoins) {
        double[] restesBesoins = besoins.stream().mapToDouble(b -> arrondi(b.reste())).toArray();
        List<Affectation> out = new ArrayList<>();
        List<Source> ordreSources = new ArrayList<>(sources.stream().filter(Source::reservee).toList());
        ordreSources.addAll(sources.stream().filter(s -> !s.reservee()).toList());
        for (Source s : ordreSources) {
            double dispo = arrondi(s.reste());
            if (dispo <= 0) continue;
            for (int idx : ordrePour(s, besoins)) {
                if (dispo <= 0) break;
                double besoin = restesBesoins[idx];
                if (besoin <= 0) continue;
                double m = arrondi(Math.min(dispo, besoin));
                Besoin b = besoins.get(idx);
                out.add(new Affectation(s.paiementUniqueId(), b.cibleType(), b.cibleUniqueId(), m));
                dispo = arrondi(dispo - m);
                restesBesoins[idx] = arrondi(besoin - m);
            }
        }
        return out;
    }

    private static List<Integer> ordrePour(Source s, List<Besoin> besoins) {
        List<Integer> cible = new ArrayList<>(), commande = new ArrayList<>(), autres = new ArrayList<>();
        for (int i = 0; i < besoins.size(); i++) {
            Besoin b = besoins.get(i);
            boolean deSaCommande = s.commandeUniqueId() != null && s.commandeUniqueId().equals(b.commandeUniqueId());
            if (s.reservee() && !deSaCommande) continue; // réservé : jamais hors de sa commande
            if (s.venteCibleUniqueId() != null && s.venteCibleUniqueId().equals(b.cibleUniqueId())) cible.add(i);
            else if (deSaCommande) commande.add(i);
            else autres.add(i);
        }
        List<Integer> ordre = new ArrayList<>(cible);
        ordre.addAll(commande);
        ordre.addAll(autres);
        return ordre;
    }

    /** Remboursement : prend l'argent non imputé, d'abord sur les paiements de la
     * commande concernée, puis sur les paiements les plus récents. L'argent réservé à une
     * commande ouverte n'est pris que si le remboursement vise cette commande. */
    public static List<Affectation> prelever(List<Source> sources, String cibleType, String cibleUniqueId,
                                             double montant, String commandeUniqueId) {
        double voulu = arrondi(montant);
        List<Source> utilisables = sources.stream()
                .filter(s -> !s.reservee() || (commandeUniqueId != null && commandeUniqueId.equals(s.commandeUniqueId())))
                .toList();
        double dispoTotal = arrondi(utilisables.stream().mapToDouble(s -> Math.max(0, s.reste())).sum());
        double reserveAilleurs = arrondi(sources.stream().mapToDouble(s -> Math.max(0, s.reste())).sum() - dispoTotal);
        if (voulu <= 0) throw new IllegalArgumentException("Le montant à rembourser doit être positif.");
        if (voulu > dispoTotal) {
            throw new IllegalArgumentException("Le remboursement (" + voulu + " FCFA) dépasse l'avance disponible du client ("
                    + dispoTotal + " FCFA)." + (reserveAilleurs > 0 ? " " + reserveAilleurs
                    + " FCFA sont réservés à des commandes en cours : pour les rendre, remboursez depuis la commande concernée."
                    : ""));
        }
        List<Source> ordre = new ArrayList<>(utilisables);
        java.util.Collections.reverse(ordre); // plus récents d'abord
        ordre.sort(Comparator.comparing((Source s) -> !Objects.equals(commandeUniqueId, s.commandeUniqueId())
                || commandeUniqueId == null)); // tri stable : ceux de la commande devant
        List<Affectation> out = new ArrayList<>();
        for (Source s : ordre) {
            if (voulu <= 0) break;
            double dispo = arrondi(s.reste());
            if (dispo <= 0) continue;
            double m = arrondi(Math.min(dispo, voulu));
            out.add(new Affectation(s.paiementUniqueId(), cibleType, cibleUniqueId, m));
            voulu = arrondi(voulu - m);
        }
        return out;
    }
}
