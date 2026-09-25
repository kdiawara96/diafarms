package com.diafarms.ml.ServiceImpl;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.OccupationBatimentDTO;
import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.commons.PdfStyle;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Alimentation;
import com.diafarms.ml.models.CollecteOeufs;
import com.diafarms.ml.models.ConsommationAliment;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Mortalite;
import com.diafarms.ml.models.Reforme;
import com.diafarms.ml.models.Soins;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.AlimentationRepo;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.ConsommationAlimentRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.repository.SoinsRepo;
import com.diafarms.ml.services.InvestissementService;
import com.diafarms.ml.services.MinioService;
import com.diafarms.ml.services.ProjetRapportPdfService;
import com.diafarms.ml.services.ProjetServices;
import com.diafarms.ml.services.TransactionService;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

// Rapport PDF d'UN projet, généré à la demande depuis la Fiche Projet. Aucune nouvelle
// saisie : tout est agrégé à partir de ce qui existe déjà (production, aliment, santé,
// transactions). Même identité visuelle que les factures/bulletins (PdfStyle).
@Service
@RequiredArgsConstructor
public class ProjetRapportPdfServiceImpl implements ProjetRapportPdfService {

    private static final Set<String> CATEGORIES_SANTE = Set.of("santé / vétérinaire", "vaccination", "soins");
    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ProjetServices projetServices;
    private final CollecteOeufsRepo collecteOeufsRepo;
    private final MortaliteRepo mortaliteRepo;
    private final ReformeRepo reformeRepo;
    private final ConsommationAlimentRepo consommationAlimentRepo;
    private final AlimentationRepo alimentationRepo;
    private final SoinsRepo soinsRepo;
    private final TransactionService transactionService;
    private final InvestissementService investissementService;
    private final MinioService minioService;
    private final OtherService otherService;
    private final com.diafarms.ml.repository.ProjetsRepo projetsRepo;

    // ---------- formats (espace simple comme séparateur de milliers : l'espace fine
    // insécable de Locale.FRANCE n'existe pas dans la police PDF standard) ----------
    private static DecimalFormat nombre(int decimales) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.FRANCE);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat(decimales > 0 ? "#,##0." + "0".repeat(decimales) : "#,##0", sym);
        f.setGroupingUsed(true);
        return f;
    }

    private static String n0(double v) { return nombre(0).format(v); }
    private static String n1(double v) { return nombre(1).format(v); }
    private static String fcfa(double v) { return nombre(0).format(Math.round(v)) + " FCFA"; }
    private static double nz(Double v) { return v == null ? 0 : v; }
    private static int nz(Integer v) { return v == null ? 0 : v; }
    private static boolean dans(LocalDate d, LocalDate debut, LocalDate fin) {
        return d != null && !d.isBefore(debut) && !d.isAfter(fin);
    }

    private Image chargerImage(String nomMinio) {
        if (nomMinio == null) return null;
        try (java.io.InputStream stream = minioService.downloadFile(nomMinio)) {
            return Image.getInstance(stream.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    private static Paragraph section(String titre) {
        Paragraph p = new Paragraph(titre.toUpperCase(), PdfStyle.sectionLabel());
        p.setSpacingBefore(14f);
        p.setSpacingAfter(4f);
        return p;
    }

    /** Tableau à 2 colonnes libellé / valeur. */
    private static PdfPTable kv(String[][] lignes) throws Exception {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{62, 38});
        for (String[] l : lignes) {
            PdfPCell a = PdfStyle.bodyCell(l[0]);
            PdfPCell b = PdfStyle.bodyCell(l[1], Element.ALIGN_RIGHT);
            b.setPhrase(new Paragraph(l[1], PdfStyle.bold()));
            t.addCell(a);
            t.addCell(b);
        }
        return t;
    }

    private static Paragraph note(String texte) {
        Paragraph p = new Paragraph(texte, PdfStyle.small());
        p.setSpacingBefore(3f);
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generer(String projetUniqueId, LocalDate dateDebut, LocalDate dateFin) {
        Utilisateurs currentUser = otherService.getCurrentUser();
        ProjetsDTO projet = projetServices.getProjetByUniqueId(projetUniqueId);
        Farm farm = currentUser != null ? currentUser.getFarm() : null;
        // Isolation des fermes : un projet d'une autre ferme n'est jamais rapportable.
        if (farm == null || projet == null || !farmAppartient(projetUniqueId, farm)) {
            throw new IllegalArgumentException("Projet introuvable : " + projetUniqueId);
        }

        LocalDate debut = dateDebut != null ? dateDebut : (projet.getDebut() != null ? projet.getDebut() : LocalDate.now());
        LocalDate fin = dateFin != null ? dateFin : LocalDate.now();
        if (fin.isBefore(debut)) throw new IllegalArgumentException("La date de fin est avant la date de début.");
        long jours = java.time.temporal.ChronoUnit.DAYS.between(debut, fin) + 1;

        Long projetId = projetIdParUniqueId(projetUniqueId);

        // ===== Production =====
        int oeufs = 0, casses = 0, nonUtilisables = 0;
        java.util.Set<LocalDate> joursCollecte = new java.util.HashSet<>();
        for (CollecteOeufs c : collecteOeufsRepo.findAllByProjetId(projetId)) {
            if (!dans(c.getDate(), debut, fin)) continue;
            oeufs += nz(c.getOeufsCollectes());
            casses += nz(c.getOeufsCasses());
            nonUtilisables += nz(c.getOeufsNonUtilisables());
            joursCollecte.add(c.getDate());
        }
        int bonEtat = Math.max(0, com.diafarms.ml.commons.StockOeufsRegle.bonEtat(oeufs, casses, nonUtilisables));
        int morts = 0;
        for (Mortalite m : mortaliteRepo.findAllByProjetId(projetId)) {
            if (dans(m.getDate(), debut, fin)) morts += nz(m.getNombreMorts());
        }
        int reformes = 0;
        for (Reforme r : reformeRepo.findAllByProjetId(projetId)) {
            if (dans(r.getDate(), debut, fin)) reformes += nz(r.getNombreSujets());
        }
        int effectifVivant = nz(projet.getEffectifVivant());
        double moyenneOeufsJour = joursCollecte.isEmpty() ? 0 : (double) oeufs / joursCollecte.size();
        double tauxPonteMoyen = (effectifVivant > 0 && !joursCollecte.isEmpty()) ? moyenneOeufsJour / effectifVivant * 100 : 0;

        // ===== Aliment =====
        double kgAchetesPeriode = 0, coutAchatsPeriode = 0, kgAchetesTotal = 0, coutAchatsTotal = 0;
        List<Alimentation> achats = alimentationRepo.findByProjetUniqueIdAndInitialisationRemovedFalse(projetUniqueId);
        for (Alimentation a : achats) {
            if (a.getDateDistribution() == null || a.getDateDistribution().isAfter(fin)) continue;
            kgAchetesTotal += nz(a.getQuantiteKg());
            coutAchatsTotal += nz(a.getCoutTotal());
            if (dans(a.getDateDistribution(), debut, fin)) {
                kgAchetesPeriode += nz(a.getQuantiteKg());
                coutAchatsPeriode += nz(a.getCoutTotal());
            }
        }
        double kgConsommesPeriode = 0, kgConsommesTotal = 0;
        for (ConsommationAliment c : consommationAlimentRepo.findAllByProjetId(projetId)) {
            if (c.getDate() == null || c.getDate().isAfter(fin)) continue;
            kgConsommesTotal += nz(c.getQuantiteKg());
            if (dans(c.getDate(), debut, fin)) kgConsommesPeriode += nz(c.getQuantiteKg());
        }
        double prixMoyenKg = kgAchetesTotal > 0 ? coutAchatsTotal / kgAchetesTotal : 0;
        double coutConsoEstime = kgConsommesPeriode * prixMoyenKg;
        double stockRestant = kgAchetesTotal - kgConsommesTotal;
        double grammesParPouleJour = (effectifVivant > 0 && jours > 0) ? kgConsommesPeriode * 1000 / effectifVivant / jours : 0;

        // ===== Santé =====
        List<Soins> soins = new ArrayList<>();
        for (Soins s : soinsRepo.findByProjetUniqueIdAndInitialisationRemovedFalse(projetUniqueId)) {
            if (dans(s.getDate(), debut, fin)) soins.add(s);
        }
        soins.sort(Comparator.comparing(Soins::getDate));

        // ===== Finances : transactions VALIDÉES directement rattachées au projet =====
        List<TransactionDTO> transactions = new ArrayList<>();
        int page = 0;
        int totalPages = 1;
        do {
            PaginatedResponse<TransactionDTO> res = transactionService.list(page, 500, null, null, StatutTransaction.VALIDE,
                    projetUniqueId, null, null, debut, fin);
            if (res.getData() != null) transactions.addAll(res.getData());
            totalPages = Math.max(1, res.getTotalPages());
            page++;
        } while (page < totalPages && page < 40);
        double recettesReelles = 0, recettesTheoriques = 0, depensesTotal = 0, depensesSante = 0;
        Map<String, Double> depensesParCategorie = new LinkedHashMap<>();
        for (TransactionDTO t : transactions) {
            if (!projetUniqueId.equals(t.getProjetUniqueId())) continue; // pas les "commun" simplement tagués
            if (t.getType() == TypeTransaction.ENTREE) {
                recettesTheoriques += nz(t.getMontant());
                recettesReelles += t.getMontantReel() != null ? t.getMontantReel() : nz(t.getMontant());
            } else if (t.getType() == TypeTransaction.SORTIE) {
                double m = nz(t.getMontant());
                depensesTotal += m;
                String cat = t.getCategorie() == null || t.getCategorie().isBlank() ? "Sans catégorie" : t.getCategorie();
                depensesParCategorie.merge(cat, m, Double::sum);
                if (CATEGORIES_SANTE.contains(cat.toLowerCase(Locale.ROOT))) depensesSante += m;
            }
        }
        double resultatNet = recettesReelles - depensesTotal;
        Double amortissement = null;
        try { amortissement = investissementService.getCoutAmortissementProjet(projetUniqueId); } catch (Exception ignored) { }

        // ===== Construction du document =====
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 45, 45, 40, 45);
            PdfWriter.getInstance(document, out);
            document.open();

            PdfPTable header = new PdfPTable(2);
            header.setWidthPercentage(100);
            header.setWidths(new float[]{1, 1});
            List<Element> gauche = new ArrayList<>();
            Image logo = chargerImage(farm.getLogoNomMinio());
            if (logo != null) { logo.scaleToFit(140, 70); gauche.add(logo); }
            gauche.addAll(PdfStyle.farmBlockLines(farm.getNom(), farm.getQuartier(), farm.getVille(), farm.getPays(),
                    farm.getTelephone1(), farm.getTelephone2(), farm.getEmail()));
            header.addCell(PdfStyle.layoutCell(gauche.toArray(new Element[0])));
            Paragraph titre = new Paragraph("RAPPORT DE PROJET", PdfStyle.title());
            titre.setAlignment(Element.ALIGN_RIGHT);
            Paragraph code = new Paragraph(projet.getCode() + " : " + projet.getTitre(), PdfStyle.bold());
            code.setAlignment(Element.ALIGN_RIGHT);
            Paragraph periode = new Paragraph("Période du " + debut.format(DATE_FR) + " au " + fin.format(DATE_FR)
                    + " (" + jours + " jour" + (jours > 1 ? "s" : "") + ")", PdfStyle.small());
            periode.setAlignment(Element.ALIGN_RIGHT);
            header.addCell(PdfStyle.layoutCell(titre, code, periode));
            document.add(header);
            document.add(new Paragraph(" "));
            document.add(PdfStyle.colorBand(3f));

            // --- Identité ---
            document.add(section("Le projet"));
            List<String[]> id = new ArrayList<>();
            id.add(new String[]{"Race / objectif", (projet.getRace() != null ? projet.getRace().getNom() : "-") + " / "
                    + (projet.getObjectif() != null ? projet.getObjectif().name() : "-")});
            if (projet.getSiteNom() != null) id.add(new String[]{"Site", projet.getSiteNom()});
            id.add(new String[]{"Début / fin prévue", (projet.getDebut() != null ? projet.getDebut().format(DATE_FR) : "-") + " / "
                    + (projet.getFinPrevue() != null ? projet.getFinPrevue().format(DATE_FR) : "-")});
            if (projet.getResponsableNom() != null) id.add(new String[]{"Responsable", projet.getResponsableNom()});
            if (projet.getResponsableProductionNom() != null) id.add(new String[]{"Responsable production", projet.getResponsableProductionNom()});
            if (projet.getResponsableFinanceNom() != null) id.add(new String[]{"Responsable finance", projet.getResponsableFinanceNom()});
            id.add(new String[]{"Sujets au départ", n0(nz(projet.getNbSujets()))});
            id.add(new String[]{"Sujets vivants aujourd'hui", n0(effectifVivant)});
            id.add(new String[]{"Mortalité cumulée / sujets réformés", n1(nz(projet.getMortaliteCumulee())) + " % / " + n0(nz(projet.getSujetsReformesCumulee()))});
            if (projet.getOccupationBatiment() != null && !projet.getOccupationBatiment().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (OccupationBatimentDTO o : projet.getOccupationBatiment()) {
                    if (o.getDateSortie() != null) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(o.getNomBatiment()).append(o.getNbSujetsDansBatiment() != null ? " (" + n0(o.getNbSujetsDansBatiment()) + ")" : "");
                }
                if (sb.length() > 0) id.add(new String[]{"Poulaillers occupés (sujets au départ)", sb.toString()});
            }
            document.add(kv(id.toArray(new String[0][])));

            // --- Production ---
            document.add(section("Production sur la période"));
            document.add(kv(new String[][]{
                    {"Œufs collectés (" + joursCollecte.size() + " jour(s) de collecte)", n0(oeufs)},
                    {"Dont bon état / cassés / non utilisables", n0(bonEtat) + " / " + n0(casses) + " / " + n0(nonUtilisables)},
                    {"Équivalent en alvéoles (30 œufs) du bon état", n1(bonEtat / 30.0)},
                    {"Moyenne d'œufs par jour de collecte", n0(moyenneOeufsJour)},
                    {"Taux de ponte moyen (sur l'effectif vivant actuel)", n1(tauxPonteMoyen) + " %"},
                    {"Mortalité sur la période", n0(morts) + " sujet(s)"},
                    {"Sujets réformés sur la période", n0(reformes)},
            }));

            // --- Aliment ---
            document.add(section("Aliment"));
            document.add(kv(new String[][]{
                    {"Aliment acheté sur la période", n0(kgAchetesPeriode) + " kg"},
                    {"Coût des achats sur la période", fcfa(coutAchatsPeriode)},
                    {"Aliment consommé sur la période", n1(kgConsommesPeriode) + " kg"},
                    {"Prix moyen d'achat au kilo (achats depuis le début)", prixMoyenKg > 0 ? fcfa(prixMoyenKg) : "-"},
                    {"Coût estimé de l'aliment consommé", prixMoyenKg > 0 ? fcfa(coutConsoEstime) : "-"},
                    {"Consommation moyenne par poule et par jour", n0(grammesParPouleJour) + " g"},
                    {"Stock d'aliment restant (achats moins consommation, à la date de fin)", n1(stockRestant) + " kg"},
            }));
            document.add(note("Le coût de l'aliment consommé est une estimation : kilos consommés multipliés par le prix moyen d'achat au kilo. Le coût réel payé est celui des achats."));

            // --- Santé ---
            document.add(section("Santé / vétérinaire"));
            if (soins.isEmpty()) {
                document.add(new Paragraph("Aucune saisie de santé sur la période.", PdfStyle.normal()));
            } else {
                PdfPTable t = new PdfPTable(4);
                t.setWidthPercentage(100);
                t.setWidths(new float[]{16, 22, 42, 20});
                t.addCell(PdfStyle.tableHeaderCell("Date"));
                t.addCell(PdfStyle.tableHeaderCell("Type"));
                t.addCell(PdfStyle.tableHeaderCell("Produit"));
                t.addCell(PdfStyle.tableHeaderCell("Quantité"));
                for (Soins s : soins) {
                    String type = switch (s.getType()) {
                        case VACCINATION -> "Vaccination";
                        case MEDICAMENT -> "Médicament";
                        default -> "Autre";
                    };
                    t.addCell(PdfStyle.bodyCell(s.getDate().format(DATE_FR)));
                    t.addCell(PdfStyle.bodyCell(type));
                    t.addCell(PdfStyle.bodyCell(s.getProduit() != null ? s.getProduit() : "-"));
                    t.addCell(PdfStyle.bodyCell(s.getQuantite() != null ? n0(s.getQuantite()) : "-", Element.ALIGN_RIGHT));
                }
                document.add(t);
            }
            document.add(kv(new String[][]{{"Coût santé / vétérinaire (dépenses validées de la catégorie)", fcfa(depensesSante)}}));

            // --- Finances ---
            document.add(section("Finances (transactions validées du projet)"));
            document.add(kv(new String[][]{
                    {"Recettes encaissées", fcfa(recettesReelles)},
                    {"Recettes théoriques (valeur des ventes)", fcfa(recettesTheoriques)},
                    {"Dépenses", fcfa(depensesTotal)},
                    {"Résultat net (recettes encaissées moins dépenses)", (resultatNet >= 0 ? "+" : "-") + fcfa(Math.abs(resultatNet))},
            }));
            if (!depensesParCategorie.isEmpty()) {
                Paragraph sous = new Paragraph("Dépenses par catégorie", PdfStyle.bold());
                sous.setSpacingBefore(8f);
                sous.setSpacingAfter(3f);
                document.add(sous);
                PdfPTable t = new PdfPTable(3);
                t.setWidthPercentage(100);
                t.setWidths(new float[]{50, 30, 20});
                t.addCell(PdfStyle.tableHeaderCell("Catégorie"));
                t.addCell(PdfStyle.tableHeaderCell("Montant"));
                t.addCell(PdfStyle.tableHeaderCell("Part"));
                final double totalDep = depensesTotal;
                depensesParCategorie.entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .forEach(e -> {
                            t.addCell(PdfStyle.bodyCell(e.getKey()));
                            t.addCell(PdfStyle.bodyCell(fcfa(e.getValue()), Element.ALIGN_RIGHT));
                            t.addCell(PdfStyle.bodyCell(totalDep > 0 ? n1(e.getValue() / totalDep * 100) + " %" : "-", Element.ALIGN_RIGHT));
                        });
                document.add(t);
            }
            if (amortissement != null && amortissement > 0) {
                document.add(note("Amortissement des investissements alloué à ce projet : " + fcfa(amortissement)
                        + " (suivi de rentabilité, non compté dans les dépenses ci-dessus)."));
            }
            document.add(note("Les dépenses communes à plusieurs projets ne sont pas réparties : seules les transactions rattachées directement à ce projet sont comptées."));

            Paragraph pied = new Paragraph("Document généré par Diafarms le " + LocalDate.now().format(DATE_FR), PdfStyle.small());
            pied.setSpacingBefore(18f);
            pied.setAlignment(Element.ALIGN_CENTER);
            document.add(pied);
            Image tampon = chargerImage(farm.getTamponNomMinio());
            if (tampon != null) {
                tampon.scaleToFit(90, 90);
                tampon.setAlignment(Element.ALIGN_RIGHT);
                document.add(tampon);
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la génération du rapport PDF : " + e.getMessage(), e);
        }
    }

    // ---- accès aux entités (le DTO ne porte pas l'id numérique ni la ferme) ----
    private Long projetIdParUniqueId(String uniqueId) {
        return projetsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + uniqueId)).getId();
    }

    private boolean farmAppartient(String uniqueId, Farm farm) {
        return projetsRepo.findByUniqueId(uniqueId)
                .map(p -> p.getFarm() != null && p.getFarm().getId().equals(farm.getId()))
                .orElse(false);
    }
}
