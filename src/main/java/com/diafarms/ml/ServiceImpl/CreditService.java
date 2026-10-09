package com.diafarms.ml.ServiceImpl;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.diafarms.ml.DTO.CreditDTO;
import com.diafarms.ml.DTO.MouvementCreditDTO;
import com.diafarms.ml.commons.AbonnementCredit;
import com.diafarms.ml.commons.AbonnementEcheance;
import com.diafarms.ml.commons.AbonnementTarif;
import com.diafarms.ml.commons.Telephone;
import com.diafarms.ml.enums.Periodicite;
import com.diafarms.ml.enums.StatutAbonnement;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.models.MouvementCredit;
import com.diafarms.ml.models.PaiementAbonnement;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.MouvementCreditRepo;
import com.diafarms.ml.services.EmailService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

// Crédit prépayé des fermes (décision du 2026-10-09). Règles : commons/AbonnementCredit.
//
// Le compte : une ligne MouvementCredit par mouvement (recharge, bonus, mensualité,
// parrainage, ajustement) ; le solde est leur somme. Toute écriture se fait sur
// l'abonnement VERROUILLÉ (SELECT ... FOR NO KEY UPDATE, voir verrouiller) : deux
// écritures sur la même ferme passent l'une après l'autre et relisent le solde à jour ;
// une mensualité, une recharge, un parrainage ne sont écrits qu'une fois (clé unique
// MouvementCredit.cle, vérifiée sous le verrou).
//
// L'échéance : après chaque mouvement, et chaque jour, Abonnement.dateFin est recalculée
// (crédit positif : dernier jour couvert estimé ; crédit à zéro ou moins : veille du
// premier jour non couvert). Tout le reste de l'application (rappels J-7 / J-1, grâce,
// blocage du web, option A du mobile, console) lit dateFin comme avant, sans changement.
//
// Tâche quotidienne (00 h 30 UTC, voir tacheQuotidienne) : passe au crédit les fermes
// d'avant le crédit, prélève les mensualités des mois terminés (le 1er du mois, ou en
// rattrapage si le serveur était arrêté), recalcule les échéances et envoie les e-mails
// de mensualité. Aucune dépendance à un utilisateur connecté ; tout est rejouable sans
// double prélèvement. Comptage des poules : AbonnementTarifService (2 requêtes pour toutes
// les fermes d'un mois, jamais une requête par ferme ni par jour).
//
// Argent toujours en FCFA, jamais dans la devise de la ferme.
@Service
@Slf4j
public class CreditService {

    public static final String TYPE_RECHARGE = "RECHARGE";
    public static final String TYPE_BONUS = "BONUS";
    public static final String TYPE_MENSUALITE = "MENSUALITE";
    public static final String TYPE_PARRAINAGE = "PARRAINAGE";
    public static final String TYPE_AJUSTEMENT = "AJUSTEMENT";

    public static final String NUMERO = "+223 83 91 86 99";
    // Rattrapage des mensualités oubliées : 24 mois au plus.
    static final int MOIS_RATTRAPAGE = 24;

    private final JdbcTemplate jdbc;
    private final AbonnementConfigRepo configRepo;
    private final MouvementCreditRepo mouvementRepo;
    private final AbonnementTarifService tarifService;
    private final com.diafarms.ml.commons.AbonnementAccesMobile accesMobile;
    private final DestinatairesAdmin destinataires;
    private final EmailService emailService;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    public CreditService(JdbcTemplate jdbc, AbonnementConfigRepo configRepo, MouvementCreditRepo mouvementRepo,
            AbonnementTarifService tarifService, com.diafarms.ml.commons.AbonnementAccesMobile accesMobile,
            DestinatairesAdmin destinataires, EmailService emailService, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.configRepo = configRepo;
        this.mouvementRepo = mouvementRepo;
        this.tarifService = tarifService;
        this.accesMobile = accesMobile;
        this.destinataires = destinataires;
        this.emailService = emailService;
        this.tx = new TransactionTemplate(tm);
    }

    // ------------------------------------------------------------------ lecture

    public double solde(Long abonnementId) {
        Double s = jdbc.queryForObject("SELECT COALESCE(SUM(montant), 0) FROM mouvements_credit WHERE abonnement_id = ?",
                Double.class, abonnementId);
        return s == null ? 0 : s;
    }

    // Solde de toutes les fermes : abonnementId -> solde (absent = 0).
    public Map<Long, Double> soldes() {
        Map<Long, Double> m = new HashMap<>();
        jdbc.query("SELECT abonnement_id, SUM(montant) FROM mouvements_credit GROUP BY abonnement_id",
                rs -> {
                    m.put(rs.getLong(1), rs.getDouble(2));
                });
        return m;
    }

    public List<MouvementCreditDTO> mouvements(Long abonnementId, boolean avecAuteur) {
        return mouvementRepo.findByAbonnement(abonnementId).stream().map(m -> MouvementCreditDTO.of(m, avecAuteur)).toList();
    }

    // Rythme du mois en cours : moyenne des poules vivantes du 1er du mois (ou du premier
    // jour payé, s'il est plus tard) jusqu'à aujourd'hui.
    public record Rythme(double moyenne, int jours) {}

    // farmId -> rythme ; ferme absente = aucune poule. null si le comptage a échoué.
    public Map<Long, Rythme> rythmes(Collection<Long> farmIds, Map<Long, LocalDate> creditDepuis, LocalDate auj) {
        LocalDate debut = auj.withDayOfMonth(1);
        Map<Long, long[]> jours = tarifService.sujetsParJourSansRisque(farmIds, debut, auj);
        if (jours == null) return null;
        Map<Long, Rythme> res = new HashMap<>();
        Collection<Long> ids = farmIds != null ? farmIds : creditDepuis.keySet();
        for (Long id : ids) {
            LocalDate depuis = creditDepuis.get(id);
            int i0 = depuis != null && depuis.isAfter(debut) && !depuis.isAfter(auj) ? depuis.getDayOfMonth() - 1 : 0;
            long[] t = jours.get(id);
            int n = auj.getDayOfMonth() - i0;
            long somme = 0;
            if (t != null) for (int i = i0; i < t.length; i++) somme += t[i];
            res.put(id, new Rythme(n > 0 ? (double) somme / n : 0, n));
        }
        return res;
    }

    private Rythme rythme(Abonnement a, LocalDate auj) {
        Long farmId = a.getFarm().getId();
        Map<Long, LocalDate> dep = new HashMap<>();
        dep.put(farmId, a.getCreditDepuis());
        Map<Long, Rythme> r = rythmes(List.of(farmId), dep, auj);
        return r == null ? null : r.get(farmId);
    }

    // Coût d'un mois entier au rythme actuel (null si le comptage a échoué et qu'il n'y a
    // pas de tarif spécial).
    private static Double coutMensuel(Abonnement a, Rythme r, AbonnementConfig config) {
        Double fixe = a.getPrixMensuelFixe();
        if (r == null && (fixe == null || fixe <= 0)) return null;
        return AbonnementCredit.coutMensuel(r == null ? 0 : r.moyenne(), AbonnementTarif.regles(config), fixe);
    }

    // État complet pour la page Abonnement et la fiche de la console (une ferme).
    // poulesComptees : le plus haut des 30 derniers jours (drapeau « à chiffrer »).
    public CreditDTO etat(Abonnement a, AbonnementConfig config, int poulesComptees) {
        LocalDate auj = LocalDate.now();
        double solde = solde(a.getId());
        Map<Long, LocalDate> dep = new HashMap<>();
        dep.put(a.getFarm().getId(), a.getCreditDepuis());
        Map<Long, Rythme> m = rythmes(List.of(a.getFarm().getId()), dep, auj);
        Rythme r = m == null ? null : m.get(a.getFarm().getId());
        return etat(a, config, solde, r, auj, poulesComptees,
                suspensions(List.of(a.getId())).getOrDefault(a.getId(), List.of()));
    }

    // Version sans requête (liste de la console) : solde et rythme déjà calculés.
    public CreditDTO etat(Abonnement a, AbonnementConfig config, double solde, Rythme r, LocalDate auj, int poulesComptees) {
        return etat(a, config, solde, r, auj, poulesComptees, List.of());
    }

    public CreditDTO etat(Abonnement a, AbonnementConfig config, double solde, Rythme r, LocalDate auj, int poulesComptees,
            List<LocalDate[]> suspensionsConnues) {
        AbonnementTarif.Regles t = AbonnementTarif.regles(config);
        AbonnementCredit.Regles rc = AbonnementCredit.regles(config);
        Double fixe = a.getPrixMensuelFixe();
        boolean prixFixe = fixe != null && fixe > 0;
        Double cout = coutMensuel(a, r, config);
        double coutAffiche = cout == null ? t.prixMinimumMensuel() : cout;
        // Mensualité prévue à la fin du mois en cours (jours payés de ce mois, au rythme actuel).
        double prevue = 0;
        YearMonth ce = YearMonth.from(auj);
        JoursPayes p = a.getCreditDepuis() == null ? null : joursPayes(a.getCreditDepuis(),
                solde > 0 ? null : a.getCreditEpuiseLe(), ce, suspensionsConnues == null ? List.of() : suspensionsConnues);
        if (p != null) {
            prevue = AbonnementCredit.mensualite(r == null ? 0 : r.moyenne(), p.nb(), ce.lengthOfMonth(), t, fixe).montant();
        }
        boolean avant = a.getCreditDepuis() == null || a.getCreditDepuis().isAfter(auj);
        boolean aRecharger = solde <= 0 && !avant;
        Double mois = AbonnementCredit.moisRestants(solde, coutAffiche);
        boolean bas = solde > 0 && a.getDateFin() != null
                && java.time.temporal.ChronoUnit.DAYS.between(auj, a.getDateFin()) < AbonnementCredit.CREDIT_BAS_JOURS;
        boolean aChiffrer = !prixFixe && poulesComptees > rc.seuilSurDevis();
        return new CreditDTO(a.getCreditDepuis() != null, solde, solde < 0 ? -solde : 0, aRecharger, bas, mois,
                AbonnementCredit.phraseMois(mois), a.getDateFin(), r == null ? 0 : Math.round(r.moyenne() * 10) / 10.0,
                r == null ? 0 : r.jours(), prevue, coutAffiche, cout == null, a.getCreditDepuis(), avant,
                Boolean.TRUE.equals(a.getEssaiRefuse()), prixFixe, rc.bonusSeuil(), rc.bonusPourcent(),
                rc.seuilSurDevis(), aChiffrer,
                // Période déjà payée (ancien modèle), sans recharge depuis : même règle que AbonnementEcheance.
                a.getCreditDepuis() != null && a.getPeriodicite() != null && a.getCreditEpuiseLe() != null
                        && a.getCreditEpuiseLe().equals(a.getCreditDepuis()) && solde <= 0,
                t.prixMinimumMensuel());
    }

    public boolean cleExiste(String cle) {
        return cle != null && mouvementRepo.existsByCle(cle);
    }

    public Abonnement abonnement(Long id) {
        return em.find(Abonnement.class, id);
    }

    // ------------------------------------------------------------------ verrou

    // Abonnement verrouillé (FOR NO KEY UPDATE : bloque les autres écritures sur cette
    // ferme, pas les insertions qui la référencent) et relu à jour. À appeler dans une
    // transaction ; les modifications en attente de l'appelant sont d'abord écrites.
    public Abonnement verrouiller(Long abonnementId) {
        em.flush();
        jdbc.queryForList("SELECT id FROM abonnements WHERE id = ? FOR NO KEY UPDATE", Long.class, abonnementId);
        Abonnement a = em.find(Abonnement.class, abonnementId);
        em.refresh(a);
        return a;
    }

    // ------------------------------------------------------------------ conversion

    // Ferme d'avant le crédit : le crédit commence au lendemain de sa fin actuelle (fin de
    // l'essai ou de la période déjà payée), à zéro. Rien ne change pour elle d'ici là.
    public static void assurerCredit(Abonnement a) {
        if (a.getCreditDepuis() != null) return;
        LocalDate d = a.getDateFin().plusDays(1);
        a.setCreditDepuis(d);
        a.setCreditEpuiseLe(d);
    }

    // Expression SQL : téléphone en chiffres internationaux (même règle que Telephone.international).
    static String telSql(String col) {
        String c = "regexp_replace(regexp_replace(COALESCE(" + col + ", ''), '[^0-9]', '', 'g'), '^00', '')";
        return "(CASE WHEN length(" + c + ") = 8 THEN '223' || " + c + " WHEN length(" + c + ") < 8 THEN NULL ELSE " + c + " END)";
    }

    // Toutes les fermes d'avant le crédit (idempotent : seulement celles sans credit_depuis),
    // et la mémoire des essais déjà donnés (propriétaire de chaque ferme). Au démarrage et
    // chaque jour. Même chose en SQL : docs/sql/2026-10-09-credit-prepaye.sql.
    public int convertirAnciennesFermes() {
        Integer n = tx.execute(s -> {
            int c = jdbc.update("UPDATE abonnements SET credit_depuis = date_fin + 1, credit_epuise_le = date_fin + 1 "
                    + "WHERE credit_depuis IS NULL");
            // Fermes déjà suspendues avant les périodes de suspension : période ouverte.
            jdbc.update("INSERT INTO suspensions_credit (abonnement_id, du) SELECT a.id, CAST(COALESCE(a.suspendu_le, now()) AS date) "
                    + "FROM abonnements a WHERE COALESCE(a.suspendu, false) = true "
                    + "AND NOT EXISTS (SELECT 1 FROM suspensions_credit s WHERE s.abonnement_id = a.id AND s.au IS NULL)");
            jdbc.update("INSERT INTO essais_gratuits (farm_id, telephone, email, essai_donne, cree_le) "
                    + "SELECT DISTINCT ON (u.farm_id) u.farm_id, " + telSql("u.telephone") + ", LOWER(TRIM(u.email)), true, now() "
                    + "FROM utilisateurs u JOIN roles_users ru ON ru.id_utilisateurs = u.id JOIN roles r ON r.id = ru.id_roles "
                    + "WHERE r.role = 'ADMIN' AND u.farm_id IS NOT NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM essais_gratuits e WHERE e.farm_id = u.farm_id) "
                    + "ORDER BY u.farm_id, u.id");
            return c;
        });
        return n == null ? 0 : n;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void auDemarrage() {
        try {
            int n = convertirAnciennesFermes();
            if (n > 0) log.info("Crédit prépayé : {} ferme(s) passée(s) au crédit", n);
        } catch (Exception e) {
            log.error("Crédit prépayé : conversion des fermes en échec : {}", e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ essai gratuit

    // Un essai par propriétaire : true si le téléphone ou l'e-mail a déjà servi à un
    // propriétaire d'une AUTRE ferme : inscription mémorisée (essais_gratuits, garde la trace
    // des comptes et fermes supprimés) ou utilisateur ADMIN d'une autre ferme. Les employés
    // et les coordonnées de ferme ne comptent pas (faux positifs) : voir doublonsPossibles.
    public boolean essaiDejaUtilise(Long farmId, String telephone, String email) {
        String tel = Telephone.international(telephone);
        String mail = email == null || email.isBlank() ? null : email.trim().toLowerCase();
        if (tel == null && mail == null) return false;
        long autre = farmId == null ? -1L : farmId;
        List<Object> args = new ArrayList<>();
        List<String> parties = new ArrayList<>();
        String admin = "EXISTS (SELECT 1 FROM roles_users ru JOIN roles r ON r.id = ru.id_roles "
                + "WHERE ru.id_utilisateurs = u.id AND r.role = 'ADMIN')";
        if (tel != null) {
            parties.add("SELECT 1 FROM essais_gratuits WHERE telephone = ? AND COALESCE(farm_id, -1) <> ?");
            args.add(tel); args.add(autre);
            parties.add("SELECT 1 FROM utilisateurs u WHERE u.farm_id IS NOT NULL AND u.farm_id <> ? AND " + telSql("u.telephone")
                    + " = ? AND " + admin);
            args.add(autre); args.add(tel);
        }
        if (mail != null) {
            parties.add("SELECT 1 FROM essais_gratuits WHERE email = ? AND COALESCE(farm_id, -1) <> ?");
            args.add(mail); args.add(autre);
            parties.add("SELECT 1 FROM utilisateurs u WHERE u.farm_id IS NOT NULL AND u.farm_id <> ? AND LOWER(TRIM(u.email)) = ? AND " + admin);
            args.add(autre); args.add(mail);
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM (" + String.join(" UNION ALL ", parties) + ") x",
                Integer.class, args.toArray());
        return n != null && n > 0;
    }

    // Console : fermes dont le propriétaire (premier ADMIN) a le même téléphone ou e-mail
    // qu'un employé ou que les coordonnées d'une AUTRE ferme. Simple signal (« doublon
    // possible »), jamais un refus d'essai. Une requête pour toutes les fermes.
    public Set<Long> doublonsPossibles() {
        String proprios = "SELECT DISTINCT ON (u.farm_id) u.farm_id, " + telSql("u.telephone") + " AS tel, LOWER(TRIM(u.email)) AS mail "
                + "FROM utilisateurs u JOIN roles_users ru ON ru.id_utilisateurs = u.id JOIN roles r ON r.id = ru.id_roles "
                + "WHERE r.role = 'ADMIN' AND u.farm_id IS NOT NULL ORDER BY u.farm_id, u.id";
        String sql = "SELECT DISTINCT p.farm_id FROM (" + proprios + ") p WHERE "
                + "EXISTS (SELECT 1 FROM utilisateurs v WHERE v.farm_id IS NOT NULL AND v.farm_id <> p.farm_id "
                + "AND ((p.tel IS NOT NULL AND " + telSql("v.telephone") + " = p.tel) OR (p.mail IS NOT NULL AND LOWER(TRIM(v.email)) = p.mail))) "
                + "OR EXISTS (SELECT 1 FROM farms f WHERE f.id <> p.farm_id "
                + "AND ((p.tel IS NOT NULL AND (" + telSql("f.telephone1") + " = p.tel OR " + telSql("f.telephone2") + " = p.tel)) "
                + "OR (p.mail IS NOT NULL AND LOWER(TRIM(f.email)) = p.mail)))";
        return new HashSet<>(jdbc.queryForList(sql, Long.class));
    }

    public void memoriserEssai(Long farmId, String telephone, String email, boolean donne) {
        jdbc.update("INSERT INTO essais_gratuits (farm_id, telephone, email, essai_donne, cree_le) VALUES (?, ?, ?, ?, now())",
                farmId, Telephone.international(telephone),
                email == null || email.isBlank() ? null : email.trim().toLowerCase(), donne);
    }

    // ------------------------------------------------------------------ écriture

    public record Ecriture(MouvementCredit mouvement, double soldeAvant, double soldeApres) {}

    // Écrit un mouvement sur un abonnement verrouillé. null si la clé existe déjà.
    private Ecriture ecrire(Abonnement a, String type, double montant, String cle, Utilisateurs auteur,
            java.util.function.Consumer<MouvementCredit> detail) {
        if (cle != null && mouvementRepo.existsByCle(cle)) return null;
        double avant = solde(a.getId());
        MouvementCredit m = new MouvementCredit();
        m.setUniqueId(UUID.randomUUID().toString());
        m.setAbonnement(a);
        m.setType(type);
        m.setMontant(montant);
        m.setSoldeApres(avant + montant);
        m.setDateMouvement(LocalDateTime.now());
        m.setCle(cle);
        m.setAuteur(auteur);
        if (detail != null) detail.accept(m);
        mouvementRepo.save(m);
        em.flush(); // le solde suivant (requête SQL) doit voir cette ligne
        return new Ecriture(m, avant, avant + montant);
    }

    // Recalcule creditEpuiseLe et dateFin d'un abonnement verrouillé.
    public void recalculer(Abonnement a, double solde, Rythme r, AbonnementConfig config, boolean rythmeConnu) {
        if (a.getCreditDepuis() == null) return;
        LocalDate auj = LocalDate.now();
        Rythme ry = rythmeConnu ? r : (solde > 0 ? rythme(a, auj) : null);
        Double cout = solde > 0 ? coutMensuel(a, ry, config) : null;
        AbonnementCredit.Fin f = AbonnementCredit.fin(solde, auj, a.getCreditDepuis(), a.getCreditEpuiseLe(), cout,
                a.getDateFin());
        boolean change = !Objects.equals(f.dateFin(), a.getDateFin()) || !Objects.equals(f.creditEpuiseLe(), a.getCreditEpuiseLe());
        a.setCreditEpuiseLe(f.creditEpuiseLe());
        a.setDateFin(f.dateFin());
        if (change) accesMobile.invaliderApresCommit(a.getFarm().getId());
    }

    // Crédit ajouté (recharge, bonus, parrainage, ajustement en crédit) : si la ferme était
    // bloquée faute de crédit et que le crédit redevient positif, le crédit repart
    // d'aujourd'hui (jours bloqués jamais payés). Ferme marquée payante.
    private void apresCreditAjoute(Abonnement a, Ecriture e, boolean bloqueAvant) {
        if (e.soldeApres() <= 0) return;
        LocalDate auj = LocalDate.now();
        if (bloqueAvant && a.getCreditDepuis().isBefore(auj)) a.setCreditDepuis(auj);
        a.setCreditEpuiseLe(null);
        a.setPeriodicite(Periodicite.MENSUEL);
        a.setStatut(StatutAbonnement.ACTIF);
    }

    private boolean bloque(Abonnement a, double solde, AbonnementConfig config) {
        return AbonnementCredit.bloqueParCredit(solde, a.getCreditEpuiseLe(), AbonnementEcheance.delaiGraceJours(config),
                LocalDate.now());
    }

    // Résultat d'une recharge, pour l'e-mail et la console.
    public record Recharge(double montant, double bonus, double solde, LocalDate finEstimee, String phraseMois) {}

    // Recharge validée (déclaration « J'ai rechargé » validée, ou argent reçu saisi dans la
    // console). Dans la transaction de l'appelant ; p déjà enregistré (il a un id).
    // Rembourse d'abord ce qui est dû (le solde est une somme : un crédit négatif est
    // simplement compensé), puis ajoute ; bonus si la recharge atteint le seuil.
    public Recharge appliquerRecharge(Abonnement a0, PaiementAbonnement p, Utilisateurs auteur) {
        return appliquerRecharge(a0, p, auteur, null);
    }

    // cleRecharge : clé de la ligne RECHARGE (« REQ:<id de requête> » pour la console : un
    // même envoi rejoué ne crédite jamais deux fois). null = « RECH:<paiement> ».
    public Recharge appliquerRecharge(Abonnement a0, PaiementAbonnement p, Utilisateurs auteur, String cleRecharge) {
        Abonnement a = verrouiller(a0.getId());
        assurerCredit(a);
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        regulariser(a, config);
        double montant = p.getMontant() == null ? 0 : p.getMontant();
        double bonus = AbonnementCredit.bonus(montant, AbonnementCredit.regles(config));
        double avant = solde(a.getId());
        boolean bloqueAvant = bloque(a, avant, config);
        String moyen = p.getMoyenPaiement() == null ? "" : " (" + p.getMoyenPaiement() + ")";
        Ecriture e = ecrire(a, TYPE_RECHARGE, montant, cleRecharge != null ? cleRecharge : "RECH:" + p.getId(), auteur, m -> {
            m.setPaiementId(p.getId());
            m.setLibelle("Recharge" + moyen + (avant < 0 ? " : " + AbonnementEcheance.fcfa(Math.min(-avant, montant))
                    + " pour payer ce qui était dû" : ""));
        });
        if (e != null && bonus > 0) {
            AbonnementCredit.Regles rc = AbonnementCredit.regles(config);
            Ecriture b = ecrire(a, TYPE_BONUS, bonus, "BONUS:" + p.getId(), auteur, m -> {
                m.setPaiementId(p.getId());
                m.setLibelle("Bonus de " + pourcent(rc.bonusPourcent()) + " % offert sur la recharge de "
                        + AbonnementEcheance.fcfa(montant));
            });
            if (b != null) e = new Ecriture(e.mouvement(), e.soldeAvant(), b.soldeApres());
        }
        p.setBonus(e != null ? bonus : p.getBonus());
        double apres = solde(a.getId());
        if (e != null) apresCreditAjoute(a, new Ecriture(e.mouvement(), avant, apres), bloqueAvant);
        Rythme r = rythme(a, LocalDate.now());
        recalculer(a, apres, r, config, true);
        em.merge(a);
        Double cout = coutMensuel(a, r, config);
        Double mois = AbonnementCredit.moisRestants(apres, cout == null ? AbonnementTarif.regles(config).prixMinimumMensuel() : cout);
        return new Recharge(montant, bonus, apres, a.getDateFin(), AbonnementCredit.phraseMois(mois));
    }

    static String pourcent(double p) {
        return p == Math.floor(p) ? String.valueOf((long) p) : String.valueOf(p).replace('.', ',');
    }

    // Ajustement manuel de l'équipe (crédit ou débit), avec la raison. Dans la transaction
    // de l'appelant (console).
    public Ecriture ajuster(Abonnement a0, double montant, String motif, Utilisateurs auteur) {
        return ajuster(a0, montant, motif, auteur, null);
    }

    // cle : « REQ:<id de requête> » (console) ; null si déjà écrit (rejeu du même envoi).
    public Ecriture ajuster(Abonnement a0, double montant, String motif, Utilisateurs auteur, String cle) {
        Abonnement a = verrouiller(a0.getId());
        assurerCredit(a);
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        regulariser(a, config);
        double avant = solde(a.getId());
        boolean bloqueAvant = bloque(a, avant, config);
        Ecriture e = ecrire(a, TYPE_AJUSTEMENT, montant, cle, auteur, m -> m.setLibelle(motif));
        if (e == null) return null;
        if (montant > 0) apresCreditAjoute(a, e, bloqueAvant);
        recalculer(a, e.soldeApres(), null, config, false);
        em.merge(a);
        return e;
    }

    // Parrainage : crédit offert au parrain (clé unique par parrainage). Dans la transaction
    // de l'appelant. null si déjà donné.
    public Ecriture crediterParrainage(Long abonnementId, Long parrainageId, double montant, String libelle) {
        Abonnement a = verrouiller(abonnementId);
        assurerCredit(a);
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        regulariser(a, config);
        double avant = solde(a.getId());
        boolean bloqueAvant = bloque(a, avant, config);
        Ecriture e = ecrire(a, TYPE_PARRAINAGE, montant, "PARR:" + parrainageId, null, m -> {
            m.setParrainageId(parrainageId);
            m.setLibelle(libelle);
        });
        if (e == null) return null;
        apresCreditAjoute(a, e, bloqueAvant);
        recalculer(a, e.soldeApres(), null, config, false);
        em.merge(a);
        return e;
    }

    // Recalcul après un changement qui n'écrit rien dans le compte (tarif spécial,
    // prolongation de l'essai). Dans la transaction de l'appelant.
    public void recalculerApresChangement(Abonnement a0) {
        Abonnement a = verrouiller(a0.getId());
        assurerCredit(a);
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        recalculer(a, solde(a.getId()), null, config, false);
        em.merge(a);
    }

    // ------------------------------------------------------------------ mensualités

    public record Prelevement(Long abonnementId, Long farmId, String farmUniqueId, String mois, double montant,
            double poulesMoyenne, int jours, int joursMois, boolean prixFixe, double soldeAvant, double soldeApres,
            String libelle, boolean ecrit) {}

    // ------------------------------------------------------------------ suspensions

    // Suspension ouverte par la console (le jour même n'est pas facturé). Sans effet si une
    // suspension est déjà ouverte.
    public void ouvrirSuspension(Long abonnementId, LocalDate du) {
        Integer ouvertes = jdbc.queryForObject("SELECT COUNT(*) FROM suspensions_credit WHERE abonnement_id = ? AND au IS NULL",
                Integer.class, abonnementId);
        if (ouvertes != null && ouvertes > 0) return;
        jdbc.update("INSERT INTO suspensions_credit (abonnement_id, du) VALUES (?, ?)", abonnementId, java.sql.Date.valueOf(du));
    }

    // Fin de la suspension (réactivation ou recharge de la console) : le jour de la
    // réactivation est facturé, la suspension s'arrête la veille.
    public void fermerSuspension(Long abonnementId, LocalDate reactivation) {
        jdbc.update("UPDATE suspensions_credit SET au = ? WHERE abonnement_id = ? AND au IS NULL",
                java.sql.Date.valueOf(reactivation.minusDays(1)), abonnementId);
    }

    // Suspensions par abonnement : [du, au] (au null = toujours en cours). ids null : toutes.
    Map<Long, List<LocalDate[]>> suspensions(Collection<Long> abonnementIds) {
        Map<Long, List<LocalDate[]>> m = new HashMap<>();
        if (abonnementIds != null && abonnementIds.isEmpty()) return m;
        String sql = "SELECT abonnement_id, du, au FROM suspensions_credit";
        Object[] args = new Object[0];
        if (abonnementIds != null) {
            sql += " WHERE abonnement_id IN (" + String.join(",", java.util.Collections.nCopies(abonnementIds.size(), "?")) + ")";
            args = abonnementIds.toArray();
        }
        jdbc.query(sql, rs -> {
            m.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new LocalDate[] {
                    rs.getDate(2).toLocalDate(), rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate() });
        }, args);
        return m;
    }

    // Jours payés d'un mois : la période couverte par le crédit (AbonnementCredit.periodePayee),
    // moins chaque jour suspendu. null si aucun jour (mois entièrement suspendu, ou non couvert :
    // aucune mensualité, pas même à 0).
    public record JoursPayes(boolean[] payes, int nb) {}

    static JoursPayes joursPayes(LocalDate creditDepuis, LocalDate creditEpuiseLe, YearMonth m,
            List<LocalDate[]> suspensions) {
        AbonnementCredit.Periode p = AbonnementCredit.periodePayee(m, creditDepuis, creditEpuiseLe);
        if (p == null) return null;
        boolean[] b = new boolean[m.lengthOfMonth()];
        for (int d = p.du().getDayOfMonth(); d <= p.au().getDayOfMonth(); d++) b[d - 1] = true;
        if (suspensions != null) {
            for (LocalDate[] s : suspensions) {
                LocalDate debut = s[0].isBefore(m.atDay(1)) ? m.atDay(1) : s[0];
                LocalDate fin = s[1] == null || s[1].isAfter(m.atEndOfMonth()) ? m.atEndOfMonth() : s[1];
                for (LocalDate d = debut; !d.isAfter(fin); d = d.plusDays(1)) b[d.getDayOfMonth() - 1] = false;
            }
        }
        int nb = 0;
        for (boolean x : b) if (x) nb++;
        return nb == 0 ? null : new JoursPayes(b, nb);
    }

    static String cleMensualite(Long abonnementId, YearMonth m) {
        return "MENS:" + abonnementId + ":" + m;
    }

    // Mensualité d'un mois terminé, sur un abonnement verrouillé. sujetsMois : sujets
    // vivants de la ferme chaque jour du mois (index 0 = le 1er), null = aucune poule.
    private Prelevement preleverMois(Abonnement a, YearMonth m, long[] sujetsMois, AbonnementConfig config,
            boolean ecrire, List<LocalDate[]> suspensions) {
        String cle = cleMensualite(a.getId(), m);
        if (mouvementRepo.existsByCle(cle)) return null;
        JoursPayes p = joursPayes(a.getCreditDepuis(), a.getCreditEpuiseLe(), m, suspensions);
        if (p == null) return null;
        long somme = 0;
        if (sujetsMois != null) {
            for (int i = 0; i < p.payes().length && i < sujetsMois.length; i++) {
                if (p.payes()[i]) somme += sujetsMois[i];
            }
        }
        double moyenne = (double) somme / p.nb();
        AbonnementCredit.Mensualite mm = AbonnementCredit.mensualite(moyenne, p.nb(), m.lengthOfMonth(),
                AbonnementTarif.regles(config), a.getPrixMensuelFixe());
        String libelle = AbonnementCredit.libelleMensualite(m, mm);
        double avant = solde(a.getId());
        if (!ecrire) {
            return new Prelevement(a.getId(), a.getFarm().getId(), a.getFarm().getUniqueId(), m.toString(), mm.montant(),
                    moyenne, mm.jours(), mm.joursMois(), mm.prixFixe(), avant, avant - mm.montant(), libelle, false);
        }
        Ecriture e = ecrire(a, TYPE_MENSUALITE, -mm.montant(), cle, null, x -> {
            x.setMois(m.toString());
            x.setPoulesMoyenne(Math.round(moyenne * 100) / 100.0);
            x.setJours(mm.jours());
            x.setJoursMois(mm.joursMois());
            x.setPrixFixe(mm.prixFixe());
            x.setLibelle(libelle);
        });
        if (e == null) return null;
        // Crédit vidé : le mois suivant n'est plus couvert (grâce, puis blocage).
        if (e.soldeApres() <= 0 && a.getCreditEpuiseLe() == null) {
            a.setCreditEpuiseLe(m.plusMonths(1).atDay(1));
        }
        return new Prelevement(a.getId(), a.getFarm().getId(), a.getFarm().getUniqueId(), m.toString(), mm.montant(),
                moyenne, mm.jours(), mm.joursMois(), mm.prixFixe(), e.soldeAvant(), e.soldeApres(), libelle, true);
    }

    private static YearMonth premierMois(LocalDate creditDepuis, YearMonth courant) {
        YearMonth d = YearMonth.from(creditDepuis);
        YearMonth limite = courant.minusMonths(MOIS_RATTRAPAGE);
        return d.isBefore(limite) ? limite : d;
    }

    // Mensualités en retard d'UNE ferme (abonnement verrouillé), avant d'ajouter du crédit :
    // un mois terminé est toujours payé avec les règles du moment où il s'est terminé.
    void regulariser(Abonnement a, AbonnementConfig config) {
        if (a.getCreditDepuis() == null) return;
        YearMonth courant = YearMonth.now();
        List<LocalDate[]> susp = suspensions(List.of(a.getId())).getOrDefault(a.getId(), List.of());
        for (YearMonth m = premierMois(a.getCreditDepuis(), courant); m.isBefore(courant); m = m.plusMonths(1)) {
            if (joursPayes(a.getCreditDepuis(), a.getCreditEpuiseLe(), m, susp) == null
                    || mouvementRepo.existsByCle(cleMensualite(a.getId(), m))) continue;
            Map<Long, long[]> s = tarifService.sujetsParJourSansRisque(List.of(a.getFarm().getId()), m.atDay(1), m.atEndOfMonth());
            if (s == null) {
                log.error("Crédit : mensualité de {} non prélevée pour l'abonnement {} (comptage en échec), rattrapée par la tâche",
                        m, a.getId());
                return;
            }
            preleverMois(a, m, s.get(a.getFarm().getId()), config, true, susp);
        }
    }

    private record Ligne(Long abonnementId, Long farmId, LocalDate creditDepuis, LocalDate creditEpuiseLe,
            boolean suspendu, LocalDate suspenduLe) {}

    private List<Ligne> lignesCredit() {
        return jdbc.query("SELECT id, farm_id, credit_depuis, credit_epuise_le, COALESCE(suspendu, false), suspendu_le "
                + "FROM abonnements WHERE credit_depuis IS NOT NULL ORDER BY id", (rs, i) -> new Ligne(rs.getLong(1),
                        rs.getLong(2), rs.getDate(3).toLocalDate(),
                        rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), rs.getBoolean(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime().toLocalDate()));
    }

    // Toutes les mensualités dues (mois terminés pas encore prélevés). ecrire=false :
    // simulation, rien n'est écrit. Une transaction par ferme et par mois : une ferme en
    // erreur n'empêche jamais les autres. Les mois d'une même ferme sont pris dans l'ordre
    // (un mois qui vide le crédit rend le suivant non couvert).
    public List<Prelevement> prelever(boolean ecrire) {
        YearMonth courant = YearMonth.now();
        Set<String> deja = new HashSet<>(jdbc.queryForList(
                "SELECT cle FROM mouvements_credit WHERE type = 'MENSUALITE' AND mois >= ?", String.class,
                courant.minusMonths(MOIS_RATTRAPAGE).toString()));
        // Mois -> abonnements à prélever (d'après l'état actuel, sans requête par ferme).
        TreeMap<YearMonth, List<Ligne>> parMois = new TreeMap<>();
        Map<Long, List<LocalDate[]>> susp = suspensions(null);
        for (Ligne l : lignesCredit()) {
            List<LocalDate[]> s = susp.getOrDefault(l.abonnementId(), List.of());
            for (YearMonth m = premierMois(l.creditDepuis(), courant); m.isBefore(courant); m = m.plusMonths(1)) {
                if (deja.contains(cleMensualite(l.abonnementId(), m))
                        || joursPayes(l.creditDepuis(), l.creditEpuiseLe(), m, s) == null) continue;
                parMois.computeIfAbsent(m, k -> new ArrayList<>()).add(l);
            }
        }
        List<Prelevement> res = new ArrayList<>();
        for (Map.Entry<YearMonth, List<Ligne>> e : parMois.entrySet()) {
            YearMonth m = e.getKey();
            List<Long> fermes = e.getValue().stream().map(Ligne::farmId).distinct().toList();
            Map<Long, long[]> sujets = tarifService.sujetsParJourSansRisque(fermes, m.atDay(1), m.atEndOfMonth());
            if (sujets == null) {
                log.error("Crédit : comptage des poules de {} en échec, mensualités reportées à demain", m);
                continue;
            }
            for (Ligne l : e.getValue()) {
                try {
                    Prelevement p = ecrire
                            ? tx.execute(s -> {
                                Abonnement a = verrouiller(l.abonnementId());
                                AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
                                Prelevement x = preleverMois(a, m, sujets.get(l.farmId()), config, true,
                                        susp.getOrDefault(l.abonnementId(), List.of()));
                                if (x != null) {
                                    // Sans comptage ici : recalculerToutes (en un seul comptage) suit.
                                    recalculer(a, x.soldeApres(), null, config, true);
                                    em.merge(a);
                                }
                                return x;
                            })
                            : tx.execute(s -> {
                                s.setRollbackOnly();
                                Abonnement a = em.find(Abonnement.class, l.abonnementId());
                                return preleverMois(a, m, sujets.get(l.farmId()), configRepo.findFirstByOrderByIdAsc(), false,
                                        susp.getOrDefault(l.abonnementId(), List.of()));
                            });
                    if (p != null) res.add(p);
                } catch (Exception ex) {
                    log.error("Crédit : mensualité {} de l'abonnement {} en échec : {}", m, l.abonnementId(), ex.getMessage(), ex);
                }
            }
        }
        return res;
    }

    // Échéance de toutes les fermes au crédit, recalculée au rythme du jour : un seul
    // comptage pour toutes les fermes, une écriture seulement si l'échéance change.
    public int recalculerToutes() {
        LocalDate auj = LocalDate.now();
        List<Ligne> lignes = lignesCredit();
        if (lignes.isEmpty()) return 0;
        Map<Long, Double> soldes = soldes();
        Map<Long, LocalDate> depuis = new HashMap<>();
        for (Ligne l : lignes) depuis.put(l.farmId(), l.creditDepuis());
        Map<Long, Rythme> rythmes = rythmes(null, depuis, auj);
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        Map<Long, Object[]> actuels = new HashMap<>();
        jdbc.query("SELECT id, date_fin, prix_mensuel_fixe FROM abonnements WHERE credit_depuis IS NOT NULL", rs -> {
            actuels.put(rs.getLong(1), new Object[] { rs.getDate(2).toLocalDate(), rs.getObject(3) == null ? null : rs.getDouble(3) });
        });
        int n = 0;
        for (Ligne l : lignes) {
            double solde = soldes.getOrDefault(l.abonnementId(), 0.0);
            Rythme r = rythmes == null ? null : rythmes.getOrDefault(l.farmId(), new Rythme(0, auj.getDayOfMonth()));
            Object[] act = actuels.get(l.abonnementId());
            if (act == null) continue;
            Abonnement fictif = new Abonnement();
            fictif.setPrixMensuelFixe((Double) act[1]);
            Double cout = solde > 0 ? coutMensuel(fictif, r, config) : null;
            AbonnementCredit.Fin f = AbonnementCredit.fin(solde, auj, l.creditDepuis(), l.creditEpuiseLe(), cout, (LocalDate) act[0]);
            if (Objects.equals(f.dateFin(), act[0]) && Objects.equals(f.creditEpuiseLe(), l.creditEpuiseLe())) continue;
            try {
                tx.executeWithoutResult(s -> {
                    Abonnement a = verrouiller(l.abonnementId());
                    recalculer(a, solde(a.getId()), r, config, true);
                    em.merge(a);
                });
                n++;
            } catch (Exception ex) {
                log.error("Crédit : échéance de l'abonnement {} non recalculée : {}", l.abonnementId(), ex.getMessage(), ex);
            }
        }
        return n;
    }

    public record ResultatTache(int fermesConverties, List<Prelevement> mensualites, int echeancesRecalculees) {}

    // Tous les jours à 00 h 30 UTC (le 1er du mois : mensualités du mois qui vient de finir).
    @Scheduled(cron = "${abonnement.credit.cron:0 30 0 * * *}", zone = "UTC")
    public void tacheQuotidienne() {
        try {
            ResultatTache r = executer(true);
            log.info("Crédit : {} ferme(s) convertie(s), {} mensualité(s), {} échéance(s) recalculée(s)",
                    r.fermesConverties(), r.mensualites().size(), r.echeancesRecalculees());
        } catch (Exception e) {
            log.error("Tâche du crédit en échec : {}", e.getMessage(), e);
        }
    }

    public ResultatTache executer(boolean ecrire) {
        int conv = ecrire ? convertirAnciennesFermes() : 0;
        List<Prelevement> p = prelever(ecrire);
        int maj = ecrire ? recalculerToutes() : 0;
        if (ecrire) envoyerEmailsMensualites(p);
        return new ResultatTache(conv, p, maj);
    }

    // ------------------------------------------------------------------ e-mails

    // Après le commit (jamais pour une action annulée) ; un échec d'envoi ne casse rien.
    public void apresCommit(Runnable r) {
        Runnable sur = () -> {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("Crédit : e-mail non envoyé : {}", e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sur.run();
                }
            });
        } else {
            sur.run();
        }
    }

    public void emailFerme(Long farmId, String sujet, String message) {
        try {
            for (DestinatairesAdmin.Destinataire d : destinataires.parFerme(List.of(farmId)).getOrDefault(farmId, List.of())) {
                try {
                    emailService.sendMessageCocorico(d.email(), d.nom(), "Crédit Cocorico", sujet, message);
                } catch (Exception e) {
                    log.warn("Crédit : e-mail à {} non envoyé : {}", d.email(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Crédit : e-mails de la ferme {} non préparés : {}", farmId, e.getMessage());
        }
    }

    public static String phraseSolde(double solde, LocalDate finEstimee, String phraseMois) {
        if (solde > 0) {
            return "Crédit restant : " + AbonnementEcheance.fcfa(solde) + ", " + phraseMois + " au rythme actuel"
                    + (finEstimee != null ? " (jusqu'au " + AbonnementEcheance.date(finEstimee) + " environ)" : "") + ".";
        }
        if (solde < 0) return "Votre crédit est épuisé : il reste " + AbonnementEcheance.fcfa(-solde) + " à payer.";
        return "Votre crédit est épuisé.";
    }

    static final String COMMENT_RECHARGER = "Pour recharger : envoyez le montant de votre choix par mobile money au "
            + NUMERO + ", puis ouvrez la page Abonnement dans Cocorico et cliquez sur « J'ai rechargé ».";

    public void emailRechargeValidee(Long farmId, String farmNom, Recharge r) {
        String sujet = "Votre recharge de " + AbonnementEcheance.fcfa(r.montant()) + " est validée";
        String message = "La recharge de la ferme " + farmNom + " est validée : " + AbonnementEcheance.fcfa(r.montant())
                + " ajoutés à votre crédit."
                + (r.bonus() > 0 ? "\n\nBonus offert : " + AbonnementEcheance.fcfa(r.bonus()) + " de crédit en plus." : "")
                + "\n\n" + phraseSolde(r.solde(), r.finEstimee(), r.phraseMois())
                + "\n\nMerci pour votre confiance. Une question ? Écrivez-nous sur WhatsApp au " + NUMERO + ".";
        emailFerme(farmId, sujet, message);
    }

    private void envoyerEmailsMensualites(List<Prelevement> liste) {
        if (liste == null || liste.isEmpty()) return;
        // Une ferme peut avoir plusieurs mois (rattrapage) : un e-mail par ferme, le dernier état.
        Map<Long, List<Prelevement>> parFerme = new HashMap<>();
        for (Prelevement p : liste) if (p.ecrit()) parFerme.computeIfAbsent(p.farmId(), k -> new ArrayList<>()).add(p);
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        for (Map.Entry<Long, List<Prelevement>> e : parFerme.entrySet()) {
            try {
                Object[] etat = tx.execute(s -> {
                    s.setRollbackOnly();
                    List<Abonnement> as = em.createQuery("SELECT a FROM Abonnement a JOIN FETCH a.farm WHERE a.farm.id = :f",
                            Abonnement.class).setParameter("f", e.getKey()).getResultList();
                    if (as.isEmpty()) return null;
                    Abonnement a = as.get(0);
                    CreditDTO c = etat(a, config, 0);
                    String nom = a.getFarm().getNom();
                    if (nom == null || nom.isBlank()) {
                        List<String> n = jdbc.queryForList("SELECT farm_name FROM utilisateurs WHERE farm_id = ? AND farm_name IS NOT NULL ORDER BY id LIMIT 1",
                                String.class, e.getKey());
                        nom = n.isEmpty() ? "votre ferme" : n.get(0);
                    }
                    return new Object[] { c, nom, AbonnementEcheance.calculer(a, config, LocalDate.now()) };
                });
                if (etat == null) continue;
                CreditDTO c = (CreditDTO) etat[0];
                AbonnementEcheance.Etat ech = (AbonnementEcheance.Etat) etat[2];
                List<Prelevement> ps = e.getValue();
                Prelevement dernier = ps.get(ps.size() - 1);
                StringBuilder sb = new StringBuilder();
                for (Prelevement p : ps) {
                    sb.append("Mensualité de ").append(AbonnementCredit.moisFr(YearMonth.parse(p.mois()))).append(" : ")
                      .append(AbonnementEcheance.fcfa(p.montant())).append(" retirés de votre crédit.\n")
                      .append(p.libelle()).append("\n\n");
                }
                sb.append(phraseSolde(c.solde(), c.finEstimee(), c.phraseMois()));
                if (c.solde() <= 0) {
                    sb.append("\n\n").append(ech.delaiGraceJours() > 0
                            ? "Tout marche encore normalement jusqu'au " + AbonnementEcheance.date(ech.dernierJourAcces())
                                    + ". Ensuite, l'application web sera bloquée."
                            : "L'application web est bloquée jusqu'à la recharge.");
                    sb.append("\n\n").append(COMMENT_RECHARGER);
                }
                sb.append("\n\nUne question ? Écrivez-nous sur WhatsApp au ").append(NUMERO).append(".");
                String sujet = (dernier.soldeApres() <= 0 ? "Crédit épuisé : " : "")
                        + "mensualité de " + AbonnementCredit.moisFr(YearMonth.parse(dernier.mois())) + " ("
                        + AbonnementEcheance.fcfa(ps.stream().mapToDouble(Prelevement::montant).sum()) + ")";
                emailFerme(e.getKey(), Character.toUpperCase(sujet.charAt(0)) + sujet.substring(1), sb.toString());
            } catch (Exception ex) {
                log.warn("Crédit : e-mail de mensualité non envoyé à la ferme {} : {}", e.getKey(), ex.getMessage());
            }
        }
    }

    // Utilisé par les rappels : solde de quelques abonnements.
    public Map<Long, Double> soldesDe(Collection<Long> abonnementIds) {
        Map<Long, Double> m = new HashMap<>();
        if (abonnementIds == null || abonnementIds.isEmpty()) return m;
        List<Long> ids = new ArrayList<>(abonnementIds);
        String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT abonnement_id, SUM(montant) FROM mouvements_credit WHERE abonnement_id IN (" + in + ") GROUP BY 1",
                rs -> {
                    m.put(rs.getLong(1), rs.getDouble(2));
                }, ids.toArray());
        return m;
    }

    static Date sql(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
