
package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.models.Batiment;

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
public class BatimentsDTO {
	private Long id;
	private String uniqueId;
	private String nom;
	private Integer capacite;
	private String type;
	private String statut;
	private String description;
	private LocalDateTime createdAt;
	private LocalDate dateDerniereMaintenance;
	private Double superficieM2;

	public static BatimentsDTO toDTO(Batiment batiment) {
		if (batiment == null) return null;

		return BatimentsDTO.builder()
				.id(batiment.getId())
				.uniqueId(batiment.getUniqueId())
				.nom(batiment.getNom())
				.capacite(batiment.getCapacite())
				.type(batiment.getType() != null ? batiment.getType().name() : null)
				.statut(batiment.getStatut() != null ? batiment.getStatut().name() : null)
				.description(batiment.getDescription())
				.createdAt(
					batiment.getInitialisation() != null 
						? batiment.getInitialisation().getCreatedAt()
						: null
				)
				.dateDerniereMaintenance(batiment.getDateDerniereMaintenance())
				.superficieM2(batiment.getSuperficieM2())
				.build();
	}

	public static BatimentsDTO select(Batiment batiment) {
		if (batiment == null) return null;

		return BatimentsDTO.builder()
				.id(batiment.getId())
				.uniqueId(batiment.getUniqueId())
				.nom(batiment.getNom())
				.capacite(batiment.getCapacite())
				.type(batiment.getType() != null ? batiment.getType().name() : null)
				.superficieM2(batiment.getSuperficieM2())
				.build();
	}


}
