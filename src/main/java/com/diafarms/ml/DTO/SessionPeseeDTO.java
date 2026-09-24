package com.diafarms.ml.DTO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Assemblé dans une méthode @Transactional du service (open-in-view=false).
// pesees est vide dans la liste, rempli dans le détail et la réponse de synchro.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SessionPeseeDTO {
    private String uniqueId;
    private String projetUniqueId;
    private String projetCode;
    private String statut;
    private Integer nombreParDefaut;
    private LocalDateTime dateDebut;
    private LocalDateTime dateFin;
    private LocalDateTime derniereDatePesee;
    private Integer nombreTotalSujets;
    private Double poidsTotalKg;
    private Double poidsMoyenKg;
    private Integer nombrePesees;      // pesées non annulées
    private String creeParNom;
    @Builder.Default
    private List<PeseeDTO> pesees = new ArrayList<>();
}
