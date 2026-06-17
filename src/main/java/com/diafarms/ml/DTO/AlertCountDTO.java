package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AlertCountDTO {
    private String label; // ex: "MORTALITE", "ALIMENTATION"
    private Long count;   // Le nombre d'alertes trouvées
}