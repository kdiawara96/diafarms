package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.diafarms.ml.models.Soins;

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
public class SoinsDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private String type; // "VACCINATION" | "MEDICAMENT" | "AUTRE"
    private String produit;
    private Double quantite;
    private Double prixUnitaire; // renseigné seulement si type = VACCINATION
    private Double coutTotal;
    private String modeAdministration; // renseigné seulement si type = VACCINATION
    private String observations;
    private String projetCode;
    private String projetUniqueId;
    private String batimentNom;
    private String batimentUniqueId;
    private LocalDateTime createdAt;

    public static SoinsDTO fromEntity(Soins s) {
        if (s == null) return null;

        return SoinsDTO.builder()
                .uniqueId(s.getUniqueId())
                .date(s.getDate())
                .heure(s.getHeure())
                .type(s.getType() != null ? s.getType().name() : null)
                .produit(s.getProduit())
                .quantite(s.getQuantite())
                .prixUnitaire(s.getPrixUnitaire())
                .coutTotal(s.getCoutTotal())
                .modeAdministration(s.getModeAdministration())
                .observations(s.getObservations())
                .projetCode(s.getProjet() != null ? s.getProjet().getCode() : null)
                .projetUniqueId(s.getProjet() != null ? s.getProjet().getUniqueId() : null)
                .batimentNom(s.getBatiment() != null ? s.getBatiment().getNom() : null)
                .batimentUniqueId(s.getBatiment() != null ? s.getBatiment().getUniqueId() : null)
                .createdAt(s.getInitialisation() != null ? s.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
