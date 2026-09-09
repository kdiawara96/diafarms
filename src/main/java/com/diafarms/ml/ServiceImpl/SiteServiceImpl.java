package com.diafarms.ml.ServiceImpl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.SiteDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Site;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.SiteRepo;
import com.diafarms.ml.request.create.SiteCreate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.SiteService;

import lombok.RequiredArgsConstructor;

// Emplacement physique de la ferme — voir Site.java. Permissions alignées sur
// BatimentImpl/MagasinServiceImpl : gestion réservée à ADMIN/RESPONSABLE (même
// périmètre que les poulaillers/magasins qu'un site regroupe).
@Service
@RequiredArgsConstructor
public class SiteServiceImpl implements SiteService {

    private final SiteRepo siteRepo;
    private final LogsServices logs;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasRole(Utilisateurs u, String role) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> role.equalsIgnoreCase(r.getRole()));
    }

    private void ensureCanManage(Utilisateurs u) {
        if (!hasRole(u, "ADMIN") && !hasRole(u, "SUPER_ADMIN") && !hasRole(u, "RESPONSABLE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour gérer les sites.");
        }
    }

    @Override
    @Transactional
    public SiteDTO create(SiteCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (data.getNom() == null || data.getNom().isBlank()) {
            throw new IllegalArgumentException("Le nom du site est obligatoire.");
        }
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Votre compte n'est rattaché à aucune ferme.");
        }
        if (siteRepo.existsByNomIgnoreCaseAndFarmIdAndInitialisationRemovedFalse(data.getNom().trim(), currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Un site portant ce nom existe déjà.");
        }

        Site s = new Site();
        s.setUniqueId(java.util.UUID.randomUUID().toString());
        s.setNom(data.getNom().trim());
        s.setLocalisation(data.getLocalisation());
        s.setLatitude(data.getLatitude());
        s.setLongitude(data.getLongitude());
        s.setFarm(currentUser.getFarm());
        s.setInitialisation(Initialisation.init());

        Site saved = siteRepo.save(s);
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Site", "Ajout d'un site : " + saved.getNom());
        }
        return SiteDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public SiteDTO update(String uniqueId, SiteCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        Site s = siteRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Site introuvable : " + uniqueId));

        if (data.getNom() != null && !data.getNom().isBlank()) s.setNom(data.getNom().trim());
        s.setLocalisation(data.getLocalisation());
        s.setLatitude(data.getLatitude());
        s.setLongitude(data.getLongitude());
        if (s.getInitialisation() != null) s.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());

        Site saved = siteRepo.save(s);
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Site", "Mise à jour du site : " + saved.getNom());
        }
        return SiteDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        Site s = siteRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Site introuvable : " + uniqueId));

        s.getInitialisation().setRemoved(!s.getInitialisation().getRemoved());
        boolean removed = s.getInitialisation().getRemoved();
        siteRepo.save(s);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), s.getId(), "Site",
                    (removed ? "Suppression" : "Restauration") + " du site : " + s.getNom());
        }
        return removed ? "Site supprimé." : "Site récupéré.";
    }

    @Override
    @Transactional(readOnly = true)
    public List<SiteDTO> list() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) return List.of();
        return siteRepo.findAllActiveByFarm(currentUser.getFarm().getId()).stream()
                .map(SiteDTO::fromEntity)
                .collect(Collectors.toList());
    }
}
