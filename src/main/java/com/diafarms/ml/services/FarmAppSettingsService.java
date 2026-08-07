package com.diafarms.ml.services;

import com.diafarms.ml.DTO.FarmAppSettingsDTO;

public interface FarmAppSettingsService {

    /** Paramètres de la ferme de l'admin connecté (créés à la volée si absents). */
    FarmAppSettingsDTO getSettings();

    /** Réservé à un ADMIN/SUPER_ADMIN. */
    FarmAppSettingsDTO updateSettings(FarmAppSettingsDTO data);
}
