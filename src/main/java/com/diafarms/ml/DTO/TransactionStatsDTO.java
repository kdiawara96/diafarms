package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TransactionStatsDTO {
    private long nbValide;
    private long nbAttente;
    private long nbRejete;
    private Double totalEntreesValidees;
    private Double totalSortiesValidees;
}
