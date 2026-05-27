package com.diafarms.ml.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogsDTO {

    private Long id;
    private String uniqueId;
    
    // On remplace userId par fullName
    private String fullName;  // ← "Prénom NOM" de l'utilisateur
    
    private Long entityId;
    private String entityType;
    private String action;
    private String ipAddress;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}