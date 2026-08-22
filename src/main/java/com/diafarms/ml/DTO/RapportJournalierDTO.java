package com.diafarms.ml.DTO;

import java.util.List;

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
public class RapportJournalierDTO {
    private String projetCode;
    private String projetTitre;
    private Integer nbSujetsDepart;
    // Achat sujets + Autres charges (transactions générées à la création du projet,
    // voir ProjetImpl.syncAchatSujets/syncAutresCharges) — le capital à récupérer
    // avant que le projet ne soit "amorti", voir RapportJournalierLigneDTO.resteAAmortir.
    private Double depensesInitiales;
    private List<RapportJournalierLigneDTO> lignes;
}
