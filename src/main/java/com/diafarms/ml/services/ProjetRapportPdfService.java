package com.diafarms.ml.services;

import java.time.LocalDate;

public interface ProjetRapportPdfService {

    /** Rapport PDF d'un projet (identité, production, aliment, santé, finances) sur la
     * période demandée ; dateDebut/dateFin null = du début du projet à aujourd'hui. */
    byte[] generer(String projetUniqueId, LocalDate dateDebut, LocalDate dateFin);
}
