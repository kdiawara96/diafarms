package com.diafarms.ml.commons;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.OccupationBatimentRepo;
import com.diafarms.ml.repository.ReformeRepo;

import lombok.RequiredArgsConstructor;

// Une seule définition de l'effectif vivant (et du plafond de ponte du jour), partagée
// par CollecteOeufsImpl, MortaliteImpl et l'endpoint /plafond-saisie qui alimente les
// alertes en direct du web et du mobile : avant, chaque service recalculait sa propre
// version (voir l'ancien CollecteOeufsImpl.effectifVivant), ce qui laissait la mortalité
// sans aucun plafond.
@Component
@RequiredArgsConstructor
public class EffectifVivantHelper {

    private final MortaliteRepo mortaliteRepo;
    private final ReformeRepo reformeRepo;
    private final OccupationBatimentRepo occupationBatimentRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // nbSujets - mortalité cumulée - déjà réformés (un sujet réformé ne pond plus).
    public int effectifProjet(Projets projet) {
        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));
        return nbSujets - morts - dejaReformes;
    }

    // Effectif vivant d'UN bâtiment, à partir de son occupation active moins la
    // mortalité/réforme attribuées à CE bâtiment. null si inconnu (aucune occupation
    // active, ou nbSujetsDansBatiment jamais renseigné) : l'appelant retombe alors sur
    // l'effectif du projet plutôt que de bloquer une saisie faute de donnée.
    public Integer effectifBatiment(Batiment batiment) {
        var actives = occupationBatimentRepo.findActiveByBatimentId(batiment.getId());
        if (actives.isEmpty() || actives.get(0).getNbSujetsDansBatiment() == null) return null;
        int base = actives.get(0).getNbSujetsDansBatiment();
        int morts = nz(mortaliteRepo.sumMortsByBatimentId(batiment.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByBatimentId(batiment.getId()));
        return base - morts - dejaReformes;
    }

    // Plafond applicable à une saisie : celui du bâtiment s'il est connu, sinon celui
    // du projet entier.
    public int plafond(Projets projet, Batiment batiment) {
        if (batiment != null) {
            Integer effectifBatiment = effectifBatiment(batiment);
            if (effectifBatiment != null) return effectifBatiment;
        }
        return effectifProjet(projet);
    }

    // true si le plafond vient du bâtiment (et non du projet entier).
    public boolean plafondParBatiment(Batiment batiment) {
        return batiment != null && effectifBatiment(batiment) != null;
    }

    // Œufs déjà collectés CE JOUR-LÀ dans le même périmètre que plafond().
    public int oeufsDejaCollectes(Projets projet, Batiment batiment, LocalDate date, Long excludeId) {
        return nz(batiment != null
                ? collecteOeufsRepo.sumOeufsByBatimentIdAndDateExcluding(batiment.getId(), date, excludeId)
                : collecteOeufsRepo.sumOeufsByProjetIdAndDateExcluding(projet.getId(), date, excludeId));
    }
}
