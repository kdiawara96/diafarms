package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une ligne du stock de médicaments d'un projet : un produit dans une unité.
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMedicamentDTO {
    private String nom;
    private String forme;
    private String unite;
    private double achete;
    private double utilise;
    private double restant;
}
