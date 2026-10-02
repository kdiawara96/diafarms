package com.diafarms.ml.ServiceImpl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.RepriseReformeTransfertsDTO;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Reforme;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

// Reprise : réformes saisies avant le transfert automatique vers un point de vente (voir
// ReformePointDeVente), donc jamais placées dans un point de vente -> vente de réformes
// refusée (stock 0). Par projet : à transférer = sujets réformés - déjà transférés
// (transferts manuels compris, rien n'est transféré deux fois). Le manque est rattaché
// aux réformes sans transfert lié, les plus récentes d'abord (les plus anciennes sont
// celles que d'éventuels transferts manuels couvrent déjà), vers le point de vente
// demandé ou celui par défaut. Idempotent : un second passage ne trouve plus rien.
// executer=false : simulation, rien n'est écrit.
@Service
@RequiredArgsConstructor
public class ReformeTransfertsManquantsService {

    private final ReformeRepo reformeRepo;
    private final ProjetsRepo projetsRepo;
    private final MagasinTransfertRepo magasinTransfertRepo;
    private final ReformePointDeVente pointDeVente;
    private final LogsServices logs;

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    @Transactional
    public RepriseReformeTransfertsDTO lancer(Farm farm, boolean executer, String magasinVenteUniqueId, Utilisateurs user) {
        RepriseReformeTransfertsDTO rapport = new RepriseReformeTransfertsDTO();
        rapport.setExecute(executer);
        rapport.setFarmUniqueId(farm.getUniqueId());
        rapport.setFarmNom(farm.getNom());

        Magasin cible = null;
        try {
            cible = pointDeVente.resoudre(farm, magasinVenteUniqueId);
            if (cible == null) rapport.setErreur("Aucun point de vente dans cette ferme : créez-en un dans Magasins.");
        } catch (IllegalArgumentException e) {
            rapport.setErreur(ReformePointDeVente.MSG_CHOISIR.equals(e.getMessage())
                    ? "Plusieurs points de vente sans point de vente par défaut : précisez magasinVenteUniqueId."
                    : e.getMessage());
        }
        if (cible != null) {
            rapport.setPointDeVenteUniqueId(cible.getUniqueId());
            rapport.setPointDeVenteNom(cible.getNom());
        }
        // Une réforme qui a déjà un point de vente (choisi avant) y reste ; les autres vont
        // au point de vente demandé ou par défaut (cible).
        boolean ecrire = executer;

        for (Long projetId : reformeRepo.findDistinctProjetIdsByFarmId(farm.getId())) {
            Projets projet = projetsRepo.findById(projetId).orElse(null);
            if (projet == null) continue;
            int reformes = nz(reformeRepo.sumSujetsByProjetId(projetId));
            int transferes = nz(magasinTransfertRepo.sumQuantiteByProjetIdAndType(projetId, TypeStockMagasin.REFORME));
            int manque = Math.max(0, reformes - transferes);
            RepriseReformeTransfertsDTO.Projet ligne = new RepriseReformeTransfertsDTO.Projet(
                    projet.getUniqueId(), projet.getCode(), projet.getTitre(), reformes, transferes, manque, 0, 0);
            rapport.getProjets().add(ligne);
            rapport.setTotalATransferer(rapport.getTotalATransferer() + manque);
            if (manque == 0 || !ecrire) continue;

            int reste = manque;
            for (Reforme r : reformeRepo.findSansTransfertByProjetId(projetId)) {
                if (reste <= 0) break;
                int q = Math.min(reste, nz(r.getNombreSujets()));
                if (q <= 0) continue;
                Magasin m = r.getMagasinVente() != null && r.getMagasinVente().getType() == Magasin.TypeMagasin.VENTE
                        && (r.getMagasinVente().getInitialisation() == null || !Boolean.TRUE.equals(r.getMagasinVente().getInitialisation().getRemoved()))
                        ? r.getMagasinVente() : cible;
                if (m == null) continue;
                pointDeVente.reprise(r, m, q, user);
                reformeRepo.save(r);
                reste -= q;
                ligne.setTransferes(ligne.getTransferes() + q);
                ligne.setTransfertsCrees(ligne.getTransfertsCrees() + 1);
            }
            rapport.setTotalTransfere(rapport.getTotalTransfere() + ligne.getTransferes());
            rapport.setTransfertsCrees(rapport.getTransfertsCrees() + ligne.getTransfertsCrees());
        }

        if (ecrire && rapport.getTransfertsCrees() > 0 && user != null) {
            logs.addLogs(user.getId(), null, "MagasinTransfert",
                    "Reprise : " + rapport.getTotalTransfere() + " sujet(s) réformé(s) transféré(s) vers "
                            + (cible != null ? "le point de vente " + cible.getNom() : "leur point de vente") + " (" + rapport.getTransfertsCrees() + " transfert(s))");
        }
        return rapport;
    }
}
