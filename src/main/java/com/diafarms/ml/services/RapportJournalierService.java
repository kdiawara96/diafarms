package com.diafarms.ml.services;

import com.diafarms.ml.DTO.RapportJournalierDTO;

public interface RapportJournalierService {
    RapportJournalierDTO genererPourProjet(String projetUniqueId);
}
