package com.diafarms.ml.DTO.mappers;

import com.diafarms.ml.models.Race;
import com.diafarms.ml.DTO.RaceDTO;

public class RaceMapper {
    public static RaceDTO toDTO(Race race) {
        if (race == null) return null;
        RaceDTO dto = new RaceDTO();
        dto.setId(race.getId());
        dto.setUniqueId(race.getUniqueId());
        dto.setNom(race.getNom());
        dto.setType(race.getType() != null ? race.getType().name() : null);
        dto.setOrigine(race.getOrigine());
        dto.setDescription(race.getDescription());
        dto.setDateCreation(race.getDateCreation());
        dto.setEsperanceVieAnnees(race.getEsperanceVieAnnees());
        dto.setPoidsAdulteKg(race.getPoidsAdulteKg());
        dto.setProductionOeufsAn(race.getProductionOeufsAn());
        dto.setCouleurOeuf(race.getCouleurOeuf() != null ? race.getCouleurOeuf().name() : null);
        dto.setTempsCroissance(race.getTempsCroissance() != null ? race.getTempsCroissance().name() : null);
        dto.setPoidsAbattage(race.getPoidsAbattage() != null ? race.getPoidsAbattage().name() : null);
        dto.setRusticite(race.getRusticite() != null ? race.getRusticite().name() : null);
        dto.setAdaptationClimat(race.getAdaptationClimat() != null ? race.getAdaptationClimat().name() : null);
        dto.setCertificationRace(race.getCertificationRace() != null ? race.getCertificationRace().name() : null);
        return dto;
    }

    public static Race toEntity(RaceDTO dto) {
        if (dto == null) return null;
        Race race = new Race();
        race.setId(dto.getId());
        race.setUniqueId(dto.getUniqueId());
        race.setNom(dto.getNom());
        if (dto.getType() != null) {
            race.setType(Race.TypeRace.valueOf(dto.getType()));
        }
        race.setOrigine(dto.getOrigine());
        race.setDescription(dto.getDescription());
        race.setDateCreation(dto.getDateCreation());
        race.setEsperanceVieAnnees(dto.getEsperanceVieAnnees());
        race.setPoidsAdulteKg(dto.getPoidsAdulteKg());
        race.setProductionOeufsAn(dto.getProductionOeufsAn());
        if (dto.getCouleurOeuf() != null) {
            race.setCouleurOeuf(Race.CouleurOeuf.valueOf(dto.getCouleurOeuf()));
        }
        if (dto.getTempsCroissance() != null) {
            race.setTempsCroissance(Race.TempsCroissance.valueOf(dto.getTempsCroissance()));
        }
        if (dto.getPoidsAbattage() != null) {
            race.setPoidsAbattage(Race.PoidsAbattage.valueOf(dto.getPoidsAbattage()));
        }
        if (dto.getRusticite() != null) {
            race.setRusticite(Race.Rusticite.valueOf(dto.getRusticite()));
        }
        if (dto.getAdaptationClimat() != null) {
            race.setAdaptationClimat(Race.AdaptationClimat.valueOf(dto.getAdaptationClimat()));
        }
        if (dto.getCertificationRace() != null) {
            race.setCertificationRace(Race.CertificationRace.valueOf(dto.getCertificationRace()));
        }
        return race;
    }
}
