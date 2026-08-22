package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.RapportJournalierDTO;
import com.diafarms.ml.DTO.RapportJournalierLigneDTO;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.CollecteOeufs;
import com.diafarms.ml.models.Mortalite;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Reforme;
import com.diafarms.ml.models.Transaction;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.repository.TransactionRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.services.RapportJournalierService;

import lombok.RequiredArgsConstructor;

// Reconstitue, jour par jour depuis le début du projet, le tableau de suivi
// production+finance qu'une ferme cliente tenait auparavant à la main (fichier Excel
// "Poulailler" fourni par Hamidou Diawara) — à partir des vraies données déjà en base
// (CollecteOeufs, Mortalite, Reforme, Transaction), sans aucune nouvelle saisie. Voir
// Paramètres > "Export Hamidou Diawara" côté web pour le déclencheur.
@Service
@RequiredArgsConstructor
public class RapportJournalierServiceImpl implements RapportJournalierService {

    private final ProjetsRepo projetsRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;
    private final MortaliteRepo mortaliteRepo;
    private final ReformeRepo reformeRepo;
    private final TransactionRepo transactionRepo;
    private final VenteOeufsRepo venteOeufsRepo;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private int nz(Integer v) { return v == null ? 0 : v; }
    private double nz(Double v) { return v == null ? 0.0 : v; }

    @Override
    @Transactional(readOnly = true)
    public RapportJournalierDTO genererPourProjet(String projetUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));
        if (projet.getFarm() == null || !projet.getFarm().getId().equals(currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Projet introuvable : " + projetUniqueId);
        }
        if (projet.getDebut() == null) {
            throw new IllegalArgumentException("Ce projet n'a pas de date de début.");
        }

        Map<LocalDate, Integer> collectesParJour = new HashMap<>();
        Map<LocalDate, Integer> cassesParJour = new HashMap<>();
        Map<LocalDate, Integer> nonUtilisablesParJour = new HashMap<>();
        for (CollecteOeufs c : collecteOeufsRepo.findAllByProjetId(projet.getId())) {
            if (c.getDate() == null) continue;
            collectesParJour.merge(c.getDate(), nz(c.getOeufsCollectes()), Integer::sum);
            cassesParJour.merge(c.getDate(), nz(c.getOeufsCasses()), Integer::sum);
            nonUtilisablesParJour.merge(c.getDate(), nz(c.getOeufsNonUtilisables()), Integer::sum);
        }

        Map<LocalDate, Integer> mortsParJour = new HashMap<>();
        for (Mortalite m : mortaliteRepo.findAllByProjetId(projet.getId())) {
            if (m.getDate() == null) continue;
            mortsParJour.merge(m.getDate(), nz(m.getNombreMorts()), Integer::sum);
        }

        Map<LocalDate, Integer> reformesParJour = new HashMap<>();
        for (Reforme r : reformeRepo.findAllByProjetId(projet.getId())) {
            if (r.getDate() == null) continue;
            reformesParJour.merge(r.getDate(), nz(r.getNombreSujets()), Integer::sum);
        }

        Map<LocalDate, Double> ejaParJour = new HashMap<>();
        Map<LocalDate, Double> rjParJour = new HashMap<>();
        Map<LocalDate, Double> djaParJour = new HashMap<>();
        Map<LocalDate, Double> adjParJour = new HashMap<>();
        Map<LocalDate, List<String>> commentairesParJour = new HashMap<>();
        double depensesInitiales = 0.0;
        for (Transaction t : transactionRepo.findAllValideByProjetId(projet.getId())) {
            if (t.getDate() == null) continue;
            double montant = nz(t.getMontant());
            // Achat sujets / Autres charges = capital initial (voir depensesInitiales
            // ci-dessous), jamais reversé dans une dépense journalière récurrente.
            if (t.getSourceType() == SourceTransaction.PROJET_ACHAT_SUJETS || t.getSourceType() == SourceTransaction.PROJET_CHARGES) {
                depensesInitiales += montant;
                continue;
            }
            if (t.getType() == TypeTransaction.ENTREE) {
                ejaParJour.merge(t.getDate(), montant, Double::sum);
                if (t.getSourceType() == SourceTransaction.VENTE_OEUFS || t.getSourceType() == SourceTransaction.VENTE_REFORME) {
                    rjParJour.merge(t.getDate(), montant, Double::sum);
                }
            } else {
                if (t.getSourceType() == SourceTransaction.ALIMENTATION) {
                    djaParJour.merge(t.getDate(), montant, Double::sum);
                } else {
                    adjParJour.merge(t.getDate(), montant, Double::sum);
                }
                if (t.getDescription() != null && !t.getDescription().isBlank()) {
                    commentairesParJour.computeIfAbsent(t.getDate(), d -> new ArrayList<>()).add(t.getDescription());
                }
            }
        }

        // Dernier prix de vente d'œufs BON connu, farm-wide (la vente n'est pas
        // rattachée à un seul projet), reporté jour après jour tant qu'aucune vente
        // plus récente n'est connue — voir VenteOeufsRepo.findAllBonByFarmIdOrderByDateAsc.
        List<VenteOeufs> ventesBon = venteOeufsRepo.findAllBonByFarmIdOrderByDateAsc(currentUser.getFarm().getId());
        int indexVente = 0;
        Double dernierPrixConnu = null;

        int nbSujets = nz(projet.getNbSujets());
        LocalDate debut = projet.getDebut();
        LocalDate fin = LocalDate.now();

        List<RapportJournalierLigneDTO> lignes = new ArrayList<>();
        int npmCumule = 0;
        int oeufsBonsCumule = 0;
        int oeufsCassesCumule = 0;
        int mortsEtReformesCumules = 0;
        double cumulDJ = 0.0;
        Double resteAAmortirPrecedent = null;

        for (LocalDate date = debut; !date.isAfter(fin); date = date.plusDays(1)) {
            int npm = nz(mortsParJour.get(date));
            int reforme = nz(reformesParJour.get(date));
            int nto = nz(collectesParJour.get(date));
            int nec = nz(cassesParJour.get(date));
            int nonUtil = nz(nonUtilisablesParJour.get(date));

            mortsEtReformesCumules += npm + reforme;
            int npr = Math.max(0, nbSujets - mortsEtReformesCumules);
            double tp = npr > 0 ? (double) nto / npr : 0.0;

            npmCumule += npm;
            int oeufsBonsDuJour = Math.max(0, nto - nec - nonUtil);
            oeufsBonsCumule += oeufsBonsDuJour;
            oeufsCassesCumule += nec;

            double nja = oeufsBonsDuJour / 30.0;

            while (indexVente < ventesBon.size() && !ventesBon.get(indexVente).getDate().isAfter(date)) {
                dernierPrixConnu = ventesBon.get(indexVente).getPrixUnitaire();
                indexVente++;
            }
            double pua = nz(dernierPrixConnu);
            double mpj = nja * pua;

            double rj = nz(rjParJour.get(date));
            double eja = nz(ejaParJour.get(date));
            double dja = nz(djaParJour.get(date));
            double adj = nz(adjParJour.get(date));
            double dj = eja - (dja + adj);
            cumulDJ += dj;

            double resteAAmortir = Math.max(0.0, depensesInitiales - cumulDJ);
            double tv = (resteAAmortirPrecedent != null && resteAAmortirPrecedent > 0)
                    ? ((resteAAmortirPrecedent - resteAAmortir) / resteAAmortirPrecedent) * 100.0
                    : 0.0;
            resteAAmortirPrecedent = resteAAmortir;

            List<String> commentairesJour = commentairesParJour.get(date);
            String commentaires = commentairesJour != null ? String.join(", ", commentairesJour) : "";

            lignes.add(RapportJournalierLigneDTO.builder()
                    .date(date)
                    .nombrePoulesRestantes(npr)
                    .nombrePoulesMortes(npm)
                    .nombreTotalOeufs(nto)
                    .tauxPonte(tp)
                    .nombreOeufsCasses(nec)
                    .nombrePoulesMortesCumule(npmCumule)
                    .oeufsBonsCumule(oeufsBonsCumule)
                    .oeufsCassesCumule(oeufsCassesCumule)
                    .nombreAlveolesJournalier(nja)
                    .prixUnitaireAlveole(pua)
                    .montantPotentielJournalier(mpj)
                    .recetteJournaliere(rj)
                    .entreeJournaliereArgent(eja)
                    .depenseJournaliereAliment(dja)
                    .autresDepensesJournalieres(adj)
                    .differenceJournaliere(dj)
                    .cumulDifferenceJournaliere(cumulDJ)
                    .resteAAmortir(resteAAmortir)
                    .tauxVariation(tv)
                    .commentaires(commentaires)
                    .build());
        }

        return RapportJournalierDTO.builder()
                .projetCode(projet.getCode())
                .projetTitre(projet.getTitre())
                .nbSujetsDepart(nbSujets)
                .depensesInitiales(depensesInitiales)
                .lignes(lignes)
                .build();
    }
}
