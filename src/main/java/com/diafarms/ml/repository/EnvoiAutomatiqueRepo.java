package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.EnvoiAutomatique;

@Repository
public interface EnvoiAutomatiqueRepo extends JpaRepository<EnvoiAutomatique, Long> {

    // 1 si cette exécution obtient l'envoi, 0 s'il est déjà fait (uk_envoi_automatique).
    @Modifying
    @Query(value = "INSERT INTO envois_automatiques (farm_id, type, cle, envoye_le, destinataires, emails_envoyes) "
            + "VALUES (:farmId, :type, :cle, now(), 0, 0) ON CONFLICT (farm_id, type, cle) DO NOTHING", nativeQuery = true)
    int reserver(@Param("farmId") Long farmId, @Param("type") String type, @Param("cle") String cle);

    @Modifying
    @Query("UPDATE EnvoiAutomatique e SET e.destinataires = :destinataires, e.emailsEnvoyes = :emails "
            + "WHERE e.farmId = :farmId AND e.type = :type AND e.cle = :cle")
    int enregistrer(@Param("farmId") Long farmId, @Param("type") String type, @Param("cle") String cle,
            @Param("destinataires") int destinataires, @Param("emails") int emails);
}
