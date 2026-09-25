package com.diafarms.ml.models;

import java.time.LocalDateTime;

import com.diafarms.ml.enums.TypeEvenementPesee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// Journal d'une session de pesée : une ligne par action faite depuis le web (création,
// ajout, modification, annulation, terminaison). Sert à prévenir l'utilisateur du
// téléphone de ce qui a changé côté serveur.
@Entity
@Table(name = "sessions_pesee_evenements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionPeseeEvenement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private SessionPesee session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeEvenementPesee type;

    // Pesée concernée (null pour CREATION_WEB / TERMINAISON_WEB).
    @Column(name = "pesee_unique_id", length = 50)
    private String peseeUniqueId;

    @Column(name = "ancien_nombre")
    private Integer ancienNombre;

    @Column(name = "ancien_poids")
    private Double ancienPoids;

    @Column(name = "nouveau_nombre")
    private Integer nouveauNombre;

    @Column(name = "nouveau_poids")
    private Double nouveauPoids;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "par_id")
    private Utilisateurs par;

    @Column(nullable = false)
    private LocalDateTime date;

    // Phrase lisible, ex. « Pesée n°3 modifiée : 3 sujets 6,3 kg → 3 sujets 6,1 kg par Awa Traoré ».
    @Column(length = 500)
    private String description;
}
