package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.models.FactureLigne;

@Repository
public interface FactureLigneRepo extends JpaRepository<FactureLigne, Long> {

    List<FactureLigne> findByFacture_Id(Long factureId);

    // Une vente déjà présente sur une facture non annulée ne peut pas être refacturée —
    // voir FactureServiceImpl.genererDepuis. Une facture ANNULEE libère ses ventes.
    @Query("SELECT COUNT(l) > 0 FROM FactureLigne l WHERE l.venteType = :t AND l.venteUniqueId = :u " +
        "AND l.facture.statut <> com.diafarms.ml.models.Facture.StatutFacture.ANNULEE")
    boolean venteDejaFacturee(@Param("t") CibleImputation t, @Param("u") String u);

    // Numéro de la facture ACTIVE (non ANNULEE) contenant cette vente — jumeau de
    // venteDejaFacturee, pour l'afficher dans la fiche client (voir
    // ClientServiceImpl.getReport). Au plus un résultat en pratique : une vente n'est
    // jamais facturée deux fois tant qu'une facture active existe déjà dessus.
    @Query("SELECT l.facture.numeroFacture FROM FactureLigne l WHERE l.venteType = :t AND l.venteUniqueId = :u " +
        "AND l.facture.statut <> com.diafarms.ml.models.Facture.StatutFacture.ANNULEE")
    List<String> numeroFactureActive(@Param("t") CibleImputation t, @Param("u") String u);
}
