package com.diafarms.ml.DTO;

import java.time.LocalDate;

import com.diafarms.ml.commons.AbonnementEcheance;
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
    // Délai de grâce (voir AbonnementEcheance) : nombre de jours configuré, dernier jour
    // d'accès (dateFin + délai) et jours d'accès restants pendant la grâce (aujourd'hui
    // compris, 0 hors grâce).
    private boolean estEssai;
    private int delaiGraceJours;
    private LocalDate dernierJourAcces;
    private long joursGraceRestants;
    // Suspension manuelle par le SUPER_ADMIN : statutEffectif vaut alors EXPIRE (les
    // anciens clients bloquent sans connaître ce champ), le web récent affiche le motif.
    private boolean suspendu;
    private String motifSuspension;
    private java.time.LocalDateTime suspenduLe;
    // Prix de la ferme (prix par poule, ou tarif spécial) : ce qu'elle paiera au prochain
    // renouvellement. Voir AbonnementTarifService. Champ ajouté, rien n'a été retiré.
    private AbonnementTarifDTO tarif;

    public static AbonnementDTO of(Abonnement a, AbonnementEcheance.Etat etat, PaiementAbonnementDTO paiementEnAttente) {
        AbonnementDTO dto = of(a, etat.statut(), etat.enGrace(), etat.joursRestants(), paiementEnAttente);
        dto.setEstEssai(etat.estEssai());
        dto.setDelaiGraceJours(etat.delaiGraceJours());
        dto.setDernierJourAcces(etat.dernierJourAcces());
        dto.setJoursGraceRestants(etat.joursGraceRestants());
        dto.setSuspendu(etat.suspendu());
        if (etat.suspendu()) {
            dto.setMotifSuspension(a.getMotifSuspension());
            dto.setSuspenduLe(a.getSuspenduLe());
        }
        return dto;
    }

    public static AbonnementDTO of(Abonnement a, String statutEffectif, boolean enGrace,
            long joursRestants, PaiementAbonnementDTO paiementEnAttente) {
        return AbonnementDTO.builder()
                .uniqueId(a.getUniqueId())
                .farmUniqueId(a.getFarm() != null ? a.getFarm().getUniqueId() : null)
                .farmNom(a.getFarm() != null
                        ? (a.getFarm().getNom() != null ? a.getFarm().getNom() : a.getFarm().getUniqueId())
                        : null)
                .statutEffectif(statutEffectif)
                .enGrace(enGrace)
                .dateFin(a.getDateFin())
                .joursRestants(joursRestants)
                .periodicite(a.getPeriodicite() != null ? a.getPeriodicite().name() : null)
                .paiementEnAttente(paiementEnAttente)
                .build();
    }
}
