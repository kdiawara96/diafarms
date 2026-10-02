package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.QueryHint;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Farm;

@Repository
public interface FarmsRepo extends JpaRepository<Farm, Long> {
    
    Farm findByUniqueId(String uniqueId);

    // Devise de la ferme d'un utilisateur (null si pas de ferme ou devise non choisie) :
    // voir commons.Devise.courante, appelé une fois par requête.
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    @Query("select f.devise from Utilisateurs u join u.farm f where u.uniqueId = :uid")
    String findDeviseByUtilisateur(@Param("uid") String uid);

}
