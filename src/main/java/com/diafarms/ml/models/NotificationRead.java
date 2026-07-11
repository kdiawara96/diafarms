package com.diafarms.ml.models;

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

// Les notifications elles-mêmes ne sont jamais persistées : elles sont
// recalculées à chaque requête à partir des vraies données (stock, mortalité,
// transactions en attente...). Seul l'état "lu" est stocké ici, par clé stable
// (ex: "stock-<projetUniqueId>"), pour survivre au rechargement de la page.
@Entity
@Table(name = "notification_reads", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "notification_key" }))
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class NotificationRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Utilisateurs user;

    @Column(name = "notification_key", nullable = false, length = 100)
    private String notificationKey;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
