package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.NotificationDTO;

public interface NotificationService {

    /**
     * Recalcule les notifications actives à partir des vraies données (stock
     * d'aliment, mortalité cumulée, transactions en attente) pour la ferme de
     * l'utilisateur connecté, croisées avec son état de lecture persisté.
     */
    List<NotificationDTO> getActiveNotifications();

    /**
     * Même calcul (stock, mortalité) mais limité à un seul projet, sans état
     * de lecture — sert à la section "Alertes actives aujourd'hui" de la
     * Fiche Projet.
     */
    List<NotificationDTO> getActiveNotificationsForProjet(String projetUniqueId);

    void markRead(String key);

    void markAllRead();
}
