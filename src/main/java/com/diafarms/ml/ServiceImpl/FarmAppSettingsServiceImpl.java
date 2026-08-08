package com.diafarms.ml.ServiceImpl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.FarmAppSettingsDTO;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.FarmAppSettings;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.FarmAppSettingsRepo;
import com.diafarms.ml.services.FarmAppSettingsService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FarmAppSettingsServiceImpl implements FarmAppSettingsService {

    private final FarmAppSettingsRepo repo;
    private final OtherService otherService;

    private boolean isAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Transactional
    public FarmAppSettingsDTO getSettings() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return FarmAppSettingsDTO.fromEntity(null);
        }
        return FarmAppSettingsDTO.fromEntity(findOrCreate(currentUser.getFarm()));
    }

    @Override
    @Transactional
    public FarmAppSettingsDTO updateSettings(FarmAppSettingsDTO data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (!isAdmin(currentUser)) {
            throw new IllegalArgumentException("Seul un administrateur peut modifier ces paramètres.");
        }
        if (currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Aucune ferme rattachée à ce compte.");
        }

        FarmAppSettings settings = findOrCreate(currentUser.getFarm());
        settings.setProductionMobileEnabled(data.isProductionMobileEnabled());
        settings.setProductionWebEnabled(data.isProductionWebEnabled());
        settings.setComptableMobileEnabled(data.isComptableMobileEnabled());
        settings.setComptableWebEnabled(data.isComptableWebEnabled());
        settings.setVenteMobileEnabled(data.isVenteMobileEnabled());
        settings.setVenteWebEnabled(data.isVenteWebEnabled());
        settings.setResponsableWebEnabled(data.isResponsableWebEnabled());

        return FarmAppSettingsDTO.fromEntity(repo.save(settings));
    }

    private FarmAppSettings findOrCreate(Farm farm) {
        return repo.findByFarm_Id(farm.getId()).orElseGet(() -> {
            FarmAppSettings s = new FarmAppSettings();
            s.setFarm(farm);
            return repo.save(s);
        });
    }
}
