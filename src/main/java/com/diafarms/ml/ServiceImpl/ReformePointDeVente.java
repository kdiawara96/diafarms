package com.diafarms.ml.ServiceImpl;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.MagasinTransfert;
import com.diafarms.ml.models.Reforme;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.MagasinRepo;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;

import lombok.RequiredArgsConstructor;

// Transfert automatique des sujets réformés vers un point de vente (même idée que le
// transfert automatique des œufs à la collecte, voir CollecteOeufsImpl) : une vente de
// réformes se fait toujours depuis un point de vente (VenteReformeImpl), et sans ce
// transfert les réformés restaient « nulle part » (vente refusée, stock 0).
//
// Chaque réforme porte au plus UN transfert REFORME lié (MagasinTransfert.reforme) dont
// la quantité suit la réforme : création = transfert de nombreSujets ; modification =
// même écart ; suppression/restauration = transfert supprimé/restauré. Le stock du
// point de vente pour le projet (reçu - vendu) ne devient jamais négatif : une baisse
// au-delà de ce qui n'est pas encore vendu est refusée.
//
// Point de vente par défaut (réforme envoyée sans point de vente : ancien téléphone,
// formulaire sans choix) : le seul point de vente de la ferme ; sinon celui que tous
// les magasins de stockage désignent comme point de vente par défaut (s'ils en
// désignent un seul) ; sinon aucun -> "Choisissez le point de vente des réformés".
@Component
@RequiredArgsConstructor
public class ReformePointDeVente {

    public static final String MSG_CHOISIR = "Choisissez le point de vente des réformés.";

    private final MagasinRepo magasinRepo;
    private final MagasinTransfertRepo magasinTransfertRepo;
    private final VenteReformeRepartitionRepo repartitionRepo;

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    public List<Magasin> pointsDeVente(Farm farm) {
        if (farm == null) return List.of();
        return magasinRepo.findAllActiveByFarmAndType(farm.getId(), Magasin.TypeMagasin.VENTE);
    }

    /** Point de vente par défaut des réformés de cette ferme, ou null s'il n'y en a pas
     * (aucun point de vente, ou plusieurs sans désignation commune). */
    public Magasin parDefaut(Farm farm) {
        List<Magasin> ventes = pointsDeVente(farm);
        if (ventes.size() == 1) return ventes.get(0);
        if (ventes.isEmpty()) return null;
        Set<Long> idsVente = ventes.stream().map(Magasin::getId).collect(Collectors.toSet());
        Set<Long> designes = magasinRepo.findAllActiveByFarmAndType(farm.getId(), Magasin.TypeMagasin.STOCKAGE).stream()
                .map(Magasin::getMagasinVenteParDefaut)
                .filter(Objects::nonNull)
                .map(Magasin::getId)
                .filter(idsVente::contains)
                .collect(Collectors.toSet());
        if (designes.size() != 1) return null;
        Long id = designes.iterator().next();
        return ventes.stream().filter(m -> m.getId().equals(id)).findFirst().orElse(null);
    }

    /** Point de vente demandé (vérifié : même ferme, type VENTE, actif) ou, à défaut, le
     * point de vente par défaut. Null seulement si la ferme n'a aucun point de vente (la
     * réforme est alors enregistrée sans transfert, comme une collecte sans magasin de
     * vente par défaut). Plusieurs points de vente sans désignation -> 400. */
    public Magasin resoudre(Farm farm, String magasinVenteUniqueId) {
        if (magasinVenteUniqueId != null && !magasinVenteUniqueId.isBlank()) {
            return magasinRepo.findByUniqueId(magasinVenteUniqueId)
                    .filter(m -> farm != null && m.getFarm() != null && Objects.equals(m.getFarm().getId(), farm.getId()))
                    .filter(m -> m.getType() == Magasin.TypeMagasin.VENTE)
                    .filter(m -> m.getInitialisation() == null || !Boolean.TRUE.equals(m.getInitialisation().getRemoved()))
                    .orElseThrow(() -> new IllegalArgumentException("Point de vente introuvable : " + magasinVenteUniqueId));
        }
        Magasin defaut = parDefaut(farm);
        if (defaut == null && !pointsDeVente(farm).isEmpty()) {
            throw new IllegalArgumentException(MSG_CHOISIR);
        }
        return defaut;
    }

    public MagasinTransfert transfertLie(Reforme r) {
        return r.getId() == null ? null : magasinTransfertRepo.findFirstByReformeIdOrderByIdAsc(r.getId()).orElse(null);
    }

    private static boolean actif(MagasinTransfert t) {
        return t != null && (t.getInitialisation() == null || !Boolean.TRUE.equals(t.getInitialisation().getRemoved()));
    }

    /** Sujets de ce projet encore en stock (reçus - vendus) dans ce point de vente. */
    public int libreAuPointDeVente(Magasin magasin, Long projetId) {
        int recus = nz(magasinTransfertRepo.sumQuantiteByMagasinAndProjetAndType(magasin.getId(), projetId, TypeStockMagasin.REFORME));
        int vendus = nz(repartitionRepo.sumSujetsByProjetIdAndMagasinId(projetId, magasin.getId()));
        return recus - vendus;
    }

    // Retirer `retrait` sujets de ce projet du point de vente : refusé si ce n'est plus
    // en stock (déjà vendus).
    private void verifierRetrait(Magasin magasin, Long projetId, int retrait, String action) {
        if (retrait <= 0) return;
        int libre = Math.max(0, libreAuPointDeVente(magasin, projetId));
        if (retrait > libre) {
            throw new IllegalArgumentException("Impossible de " + action + " : les réformés de ce projet ont déjà été vendus "
                    + "depuis le point de vente « " + magasin.getNom() + " » (il n'en reste que " + libre
                    + " sujet(s) en stock, il en faudrait " + retrait + ").");
        }
    }

    private MagasinTransfert nouveau(Reforme r, Magasin magasin, int quantite, Utilisateurs user) {
        MagasinTransfert t = new MagasinTransfert();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setMagasin(magasin);
        t.setProjet(r.getProjet());
        t.setReforme(r);
        t.setType(TypeStockMagasin.REFORME);
        t.setQuantite(quantite);
        t.setDate(r.getDate());
        t.setFarm(r.getProjet().getFarm());
        t.setCreePar(user);
        t.setInitialisation(Initialisation.init());
        return t;
    }

    /** Création : transfert de toute la réforme vers `magasin` (rien si null). */
    public void apresCreation(Reforme r, Magasin magasin, Utilisateurs user) {
        if (magasin == null) return;
        magasinTransfertRepo.save(nouveau(r, magasin, r.getNombreSujets(), user));
    }

    /** Modification : à appeler AVANT d'enregistrer la nouvelle valeur (ancienNombre =
     * valeur en base). `cible` = point de vente demandé, ou null pour garder l'actuel. */
    public void apresModification(Reforme r, int ancienNombre, int nouveauNombre, Magasin cible, Utilisateurs user) {
        MagasinTransfert t = transfertLie(r);
        int ecart = nouveauNombre - ancienNombre;
        boolean reformeActive = r.getInitialisation() == null || !Boolean.TRUE.equals(r.getInitialisation().getRemoved());
        if (t == null) {
            // Réforme antérieure au transfert automatique : seul un ajout est transféré
            // (une baisse ne touche pas des transferts manuels qu'on ne sait pas relier).
            if (ecart > 0 && reformeActive) {
                Magasin m = cible != null ? cible : (r.getMagasinVente() != null ? r.getMagasinVente() : resoudre(r.getProjet().getFarm(), null));
                if (m != null) {
                    magasinTransfertRepo.save(nouveau(r, m, ecart, user));
                    r.setMagasinVente(m);
                }
            } else if (cible != null) {
                r.setMagasinVente(cible);
            }
            return;
        }
        Magasin ancien = t.getMagasin();
        Magasin nouveauMagasin = cible != null ? cible : ancien;
        int ancienneQuantite = t.getQuantite() == null ? 0 : t.getQuantite();
        int nouvelleQuantite = Math.max(0, ancienneQuantite + ecart);
        if (actif(t)) {
            boolean memeMagasin = Objects.equals(ancien.getId(), nouveauMagasin.getId());
            int retrait = ancienneQuantite - (memeMagasin ? nouvelleQuantite : 0);
            verifierRetrait(ancien, r.getProjet().getId(), retrait,
                    memeMagasin ? "réduire cette réforme" : "changer le point de vente de cette réforme");
        }
        t.setMagasin(nouveauMagasin);
        t.setQuantite(nouvelleQuantite);
        t.setDate(r.getDate());
        if (reformeActive && t.getInitialisation() != null) {
            t.getInitialisation().setRemoved(nouvelleQuantite == 0);
        }
        magasinTransfertRepo.save(t);
        r.setMagasinVente(nouveauMagasin);
    }

    /** Suppression (supprimee=true) ou restauration de la réforme. */
    public void apresSuppressionOuRestauration(Reforme r, boolean supprimee) {
        MagasinTransfert t = transfertLie(r);
        if (t == null || t.getInitialisation() == null) return;
        if (supprimee) {
            if (!actif(t)) return;
            verifierRetrait(t.getMagasin(), r.getProjet().getId(), nz(t.getQuantite()), "supprimer cette réforme");
            t.getInitialisation().setRemoved(true);
        } else {
            if (actif(t) || nz(t.getQuantite()) <= 0) return;
            t.getInitialisation().setRemoved(false);
        }
        magasinTransfertRepo.save(t);
    }

    /** Reprise : transfert lié de `quantite` sujets pour une réforme qui n'en a pas. */
    public MagasinTransfert reprise(Reforme r, Magasin magasin, int quantite, Utilisateurs user) {
        MagasinTransfert t = magasinTransfertRepo.save(nouveau(r, magasin, quantite, user));
        if (r.getMagasinVente() == null) r.setMagasinVente(magasin);
        return t;
    }
}
