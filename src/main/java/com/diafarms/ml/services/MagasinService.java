package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.MagasinDTO;
import com.diafarms.ml.DTO.StockMagasinDTO;
import com.diafarms.ml.request.create.MagasinCreate;

public interface MagasinService {
    MagasinDTO create(MagasinCreate data);
    MagasinDTO update(String uniqueId, MagasinCreate data);
    String deleteOrRecover(String uniqueId);
    List<MagasinDTO> list(String type);
    StockMagasinDTO getStock(String uniqueId);
    // Même calcul sans contrôle de ferme : réservé aux appelants internes qui ont
    // déjà chargé le magasin dans la ferme de l'utilisateur (voir NotificationServiceImpl).
    StockMagasinDTO stockDuMagasin(com.diafarms.ml.models.Magasin magasin);
}
