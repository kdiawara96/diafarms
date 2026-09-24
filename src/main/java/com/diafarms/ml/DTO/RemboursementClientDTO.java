package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.StatutMouvement;
import com.diafarms.ml.models.RemboursementClient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Argent rendu à un client — voir RemboursementClient.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RemboursementClientDTO {
    private String uniqueId;
    private LocalDate date;
    private Double montant;
    private ModePaiement mode;
    private String motif;
    private String commandeUniqueId;
    private String effectueParNom;
    private StatutMouvement statut;
    private String motifAnnulation;
    private LocalDateTime dateAnnulation;

    public static RemboursementClientDTO fromEntity(RemboursementClient r) {
        if (r == null) return null;
        return RemboursementClientDTO.builder()
                .uniqueId(r.getUniqueId())
                .date(r.getDate())
                .montant(r.getMontant())
                .mode(r.getMode())
                .motif(r.getMotif())
                .commandeUniqueId(r.getCommande() != null ? r.getCommande().getUniqueId() : null)
                .effectueParNom(r.getEffectuePar() != null ? r.getEffectuePar().getFullName() : null)
                .statut(r.getStatut())
                .motifAnnulation(r.getMotifAnnulation())
                .dateAnnulation(r.getDateAnnulation())
                .build();
    }
}
