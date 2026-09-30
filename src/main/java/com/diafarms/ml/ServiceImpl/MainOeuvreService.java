package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.AffectationPersonnelDTO;
import com.diafarms.ml.DTO.CoutMainOeuvreDTO;
import com.diafarms.ml.commons.FermeScope;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.commons.ProjetsFerme;
import com.diafarms.ml.models.AffectationPersonnel;
import com.diafarms.ml.models.PaiementSalaire;
import com.diafarms.ml.models.Personnel;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AffectationPersonnelRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.PaiementSalaireRepo;
import com.diafarms.ml.repository.PersonnelRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.request.create.AffectationPersonnelCreate;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

// Répartition des salaires sur les projets (coût de main-d'œuvre, analytique : en
// Comptabilité un salaire reste une sortie de la ferme). Pour chaque salaire payé
// d'un mois :
// - les jours où l'employé est affecté à un projet (AffectationPersonnel) vont à ce
//   projet, au prorata des jours du mois (affecté le 11 d'un mois de 30 jours :
//   20/30 du salaire au projet) ;
// - le reste du mois (employé non affecté ces jours-là) est partagé entre les projets
//   en cours ce mois-là, au prorata de leurs sujets vivants à la fin du mois
//   (nbSujets - morts - réformés à cette date). Aucun projet en cours : ce reste n'est
//   attribué à aucun projet.
// Calcul à la lecture (rien de stocké) : une affectation corrigée après coup se
// répercute sur les mois déjà payés.
@Service
@RequiredArgsConstructor
public class MainOeuvreService {

    private final AffectationPersonnelRepo affectationRepo;
    private final PersonnelRepo personnelRepo;
    private final PaiementSalaireRepo paiementRepo;
    private final ProjetsRepo projetsRepo;
    private final MortaliteRepo mortaliteRepo;
    private final ReformeRepo reformeRepo;
    private final ProjetsFerme projetsFerme;
    private final OtherService otherService;
    private final LogsServices logs;

    private Utilisateurs utilisateur() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    // Même population que la gestion du personnel (PersonnelServiceImpl.ensureCanManage).
    private void ensureCanManage(Utilisateurs u) {
        boolean ok = u != null && u.getRoles() != null && u.getRoles().stream().anyMatch(r ->
                "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole())
                        || "RESPONSABLE".equalsIgnoreCase(r.getRole()) || "COMPTABLE".equalsIgnoreCase(r.getRole()));
        if (!ok) throw new IllegalArgumentException("Vous n'avez pas les droits pour gérer le personnel.");
    }

    private Personnel personnel(String uniqueId, Utilisateurs u) {
        Personnel p = personnelRepo.findByUniqueId(uniqueId);
        if (p == null || !FermeScope.memeFerme(p.getFarm(), u)) {
            throw new IllegalArgumentException("Employé introuvable : " + uniqueId);
        }
        return p;
    }

    private AffectationPersonnel affectation(String uniqueId, Utilisateurs u) {
        return affectationRepo.findByUniqueId(uniqueId)
                .filter(a -> FermeScope.memeFerme(a.getFarm(), u))
                .filter(a -> a.getInitialisation() == null || !Boolean.TRUE.equals(a.getInitialisation().getRemoved()))
                .orElseThrow(() -> new IllegalArgumentException("Affectation introuvable : " + uniqueId));
    }

    private static LocalDate date(String s, String libelle) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(libelle + " invalide (attendu AAAA-MM-JJ) : " + s);
        }
    }

    private static boolean chevauche(LocalDate d1, LocalDate f1, LocalDate d2, LocalDate f2) {
        LocalDate fin1 = f1 != null ? f1 : LocalDate.MAX;
        LocalDate fin2 = f2 != null ? f2 : LocalDate.MAX;
        return !d1.isAfter(fin2) && !d2.isAfter(fin1);
    }

    // Une seule affectation à la fois : sinon un même jour de salaire irait à deux projets.
    private void verifierChevauchement(Personnel p, LocalDate debut, LocalDate fin, String exclure) {
        for (AffectationPersonnel a : affectationRepo.findActivesByPersonnelId(p.getId())) {
            if (a.getUniqueId().equals(exclure)) continue;
            if (chevauche(debut, fin, a.getDateDebut(), a.getDateFin())) {
                throw new IllegalArgumentException("Cet employé est déjà affecté au projet " + a.getProjet().getCode()
                        + " sur cette période (du " + a.getDateDebut() + (a.getDateFin() != null ? " au " + a.getDateFin() : ", toujours en cours")
                        + ") : terminez d'abord cette affectation.");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<AffectationPersonnelDTO> affectations(String personnelUniqueId) {
        Utilisateurs u = utilisateur();
        ensureCanManage(u); // les affectations mènent aux salaires : même population
        Personnel p = personnel(personnelUniqueId, u);
        return affectationRepo.findActivesByPersonnelId(p.getId()).stream().map(AffectationPersonnelDTO::fromEntity).toList();
    }

    @Transactional
    public AffectationPersonnelDTO affecter(String personnelUniqueId, AffectationPersonnelCreate data) {
        Utilisateurs u = utilisateur();
        ensureCanManage(u);
        Personnel p = personnel(personnelUniqueId, u);
        if (data.getProjetUniqueId() == null || data.getProjetUniqueId().isBlank()) {
            throw new IllegalArgumentException("Choisissez le projet.");
        }
        Projets projet = projetsFerme.charger(data.getProjetUniqueId());
        if (data.getDateDebut() == null || data.getDateDebut().isBlank()) {
            throw new IllegalArgumentException("La date de début est obligatoire.");
        }
        LocalDate debut = date(data.getDateDebut(), "Date de début");
        LocalDate fin = data.getDateFin() == null || data.getDateFin().isBlank() ? null : date(data.getDateFin(), "Date de fin");
        if (fin != null && fin.isBefore(debut)) throw new IllegalArgumentException("La date de fin est avant la date de début.");
        verifierChevauchement(p, debut, fin, null);

        AffectationPersonnel a = new AffectationPersonnel();
        a.setUniqueId(java.util.UUID.randomUUID().toString());
        a.setPersonnel(p);
        a.setProjet(projet);
        a.setDateDebut(debut);
        a.setDateFin(fin);
        a.setFarm(p.getFarm());
        a.setInitialisation(Initialisation.init());
        AffectationPersonnel saved = affectationRepo.save(a);
        if (u != null) logs.addLogs(u.getId(), saved.getId(), "AffectationPersonnel",
                "Affectation de " + p.getNom() + " au projet " + projet.getCode() + " à partir du " + debut);
        return AffectationPersonnelDTO.fromEntity(saved);
    }

    // Changer les dates (ex. terminer : dateFin = aujourd'hui ; "" = rouvrir).
    @Transactional
    public AffectationPersonnelDTO modifier(String affectationUniqueId, AffectationPersonnelCreate data) {
        Utilisateurs u = utilisateur();
        ensureCanManage(u);
        AffectationPersonnel a = affectation(affectationUniqueId, u);
        LocalDate debut = data.getDateDebut() != null && !data.getDateDebut().isBlank() ? date(data.getDateDebut(), "Date de début") : a.getDateDebut();
        LocalDate fin = data.getDateFin() == null ? a.getDateFin() : data.getDateFin().isBlank() ? null : date(data.getDateFin(), "Date de fin");
        if (fin != null && fin.isBefore(debut)) throw new IllegalArgumentException("La date de fin est avant la date de début.");
        verifierChevauchement(a.getPersonnel(), debut, fin, a.getUniqueId());
        if (data.getProjetUniqueId() != null && !data.getProjetUniqueId().isBlank()) {
            a.setProjet(projetsFerme.charger(data.getProjetUniqueId()));
        }
        a.setDateDebut(debut);
        a.setDateFin(fin);
        a.setInitialisation(Initialisation.updateDate(a.getInitialisation()));
        return AffectationPersonnelDTO.fromEntity(affectationRepo.save(a));
    }

    @Transactional
    public void supprimer(String affectationUniqueId) {
        Utilisateurs u = utilisateur();
        ensureCanManage(u);
        AffectationPersonnel a = affectation(affectationUniqueId, u);
        a.getInitialisation().setRemoved(true);
        a.setInitialisation(Initialisation.updateDate(a.getInitialisation()));
        affectationRepo.save(a);
    }

    private static Map<Long, Integer> versMap(List<Object[]> lignes) {
        Map<Long, Integer> m = new HashMap<>();
        for (Object[] l : lignes) {
            if (l[0] != null && l[1] != null) m.put(((Number) l[0]).longValue(), ((Number) l[1]).intValue());
        }
        return m;
    }

    // Fin réelle d'un projet pour la répartition : un projet clôturé s'arrête à sa
    // clôture (date de libération de ses poulaillers, posée à la clôture, voir
    // ProjetImpl.libererOccupationsActives), à défaut à sa fin prévue ; un projet NON
    // clôturé est toujours en cours, même au-delà de sa fin prévue (null = pas de fin).
    private static LocalDate finEffective(Projets p) {
        boolean cloture = p.getInitialisation() != null && Boolean.TRUE.equals(p.getInitialisation().getArchive());
        if (!cloture) return null;
        LocalDate liberation = p.getOccupations() == null ? null : p.getOccupations().stream()
                .map(com.diafarms.ml.models.OccupationBatiment::getDateSortie)
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
        return liberation != null ? liberation : p.getFinPrevue();
    }

    // Poids de chaque projet en cours sur le mois : ses sujets vivants à la fin du mois.
    private Map<Long, Double> poidsDuMois(Long farmId, List<Projets> projets, YearMonth ym) {
        LocalDate d1 = ym.atDay(1);
        LocalDate d2 = ym.atEndOfMonth();
        Map<Long, Integer> morts = versMap(mortaliteRepo.sumMortsParProjetJusqua(farmId, d2));
        Map<Long, Integer> reformes = versMap(reformeRepo.sumSujetsParProjetJusqua(farmId, d2));
        Map<Long, Double> poids = new HashMap<>();
        for (Projets p : projets) {
            if (p.getDebut() == null || p.getDebut().isAfter(d2)) continue;
            LocalDate fin = finEffective(p);
            if (fin != null && fin.isBefore(d1)) continue;
            int vivants = (p.getNbSujets() == null ? 0 : p.getNbSujets()) - morts.getOrDefault(p.getId(), 0) - reformes.getOrDefault(p.getId(), 0);
            if (vivants > 0) poids.put(p.getId(), (double) vivants);
        }
        return poids;
    }

    @Transactional(readOnly = true)
    public CoutMainOeuvreDTO coutProjet(String projetUniqueId) {
        // Détail par employé et par mois des salaires payés : même population que les salaires.
        ensureCanManage(utilisateur());
        Projets cible = projetsFerme.charger(projetUniqueId);
        Long farmId = cible.getFarm().getId();
        Map<Long, List<AffectationPersonnel>> affParEmploye = affectationRepo.findActivesByFarmId(farmId).stream()
                .collect(Collectors.groupingBy(a -> a.getPersonnel().getId()));
        List<Projets> projets = projetsRepo.findAllActiveByFarm(farmId);
        Map<YearMonth, Map<Long, Double>> cache = new HashMap<>();
        List<CoutMainOeuvreDTO.Ligne> lignes = new ArrayList<>();

        for (PaiementSalaire p : paiementRepo.findActifsByFarmId(farmId)) {
            YearMonth ym;
            try {
                ym = YearMonth.parse(p.getPeriode());
            } catch (Exception e) {
                continue;
            }
            double montant = p.getMontantPaye() == null ? 0 : p.getMontantPaye();
            if (montant <= 0) continue;
            LocalDate d1 = ym.atDay(1);
            LocalDate d2 = ym.atEndOfMonth();
            int joursMois = ym.lengthOfMonth();
            int joursAffectesTotal = 0;
            int joursCible = 0;
            for (AffectationPersonnel a : affParEmploye.getOrDefault(p.getSalaire().getEmploye().getId(), List.of())) {
                LocalDate debut = a.getDateDebut().isAfter(d1) ? a.getDateDebut() : d1;
                LocalDate fin = a.getDateFin() == null || a.getDateFin().isAfter(d2) ? d2 : a.getDateFin();
                int jours = fin.isBefore(debut) ? 0 : (int) ChronoUnit.DAYS.between(debut, fin) + 1;
                joursAffectesTotal += jours;
                if (a.getProjet().getId().equals(cible.getId())) joursCible += jours;
            }
            joursAffectesTotal = Math.min(joursAffectesTotal, joursMois);
            double partAffectation = montant * joursCible / joursMois;
            double reste = montant * (joursMois - joursAffectesTotal) / joursMois;
            double partProrata = 0;
            double pourcentage = 0;
            if (reste > 0.005) {
                Map<Long, Double> poids = cache.computeIfAbsent(ym, m -> poidsDuMois(farmId, projets, m));
                double total = poids.values().stream().mapToDouble(Double::doubleValue).sum();
                if (total > 0 && poids.containsKey(cible.getId())) {
                    pourcentage = poids.get(cible.getId()) / total * 100;
                    partProrata = reste * poids.get(cible.getId()) / total;
                }
            }
            double totalLigne = partAffectation + partProrata;
            if (totalLigne < 0.5) continue;
            lignes.add(CoutMainOeuvreDTO.Ligne.builder()
                    .periode(p.getPeriode())
                    .employeNom(p.getSalaire().getEmploye().getNom())
                    .montantPaye(montant)
                    .partAffectation(Math.round(partAffectation))
                    .joursAffectes(joursCible)
                    .joursDuMois(joursMois)
                    .partProrata(Math.round(partProrata))
                    .pourcentageProrata(Math.round(pourcentage * 10) / 10.0)
                    .total(Math.round(totalLigne))
                    .build());
        }
        lignes.sort((a, b) -> b.getPeriode().compareTo(a.getPeriode()) != 0 ? b.getPeriode().compareTo(a.getPeriode()) : a.getEmployeNom().compareToIgnoreCase(b.getEmployeNom()));
        double total = lignes.stream().mapToDouble(CoutMainOeuvreDTO.Ligne::getTotal).sum();
        return CoutMainOeuvreDTO.builder().total(total).lignes(lignes).build();
    }
}
