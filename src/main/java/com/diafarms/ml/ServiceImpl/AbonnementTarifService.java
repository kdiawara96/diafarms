package com.diafarms.ml.ServiceImpl;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
//   - Projet en cours le jour J : commencé au plus tard J, et pas clôturé avant J (fin
//     effective = MainOeuvreService.finEffective : libération des poulaillers, à défaut fin
//     prévue). Projets supprimés ignorés.
// Une ferme de chair vide entre deux bandes paie donc selon sa dernière bande pendant 30
// jours, puis le minimum.
//
// Performances : 2 requêtes SQL pour N fermes (projets, puis morts + réformes groupés par
// Projet et par jour), jamais une requête par ferme. Le comptage tourne dans sa propre
// transaction en lecture seule : s'il échoue, la transaction de l'appelant reste saine et
// le tarif retombe sur le minimum (calculEnErreur = true), sans jamais faire planter la
// page, la déclaration ou la tâche des rappels.
@Service
@Slf4j
public class AbonnementTarifService {

    private static final int PAQUET = 1000;

    private final JdbcTemplate jdbc;
    private final AbonnementConfigRepo configRepo;
    private final TransactionTemplate txIsolee;

    public AbonnementTarifService(JdbcTemplate jdbc, AbonnementConfigRepo configRepo,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.configRepo = configRepo;
        this.txIsolee = new TransactionTemplate(transactionManager);
        this.txIsolee.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txIsolee.setReadOnly(true);
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
        try {
            LocalDate auj = LocalDate.now();
            return txIsolee.execute(s -> compterPoules(farmIds, auj));
        } catch (Exception e) {
            log.error("Comptage des poules pour le tarif d'abonnement en échec : {}", e.getMessage(), e);
            return null;
        }
    }

    // ------------------------------------------------------------------ comptage

    private record Projet(long id, long farmId, LocalDate debut, int nbSujets, LocalDate fin) {}

    // farmIds null : toutes les fermes ; vide : aucune.
    Map<Long, Comptage> compterPoules(Collection<Long> farmIds, LocalDate aujourdHui) {
        Map<Long, Comptage> res = new HashMap<>();
        if (farmIds != null && farmIds.isEmpty()) return res;
        LocalDate deb = aujourdHui.minusDays(AbonnementTarif.FENETRE_JOURS - 1L);

        // 1. Projets commencés au plus tard aujourd'hui, avec leur fin effective.
        List<Projet> projets = new ArrayList<>();
        List<List<Long>> paquetsFermes = farmIds == null ? java.util.Collections.singletonList(null) : paquets(farmIds);
        for (List<Long> paquet : paquetsFermes) {
            List<Object> args = new ArrayList<>();
            args.add(Date.valueOf(aujourdHui));
            StringBuilder sql = new StringBuilder(
                    "SELECT p.id, p.farm_id, p.date_debut, COALESCE(p.nb_sujets, 0), COALESCE(p.archive, false), "
                    + "p.date_fin_prevue, (SELECT MAX(o.date_sortie) FROM occupations_batiments o WHERE o.projet_id = p.id), "
                    + "p.updated_at FROM projets p WHERE p.farm_id IS NOT NULL AND COALESCE(p.removed, false) = false "
                    + "AND p.date_debut IS NOT NULL AND p.date_debut <= ?");
            if (paquet != null) {
                sql.append(" AND p.farm_id IN (").append(marques(paquet.size())).append(")");
                args.addAll(paquet);
            }
            jdbc.query(sql.toString(), rs -> {
                boolean cloture = rs.getBoolean(5);
                LocalDate finPrevue = date(rs.getDate(6));
                LocalDate liberation = date(rs.getDate(7));
                LocalDate fin = MainOeuvreService.finEffective(cloture, liberation, finPrevue);
                if (cloture && fin == null) {
                    // Clôturé sans aucune date (ni libération ni fin prévue) : on prend le jour
                    // de la clôture plutôt que de le compter « en cours » pour toujours.
                    Timestamp maj = rs.getTimestamp(8);
                    fin = maj != null ? maj.toLocalDateTime().toLocalDate() : deb.minusDays(1);
                }
                if (fin != null && fin.isBefore(deb)) return; // terminé avant les 30 jours
                projets.add(new Projet(rs.getLong(1), rs.getLong(2), date(rs.getDate(3)), rs.getInt(4), fin));
            }, args.toArray());
        }
        if (projets.isEmpty()) return res;

        // 2. Morts + réformés de ces Projets : cumul avant la fenêtre (date null), puis par jour.
        Map<Long, Long> avant = new HashMap<>();
        Map<Long, Map<LocalDate, Long>> parJour = new HashMap<>();
        List<Long> projetIds = projets.stream().map(Projet::id).toList();
        for (List<Long> paquet : paquets(projetIds)) {
            List<Object> args = new ArrayList<>();
            args.add(Date.valueOf(deb));
            args.add(Date.valueOf(aujourdHui));
            args.addAll(paquet);
            args.add(Date.valueOf(aujourdHui));
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

        // 3. Jour par jour : total de la ferme, et son maximum sur la fenêtre.
        Map<Long, long[]> totaux = new HashMap<>(); // farmId -> sujets vivants par jour (index 0 = deb)
        int nbJours = AbonnementTarif.FENETRE_JOURS;
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
