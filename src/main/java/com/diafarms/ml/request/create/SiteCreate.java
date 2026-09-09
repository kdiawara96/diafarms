package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class SiteCreate {
    private String nom;
    private String localisation; // optionnel
    private Double latitude; // optionnel
    private Double longitude; // optionnel
}
