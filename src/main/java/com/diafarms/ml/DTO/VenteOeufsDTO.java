package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.diafarms.ml.models.VenteOeufs;

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
public class VenteOeufsDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private String magasinUniqueId;
    private String magasinNom;
    private String clientUniqueId;
    private String clientNom;
    private Integer quantiteOeufs;
    private Double prixUnitaire;
    private Double montant;
    private Double montantRapporte;
    private String creeParNom;
    private LocalDateTime createdAt;
    // Part de chaque projet contributeur dans cette vente (voir VenteOeufsRepartition) —
    // permet d'afficher qui a apporté quoi, la vraie donnée reste les Transactions
    // générées une par projet.
    private List<VenteOeufsRepartitionDTO> repartitions;

    public static VenteOeufsDTO fromEntity(VenteOeufs v) {
        if (v == null) return null;

        return VenteOeufsDTO.builder()
                .uniqueId(v.getUniqueId())
                .date(v.getDate())
                .heure(v.getHeure())
                .magasinUniqueId(v.getMagasin() != null ? v.getMagasin().getUniqueId() : null)
                .magasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null)
                .clientUniqueId(v.getClient() != null ? v.getClient().getUniqueId() : null)
                .clientNom(v.getClient() != null ? v.getClient().getNom() : null)
                .quantiteOeufs(v.getQuantiteOeufs())
                .prixUnitaire(v.getPrixUnitaire())
                .montant(v.getMontant())
                .montantRapporte(v.getMontantRapporte())
                .creeParNom(v.getCreePar() != null ? v.getCreePar().getFullName() : null)
                .createdAt(v.getInitialisation() != null ? v.getInitialisation().getCreatedAt() : null)
                .repartitions(v.getRepartitions() != null ? v.getRepartitions().stream()
                        .map(VenteOeufsRepartitionDTO::fromEntity)
                        .toList() : java.util.Collections.emptyList())
                .build();
    }
}
