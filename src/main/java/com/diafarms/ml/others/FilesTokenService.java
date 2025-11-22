package com.diafarms.ml.others;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;

import com.diafarms.ml.commons.VariableEnv;

import java.security.Key;
import java.util.Date;

import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class FilesTokenService {

    public static String generateUrlFiles(String dossier, String image) {
        if (image == null || image.equalsIgnoreCase("")) {
            return "";
        }
        String fullPath = dossier + "/" + image;
        String imageToken = generateImageToken(fullPath, 700);
        String baseUrl = VariableEnv.get("BASE_URL");

        System.out.println("Dossier : " + dossier);
        System.out.println("Image : " + image);
        System.out.println("Chemin complet : " + fullPath);
        System.out.println("Token généré : " + imageToken);
        System.out.println("BASE_URL : " + baseUrl);
        return baseUrl + imageToken;
    }


    public static String generateImageToken(String image, long durationHours) {
        // durationHours = durée du token en heures (ex: 24 pour 1 jour, 700 pour 29 jours)
        long expirationTimeMs = durationHours * 60L * 60L * 1000L; // conversion heures -> ms
        Date expirationDate = new Date(System.currentTimeMillis() + expirationTimeMs);

        return Jwts.builder()
                .setSubject(image)
                .setExpiration(expirationDate)
                .signWith(getSingningKey(), SignatureAlgorithm.HS256)
                .compact();
    }



    // Vérifier et décoder le token pour obtenir le chemin de l'image
    public static String verifyImageToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSingningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();

            // Retourne le chemin de l'image à partir du token
            return claims.getSubject();
        } catch (ExpiredJwtException e) {
            throw new IllegalStateException("Token expiré");
        } catch (Exception e) {
            throw new IllegalStateException("Token invalide");
        }
    }


    public static Key getSingningKey() {
    byte[] keyBytes = Decoders.BASE64.decode(VariableEnv.get("SECRET_KEY"));
     return Keys.hmacShaKeyFor(keyBytes);
    }
}
