package com.diafarms.ml.ServiceImpl;

import com.diafarms.ml.commons.Devise;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.AlimentationDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Alimentation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.AlimentationRepo;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.ConsommationAlimentRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.TypeAliment;
import com.diafarms.ml.request.create.AlimentationCreate;
import com.diafarms.ml.request.update.AlimentationUpdate;
import com.diafarms.ml.services.AlimentationService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AlimentationImpl implements AlimentationService {


    private final AlimentationRepo alimentationRepo;
    private final ProjetsRepo projetsRepo;
    private final com.diafarms.ml.commons.ProjetsFerme projetsFerme;
    private final BatimentRepo batimentRepo;
    private final ConsommationAlimentRepo consommationAlimentRepo;
    private final OtherService otherService;
    private final LogsServices logs;
    private final TransactionService transactionService;

    // Génère/synchronise la sortie comptable liée à cet achat d'aliment — voir
    // TransactionService.syncSortie : plus besoin de ressaisir le coût manuellement
    // en Comptabilité, la Transaction suit automatiquement coutTotal.
    private void syncTransaction(Alimentation a, Utilisateurs currentUser) {
        if (currentUser == null || currentUser.getFarm() == null) return;
        String description = "Achat aliment : " + a.getNomAliment() + " (" + a.getQuantiteKg() + " kg), projet "
                + (a.getProjet() != null ? a.getProjet().getTitre() : "?");
        // L'achat appartient au projet : la dépense reprend le projet et son site (pas de
        // poulailler, même pour un ancien achat qui en avait un).
        transactionService.syncSortie(a.getProjet(), currentUser.getFarm(), a.getCoutTotal(), "Aliment",
                a.getDateDistribution(), description, SourceTransaction.ALIMENTATION, a.getUniqueId(), currentUser,
                null, a.getProjet() != null ? a.getProjet().getSite() : null, true);
    }

    // Entrée de stock créée par ProjetImpl.transfererStock (clôture d'un projet) : ce n'est
    // pas un achat (l'argent a été dépensé par le projet d'origine, aucune dépense n'y est
    // liée) et une consommation du projet d'origine la compense. La modifier créerait une
    // dépense en double (syncTransaction), la supprimer casserait la compensation.
    static final String PREFIXE_TRANSFERT = "Transfert depuis ";
    static final String OBSERVATION_TRANSFERT = "Stock restant transféré lors de la clôture";

    private static void refuserSiTransfert(Alimentation a) {
        boolean transfert = a.getNomAliment() != null && a.getNomAliment().startsWith(PREFIXE_TRANSFERT)
                && a.getObservations() != null && a.getObservations().startsWith(OBSERVATION_TRANSFERT);
        if (transfert) {
            throw new IllegalArgumentException("Ce stock vient d'un transfert de fin de projet, ce n'est pas un achat : "
                    + "il ne se modifie pas et ne se supprime pas.");
        }
    }

    // nomAliment reste obligatoire en base : vide, il est déduit du type ("Aliment ponte").
    private static String nomOuDefaut(String nom, TypeAliment type) {
        if (nom != null && !nom.isBlank()) return nom.trim();
        if (type == null || type == TypeAliment.AUTRE) return "Aliment";
        return "Aliment " + type.getLabel().toLowerCase();
    }

    private static String kg(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.FRANCE, "%.1f", v));
    }

    // --- Génération UID ---   
    private String generateUID() {
        return "ALI-" + java.util.UUID.randomUUID().toString();
    }

    // --- Récupération utilisateur safe ---
    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            System.err.println("Impossible de récupérer l'utilisateur connecté : " + e.getMessage());   
            return null;
        }
    }

    // L'achat d'aliment est un acte financier (il crée le stock ET la sortie d'argent) :
    // réservé à la finance, plus à la Production, qui ne fait que consommer et consulter
    // le stock. Même population que TransactionServiceImpl.ensureCanDemanderSuppression.
    private void ensureCanManageAchat(Utilisateurs u) {
        boolean ok = u != null && u.getRoles() != null && u.getRoles().stream().anyMatch(r ->
                "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole())
                        || "RESPONSABLE".equalsIgnoreCase(r.getRole()) || "COMPTABLE".equalsIgnoreCase(r.getRole()));
        if (!ok) {
            throw new IllegalArgumentException("Seule la finance (comptable, responsable ou administrateur) peut enregistrer, modifier ou supprimer un achat d'aliment.");
        }
    }

    // --- Log helper ---
    private void logAction(Utilisateurs currentUser, Alimentation alimentation, String message) {
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), alimentation.getId(), "Alimentation", message);
        }
    }

    // ============================================================
    // SAVE
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO save(AlimentationCreate data, String uniqueIdProjet) {
        // 1. Vérifier le projet
        Projets projet = projetsFerme.charger(uniqueIdProjet);

        // 2. Récupérer l'utilisateur et sa ferme
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManageAchat(currentUser);
        if (data.getCoutTotal() == null || data.getCoutTotal() <= 0) {
            throw new IllegalArgumentException("Le coût total de l'achat est obligatoire.");
        }
        // Sacs facultatifs (APK 1.29/1.30 : « Nombre de sacs (optionnel) ») : il faut
        // seulement de quoi connaître la quantité, les kg ou les sacs.
        if (data.getSac() != null && data.getSac() < 0) {
            throw new IllegalArgumentException("Le nombre de sacs ne peut pas être négatif.");
        }
        if (data.getSac() == null && (data.getQuantiteKg() == null || data.getQuantiteKg() <= 0)) {
            throw new IllegalArgumentException("Indiquez la quantité achetée : le nombre de sacs ou le poids total (kg).");
        }
        Farm farm = currentUser != null ? currentUser.getFarm() : null;
        TypeAliment type = TypeAliment.parse(data.getTypeAliment());
        // La quantité achetée EST le stock (pas d'étape de réception) : sans kg saisi, on
        // prend sacs x poids d'un sac (50 kg par défaut).
        Double quantiteKg = data.getQuantiteKg();
        if (quantiteKg == null) {
            double poidsSac = data.getPoidsSacKg() != null && data.getPoidsSacKg() > 0 ? data.getPoidsSacKg() : 50.0;
            quantiteKg = data.getSac() * poidsSac;
        }
        if (quantiteKg <= 0) {
            throw new IllegalArgumentException("La quantité achetée (kg) doit être supérieure à 0.");
        }

        // 3. Créer l'entité
        Alimentation alimentation = new Alimentation();
        alimentation.setUniqueId(generateUID());
        alimentation.setTypeAliment(type);
        alimentation.setNomAliment(nomOuDefaut(data.getNomAliment(), type));
        // Colonne sac NOT NULL : sans sacs saisis, 0 (seuls les kg comptent pour le stock).
        alimentation.setSac(data.getSac() != null ? data.getSac() : 0.0);
        alimentation.setQuantiteKg(quantiteKg);
        alimentation.setCoutTotal(com.diafarms.ml.commons.Franc.arrondi(data.getCoutTotal()));
        alimentation.setDateDistribution(
            com.diafarms.ml.commons.DateSaisie.saisie(data.getDateDistribution(), LocalDate.now())
        );
        alimentation.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        alimentation.setObservations(data.getObservations());
        alimentation.setFournisseur(data.getFournisseur());
        alimentation.setProjet(projet);
        // Pas de poulailler : l'achat est lié au projet (batimentUniqueId ignoré).
        alimentation.setFarm(farm);
        alimentation.setInitialisation(Initialisation.init());

        // 4. Sauvegarder
        Alimentation saved = alimentationRepo.save(alimentation);
        syncTransaction(saved, currentUser);

        // 5. Log
        logAction(currentUser, saved,
            "Création de l'alimentation '" + saved.getNomAliment()
                + "' (" + saved.getQuantiteKg() + " kg, " + saved.getSac() + " sacs) pour le projet '"
                + projet.getTitre() + "' | Coût total : " + Devise.montant(saved.getCoutTotal())
        );

        return AlimentationDTO.fromEntityList(saved);
    }

    // ============================================================
    // UPDATE
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO update(String uniqueId, AlimentationUpdate data) {
        ensureCanManageAchat(getCurrentUserSafe());
        // 1. Trouver l'alimentation
        Alimentation alimentation = alimentationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Alimentation non trouvée avec l'UID : " + uniqueId));
        // Isolation des fermes (via le projet, toujours renseigné) : autre ferme = introuvable.
        projetsFerme.verifier(alimentation.getProjet(), "Alimentation non trouvée avec l'UID : " + uniqueId);

        // 2. Vérifier si non supprimée
        if (Boolean.TRUE.equals(alimentation.getInitialisation().getRemoved())) {
            throw new IllegalArgumentException("Cette alimentation a été supprimée et ne peut pas être modifiée.");
        }
        refuserSiTransfert(alimentation);

        // 3. Sauvegarder anciennes valeurs pour le log
        String ancienNom = alimentation.getNomAliment();
        Double ancienneQuantite = alimentation.getQuantiteKg();
        Double ancienCout = alimentation.getCoutTotal();

        // 4. Mettre à jour les champs
        if (data.getNomAliment() != null && !data.getNomAliment().trim().isEmpty()) {
            alimentation.setNomAliment(data.getNomAliment());
        }
        // null = inchangé ; "" = type retiré ("Non précisé") ; valeur = défini.
        if (data.getTypeAliment() != null) {
            alimentation.setTypeAliment(TypeAliment.parse(data.getTypeAliment()));
        }
        if (data.getSac() != null) {
            alimentation.setSac(data.getSac());
        }
        // Quantité et projet : l'achat nourrit le stock de SON projet. Ce que ce projet a
        // déjà consommé doit rester couvert par ses achats après la modification (sinon
        // la consommation dépasserait l'achat). Même règle pour changer de projet : c'est
        // l'ancien projet qui perd cet aliment.
        Projets ancienProjet = alimentation.getProjet();
        Projets nouveauProjet = ancienProjet;
        if (data.getProjetUniqueId() != null && !data.getProjetUniqueId().isBlank()
                && !data.getProjetUniqueId().equals(ancienProjet.getUniqueId())) {
            nouveauProjet = projetsFerme.charger(data.getProjetUniqueId());
        }
        projetsFerme.verrouiller(ancienProjet, nouveauProjet);
        double nouvelleQuantite = data.getQuantiteKg() != null ? data.getQuantiteKg() : ancienneQuantite;
        if (nouvelleQuantite <= 0) {
            throw new IllegalArgumentException("La quantité achetée (kg) doit être supérieure à 0.");
        }
        boolean changeDeProjet = !nouveauProjet.getId().equals(ancienProjet.getId());
        if (changeDeProjet || nouvelleQuantite < ancienneQuantite) {
            double acheteSansCetAchat = alimentationRepo.sumAcheteByProjetId(ancienProjet.getId()) - ancienneQuantite;
            double consomme = consommationAlimentRepo.sumConsommeByProjetId(ancienProjet.getId());
            double reste = acheteSansCetAchat + (changeDeProjet ? 0 : nouvelleQuantite);
            if (reste + 1e-6 < consomme) {
                double minimum = Math.max(0, consomme - acheteSansCetAchat);
                if (changeDeProjet) {
                    throw new IllegalArgumentException("Impossible de changer le projet de cet achat : le projet « "
                            + ancienProjet.getTitre() + " » a déjà consommé " + kg(consomme) + " kg, il lui faut au moins "
                            + kg(minimum) + " kg de cet achat.");
                }
                throw new IllegalArgumentException("Impossible de réduire cet achat à " + kg(nouvelleQuantite)
                        + " kg : le projet a déjà consommé " + kg(consomme) + " kg, le minimum pour cet achat est "
                        + kg(minimum) + " kg.");
            }
        }
        alimentation.setQuantiteKg(nouvelleQuantite);
        alimentation.setProjet(nouveauProjet);
        if (data.getCoutTotal() != null) {
            alimentation.setCoutTotal(com.diafarms.ml.commons.Franc.arrondi(data.getCoutTotal()));
        }
        alimentation.setDateDistribution(
            com.diafarms.ml.commons.DateSaisie.modifiee(data.getDateDistribution(), alimentation.getDateDistribution())
        );
        if (data.getObservations() != null) {
            alimentation.setObservations(data.getObservations());
        }
        if (data.getHeure() != null) {
            alimentation.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        }
        // Un achat d'aliment appartient au projet, pas à un poulailler : batimentUniqueId
        // (encore envoyé par d'anciennes versions) est ignoré.
        if (data.getFournisseur() != null) {
            alimentation.setFournisseur(data.getFournisseur());
        }

        // Mise à jour date
        alimentation.setInitialisation(Initialisation.updateDate(alimentation.getInitialisation()));

        // 5. Sauvegarder
        Alimentation updated = alimentationRepo.save(alimentation);

        // 6. Log
        Utilisateurs currentUser = getCurrentUserSafe();
        syncTransaction(updated, currentUser);
        logAction(currentUser, updated,
            "Modification de l'alimentation '" + ancienNom + "' → '" + updated.getNomAliment()
                + "' | Quantité : " + ancienneQuantite + " → " + updated.getQuantiteKg()
                + " kg | Coût : " + Devise.montant(ancienCout) + " → " + Devise.montant(updated.getCoutTotal())
        );

        return AlimentationDTO.fromEntityList(updated);
    }

    // ============================================================
    // DELETE (Soft Delete)
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO delete(String uniqueId) {
        ensureCanManageAchat(getCurrentUserSafe());
        // 1. Trouver l'alimentation
        Alimentation alimentation = alimentationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Alimentation non trouvée avec l'UID : " + uniqueId));
        // Isolation des fermes (via le projet, toujours renseigné) : autre ferme = introuvable.
        projetsFerme.verifier(alimentation.getProjet(), "Alimentation non trouvée avec l'UID : " + uniqueId);

        // 2. Vérifier si déjà supprimée
        if (Boolean.TRUE.equals(alimentation.getInitialisation().getRemoved())) {
            throw new IllegalArgumentException("Cette alimentation est déjà supprimée.");
        }
        refuserSiTransfert(alimentation);

        // Ce que le projet a déjà consommé doit rester couvert par ses autres achats.
        projetsFerme.verrouiller(alimentation.getProjet());
        double acheteSansCetAchat = alimentationRepo.sumAcheteByProjetId(alimentation.getProjet().getId()) - alimentation.getQuantiteKg();
        double consomme = consommationAlimentRepo.sumConsommeByProjetId(alimentation.getProjet().getId());
        if (acheteSansCetAchat + 1e-6 < consomme) {
            throw new IllegalArgumentException("Impossible de supprimer cet achat : le projet a déjà consommé " + kg(consomme)
                    + " kg et, sans cet achat, il n'en aurait acheté que " + kg(Math.max(0, acheteSansCetAchat))
                    + " kg. Vous pouvez seulement réduire la quantité jusqu'à " + kg(consomme - acheteSansCetAchat) + " kg.");
        }

        // 3. Soft delete (le hard delete précédent effaçait définitivement la ligne,
        // incohérent avec le reste de l'app où tout est récupérable)
        alimentation.getInitialisation().setRemoved(true);
        alimentation.setInitialisation(Initialisation.updateDate(alimentation.getInitialisation()));

        Alimentation deleted = alimentationRepo.save(alimentation);
        transactionService.setRemovedBySource(deleted.getUniqueId(), true);

        // 4. Log
        Utilisateurs currentUser = getCurrentUserSafe();

        logAction(currentUser, deleted,
            "Suppression de l'alimentation '" + deleted.getNomAliment()
                + "' (" + deleted.getQuantiteKg() + " kg) du projet '"
                + deleted.getProjet().getTitre() + "'"
        );

        return AlimentationDTO.fromEntityList(deleted);
    }

    // ============================================================
    // LIST BY PROJECT
    // ============================================================
    @Override
    @Transactional(readOnly = true)
    public List<AlimentationDTO> findByProjetUniqueId(String uniqueIdProjet) {
        projetsFerme.charger(uniqueIdProjet);
        return alimentationRepo.findByProjetUniqueIdAndInitialisationRemovedFalse(uniqueIdProjet)
                .stream()
                .map(AlimentationDTO::fromEntityList)
                .toList();
    }


    // ============================================================
    // GET BY UNIQUE ID
    // ============================================================
    @Override
    @Transactional(readOnly = true)
    public AlimentationDTO findByUniqueId(String uniqueId) {
        Alimentation alimentation = alimentationRepo.findByUniqueIdAndInitialisationRemovedFalse(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Alimentation non trouvée avec l'UID : " + uniqueId));
        // Isolation des fermes (via le projet, toujours renseigné) : autre ferme = introuvable.
        projetsFerme.verifier(alimentation.getProjet(), "Alimentation non trouvée avec l'UID : " + uniqueId);
        return AlimentationDTO.fromEntityList(alimentation);
    }

    // ============================================================
    // LIST GLOBAL (paginée, pour la page Production)
    // ============================================================
    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<AlimentationDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dateDistribution"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<Alimentation> resultPage = alimentationRepo.search(farmId, projetParam, batimentParam, searchParam, pageable);

        List<AlimentationDTO> dtoList = resultPage.getContent().stream()
                .map(AlimentationDTO::fromEntityList)
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }

}
