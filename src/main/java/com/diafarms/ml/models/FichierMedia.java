package com.diafarms.ml.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

@Entity
@Table(name = "fichiers_media")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class FichierMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nom_original", nullable = false)
    private String nomOriginal; // ex: "facture_poussins.pdf"

    @Column(name = "nom_minio", nullable = false, unique = true)
    private String nomMinio; // Le nom unique généré pour MinIO (ex: "uuid-facture_poussins.pdf")

    @Column(name = "bucket_name", nullable = false)
    private String bucketName; // ex: "diafarms-documents", "photos-profil"

    @Column(name = "content_type")
    private String contentType; // ex: "application/pdf", "image/jpeg"

    @Column(name = "taille_octets")
    private Long tailleOctets;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // --- RELATIONS FACULTATIVES (Selon l'usage du fichier) ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id")
    private Projets projet; // Si le fichier est lié à un projet avicole

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm; // Sécurité multi-tenant
}