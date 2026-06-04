package com.diafarms.ml.request.create;

import lombok.Data;
import java.time.LocalDate;

@Data
public class OccupationCreate {
    private String batiment; // Nom ou ID du bâtiment sélectionné
    private LocalDate dateEntree;
    private LocalDate dateSortie;
    private Integer nbSujets;
}