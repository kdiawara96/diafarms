package com.diafarms.ml.commons;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;
import java.security.SecureRandom;
import java.time.LocalDateTime;

@Data
@Embeddable
public class Initialisation {
    
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    @Column(name = "created_at", length = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    @Column(name = "updated_at", length = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;
    
    @Column(name = "removed")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean removed = false;

    @Column(name = "archive")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean archive = false;

        public Initialisation() {
        // Ne rien faire, on laissera le service définir createAt
    }

    public static Initialisation init() {
        Initialisation init = new Initialisation();
        init.setCreatedAt(LocalDateTime.now());
        return init;
    }

    public static Initialisation updateDate(Initialisation initialisation){
        initialisation.setUpdatedAt(LocalDateTime.now());
        return initialisation;
    }

    public static String generateUniqueId() {
        final String UPPER_CASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        final String LOWER_CASE = "abcdefghijklmnopqrstuvwxyz";
        final int UNIQUE_ID = 20;
        StringBuilder unique_id = new StringBuilder();
        SecureRandom secureRandom = new SecureRandom();
        String allCharacters = UPPER_CASE + LOWER_CASE;
        for (int i = 0; i < UNIQUE_ID; i++) {
            int randomIndex = secureRandom.nextInt(allCharacters.length());
            unique_id.append(allCharacters.charAt(randomIndex));
        }
        return unique_id.toString();
    }

}
