package com.diafarms.ml.models;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Trace des e-mails automatiques de croissance (même principe que AbonnementRappel) : une
// ligne par (ferme, type, clé), réservée par INSERT ... ON CONFLICT DO NOTHING AVANT
// l'envoi, donc jamais deux fois le même e-mail, même si la tâche tourne deux fois ou
// en même temps qu'un déclenchement manuel.
//   type "ESSAI_J1" | "ESSAI_J3" | "ESSAI_J7" : e-mails de démarrage pendant l'essai,
//        clé "essai" (une seule fois par ferme) ;
//   type "RESUME_SEMAINE" : résumé du lundi, clé = date du lundi de la semaine résumée.
// Simple texte, pas d'enum : aucune contrainte CHECK Postgres à maintenir.
@Entity
@Table(name = "envois_automatiques",
        uniqueConstraints = @UniqueConstraint(name = "uk_envoi_automatique", columnNames = { "farm_id", "type", "cle" }))
@Getter
@Setter
@NoArgsConstructor
public class EnvoiAutomatique {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "cle", nullable = false, length = 20)
    private String cle;

    @Column(name = "envoye_le", nullable = false)
    private LocalDateTime envoyeLe;

    @Column(name = "destinataires")
    private Integer destinataires;

    @Column(name = "emails_envoyes")
    private Integer emailsEnvoyes;
}
