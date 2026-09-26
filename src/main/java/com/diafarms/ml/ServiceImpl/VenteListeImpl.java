package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.VenteLigneDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.ProduitVenteDiverse;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeVenteOeufs;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteDiverse;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteOeufsRepartition;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.models.VenteReformeRepartition;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.TransactionRepo;
import com.diafarms.ml.repository.VenteDiverseRepo;
import com.diafarms.ml.repository.VenteOeufsRepartitionRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;
import com.diafarms.ml.repository.VenteReformeRepo;

import lombok.RequiredArgsConstructor;

// Page Ventes : chaque vente est lue dans SA table (œufs, réforme, diverses), plus
// reconstruite depuis les transactions — la comptabilité reste le grand livre, la vente
// reste l'original. Mêmes restrictions de visibilité que TransactionServiceImpl.list :
// un VENTE pur ne voit que ses ventes, un ADMIN ou COMPTABLE pur peut filtrer par
// vendeur, un RESPONSABLE pur ne voit que les ventes auxquelles ses projets ont contribué.
@Service
@RequiredArgsConstructor
public class VenteListeImpl {

    private static final LocalDate DATE_MIN = LocalDate.of(1900, 1, 1);
    private static final LocalDate DATE_MAX = LocalDate.of(2999, 12, 31);
    // Taille des listes IN envoyées à Postgres (limite de paramètres par requête).
    private static final int LOT = 1000;

    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteReformeRepo venteReformeRepo;
    private final VenteDiverseRepo venteDiverseRepo;
    private final VenteOeufsRepartitionRepo venteOeufsRepartitionRepo;
    private final VenteReformeRepartitionRepo venteReformeRepartitionRepo;
    private final TransactionRepo transactionRepo;
    private final ProjetsRepo projetsRepo;
    private final OtherService otherService;
    private final CompteClientService compteClientService;

    private boolean isAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private boolean isPureRole(Utilisateurs u, String role) {
        return u != null && u.getRoles() != null && !u.getRoles().isEmpty()
                && u.getRoles().stream().allMatch(r -> role.equalsIgnoreCase(r.getRole()));
    }

    private static <T, R> List<R> parLots(List<T> ids, Function<List<T>, List<R>> requete) {
        List<R> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += LOT) {
            out.addAll(requete.apply(ids.subList(i, Math.min(ids.size(), i + LOT))));
        }
        return out;
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    @Transactional(readOnly = true)
    public List<VenteLigneDTO> list(LocalDate dateDebut, LocalDate dateFin, String vendeurUniqueId) {
        Utilisateurs currentUser;
        try {
            currentUser = otherService.getCurrentUser();
        } catch (Exception e) {
            currentUser = null;
        }
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Long farmId = currentUser.getFarm().getId();
        LocalDate deb = dateDebut != null ? dateDebut : DATE_MIN;
        LocalDate fin = dateFin != null ? dateFin : DATE_MAX;

        // Axe vendeur (voir TransactionServiceImpl.resolveVendeurScopeForList).
        String vendeurScope = null;
        if (isAdmin(currentUser) || isPureRole(currentUser, "COMPTABLE")) {
            vendeurScope = vendeurUniqueId == null || vendeurUniqueId.isBlank() ? null : vendeurUniqueId;
        } else if (isPureRole(currentUser, "VENTE")) {
            vendeurScope = currentUser.getUniqueId();
        }
        // Axe projet : un RESPONSABLE pur ne voit que les ventes de SES projets.
        Set<Long> projetsAutorises = isPureRole(currentUser, "RESPONSABLE")
                ? new HashSet<>(projetsRepo.findProjetIdsAssignedAsResponsableToUser(farmId, currentUser.getUniqueId()))
                : null;

        List<VenteLigneDTO> lignes = new ArrayList<>();

        // --- Œufs ---
        List<VenteOeufs> oeufs = venteOeufsRepo.findActivesPourListe(farmId, deb, fin);
        Map<Long, List<VenteOeufsRepartition>> repOeufs = new HashMap<>();
        for (VenteOeufsRepartition r : parLots(oeufs.stream().map(VenteOeufs::getId).toList(),
                venteOeufsRepartitionRepo::findByVenteIds)) {
            repOeufs.computeIfAbsent(r.getVenteOeufs().getId(), k -> new ArrayList<>()).add(r);
        }
        // --- Réforme ---
        List<VenteReforme> reformes = venteReformeRepo.findActivesPourListe(farmId, deb, fin);
        Map<Long, List<VenteReformeRepartition>> repReforme = new HashMap<>();
        for (VenteReformeRepartition r : parLots(reformes.stream().map(VenteReforme::getId).toList(),
                venteReformeRepartitionRepo::findByVenteIds)) {
            repReforme.computeIfAbsent(r.getVenteReforme().getId(), k -> new ArrayList<>()).add(r);
        }
        // --- Diverses (communes : jamais visibles pour un RESPONSABLE pur, comme leurs
        // transactions communes dans sa Comptabilité) ---
        List<VenteDiverse> diverses = projetsAutorises != null ? List.of()
                : venteDiverseRepo.findActives(farmId, deb, fin);

        // Statut des transactions générées, en une passe.
        List<String> sourceIds = new ArrayList<>();
        repOeufs.values().forEach(l -> l.forEach(r -> sourceIds.add(r.getUniqueId())));
        repReforme.values().forEach(l -> l.forEach(r -> sourceIds.add(r.getUniqueId())));
        diverses.forEach(v -> sourceIds.add(v.getUniqueId()));
        Map<String, StatutTransaction> statutParSource = new HashMap<>();
        for (Object[] row : parLots(sourceIds, transactionRepo::findStatutsBySourceIds)) {
            statutParSource.put((String) row[0], (StatutTransaction) row[1]);
        }

        for (VenteOeufs v : oeufs) {
            List<VenteOeufsRepartition> reps = repOeufs.getOrDefault(v.getId(), List.of());
            if (!visible(v.getCreePar(), vendeurScope, projetsAutorises, reps.stream().map(VenteOeufsRepartition::getProjet).toList())) continue;
            boolean casse = v.getTypeOeuf() == TypeVenteOeufs.CASSE;
            VenteLigneDTO d = base(v.getUniqueId(), "OEUFS", "Vente œufs", v.getDate(), v.getMontant(), v.getMontantRapporte(), v.getCreePar());
            d.setQuantite(v.getQuantiteOeufs() != null ? v.getQuantiteOeufs().doubleValue() : null);
            d.setUnite(casse ? "œufs cassés" : "œufs");
            d.setPrixUnitaire(v.getPrixUnitaire());
            d.setDescription(v.getQuantiteOeufs() + " " + (casse ? "œufs cassés" : "œufs")
                    + (v.getMagasin() != null ? ", magasin " + v.getMagasin().getNom() : ""));
            d.setMagasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null);
            client(d, v.getClient());
            statutPaiement(d, v.getClient(), CibleImputation.VENTE_OEUFS, v.getUniqueId(), v.getMontant());
            d.setCommandeUniqueId(v.getCommande() != null ? v.getCommande().getUniqueId() : null);
            d.setProjets(codes(reps.stream().map(VenteOeufsRepartition::getProjet).toList()));
            d.setStatut(statut(reps.stream().map(VenteOeufsRepartition::getUniqueId).toList(), statutParSource));
            suppression(d, v.getDemandeSuppressionPar(), v.getDateDemandeSuppression(), v.getMotifSuppression());
            lignes.add(d);
        }
        for (VenteReforme v : reformes) {
            List<VenteReformeRepartition> reps = repReforme.getOrDefault(v.getId(), List.of());
            if (!visible(v.getCreePar(), vendeurScope, projetsAutorises, reps.stream().map(VenteReformeRepartition::getProjet).toList())) continue;
            VenteLigneDTO d = base(v.getUniqueId(), "REFORME", "Vente réforme", v.getDate(), v.getMontant(), v.getMontantRapporte(), v.getCreePar());
            d.setQuantite(v.getNombreSujets() != null ? v.getNombreSujets().doubleValue() : null);
            d.setUnite("sujets");
            d.setPrixUnitaire(v.getPrixUnitaire());
            d.setTypeVente(v.getTypeVente() != null ? v.getTypeVente().name() : "TETE");
            d.setPoidsTotalKg(v.getPoidsTotalKg());
            d.setPoidsMoyenParSujet(VenteReformeDTO.poidsMoyenParSujet(v));
            d.setPrixParKg(VenteReformeDTO.prixParKg(v));
            d.setPrixParTete(VenteReformeDTO.prixParTete(v));
            d.setDescription(v.getNombreSujets() + " sujet(s) réformé(s)"
                    + (v.getPoidsTotalKg() != null ? ", " + v.getPoidsTotalKg() + " kg" : "")
                    + (v.getMagasin() != null ? ", magasin " + v.getMagasin().getNom() : ""));
            d.setMagasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null);
            client(d, v.getClient());
            statutPaiement(d, v.getClient(), CibleImputation.VENTE_REFORME, v.getUniqueId(), v.getMontant());
            d.setCommandeUniqueId(v.getCommande() != null ? v.getCommande().getUniqueId() : null);
            d.setProjets(codes(reps.stream().map(VenteReformeRepartition::getProjet).toList()));
            d.setStatut(statut(reps.stream().map(VenteReformeRepartition::getUniqueId).toList(), statutParSource));
            suppression(d, v.getDemandeSuppressionPar(), v.getDateDemandeSuppression(), v.getMotifSuppression());
            lignes.add(d);
        }
        for (VenteDiverse v : diverses) {
            if (!visible(v.getCreePar(), vendeurScope, null, List.of())) continue;
            boolean fientes = v.getProduit() == ProduitVenteDiverse.FIENTES;
            VenteLigneDTO d = base(v.getUniqueId(), fientes ? "FIENTES" : "AUTRE", fientes ? "Vente fientes" : "Autre vente",
                    v.getDate(), v.getMontant(), null, v.getCreePar());
            d.setQuantite(v.getQuantite());
            d.setUnite(fientes && v.getQuantite() != null ? "sacs" : null);
            d.setPrixUnitaire(v.getPrixUnitaire());
            d.setDescription(v.getDescription()); // brute : le web affiche "Vente de fientes" si vide
            d.setStatutPaiement("COMPTANT"); // jamais de client sur une vente diverse
            d.setProjets(List.of());
            d.setStatut(statut(List.of(v.getUniqueId()), statutParSource));
            suppression(d, v.getDemandeSuppressionPar(), v.getDateDemandeSuppression(), v.getMotifSuppression());
            lignes.add(d);
        }

        lignes.sort(Comparator.comparing(VenteLigneDTO::getDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return lignes;
    }

    private boolean visible(Utilisateurs creePar, String vendeurScope, Set<Long> projetsAutorises, List<Projets> projets) {
        if (vendeurScope != null && (creePar == null || !vendeurScope.equals(creePar.getUniqueId()))) return false;
        if (projetsAutorises != null && projets.stream().noneMatch(p -> projetsAutorises.contains(p.getId()))) return false;
        return true;
    }

    private static VenteLigneDTO base(String uniqueId, String type, String categorie, LocalDate date,
                                      Double montant, Double montantRapporte, Utilisateurs creePar) {
        VenteLigneDTO d = new VenteLigneDTO();
        d.setUniqueId(uniqueId);
        d.setType(type);
        d.setCategorie(categorie);
        d.setDate(date);
        d.setMontant(montant);
        d.setMontantRapporte(montantRapporte);
        d.setMontantReel(montantRapporte != null ? montantRapporte : nz(montant));
        d.setCreeParUniqueId(creePar != null ? creePar.getUniqueId() : null);
        d.setCreeParNom(creePar != null ? creePar.getFullName() : null);
        return d;
    }

    private static void client(VenteLigneDTO d, com.diafarms.ml.models.Client c) {
        d.setClientUniqueId(c != null ? c.getUniqueId() : null);
        d.setClientNom(c != null ? c.getNom() : null);
    }

    // Vente AVEC client : l'argent passe par des paiements/imputations (voir
    // CompteClientService), pas par montantRapporte — payé/reste remplacent
    // montantRapporte/montantReel, et montantRapporte est forcé à null (même s'il traîne
    // encore une ancienne valeur en base). Sans client : COMPTANT, rien à changer (déjà
    // posé par base()).
    private void statutPaiement(VenteLigneDTO d, com.diafarms.ml.models.Client c, CibleImputation type, String venteUniqueId, Double montant) {
        if (c == null) {
            d.setStatutPaiement("COMPTANT");
            return;
        }
        double paye = compteClientService.payeVente(type, venteUniqueId);
        double reste = compteClientService.resteAPayerVente(type, venteUniqueId, nz(montant));
        d.setPaye(paye);
        d.setResteAPayer(reste);
        d.setStatutPaiement(reste <= 0 ? "PAYEE" : (paye > 0 ? "PARTIELLE" : "NON_PAYEE"));
        d.setMontantReel(paye);
        d.setMontantRapporte(null);
    }

    private static void suppression(VenteLigneDTO d, Utilisateurs par, java.time.LocalDateTime date, String motif) {
        d.setDemandeSuppressionParNom(par != null ? par.getFullName() : null);
        d.setDateDemandeSuppression(date);
        d.setMotifSuppression(par != null ? motif : null);
    }

    private static List<String> codes(List<Projets> projets) {
        return projets.stream().map(Projets::getCode).distinct().sorted().toList();
    }

    // REJETE l'emporte (une part rejetée = vente contestée), puis EN_ATTENTE, sinon VALIDE.
    private static StatutTransaction statut(List<String> sourceIds, Map<String, StatutTransaction> statutParSource) {
        boolean attente = false;
        for (String id : sourceIds) {
            StatutTransaction s = statutParSource.get(id);
            if (s == StatutTransaction.REJETE) return StatutTransaction.REJETE;
            if (s == StatutTransaction.EN_ATTENTE) attente = true;
        }
        return attente ? StatutTransaction.EN_ATTENTE : StatutTransaction.VALIDE;
    }
}
