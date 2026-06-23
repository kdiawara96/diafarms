package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.FichierMedia;
import com.fasterxml.jackson.annotation.JsonFormat;

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
public class FichierMediaDTO {
    
    private Long id;
    private String nomOriginal;
    private String nomMinio;
    private String bucketName;
    private String contentType;
    private Long tailleOctets;

    private String url;
    private String tailleFormatee;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;

        /**
     * Mapper basique (sans URL)
     */
    public static FichierMediaDTO fromEntity(FichierMedia data) {
        if (data == null) {
            return null;
        }

        return FichierMediaDTO.builder()
                .id(data.getId())
                .nomOriginal(data.getNomOriginal())
                .nomMinio(data.getNomMinio())
                .bucketName(data.getBucketName())
                .contentType(data.getContentType())
                .tailleOctets(data.getTailleOctets())
                .createdAt(data.getCreatedAt())
                .tailleFormatee(formatTaille(data.getTailleOctets()))
                .build();
    }

    /**
     * Mapper avec URL pré-signée
     */
    public static FichierMediaDTO fromEntity(FichierMedia data, String presignedUrl) {
        if (data == null) {
            return null;
        }

        return FichierMediaDTO.builder()
                .id(data.getId())
                .nomOriginal(data.getNomOriginal())
                .nomMinio(data.getNomMinio())
                .bucketName(data.getBucketName())
                .contentType(data.getContentType())
                .tailleOctets(data.getTailleOctets())
                .url(presignedUrl)
                .tailleFormatee(formatTaille(data.getTailleOctets()))
                .createdAt(data.getCreatedAt())
                .build();
    }


     private static String formatTaille(long octets) {
        if (octets < 1024) return octets + " B";
        if (octets < 1024 * 1024) return String.format("%.1f KB", octets / 1024.0);
        return String.format("%.1f MB", octets / (1024.0 * 1024));
    }
}
