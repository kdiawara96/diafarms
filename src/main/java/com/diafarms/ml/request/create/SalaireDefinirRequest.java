package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class SalaireDefinirRequest {
    private String employeUniqueId;
    private Double montantMensuel;
}
