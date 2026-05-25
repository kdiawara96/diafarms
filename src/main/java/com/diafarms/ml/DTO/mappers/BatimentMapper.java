package com.diafarms.ml.DTO.mappers;

import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.DTO.BatimentsDTO;

public class BatimentMapper {
    public static BatimentsDTO toDTO(Batiment batiment) {
        if (batiment == null) return null;
        BatimentsDTO dto = new BatimentsDTO();
        dto.setId(batiment.getId());
        dto.setUniqueId(batiment.getUniqueId());
        dto.setNom(batiment.getNom());
        dto.setCapacite(batiment.getCapacite());
        dto.setType(batiment.getType() != null ? batiment.getType().name() : null);
        dto.setStatut(batiment.getStatut() != null ? batiment.getStatut().name() : null);
        dto.setDescription(batiment.getDescription());
        dto.setCreatedAt(batiment.getInitialisation().getCreatedAt());
        dto.setDateDerniereMaintenance(batiment.getDateDerniereMaintenance());
        dto.setSuperficieM2(batiment.getSuperficieM2());
        return dto;
    }

    public static Batiment toEntity(BatimentsDTO dto) {
        if (dto == null) return null;
        Batiment batiment = new Batiment();
        batiment.setId(dto.getId());
        batiment.setUniqueId(dto.getUniqueId());
        batiment.setNom(dto.getNom());
        batiment.setCapacite(dto.getCapacite());
        if (dto.getType() != null) {
            batiment.setType(Batiment.TypeBatiment.valueOf(dto.getType()));
        }
        if (dto.getStatut() != null) {
            batiment.setStatut(Batiment.StatutBatiment.valueOf(dto.getStatut()));
        }
        batiment.setDescription(dto.getDescription());
        batiment.setDateDerniereMaintenance(dto.getDateDerniereMaintenance());
        batiment.setSuperficieM2(dto.getSuperficieM2());
        return batiment;
    }
}
