package com.diafarms.ml.services;


import com.diafarms.ml.DTO.InvestissementDTO;
import com.diafarms.ml.DTO.InvestissementRepartitionDTO;
import com.diafarms.ml.models.Investissement;
import com.diafarms.ml.models.InvestissementRepartition;

import java.util.List;

public interface InvestissementService {
    
    // --- Actions Investissements ---
    List<InvestissementDTO> getInvestissementsParFerme(Long farmId);
    InvestissementDTO getInvestissementParUniqueId(String uniqueId);
    InvestissementDTO creerInvestissement(Investissement investissement, Long farmId, Long utilisateurId);
    InvestissementDTO modifierInvestissement(String uniqueId, Investissement investissementDetails);
    void supprimerInvestissement(String uniqueId);

    // --- Actions Répartitions ---
    List<InvestissementRepartitionDTO> getRepartitionsParInvestissement(String uniqueId);
    InvestissementRepartitionDTO ajouterRepartition(String invUniqueId, String projetUniqueId, InvestissementRepartition repartition);
    Double getCoutAmortissementProjet(String projetUniqueId);
}