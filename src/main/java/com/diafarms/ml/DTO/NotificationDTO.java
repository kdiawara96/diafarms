package com.diafarms.ml.DTO;

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
public class NotificationDTO {
    private String key;          // clé stable, ex: "stock-<projetUniqueId>"
    private String type;         // STOCK | MORTALITE | TRANSACTION
    private String level;        // CRITIQUE | WARNING
    private String message;
    private String projetCode;
    private String projetUniqueId;
    private String actionPath;   // route front à ouvrir au clic
    private boolean read;
}
