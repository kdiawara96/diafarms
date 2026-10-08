package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.EntretienDTO;
import com.diafarms.ml.commons.DateSaisie;
import com.diafarms.ml.commons.FermeScope;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.NiveauEntretien;
import com.diafarms.ml.enums.TypeEntretien;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Entretien;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.EntretienRepo;
import com.diafarms.ml.request.create.EntretienCreate;
import com.diafarms.ml.request.update.EntretienUpdate;
import com.diafarms.ml.services.EntretienService;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EntretienImpl implements EntretienService {

    private final EntretienRepo entretienRepo;
    private final BatimentRepo batimentRepo;
    private final LogsServices logs;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private NiveauEntretien parseNiveau(String niveau) {
        if (niveau == null || niveau.isBlank()) {
            throw new IllegalArgumentException("Le niveau est requis (BATIMENT ou SITE).");
        }
        try {
            return NiveauEntretien.valueOf(niveau.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Niveau invalide : " + niveau);
        }
    }

    private TypeEntretien parseType(String type) {
        if (type == null || type.isBlank()) return TypeEntretien.AUTRE;
        try {
            return TypeEntretien.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Type invalide : " + type);
        }
    }

    // Pas de montant à synchroniser ici (voir Entretien.java) : contrairement à
    // SoinsImpl, aucun appel à TransactionService.

    // Un poulailler tout juste nettoyé garde trace de la date sur son propre champ
    // (déjà présent, jamais alimenté jusqu'ici) — seulement si plus récent que la
    // valeur existante, pour ne pas régresser sur une saisie rétroactive.
    private void mettreAJourDerniereMaintenance(Batiment batiment, LocalDate date) {
        if (batiment == null || date == null) return;
        if (batiment.getDateDerniereMaintenance() == null || date.isAfter(batiment.getDateDerniereMaintenance())) {
            batiment.setDateDerniereMaintenance(date);
            batimentRepo.save(batiment);
        }
    }

    // Poulailler de la ferme de l'utilisateur seulement : celui d'une autre ferme est
    // traité comme inexistant (voir FermeScope).
    private Batiment poulaillerDeLaFerme(String uniqueId, Utilisateurs u) {
        Batiment b = batimentRepo.findByUniqueId(uniqueId);
        if (b == null || !FermeScope.memeFerme(b.getFarm(), u)) {
            throw new IllegalArgumentException("Poulailler introuvable : " + uniqueId);
        }
        return b;
    }

    private Entretien entretienDeLaFerme(String uniqueId, Utilisateurs u) {
        return entretienRepo.findByUniqueId(uniqueId)
                .filter(x -> FermeScope.memeFerme(x.getFarm(), u))
                .orElseThrow(() -> new IllegalArgumentException("Entretien introuvable : " + uniqueId));
    }

    private static LocalTime heure(String brute) {
        if (brute == null || brute.isBlank()) return null;
        try {
            return LocalTime.parse(brute.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("Heure invalide : " + brute + " (format attendu HH:mm).");
        }
    }

    @Override
    @Transactional
    public EntretienDTO create(EntretienCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        NiveauEntretien niveau = parseNiveau(data.getNiveau());
        if (data.getDescription() == null || data.getDescription().isBlank()) {
            throw new IllegalArgumentException("La description est obligatoire.");
        }

        Entretien e = new Entretien();
        e.setUniqueId(java.util.UUID.randomUUID().toString());
        e.setDate(DateSaisie.saisie(data.getDate(), LocalDate.now()));
        e.setHeure(heure(data.getHeure()));
        e.setNiveau(niveau);
        e.setType(niveau == NiveauEntretien.SITE ? TypeEntretien.AUTRE : parseType(data.getType()));
        e.setDescription(data.getDescription());
        e.setObservations(data.getObservations());
        e.setInitialisation(Initialisation.init());

        if (niveau == NiveauEntretien.BATIMENT) {
            if (data.getBatimentUniqueId() == null || data.getBatimentUniqueId().isBlank()) {
                throw new IllegalArgumentException("Le poulailler est requis pour une action de niveau Poulailler.");
            }
            e.setBatiment(poulaillerDeLaFerme(data.getBatimentUniqueId(), currentUser));
        }
        if (currentUser != null) {
            e.setFarm(currentUser.getFarm());
        }

        Entretien saved = entretienRepo.save(e);

        if (saved.getType() == TypeEntretien.NETTOYAGE && saved.getBatiment() != null) {
            mettreAJourDerniereMaintenance(saved.getBatiment(), saved.getDate());
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Entretien",
                    "Saisie d'entretien (" + saved.getType() + " : " + saved.getDescription() + ")");
        }

        return EntretienDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public EntretienDTO update(String uniqueId, EntretienUpdate data) {
        Utilisateurs utilisateur = getCurrentUserSafe();
        Entretien e = entretienDeLaFerme(uniqueId, utilisateur);

        e.setDate(DateSaisie.modifiee(data.getDate(), e.getDate()));
        if (data.getHeure() != null) e.setHeure(heure(data.getHeure()));
        if (data.getDescription() != null && data.getDescription().isBlank()) {
            throw new IllegalArgumentException("La description est obligatoire.");
        }
        if (data.getNiveau() != null) e.setNiveau(parseNiveau(data.getNiveau()));
        if (data.getType() != null) e.setType(e.getNiveau() == NiveauEntretien.SITE ? TypeEntretien.AUTRE : parseType(data.getType()));
        if (data.getDescription() != null) e.setDescription(data.getDescription());
        if (data.getObservations() != null) e.setObservations(data.getObservations());

        if (e.getNiveau() == NiveauEntretien.SITE) {
            e.setBatiment(null);
            e.setType(TypeEntretien.AUTRE);
        } else if (data.getBatimentUniqueId() != null) {
            e.setBatiment(data.getBatimentUniqueId().isBlank() ? null : poulaillerDeLaFerme(data.getBatimentUniqueId(), utilisateur));
        }
        if (e.getInitialisation() != null) {
            e.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        Entretien saved = entretienRepo.save(e);

        if (saved.getType() == TypeEntretien.NETTOYAGE && saved.getBatiment() != null) {
            mettreAJourDerniereMaintenance(saved.getBatiment(), saved.getDate());
        }

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Entretien", "Modification d'une saisie d'entretien");
        }

        return EntretienDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Entretien e = entretienDeLaFerme(uniqueId, getCurrentUserSafe());

        e.getInitialisation().setRemoved(!e.getInitialisation().getRemoved());
        entretienRepo.save(e);
        boolean removed = e.getInitialisation().getRemoved();

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), e.getId(), "Entretien",
                    (removed ? "Suppression" : "Restauration") + " d'une saisie d'entretien");
        }

        return removed ? "Saisie supprimée." : "Saisie récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<EntretienDTO> list(int page, int size, String search, String batimentUniqueId, String niveau, String type) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;
        NiveauEntretien niveauParam = (niveau == null || niveau.isBlank()) ? null : parseNiveau(niveau);
        TypeEntretien typeParam = (type == null || type.isBlank()) ? null : parseType(type);

        Page<Entretien> resultPage = entretienRepo.search(farmId,
                batimentParam != null, batimentParam != null ? batimentParam : "",
                niveauParam != null, niveauParam != null ? niveauParam : NiveauEntretien.BATIMENT,
                typeParam != null, typeParam != null ? typeParam : TypeEntretien.AUTRE,
                searchParam != null, searchParam != null ? searchParam : "", pageable);

        List<EntretienDTO> dtoList = resultPage.getContent().stream()
                .map(EntretienDTO::fromEntity)
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
