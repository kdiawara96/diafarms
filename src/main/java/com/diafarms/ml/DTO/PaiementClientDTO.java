package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.enums.StatutMouvement;
import com.diafarms.ml.models.PaiementClient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Argent reçu d'un client — voir PaiementClient. impute/disponible sont recalculés à
// la volée (jamais stockés) à partir des ImputationPaiement actives.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PaiementClientDTO {
    private String uniqueId;
    private String clientUniqueId;
    private String clientNom;
    private LocalDate date;
    private Double montant;
    private ModePaiement mode;
    private OriginePaiement origine;
    private String commandeUniqueId;
    private String venteCibleUniqueId;
    private String factureNumero;
    private String observations;
    private String recuParNom;
    private StatutMouvement statut;
    private String motifAnnulation;
    private String annuleParNom;
    private LocalDateTime dateAnnulation;
    private double impute;
    private double disponible;

    public static PaiementClientDTO fromEntity(PaiementClient p, double impute) {
        if (p == null) return null;
        double disponible = p.getStatut() == StatutMouvement.ACTIF ? CalculImputation.arrondi(p.getMontant() - impute) : 0;
        return PaiementClientDTO.builder()
                .uniqueId(p.getUniqueId())
                .clientUniqueId(p.getClient() != null ? p.getClient().getUniqueId() : null)
                .clientNom(p.getClient() != null ? p.getClient().getNom() : null)
                .date(p.getDate())
                .montant(p.getMontant())
                .mode(p.getMode())
                .origine(p.getOrigine())
                .commandeUniqueId(p.getCommande() != null ? p.getCommande().getUniqueId() : null)
                .venteCibleUniqueId(p.getVenteCibleUniqueId())
                .factureNumero(p.getFacture() != null ? p.getFacture().getNumeroFacture() : null)
                .observations(p.getObservations())
                .recuParNom(p.getRecuPar() != null ? p.getRecuPar().getFullName() : null)
                .statut(p.getStatut())
                .motifAnnulation(p.getMotifAnnulation())
                .annuleParNom(p.getAnnulePar() != null ? p.getAnnulePar().getFullName() : null)
                .dateAnnulation(p.getDateAnnulation())
                .impute(impute)
                .disponible(disponible)
                .build();
    }
}
