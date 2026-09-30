package com.diafarms.ml.commons;

import org.springframework.stereotype.Component;

import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.ProjetsRepo;

import lombok.RequiredArgsConstructor;

// Chargement d'un projet par son uniqueId, limité à la ferme de l'utilisateur courant
// (voir FermeScope) : pour les endpoints de lecture par projet (stock d'aliment,
// effectif réforme, alertes, fichiers, notifications, plafond de saisie...). Un
// projet d'une autre ferme répond exactement comme un projet inexistant.
@Component
@RequiredArgsConstructor
public class ProjetsFerme {

    private final ProjetsRepo projetsRepo;
    private final OtherService otherService;

    private Utilisateurs utilisateurCourant() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    public Projets charger(String projetUniqueId) {
        Utilisateurs u = utilisateurCourant();
        return projetsRepo.findByUniqueId(projetUniqueId)
                .filter(p -> FermeScope.memeFerme(p.getFarm(), u))
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));
    }

    /** Verrouille les projets (PESSIMISTIC_WRITE, SELECT ... FOR UPDATE) avant un contrôle
     * de stock suivi d'une écriture, dans l'ordre des id pour ne jamais s'interbloquer
     * (même principe que CompteClientService.verrouiller). Les null sont ignorés. À appeler
     * dans une transaction. */
    public void verrouiller(Projets... projets) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        for (Projets p : projets) if (p != null && p.getId() != null) ids.add(p.getId());
        for (Long id : ids) {
            projetsRepo.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Projet introuvable."));
        }
    }

    public void verifier(Projets projet, String messageIntrouvable) {
        FermeScope.verifier(projet != null ? projet.getFarm() : null, utilisateurCourant(), messageIntrouvable);
    }
}
