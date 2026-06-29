package com.diafarms.ml.DTO;

import lombok.Data;

@Data
public class QRCodeRequestDTO {
    
    private String uniqueId;
    private String duration; // "24h", "7d", "30d", "permanent"
}