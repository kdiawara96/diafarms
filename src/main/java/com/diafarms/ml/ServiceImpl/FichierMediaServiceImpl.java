package com.diafarms.ml.ServiceImpl;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.diafarms.ml.DTO.FichierMediaDTO;
import com.diafarms.ml.commons.VariableEnv;
import com.diafarms.ml.models.FichierMedia;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.repository.FichierMediaRepository;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.services.FichierMediaService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FichierMediaServiceImpl implements FichierMediaService{
            
        private final MinioServiceImpl minioService;
        private final FichierMediaRepository fichierMediaRepository;
        private final ProjetsRepo projetsRepository;

        @Transactional
        public FichierMediaDTO uploadFichierProjet(MultipartFile file, String projetUniqueId) throws Exception {
            // Récupérer le projet
            Projets projet = projetsRepository.findByUniqueId(projetUniqueId)
                    .orElseThrow(() -> new RuntimeException("Projet non trouvé : " + projetUniqueId));

            // Upload vers MinIO
            String nomMinio = minioService.uploadFile(file, "projets/" + projetUniqueId);

            // Créer l'entité
            FichierMedia fichier = new FichierMedia();
            fichier.setNomOriginal(file.getOriginalFilename());
            fichier.setNomMinio(nomMinio);
            fichier.setBucketName(VariableEnv.get("MINIO_BUCKET_NAME"));
            fichier.setContentType(file.getContentType());
            fichier.setTailleOctets(file.getSize());
            fichier.setCreatedAt(LocalDateTime.now());
            fichier.setProjet(projet);
            fichier.setFarm(projet.getFarm());

            FichierMedia saved = fichierMediaRepository.save(fichier);
            
            return FichierMediaDTO.fromEntity(saved, minioService.getPresignedUrl(saved.getNomMinio()));
        }

        @Transactional(readOnly = true)
        public List<FichierMediaDTO> getFichiersByProjet(String projetUniqueId) {
            List<FichierMedia> fichiers = fichierMediaRepository.findByProjetUniqueId(projetUniqueId);
            
            return fichiers.stream().map(f -> {
                try {
                    String url = minioService.getPresignedUrl(f.getNomMinio());
                    return FichierMediaDTO.fromEntity(f, url);
                } catch (Exception e) {
                    return FichierMediaDTO.fromEntity(f, null);
                }
            }).toList();
        }

        @Transactional
        public String deleteFichier(Long fichierId) throws Exception {
            FichierMedia fichier = fichierMediaRepository.findById(fichierId)
                    .orElseThrow(() -> new RuntimeException("Fichier non trouvé"));

            // Supprimer de MinIO
            minioService.deleteFile(fichier.getNomMinio());

            // Supprimer de la BDD
            fichierMediaRepository.delete(fichier);

            return "Fichier supprimé";
        }


        @Transactional(readOnly = true)
        public FichierMediaDTO getFichierById(Long fichierId) {
            FichierMedia fichier = fichierMediaRepository.findById(fichierId)
                    .orElseThrow(() -> new RuntimeException("Fichier non trouvé"));
            
            try {
                String url = minioService.getPresignedUrl(fichier.getNomMinio());
                return FichierMediaDTO.fromEntity(fichier, url);
            } catch (Exception e) {
                return FichierMediaDTO.fromEntity(fichier, null);
            }
        }
    
    
}
