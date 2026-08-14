package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.models.PaiementSalaire;

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
public class PaiementSalaireDTO {
    private String uniqueId;
    private String employeNom;
    private String periode;
    private Double montantPaye;
    private Double quantite;
    private LocalDate datePaiement;
    private String creeParNom;
    private LocalDateTime createdAt;

    public static PaiementSalaireDTO fromEntity(PaiementSalaire p) {
        if (p == null) return null;
        return PaiementSalaireDTO.builder()
                .uniqueId(p.getUniqueId())
                .employeNom(p.getSalaire() != null && p.getSalaire().getEmploye() != null ? p.getSalaire().getEmploye().getFullName() : null)
                .periode(p.getPeriode())
                .montantPaye(p.getMontantPaye())
                .quantite(p.getQuantite())
                .datePaiement(p.getDatePaiement())
                .creeParNom(p.getCreePar() != null ? p.getCreePar().getFullName() : null)
                .createdAt(p.getInitialisation() != null ? p.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
