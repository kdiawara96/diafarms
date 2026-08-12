package com.diafarms.ml.DTO;

import java.time.LocalDate;

import com.diafarms.ml.models.PaiementSalaire;
import com.diafarms.ml.models.Salaire;

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
public class SalaireDTO {
    private String uniqueId;
    private String employeUniqueId;
    private String employeNom;
    private Double montantMensuel;
    private String dernierPaiementPeriode;
    private LocalDate dernierPaiementDate;
    private Double dernierPaiementMontant;

    public static SalaireDTO fromEntity(Salaire s, PaiementSalaire dernier) {
        if (s == null) return null;
        return SalaireDTO.builder()
                .uniqueId(s.getUniqueId())
                .employeUniqueId(s.getEmploye() != null ? s.getEmploye().getUniqueId() : null)
                .employeNom(s.getEmploye() != null ? s.getEmploye().getFullName() : null)
                .montantMensuel(s.getMontantMensuel())
                .dernierPaiementPeriode(dernier != null ? dernier.getPeriode() : null)
                .dernierPaiementDate(dernier != null ? dernier.getDatePaiement() : null)
                .dernierPaiementMontant(dernier != null ? dernier.getMontantPaye() : null)
                .build();
    }
}
