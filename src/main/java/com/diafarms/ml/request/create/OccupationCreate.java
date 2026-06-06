package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class OccupationCreate {
    private Long batimentId; // Correspond à occupation.batimentId du Front
    private String dateEntree;
    private String dateSortie;
    private Integer nbSujets; // Correspond à occupation.nbSujets du Front
}