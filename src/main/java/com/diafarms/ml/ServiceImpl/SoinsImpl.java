package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.SoinsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.TypeSoin;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Soins;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.SoinsRepo;
import com.diafarms.ml.request.create.SoinsCreate;
import com.diafarms.ml.request.update.SoinsUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.SoinsService;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SoinsImpl implements SoinsService {

    private final SoinsRepo soinsRepo;
    private final ProjetsRepo projetsRepo;
    private final BatimentRepo batimentRepo;
    private final LogsServices logs;
    private final OtherService otherService;
    private final TransactionService transactionService;
    private final com.diafarms.ml.commons.PoulaillerObligatoire poulaillerObligatoire;
    private final com.diafarms.ml.commons.ProjetsFerme projetsFerme;
    private final MedicamentService medicamentService;

    // Soin d'une autre ferme : même réponse qu'introuvable.
    private Soins soinDeLaFerme(String uniqueId) {
        Soins s = soinsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Soins introuvable : " + uniqueId));
        projetsFerme.verifier(s.getProjet(), "Soins introuvable : " + uniqueId);
        return s;
    }

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private TypeSoin parseType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Le type de soins est requis (VACCINATION, MEDICAMENT ou AUTRE).");
        }
        // Le téléphone envoie le libellé affiché (« Médicament », avec accent) : on
        // accepte le code comme le libellé, accents et casse ignorés.
        String cle = java.text.Normalizer.normalize(type.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase();
        for (TypeSoin t : TypeSoin.values()) {
            if (t.name().equals(cle)) return t;
        }
        throw new IllegalArgumentException("Type de soins invalide : " + type);
    }

    private String joinModeAdministration(List<String> modes) {
        if (modes == null || modes.isEmpty()) return null;
        String joined = modes.stream()
                .filter(m -> m != null && !m.trim().isEmpty())
                .collect(Collectors.joining(" | "));
        return joined.isBlank() ? null : joined;
    }

    // Voir AlimentationImpl.syncTransaction — même principe. Un vaccin (doses + prix
    // unitaire, coût calculé automatiquement) garde sa propre catégorie comptable
    // "Vaccination" pour ne pas mélanger les rapports existants avec les soins
    // génériques, même si les deux vivent maintenant dans la même table.
    private void syncTransaction(Soins s, Utilisateurs currentUser) {
        if (currentUser == null || currentUser.getFarm() == null) return;
        boolean vaccination = s.getType() == TypeSoin.VACCINATION;
        String description = vaccination
                ? "Vaccin " + s.getProduit() + " (" + s.getQuantite() + " doses), projet " + (s.getProjet() != null ? s.getProjet().getTitre() : "?")
                : "Soins (" + s.getType() + " : " + s.getProduit() + "), projet " + (s.getProjet() != null ? s.getProjet().getTitre() : "?");
        transactionService.syncSortie(s.getProjet(), currentUser.getFarm(), s.getCoutTotal(),
                vaccination ? "Vaccination" : "Soins", s.getDate(), description,
                vaccination ? SourceTransaction.VACCINATION : SourceTransaction.SOINS,
                s.getUniqueId(), currentUser);
    }

    @Override
    @Transactional
    public SoinsDTO create(SoinsCreate data) {
        Projets projet = projetsFerme.charger(data.getProjetUniqueId());

        Utilisateurs currentUser = getCurrentUserSafe();
        boolean depuisStock = Boolean.TRUE.equals(data.getDepuisStock());
        if (data.getProduit() == null || data.getProduit().isBlank()) {
            throw new IllegalArgumentException("Le produit (nom du vaccin ou du médicament) est obligatoire.");
        }
        if (depuisStock) {
            medicamentService.verifierConsommation(projet, data.getProduit(), data.getUnite(), data.getQuantite(), null);
        }

        Soins s = new Soins();
        s.setUniqueId(java.util.UUID.randomUUID().toString());
        s.setProjet(projet);
        s.setDate(com.diafarms.ml.commons.DateSaisie.saisie(data.getDate(), LocalDate.now()));
        s.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        s.setType(parseType(data.getType()));
        s.setProduit(data.getProduit());
        s.setQuantite(data.getQuantite());
        s.setPrixUnitaire(data.getPrixUnitaire());
        // Pris dans le stock : le médicament a déjà été payé à son achat (dépense
        // MEDICAMENT), le soin ne crée donc aucune dépense SOINS/VACCINATION.
        s.setCoutTotal(depuisStock ? null : com.diafarms.ml.commons.Franc.arrondi(data.getCoutTotal()));
        s.setModeAdministration(joinModeAdministration(data.getModeAdministration()));
        s.setObservations(data.getObservations());
        s.setDepuisStock(depuisStock ? Boolean.TRUE : null);
        s.setUnite(depuisStock ? data.getUnite().trim() : (data.getUnite() != null && !data.getUnite().isBlank() ? data.getUnite().trim() : null));
        s.setInitialisation(Initialisation.init());

        s.setBatiment(poulaillerObligatoire.resoudre(projet, data.getBatimentUniqueId()));
        if (currentUser != null) {
            s.setFarm(currentUser.getFarm());
        }

        Soins saved = soinsRepo.save(s);
        syncTransaction(saved, currentUser);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Soins",
                    "Saisie de soins (" + saved.getType() + " : " + saved.getProduit() + ") pour le projet '" + projet.getTitre() + "'");
        }

        return SoinsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public SoinsDTO update(String uniqueId, SoinsUpdate data) {
        Soins s = soinDeLaFerme(uniqueId);

        s.setDate(com.diafarms.ml.commons.DateSaisie.modifiee(data.getDate(), s.getDate()));
        if (data.getHeure() != null) s.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getType() != null) s.setType(parseType(data.getType()));
        if (data.getProduit() != null) {
            if (data.getProduit().isBlank()) {
                throw new IllegalArgumentException("Le produit (nom du vaccin ou du médicament) est obligatoire.");
            }
            s.setProduit(data.getProduit());
        }
        if (data.getQuantite() != null) s.setQuantite(data.getQuantite());
        if (data.getPrixUnitaire() != null) s.setPrixUnitaire(data.getPrixUnitaire());
        if (data.getCoutTotal() != null) s.setCoutTotal(com.diafarms.ml.commons.Franc.arrondi(data.getCoutTotal()));
        if (data.getModeAdministration() != null) s.setModeAdministration(joinModeAdministration(data.getModeAdministration()));
        if (data.getObservations() != null) s.setObservations(data.getObservations());
        if (data.getDepuisStock() != null) s.setDepuisStock(data.getDepuisStock() ? Boolean.TRUE : null);
        if (data.getUnite() != null) s.setUnite(data.getUnite().isBlank() ? null : data.getUnite().trim());
        if (Boolean.TRUE.equals(s.getDepuisStock())) {
            // Pris dans le stock : pas de coût propre (voir create), une dépense existante
            // est retirée par syncTransaction.
            s.setCoutTotal(null);
            // Sa propre consommation actuelle est rendue avant de vérifier la nouvelle.
            medicamentService.verifierConsommation(s.getProjet(), s.getProduit(), s.getUnite(), s.getQuantite(), s.getUniqueId());
        }
        s.setBatiment(poulaillerObligatoire.resoudrePourModification(s.getProjet(), s.getBatiment(), data.getBatimentUniqueId()));
        if (s.getInitialisation() != null) {
            s.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        Soins saved = soinsRepo.save(s);

        Utilisateurs currentUser = getCurrentUserSafe();
        syncTransaction(saved, currentUser);
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Soins", "Modification d'une saisie de soins");
        }

        return SoinsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Soins s = soinDeLaFerme(uniqueId);

        // Restaurer un soin pris dans le stock : il faut que le stock le permette encore.
        if (Boolean.TRUE.equals(s.getInitialisation().getRemoved()) && Boolean.TRUE.equals(s.getDepuisStock())) {
            medicamentService.verifierConsommation(s.getProjet(), s.getProduit(), s.getUnite(), s.getQuantite(), s.getUniqueId());
        }
        s.getInitialisation().setRemoved(!s.getInitialisation().getRemoved());
        soinsRepo.save(s);
        boolean removed = s.getInitialisation().getRemoved();
        // Restauration : la dépense ne revient que si le soin a un coût propre (pas pris
        // dans le stock), même règle que ProjetImpl.basculerTransactionsGenerees.
        boolean aUnCout = !Boolean.TRUE.equals(s.getDepuisStock()) && s.getCoutTotal() != null && s.getCoutTotal() > 0;
        if (removed || aUnCout) {
            transactionService.setRemovedBySource(s.getUniqueId(), removed);
        }

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), s.getId(), "Soins",
                    (removed ? "Suppression" : "Restauration") + " d'une saisie de soins");
        }

        return removed ? "Saisie supprimée." : "Saisie récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<SoinsDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId, String type) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;
        TypeSoin typeParam = (type == null || type.isBlank()) ? null : parseType(type);

        Page<Soins> resultPage = soinsRepo.search(farmId,
                projetParam != null, projetParam != null ? projetParam : "",
                batimentParam != null, batimentParam != null ? batimentParam : "",
                typeParam != null, typeParam != null ? typeParam : TypeSoin.AUTRE,
                searchParam != null, searchParam != null ? searchParam : "", pageable);

        List<SoinsDTO> dtoList = resultPage.getContent().stream()
                .map(SoinsDTO::fromEntity)
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
