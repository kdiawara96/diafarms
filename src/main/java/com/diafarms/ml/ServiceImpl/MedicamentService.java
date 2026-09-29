package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.AchatMedicamentDTO;
import com.diafarms.ml.DTO.StockMedicamentDTO;
import com.diafarms.ml.commons.FermeScope;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.commons.ProjetsFerme;
import com.diafarms.ml.enums.FormeMedicament;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.AchatMedicament;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Soins;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AchatMedicamentRepo;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.SoinsRepo;
import com.diafarms.ml.repository.TransactionRepo;
import com.diafarms.ml.request.create.AchatMedicamentCreate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

// Achats de médicaments et de vaccins, et stock par projet. Même principe que l'achat
// d'aliment : une seule saisie (Comptabilité, Sortie d'argent, Santé / Vétérinaire,
// « Achat de médicament ») fait la dépense (transaction verrouillée, source MEDICAMENT)
// ET l'entrée en stock du projet. Le stock est suivi par produit et unité (nom sans tenir
// compte des majuscules) : acheté - utilisé par les soins pris dans le stock
// (Soins.depuisStock). Règle : ce qui a déjà été utilisé doit rester couvert par les
// achats (quantité minimum, pas de changement de projet ni de suppression sinon).
@Service
@RequiredArgsConstructor
public class MedicamentService {

    private final AchatMedicamentRepo achatRepo;
    private final SoinsRepo soinsRepo;
    private final BatimentRepo batimentRepo;
    private final TransactionRepo transactionRepo;
    private final TransactionService transactionService;
    private final ProjetsFerme projetsFerme;
    private final OtherService otherService;
    private final LogsServices logs;

    public static final String CATEGORIE = "Santé / Vétérinaire";

    private Utilisateurs utilisateur() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    // Même population que l'achat d'aliment (acte financier).
    private void ensureFinance(Utilisateurs u) {
        boolean ok = u != null && u.getRoles() != null && u.getRoles().stream().anyMatch(r ->
                "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole())
                        || "RESPONSABLE".equalsIgnoreCase(r.getRole()) || "COMPTABLE".equalsIgnoreCase(r.getRole()));
        if (!ok) throw new IllegalArgumentException("Seule la finance (comptable, responsable ou administrateur) peut enregistrer, modifier ou supprimer un achat de médicament.");
    }

    public static String cle(String nom, String unite) {
        return (nom == null ? "" : nom.trim().toLowerCase(Locale.FRENCH)) + "|" + (unite == null ? "" : unite.trim().toLowerCase(Locale.FRENCH));
    }

    private static String q(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.FRANCE, "%.2f", v);
    }

    private static FormeMedicament forme(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return FormeMedicament.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Forme inconnue : " + s + " (LIQUIDE, POUDRE, COMPRIME ou AUTRE).");
        }
    }

    private Batiment batiment(String uid, Utilisateurs u) {
        if (uid == null || uid.isBlank()) return null;
        Batiment b = batimentRepo.findByUniqueId(uid);
        if (b == null || !FermeScope.memeFerme(b.getFarm(), u)) throw new IllegalArgumentException("Poulailler introuvable : " + uid);
        return b;
    }

    private AchatMedicament achat(String uid) {
        AchatMedicament a = achatRepo.findByUniqueId(uid)
                .filter(x -> x.getInitialisation() == null || !Boolean.TRUE.equals(x.getInitialisation().getRemoved()))
                .orElseThrow(() -> new IllegalArgumentException("Achat de médicament introuvable : " + uid));
        projetsFerme.verifier(a.getProjet(), "Achat de médicament introuvable : " + uid);
        return a;
    }

    // Quantités achetées et utilisées par clé (produit|unité) pour un projet.
    private Map<String, double[]> soldes(Projets projet, String achatExclu, String soinExclu) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (AchatMedicament a : achatRepo.findActifsByProjetId(projet.getId())) {
            if (a.getUniqueId().equals(achatExclu)) continue;
            m.computeIfAbsent(cle(a.getNom(), a.getUnite()), k -> new double[2])[0] += a.getQuantite();
        }
        for (Soins s : soinsRepo.findDepuisStockByProjetId(projet.getId())) {
            if (s.getUniqueId().equals(soinExclu)) continue;
            m.computeIfAbsent(cle(s.getProduit(), s.getUnite()), k -> new double[2])[1] += s.getQuantite() == null ? 0 : s.getQuantite();
        }
        return m;
    }

    /** Refuse un soin pris dans le stock si le projet n'a pas assez de ce produit. */
    public void verifierConsommation(Projets projet, String produit, String unite, Double quantite, String soinExclu) {
        if (produit == null || produit.isBlank() || unite == null || unite.isBlank()) {
            throw new IllegalArgumentException("Choisissez le médicament du stock (produit et unité).");
        }
        if (quantite == null || quantite <= 0) {
            throw new IllegalArgumentException("Indiquez la quantité utilisée.");
        }
        double[] s = soldes(projet, null, soinExclu).getOrDefault(cle(produit, unite), new double[2]);
        double restant = s[0] - s[1];
        if (s[0] <= 0) {
            throw new IllegalArgumentException("Ce projet n'a pas de « " + produit.trim() + " » (" + unite.trim() + ") en stock : l'achat se saisit en Comptabilité.");
        }
        if (quantite > restant + 1e-9) {
            throw new IllegalArgumentException("Stock insuffisant : il reste " + q(Math.max(0, restant)) + " " + unite.trim()
                    + " de « " + produit.trim() + " » pour ce projet.");
        }
    }

    private void syncTransaction(AchatMedicament a, Utilisateurs u) {
        if (u == null || u.getFarm() == null) return;
        String description = "Achat médicament : " + a.getNom() + " (" + q(a.getQuantite()) + " " + a.getUnite() + "), projet "
                + a.getProjet().getTitre();
        // Santé : liée au projet (et au poulailler s'il est précisé), jamais à un site.
        transactionService.syncSortie(a.getProjet(), u.getFarm(), a.getCoutTotal(), CATEGORIE, a.getDateAchat(), description,
                SourceTransaction.MEDICAMENT, a.getUniqueId(), u, a.getBatiment(), null, true);
        transactionService.updateDateBySource(a.getUniqueId(), a.getDateAchat());
        transactionRepo.findBySourceUniqueId(a.getUniqueId()).ifPresent(t -> {
            t.setQuantite(a.getQuantite());
            t.setPrixUnitaire(a.getPrixUnitaire());
            transactionRepo.save(t);
        });
    }

    private void remplir(AchatMedicament a, AchatMedicamentCreate d, boolean creation, Utilisateurs u) {
        if (creation || d.getNom() != null) {
            if (d.getNom() == null || d.getNom().isBlank()) throw new IllegalArgumentException("Le nom du médicament est obligatoire.");
            a.setNom(d.getNom().trim());
        }
        if (creation || d.getForme() != null) a.setForme(forme(d.getForme()));
        if (creation || d.getUnite() != null) {
            if (d.getUnite() == null || d.getUnite().isBlank()) throw new IllegalArgumentException("L'unité est obligatoire (flacon, ml, sachet, g...).");
            a.setUnite(d.getUnite().trim());
        }
        if (creation || d.getQuantite() != null) {
            if (d.getQuantite() == null || d.getQuantite() <= 0) throw new IllegalArgumentException("La quantité achetée doit être supérieure à 0.");
            a.setQuantite(d.getQuantite());
        }
        if (creation || d.getCoutTotal() != null) {
            if (d.getCoutTotal() == null || d.getCoutTotal() <= 0) throw new IllegalArgumentException("Le montant payé est obligatoire.");
            a.setCoutTotal(d.getCoutTotal());
        }
        if (d.getPrixUnitaire() != null && d.getPrixUnitaire() > 0) {
            a.setPrixUnitaire(d.getPrixUnitaire());
        } else if (creation || d.getQuantite() != null || d.getCoutTotal() != null) {
            a.setPrixUnitaire(Math.round(a.getCoutTotal() / a.getQuantite() * 100.0) / 100.0);
        }
        if (creation || d.getDateAchat() != null) {
            try {
                a.setDateAchat(d.getDateAchat() == null || d.getDateAchat().isBlank() ? LocalDate.now() : LocalDate.parse(d.getDateAchat()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Date invalide (attendu AAAA-MM-JJ) : " + d.getDateAchat());
            }
        }
        if (d.getFournisseur() != null) a.setFournisseur(d.getFournisseur().isBlank() ? null : d.getFournisseur().trim());
        if (d.getObservations() != null) a.setObservations(d.getObservations().isBlank() ? null : d.getObservations().trim());
        if (d.getBatimentUniqueId() != null) a.setBatiment(batiment(d.getBatimentUniqueId(), u));
    }

    @Transactional
    public AchatMedicamentDTO creer(String projetUniqueId, AchatMedicamentCreate d) {
        Utilisateurs u = utilisateur();
        ensureFinance(u);
        Projets projet = projetsFerme.charger(projetUniqueId);
        AchatMedicament a = new AchatMedicament();
        a.setUniqueId("MED-" + java.util.UUID.randomUUID());
        a.setProjet(projet);
        a.setFarm(projet.getFarm());
        a.setInitialisation(Initialisation.init());
        remplir(a, d, true, u);
        AchatMedicament saved = achatRepo.save(a);
        syncTransaction(saved, u);
        if (u != null) logs.addLogs(u.getId(), saved.getId(), "AchatMedicament",
                "Achat de " + q(saved.getQuantite()) + " " + saved.getUnite() + " de " + saved.getNom() + " (" + saved.getCoutTotal() + " FCFA), projet " + projet.getTitre());
        return AchatMedicamentDTO.fromEntity(saved);
    }

    @Transactional
    public AchatMedicamentDTO modifier(String uniqueId, AchatMedicamentCreate d) {
        Utilisateurs u = utilisateur();
        ensureFinance(u);
        AchatMedicament a = achat(uniqueId);
        Projets ancienProjet = a.getProjet();
        String ancienneCle = cle(a.getNom(), a.getUnite());
        Projets nouveauProjet = d.getProjetUniqueId() != null && !d.getProjetUniqueId().isBlank()
                && !d.getProjetUniqueId().equals(ancienProjet.getUniqueId())
                ? projetsFerme.charger(d.getProjetUniqueId()) : ancienProjet;
        remplir(a, d, false, u);
        a.setProjet(nouveauProjet);
        a.setFarm(nouveauProjet.getFarm());
        // Ce que l'ancien projet a déjà utilisé de ce produit doit rester couvert.
        double[] s = soldes(ancienProjet, a.getUniqueId(), null).getOrDefault(ancienneCle, new double[2]);
        boolean memeStock = nouveauProjet.getId().equals(ancienProjet.getId()) && cle(a.getNom(), a.getUnite()).equals(ancienneCle);
        double reste = s[0] + (memeStock ? a.getQuantite() : 0);
        if (reste + 1e-9 < s[1]) {
            if (!memeStock) {
                throw new IllegalArgumentException("Une partie de ce médicament a déjà été utilisée par des soins du projet (" + q(s[1])
                        + ") : impossible de changer le produit, l'unité ou le projet de cet achat.");
            }
            throw new IllegalArgumentException("Impossible de réduire cet achat à " + q(a.getQuantite()) + " : les soins du projet en ont déjà utilisé "
                    + q(s[1]) + ", le minimum pour cet achat est " + q(s[1] - s[0]) + ".");
        }
        a.setInitialisation(Initialisation.updateDate(a.getInitialisation()));
        AchatMedicament saved = achatRepo.save(a);
        syncTransaction(saved, u);
        if (u != null) logs.addLogs(u.getId(), saved.getId(), "AchatMedicament", "Modification de l'achat de " + saved.getNom());
        return AchatMedicamentDTO.fromEntity(saved);
    }

    @Transactional
    public void supprimer(String uniqueId) {
        Utilisateurs u = utilisateur();
        ensureFinance(u);
        AchatMedicament a = achat(uniqueId);
        double[] s = soldes(a.getProjet(), a.getUniqueId(), null).getOrDefault(cle(a.getNom(), a.getUnite()), new double[2]);
        if (s[0] + 1e-9 < s[1]) {
            throw new IllegalArgumentException("Impossible de supprimer cet achat : les soins du projet en ont déjà utilisé " + q(s[1]) + " "
                    + a.getUnite() + ". Vous pouvez seulement réduire la quantité jusqu'à " + q(s[1] - s[0]) + ".");
        }
        a.getInitialisation().setRemoved(true);
        a.setInitialisation(Initialisation.updateDate(a.getInitialisation()));
        achatRepo.save(a);
        transactionService.setRemovedBySource(a.getUniqueId(), true);
        if (u != null) logs.addLogs(u.getId(), a.getId(), "AchatMedicament", "Suppression de l'achat de " + a.getNom());
    }

    @Transactional(readOnly = true)
    public AchatMedicamentDTO detail(String uniqueId) {
        return AchatMedicamentDTO.fromEntity(achat(uniqueId));
    }

    @Transactional(readOnly = true)
    public List<AchatMedicamentDTO> parProjet(String projetUniqueId) {
        Projets p = projetsFerme.charger(projetUniqueId);
        return achatRepo.findActifsByProjetId(p.getId()).stream().map(AchatMedicamentDTO::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public List<AchatMedicamentDTO> recents(int jours) {
        Utilisateurs u = utilisateur();
        if (u == null || u.getFarm() == null) return List.of();
        return achatRepo.findRecentsByFarmId(u.getFarm().getId(), LocalDate.now().minusDays(Math.max(1, jours)))
                .stream().map(AchatMedicamentDTO::fromEntity).toList();
    }

    /** Stock du projet par produit et unité (produits achetés ; restant peut être 0). */
    @Transactional(readOnly = true)
    public List<StockMedicamentDTO> stock(String projetUniqueId) {
        Projets p = projetsFerme.charger(projetUniqueId);
        Map<String, StockMedicamentDTO> m = new LinkedHashMap<>();
        for (AchatMedicament a : achatRepo.findActifsByProjetId(p.getId())) {
            StockMedicamentDTO s = m.computeIfAbsent(cle(a.getNom(), a.getUnite()), k -> StockMedicamentDTO.builder()
                    .nom(a.getNom()).unite(a.getUnite()).forme(a.getForme() != null ? a.getForme().name() : null).build());
            s.setAchete(s.getAchete() + a.getQuantite());
        }
        for (Soins so : soinsRepo.findDepuisStockByProjetId(p.getId())) {
            StockMedicamentDTO s = m.get(cle(so.getProduit(), so.getUnite()));
            if (s != null) s.setUtilise(s.getUtilise() + (so.getQuantite() == null ? 0 : so.getQuantite()));
        }
        List<StockMedicamentDTO> liste = new ArrayList<>(m.values());
        liste.forEach(s -> s.setRestant(Math.round((s.getAchete() - s.getUtilise()) * 100.0) / 100.0));
        return liste;
    }
}
