package com.diafarms.ml.ServiceImpl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.MagasinTransfert;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Reforme;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.MagasinRepo;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;

import lombok.RequiredArgsConstructor;

// Les sujets réformés suivent EXACTEMENT le chemin des œufs (voir CollecteOeufsImpl) :
// réforme -> magasin de STOCKAGE -> point de vente, automatiquement si ce magasin a un
// point de vente par défaut (Magasin.magasinVenteParDefaut, transfert REFORME lié à la
// réforme : MagasinTransfert.reforme), sinon par un transfert manuel depuis Magasins
// (MagasinTransfertServiceImpl, depuis le magasin de stockage comme pour les œufs).
//
// Stock de réformés, par projet :
// - dans un magasin de stockage = réformés qui y sont entrés - transférés depuis ce magasin ;
// - « sans magasin de stockage » (réformes anciennes, ou ancien téléphone sans magasin
//   déterminable) = ces réformés - transferts sans magasin de stockage (transferts manuels
//   anciens « depuis un projet » et envois directs du 2 octobre 2026) ;
// - au point de vente = transferts reçus - vendus (VenteReformeImpl, inchangé).
// Une modification, suppression ou restauration de réforme ajuste son transfert lié et
// ne fait JAMAIS baisser un de ces stocks au-dessous de zéro (refus si les réformés ont
// déjà été transférés ou vendus), sous verrou FOR NO KEY UPDATE du magasin ou du projet.
@Component
@RequiredArgsConstructor
public class ReformeStockage {

    private final MagasinRepo magasinRepo;
    private final MagasinTransfertRepo magasinTransfertRepo;
    private final VenteReformeRepartitionRepo repartitionRepo;
    private final ReformeRepo reformeRepo;
    private final ProjetsRepo projetsRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;

    private static final long AUCUNE = -1L;

    /** État d'une réforme vu par le stock : nombre de sujets, active ou non, magasin de stockage. */
    public record Etat(int nombre, boolean actif, Magasin stockage) {}

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean actif(Magasin m) {
        return m != null && (m.getInitialisation() == null || !Boolean.TRUE.equals(m.getInitialisation().getRemoved()));
    }

    private static boolean actif(MagasinTransfert t) {
        return t != null && (t.getInitialisation() == null || !Boolean.TRUE.equals(t.getInitialisation().getRemoved()));
    }

    private static Long idDe(Magasin m) {
        return m == null ? null : m.getId();
    }

    private static boolean meme(Magasin a, Magasin b) {
        return Objects.equals(idDe(a), idDe(b));
    }

    private static boolean memeFerme(Magasin m, Farm farm) {
        return farm != null && m.getFarm() != null && Objects.equals(m.getFarm().getId(), farm.getId());
    }

    public List<Magasin> stockages(Farm farm) {
        if (farm == null) return List.of();
        return magasinRepo.findAllActiveByFarmAndType(farm.getId(), Magasin.TypeMagasin.STOCKAGE);
    }

    /** Point de vente où ce magasin de stockage envoie automatiquement (même règle que les
     * œufs à la collecte), ou null : les réformés restent alors au magasin de stockage. */
    public Magasin pointDeVenteAuto(Magasin stockage) {
        if (stockage == null) return null;
        Magasin pdv = stockage.getMagasinVenteParDefaut();
        return pdv != null && pdv.getType() == Magasin.TypeMagasin.VENTE && actif(pdv) ? pdv : null;
    }

    /** Magasin de stockage demandé : même ferme, type STOCKAGE, actif (400 sinon). Null si vide. */
    public Magasin stockageDemande(Farm farm, String uniqueId) {
        Magasin m = stockageExistant(farm, uniqueId);
        if (m != null && !actif(m)) throw new IllegalArgumentException("Ce magasin de stockage a été supprimé : " + m.getNom());
        return m;
    }

    private Magasin stockageExistant(Farm farm, String uniqueId) {
        if (uniqueId == null || uniqueId.isBlank()) return null;
        return magasinRepo.findByUniqueId(uniqueId)
                .filter(m -> memeFerme(m, farm))
                .filter(m -> m.getType() == Magasin.TypeMagasin.STOCKAGE)
                .orElseThrow(() -> new IllegalArgumentException("Magasin de stockage invalide : " + uniqueId));
    }

    /** Magasin de stockage d'une NOUVELLE réforme. Jamais de 400 pour un magasin absent
     * (ancien téléphone) : magasin demandé ; sinon (ancien contrat) celui dont le point de
     * vente envoyé est le point de vente par défaut, s'il est seul ; sinon le seul magasin
     * de stockage de la ferme ; sinon celui de la dernière collecte du projet ; sinon null
     * (réforme enregistrée sans magasin, à compléter en modification). */
    public Magasin resoudre(Farm farm, Projets projet, String stockageUniqueId, String ancienPointDeVenteUniqueId) {
        // Magasin supprimé depuis la saisie hors ligne : règle par défaut plutôt qu'un 400.
        Magasin demande = stockageExistant(farm, stockageUniqueId);
        if (demande != null && actif(demande)) return demande;
        List<Magasin> stockages = stockages(farm);
        if (ancienPointDeVenteUniqueId != null && !ancienPointDeVenteUniqueId.isBlank()) {
            List<Magasin> lies = stockages.stream()
                    .filter(s -> s.getMagasinVenteParDefaut() != null
                            && ancienPointDeVenteUniqueId.equals(s.getMagasinVenteParDefaut().getUniqueId()))
                    .toList();
            if (lies.size() == 1) return lies.get(0);
        }
        if (stockages.size() == 1) return stockages.get(0);
        if (stockages.isEmpty() || projet == null) return null;
        return collecteOeufsRepo.findMagasinsStockageRecentsByProjetId(projet.getId(), PageRequest.of(0, 1)).stream()
                .filter(m -> m.getType() == Magasin.TypeMagasin.STOCKAGE && actif(m) && memeFerme(m, farm))
                .findFirst().orElse(null);
    }

    public MagasinTransfert transfertLie(Reforme r) {
        return r.getId() == null ? null : magasinTransfertRepo.findFirstByReformeIdOrderByIdAsc(r.getId()).orElse(null);
    }

    // Stock de réformés d'un projet dans un magasin de stockage (null = « sans magasin de
    // stockage »), sans compter la réforme `reformeId` ni son transfert lié.
    private int libreHors(Magasin stockage, Long projetId, Long reformeId) {
        if (stockage == null) {
            return nz(reformeRepo.sumSujetsSansStockageByProjetIdHors(projetId, reformeId))
                    - nz(magasinTransfertRepo.sumReformeSansStockageByProjetIdHors(projetId, reformeId));
        }
        return nz(reformeRepo.sumSujetsByProjetIdAndMagasinStockageIdHors(projetId, stockage.getId(), reformeId))
                - nz(magasinTransfertRepo.sumReformeByProjetIdAndMagasinStockageIdHors(projetId, stockage.getId(), reformeId));
    }

    /** Réformés de ce projet encore dans ce magasin de stockage (non transférés). */
    public int libreDansStockage(Magasin stockage, Long projetId) {
        return libreHors(stockage, projetId, AUCUNE);
    }

    /** Réformés de ce projet sans magasin de stockage, pas encore transférés. */
    public int libreSansStockage(Long projetId) {
        return libreHors(null, projetId, AUCUNE);
    }

    /** Réformés disponibles par projet dans ce magasin de stockage (projets à stock > 0). */
    public Map<Long, Integer> libreParProjetDansStockage(Magasin stockage) {
        Map<Long, Integer> dispo = new LinkedHashMap<>();
        for (Long projetId : reformeRepo.findDistinctProjetIdsByMagasinStockageId(stockage.getId())) {
            int libre = libreDansStockage(stockage, projetId);
            if (libre > 0) dispo.put(projetId, libre);
        }
        return dispo;
    }

    /** Sujets de ce projet encore en stock (reçus - vendus) dans ce point de vente. */
    public int libreAuPointDeVente(Magasin magasin, Long projetId) {
        int recus = nz(magasinTransfertRepo.sumQuantiteByMagasinAndProjetAndType(magasin.getId(), projetId, TypeStockMagasin.REFORME));
        int vendus = nz(repartitionRepo.sumSujetsByProjetIdAndMagasinId(projetId, magasin.getId()));
        return recus - vendus;
    }

    // Retirer `retrait` sujets de ce projet du point de vente : refusé s'ils sont déjà vendus.
    private void verifierRetraitPointDeVente(Magasin magasin, Long projetId, int retrait, String action) {
        if (retrait <= 0) return;
        // Verrou du point de vente : une vente simultanée (VenteReformeImpl) ne doit pas
        // puiser dans les sujets qu'on retire ici.
        magasinRepo.verrouillerParId(magasin.getId());
        int libre = Math.max(0, libreAuPointDeVente(magasin, projetId));
        if (retrait > libre) {
            throw new IllegalArgumentException("Impossible de " + action + " : les réformés de ce projet ont déjà été vendus "
                    + "depuis le point de vente « " + magasin.getNom() + " » (il n'en reste que " + libre
                    + " sujet(s) en stock, il en faudrait " + retrait + ").");
        }
    }

    private MagasinTransfert nouveau(Reforme r, Magasin pointDeVente, Magasin stockage, int quantite, Utilisateurs user) {
        MagasinTransfert t = new MagasinTransfert();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setMagasin(pointDeVente);
        t.setMagasinStockage(stockage);
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

    private static String action(Etat avant, Etat apres) {
        if (avant.actif() && !apres.actif()) return "supprimer cette réforme";
        if (!meme(avant.stockage(), apres.stockage())) return "changer le magasin de stockage de cette réforme";
        if (apres.nombre() < avant.nombre()) return "réduire cette réforme";
        return "modifier cette réforme";
    }

    /**
     * Passage de la réforme de l'état `avant` à l'état `apres` (création : avant = (0,
     * inactive, null)). Vérifie que ni le magasin de stockage ni le point de vente ne
     * passent sous zéro, puis ajuste (ou crée) le transfert lié. Aucune écriture avant la
     * fin des contrôles. Met à jour r.magasinVente (point de vente actuel des sujets).
     */
    public void changer(Reforme r, Etat avant, Etat apres, Utilisateurs user) {
        boolean changeStockage = !meme(avant.stockage(), apres.stockage());
        if (changeStockage && !apres.actif()) {
            throw new IllegalArgumentException("Restaurez d'abord cette réforme avant de changer son magasin de stockage.");
        }
        Long projetId = r.getProjet().getId();
        MagasinTransfert t = transfertLie(r);

        // Transfert lié : avant (m0, ts0, q0, a0) -> après (m1, ts1, q1, a1).
        boolean existe = t != null;
        Magasin m0 = existe ? t.getMagasin() : null;
        Magasin ts0 = existe ? t.getMagasinStockage() : null;
        int q0 = existe ? nz(t.getQuantite()) : 0;
        boolean a0 = actif(t);
        Magasin m1 = m0;
        Magasin ts1 = ts0;
        int q1 = q0;
        boolean a1 = a0;
        boolean creer = false;
        if (existe && !changeStockage && q0 == 0 && !a0) {
            // Transfert lié « garé » (réforme déplacée vers un magasin sans point de vente
            // par défaut) : il reste garé, les sujets ajoutés restent au magasin de stockage.
            q1 = 0;
            a1 = false;
        } else if (existe && !changeStockage) {
            // Même magasin : le transfert lié suit l'écart (il peut couvrir moins que la
            // réforme pour une réforme ancienne). Une réforme supprimée avec un transfert
            // actif (q0 > 0) le retrouve à la restauration.
            q1 = Math.max(0, q0 + (apres.nombre() - avant.nombre()));
            a1 = apres.actif() && q1 > 0;
        } else if (changeStockage) {
            Magasin auto = pointDeVenteAuto(apres.stockage());
            if (auto != null) {
                m1 = auto;
                ts1 = apres.stockage();
                q1 = apres.nombre();
                a1 = true;
                creer = !existe;
            } else if (existe) {
                ts1 = apres.stockage();
                q1 = 0;
                a1 = false;
            }
        }

        // Contrôle des stocks de magasin de stockage concernés (et « sans magasin »).
        List<Magasin> pools = new ArrayList<>();
        List<Long> vus = new ArrayList<>();
        for (Magasin x : new Magasin[] { avant.stockage(), apres.stockage(), ts0, ts1 }) {
            if (vus.contains(idDe(x))) continue;
            vus.add(idDe(x));
            pools.add(x);
        }
        pools.sort(Comparator.comparing(m -> m == null ? Long.MAX_VALUE : m.getId()));
        String action = action(avant, apres);
        boolean lieApres = existe || creer;
        for (Magasin x : pools) {
            int contribAvant = (avant.actif() && meme(avant.stockage(), x) ? avant.nombre() : 0)
                    - (existe && a0 && meme(ts0, x) ? q0 : 0);
            int contribApres = (apres.actif() && meme(apres.stockage(), x) ? apres.nombre() : 0)
                    - (lieApres && a1 && meme(ts1, x) ? q1 : 0);
            if (contribApres >= contribAvant) continue;
            if (x != null) magasinRepo.verrouillerParId(x.getId());
            else projetsRepo.verrouillerParId(projetId);
            int base = libreHors(x, projetId, r.getId() == null ? AUCUNE : r.getId());
            int stockAvant = base + contribAvant;
            int stockApres = base + contribApres;
            if (stockApres < 0) {
                if (x != null) {
                    throw new IllegalArgumentException("Impossible de " + action + " : des réformés de ce projet ont déjà été "
                            + "transférés depuis le magasin de stockage « " + x.getNom() + " » vers un point de vente (il n'en reste que "
                            + Math.max(0, stockAvant) + " sujet(s) au magasin, il en faudrait " + (contribAvant - contribApres) + ").");
                }
                if (changeStockage) {
                    throw new IllegalArgumentException("Ces réformés sont déjà au point de vente (transfert manuel) : le magasin de "
                            + "stockage ne peut plus être changé ; modifiez seulement le nombre ou la cause.");
                }
                throw new IllegalArgumentException("Impossible de " + action + " : des sujets de cette réforme ont déjà été "
                        + "transférés à la main vers un point de vente (" + (-stockApres) + " sujet(s) de trop) ; retirez d'abord ce transfert.");
            }
        }

        // Point de vente : on ne retire pas des sujets déjà vendus.
        if (existe && a0) {
            int retrait = q0 - (a1 && meme(m1, m0) ? q1 : 0);
            verifierRetraitPointDeVente(m0, projetId, retrait, action);
        }

        if (creer) {
            magasinTransfertRepo.save(nouveau(r, m1, ts1, q1, user));
        } else if (existe) {
            t.setMagasin(m1);
            t.setMagasinStockage(ts1);
            t.setQuantite(q1);
            t.setDate(r.getDate());
            if (t.getInitialisation() != null) t.getInitialisation().setRemoved(!a1);
            magasinTransfertRepo.save(t);
        }
        if (lieApres) r.setMagasinVente(a1 ? m1 : null);
    }
}
