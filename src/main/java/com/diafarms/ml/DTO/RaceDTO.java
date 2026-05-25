package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

public class RaceDTO {
    private Long id;
    private String uniqueId;
    private String nom;
     private String identifiant; // Un identifiant court et lisible pour les utilisateurs (ex: RAC-001, RAC-002, etc.)
    private String type;
    private String origine;
    private String description;
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;
    private Integer esperanceVieAnnees;
    private Double poidsAdulteKg;
    private Integer productionOeufsAn;
    private String couleurOeuf;
    private String tempsCroissance;
    private String poidsAbattage;
    private String rusticite;
    private String adaptationClimat;
    private String certificationRace;

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUniqueId() { return uniqueId; }
    public void setUniqueId(String uniqueId) { this.uniqueId = uniqueId; }
    public String getIdentifiant() { return identifiant; }
    public void setIdentifiant(String identifiant) { this.identifiant = identifiant; }
    public String getNom() { return nom; }
    public void setNom(String nom) { this.nom = nom; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getOrigine() { return origine; }
    public void setOrigine(String origine) { this.origine = origine; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getEsperanceVieAnnees() { return esperanceVieAnnees; }
    public void setEsperanceVieAnnees(Integer esperanceVieAnnees) { this.esperanceVieAnnees = esperanceVieAnnees; }
    public Double getPoidsAdulteKg() { return poidsAdulteKg; }
    public void setPoidsAdulteKg(Double poidsAdulteKg) { this.poidsAdulteKg = poidsAdulteKg; }
    public Integer getProductionOeufsAn() { return productionOeufsAn; }
    public void setProductionOeufsAn(Integer productionOeufsAn) { this.productionOeufsAn = productionOeufsAn; }
    public String getCouleurOeuf() { return couleurOeuf; }
    public void setCouleurOeuf(String couleurOeuf) { this.couleurOeuf = couleurOeuf; }
    public String getTempsCroissance() { return tempsCroissance; }
    public void setTempsCroissance(String tempsCroissance) { this.tempsCroissance = tempsCroissance; }
    public String getPoidsAbattage() { return poidsAbattage; }
    public void setPoidsAbattage(String poidsAbattage) { this.poidsAbattage = poidsAbattage; }
    public String getRusticite() { return rusticite; }
    public void setRusticite(String rusticite) { this.rusticite = rusticite; }
    public String getAdaptationClimat() { return adaptationClimat; }
    public void setAdaptationClimat(String adaptationClimat) { this.adaptationClimat = adaptationClimat; }
    public String getCertificationRace() { return certificationRace; }
    public void setCertificationRace(String certificationRace) { this.certificationRace = certificationRace; }
}
