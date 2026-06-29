package com.diafarms.ml.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class QrCodeEncrypte {
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime qrGeneratedAt;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime qrExpiresAt;

    private String role;
    private String uniqueIdUser;
    private String fullNameUser;
    private String token;
}