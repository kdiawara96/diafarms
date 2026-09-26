package com.diafarms.ml.models;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Trace d'une requête d'écriture envoyée avec l'en-tête Idempotency-Key (appli mobile :
// une saisie hors ligne renvoyée après une réponse perdue ne doit pas être créée deux
// fois). Lue et écrite uniquement par IdempotenceStore (JDBC, hors transaction JPA) ;
// l'entité ne sert qu'à faire créer la table et sa contrainte unique par ddl-auto.
// Voir security/IdempotenceFilter pour le comportement complet.
@Entity
@Table(name = "idempotency_requests",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_cle_utilisateur", columnNames = {"cle", "utilisateur"}),
        indexes = @Index(name = "idx_idempotency_created_at", columnList = "created_at"))
@NoArgsConstructor
@Getter
@Setter
public class IdempotencyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Valeur de l'en-tête Idempotency-Key (clé locale stable de la saisie côté téléphone).
    @Column(name = "cle", nullable = false, length = 100)
    private String cle;

    // Username (sujet du JWT) : une même clé n'entre jamais en conflit entre deux comptes.
    @Column(name = "utilisateur", nullable = false, length = 150)
    private String utilisateur;

    @Column(name = "farm_id")
    private Long farmId;

    // "POST /diafarms/api/v1/collectes-oeufs/create" (+ "?query" éventuelle).
    @Column(name = "methode_chemin", nullable = false, length = 500)
    private String methodeChemin;

    // SHA-256 hexadécimal du corps (JSON normalisé : clés triées, espaces ignorés).
    @Column(name = "hash_corps", nullable = false, length = 64)
    private String hashCorps;

    // EN_COURS pendant l'exécution, TERMINE une fois la réponse (2xx/4xx) enregistrée.
    @Column(name = "statut", nullable = false, length = 20)
    private String statut;

    @Column(name = "statut_reponse")
    private Integer statutReponse;

    @Column(name = "corps_reponse", columnDefinition = "text")
    private String corpsReponse;

    @Column(name = "type_contenu", length = 150)
    private String typeContenu;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
