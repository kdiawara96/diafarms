package com.diafarms.ml.ServiceImpl;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.AdminConsoleDTO;
import com.diafarms.ml.DTO.PaiementAbonnementDTO;
import com.diafarms.ml.commons.AbonnementEcheance;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Periodicite;
import com.diafarms.ml.enums.StatutAbonnement;
import com.diafarms.ml.enums.StatutPaiementAbonnement;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.NoteAdminFerme;
import com.diafarms.ml.models.PaiementAbonnement;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.AbonnementRepo;
import com.diafarms.ml.repository.FarmsRepo;
import com.diafarms.ml.repository.NoteAdminFermeRepo;
import com.diafarms.ml.repository.PaiementAbonnementRepo;
import com.diafarms.ml.request.others.AdminActiverAbonnementRequest;
import com.diafarms.ml.request.others.AdminNoteRequest;
import com.diafarms.ml.request.others.AdminProlongerEssaiRequest;
import com.diafarms.ml.request.others.AdminSuspendreRequest;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

// Console d'administration SUPER_ADMIN (web : page d'accueil du SUPER_ADMIN, onglets
// Vue d'ensemble / Fermes / Finances / Journal). Tout est réservé au SUPER_ADMIN, vérifié
// ici côté serveur (ensureSuperAdmin), jamais seulement par le menu web.
//
// Performances : les chiffres par ferme (utilisateurs, projets en cours, sujets vivants,
// dernière activité, total payé) viennent de quelques requêtes SQL agrégées GROUP BY
// farm_id, jamais d'une requête par ferme : quelques centaines de fermes = une dizaine de
// requêtes au total. Filtres facultatifs : clauses SQL ajoutées seulement quand le filtre
// est présent (jamais l'idiome « :param IS NULL OR ... » qui plante sous Postgres).
//
// Actions sur une ferme (activer, suspendre, réactiver, prolonger l'essai, note) :
// journalisées dans logs avec entity_type = "AdminFerme" et entity_id = farms.id (farm_id
// du log laissé vide : une action du SUPER_ADMIN n'est ni une activité de la ferme, ni
// visible dans son propre journal).
//
// Rien ici ne bloque l'API ni le mobile : une suspension ne fait que passer statutEffectif
// à EXPIRE (avec suspendu=true) dans /abonnements/moi, que seul le web lit (AbonnementGate).
@Service
@RequiredArgsConstructor
public class AdminConsoleService {

    public static final String ENTITE_ADMIN_FERME = "AdminFerme";

    // Paiements comptés dans les statistiques : ceux des fermes NON exclues
    // (Farm.exclureStatistiques, ex. ferme de démonstration). Colonnes non qualifiées
    // utilisables comme sur paiements_abonnement.
    private static final String PAIEMENTS_STATS = "(SELECT p.* FROM paiements_abonnement p "
            + "JOIN abonnements a ON a.id = p.abonnement_id JOIN farms f ON f.id = a.farm_id "
            + "WHERE COALESCE(f.exclure_statistiques, false) = false) ps";

    private static boolean exclue(Farm f) {
        return Boolean.TRUE.equals(f.getExclureStatistiques());
    }

    private final JdbcTemplate jdbc;
    private final AbonnementRepo abonnementRepo;
    private final AbonnementConfigRepo configRepo;
    private final PaiementAbonnementRepo paiementRepo;
    private final FarmsRepo farmsRepo;
    private final NoteAdminFermeRepo noteRepo;
    private final AbonnementServiceImpl abonnementService;
    private final OtherService otherService;
    private final LogsServices logs;
    private final com.diafarms.ml.repository.UtilisateursRepo utilisateursRepo;
    private final com.diafarms.ml.services.EmailService emailService;
    private final com.diafarms.ml.commons.AbonnementAccesMobile accesMobile;
    private final AbonnementTarifService tarifService;
    private final ParrainageService parrainageService;
    private final GuideDemarrageService guideService;
    private final CreditService creditService;

    // ------------------------------------------------------------------ sécurité

    private Utilisateurs superAdmin() {
        Utilisateurs u;
        try {
            u = otherService.getCurrentUser();
        } catch (Exception e) {
            u = null;
        }
        boolean ok = u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
        if (!ok) {
            // 403 (voir AdminConsoleController).
            throw new AccessDeniedException("Seul un SUPER_ADMIN peut effectuer cette action.");
        }
        return u;
    }

    public void verifierSuperAdmin() {
        superAdmin();
    }

    // ------------------------------------------------------------------ outils SQL

    private static long l(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static double d(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private static LocalDateTime ldt(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        if (o instanceof LocalDateTime x) return x;
        return null;
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate parseDate(String s, String libelle) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim().substring(0, Math.min(10, s.trim().length())));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date invalide pour " + libelle + " : " + s);
        }
    }

    private static String nomFerme(Farm f, String nomInscription) {
        if (f.getNom() != null && !f.getNom().isBlank()) return f.getNom();
        if (nomInscription != null && !nomInscription.isBlank()) return nomInscription;
        return f.getUniqueId();
    }

    // ------------------------------------------------------------------ données agrégées par ferme

    private record Proprio(String nom, String telephone, String email, String nomFermeInscription) {}

    private record Agregats(
            Map<Long, Proprio> proprios,
            Map<Long, long[]> utilisateurs,            // [nb actifs]
            Map<Long, LocalDateTime> premiereInscription,
            Map<Long, LocalDateTime> derniereConnexion,
            Map<Long, long[]> projets,                 // [projets en cours, sujets vivants]
            Map<Long, LocalDateTime> dernierLog,
            Map<Long, Double> totalPaye,
            Map<Long, Boolean> enAttente,
            Map<Long, GuideDemarrageService.Etapes> etapes,
            Map<Long, long[]> parrainages) {}         // [filleuls, mois gagnés, 1 si parrainée]

    // farmId null : toutes les fermes (liste, tableau de bord) ; sinon UNE ferme (fiche),
    // filtre passé en paramètre lié, sans parcourir les données des autres fermes.
    private Agregats agregats(Long farmId) {
        boolean une = farmId != null;
        Object[] args = une ? new Object[] { farmId } : new Object[0];

        Map<Long, Proprio> proprios = new HashMap<>();
        // Propriétaire : le premier ADMIN (plus petit id) de la ferme.
        jdbc.query("SELECT DISTINCT ON (u.farm_id) u.farm_id, u.full_name, u.telephone, u.email, u.farm_name "
                + "FROM utilisateurs u JOIN roles_users ru ON ru.id_utilisateurs = u.id JOIN roles r ON r.id = ru.id_roles "
                + "WHERE r.role = 'ADMIN' AND u.farm_id IS NOT NULL AND COALESCE(u.removed, false) = false "
                + (une ? "AND u.farm_id = ? " : "")
                + "ORDER BY u.farm_id, u.id", rs -> {
                    proprios.put(rs.getLong(1), new Proprio(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }, args);

        Map<Long, long[]> utilisateurs = new HashMap<>();
        Map<Long, LocalDateTime> premiere = new HashMap<>();
        Map<Long, LocalDateTime> derniereCo = new HashMap<>();
        jdbc.query("SELECT farm_id, "
                + "COUNT(*) FILTER (WHERE COALESCE(removed, false) = false AND COALESCE(archive, false) = false), "
                + "MIN(created_at), MAX(last_login) FROM utilisateurs WHERE farm_id IS NOT NULL "
                + (une ? "AND farm_id = ? " : "") + "GROUP BY farm_id", rs -> {
                    long id = rs.getLong(1);
                    utilisateurs.put(id, new long[] { rs.getLong(2) });
                    premiere.put(id, ldt(rs.getTimestamp(3)));
                    derniereCo.put(id, ldt(rs.getTimestamp(4)));
                }, args);

        // Projets en cours (ni supprimés ni clôturés) et leurs sujets vivants :
        // sujets de départ - morts - réformés (même définition qu'EffectifVivantHelper),
        // jamais négatif pour un projet. Toutes les fermes : sommes groupées en un passage ;
        // une ferme : sous-requêtes par projet de cette ferme seulement.
        Map<Long, long[]> projets = new HashMap<>();
        String sqlProjets = une
                ? "SELECT p.farm_id, COUNT(*), COALESCE(SUM(GREATEST(COALESCE(p.nb_sujets, 0) "
                        + "- COALESCE((SELECT SUM(m.nombre_morts) FROM mortalites m WHERE m.projet_id = p.id AND COALESCE(m.removed, false) = false), 0) "
                        + "- COALESCE((SELECT SUM(r.nombre_sujets) FROM reformes r WHERE r.projet_id = p.id AND COALESCE(r.removed, false) = false), 0), 0)), 0) "
                        + "FROM projets p WHERE p.farm_id = ? AND COALESCE(p.removed, false) = false AND COALESCE(p.archive, false) = false "
                        + "GROUP BY p.farm_id"
                : "SELECT p.farm_id, COUNT(*), "
                        + "COALESCE(SUM(GREATEST(COALESCE(p.nb_sujets, 0) - COALESCE(m.morts, 0) - COALESCE(rf.ref, 0), 0)), 0) "
                        + "FROM projets p "
                        + "LEFT JOIN (SELECT projet_id, SUM(nombre_morts) AS morts FROM mortalites "
                        + "           WHERE COALESCE(removed, false) = false GROUP BY projet_id) m ON m.projet_id = p.id "
                        + "LEFT JOIN (SELECT projet_id, SUM(nombre_sujets) AS ref FROM reformes "
                        + "           WHERE COALESCE(removed, false) = false GROUP BY projet_id) rf ON rf.projet_id = p.id "
                        + "WHERE p.farm_id IS NOT NULL AND COALESCE(p.removed, false) = false AND COALESCE(p.archive, false) = false "
                        + "GROUP BY p.farm_id";
        jdbc.query(sqlProjets, rs -> {
            projets.put(rs.getLong(1), new long[] { rs.getLong(2), rs.getLong(3) });
        }, args);

        // Dernière activité : une sous-requête par ferme (peut utiliser un index sur
        // logs(farm_id, created_at), voir le rapport), plutôt qu'un GROUP BY sur tout logs.
        Map<Long, LocalDateTime> dernierLog = new HashMap<>();
        jdbc.query("SELECT f.id, (SELECT MAX(l.created_at) FROM logs l WHERE l.farm_id = f.id) FROM farms f"
                + (une ? " WHERE f.id = ?" : ""), rs -> {
                    dernierLog.put(rs.getLong(1), ldt(rs.getTimestamp(2)));
                }, args);

        Map<Long, Double> totalPaye = new HashMap<>();
        Map<Long, Boolean> enAttente = new HashMap<>();
        jdbc.query("SELECT a.farm_id, COALESCE(SUM(p.montant) FILTER (WHERE p.statut = 'VALIDE'), 0), "
                + "BOOL_OR(p.statut = 'EN_ATTENTE') FROM paiements_abonnement p "
                + "JOIN abonnements a ON a.id = p.abonnement_id "
                + (une ? "WHERE a.farm_id = ? " : "") + "GROUP BY a.farm_id", rs -> {
                    totalPaye.put(rs.getLong(1), rs.getDouble(2));
                    enAttente.put(rs.getLong(1), rs.getBoolean(3));
                }, args);
        // Guide « Bien démarrer » : une requête pour toutes les fermes (voir GuideDemarrageService).
        Map<Long, GuideDemarrageService.Etapes> etapes = guideService.etapes(farmId);
        Map<Long, long[]> parrainages = new HashMap<>();
        jdbc.query("SELECT parrain_farm_id, COUNT(*), COUNT(recompense_le) FROM parrainages "
                + (une ? "WHERE parrain_farm_id = ? " : "") + "GROUP BY parrain_farm_id", rs -> {
                    parrainages.computeIfAbsent(rs.getLong(1), k -> new long[3])[0] = rs.getLong(2);
                    parrainages.get(rs.getLong(1))[1] = rs.getLong(3);
                }, args);
        jdbc.query("SELECT filleul_farm_id FROM parrainages" + (une ? " WHERE filleul_farm_id = ?" : ""), rs -> {
            parrainages.computeIfAbsent(rs.getLong(1), k -> new long[3])[2] = 1;
        }, args);
        return new Agregats(proprios, utilisateurs, premiere, derniereCo, projets, dernierLog, totalPaye, enAttente,
                etapes, parrainages);
    }

    // Une ligne par ferme, avec son abonnement (et son état du jour) s'il existe.
    private record Ligne(Farm farm, Abonnement abonnement, AbonnementEcheance.Etat etat, AdminConsoleDTO.Ferme dto,
            com.diafarms.ml.DTO.AbonnementTarifDTO tarif, com.diafarms.ml.DTO.CreditDTO credit) {}

    private List<Ligne> lignes() {
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        LocalDate auj = LocalDate.now();
        Map<Long, Abonnement> parFerme = new HashMap<>();
        for (Abonnement a : abonnementRepo.findAllAvecFerme()) parFerme.put(a.getFarm().getId(), a);
        Agregats ag = agregats(null);
        // Tarif de toutes les fermes : 2 requêtes au total (voir AbonnementTarifService).
        Map<Long, com.diafarms.ml.DTO.AbonnementTarifDTO> tarifs = tarifService.tarifsFermes(null, parFerme, config);
        // Crédit de toutes les fermes : une requête pour les soldes, un comptage pour le
        // rythme du mois (voir CreditService).
        Map<Long, Double> soldes = creditService.soldes();
        Map<Long, LocalDate> depuis = new HashMap<>();
        for (Abonnement a : parFerme.values()) depuis.put(a.getFarm().getId(), a.getCreditDepuis());
        Map<Long, CreditService.Rythme> rythmes = creditService.rythmes(null, depuis, auj);
        java.util.Set<Long> doublons = creditService.doublonsPossibles();
        List<Ligne> res = new ArrayList<>();
        for (Farm f : farmsRepo.findAll()) {
            Abonnement a = parFerme.get(f.getId());
            AbonnementEcheance.Etat etat = a != null ? AbonnementEcheance.calculer(a, config, auj) : null;
            com.diafarms.ml.DTO.AbonnementTarifDTO t = tarifs.get(f.getId());
            if (t == null) t = tarifService.tarifSansPoule(a, config);
            com.diafarms.ml.DTO.CreditDTO c = a == null ? null : creditService.etat(a, config,
                    soldes.getOrDefault(a.getId(), 0.0), rythmes == null ? null : rythmes.get(f.getId()), auj,
                    t.poulesComptees());
            res.add(new Ligne(f, a, etat, versDto(f, a, etat, ag, t, c, doublons.contains(f.getId())), t, c));
        }
        return res;
    }

    private AdminConsoleDTO.Ferme versDto(Farm f, Abonnement a, AbonnementEcheance.Etat etat, Agregats ag,
            com.diafarms.ml.DTO.AbonnementTarifDTO tarif, com.diafarms.ml.DTO.CreditDTO c, boolean doublon) {
        Long id = f.getId();
        Proprio p = ag.proprios().get(id);
        LocalDateTime inscription = ag.premiereInscription().get(id);
        if (inscription == null && a != null && a.getInitialisation() != null) inscription = a.getInitialisation().getCreatedAt();
        long[] u = ag.utilisateurs().getOrDefault(id, new long[] { 0 });
        long[] pr = ag.projets().getOrDefault(id, new long[] { 0, 0 });
        GuideDemarrageService.Etapes et = ag.etapes().getOrDefault(id, GuideDemarrageService.Etapes.vide());
        long[] pa = ag.parrainages().getOrDefault(id, new long[3]);
        return new AdminConsoleDTO.Ferme(
                f.getUniqueId(),
                nomFerme(f, p != null ? p.nomFermeInscription() : null),
                p != null ? p.nom() : null,
                p != null ? p.telephone() : null,
                p != null ? p.email() : null,
                f.getPaysCode(),
                f.getDevise(),
                f.getVille(),
                inscription != null ? inscription.toLocalDate() : null,
                etat != null ? etat.statutDetaille() : "AUCUN",
                a != null ? a.getDateFin() : null,
                etat != null ? etat.joursRestants() : null,
                etat != null ? etat.dernierJourAcces() : null,
                a != null && a.getPeriodicite() != null ? a.getPeriodicite().name() : null,
                a != null && a.estSuspendu(),
                a != null && a.estSuspendu() ? a.getMotifSuspension() : null,
                a != null && a.estSuspendu() ? a.getSuspenduLe() : null,
                u[0],
                pr[0],
                pr[1],
                max(ag.dernierLog().get(id), ag.derniereConnexion().get(id)),
                ag.totalPaye().getOrDefault(id, 0.0),
                Boolean.TRUE.equals(ag.enAttente().get(id)),
                exclue(f),
                tarif.poulesComptees(),
                tarif.prixMensuel(),
                tarif.prixAnnuel(),
                tarif.prixFixe(),
                inscription != null ? java.time.temporal.ChronoUnit.DAYS.between(inscription.toLocalDate(), LocalDate.now()) : null,
                et.faites(),
                GuideDemarrageService.TOTAL,
                et.saisie(),
                etat != null && etat.estEssai() && !etat.bloque() && !et.saisie() && !exclue(f),
                p != null ? com.diafarms.ml.commons.Telephone.international(p.telephone()) : null,
                f.getCodeParrainage(),
                pa[0],
                pa[1],
                pa[2] == 1,
                c != null ? c.solde() : 0,
                c != null ? c.coutMensuel() : tarif.prixMensuel(),
                c != null ? c.moyennePoulesMois() : 0,
                c != null && c.aRecharger(),
                c != null ? c.aChiffrer() : (!tarif.prixFixe() && tarif.surDevis()),
                c != null && c.actif(),
                doublon);
    }

    // ------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    public List<AdminConsoleDTO.Ferme> listerFermes() {
        superAdmin();
        return lignes().stream().map(Ligne::dto)
                .sorted(Comparator.comparing(AdminConsoleDTO.Ferme::inscriteLe, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static List<String> douzeDerniersMois(LocalDate auj) {
        List<String> mois = new ArrayList<>();
        YearMonth ym = YearMonth.from(auj).minusMonths(11);
        for (int i = 0; i < 12; i++) mois.add(ym.plusMonths(i).toString());
        return mois;
    }

    // Paiements validés par mois (date de validation) : "2026-10" -> [montant, nombre].
    private Map<String, double[]> revenusParMois(LocalDate du, LocalDate au) {
        Map<String, double[]> m = new HashMap<>();
        jdbc.query("SELECT to_char(date_validation, 'YYYY-MM'), SUM(montant), COUNT(*) FROM " + PAIEMENTS_STATS + " "
                + "WHERE statut = 'VALIDE' AND date_validation >= ? AND date_validation < ? GROUP BY 1",
                rs -> {
                    m.put(rs.getString(1), new double[] { rs.getDouble(2), rs.getLong(3) });
                }, du.atStartOfDay(), au.atStartOfDay());
        return m;
    }

    @Transactional(readOnly = true)
    public AdminConsoleDTO.TableauDeBord tableauDeBord() {
        superAdmin();
        LocalDate auj = LocalDate.now();
        // Fermes hors statistiques (ex. démonstration) : jamais comptées ici.
        List<Ligne> lignes = lignes().stream().filter(li -> !exclue(li.farm())).toList();

        long essai = 0, actives = 0, grace = 0, expirees = 0, suspendues = 0, aucun = 0;
        long nouvelles = 0, actives7 = 0, actives30 = 0, sujets = 0;
        YearMonth ceMois = YearMonth.from(auj);
        LocalDateTime il7 = auj.minusDays(7).atStartOfDay();
        LocalDateTime il30 = auj.minusDays(30).atStartOfDay();
        List<AdminConsoleDTO.FermeCourte> essaisFin = new ArrayList<>();
        Map<String, Long> nouvellesParMois = new HashMap<>();
        for (Ligne li : lignes) {
            AdminConsoleDTO.Ferme f = li.dto();
            switch (f.statut()) {
                case "ESSAI" -> essai++;
                case "ACTIF" -> actives++;
                case "GRACE" -> grace++;
                case "EXPIRE" -> expirees++;
                case "SUSPENDU" -> suspendues++;
                default -> aucun++;
            }
            if (f.inscriteLe() != null) {
                if (YearMonth.from(f.inscriteLe()).equals(ceMois)) nouvelles++;
                nouvellesParMois.merge(YearMonth.from(f.inscriteLe()).toString(), 1L, Long::sum);
            }
            if (f.derniereActivite() != null && !f.derniereActivite().isBefore(il7)) actives7++;
            if (f.derniereActivite() != null && !f.derniereActivite().isBefore(il30)) actives30++;
            sujets += f.sujetsVivants();
            if ("ESSAI".equals(f.statut()) && f.joursRestants() != null && f.joursRestants() >= 0 && f.joursRestants() <= 7) {
                essaisFin.add(new AdminConsoleDTO.FermeCourte(f.farmUniqueId(), f.nom(), f.dateFin(), f.statut()));
            }
        }
        essaisFin.sort(Comparator.comparing(AdminConsoleDTO.FermeCourte::dateFin));

        // Revenus : 12 derniers mois + année en cours, en une requête.
        LocalDate debut = YearMonth.from(auj).minusMonths(11).atDay(1);
        LocalDate debutAnnee = auj.withDayOfYear(1);
        Map<String, double[]> rev = revenusParMois(debut.isBefore(debutAnnee) ? debut : debutAnnee, auj.plusDays(1));
        List<AdminConsoleDTO.MoisValeur> revenus12 = new ArrayList<>();
        List<AdminConsoleDTO.MoisValeur> nouvelles12 = new ArrayList<>();
        for (String m : douzeDerniersMois(auj)) {
            double[] v = rev.getOrDefault(m, new double[] { 0, 0 });
            revenus12.add(new AdminConsoleDTO.MoisValeur(m, v[0], (long) v[1]));
            nouvelles12.add(new AdminConsoleDTO.MoisValeur(m, 0, nouvellesParMois.getOrDefault(m, 0L)));
        }
        double revMois = rev.getOrDefault(ceMois.toString(), new double[] { 0 })[0];
        double revPrec = rev.getOrDefault(ceMois.minusMonths(1).toString(), new double[] { 0 })[0];
        double revAnnee = 0;
        for (Map.Entry<String, double[]> e : rev.entrySet()) {
            if (e.getKey().startsWith(String.valueOf(auj.getYear()))) revAnnee += e.getValue()[0];
        }

        // Revenu mensuel estimé : fermes payantes non bloquées (actives ou en grâce), chacune
        // à son tarif ACTUEL (prix par poule ou tarif spécial, voir AbonnementTarifService),
        // formule annuelle comptée / 12.
        // Crédit prépayé : coût d'un mois au rythme actuel (moyenne des poules du mois, ou
        // tarif spécial) de chaque ferme payante non bloquée.
        double mrr = 0;
        long tarifsEnErreur = 0, aRecharger = 0, aChiffrer = 0;
        double creditTotal = 0;
        for (Ligne li : lignes) {
            if (li.credit() != null) {
                if (li.credit().aRecharger()) aRecharger++;
                if (li.credit().aChiffrer()) aChiffrer++;
                if (li.credit().solde() > 0) creditTotal += li.credit().solde();
            }
            String st = li.dto().statut();
            if (li.abonnement() == null || !("ACTIF".equals(st) || "GRACE".equals(st)) || li.etat().estEssai()) continue;
            if (li.credit() != null) {
                if (li.credit().comptageEnErreur()) tarifsEnErreur++;
                mrr += li.credit().coutMensuel();
            } else {
                if (!com.diafarms.ml.commons.AbonnementTarif.facturable(li.tarif())) tarifsEnErreur++;
                mrr += li.tarif().prixMensuel();
            }
        }

        Long attente = jdbc.queryForObject("SELECT COUNT(*) FROM paiements_abonnement WHERE statut = 'EN_ATTENTE'", Long.class);

        return new AdminConsoleDTO.TableauDeBord(lignes.size(), essai, actives, grace, expirees, suspendues, aucun,
                nouvelles, actives7, actives30, sujets, revMois, revPrec, revAnnee, Math.round(mrr),
                attente == null ? 0 : attente, essaisFin, revenus12, nouvelles12, tarifsEnErreur, aRecharger, aChiffrer,
                Math.round(creditTotal));
    }

    private Farm fermeOu400(String farmUniqueId) {
        Farm f = farmUniqueId == null ? null : farmsRepo.findByUniqueId(farmUniqueId);
        if (f == null) throw new IllegalArgumentException("Ferme introuvable : " + farmUniqueId);
        return f;
    }

    @Transactional(readOnly = true)
    public AdminConsoleDTO.FermeDetail detailFerme(String farmUniqueId) {
        superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        Abonnement a = abonnementRepo.findByFarm_Id(f.getId()).orElse(null);
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        AbonnementEcheance.Etat etat = a != null ? AbonnementEcheance.calculer(a, config, LocalDate.now()) : null;
        com.diafarms.ml.DTO.AbonnementTarifDTO tarif = tarifService.tarifFerme(f.getId(), a, config);
        com.diafarms.ml.DTO.CreditDTO credit = a != null ? creditService.etat(a, config, tarif.poulesComptees()) : null;
        AdminConsoleDTO.Ferme dto = versDto(f, a, etat, agregats(f.getId()), tarif, credit,
                creditService.doublonsPossibles().contains(f.getId()));

        // Utilisateurs de la ferme, rôles groupés en une requête.
        Map<Long, AdminConsoleDTO.Utilisateur> users = new LinkedHashMap<>();
        Map<Long, List<String>> roles = new HashMap<>();
        jdbc.query("SELECT u.id, r.role FROM utilisateurs u JOIN roles_users ru ON ru.id_utilisateurs = u.id "
                + "JOIN roles r ON r.id = ru.id_roles WHERE u.farm_id = ?", rs -> {
                    roles.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                }, f.getId());
        jdbc.query("SELECT id, unique_id, full_name, telephone, email, statut, removed, archive, last_login, created_at "
                + "FROM utilisateurs WHERE farm_id = ? ORDER BY id", rs -> {
                    long id = rs.getLong(1);
                    boolean actif = !rs.getBoolean(7) && !rs.getBoolean(8) && (rs.getObject(6) == null || rs.getBoolean(6));
                    users.put(id, new AdminConsoleDTO.Utilisateur(rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), roles.getOrDefault(id, List.of()), actif, ldt(rs.getTimestamp(9)),
                            ldt(rs.getTimestamp(10))));
                }, f.getId());

        com.diafarms.ml.commons.AbonnementCredit.Regles rc = com.diafarms.ml.commons.AbonnementCredit.regles(config);
        List<PaiementAbonnementDTO> paiements = paiementRepo.findByAbonnement_Farm_IdOrderByDateDeclarationDesc(f.getId())
                .stream().map(p -> {
                    PaiementAbonnementDTO d = PaiementAbonnementDTO.fromEntity(p);
                    if (p.getStatut() == StatutPaiementAbonnement.EN_ATTENTE) {
                        d.setBonusPrevu(com.diafarms.ml.commons.AbonnementCredit.bonus(p.getMontant(), rc));
                    }
                    return d;
                }).toList();

        List<AdminConsoleDTO.RappelEnvoye> rappels = new ArrayList<>();
        if (a != null) {
            jdbc.query("SELECT type, date_fin, envoye_le, destinataires, emails_envoyes FROM abonnement_rappels "
                    + "WHERE abonnement_id = ? ORDER BY envoye_le DESC", rs -> {
                        rappels.add(new AdminConsoleDTO.RappelEnvoye(rs.getString(1), rs.getDate(2).toLocalDate(),
                                ldt(rs.getTimestamp(3)), (Integer) rs.getObject(4), (Integer) rs.getObject(5)));
                    }, a.getId());
        }

        List<AdminConsoleDTO.Note> notes = noteRepo.findByFarm_IdOrderByCreeLeDesc(f.getId()).stream()
                .map(n -> new AdminConsoleDTO.Note(n.getUniqueId(), n.getContenu(), n.getAuteurNom(), n.getCreeLe()))
                .toList();

        List<AdminConsoleDTO.JournalEntree> journal = journal(null, null, null, f.getUniqueId(), 0, 50).data();

        List<GuideDemarrageService.EtapeDTO> etapes = GuideDemarrageService.liste(
                guideService.etapes(f.getId()).getOrDefault(f.getId(), GuideDemarrageService.Etapes.vide()));
        return new AdminConsoleDTO.FermeDetail(dto, new ArrayList<>(users.values()), paiements, rappels, notes, journal, tarif,
                etapes, parrainageService.pourLaConsole(f), credit,
                a != null ? creditService.mouvements(a.getId(), true) : List.of());
    }

    // ------------------------------------------------------------------ actions

    // Abonnement de la ferme VERROUILLÉ (SELECT ... FOR UPDATE, voir
    // AbonnementRepo.verrouillerParFerme), lu avant toute autre lecture dans la transaction :
    // deux actions simultanées (console, validation d'un paiement) ne peuvent ni perdre une
    // prolongation ni annuler une suspension. Créé d'abord s'il n'existe pas encore.
    private Abonnement abonnementVerrouille(Farm f) {
        // L'abonnement va changer : les téléphones de la ferme voient le nouvel état sans attendre.
        accesMobile.invaliderApresCommit(f.getId());
        return abonnementRepo.verrouillerParFerme(f.getId()).orElseGet(() -> {
            abonnementService.abonnementDeLaFerme(f);
            return abonnementRepo.verrouillerParFerme(f.getId())
                    .orElseThrow(() -> new IllegalStateException("Abonnement introuvable."));
        });
    }

    // Journal de la console (lu aussi par le SUPER_ADMIN seulement, farm_id vide). Le texte
    // ne contient JAMAIS d'information confidentielle (contenu d'une note, motif de
    // suspension, montant ou moyen d'un paiement) : ces données restent dans leurs tables.
    private void journaliser(Utilisateurs sa, Farm f, String action) {
        logs.addLogs(sa.getId(), f.getId(), ENTITE_ADMIN_FERME, action.length() > 490 ? action.substring(0, 490) : action);
    }

    private String nom(Farm f) {
        if (f.getNom() != null && !f.getNom().isBlank()) return f.getNom();
        List<String> n = jdbc.queryForList("SELECT farm_name FROM utilisateurs WHERE farm_id = ? AND farm_name IS NOT NULL ORDER BY id LIMIT 1",
                String.class, f.getId());
        return n.isEmpty() ? f.getUniqueId() : n.get(0);
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdminConsoleService.class);
    private static final String WHATSAPP = "Une question ? Écrivez-nous sur WhatsApp au +223 83 91 86 99.";

    // E-mail aux ADMIN et RESPONSABLE actifs de la ferme (mêmes destinataires que les
    // rappels, voir UtilisateursRepo.findAdminsActifsByFarmId), signé Cocorico. Envoyé
    // APRÈS le commit (jamais pour une action annulée, et sans garder le verrou pendant
    // l'envoi). Un échec d'envoi ne fait jamais échouer l'action.
    private void emailFerme(Farm f, String sujet, String message) {
        try {
            List<String[]> dest = utilisateursRepo.findAdminsActifsByFarmId(f.getId()).stream()
                    .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                    .map(u -> new String[] { u.getEmail(), u.getFullName() })
                    .toList();
            if (dest.isEmpty()) return;
            Runnable envoi = () -> {
                for (String[] d : dest) {
                    try {
                        emailService.sendRappelAbonnement(d[0], d[1], sujet, message);
                    } catch (Exception e) {
                        LOG.warn("E-mail de la console à {} non envoyé : {}", d[0], e.getMessage());
                    }
                }
            };
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                envoi.run();
                            }
                        });
            } else {
                envoi.run();
            }
        } catch (Exception e) {
            LOG.warn("E-mails de la console non préparés pour la ferme {} : {}", f.getId(), e.getMessage());
        }
    }

    // Durée en jours, même convention que la validation d'un paiement
    // (AbonnementServiceImpl.valider) : 1 mois = 30 jours, 12 mois = 365 jours.
    static int joursPourMois(int mois) {
        return (mois / 12) * 365 + (mois % 12) * 30;
    }

    // « Recharger » (crédit prépayé) : argent reçu en dehors de l'application, enregistré
    // directement comme une recharge VALIDÉE (bonus compris), comme une déclaration
    // validée. Lève une suspension (comme l'ancien bouton d'activation). Le montant est
    // obligatoire : offrir du crédit sans paiement se fait par un ajustement.
    @Transactional
    public AdminConsoleDTO.FermeDetail activer(String farmUniqueId, AdminActiverAbonnementRequest req) {
        Utilisateurs sa = superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        // Toutes les vérifications AVANT la moindre modification.
        if (req == null || req.getMontant() == null) {
            throw new IllegalArgumentException("Indiquez le montant reçu (FCFA) : il est ajouté au crédit de la ferme. "
                    + "Pour offrir du crédit sans paiement, utilisez un ajustement.");
        }
        if (req.getMontant().isNaN() || req.getMontant() <= 0 || req.getMontant() > AbonnementServiceImpl.RECHARGE_MAX) {
            throw new IllegalArgumentException("Le montant reçu doit être supérieur à 0 et au plus 10 000 000 FCFA.");
        }
        if (req.getMoyenPaiement() == null || req.getMoyenPaiement().isBlank()) {
            throw new IllegalArgumentException("Indiquez le moyen de paiement.");
        }
        String moyen = req.getMoyenPaiement().trim();
        if (moyen.length() > 50) moyen = moyen.substring(0, 50);
        double montant = Math.round(req.getMontant());
        String cleRequete = cleRequete(req.getRequestId());

        Abonnement a = abonnementVerrouille(f);
        // Même envoi rejoué (double clic, réseau) : rien de plus, verrou déjà pris.
        if (creditService.cleExiste(cleRequete)) return detailFerme(farmUniqueId);
        boolean etaitSuspendu = a.estSuspendu();
        a.setSuspendu(null);
        a.setMotifSuspension(null);
        a.setSuspenduLe(null);
        if (a.getInitialisation() != null) Initialisation.updateDate(a.getInitialisation());
        abonnementRepo.save(a);
        if (etaitSuspendu) creditService.fermerSuspension(a.getId(), LocalDate.now());

        PaiementAbonnement p = new PaiementAbonnement();
        p.setUniqueId(UUID.randomUUID().toString());
        p.setAbonnement(a);
        p.setMontant(montant);
        p.setPeriodicite(Periodicite.MENSUEL);
        p.setRecharge(true);
        p.setMoyenPaiement(moyen);
        String ref = req.getReference() == null || req.getReference().isBlank() ? null : req.getReference().trim();
        p.setReference(ref != null && ref.length() > 100 ? ref.substring(0, 100) : ref);
        p.setStatut(StatutPaiementAbonnement.VALIDE);
        LocalDateTime maintenant = LocalDateTime.now();
        p.setDateDeclaration(maintenant);
        p.setDateValidation(maintenant);
        p.setValidePar(sa);
        p.setHorsApplication(true);
        p.setInitialisation(Initialisation.init());
        paiementRepo.save(p);
        CreditService.Recharge r = creditService.appliquerRecharge(a, p, sa, cleRequete);
        paiementRepo.save(p);
        // Parrainage : une recharge reçue compte comme une validation (après le commit).
        parrainageService.apresPaiementValide(f.getId(), p.getId(), sa.getId());

        journaliser(sa, f, "Recharge du crédit de la ferme " + nom(f) + " enregistrée (reçue hors application)"
                + (etaitSuspendu ? ", suspension levée" : ""));
        String nomFerme = nom(f);
        emailFerme(f, "Votre recharge de " + AbonnementEcheance.fcfa(r.montant()) + " est validée",
                "La recharge de la ferme " + nomFerme + " est validée : " + AbonnementEcheance.fcfa(r.montant())
                        + " ajoutés à votre crédit."
                        + (r.bonus() > 0 ? "\n\nBonus offert : " + AbonnementEcheance.fcfa(r.bonus()) + " de crédit en plus." : "")
                        + "\n\n" + CreditService.phraseSolde(r.solde(), r.finEstimee(), r.phraseMois())
                        + (etaitSuspendu ? "\n\nL'accès de votre ferme est rétabli." : "")
                        + "\n\nMerci pour votre confiance. " + WHATSAPP);
        return detailFerme(farmUniqueId);
    }

    // « REQ:<id> » pour un identifiant d'envoi valable, null sans identifiant.
    private static String cleRequete(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        String id = requestId.trim();
        if (!id.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("Identifiant d'envoi invalide.");
        return "REQ:" + id;
    }

    // Ajustement manuel du crédit (en plus ou en moins), avec la raison (visible par la
    // ferme dans son historique). Journalisé sans le montant ni la raison.
    @Transactional
    public AdminConsoleDTO.FermeDetail ajuster(String farmUniqueId, com.diafarms.ml.request.others.AdminAjustementRequest req) {
        Utilisateurs sa = superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        Double montant = req == null ? null : req.getMontant();
        String motif = req == null || req.getMotif() == null ? "" : req.getMotif().trim();
        if (montant == null || montant.isNaN() || Math.round(montant) == 0 || Math.abs(montant) > AbonnementServiceImpl.RECHARGE_MAX) {
            throw new IllegalArgumentException("Indiquez le montant de l'ajustement en FCFA (positif pour ajouter, négatif pour retirer).");
        }
        if (motif.isEmpty()) throw new IllegalArgumentException("Indiquez la raison de l'ajustement (elle est montrée à la ferme).");
        if (motif.length() > 300) throw new IllegalArgumentException("Raison trop longue (300 caractères au plus).");
        String cleRequete = cleRequete(req.getRequestId());
        Abonnement a = abonnementVerrouille(f);
        if (creditService.cleExiste(cleRequete)) return detailFerme(farmUniqueId); // envoi rejoué
        creditService.ajuster(a, (double) Math.round(montant), motif, sa, cleRequete);
        journaliser(sa, f, "Ajustement du crédit de la ferme " + nom(f) + (montant > 0 ? " (ajout)" : " (retrait)"));
        return detailFerme(farmUniqueId);
    }

    @Transactional
    public AdminConsoleDTO.FermeDetail suspendre(String farmUniqueId, AdminSuspendreRequest req) {
        Utilisateurs sa = superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        String motif = req == null || req.getMotif() == null ? "" : req.getMotif().trim();
        if (motif.isEmpty()) throw new IllegalArgumentException("Indiquez le motif de la suspension (il sera montré à la ferme).");
        if (motif.length() > 500) throw new IllegalArgumentException("Motif trop long (500 caractères au plus).");
        Abonnement a = abonnementVerrouille(f);
        if (a.estSuspendu()) throw new IllegalArgumentException("Cette ferme est déjà suspendue.");
        a.setSuspendu(true);
        a.setMotifSuspension(motif);
        a.setSuspenduLe(LocalDateTime.now());
        abonnementRepo.save(a);
        creditService.ouvrirSuspension(a.getId(), LocalDate.now()); // jours suspendus jamais facturés
        journaliser(sa, f, "Suspension de la ferme " + nom(f)); // motif : dans abonnements seulement
        // Le motif est déjà montré à la ferme sur son écran de blocage : il figure aussi ici.
        emailFerme(f, "L'accès de votre ferme à Cocorico est suspendu",
                "L'accès de la ferme " + nom(f) + " à Cocorico est suspendu.\n\nMotif : " + motif
                        + "\n\nContactez-nous sur WhatsApp au +223 83 91 86 99.");
        return detailFerme(farmUniqueId);
    }

    @Transactional
    public AdminConsoleDTO.FermeDetail reactiver(String farmUniqueId) {
        Utilisateurs sa = superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        Abonnement a = abonnementVerrouille(f);
        if (!a.estSuspendu()) throw new IllegalArgumentException("Cette ferme n'est pas suspendue.");
        a.setSuspendu(null);
        a.setMotifSuspension(null);
        a.setSuspenduLe(null);
        abonnementRepo.save(a);
        creditService.fermerSuspension(a.getId(), LocalDate.now());
        journaliser(sa, f, "Réactivation de la ferme " + nom(f) + " (fin de la suspension)");
        emailFerme(f, "L'accès de votre ferme à Cocorico est rétabli",
                "L'accès de la ferme " + nom(f) + " à Cocorico est rétabli. Vous pouvez de nouveau utiliser "
                        + "l'application web et l'application mobile.\n\n" + WHATSAPP);
        return detailFerme(farmUniqueId);
    }

    @Transactional
    public AdminConsoleDTO.FermeDetail prolongerEssai(String farmUniqueId, AdminProlongerEssaiRequest req) {
        Utilisateurs sa = superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        int jours = req == null || req.getJours() == null ? 0 : req.getJours();
        if (jours < 1 || jours > 365) throw new IllegalArgumentException("Nombre de jours invalide (1 à 365).");
        Abonnement a = abonnementVerrouille(f);
        if (a.getPeriodicite() != null) {
            throw new IllegalArgumentException("Cette ferme a déjà un abonnement payé : utilisez « Recharger ».");
        }
        LocalDate auj = LocalDate.now();
        LocalDate base = a.getDateFin().isBefore(auj) ? auj : a.getDateFin();
        a.setDateFin(base.plusDays(jours));
        // Crédit prépayé : le crédit commence au lendemain du nouvel essai. Une ferme
        // inscrite sans essai (essai déjà utilisé) reçoit ici un essai décidé par l'équipe.
        a.setEssaiRefuse(null);
        a.setCreditDepuis(a.getDateFin().plusDays(1));
        if (creditService.solde(a.getId()) <= 0) a.setCreditEpuiseLe(a.getCreditDepuis());
        abonnementRepo.save(a);
        creditService.recalculerApresChangement(a);
        journaliser(sa, f, "Prolongation de l'essai de la ferme " + nom(f) + " : " + jours + " jour(s), jusqu'au "
                + AbonnementEcheance.date(a.getDateFin()));
        emailFerme(f, "Votre période d'essai Cocorico est prolongée",
                "La période d'essai de la ferme " + nom(f) + " est prolongée jusqu'au "
                        + AbonnementEcheance.date(a.getDateFin()) + ".\n\n" + WHATSAPP);
        return detailFerme(farmUniqueId);
    }

    @Transactional
    public AdminConsoleDTO.FermeDetail ajouterNote(String farmUniqueId, AdminNoteRequest req) {
        Utilisateurs sa = superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        String contenu = req == null || req.getContenu() == null ? "" : req.getContenu().trim();
        if (contenu.isEmpty()) throw new IllegalArgumentException("La note est vide.");
        if (contenu.length() > 2000) throw new IllegalArgumentException("Note trop longue (2000 caractères au plus).");
        NoteAdminFerme n = new NoteAdminFerme();
        n.setUniqueId(UUID.randomUUID().toString());
        n.setFarm(f);
        n.setContenu(contenu);
        n.setAuteur(sa);
        n.setAuteurNom(sa.getFullName());
        n.setCreeLe(LocalDateTime.now());
        noteRepo.save(n);
        journaliser(sa, f, "Note interne ajoutée sur la ferme " + nom(f)); // contenu : dans notes_admin_ferme seulement
        return detailFerme(farmUniqueId);
    }

    // Tarif spécial (prix fixe par mois) d'une ferme, ou son retrait (prixMensuelFixe null).
    // Remplace le prix par poule à partir du prochain renouvellement : la période déjà
    // payée ne change pas. Journalisé sans le montant ni le motif (voir journaliser).
    @Transactional
    public AdminConsoleDTO.FermeDetail prixFixe(String farmUniqueId, com.diafarms.ml.request.others.AdminPrixFixeRequest req) {
        Utilisateurs sa = superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        Double prix = req == null ? null : req.getPrixMensuelFixe();
        String motif = req == null || req.getMotif() == null ? "" : req.getMotif().trim();
        if (prix != null) {
            if (prix.isNaN() || prix <= 0 || prix > 10_000_000) {
                throw new IllegalArgumentException("Le prix fixe doit être supérieur à 0 et au plus 10 000 000 FCFA par mois.");
            }
            if (motif.isEmpty()) throw new IllegalArgumentException("Indiquez la raison du tarif spécial.");
            if (motif.length() > 300) throw new IllegalArgumentException("Raison trop longue (300 caractères au plus).");
        }
        Abonnement a = abonnementVerrouille(f);
        if (prix == null && a.getPrixMensuelFixe() == null) {
            throw new IllegalArgumentException("Cette ferme n'a pas de tarif spécial.");
        }
        a.setPrixMensuelFixe(prix == null ? null : (double) Math.round(prix));
        a.setMotifPrixFixe(prix == null ? null : motif);
        a.setPrixFixeLe(prix == null ? null : LocalDateTime.now());
        abonnementRepo.save(a);
        creditService.recalculerApresChangement(a); // estimation de la fin au nouveau prix
        journaliser(sa, f, prix == null ? "Tarif spécial retiré pour la ferme " + nom(f) + " (retour au prix par poule)"
                : "Tarif spécial fixé pour la ferme " + nom(f));
        return detailFerme(farmUniqueId);
    }

    // Exclure une ferme des statistiques (ou l'y réintégrer). Journalisé, sans e-mail.
    @Transactional
    public AdminConsoleDTO.FermeDetail statistiques(String farmUniqueId, com.diafarms.ml.request.others.AdminStatistiquesRequest req) {
        Utilisateurs sa = superAdmin();
        Farm f = fermeOu400(farmUniqueId);
        if (req == null || req.getExclure() == null) {
            throw new IllegalArgumentException("Indiquez si la ferme doit être exclue des statistiques (exclure : true ou false).");
        }
        boolean exclure = req.getExclure();
        if (exclure == exclue(f)) {
            throw new IllegalArgumentException(exclure ? "Cette ferme est déjà hors statistiques." : "Cette ferme est déjà comptée dans les statistiques.");
        }
        f.setExclureStatistiques(exclure ? Boolean.TRUE : null);
        farmsRepo.save(f);
        journaliser(sa, f, exclure ? "Ferme " + nom(f) + " exclue des statistiques"
                : "Ferme " + nom(f) + " réintégrée dans les statistiques");
        return detailFerme(farmUniqueId);
    }

    // ------------------------------------------------------------------ finances

    @Transactional(readOnly = true)
    public AdminConsoleDTO.Finances finances(Integer anneeDemandee) {
        superAdmin();
        LocalDate auj = LocalDate.now();
        int annee = anneeDemandee == null ? auj.getYear() : anneeDemandee;
        if (annee < 2000 || annee > 2100) throw new IllegalArgumentException("Année invalide : " + annee);
        LocalDate d1 = LocalDate.of(annee, 1, 1);
        LocalDate d2 = d1.plusYears(1);

        Map<String, double[]> rev = revenusParMois(d1, d2);
        List<AdminConsoleDTO.MoisValeur> parMois = new ArrayList<>();
        double totalAnnee = 0;
        for (int m = 1; m <= 12; m++) {
            String cle = YearMonth.of(annee, m).toString();
            double[] v = rev.getOrDefault(cle, new double[] { 0, 0 });
            parMois.add(new AdminConsoleDTO.MoisValeur(cle, v[0], (long) v[1]));
            totalAnnee += v[0];
        }

        List<AdminConsoleDTO.CleValeur> parAnnee = jdbc.query(
                "SELECT to_char(date_validation, 'YYYY'), SUM(montant), COUNT(*) FROM " + PAIEMENTS_STATS + " "
                        + "WHERE statut = 'VALIDE' AND date_validation IS NOT NULL GROUP BY 1 ORDER BY 1",
                (rs, i) -> new AdminConsoleDTO.CleValeur(rs.getString(1), rs.getDouble(2), rs.getLong(3)));
        double totalDepuisDebut = parAnnee.stream().mapToDouble(AdminConsoleDTO.CleValeur::montant).sum();

        List<AdminConsoleDTO.CleValeur> parPeriodicite = jdbc.query(
                "SELECT CASE WHEN COALESCE(recharge, false) THEN 'RECHARGE' ELSE periodicite END, SUM(montant), COUNT(*) FROM "
                        + PAIEMENTS_STATS + " WHERE statut = 'VALIDE' "
                        + "AND date_validation >= ? AND date_validation < ? GROUP BY 1 ORDER BY 2 DESC",
                (rs, i) -> new AdminConsoleDTO.CleValeur(rs.getString(1), rs.getDouble(2), rs.getLong(3)),
                d1.atStartOfDay(), d2.atStartOfDay());
        // Moyen de paiement en texte libre : regroupé sans tenir compte des majuscules ni des espaces.
        List<AdminConsoleDTO.CleValeur> parMoyen = jdbc.query(
                "SELECT MIN(TRIM(moyen_paiement)), SUM(montant), COUNT(*) FROM " + PAIEMENTS_STATS + " WHERE statut = 'VALIDE' "
                        + "AND date_validation >= ? AND date_validation < ? GROUP BY LOWER(TRIM(moyen_paiement)) ORDER BY 2 DESC",
                (rs, i) -> new AdminConsoleDTO.CleValeur(rs.getString(1), rs.getDouble(2), rs.getLong(3)),
                d1.atStartOfDay(), d2.atStartOfDay());

        // Conversion et pertes, sur l'état du jour de toutes les fermes.
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        Map<Long, Long> paiementsValides = new HashMap<>();
        jdbc.query("SELECT abonnement_id, COUNT(*) FROM paiements_abonnement WHERE statut = 'VALIDE' GROUP BY abonnement_id",
                rs -> {
                    paiementsValides.put(rs.getLong(1), rs.getLong(2));
                });
        long essaisTermines = 0, convertis = 0, ayantPaye = 0, perdues = 0;
        List<AdminConsoleDTO.FermeCourte> listePerdues = new ArrayList<>();
        Map<Long, String> noms = nomsInscription();
        for (Abonnement a : abonnementRepo.findAllAvecFerme()) {
            if (exclue(a.getFarm())) continue; // hors statistiques
            AbonnementEcheance.Etat etat = AbonnementEcheance.calculer(a, config, auj);
            boolean aPaye = paiementsValides.containsKey(a.getId()) || a.getPeriodicite() != null;
            if (aPaye) {
                convertis++;
                essaisTermines++;
                ayantPaye++;
                if (etat.expire() && !etat.suspendu()) {
                    perdues++;
                    listePerdues.add(new AdminConsoleDTO.FermeCourte(a.getFarm().getUniqueId(),
                            nomFerme(a.getFarm(), noms.get(a.getFarm().getId())), a.getDateFin(), "EXPIRE"));
                }
            } else if (etat.joursRestants() < 0) {
                essaisTermines++; // essai terminé sans paiement
            }
        }
        listePerdues.sort(Comparator.comparing(AdminConsoleDTO.FermeCourte::dateFin).reversed());
        double taux = essaisTermines == 0 ? 0 : Math.round(convertis * 1000.0 / essaisTermines) / 10.0;
        double tauxPerte = ayantPaye == 0 ? 0 : Math.round(perdues * 1000.0 / ayantPaye) / 10.0;
        return new AdminConsoleDTO.Finances(annee, parMois, parAnnee, parPeriodicite, parMoyen, totalAnnee,
                totalDepuisDebut, essaisTermines, convertis, taux, ayantPaye, perdues, tauxPerte, listePerdues);
    }

    private Map<Long, String> nomsInscription() {
        Map<Long, String> m = new HashMap<>();
        jdbc.query("SELECT DISTINCT ON (farm_id) farm_id, farm_name FROM utilisateurs "
                + "WHERE farm_id IS NOT NULL AND farm_name IS NOT NULL ORDER BY farm_id, id", rs -> {
                    m.put(rs.getLong(1), rs.getString(2));
                });
        return m;
    }

    // Liste des paiements (tous statuts) avec filtres facultatifs : dates (de déclaration),
    // ferme, statut. Plus récents d'abord, 2000 lignes au plus.
    @Transactional(readOnly = true)
    public List<PaiementAbonnementDTO> paiements(String du, String au, String farmUniqueId, String statut) {
        superAdmin();
        StringBuilder sql = new StringBuilder("SELECT p.id FROM paiements_abonnement p JOIN abonnements a ON a.id = p.abonnement_id "
                + "JOIN farms f ON f.id = a.farm_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        LocalDate dDu = parseDate(du, "la date de début");
        LocalDate dAu = parseDate(au, "la date de fin");
        if (dDu != null) {
            sql.append(" AND p.date_declaration >= ?");
            args.add(dDu.atStartOfDay());
        }
        if (dAu != null) {
            sql.append(" AND p.date_declaration < ?");
            args.add(dAu.plusDays(1).atStartOfDay());
        }
        if (farmUniqueId != null && !farmUniqueId.isBlank()) {
            sql.append(" AND f.unique_id = ?");
            args.add(farmUniqueId.trim());
        }
        if (statut != null && !statut.isBlank()) {
            try {
                args.add(StatutPaiementAbonnement.valueOf(statut.trim().toUpperCase()).name());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Statut invalide : " + statut);
            }
            sql.append(" AND p.statut = ?");
        }
        sql.append(" ORDER BY p.date_declaration DESC, p.id DESC LIMIT 2000");
        List<Long> ids = jdbc.queryForList(sql.toString(), Long.class, args.toArray());
        if (ids.isEmpty()) return List.of();
        Map<Long, PaiementAbonnement> parId = new HashMap<>();
        for (PaiementAbonnement p : paiementRepo.findAllAvecFermeParIds(ids)) parId.put(p.getId(), p);
        Map<Long, String> noms = nomsInscription();
        List<PaiementAbonnementDTO> res = new ArrayList<>();
        for (Long id : ids) {
            PaiementAbonnement p = parId.get(id);
            if (p == null) continue;
            PaiementAbonnementDTO dto = PaiementAbonnementDTO.fromEntity(p);
            Farm f = p.getAbonnement().getFarm();
            dto.setFarmNom(nomFerme(f, noms.get(f.getId())));
            res.add(dto);
        }
        return res;
    }

    // ------------------------------------------------------------------ journal

    // Catégorie d'une action, d'après le type d'entité et le début du texte (les textes
    // sont écrits par AbonnementServiceImpl, AbonnementRappelService et ce service).
    private static final String CATEGORIE_SQL = "CASE "
            + "WHEN l.entity_type = 'AbonnementConfig' THEN 'CONFIG' "
            + "WHEN l.entity_type = 'AbonnementRappel' THEN 'RAPPELS' "
            + "WHEN l.action LIKE 'Validation du paiement%' THEN 'VALIDATION' "
            + "WHEN l.action LIKE 'Rejet du paiement%' THEN 'REJET' "
            + "WHEN l.action LIKE 'Suspension%' THEN 'SUSPENSION' "
            + "WHEN l.action LIKE 'Réactivation%' THEN 'REACTIVATION' "
            + "WHEN l.action LIKE 'Activation%' THEN 'ACTIVATION' "
            + "WHEN l.action LIKE 'Recharge%' THEN 'RECHARGE' "
            + "WHEN l.action LIKE 'Ajustement%' THEN 'AJUSTEMENT' "
            + "WHEN l.action LIKE 'Prolongation de l''essai%' THEN 'ESSAI' "
            + "WHEN l.action LIKE 'Note interne%' THEN 'NOTE' "
            + "WHEN l.action LIKE 'Ferme % statistiques' THEN 'STATISTIQUES' "
            + "WHEN l.action LIKE 'Tarif spécial%' THEN 'TARIF' "
            + "WHEN l.action LIKE 'Parrainage%' THEN 'PARRAINAGE' "
            + "ELSE 'AUTRE' END";

    @Transactional(readOnly = true)
    public AdminConsoleDTO.JournalPage journalPage(String du, String au, String categorie, String farmUniqueId, int page, int size) {
        superAdmin();
        return journal(du, au, categorie, farmUniqueId, page, size);
    }

    private AdminConsoleDTO.JournalPage journal(String du, String au, String categorie, String farmUniqueId, int page, int size) {
        int taille = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        // Actions faites par un compte SUPER_ADMIN ; ferme retrouvée selon le type d'entité.
        String base = "SELECT l.unique_id, l.created_at, l.action, u.full_name, f.unique_id AS farm_uid, "
                + "COALESCE(f.nom, (SELECT u2.farm_name FROM utilisateurs u2 WHERE u2.farm_id = f.id AND u2.farm_name IS NOT NULL "
                + "ORDER BY u2.id LIMIT 1), f.unique_id) AS farm_nom, "
                + "f.id AS farm_id, " + CATEGORIE_SQL + " AS categorie "
                + "FROM logs l JOIN utilisateurs u ON u.id = l.user_id "
                + "LEFT JOIN paiements_abonnement pa ON l.entity_type = 'PaiementAbonnement' AND pa.id = l.entity_id "
                + "LEFT JOIN abonnements ab ON ab.id = pa.abonnement_id "
                + "LEFT JOIN farms f ON f.id = CASE WHEN l.entity_type = '" + ENTITE_ADMIN_FERME + "' THEN l.entity_id "
                + "                                 WHEN l.entity_type = 'PaiementAbonnement' THEN ab.farm_id END "
                + "WHERE EXISTS (SELECT 1 FROM roles_users ru JOIN roles r ON r.id = ru.id_roles "
                + "              WHERE ru.id_utilisateurs = l.user_id AND r.role = 'SUPER_ADMIN') "
                + "AND COALESCE(l.removed, false) = false";
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        LocalDate dDu = parseDate(du, "la date de début");
        LocalDate dAu = parseDate(au, "la date de fin");
        if (dDu != null) {
            where.append(" AND x.created_at >= ?");
            args.add(dDu.atStartOfDay());
        }
        if (dAu != null) {
            where.append(" AND x.created_at < ?");
            args.add(dAu.plusDays(1).atStartOfDay());
        }
        if (categorie != null && !categorie.isBlank()) {
            where.append(" AND x.categorie = ?");
            args.add(categorie.trim().toUpperCase());
        }
        if (farmUniqueId != null && !farmUniqueId.isBlank()) {
            where.append(" AND x.farm_uid = ?");
            args.add(farmUniqueId.trim());
        }
        String from = " FROM (" + base + ") x WHERE 1 = 1" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        List<Object> argsPage = new ArrayList<>(args);
        argsPage.add(taille);
        argsPage.add((long) pg * taille);
        List<AdminConsoleDTO.JournalEntree> data = jdbc.query(
                "SELECT x.*" + from + " ORDER BY x.created_at DESC NULLS LAST LIMIT ? OFFSET ?",
                (rs, i) -> new AdminConsoleDTO.JournalEntree(rs.getString("unique_id"), ldt(rs.getTimestamp("created_at")),
                        rs.getString("categorie"), rs.getString("action"), rs.getString("full_name"),
                        rs.getString("farm_uid"), rs.getString("farm_nom")),
                argsPage.toArray());
        long t = total == null ? 0 : total;
        return new AdminConsoleDTO.JournalPage(data, pg + 1, (int) ((t + taille - 1) / taille), t, taille);
    }
}
