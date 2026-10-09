package com.diafarms.ml.ServiceImpl;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.AbonnementTarifDTO;
import com.diafarms.ml.commons.AbonnementTarif;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.repository.AbonnementConfigRepo;

import lombok.extern.slf4j.Slf4j;

// Seule source du prix d'abonnement d'une ferme (règle : commons/AbonnementTarif) :
// /abonnements/moi, déclaration d'un paiement, rappels, cloche, console SUPER_ADMIN et
// simulation publique passent tous par ici.
//
// Poules comptées = le plus grand nombre de sujets vivants des Projets en cours de la
// ferme, jour par jour, sur les 30 derniers jours (aujourd'hui compris). Même définition
// que le Reporting et la main-d'œuvre (ReportingService, MainOeuvreService) :
//   - sujets vivants d'un Projet un jour J = sujets de départ - morts - réformés jusqu'à J
//     inclus, jamais négatif ;
//   - Projet en cours le jour J : commencé au plus tard J, et pas terminé avant J (fin
//     d'un Projet clôturé : voir plus bas). Projets supprimés ignorés.
// Une ferme de chair vide entre deux bandes paie donc selon sa dernière bande pendant 30
// jours, puis le minimum.
//
// Fin d'un Projet clôturé, pour le prix SEULEMENT (le Reporting et la main-d'œuvre gardent
// MainOeuvreService.finEffective) : libération des poulaillers, sinon jour de la clôture
// (Projets.dateCloture), sinon le plus tôt entre la fin prévue et la dernière modification
// (anciens projets clôturés sans date). Un projet clôturé compte donc 30 jours après sa
// clôture, puis plus du tout, même si sa fin prévue est plus tard.
//
// Performances : 2 requêtes SQL pour N fermes (projets, puis morts + réformes groupés par
// Projet et par jour), jamais une requête par ferme. Le comptage tourne sur la connexion
// de la transaction en cours, derrière un point de sauvegarde JDBC (SAVEPOINT) : s'il
// échoue, seul ce point est annulé, la transaction de l'appelant reste saine, et aucune
// connexion de plus n'est prise. (PROPAGATION_NESTED n'est pas possible ici :
// JpaTransactionManager refuse les points de sauvegarde avec Hibernate.) Hors
// transaction, la connexion est en autocommit : rien à protéger. Le tarif est alors marqué
// calculEnErreur. Les appelants ne
// facturent jamais un tarif en erreur (déclaration refusée, montant absent des rappels).
@Service
@Slf4j
public class AbonnementTarifService {

    private static final int PAQUET = 1000;

    private final JdbcTemplate jdbc;
    private final AbonnementConfigRepo configRepo;

    public AbonnementTarifService(JdbcTemplate jdbc, AbonnementConfigRepo configRepo) {
        this.jdbc = jdbc;
        this.configRepo = configRepo;
    }

    public record Comptage(int poules, LocalDate dateMax) {}

    private static final Comptage ZERO = new Comptage(0, null);

    // ------------------------------------------------------------------ API

    public AbonnementTarif.Regles regles() {
        return AbonnementTarif.regles(configRepo.findFirstByOrderByIdAsc());
    }

    // Simulation (page d'inscription, FAQ) : prix pour un nombre de poules donné, sans prix fixe.
    public AbonnementTarifDTO simulation(int poules) {
        return AbonnementTarif.calculer(poules, null, null, regles(), false);
    }

    // Tarif d'UNE ferme. abonnement : pour le prix fixe éventuel (null = aucun).
    public AbonnementTarifDTO tarifFerme(Long farmId, Abonnement abonnement) {
        return tarifFerme(farmId, abonnement, configRepo.findFirstByOrderByIdAsc());
    }

    public AbonnementTarifDTO tarifFerme(Long farmId, Abonnement abonnement, AbonnementConfig config) {
        Map<Long, Comptage> c = compterSansRisque(farmId == null ? List.of() : List.of(farmId));
        return construire(c, farmId, abonnement, AbonnementTarif.regles(config));
    }

    // Tarifs de plusieurs fermes en une fois. farmIds null : toutes les fermes.
    // abonnements : farmId -> abonnement (prix fixe), peut être incomplet.
    public Map<Long, AbonnementTarifDTO> tarifsFermes(Collection<Long> farmIds, Map<Long, Abonnement> abonnements,
            AbonnementConfig config) {
        AbonnementTarif.Regles r = AbonnementTarif.regles(config);
        Map<Long, Comptage> c = compterSansRisque(farmIds);
        Collection<Long> ids = farmIds != null ? farmIds : unionIds(c, abonnements);
        Map<Long, AbonnementTarifDTO> res = new HashMap<>();
        for (Long id : ids) {
            res.put(id, construire(c, id, abonnements == null ? null : abonnements.get(id), r));
        }
        return res;
    }

    // Tarif d'une ferme absente d'une carte tarifsFermes (aucun Projet) : 0 poule.
    public AbonnementTarifDTO tarifSansPoule(Abonnement abonnement, AbonnementConfig config) {
        return AbonnementTarif.calculer(0, null, abonnement, AbonnementTarif.regles(config), false);
    }

    private static Collection<Long> unionIds(Map<Long, Comptage> c, Map<Long, Abonnement> abonnements) {
        java.util.Set<Long> s = new java.util.HashSet<>();
        if (c != null) s.addAll(c.keySet());
        if (abonnements != null) s.addAll(abonnements.keySet());
        return s;
    }

    private static AbonnementTarifDTO construire(Map<Long, Comptage> comptages, Long farmId, Abonnement abonnement,
            AbonnementTarif.Regles r) {
        if (comptages == null) {
            // Comptage en échec : minimum (ou prix fixe), jamais d'exception.
            return AbonnementTarif.calculer(0, null, abonnement, r, true);
        }
        Comptage c = comptages.getOrDefault(farmId, ZERO);
        return AbonnementTarif.calculer(c.poules(), c.dateMax(), abonnement, r, false);
    }

    // null si le comptage a échoué (voir l'en-tête).
    private Map<Long, Comptage> compterSansRisque(Collection<Long> farmIds) {
        LocalDate auj = LocalDate.now();
        Map<Long, long[]> jours = sujetsParJourSansRisque(farmIds, auj.minusDays(AbonnementTarif.FENETRE_JOURS - 1L), auj);
        if (jours == null) return null;
        return maxSurFenetre(jours, auj.minusDays(AbonnementTarif.FENETRE_JOURS - 1L));
    }

    // Sujets vivants de chaque ferme, jour par jour, du jour deb au jour fin inclus
    // (index 0 = deb). Même définition que le prix (voir l'en-tête) ; même protection
    // (point de sauvegarde JDBC). null si le comptage a échoué. farmIds null : toutes les
    // fermes ; une ferme sans Projet est absente de la carte (0 sujet chaque jour).
    // Utilisé aussi par le crédit (CreditService) : moyenne des poules d'un mois.
    public Map<Long, long[]> sujetsParJourSansRisque(Collection<Long> farmIds, LocalDate deb, LocalDate fin) {
        try {
            return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Map<Long, long[]>>) con -> {
                // Toutes les requêtes sur CETTE connexion (aucune autre prise dans le pool).
                JdbcTemplate meme = new JdbcTemplate(
                        new org.springframework.jdbc.datasource.SingleConnectionDataSource(con, true));
                if (con.getAutoCommit()) return sujetsParJour(meme, farmIds, deb, fin);
                java.sql.Savepoint sp = con.setSavepoint();
                try {
                    Map<Long, long[]> r = sujetsParJour(meme, farmIds, deb, fin);
                    con.releaseSavepoint(sp);
                    return r;
                } catch (RuntimeException e) {
                    con.rollback(sp);
                    throw e;
                }
            });
        } catch (Exception e) {
            log.error("Comptage des poules (abonnement) en échec : {}", e.getMessage(), e);
            return null;
        }
    }

    // ------------------------------------------------------------------ comptage

    private record Projet(long id, long farmId, LocalDate debut, int nbSujets, LocalDate fin) {}

    // farmIds null : toutes les fermes ; vide : aucune.
    Map<Long, Comptage> compterPoules(JdbcTemplate jdbc, Collection<Long> farmIds, LocalDate aujourdHui) {
        LocalDate deb = aujourdHui.minusDays(AbonnementTarif.FENETRE_JOURS - 1L);
        return maxSurFenetre(sujetsParJour(jdbc, farmIds, deb, aujourdHui), deb);
    }

    // Le plus grand total d'un jour (le plus récent en cas d'égalité), et ce jour-là.
    private static Map<Long, Comptage> maxSurFenetre(Map<Long, long[]> totaux, LocalDate deb) {
        Map<Long, Comptage> res = new HashMap<>();
        for (Map.Entry<Long, long[]> e : totaux.entrySet()) {
            long max = 0;
            int jourMax = -1;
            long[] t = e.getValue();
            for (int i = 0; i < t.length; i++) {
                if (t[i] > 0 && t[i] >= max) { max = t[i]; jourMax = i; }
            }
            res.put(e.getKey(), new Comptage((int) Math.min(Integer.MAX_VALUE, max),
                    jourMax >= 0 ? deb.plusDays(jourMax) : null));
        }
        return res;
    }

    // Sujets vivants par ferme et par jour sur [deb, fin]. 2 requêtes par paquet de 1 000
    // fermes ou Projets, jamais une requête par ferme ni par jour.
    Map<Long, long[]> sujetsParJour(JdbcTemplate jdbc, Collection<Long> farmIds, LocalDate deb, LocalDate fin) {
        Map<Long, long[]> totaux = new HashMap<>();
        if (farmIds != null && farmIds.isEmpty()) return totaux;
        if (fin.isBefore(deb)) return totaux;

        // 1. Projets commencés au plus tard le dernier jour, avec leur fin effective.
        List<Projet> projets = new ArrayList<>();
        List<List<Long>> paquetsFermes = farmIds == null ? java.util.Collections.singletonList(null) : paquets(farmIds);
        for (List<Long> paquet : paquetsFermes) {
            List<Object> args = new ArrayList<>();
            args.add(Date.valueOf(fin));
            StringBuilder sql = new StringBuilder(
                    "SELECT p.id, p.farm_id, p.date_debut, COALESCE(p.nb_sujets, 0), COALESCE(p.archive, false), "
                    + "COALESCE(o.liberation, p.date_cloture, LEAST(p.date_fin_prevue, CAST(p.updated_at AS date))) "
                    + "FROM projets p LEFT JOIN (SELECT projet_id, MAX(date_sortie) AS liberation "
                    + "FROM occupations_batiments GROUP BY projet_id) o ON o.projet_id = p.id "
                    + "WHERE p.farm_id IS NOT NULL AND COALESCE(p.removed, false) = false "
                    + "AND p.date_debut IS NOT NULL AND p.date_debut <= ?");
            if (paquet != null) {
                sql.append(" AND p.farm_id IN (").append(marques(paquet.size())).append(")");
                args.addAll(paquet);
            }
            jdbc.query(sql.toString(), rs -> {
                boolean cloture = rs.getBoolean(5);
                LocalDate f = null;
                if (cloture) {
                    f = date(rs.getDate(6));
                    // Clôturé sans aucune date : jamais compté « en cours » pour toujours.
                    if (f == null) f = deb.minusDays(1);
                }
                if (f != null && f.isBefore(deb)) return; // terminé avant la période
                projets.add(new Projet(rs.getLong(1), rs.getLong(2), date(rs.getDate(3)), rs.getInt(4), f));
            }, args.toArray());
        }
        if (projets.isEmpty()) return totaux;

        // 2. Morts + réformés de ces Projets : cumul avant la période (date null), puis par jour.
        Map<Long, Long> avant = new HashMap<>();
        Map<Long, Map<LocalDate, Long>> parJour = new HashMap<>();
        List<Long> projetIds = projets.stream().map(Projet::id).toList();
        for (List<Long> paquet : paquets(projetIds)) {
            List<Object> args = new ArrayList<>();
            args.add(Date.valueOf(deb));
            args.add(Date.valueOf(fin));
            args.addAll(paquet);
            args.add(Date.valueOf(fin));
            args.addAll(paquet);
            String in = marques(paquet.size());
            jdbc.query("SELECT x.projet_id, CASE WHEN x.d < ? THEN NULL ELSE x.d END AS jour, SUM(x.n) FROM ("
                    + "SELECT m.projet_id, m.date AS d, m.nombre_morts AS n FROM mortalites m "
                    + "WHERE COALESCE(m.removed, false) = false AND m.date <= ? AND m.projet_id IN (" + in + ") "
                    + "UNION ALL SELECT r.projet_id, r.date, r.nombre_sujets FROM reformes r "
                    + "WHERE COALESCE(r.removed, false) = false AND r.date <= ? AND r.projet_id IN (" + in + ")"
                    + ") x GROUP BY 1, 2", rs -> {
                        long pid = rs.getLong(1);
                        LocalDate jour = date(rs.getDate(2));
                        long n = rs.getLong(3);
                        if (jour == null) avant.merge(pid, n, Long::sum);
                        else parJour.computeIfAbsent(pid, k -> new HashMap<>()).merge(jour, n, Long::sum);
                    }, args.toArray());
        }

        // 3. Jour par jour : total de la ferme.
        int nbJours = (int) java.time.temporal.ChronoUnit.DAYS.between(deb, fin) + 1;
        for (Projet p : projets) {
            long[] t = totaux.computeIfAbsent(p.farmId(), k -> new long[nbJours]);
            long cumul = avant.getOrDefault(p.id(), 0L);
            Map<LocalDate, Long> pj = parJour.getOrDefault(p.id(), Map.of());
            for (int i = 0; i < nbJours; i++) {
                LocalDate d = deb.plusDays(i);
                cumul += pj.getOrDefault(d, 0L);
                boolean enCours = !p.debut().isAfter(d) && (p.fin() == null || !p.fin().isBefore(d));
                if (enCours) t[i] += Math.max(0, p.nbSujets() - cumul);
            }
        }
        return totaux;
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static String marques(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private static List<List<Long>> paquets(Collection<Long> ids) {
        List<Long> liste = new ArrayList<>(ids);
        List<List<Long>> res = new ArrayList<>();
        for (int i = 0; i < liste.size(); i += PAQUET) {
            res.add(liste.subList(i, Math.min(liste.size(), i + PAQUET)));
        }
        return res;
    }
}
