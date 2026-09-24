package com.diafarms.ml.request.create;
import lombok.Data;
@Data
public class RemboursementClientCreate {
    private String clientUniqueId;
    private Double montant;
    private String mode;
    private String motif;               // obligatoire (≥ 3 caractères)
    private String commandeUniqueId;    // facultatif
}
