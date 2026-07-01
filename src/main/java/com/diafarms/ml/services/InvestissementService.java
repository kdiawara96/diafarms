package com.diafarms.ml.services;


import com.diafarms.ml.DTO.InvestissementDTO;
import com.diafarms.ml.DTO.InvestissementRepartitionDTO;
import com.diafarms.ml.DTO.InvestissementStatsDTO;
import com.diafarms.ml.models.Investissement;
import com.diafarms.ml.models.InvestissementRepartition;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.InvestissementRequest;

import java.util.List;

public interface InvestissementService {
    
    // --- Actions Investissements ---
    // List<InvestissementDTO> getInvestissements(String uniqueId);
    PaginatedResponse<InvestissementDTO> getInvestissementsPagines(String uniqueIdUser, int page, int size);
    InvestissementDTO getInvestissementParUniqueId(String uniqueId);
    InvestissementDTO creerInvestissement(InvestissementRequest investissement, String utilisateurUniqueId);
    InvestissementDTO modifierInvestissement(String uniqueId, Investissement investissementDetails);
    void supprimerInvestissement(String uniqueId);
    InvestissementStatsDTO getInvestissementsStats(String utilisateurUniqueId);

    // --- Actions Répartitions ---
    List<InvestissementRepartitionDTO> getRepartitionsParInvestissement(String uniqueId);
    InvestissementRepartitionDTO ajouterRepartition(String invUniqueId, String projetUniqueId, InvestissementRepartition repartition);
    Double getCoutAmortissementProjet(String projetUniqueId);
}