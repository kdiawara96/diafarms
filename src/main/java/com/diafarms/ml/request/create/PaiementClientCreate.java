package com.diafarms.ml.request.create;
import lombok.Data;
@Data
public class PaiementClientCreate {
    private String clientUniqueId;      // obligatoire
    private Double montant;             // > 0
    private String mode;                // ModePaiement, défaut ESPECES
    private String origine;             // OriginePaiement, défaut REGLEMENT
    private String date;                // yyyy-MM-dd, défaut aujourd'hui
    private String commandeUniqueId;    // facultatif
    private String venteCibleType;      // facultatif : VENTE_OEUFS | VENTE_REFORME
    private String venteCibleUniqueId;  // facultatif
    private String factureUniqueId;     // facultatif (paiement d'une facture)
    private String observations;
}
