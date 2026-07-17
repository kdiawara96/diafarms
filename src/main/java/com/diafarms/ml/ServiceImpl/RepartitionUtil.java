package com.diafarms.ml.ServiceImpl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Répartit une quantité (et son montant associé) entre plusieurs projets au
 * prorata de leur part disponible — méthode du plus grand reste (largest
 * remainder), pour que la somme des parts arrondies retombe exactement sur la
 * quantité/le montant total (jamais un œuf ou un FCFA perdu/en trop par arrondi
 * cumulé). Partagé par VenteOeufsImpl et VenteReformeImpl : une vente Finance
 * n'est jamais rattachée à un seul projet, elle est financée par le stock
 * disponible de chacun des projets qui y ont contribué.
 */
final class RepartitionUtil {

    private RepartitionUtil() {}

    static final class Part {
        final Long projetId;
        final int quantite;
        final double montant;

        Part(Long projetId, int quantite, double montant) {
            this.projetId = projetId;
            this.quantite = quantite;
            this.montant = montant;
        }
    }

    static List<Part> repartir(int totalQuantite, double totalMontant, Map<Long, Integer> disponibleParProjetId) {
        long sumDisponible = disponibleParProjetId.values().stream().mapToLong(Integer::longValue).sum();
        if (totalQuantite <= 0 || sumDisponible <= 0 || totalQuantite > sumDisponible) {
            throw new IllegalArgumentException("Stock disponible insuffisant pour répartir cette vente entre les projets.");
        }

        // 1) Quantité : part entière (floor) au prorata du disponible, puis plus grand
        // reste pour combler l'écart jusqu'à totalQuantite.
        Map<Long, Integer> quantites = new LinkedHashMap<>();
        Map<Long, Double> restesQuantite = new LinkedHashMap<>();
        int sumBaseQuantite = 0;
        for (Map.Entry<Long, Integer> e : disponibleParProjetId.entrySet()) {
            double brut = totalQuantite * (e.getValue() / (double) sumDisponible);
            int floorVal = (int) Math.floor(brut);
            quantites.put(e.getKey(), floorVal);
            restesQuantite.put(e.getKey(), brut - floorVal);
            sumBaseQuantite += floorVal;
        }
        distribuerReste(totalQuantite - sumBaseQuantite, restesQuantite, quantites);

        // 2) Montant : proportionnel aux quantités FINALES (pas au disponible brut), pour
        // que la part d'un projet dans le montant corresponde exactement à sa part dans
        // la quantité vendue. Arrondi au centime, même méthode du plus grand reste.
        long totalMontantCents = Math.round(totalMontant * 100);
        Map<Long, Long> montantsCents = new LinkedHashMap<>();
        Map<Long, Double> restesMontant = new LinkedHashMap<>();
        long sumBaseMontant = 0;
        for (Long projetId : quantites.keySet()) {
            double brut = totalMontantCents * (quantites.get(projetId) / (double) totalQuantite);
            long floorVal = (long) Math.floor(brut);
            montantsCents.put(projetId, floorVal);
            restesMontant.put(projetId, brut - floorVal);
            sumBaseMontant += floorVal;
        }
        distribuerResteLong(totalMontantCents - sumBaseMontant, restesMontant, montantsCents);

        return quantites.keySet().stream()
                .filter(id -> quantites.get(id) > 0)
                .map(id -> new Part(id, quantites.get(id), montantsCents.get(id) / 100.0))
                .collect(Collectors.toList());
    }

    private static void distribuerReste(int aDistribuer, Map<Long, Double> restes, Map<Long, Integer> cible) {
        for (Long id : parResteDecroissant(restes)) {
            if (aDistribuer <= 0) break;
            cible.put(id, cible.get(id) + 1);
            aDistribuer--;
        }
    }

    private static void distribuerResteLong(long aDistribuer, Map<Long, Double> restes, Map<Long, Long> cible) {
        for (Long id : parResteDecroissant(restes)) {
            if (aDistribuer <= 0) break;
            cible.put(id, cible.get(id) + 1);
            aDistribuer--;
        }
    }

    private static List<Long> parResteDecroissant(Map<Long, Double> restes) {
        return restes.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
