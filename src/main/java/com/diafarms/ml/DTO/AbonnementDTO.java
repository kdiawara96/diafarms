package com.diafarms.ml.DTO;

import java.time.LocalDate;

import com.diafarms.ml.models.Abonnement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// statutEffectif/enGrace/joursRestants ne sont JAMAIS dérivés de Abonnement seul :
// ils sont calculés par AbonnementServiceImpl.calculerStatutEffectif (voir spec,
// section "Calcul du statut effectif") et passés explicitement ici.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AbonnementDTO {
    private String uniqueId;
    private String farmUniqueId;
    private String farmNom;
    private String statutEffectif;
    private boolean enGrace;
    private LocalDate dateFin;
    private long joursRestants;
    private String periodicite;
    private PaiementAbonnementDTO paiementEnAttente;

    public static AbonnementDTO of(Abonnement a, String statutEffectif, boolean enGrace,
            long joursRestants, PaiementAbonnementDTO paiementEnAttente) {
        return AbonnementDTO.builder()
                .uniqueId(a.getUniqueId())
                .farmUniqueId(a.getFarm() != null ? a.getFarm().getUniqueId() : null)
                .farmNom(a.getFarm() != null ? a.getFarm().getNom() : null)
                .statutEffectif(statutEffectif)
                .enGrace(enGrace)
                .dateFin(a.getDateFin())
                .joursRestants(joursRestants)
                .periodicite(a.getPeriodicite() != null ? a.getPeriodicite().name() : null)
                .paiementEnAttente(paiementEnAttente)
                .build();
    }
}
