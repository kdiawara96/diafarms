package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.ReportingDTO;
import com.diafarms.ml.commons.Franc;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.Objectif;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.ImputationPaiementRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReportingRepo;
import com.diafarms.ml.repository.VenteOeufsRepartitionRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;

import lombok.RequiredArgsConstructor;

// Page Reporting (GET /reporting) : tous les chiffres calculés ici, sur toute la période,
// sans limite de nombre de saisies ni de transactions. Mêmes règles que le reste de l'app :
//
// ARGENT (par Projet, puis total)
//  - Vendu : transactions VALIDES de vente de la période (œufs, réforme : la part du
//    Projet ; fientes et autres ventes), payées ou non. Même source que
//    TransactionServiceImpl.getStats totalVendu.
//  - Encaissé : argent reçu dans la période. Ferme entière = getStats totalEncaisse
//    (paiements des clients, ventes sans client au montant rapporté, ventes diverses,
//    autres entrées). Pour un Projet = règle d'EncaissementProjetService : paiements des
//    clients reçus dans la période et imputés sur ses ventes, au prorata de sa part de
//    chaque vente, + montant rapporté de ses ventes sans client + ses ventes diverses et
//    autres entrées. Ce qui n'a réglé aucune vente (acompte, avance) reste « Commun ».
//  - Reste à encaisser : ce qui reste dû aujourd'hui sur les ventes de la période.
//  - Autres entrées : entrées validées qui ne sont ni des ventes ni des paiements de clients.
//  - Dépenses : sorties VALIDES, sauf les remboursements aux clients (pas une charge).
//    Pour un Projet : ses dépenses (rattachement « Le Projet », comme la fiche Projet)
//    + sa main-d'œuvre (salaires payés dans la période, répartis par
//    MainOeuvreService, comme le « Résultat complet » de la fiche Projet).
//  - Résultat = Vendu + Autres entrées - Dépenses.
//  - Dépenses de site / de toute la ferme : jamais réparties sur un Projet (la fiche
//    Projet ne le fait pas) ; elles forment la ligne « Commun », moins la part des
//    salaires déjà répartie en main-d'œuvre.
//
// ÉLEVAGE
//  - Sujets vivants d'un Projet à une date = sujets de départ - morts - réformés jusqu'à
//    cette date (EffectifVivantHelper, MainOeuvreService), s'il est en cours ce jour-là.
//  - Effectif au début = vivants la veille du premier jour (sujets de départ pour un
//    Projet qui démarre dans la période) ; mortalité % = morts de la période / effectif
//    au début.
//  - Taux de ponte (Projets PONTE seulement) = œufs collectés / sujets vivants, les jours
//    où une collecte est saisie (rapport journalier : TP = NTO / NPR).
//  - Alvéoles = œufs collectés / 30 ; aliment par alvéole et coût d'une alvéole sur les
//    Projets PONTE (aliment consommé, dépenses du Projet).
@Service
@RequiredArgsConstructor
public class ReportingService {

    private static final int TAILLE_PAQUET = 1000;
    private static final long JOURS_MAX = 3700;
    private static final Set<String> ROLES_SANS_REPORTING = Set.of("COMPTABLE", "VENTE", "PRODUCTION");
    private static final Set<String> ROLES_SALAIRES = Set.of("ADMIN", "SUPER_ADMIN", "RESPONSABLE", "COMPTABLE");

    private final ReportingRepo reportingRepo;
    private final ProjetsRepo projetsRepo;
    private final ImputationPaiementRepo imputationRepo;
    private final VenteOeufsRepartitionRepo venteOeufsRepartitionRepo;
    private final VenteReformeRepartitionRepo venteReformeRepartitionRepo;
    private final MainOeuvreService mainOeuvreService;
    private final OtherService otherService;

    private static double nz(Object v) { return v == null ? 0.0 : ((Number) v).doubleValue(); }

    private static boolean aRole(Utilisateurs u, Set<String> roles) {
        return u.getRoles() != null && u.getRoles().stream().anyMatch(r -> r.getRole() != null && roles.contains(r.getRole().toUpperCase()));
    }

    private static boolean seulementRoles(Utilisateurs u, Set<String> roles) {
        return u.getRoles() != null && !u.getRoles().isEmpty()
                && u.getRoles().stream().allMatch(r -> r.getRole() != null && roles.contains(r.getRole().toUpperCase()));
    }

    /** Contexte commun aux deux périodes. */
    private record Contexte(Long farmId, List<Projets> projets, Map<Long, Projets> parId, Map<String, Long> idParUid,
                            Set<Long> scope, boolean vueFerme, boolean mainOeuvreVisible, boolean communVisible) {}

    @Transactional(readOnly = true)
    public ReportingDTO rapport(LocalDate dateDebut, LocalDate dateFin, String projetUniqueId) {
        Utilisateurs u;
        try {
            u = otherService.getCurrentUser();
        } catch (Exception e) {
            u = null;
        }
        if (u == null || u.getFarm() == null) throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        if (seulementRoles(u, ROLES_SANS_REPORTING) || !aRole(u, Set.of("ADMIN", "SUPER_ADMIN", "RESPONSABLE"))) {
            throw new IllegalArgumentException("Le Reporting n'est pas ouvert à votre rôle.");
        }
        LocalDate deb = dateDebut != null ? dateDebut : LocalDate.now().withDayOfMonth(1);
        LocalDate fin = dateFin != null ? dateFin : LocalDate.now();
        if (fin.isBefore(deb)) throw new IllegalArgumentException("La date de fin précède la date de début.");
        long jours = ChronoUnit.DAYS.between(deb, fin) + 1;
        if (jours > JOURS_MAX) throw new IllegalArgumentException("Période trop longue : 10 ans au plus.");

        Long farmId = u.getFarm().getId();
        List<Projets> projets = projetsRepo.findAllActiveByFarm(farmId);
        Map<Long, Projets> parId = new HashMap<>();
        Map<String, Long> idParUid = new HashMap<>();
        for (Projets p : projets) {
            parId.put(p.getId(), p);
            idParUid.put(p.getUniqueId(), p.getId());
        }
        // Même périmètre que les transactions (TransactionServiceImpl.resolveProjetIdsScopeForList) :
        // un RESPONSABLE pur ne voit que SES projets.
        boolean responsablePur = seulementRoles(u, Set.of("RESPONSABLE"));
        Set<Long> autorises = responsablePur
                ? new HashSet<>(projetsRepo.findProjetIdsAssignedAsResponsableToUser(farmId, u.getUniqueId()))
                : null;
        Set<Long> scope;
        String filtre = projetUniqueId == null || projetUniqueId.isBlank() ? null : projetUniqueId.trim();
        if (filtre != null) {
            Long id = idParUid.get(filtre);
            if (id == null || (autorises != null && !autorises.contains(id))) {
                throw new IllegalArgumentException("Projet introuvable : " + filtre);
            }
            scope = Set.of(id);
        } else if (autorises != null) {
            scope = autorises;
        } else {
            scope = new HashSet<>(parId.keySet());
        }
        boolean vueFerme = filtre == null && autorises == null;
        Contexte ctx = new Contexte(farmId, projets, parId, idParUid, scope, vueFerme,
                aRole(u, ROLES_SALAIRES), autorises == null);

        LocalDate precFin = deb.minusDays(1);
        LocalDate precDeb = precFin.minusDays(jours - 1);
        Calcul cur = calculer(ctx, deb, fin);
        Calcul prec = calculer(ctx, precDeb, precFin);

        return ReportingDTO.builder()
                .dateDebut(deb).dateFin(fin).precedentDebut(precDeb).precedentFin(precFin)
                .projetUniqueId(filtre)
                .vueFerme(vueFerme)
                .mainOeuvreVisible(ctx.mainOeuvreVisible())
                .periode(cur.bloc).precedent(prec.bloc)
                .seriesJour(cur.seriesJour).seriesSemaine(cur.seriesSemaine)
                .parProjet(cur.lignes).commun(cur.commun)
                .depensesParCategorie(cur.parCategorie).depensesParRattachement(cur.parRattachement)
                .parVendeur(cur.parVendeur)
                .build();
    }

    // ---------------------------------------------------------------------------------

    /** Cumuls d'un Projet (ou de la ferme) sur la période. */
    private static final class Acc {
        double vendu, encaisse, reste, autres, depenses, mo;
        long oeufs, casses, mortes;
        double aliment;
        int effDebut, effFin;
        // Ponte : œufs et sujets vivants cumulés sur les jours de collecte.
        double oeufsJoursCollecte, sujetsJoursCollecte;
        boolean donnees;
    }

    private static final class Calcul {
        ReportingDTO.Bloc bloc;
        List<ReportingDTO.Jour> seriesJour;
        List<ReportingDTO.Semaine> seriesSemaine;
        List<ReportingDTO.LigneProjet> lignes;
        ReportingDTO.Argent commun;
        List<ReportingDTO.Montant> parCategorie;
        List<ReportingDTO.Montant> parRattachement;
        List<ReportingDTO.Vendeur> parVendeur;
    }

    private static final class VendeurAcc {
        String nom;
        Set<String> ventes = new HashSet<>();
        long diverses;
        double vendu;
    }

    private static void ajouter(Map<LocalDate, Double> m, LocalDate d, double v) {
        if (v != 0) m.merge(d, v, Double::sum);
    }

    private Calcul calculer(Contexte ctx, LocalDate deb, LocalDate fin) {
        Long farmId = ctx.farmId();
        Set<Long> scope = ctx.scope();
        Map<Long, Acc> acc = new HashMap<>();
        for (Long id : scope) acc.put(id, new Acc());
        Acc ferme = new Acc();
        double sortiesCommunesBrutes = 0; // dépenses sans Projet (site, toute la ferme)
        Map<LocalDate, Double> encFerme = new HashMap<>(), depFerme = new HashMap<>();
        Map<LocalDate, Double> encScope = new HashMap<>(), depScope = new HashMap<>();
        Map<String, Double> categoriesFerme = new HashMap<>(), categoriesScope = new HashMap<>();
        double rattProjet = 0, rattSite = 0, rattFerme = 0;
        Map<String, VendeurAcc> vendeurs = new HashMap<>();

        // 1. Transactions validées de la période.
        for (Object[] r : reportingRepo.transactionsParJour(farmId, deb, fin)) {
            LocalDate date = (LocalDate) r[0];
            Long projetId = (Long) r[1];
            TypeTransaction type = (TypeTransaction) r[2];
            SourceTransaction source = (SourceTransaction) r[3];
            String categorie = r[4] == null || ((String) r[4]).isBlank() ? "Sans catégorie" : (String) r[4];
            Long siteId = (Long) r[5];
            double montant = nz(r[6]);
            Acc a = projetId != null ? acc.get(projetId) : null;
            if (type == TypeTransaction.ENTREE) {
                if (source == SourceTransaction.VENTE_OEUFS || source == SourceTransaction.VENTE_REFORME) {
                    ferme.vendu += montant;
                    if (a != null) { a.vendu += montant; a.donnees = true; }
                    // Encaissé de ces ventes : voir lignes de vente et imputations ci-dessous.
                } else if (source == SourceTransaction.VENTE_DIVERSE) {
                    ferme.vendu += montant;
                    ferme.encaisse += montant;
                    ajouter(encFerme, date, montant);
                    if (a != null) { a.vendu += montant; a.encaisse += montant; a.donnees = true; ajouter(encScope, date, montant); }
                } else if (source == SourceTransaction.PAIEMENT_CLIENT) {
                    ferme.encaisse += montant;
                    ajouter(encFerme, date, montant);
                } else {
                    ferme.autres += montant;
                    ferme.encaisse += montant;
                    ajouter(encFerme, date, montant);
                    if (a != null) { a.autres += montant; a.encaisse += montant; a.donnees = true; ajouter(encScope, date, montant); }
                }
            } else if (type == TypeTransaction.SORTIE) {
                if (source == SourceTransaction.REMBOURSEMENT_CLI) continue; // pas une charge
                ferme.depenses += montant;
                ajouter(depFerme, date, montant);
                categoriesFerme.merge(categorie, montant, Double::sum);
                if (projetId != null) rattProjet += montant;
                else if (siteId != null) rattSite += montant;
                else rattFerme += montant;
                if (projetId == null) sortiesCommunesBrutes += montant;
                if (a != null) {
                    a.depenses += montant;
                    a.donnees = true;
                    ajouter(depScope, date, montant);
                    categoriesScope.merge(categorie, montant, Double::sum);
                }
            }
        }

        // 2. Lignes de vente (œufs, réforme) : encaissé au comptant, reste à encaisser, vendeurs.
        List<Object[]> lignesVente = new ArrayList<>(reportingRepo.lignesVenteOeufs(farmId, deb, fin));
        lignesVente.addAll(reportingRepo.lignesVenteReforme(farmId, deb, fin));
        List<String> ventesClient = new ArrayList<>();
        for (Object[] r : lignesVente) if (r[4] != null) ventesClient.add((String) r[3]);
        Map<String, Double> paye = payeParVente(ventesClient);
        for (Object[] r : lignesVente) {
            // [projetId, date, montantLigne, venteUid, clientId, venteMontant, rapporte, montantAttribue, totalAttribue, vendeurUid, vendeurNom]
            Long projetId = (Long) r[0];
            LocalDate date = (LocalDate) r[1];
            double ligne = nz(r[2]);
            double venteMontant = nz(r[5]);
            Acc a = acc.get(projetId);
            double recu;
            if (r[4] == null) {
                // Vente sans client : montant rapporté (règle de VenteOeufsRepo.sumRapporteSansClient).
                recu = venteMontant == 0 ? ligne : ligne * (r[6] != null ? nz(r[6]) : venteMontant) / venteMontant;
                ferme.encaisse += recu;
                ajouter(encFerme, date, recu);
                if (a != null) { a.encaisse += recu; ajouter(encScope, date, recu); }
            } else {
                double part = EncaissementProjetService.part(r[5], r[7], null, null, r[8]);
                recu = Math.min(ligne, paye.getOrDefault((String) r[3], 0.0) * part);
            }
            double reste = Math.max(0.0, ligne - recu);
            ferme.reste += reste;
            if (a != null) {
                a.reste += reste;
                a.donnees = true;
                String vid = r[9] != null ? (String) r[9] : "";
                VendeurAcc v = vendeurs.computeIfAbsent(vid, k -> new VendeurAcc());
                v.nom = r[10] != null ? (String) r[10] : "Inconnu";
                v.ventes.add((String) r[3]);
                v.vendu += ligne;
            }
        }
        for (Object[] r : reportingRepo.ventesDiversesParVendeur(farmId, deb, fin)) {
            Long projetId = (Long) r[0];
            boolean visible = ctx.vueFerme() || (projetId != null && scope.contains(projetId));
            if (!visible) continue;
            String vid = r[1] != null ? (String) r[1] : "";
            VendeurAcc v = vendeurs.computeIfAbsent(vid, k -> new VendeurAcc());
            v.nom = r[2] != null ? (String) r[2] : "Inconnu";
            v.diverses += ((Number) r[3]).longValue();
            v.vendu += nz(r[4]);
        }

        // 3. Paiements des clients reçus dans la période et imputés sur des ventes : part de chaque Projet.
        List<Object[]> imputations = reportingRepo.imputationsParJour(farmId, deb, fin);
        if (!imputations.isEmpty()) {
            List<String> oeufs = new ArrayList<>(), reforme = new ArrayList<>();
            for (Object[] r : imputations) {
                if (r[1] == CibleImputation.VENTE_OEUFS) oeufs.add((String) r[2]); else reforme.add((String) r[2]);
            }
            Map<String, List<Object[]>> parts = new HashMap<>();
            for (List<String> paquet : paquets(oeufs)) {
                for (Object[] l : venteOeufsRepartitionRepo.findPartsParVentes(paquet)) parts.computeIfAbsent((String) l[0], k -> new ArrayList<>()).add(l);
            }
            for (List<String> paquet : paquets(reforme)) {
                for (Object[] l : venteReformeRepartitionRepo.findPartsParVentes(paquet)) parts.computeIfAbsent((String) l[0], k -> new ArrayList<>()).add(l);
            }
            for (Object[] r : imputations) {
                LocalDate date = (LocalDate) r[0];
                double montant = nz(r[3]);
                // [venteUid, venteMontant, projetUid, code, titre, montantAttribue, venteQte, qteAttribuee, totalAttribue]
                for (Object[] l : parts.getOrDefault((String) r[2], List.of())) {
                    Acc a = acc.get(ctx.idParUid().get((String) l[2]));
                    if (a == null) continue;
                    double m = montant * EncaissementProjetService.part(l[1], l[5], l[6], l[7], l[8]);
                    a.encaisse += m;
                    a.donnees = true;
                    ajouter(encScope, date, m);
                }
            }
        }

        // 4. Main-d'œuvre : salaires payés dans la période, répartis comme sur la fiche Projet.
        double moTousProjets = 0;
        if (ctx.mainOeuvreVisible()) {
            for (Map.Entry<Long, Map<LocalDate, Double>> e : mainOeuvreService.coutParProjet(farmId, deb, fin).entrySet()) {
                for (Map.Entry<LocalDate, Double> j : e.getValue().entrySet()) {
                    moTousProjets += j.getValue();
                    Acc a = acc.get(e.getKey());
                    if (a == null) continue;
                    a.mo += j.getValue();
                    a.depenses += j.getValue();
                    a.donnees = true;
                    ajouter(depScope, j.getKey(), j.getValue());
                    categoriesScope.merge("Main-d'œuvre (part des salaires)", j.getValue(), Double::sum);
                }
            }
        }

        // 5. Élevage.
        Map<Long, TreeMap<LocalDate, long[]>> collectes = new HashMap<>();
        for (Object[] r : reportingRepo.collectesParJour(farmId, deb, fin)) {
            collectes.computeIfAbsent((Long) r[0], k -> new TreeMap<>())
                    .put((LocalDate) r[1], new long[] { (long) nz(r[2]), (long) nz(r[3]) });
        }
        Map<Long, TreeMap<LocalDate, Long>> morts = parProjetEtJour(reportingRepo.mortsParJourJusqua(farmId, fin));
        Map<Long, TreeMap<LocalDate, Long>> reformes = parProjetEtJour(reportingRepo.reformesParJourJusqua(farmId, fin));
        Map<Long, Map<LocalDate, Double>> aliment = new HashMap<>();
        for (Object[] r : reportingRepo.alimentParJour(farmId, deb, fin)) {
            aliment.computeIfAbsent((Long) r[0], k -> new HashMap<>()).merge((LocalDate) r[1], nz(r[2]), Double::sum);
        }
        Map<LocalDate, long[]> jourOeufsMortes = new TreeMap<>();
        Map<LocalDate, Double> jourAliment = new HashMap<>();
        Map<LocalDate, double[]> jourPonte = new HashMap<>(); // [œufs, sujets vivants] des Projets PONTE collectés ce jour
        for (Long id : scope) {
            Projets p = ctx.parId().get(id);
            Acc a = acc.get(id);
            if (p == null || a == null) continue;
            boolean ponte = p.getObjectif() == Objectif.PONTE;
            int nb = p.getNbSujets() == null ? 0 : p.getNbSujets();
            LocalDate debutProjet = p.getDebut();
            LocalDate finProjet = MainOeuvreService.finEffective(p);
            boolean actif = debutProjet != null && !debutProjet.isAfter(fin) && (finProjet == null || !finProjet.isBefore(deb));
            if (actif) a.donnees = true;
            TreeMap<LocalDate, Long> m = morts.getOrDefault(id, new TreeMap<>());
            TreeMap<LocalDate, Long> rf = reformes.getOrDefault(id, new TreeMap<>());
            long cumul = somme(m.headMap(deb, false)) + somme(rf.headMap(deb, false));
            a.effDebut = actif ? (int) Math.max(0, nb - cumul) : 0;
            TreeMap<LocalDate, long[]> col = collectes.getOrDefault(id, new TreeMap<>());
            Map<LocalDate, Double> ali = aliment.getOrDefault(id, Map.of());
            for (LocalDate d = deb; !d.isAfter(fin); d = d.plusDays(1)) {
                long mortsJour = m.getOrDefault(d, 0L);
                cumul += mortsJour + rf.getOrDefault(d, 0L);
                long[] c = col.get(d);
                double kg = ali.getOrDefault(d, 0.0);
                if (mortsJour != 0 || c != null || kg != 0) {
                    a.donnees = true;
                    long[] j = jourOeufsMortes.computeIfAbsent(d, k -> new long[2]);
                    j[1] += mortsJour;
                    a.mortes += mortsJour;
                    if (c != null) { j[0] += c[0]; a.oeufs += c[0]; a.casses += c[1]; }
                    a.aliment += kg;
                    ajouter(jourAliment, d, kg);
                }
                boolean enCours = debutProjet != null && !debutProjet.isAfter(d) && (finProjet == null || !finProjet.isBefore(d));
                if (ponte && c != null && enCours) {
                    long vivants = Math.max(0, nb - cumul);
                    if (vivants > 0) {
                        a.oeufsJoursCollecte += c[0];
                        a.sujetsJoursCollecte += vivants;
                        double[] jp = jourPonte.computeIfAbsent(d, k -> new double[2]);
                        jp[0] += c[0];
                        jp[1] += vivants;
                    }
                }
            }
            boolean enCoursFin = debutProjet != null && !debutProjet.isAfter(fin) && (finProjet == null || !finProjet.isBefore(fin));
            a.effFin = enCoursFin ? (int) Math.max(0, nb - cumul) : 0;
        }

        // 6. Lignes par Projet et totaux.
        Calcul out = new Calcul();
        List<ReportingDTO.LigneProjet> lignes = new ArrayList<>();
        Acc total = new Acc();
        double ponteOeufs = 0, ponteAliment = 0, ponteDepenses = 0, ponteJoursOeufs = 0, ponteJoursSujets = 0;
        boolean ponteVue = false;
        List<Projets> tries = new ArrayList<>();
        for (Long id : scope) if (ctx.parId().containsKey(id)) tries.add(ctx.parId().get(id));
        tries.sort(Comparator.comparing(p -> p.getCode() == null ? "" : p.getCode()));
        for (Projets p : tries) {
            Acc a = acc.get(p.getId());
            boolean ponte = p.getObjectif() == Objectif.PONTE;
            cumuler(total, a);
            if (ponte) {
                ponteVue = true;
                ponteOeufs += a.oeufs;
                ponteAliment += a.aliment;
                ponteDepenses += a.depenses;
                ponteJoursOeufs += a.oeufsJoursCollecte;
                ponteJoursSujets += a.sujetsJoursCollecte;
            }
            if (!a.donnees) continue;
            lignes.add(ReportingDTO.LigneProjet.builder()
                    .projetUniqueId(p.getUniqueId()).code(p.getCode()).nom(p.getTitre())
                    .type(p.getObjectif() != null ? p.getObjectif().name() : null)
                    .elevage(elevage(a, ponte, a.oeufs, a.aliment, a.depenses, a.oeufsJoursCollecte, a.sujetsJoursCollecte))
                    .argent(argent(a, ctx.mainOeuvreVisible(), null))
                    .build());
        }

        ReportingDTO.Argent argentTotal;
        if (ctx.vueFerme()) {
            // Ferme entière : chiffres de la ferme ; « Commun » = ferme - Σ Projets.
            ferme.mo = total.mo;
            double communDepenses = ferme.depenses - total.depenses;
            argentTotal = argent(ferme, ctx.mainOeuvreVisible(), Franc.arrondi(communDepenses));
            Acc commun = new Acc();
            commun.vendu = ferme.vendu - total.vendu;
            commun.encaisse = ferme.encaisse - total.encaisse;
            commun.reste = ferme.reste - total.reste;
            commun.autres = ferme.autres - total.autres;
            commun.depenses = communDepenses;
            out.commun = argent(commun, false, null);
        } else {
            Double communes = ctx.communVisible() ? Franc.arrondi(sortiesCommunesBrutes - moTousProjets) : null;
            argentTotal = argent(total, ctx.mainOeuvreVisible(), communes);
        }
        ReportingDTO.Elevage elevageTotal = elevage(total, ponteVue, ponteOeufs, ponteAliment, ponteDepenses, ponteJoursOeufs, ponteJoursSujets);
        out.bloc = ReportingDTO.Bloc.builder().argent(argentTotal).elevage(elevageTotal).build();
        out.lignes = lignes;

        // 7. Séries, répartitions, vendeurs.
        List<ReportingDTO.Jour> jours = new ArrayList<>();
        for (LocalDate d = deb; !d.isAfter(fin); d = d.plusDays(1)) {
            long[] j = jourOeufsMortes.getOrDefault(d, new long[2]);
            double[] jp = jourPonte.get(d);
            jours.add(ReportingDTO.Jour.builder().date(d).oeufs(j[0]).mortes(j[1])
                    .alimentKg(arr(jourAliment.getOrDefault(d, 0.0), 2))
                    .tauxPonte(jp != null && jp[1] > 0 ? arr(jp[0] / jp[1] * 100, 1) : null)
                    .build());
        }
        out.seriesJour = jours;
        Map<LocalDate, Double> enc = ctx.vueFerme() ? encFerme : encScope;
        Map<LocalDate, Double> dep = ctx.vueFerme() ? depFerme : depScope;
        List<ReportingDTO.Semaine> semaines = new ArrayList<>();
        for (LocalDate d = deb; !d.isAfter(fin); d = d.plusDays(7)) {
            LocalDate f = d.plusDays(6).isAfter(fin) ? fin : d.plusDays(6);
            double e = 0, s = 0;
            for (LocalDate x = d; !x.isAfter(f); x = x.plusDays(1)) {
                e += enc.getOrDefault(x, 0.0);
                s += dep.getOrDefault(x, 0.0);
            }
            semaines.add(ReportingDTO.Semaine.builder().debut(d).fin(f).encaisse(Franc.arrondi(e)).depenses(Franc.arrondi(s)).build());
        }
        out.seriesSemaine = semaines;

        out.parCategorie = (ctx.vueFerme() ? categoriesFerme : categoriesScope).entrySet().stream()
                .filter(e -> Math.abs(e.getValue()) >= 0.005)
                .map(e -> ReportingDTO.Montant.builder().cle(e.getKey()).libelle(e.getKey()).montant(Franc.arrondi(e.getValue())).build())
                .sorted(Comparator.comparingDouble(ReportingDTO.Montant::getMontant).reversed())
                .toList();
        List<ReportingDTO.Montant> ratt = new ArrayList<>();
        if (ctx.vueFerme()) {
            ratt.add(ReportingDTO.Montant.builder().cle("PROJET").libelle("Le Projet").montant(Franc.arrondi(rattProjet)).build());
            ratt.add(ReportingDTO.Montant.builder().cle("SITE").libelle("Un site").montant(Franc.arrondi(rattSite)).build());
            ratt.add(ReportingDTO.Montant.builder().cle("FERME").libelle("Toute la ferme").montant(Franc.arrondi(rattFerme)).build());
        } else {
            ratt.add(ReportingDTO.Montant.builder().cle("PROJET").libelle("Le Projet").montant(Franc.arrondi(total.depenses - total.mo)).build());
            if (total.mo > 0) {
                ratt.add(ReportingDTO.Montant.builder().cle("MAIN_OEUVRE").libelle("Main-d'œuvre (part des salaires)").montant(Franc.arrondi(total.mo)).build());
            }
        }
        out.parRattachement = ratt;
        out.parVendeur = vendeurs.entrySet().stream()
                .map(e -> ReportingDTO.Vendeur.builder()
                        .vendeurUniqueId(e.getKey().isEmpty() ? null : e.getKey())
                        .nom(e.getValue().nom)
                        .nbVentes(e.getValue().ventes.size() + e.getValue().diverses)
                        .vendu(Franc.arrondi(e.getValue().vendu))
                        .build())
                .sorted(Comparator.comparingDouble(ReportingDTO.Vendeur::getVendu).reversed())
                .toList();
        return out;
    }

    private static void cumuler(Acc t, Acc a) {
        t.vendu += a.vendu; t.encaisse += a.encaisse; t.reste += a.reste; t.autres += a.autres;
        t.depenses += a.depenses; t.mo += a.mo;
        t.oeufs += a.oeufs; t.casses += a.casses; t.mortes += a.mortes; t.aliment += a.aliment;
        t.effDebut += a.effDebut; t.effFin += a.effFin;
    }

    private static ReportingDTO.Argent argent(Acc a, boolean moVisible, Double depensesCommunes) {
        double vendu = Franc.arrondi(a.vendu);
        double autres = Franc.arrondi(a.autres);
        double depenses = Franc.arrondi(a.depenses);
        return ReportingDTO.Argent.builder()
                .vendu(vendu)
                .encaisse(Franc.arrondi(a.encaisse))
                .resteAEncaisser(Franc.arrondi(a.reste))
                .autresEntrees(autres)
                .depenses(depenses)
                .mainOeuvre(moVisible ? Franc.arrondi(a.mo) : null)
                .resultat(Franc.arrondi(vendu + autres - depenses))
                .depensesCommunes(depensesCommunes)
                .build();
    }

    private static ReportingDTO.Elevage elevage(Acc a, boolean ponte, double oeufsPonte, double alimentPonte,
                                                double depensesPonte, double joursOeufs, double joursSujets) {
        double alveolesPonte = oeufsPonte / 30.0;
        return ReportingDTO.Elevage.builder()
                .effectifDebut(a.effDebut)
                .effectifFin(a.effFin)
                .oeufsCollectes(a.oeufs)
                .oeufsCasses(a.casses)
                .alveoles(arr(a.oeufs / 30.0, 1))
                .tauxPonteMoyen(ponte && joursSujets > 0 ? arr(joursOeufs / joursSujets * 100, 1) : null)
                .mortes(a.mortes)
                .mortalitePct(a.effDebut > 0 ? arr(a.mortes * 100.0 / a.effDebut, 2) : null)
                .alimentKg(arr(a.aliment, 2))
                .alimentParAlveoleKg(ponte && alveolesPonte > 0 ? arr(alimentPonte / alveolesPonte, 2) : null)
                .coutAlveole(ponte && alveolesPonte > 0 ? Franc.arrondi(depensesPonte / alveolesPonte) : null)
                .build();
    }

    private static double arr(double v, int decimales) {
        double f = Math.pow(10, decimales);
        return Math.round(v * f) / f;
    }

    private static long somme(Map<LocalDate, Long> m) {
        long s = 0;
        for (Long v : m.values()) s += v;
        return s;
    }

    private static Map<Long, TreeMap<LocalDate, Long>> parProjetEtJour(List<Object[]> lignes) {
        Map<Long, TreeMap<LocalDate, Long>> out = new HashMap<>();
        for (Object[] r : lignes) {
            out.computeIfAbsent((Long) r[0], k -> new TreeMap<>()).merge((LocalDate) r[1], (long) nz(r[2]), Long::sum);
        }
        return out;
    }

    private static List<List<String>> paquets(Collection<String> uids) {
        List<String> l = new ArrayList<>(new LinkedHashSet<>(uids));
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i += TAILLE_PAQUET) out.add(l.subList(i, Math.min(l.size(), i + TAILLE_PAQUET)));
        return out;
    }

    /** Σ imputations actives (toutes dates) par vente : ce qui est payé aujourd'hui. */
    private Map<String, Double> payeParVente(Collection<String> venteUids) {
        Map<String, Double> out = new HashMap<>();
        for (List<String> paquet : paquets(venteUids)) {
            for (Object[] r : imputationRepo.sumActivesParVente(paquet)) out.merge((String) r[0], nz(r[1]), Double::sum);
        }
        return out;
    }
}
