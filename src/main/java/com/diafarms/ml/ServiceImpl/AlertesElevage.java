package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.diafarms.ml.DTO.NotificationDTO;
import com.diafarms.ml.enums.Objectif;
import com.diafarms.ml.models.Projets;

import lombok.RequiredArgsConstructor;

// Alertes d'élevage calculées pour TOUS les projets d'un appel /notifications en 5 requêtes
// groupées (et non plusieurs requêtes par projet) : l'endpoint est appelé par chaque
// téléphone toutes les 15 minutes. Les chiffres servent aussi aux alertes historiques
// (stock épuisé, mortalité cumulée) de NotificationServiceImpl.
//
// Nouvelles alertes (clé stable PAR JOUR : lue aujourd'hui, elle revient demain si le
// problème est toujours là ; le téléphone la montre une fois par jour au plus) :
//
// 1. Mortalité anormale (type MORTALITE, clé mortalite-anormale-<projet>-<date>) :
//    morts du jour >= 3 ET (morts du jour > 3 x la moyenne par jour des 7 jours d'avant
//    OU morts du jour > 0,5 % des sujets vivants au début du jour).
//    CRITIQUE à partir de 1 % des vivants, sinon WARNING. Le minimum de 3 morts évite
//    une alerte pour 1 ou 2 morts dans une petite bande.
//
// 2. Stock d'aliment bas (type STOCK, clé stock-aliment-bas-<projet>-<date>) : stock
//    restant du projet (achats - consommations, même calcul que la page Aliments) divisé
//    par la consommation moyenne des 7 derniers jours COMPLETS (hier et les 6 jours
//    d'avant : la consommation du jour n'est souvent saisie que le soir) < 5 jours.
//    Remplace l'ancienne alerte « stock faible » (moins de 3 jours). Stock épuisé : alerte
//    CRITIQUE inchangée (clé stock-<projet>).
//
// 3. Ponte en baisse (Projets PONTE et MIXTE, type PONTE, clé ponte-baisse-<projet>-<date>) :
//    taux de ponte des 3 derniers jours (aujourd'hui compris) plus de 8 points sous celui
//    des 7 jours d'avant. Taux = œufs collectés / sujets vivants, sur les jours où une
//    collecte est saisie (même règle que le Reporting). Il faut au moins 2 jours de
//    collecte dans les 3 derniers jours et 3 dans les 7 d'avant : pas d'alerte sur une
//    journée oubliée.
@Component
@RequiredArgsConstructor
public class AlertesElevage {

    public static final int MORTS_MIN = 3;
    public static final double MORTS_FOIS_MOYENNE = 3.0;
    public static final double MORTS_PCT_VIVANTS = 0.5;
    public static final double MORTS_PCT_CRITIQUE = 1.0;
    public static final double STOCK_JOURS_MIN = 5.0;
    public static final double PONTE_BAISSE_POINTS = 8.0;
    private static final int JOURS_HISTO = 11; // aujourd'hui + 10 jours avant

    private final JdbcTemplate jdbc;

    /** Chiffres d'un projet. */
    public static final class Indicateurs {
        long mortsTotal, reformesTotal;
        final Map<LocalDate, Long> mortsJour = new HashMap<>();
        final Map<LocalDate, Long> reformesJour = new HashMap<>();
        final Map<LocalDate, Long> oeufsJour = new HashMap<>();
        double achete, consomme, consomme7;

        public double getAchete() { return achete; }
        public double getConsomme() { return consomme; }
        public double getConsomme7() { return consomme7; }
        public long getMortsTotal() { return mortsTotal; }
    }

    private static String in(Collection<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private static LocalDate date(Object o) {
        return o == null ? null : ((java.sql.Date) o).toLocalDate();
    }

    public Map<Long, Indicateurs> charger(List<Projets> projets, LocalDate auj) {
        Map<Long, Indicateurs> res = new HashMap<>();
        if (projets == null || projets.isEmpty()) return res;
        for (Projets p : projets) res.put(p.getId(), new Indicateurs());
        String ids = in(res.keySet()); // identifiants numériques : sûrs dans la requête
        java.sql.Date debutHisto = java.sql.Date.valueOf(auj.minusDays(JOURS_HISTO - 1));

        // 1-2. Mortalité et réformes : total, et détail par jour sur les 10 derniers jours.
        jdbc.query("SELECT projet_id, CASE WHEN date >= ? THEN date END AS j, SUM(nombre_morts) FROM mortalites "
                + "WHERE projet_id IN (" + ids + ") AND COALESCE(removed, false) = false GROUP BY 1, 2", rs -> {
                    Indicateurs x = res.get(rs.getLong(1));
                    long n = rs.getLong(3);
                    x.mortsTotal += n;
                    LocalDate d = date(rs.getDate(2));
                    if (d != null) x.mortsJour.merge(d, n, Long::sum);
                }, debutHisto);
        jdbc.query("SELECT projet_id, CASE WHEN date >= ? THEN date END AS j, SUM(nombre_sujets) FROM reformes "
                + "WHERE projet_id IN (" + ids + ") AND COALESCE(removed, false) = false GROUP BY 1, 2", rs -> {
                    Indicateurs x = res.get(rs.getLong(1));
                    long n = rs.getLong(3);
                    x.reformesTotal += n;
                    LocalDate d = date(rs.getDate(2));
                    if (d != null) x.reformesJour.merge(d, n, Long::sum);
                }, debutHisto);
        // 3. Œufs collectés par jour, 10 derniers jours.
        jdbc.query("SELECT projet_id, date, SUM(oeufs_collectes) FROM collectes_oeufs "
                + "WHERE projet_id IN (" + ids + ") AND COALESCE(removed, false) = false AND date >= ? GROUP BY 1, 2", rs -> {
                    res.get(rs.getLong(1)).oeufsJour.merge(date(rs.getDate(2)), rs.getLong(3), Long::sum);
                }, debutHisto);
        // 4. Aliment consommé : total et 7 derniers jours complets (hier et les 6 d'avant).
        jdbc.query("SELECT projet_id, SUM(quantite_kg), COALESCE(SUM(quantite_kg) FILTER (WHERE date >= ? AND date < ?), 0) "
                + "FROM consommations_aliment WHERE projet_id IN (" + ids + ") AND COALESCE(removed, false) = false GROUP BY 1",
                rs -> {
                    Indicateurs x = res.get(rs.getLong(1));
                    x.consomme = rs.getDouble(2);
                    x.consomme7 = rs.getDouble(3);
                }, java.sql.Date.valueOf(auj.minusDays(7)), java.sql.Date.valueOf(auj));
        // 5. Aliment acheté.
        jdbc.query("SELECT projet_id, SUM(quantite_kg) FROM alimentations "
                + "WHERE projet_id IN (" + ids + ") AND COALESCE(removed, false) = false GROUP BY 1", rs -> {
                    res.get(rs.getLong(1)).achete = rs.getDouble(2);
                });
        return res;
    }

    private static long vivantsActuels(Projets p, Indicateurs x) {
        int nb = p.getNbSujets() == null ? 0 : p.getNbSujets();
        return Math.max(0, nb - x.mortsTotal - x.reformesTotal);
    }

    // Sujets vivants au soir du jour d : vivants actuels + ce qui est sorti APRÈS d.
    private static long vivantsLeSoir(Projets p, Indicateurs x, LocalDate d, LocalDate auj) {
        long v = vivantsActuels(p, x);
        for (LocalDate j = d.plusDays(1); !j.isAfter(auj); j = j.plusDays(1)) {
            v += x.mortsJour.getOrDefault(j, 0L) + x.reformesJour.getOrDefault(j, 0L);
        }
        return v;
    }

    private static boolean enCours(Projets p, LocalDate auj) {
        if (p.getInitialisation() != null && Boolean.TRUE.equals(p.getInitialisation().getArchive())) return false;
        if (p.getDateCloture() != null) return false;
        return p.getDebut() == null || !p.getDebut().isAfter(auj);
    }

    private static String un(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0).replace('.', ',');
    }

    private static NotificationDTO notif(Projets p, String key, String type, String level, String message) {
        return NotificationDTO.builder().key(key).type(type).level(level).message(message)
                .projetCode(p.getCode()).projetUniqueId(p.getUniqueId()).actionPath("/projets/" + p.getUniqueId()).build();
    }

    // 1. Mortalité anormale.
    public NotificationDTO mortaliteAnormale(Projets p, Indicateurs x, LocalDate auj) {
        if (x == null || !enCours(p, auj)) return null;
        long morts = x.mortsJour.getOrDefault(auj, 0L);
        if (morts < MORTS_MIN) return null;
        long avant = 0;
        for (int i = 1; i <= 7; i++) avant += x.mortsJour.getOrDefault(auj.minusDays(i), 0L);
        double moyenne = avant / 7.0;
        long vivantsMatin = vivantsActuels(p, x) + morts + x.reformesJour.getOrDefault(auj, 0L);
        double pct = vivantsMatin > 0 ? morts * 100.0 / vivantsMatin : 100.0;
        boolean anormal = morts > MORTS_FOIS_MOYENNE * moyenne || pct > MORTS_PCT_VIVANTS;
        if (!anormal) return null;
        String level = pct >= MORTS_PCT_CRITIQUE ? "CRITIQUE" : "WARNING";
        return notif(p, "mortalite-anormale-" + p.getUniqueId() + "-" + auj, "MORTALITE", level,
                "Mortalité anormale aujourd'hui : " + morts + " morts (" + un(pct) + " % des sujets), "
                        + "moyenne des 7 jours d'avant : " + un(moyenne) + " par jour. " + p.getCode());
    }

    // 2. Stock d'aliment bas (moins de 5 jours). Le stock épuisé reste l'alerte historique.
    public NotificationDTO stockAlimentBas(Projets p, Indicateurs x, LocalDate auj) {
        if (x == null || !enCours(p, auj) || x.achete <= 0) return null;
        double restant = x.achete - x.consomme;
        double parJour = x.consomme7 / 7.0;
        if (restant <= 0 || parJour <= 0) return null;
        double jours = restant / parJour;
        if (jours >= STOCK_JOURS_MIN) return null;
        long j = (long) Math.floor(jours);
        return notif(p, "stock-aliment-bas-" + p.getUniqueId() + "-" + auj, "STOCK", "WARNING",
                "Stock d'aliment bas : " + Math.round(restant) + " kg, environ " + (j < 1 ? "moins d'un jour" : j + (j > 1 ? " jours" : " jour"))
                        + " à " + Math.round(parJour) + " kg par jour. " + p.getCode());
    }

    // 3. Ponte en baisse.
    public NotificationDTO ponteEnBaisse(Projets p, Indicateurs x, LocalDate auj) {
        if (x == null || !enCours(p, auj)) return null;
        if (p.getObjectif() != Objectif.PONTE && p.getObjectif() != Objectif.MIXTE) return null;
        // Jours complets seulement (hier et avant) : aujourd'hui, la collecte du soir n'est
        // souvent pas encore saisie, ce qui ferait croire à une baisse chaque jour.
        double[] recent = taux(p, x, auj, 1, 3);
        double[] avant = taux(p, x, auj, 4, 10);
        if (recent[2] < 2 || avant[2] < 3 || recent[1] <= 0 || avant[1] <= 0) return null;
        double tRecent = recent[0] * 100.0 / recent[1];
        double tAvant = avant[0] * 100.0 / avant[1];
        if (tAvant - tRecent <= PONTE_BAISSE_POINTS) return null;
        return notif(p, "ponte-baisse-" + p.getUniqueId() + "-" + auj, "PONTE", "WARNING",
                "Ponte en baisse : " + un(tRecent) + " % sur les 3 derniers jours contre " + un(tAvant)
                        + " % les 7 jours d'avant. " + p.getCode());
    }

    // [œufs, sujets vivants, jours de collecte] sur les jours auj-de .. auj-a (de <= a).
    private static double[] taux(Projets p, Indicateurs x, LocalDate auj, int de, int a) {
        double oeufs = 0, sujets = 0, jours = 0;
        for (int i = de; i <= a; i++) {
            LocalDate d = auj.minusDays(i);
            Long o = x.oeufsJour.get(d);
            if (o == null) continue;
            long v = vivantsLeSoir(p, x, d, auj); // vivants ce jour-là, comme le Reporting
            if (v <= 0) continue;
            oeufs += o;
            sujets += v;
            jours++;
        }
        return new double[] { oeufs, sujets, jours };
    }

    public List<NotificationDTO> nouvelles(Projets p, Indicateurs x, LocalDate auj) {
        List<NotificationDTO> l = new ArrayList<>();
        NotificationDTO n;
        if ((n = mortaliteAnormale(p, x, auj)) != null) l.add(n);
        if ((n = stockAlimentBas(p, x, auj)) != null) l.add(n);
        if ((n = ponteEnBaisse(p, x, auj)) != null) l.add(n);
        return l;
    }
}
