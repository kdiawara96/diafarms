package com.diafarms.ml.ServiceImpl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.RepriseReformeTransfertsDTO;
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

// Reprise : réformes saisies avant la règle « réformés -> magasin de stockage -> point de
// vente » (voir ReformeStockage), donc sans magasin de stockage. Par projet, les réformes
// actives sans magasin ni transfert lié sont affectées au magasin de stockage de la ferme
// (le seul, ou celui demandé), les plus récentes d'abord, tant qu'elles ne sont pas déjà
// couvertes par un transfert manuel ancien (« depuis le projet ») : ces sujets-là sont déjà
// au point de vente et restent tels quels. Puis même règle qu'une nouvelle réforme :
// transfert automatique si ce magasin a un point de vente par défaut, sinon les réformés
// restent au magasin de stockage. Idempotent : un second passage ne trouve plus rien.
// executer=false : simulation, rien n'est écrit.
@Service
@RequiredArgsConstructor
public class ReformeTransfertsManquantsService {

    private final ReformeRepo reformeRepo;
    private final ProjetsRepo projetsRepo;
    private final MagasinTransfertRepo magasinTransfertRepo;
    private final ReformeStockage reformeStockage;
    private final LogsServices logs;

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    @Transactional
    public RepriseReformeTransfertsDTO lancer(Farm farm, boolean executer, String magasinStockageUniqueId, Utilisateurs user) {
        RepriseReformeTransfertsDTO rapport = new RepriseReformeTransfertsDTO();
        rapport.setExecute(executer);
        rapport.setFarmUniqueId(farm.getUniqueId());
        rapport.setFarmNom(farm.getNom());

        Magasin cible = reformeStockage.stockageDemande(farm, magasinStockageUniqueId);
        if (cible == null) {
            List<Magasin> stockages = reformeStockage.stockages(farm);
            if (stockages.size() == 1) cible = stockages.get(0);
            else rapport.setErreur(stockages.isEmpty()
                    ? "Aucun magasin de stockage dans cette ferme : créez-en un dans Magasins."
                    : "Plusieurs magasins de stockage : précisez magasinStockageUniqueId.");
        }
        Magasin pdv = reformeStockage.pointDeVenteAuto(cible);
        if (cible != null) {
            rapport.setMagasinStockageUniqueId(cible.getUniqueId());
            rapport.setMagasinStockageNom(cible.getNom());
        }
        if (pdv != null) {
            rapport.setPointDeVenteUniqueId(pdv.getUniqueId());
            rapport.setPointDeVenteNom(pdv.getNom());
        }
        boolean ecrire = executer && cible != null;

        for (Long projetId : reformeRepo.findDistinctProjetIdsByFarmId(farm.getId())) {
            Projets projet = projetsRepo.findById(projetId).orElse(null);
            if (projet == null) continue;
            List<Reforme> anciennes = reformeRepo.findSansStockageNiTransfertByProjetId(projetId);
            if (anciennes.isEmpty()) continue;
            if (ecrire) projetsRepo.verrouillerParId(projetId);
            int sansMagasin = anciennes.stream().mapToInt(r -> nz(r.getNombreSujets())).sum();
            int dejaTransferes = nz(magasinTransfertRepo.sumReformeSansStockageByProjetIdHors(projetId, -1L));
            // Réformés sans magasin pas encore transférés (manuels anciens déduits).
            int reste = Math.max(0, reformeStockage.libreSansStockage(projetId));
            RepriseReformeTransfertsDTO.Projet ligne = new RepriseReformeTransfertsDTO.Projet(
                    projet.getUniqueId(), projet.getCode(), projet.getTitre(), sansMagasin, dejaTransferes, 0, 0, 0, 0, 0);
            rapport.getProjets().add(ligne);

            for (Reforme r : anciennes) {
                int n = nz(r.getNombreSujets());
                // Les plus anciennes sont celles que les transferts manuels couvrent : dès
                // qu'une réforme n'est plus entièrement libre, elle et les plus anciennes
                // restent telles quelles (sujets déjà, en tout ou partie, au point de vente).
                if (n > reste) break;
                if (n <= 0) continue;
                reste -= n;
                ligne.setAffectables(ligne.getAffectables() + n);
                if (!ecrire) continue;
                reformeStockage.changer(r, new ReformeStockage.Etat(n, true, null),
                        new ReformeStockage.Etat(n, true, cible), user);
                r.setMagasinStockage(cible);
                reformeRepo.save(r);
                ligne.setAffectes(ligne.getAffectes() + n);
                rapport.setReformesAffectees(rapport.getReformesAffectees() + 1);
                if (pdv != null) {
                    ligne.setTransferes(ligne.getTransferes() + n);
                    ligne.setTransfertsCrees(ligne.getTransfertsCrees() + 1);
                }
            }
            ligne.setLaisses(sansMagasin - ligne.getAffectables());
            rapport.setTotalAffectable(rapport.getTotalAffectable() + ligne.getAffectables());
            rapport.setTotalAffecte(rapport.getTotalAffecte() + ligne.getAffectes());
            rapport.setTotalTransfere(rapport.getTotalTransfere() + ligne.getTransferes());
            rapport.setTransfertsCrees(rapport.getTransfertsCrees() + ligne.getTransfertsCrees());
        }

        if (ecrire && rapport.getReformesAffectees() > 0 && user != null) {
            logs.addLogs(user.getId(), null, "Reforme",
                    "Reprise : " + rapport.getTotalAffecte() + " sujet(s) réformé(s) affecté(s) au magasin de stockage "
                            + cible.getNom() + (pdv != null ? ", transférés au point de vente " + pdv.getNom() : ""));
        }
        return rapport;
    }
}
