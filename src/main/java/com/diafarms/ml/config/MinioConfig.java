package com.diafarms.ml.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.diafarms.ml.commons.VariableEnv;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(VariableEnv.get("MINIO_URL"))
                .credentials(VariableEnv.get("MINIO_ACCESS_KEY"), VariableEnv.get("MINIO_SECRET_KEY"))
                .build();
    }
}
