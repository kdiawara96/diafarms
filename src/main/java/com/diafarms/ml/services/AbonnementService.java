package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.AbonnementConfigDTO;
import com.diafarms.ml.DTO.AbonnementDTO;
import com.diafarms.ml.DTO.PaiementAbonnementDTO;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.others.AbonnementConfigUpdateRequest;
import com.diafarms.ml.request.others.DeclarerPaiementAbonnementRequest;
import com.diafarms.ml.request.others.RejeterPaiementAbonnementRequest;

public interface AbonnementService {

    // Appelé une seule fois, juste après la création d'une nouvelle Farm (voir
    // UtilisateurImpl.save, Task 7) — crée l'essai initial (ESSAI, dateDebut =
    // aujourd'hui, dateFin = aujourd'hui + config.dureeEssaiJours).
    void creerEssaiPourFarm(Farm farm);

    // Ferme de l'utilisateur courant (OtherService.getCurrentUser()). Si la ferme
    // n'a pas encore d'Abonnement (fermes créées avant ce déploiement), en crée un
    // à la volée avec un essai complet à partir d'aujourd'hui (jamais rétroactif).
    // Retourne null si l'utilisateur courant n'a pas de ferme (SUPER_ADMIN).
    AbonnementDTO getMoi();

    // ADMIN/RESPONSABLE de la ferme courante uniquement. Refuse si une déclaration
    // est déjà EN_ATTENTE pour cette ferme.
    PaiementAbonnementDTO declarerPaiement(DeclarerPaiementAbonnementRequest request);

    // SUPER_ADMIN uniquement.
    PaginatedResponse<PaiementAbonnementDTO> listEnAttente(int page, int size);

    // SUPER_ADMIN uniquement. Étend Abonnement.dateFin, met periodicite/statut à
    // jour, envoie l'email de confirmation à declarePar.
    PaiementAbonnementDTO valider(String paiementUniqueId);

    // SUPER_ADMIN uniquement. Ne touche pas à Abonnement.dateFin.
    PaiementAbonnementDTO rejeter(String paiementUniqueId, RejeterPaiementAbonnementRequest request);

    // Lecture publique (tout utilisateur connecté) — pour afficher les prix courants
    // sur le bouton "J'ai payé".
    AbonnementConfigDTO getConfig();

    // SUPER_ADMIN uniquement. Mise à jour partielle (champs non-null seulement).
    AbonnementConfigDTO updateConfig(AbonnementConfigUpdateRequest request);

    // Historique complet des déclarations de paiement de la ferme courante (toutes
    // statuts confondus, plus récentes d'abord) — pour la page Abonnement (liste +
    // petit rapport + filtres, faits côté web sur ce volume trivial). Retourne une
    // liste vide si l'utilisateur courant n'a pas de ferme (SUPER_ADMIN).
    List<PaiementAbonnementDTO> getHistorique();
}
