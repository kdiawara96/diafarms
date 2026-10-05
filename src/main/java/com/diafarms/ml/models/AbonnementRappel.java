package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Trace des rappels de fin d'abonnement envoyés (voir AbonnementRappelService) : une
// ligne par (abonnement, type de rappel, date de fin). La contrainte d'unicité garantit
// qu'un rappel n'est envoyé qu'UNE fois par période, même si la tâche tourne deux fois
// le même jour ou après un redémarrage. Un renouvellement change Abonnement.dateFin :
// la nouvelle période n'a encore aucune ligne, les rappels sont donc réarmés tout seuls.
// type : "J7" | "J1" | "GRACE" (simple texte, pas d'enum : pas de contrainte CHECK
// Postgres à maintenir, voir AbonnementEcheance.RAPPEL_*).
@Entity
@Table(name = "abonnement_rappels",
        uniqueConstraints = @UniqueConstraint(name = "uk_abonnement_rappel_periode",
                columnNames = { "abonnement_id", "type", "date_fin" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AbonnementRappel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "abonnement_id", nullable = false)
    private Abonnement abonnement;

    @Column(name = "type", nullable = false, length = 10)
    private String type;

    // Abonnement.dateFin au moment de l'envoi (la période concernée).
    @Column(name = "date_fin", nullable = false)
    private LocalDate dateFin;

    @Column(name = "envoye_le", nullable = false)
    private LocalDateTime envoyeLe;

    // Nombre d'ADMIN destinataires, et d'emails effectivement partis (un échec d'envoi
    // n'empêche jamais le rappel dans l'application, voir AbonnementRappelService).
    @Column(name = "destinataires")
    private Integer destinataires;

    @Column(name = "emails_envoyes")
    private Integer emailsEnvoyes;
}
