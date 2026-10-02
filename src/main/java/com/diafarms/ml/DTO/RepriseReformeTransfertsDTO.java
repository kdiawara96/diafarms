package com.diafarms.ml.DTO;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Rapport de la reprise des réformes anciennes sans magasin de stockage (voir
// ReformeTransfertsManquantsService). Même forme en simulation et en exécution.
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RepriseReformeTransfertsDTO {
    private boolean execute;
    private String farmUniqueId;
    private String farmNom;
    private String magasinStockageUniqueId;
    private String magasinStockageNom;
    // Point de vente par défaut du magasin de stockage (transfert automatique), sinon null :
    // les réformés restent alors au magasin de stockage.
    private String pointDeVenteUniqueId;
    private String pointDeVenteNom;
    // Renseigné si rien n'a pu (ou ne pourrait) être affecté : magasin de stockage indéterminé.
    private String erreur;
    private int totalAffectable;
    private int totalAffecte;
    private int totalTransfere;
    private int reformesAffectees;
    private int transfertsCrees;
    private List<Projet> projets = new ArrayList<>();

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Projet {
        private String projetUniqueId;
        private String projetCode;
        private String projetTitre;
        private int sansMagasin;        // réformés actifs sans magasin de stockage ni transfert lié
        private int dejaTransferes;     // transferts REFORME sans magasin de stockage (manuels anciens)
        private int affectables;         // réformés (réformes entières) que la reprise affecte au magasin
        private int laisses;            // réformés laissés tels quels (déjà couverts par un transfert manuel)
        private int affectes;           // effectivement affectés par ce passage
        private int transferes;         // dont transférés au point de vente par défaut
        private int transfertsCrees;
    }
}
