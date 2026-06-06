package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class AlimentationCreate {
    private String nomAliment;
    private Double sac;
    private Double quantiteKg;
    private Double coutTotal;
    private String dateDistribution;
    private String observations;
}