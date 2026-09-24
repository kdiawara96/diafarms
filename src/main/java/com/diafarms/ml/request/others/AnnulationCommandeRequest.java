package com.diafarms.ml.request.others;

import lombok.Data;

// Corps de PUT /commandes/{uid}/annuler — voir CommandeServiceImpl.annuler. Le motif
// est obligatoire (MotifSuppressionRequest.exiger) ; rembourserAcompte déclenche le
// remboursement en argent de ce qui reste disponible du côté de cette commande (sinon
// ça reste une avance sur le compte du client) ; mode = mode de paiement du
// remboursement, ESPECES par défaut.
@Data
public class AnnulationCommandeRequest {
    private String motif;
    private Boolean rembourserAcompte;
    private String mode;
}
