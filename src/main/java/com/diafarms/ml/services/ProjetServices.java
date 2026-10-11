package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.ProjetAssigneDTO;
import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.DTO.ProjetsSelect;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ProjetCreate;
import com.diafarms.ml.request.update.ProjetUpdate;

public interface ProjetServices {

    PaginatedResponse<ProjetsDTO> getAllProjets(int page, int size, String search, String filter);
    ProjetsDTO getProjetByUniqueId(String uniqueId);
    ProjetsDTO createProjet(ProjetCreate data);
    ProjetsDTO updateProjet(String uniqueId, ProjetUpdate data);
    String deleteOrRecoverProjet(String uniqueId);
    String cloturerProjet(String uniqueId);

    // dateCloture null = aujourd'hui. Jamais dans le futur, avant le début du Projet ni
    // avant sa dernière saisie d'élevage.
    default String cloturerProjet(String uniqueId, java.time.LocalDate dateCloture) {
        return cloturerProjet(uniqueId);
    }
    String rouvrirProjet(String uniqueId);
    String transfererStock(String projetSourceUniqueId, String projetCibleUniqueId);

    // Transfert daté du jour de la clôture choisi (null = aujourd'hui), mêmes règles.
    default String transfererStock(String projetSourceUniqueId, String projetCibleUniqueId, java.time.LocalDate date) {
        return transfererStock(projetSourceUniqueId, projetCibleUniqueId);
    }

    List<ProjetsSelect> selectEntity();

    List<ProjetAssigneDTO> getProjetsAssignes(String userUniqueId, int limit);

}
