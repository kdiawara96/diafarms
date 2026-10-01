package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Ventes d'un projet et argent déjà reçu pour elles (GET /projets/{uniqueId}/encaissement,
// voir EncaissementProjetService). Rien n'est stocké : tout se recalcule à partir des
// répartitions des ventes actives et des imputations actives des paiements clients.
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncaissementProjetDTO {
    private String projetUniqueId;
    // Part du projet dans ses ventes actives (œufs et réforme, avec ou sans client).
    private Double vendu;
    // Argent reçu pour cette part : paiements des clients imputés sur ces ventes, au
    // prorata de la part du projet, plus le montant rapporté des ventes sans client.
    private Double encaisse;
    // vendu - encaisse, jamais négatif.
    private Double resteAEncaisser;
    // Détail de l'encaissé.
    private Double encaisseClients;
    private Double encaisseSansClient;
}
