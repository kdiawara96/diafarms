package com.diafarms.ml.ServiceImpl;

import com.diafarms.ml.commons.Devise;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.DTO.VenteDiverseDTO;
import com.diafarms.ml.commons.FermeScope;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.ProduitVenteDiverse;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteDiverse;
import com.diafarms.ml.repository.VenteDiverseRepo;
import com.diafarms.ml.request.create.TransactionCreate;
import com.diafarms.ml.request.create.VenteDiverseCreate;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.request.update.VenteDiverseUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteDiverseService;

import lombok.RequiredArgsConstructor;

// Vente de fientes / autre vente : la vente est l'original, sa Transaction (une seule,
// du Projet ou commune, SourceTransaction.VENTE_DIVERSE) la suit à chaque création, modification,
// suppression ou restauration. Mêmes règles de suppression que VenteOeufsImpl.
@Service
@RequiredArgsConstructor
public class VenteDiverseImpl implements VenteDiverseService {

    private final VenteDiverseRepo venteDiverseRepo;
    private final TransactionService transactionService;
    private final LogsServices logs;
    private final OtherService otherService;
    private final com.diafarms.ml.repository.ProjetsRepo projetsRepo;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean hasRole(Utilisateurs u, String role) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> role.equalsIgnoreCase(r.getRole()));
    }

    private boolean isAdmin(Utilisateurs u) {
        return hasRole(u, "ADMIN") || hasRole(u, "SUPER_ADMIN");
    }

    // Même population que VenteOeufsImpl : jamais le vendeur (VENTE).
    private void ensureCanDemanderSuppression(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour demander la suppression d'une vente.");
        }
    }

    private void ensureCanModifier(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Seul un administrateur, un responsable ou un comptable peut modifier une vente.");
        }
    }

    private void ensureCanConfirmerSuppression(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE")) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut supprimer une vente.");
        }
    }

    private static String categorie(ProduitVenteDiverse produit) {
        return produit == ProduitVenteDiverse.FIENTES ? CATEGORIE_FIENTES : CATEGORIE_AUTRE;
    }

    private static String nettoyer(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String formatQuantite(Double q) {
        return q == Math.floor(q) ? String.valueOf(q.longValue()) : String.valueOf(q);
    }

    /** Libellé de la transaction comptable, reconstruit depuis la vente. */
    private static String libelleTransaction(VenteDiverse v) {
        if (v.getProduit() == ProduitVenteDiverse.FIENTES) {
            if (v.getQuantite() != null && v.getQuantite() > 0) {
                String base = formatQuantite(v.getQuantite()) + " sac(s) de fientes";
                return v.getDescription() != null ? base + ", " + v.getDescription() : base;
            }
            // La catégorie dit déjà "Vente fientes" ; les ventes reprises de l'ancien
            // formulaire ont leurs sacs dans la description.
            return v.getDescription() != null ? v.getDescription() : "Vente de fientes";
        }
        return v.getDescription();
    }

    private static void valider(VenteDiverse v) {
        if (v.getMontant() == null || v.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant de la vente doit être positif.");
        }
        if (v.getQuantite() != null && v.getQuantite() < 0) {
            throw new IllegalArgumentException("La quantité ne peut pas être négative.");
        }
        if (v.getProduit() == ProduitVenteDiverse.AUTRE && v.getDescription() == null) {
            throw new IllegalArgumentException("Précisez ce qui a été vendu (description obligatoire).");
        }
    }

    // « Cette vente concerne » : le Projet ou toute la ferme, jamais un site (voir
    // TransactionServiceImpl.normaliserRattachement pour les dépenses). rattachement absent
    // (anciens clients) : projetUniqueId rempli = Projet, sinon toute la ferme.
    private com.diafarms.ml.models.Projets resoudreProjet(String rattachement, String projetUniqueId, Utilisateurs u) {
        boolean aProjet = projetUniqueId != null && !projetUniqueId.isBlank();
        if (rattachement != null && !rattachement.isBlank()) {
            String mode = rattachement.trim().toUpperCase();
            switch (mode) {
                case "PROJET" -> {
                    if (!aProjet) throw new IllegalArgumentException("Choisissez le projet concerné par cette vente.");
                }
                case "FERME" -> {
                    if (aProjet) throw new IllegalArgumentException("Une vente de toute la ferme ne précise pas de projet.");
                    return null;
                }
                case "SITE" -> throw new IllegalArgumentException("Une vente de fientes concerne le Projet ou toute la ferme, pas un site.");
                default -> throw new IllegalArgumentException("Rattachement inconnu : " + rattachement + " (attendu PROJET ou FERME).");
            }
        }
        if (!aProjet) return null;
        return projetsRepo.findByUniqueId(projetUniqueId.trim())
                .filter(p -> FermeScope.memeFerme(p.getFarm(), u))
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));
    }

    private VenteDiverse creer(ProduitVenteDiverse produit, LocalDate date, Double quantite, Double prixUnitaire,
                               Double montant, String description, String libelleForce,
                               String rattachement, String projetUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        VenteDiverse v = new VenteDiverse();
        v.setUniqueId(UUID.randomUUID().toString());
        v.setProduit(produit);
        v.setDate(date != null ? date : LocalDate.now());
        v.setQuantite(quantite);
        v.setPrixUnitaire(prixUnitaire);
        v.setMontant(com.diafarms.ml.commons.Franc.arrondi(montant));
        v.setDescription(nettoyer(description));
        v.setFarm(currentUser.getFarm());
        v.setProjet(resoudreProjet(rattachement, projetUniqueId, currentUser));
        v.setCreePar(currentUser);
        v.setInitialisation(Initialisation.init());
        valider(v);
        VenteDiverse saved = venteDiverseRepo.save(v);

        transactionService.createFromSource(saved.getProjet(), saved.getFarm(), saved.getMontant(), categorie(produit), saved.getDate(),
                libelleForce != null ? libelleForce : libelleTransaction(saved),
                SourceTransaction.VENTE_DIVERSE, saved.getUniqueId(), currentUser);

        logs.addLogs(currentUser.getId(), saved.getId(), "VenteDiverse",
                "Vente " + (produit == ProduitVenteDiverse.FIENTES ? "de fientes" : "diverse") + " (" + Devise.montant(saved.getMontant()) + ")");
        return saved;
    }

    private static ProduitVenteDiverse parseProduit(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Le produit vendu est obligatoire (FIENTES ou AUTRE).");
        }
        try {
            return ProduitVenteDiverse.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Produit inconnu : " + raw);
        }
    }

    @Override
    @Transactional
    public VenteDiverseDTO create(VenteDiverseCreate data) {
        // Sans montant mais avec sacs x prix d'un sac : le serveur calcule (au franc).
        if ((data.getMontant() == null || data.getMontant() <= 0) && data.getQuantite() != null && data.getQuantite() > 0
                && data.getPrixUnitaire() != null && data.getPrixUnitaire() > 0) {
            data.setMontant(data.getQuantite() * data.getPrixUnitaire());
        }
        VenteDiverse saved = creer(parseProduit(data.getProduit()),
                com.diafarms.ml.commons.DateSaisie.saisie(data.getDate(), null),
                data.getQuantite(), data.getPrixUnitaire(), data.getMontant(), data.getDescription(), null,
                data.getRattachement(), data.getProjetUniqueId());
        return VenteDiverseDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO createDepuisTransaction(TransactionCreate data) {
        ProduitVenteDiverse produit = CATEGORIE_FIENTES.equals(data.getCategorie())
                ? ProduitVenteDiverse.FIENTES : ProduitVenteDiverse.AUTRE;
        // L'ancien formulaire mettait déjà "N sac(s) de fientes, ..." dans la description :
        // on la garde telle quelle, sans la reconstruire (pas de quantité séparée connue).
        VenteDiverse saved = creer(produit, com.diafarms.ml.commons.DateSaisie.pasDansLeFutur(data.getDate()), null, null,
                data.getMontant(), data.getDescription(),
                nettoyer(data.getDescription()) != null ? data.getDescription().trim() : null,
                // Ancien chemin : vente du Projet seulement si explicitement non commune.
                null, Boolean.FALSE.equals(data.getCommun()) ? data.getProjetUniqueId() : null);
        return transactionService.findDtoBySource(saved.getUniqueId());
    }

    @Override
    @Transactional
    public VenteDiverseDTO update(String uniqueId, VenteDiverseUpdate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanModifier(currentUser);
        VenteDiverse v = trouver(uniqueId);
        if (Boolean.TRUE.equals(v.getInitialisation().getRemoved())) {
            throw new IllegalArgumentException("Cette vente est supprimée.");
        }
        v.setDate(com.diafarms.ml.commons.DateSaisie.modifiee(data.getDate(), v.getDate()));
        if (data.getQuantite() != null) v.setQuantite(data.getQuantite() > 0 ? data.getQuantite() : null);
        if (data.getPrixUnitaire() != null) v.setPrixUnitaire(data.getPrixUnitaire() > 0 ? data.getPrixUnitaire() : null);
        // Égal au franc près au montant actuel = inchangé (ancienne vente à centimes).
        Double montant = com.diafarms.ml.commons.Franc.modifie(data.getMontant(), v.getMontant());
        if (montant != null) v.setMontant(montant);
        if (data.getDescription() != null) v.setDescription(nettoyer(data.getDescription()));
        boolean projetModifie = (data.getRattachement() != null && !data.getRattachement().isBlank()) || data.getProjetUniqueId() != null;
        if (projetModifie) v.setProjet(resoudreProjet(data.getRattachement(), data.getProjetUniqueId(), currentUser));
        valider(v);
        Initialisation.updateDate(v.getInitialisation());
        VenteDiverse saved = venteDiverseRepo.save(v);

        transactionService.updateMontantBySource(saved.getUniqueId(), saved.getMontant());
        transactionService.updateDateBySource(saved.getUniqueId(), saved.getDate());
        transactionService.updateDescriptionBySource(saved.getUniqueId(), libelleTransaction(saved));
        if (projetModifie) transactionService.updateProjetBySource(saved.getUniqueId(), saved.getProjet());

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteDiverse", "Modification d'une vente diverse");
        }
        return VenteDiverseDTO.fromEntity(saved);
    }

    // Vente d'une autre ferme : même message qu'inexistante (voir FermeScope).
    private VenteDiverse trouver(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        return venteDiverseRepo.findByUniqueId(uniqueId)
                .filter(x -> FermeScope.memeFerme(x.getFarm(), currentUser))
                .orElseThrow(() -> new IllegalArgumentException("Vente introuvable : " + uniqueId));
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId, String motif) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);
        VenteDiverse v = trouver(uniqueId);

        boolean removed = !v.getInitialisation().getRemoved();
        if (removed) v.setMotifSuppression(MotifSuppressionRequest.exiger(motif));
        v.getInitialisation().setRemoved(removed);
        venteDiverseRepo.save(v);
        transactionService.setRemovedBySource(v.getUniqueId(), removed);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteDiverse",
                    (removed ? "Suppression" : "Restauration") + " d'une vente diverse"
                            + (removed ? ", motif : " + v.getMotifSuppression() : ""));
        }
        return removed ? "Vente supprimée." : "Vente récupérée.";
    }

    @Override
    @Transactional
    public VenteDiverseDTO demanderSuppression(String uniqueId, String motif) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanDemanderSuppression(currentUser);
        String motifValide = MotifSuppressionRequest.exiger(motif);

        VenteDiverse v = trouver(uniqueId);
        if (v.getDemandeSuppressionPar() != null) {
            throw new IllegalArgumentException("Une demande de suppression est déjà en attente pour cette vente.");
        }
        v.setDemandeSuppressionPar(currentUser);
        v.setDateDemandeSuppression(LocalDateTime.now());
        v.setMotifSuppression(motifValide);
        VenteDiverse saved = venteDiverseRepo.save(v);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteDiverse",
                    "Demande de suppression d'une vente diverse, motif : " + motifValide);
        }
        return VenteDiverseDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public VenteDiverseDTO confirmerSuppression(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);

        VenteDiverse v = trouver(uniqueId);
        if (v.getDemandeSuppressionPar() == null) {
            throw new IllegalArgumentException("Aucune demande de suppression en attente pour cette vente.");
        }
        v.getInitialisation().setRemoved(true);
        venteDiverseRepo.save(v);
        transactionService.setRemovedBySource(v.getUniqueId(), true);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteDiverse",
                    "Suppression confirmée pour une vente diverse, motif : " + v.getMotifSuppression());
        }
        return VenteDiverseDTO.fromEntity(v);
    }

    @Override
    @Transactional
    public VenteDiverseDTO annulerDemandeSuppression(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanConfirmerSuppression(currentUser);

        VenteDiverse v = trouver(uniqueId);
        if (v.getDemandeSuppressionPar() == null) {
            throw new IllegalArgumentException("Aucune demande de suppression en attente pour cette vente.");
        }
        v.setDemandeSuppressionPar(null);
        v.setDateDemandeSuppression(null);
        v.setMotifSuppression(null);
        VenteDiverse saved = venteDiverseRepo.save(v);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteDiverse", "Demande de suppression refusée pour une vente diverse");
        }
        return VenteDiverseDTO.fromEntity(saved);
    }
}
