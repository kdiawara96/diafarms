package com.diafarms.ml.config;

import com.diafarms.ml.commons.VariableEnv;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AESService {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int IV_SIZE = 16;
    
    // Configuration globale du mapper pour accepter les LocalDateTime
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()) // Enregistre le support des dates Java 8
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Force le format texte lisible

    public String encryptObject(Object obj) throws Exception {
        String json = mapper.writeValueAsString(obj);
        return encrypt(json);
    }

    public <T> T decryptObject(String encrypted, Class<T> clazz) throws Exception {
        String json = decrypt(encrypted);
        return mapper.readValue(json, clazz);
    }

    private byte[] getValidatedKeyBytes() {
        String cleanedKey = VariableEnv.get("AES_SECRET_KEY").trim(); 
        byte[] keyBytes = cleanedKey.getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length != 32) {
            byte[] adjustedKey = new byte[32];
            System.arraycopy(keyBytes, 0, adjustedKey, 0, Math.min(keyBytes.length, 32));
            return adjustedKey;
        }
        return keyBytes;
    }

    private String encrypt(String plainText) throws Exception {
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv); 
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        byte[] keyBytes = getValidatedKeyBytes();
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        
        byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[IV_SIZE + encryptedBytes.length];
        System.arraycopy(iv, 0, combined, 0, IV_SIZE);
        System.arraycopy(encryptedBytes, 0, combined, IV_SIZE, encryptedBytes.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String encrypted) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encrypted);

        byte[] iv = new byte[IV_SIZE];
        System.arraycopy(combined, 0, iv, 0, IV_SIZE);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        int encryptedSize = combined.length - IV_SIZE;
        byte[] encryptedBytes = new byte[encryptedSize];
        System.arraycopy(combined, IV_SIZE, encryptedBytes, 0, encryptedSize);

        byte[] keyBytes = getValidatedKeyBytes();
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        return new String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
    }
}