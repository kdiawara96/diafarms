
package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
	private String localisation;

	// Getters & Setters
	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public String getUniqueId() { return uniqueId; }
	public void setUniqueId(String uniqueId) { this.uniqueId = uniqueId; }
	public String getNom() { return nom; }
	public void setNom(String nom) { this.nom = nom; }
	public Integer getCapacite() { return capacite; }
	public void setCapacite(Integer capacite) { this.capacite = capacite; }
	public String getType() { return type; }
	public void setType(String type) { this.type = type; }
	public String getStatut() { return statut; }
	public void setStatut(String statut) { this.statut = statut; }
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public LocalDate getDateDerniereMaintenance() { return dateDerniereMaintenance; }
	public void setDateDerniereMaintenance(LocalDate dateDerniereMaintenance) { this.dateDerniereMaintenance = dateDerniereMaintenance; }
	public Double getSuperficieM2() { return superficieM2; }
	public void setSuperficieM2(Double superficieM2) { this.superficieM2 = superficieM2; }
	public String getLocalisation() { return localisation; }
	public void setLocalisation(String localisation) { this.localisation = localisation; }
}
