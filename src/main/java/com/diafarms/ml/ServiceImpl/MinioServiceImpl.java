package com.diafarms.ml.ServiceImpl;

import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.diafarms.ml.commons.VariableEnv;
import com.diafarms.ml.services.MinioService;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class MinioServiceImpl  implements MinioService{

    private final MinioClient minioClient;

    /**
     * Vérifie si le bucket existe, sinon le crée
     */
    public void ensureBucketExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(
            BucketExistsArgs.builder().bucket(bucketName).build()
        );
        
        if (!exists) {
            minioClient.makeBucket(
                MakeBucketArgs.builder().bucket(bucketName).build()
            );
        }
    }

    /**
     * Upload un fichier et retourne le nom unique MinIO
     */
    public String uploadFile(MultipartFile file, String dossier) throws Exception {
        String bucketName = VariableEnv.get("MINIO_BUCKET_NAME");
        ensureBucketExists(bucketName);
        
        String nomUnique = dossier + "/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
        
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .object(nomUnique)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build()
        );
        
        return nomUnique;
    }

    /**
     * Génère une URL pré-signée pour accès direct (valide 1 heure)
     */
    public String getPresignedUrl(String nomMinio) throws Exception {
        return minioClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(VariableEnv.get("MINIO_BUCKET_NAME"))
                .object(nomMinio)
                .expiry(1, TimeUnit.HOURS)
                .build()
        );
    }

    /**
     * Télécharger un fichier (stream)
     */
    public InputStream downloadFile(String nomMinio) throws Exception {
        return minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(VariableEnv.get("MINIO_BUCKET_NAME"))
                .object(nomMinio)
                .build()
        );
    }

    /**
     * Supprimer un fichier
     */
    public void deleteFile(String nomMinio) throws Exception {
        minioClient.removeObject(
            RemoveObjectArgs.builder()
                .bucket(VariableEnv.get("MINIO_BUCKET_NAME"))
                .object(nomMinio)
                .build()
        );
    }
}