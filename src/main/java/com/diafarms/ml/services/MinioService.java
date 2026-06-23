package com.diafarms.ml.services;

import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

public interface MinioService {
    
    void ensureBucketExists(String bucketName) throws Exception ;
    String uploadFile(MultipartFile file, String dossier) throws Exception;
    String getPresignedUrl(String nomMinio) throws Exception;
    InputStream downloadFile(String nomMinio) throws Exception;
    void deleteFile(String nomMinio) throws Exception;
}
