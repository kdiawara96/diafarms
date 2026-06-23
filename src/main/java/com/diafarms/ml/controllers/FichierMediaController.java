package com.diafarms.ml.controllers;

import java.io.InputStream;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.diafarms.ml.DTO.FichierMediaDTO;
import com.diafarms.ml.services.FichierMediaService;
import com.diafarms.ml.services.MinioService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/files") 
@RequiredArgsConstructor
public class FichierMediaController {

    private final FichierMediaService services;
    private final MinioService minioService;
 
    /**
     * Upload un fichier pour un projet
     */

    @PostMapping("/upload/{projetUniqueId}")
    public ResponseEntity<?> uploadFichier(
            @PathVariable String projetUniqueId,
            @RequestParam("file") MultipartFile file) {

        try {
            FichierMediaDTO fichier = services.uploadFichierProjet(file, projetUniqueId);
            return ResponseEntity.ok(fichier);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Erreur upload : " + e.getMessage());
        }
    }

    /**
     * Liste des fichiers d'un projet
     */
    @GetMapping("/projet/{projetUniqueId}")
    public ResponseEntity<List<FichierMediaDTO>> getFichiersProjet(@PathVariable String projetUniqueId) {
        return ResponseEntity.ok(services.getFichiersByProjet(projetUniqueId));
    }

    /**
     * Supprimer un fichier
     */
    @DeleteMapping("/{fichierId}")
    public ResponseEntity<?> deleteFichier(@PathVariable Long fichierId) {
        try {
            services.deleteFichier(fichierId);
            return ResponseEntity.ok("Fichier supprimé");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Erreur suppression : " + e.getMessage());
        }
    }

    /**
     * Télécharger un fichier (streaming)
     */
    @GetMapping("/download/{fichierId}")
    public ResponseEntity<?> downloadFichier(@PathVariable Long fichierId) {
        try {
            FichierMediaDTO fichier = services.getFichierById(fichierId);
            
            InputStream stream = minioService.downloadFile(fichier.getNomMinio());
            byte[] bytes = stream.readAllBytes();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"" + fichier.getNomOriginal() + "\"")
                    .contentType(MediaType.parseMediaType(fichier.getContentType()))
                    .body(bytes);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Erreur download : " + e.getMessage());
        }
    }
    
}
