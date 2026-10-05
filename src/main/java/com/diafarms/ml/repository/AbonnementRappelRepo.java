package com.diafarms.ml.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.AbonnementRappel;

@Repository
public interface AbonnementRappelRepo extends JpaRepository<AbonnementRappel, Long> {

    boolean existsByAbonnement_IdAndTypeAndDateFin(Long abonnementId, String type, LocalDate dateFin);

    // Réservation atomique d'un rappel : 1 si cette exécution l'obtient, 0 s'il a déjà
    // été envoyé pour cette période (contrainte uk_abonnement_rappel_periode). Deux
    // exécutions simultanées (tâche du matin + déclenchement manuel) ne peuvent donc
    // jamais envoyer le même rappel deux fois.
    @Modifying
    @Query(value = "INSERT INTO abonnement_rappels (abonnement_id, type, date_fin, envoye_le, destinataires, emails_envoyes) "
            + "VALUES (:abonnementId, :type, :dateFin, now(), 0, 0) "
            + "ON CONFLICT (abonnement_id, type, date_fin) DO NOTHING", nativeQuery = true)
    int reserver(@Param("abonnementId") Long abonnementId, @Param("type") String type,
            @Param("dateFin") LocalDate dateFin);

    @Modifying
    @Query("UPDATE AbonnementRappel r SET r.destinataires = :destinataires, r.emailsEnvoyes = :emails "
            + "WHERE r.abonnement.id = :abonnementId AND r.type = :type AND r.dateFin = :dateFin")
    int enregistrerEnvoi(@Param("abonnementId") Long abonnementId, @Param("type") String type,
            @Param("dateFin") LocalDate dateFin, @Param("destinataires") int destinataires,
            @Param("emails") int emails);

    // Dernier rappel envoyé pour la période en cours (cloche des ADMIN).
    Optional<AbonnementRappel> findFirstByAbonnement_IdAndDateFinOrderByEnvoyeLeDesc(Long abonnementId, LocalDate dateFin);
}
