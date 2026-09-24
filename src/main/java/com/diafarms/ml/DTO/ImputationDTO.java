package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.StatutMouvement;
import com.diafarms.ml.models.ImputationPaiement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// "Tel paiement règle telle vente (ou tel remboursement) pour tel montant" — voir
// ImputationPaiement.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ImputationDTO {
    private String uniqueId;
    private String paiementUniqueId;
    private LocalDate paiementDate;
    private CibleImputation cibleType;
    private String cibleUniqueId;
    private Double montant;
    private StatutMouvement statut;
    private String motifAnnulation;
    private LocalDateTime createdAt;

    public static ImputationDTO fromEntity(ImputationPaiement i) {
        if (i == null) return null;
        return ImputationDTO.builder()
                .uniqueId(i.getUniqueId())
                .paiementUniqueId(i.getPaiement() != null ? i.getPaiement().getUniqueId() : null)
                .paiementDate(i.getPaiement() != null ? i.getPaiement().getDate() : null)
                .cibleType(i.getCibleType())
                .cibleUniqueId(i.getCibleUniqueId())
                .montant(i.getMontant())
                .statut(i.getStatut())
                .motifAnnulation(i.getMotifAnnulation())
                .createdAt(i.getInitialisation() != null ? i.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
