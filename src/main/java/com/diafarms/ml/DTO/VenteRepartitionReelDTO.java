package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;

// Projection brute d'une ligne de répartition (VenteOeufsRepartition/
// VenteReformeRepartition) + le montant théorique/rapporté de LA VENTE ENTIÈRE dont
// elle fait partie — sert à calculer, projet par projet, quelle part de l'écart
// théorique/réel de la ferme lui revient (voir TransactionServiceImpl.
// getVentesReelParProjet) : montantAttribue est la part théorique du projet dans
// cette vente précise, à corriger au même prorata que la vente entière
// (venteMontantRapporte / venteMontant) puisque rien ne dit QUELS œufs précis n'ont
// pas été payés parmi ceux vendus ce jour-là — la répartition proportionnelle est
// la meilleure hypothèse disponible, la même déjà utilisée pour attribuer le
// théorique lui-même entre projets contributeurs.
@Getter
@AllArgsConstructor
public class VenteRepartitionReelDTO {
    private String projetUniqueId;
    private String projetCode;
    private Double montantAttribue;
    private Double venteMontant;
    private Double venteMontantRapporte; // null = pas d'écart déclaré pour cette vente
}
