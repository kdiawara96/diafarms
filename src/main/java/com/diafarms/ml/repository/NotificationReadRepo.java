package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.NotificationRead;

@Repository
public interface NotificationReadRepo extends JpaRepository<NotificationRead, Long> {

    List<NotificationRead> findByUserId(Long userId);

    // Nettoie les marques "lu" dont la condition sous-jacente n'existe plus
    // (ex: stock reconstitué) : si la même alerte se reproduit plus tard, elle
    // redevient non lue au lieu de rester silencieusement masquée pour toujours.
    @Modifying
    @Query("DELETE FROM NotificationRead r WHERE r.user.id = :userId AND r.notificationKey NOT IN :activeKeys")
    void deleteStale(@Param("userId") Long userId, @Param("activeKeys") List<String> activeKeys);
}
