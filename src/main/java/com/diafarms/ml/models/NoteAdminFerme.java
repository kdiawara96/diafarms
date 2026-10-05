package com.diafarms.ml.models;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Note interne sur une ferme, écrite par un SUPER_ADMIN depuis la console
// d'administration (« Fermes » > fiche de la ferme). Jamais visible par la ferme : seuls
// les endpoints /admin/** (SUPER_ADMIN) la lisent. Nouvelle table créée par ddl-auto.
@Entity
@Table(name = "notes_admin_ferme", indexes = @Index(name = "idx_notes_admin_ferme_farm", columnList = "farm_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NoteAdminFerme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Column(name = "contenu", nullable = false, columnDefinition = "TEXT")
    private String contenu;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auteur_id")
    private Utilisateurs auteur;

    // Copie du nom de l'auteur au moment de l'écriture (reste lisible même si le compte change).
    @Column(name = "auteur_nom", length = 100)
    private String auteurNom;

    @Column(name = "cree_le", nullable = false)
    private LocalDateTime creeLe;
}
