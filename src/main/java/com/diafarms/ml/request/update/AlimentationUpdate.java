package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class AlimentationUpdate {
    
    private String nomAliment;
    private Double sac;
    private Double quantiteKg;
    private Double coutTotal;
    private String dateDistribution;
    private String observations;
}
