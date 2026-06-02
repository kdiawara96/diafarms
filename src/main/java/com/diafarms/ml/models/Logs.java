package com.diafarms.ml.models;

import com.diafarms.ml.commons.Initialisation;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "logs")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Logs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true)
    private String uniqueId;

    @Column(name = "user_id", nullable = false)
    private Long userId; // ID de l'utilisateur qui fait l'action

    @Column(name = "entity_id")
    private Long entityId; // ID de l'entité affectée (optionnel)

    @Column(name = "entity_type")
    private String entityType; // Type d'entité (User, Role, etc.)

    @Column(name = "action", nullable = false, length = 500)
    private String action; // Description de l'action

    @Column(name = "ip_address")
    private String ipAddress; // Adresse IP (optionnel)


    @Embedded
    private Initialisation initialisation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;
}
