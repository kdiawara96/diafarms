package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.EffectifReformeDTO;
import com.diafarms.ml.DTO.ReformeDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Reforme;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.request.create.ReformeCreate;
import com.diafarms.ml.request.update.ReformeUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.ReformeService;

import lombok.RequiredArgsConstructor;

// Réforme (Production) : comptage pur des sujets retirés du cheptel vivant, plafonné
// par l'effectif vivant du projet (nbSujets - mortalité - déjà réformé) — même
// principe que le plafond de ConsommationAlimentImpl, appliqué ici à un cheptel
// plutôt qu'à un stock d'aliment. Ne contient aucun prix : la vente (avec montant)
// est un acte Finance distinct et séparé (VenteReformeImpl), à l'échelle de la ferme.
@Service
@RequiredArgsConstructor
public class ReformeImpl implements ReformeService {

    private final ReformeRepo reformeRepo;
    private final MortaliteRepo mortaliteRepo;
    private final ProjetsRepo projetsRepo;
    private final com.diafarms.ml.commons.ProjetsFerme projetsFerme;
    private final BatimentRepo batimentRepo;
    private final LogsServices logs;
    private final OtherService otherService;
    private final com.diafarms.ml.commons.PoulaillerObligatoire poulaillerObligatoire;
    private final com.diafarms.ml.commons.EffectifVivantHelper effectifVivantHelper;
    private final ReformeStockage reformeStockage;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private int effectifVivant(Projets projet) {
        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));
        return nbSujets - morts - dejaReformes;
    }

    // Effectif vivant DU POULAILLER (s'il est connu, voir EffectifVivantHelper) : on ne
    // réforme pas plus de sujets qu'il n'en reste dans ce poulailler. dejaCompte = ce que
    // cette même réforme retire déjà de cet effectif (modification dans le même poulailler).
    private void validerEffectifPoulailler(Projets projet, Batiment poulailler, int nombre, int dejaCompte) {
        if (!effectifVivantHelper.plafondParBatiment(poulailler)) return;
        int restant = effectifVivantHelper.plafond(projet, poulailler) + dejaCompte;
        if (nombre > restant) {
            throw new IllegalArgumentException(
                "Effectif vivant insuffisant dans ce poulailler (" + restant + " sujet(s) restants).");
        }
    }

    @Override
    @Transactional
    public ReformeDTO create(ReformeCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .filter(p -> com.diafarms.ml.commons.FermeScope.memeFerme(p.getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        if (data.getNombreSujets() == null || data.getNombreSujets() <= 0) {
            throw new IllegalArgumentException("Le nombre de sujets réformés doit être positif.");
        }

        int restant = effectifVivant(projet);
        if (data.getNombreSujets() > restant) {
            throw new IllegalArgumentException(
                "Effectif vivant insuffisant pour ce projet (" + restant + " sujet(s) restants)."
            );
        }

        Batiment poulailler = poulaillerObligatoire.resoudre(projet, data.getBatimentUniqueId());
        validerEffectifPoulailler(projet, poulailler, data.getNombreSujets(), 0);
        // Magasin de stockage résolu AVANT toute écriture (défaut pour un ancien téléphone).
        com.diafarms.ml.models.Magasin magasinStockage = reformeStockage.resoudre(projet.getFarm(), projet,
                data.getMagasinStockageUniqueId(), data.getMagasinVenteUniqueId());

        Utilisateurs currentUser = getCurrentUserSafe();

        Reforme r = new Reforme();
        r.setUniqueId(java.util.UUID.randomUUID().toString());
        r.setProjet(projet);
        r.setDate(com.diafarms.ml.commons.DateSaisie.saisie(data.getDate(), LocalDate.now()));
        r.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        r.setNombreSujets(data.getNombreSujets());
        r.setCause(data.getCause());
        r.setInitialisation(Initialisation.init());

        r.setBatiment(poulailler);
        r.setFarm(projet.getFarm());
        r.setMagasinStockage(magasinStockage);

        Reforme saved = reformeRepo.save(r);
        // Comme une collecte : transfert automatique vers le point de vente par défaut du
        // magasin de stockage, sinon les réformés restent au magasin de stockage.
        reformeStockage.changer(saved, new ReformeStockage.Etat(0, false, null),
                new ReformeStockage.Etat(saved.getNombreSujets(), true, magasinStockage), currentUser);
        saved = reformeRepo.save(saved);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Reforme",
                    "Réforme de " + saved.getNombreSujets() + " sujet(s) pour le projet '" + projet.getTitre() + "'"
                            + (saved.getCause() != null ? ", cause : " + saved.getCause() : ""));
        }

        return ReformeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public ReformeDTO update(String uniqueId, ReformeUpdate data) {
        Reforme r = reformeRepo.findByUniqueId(uniqueId)
                .filter(x -> com.diafarms.ml.commons.FermeScope.memeFerme(x.getProjet().getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Réforme introuvable : " + uniqueId));
        boolean active = r.getInitialisation() == null || !Boolean.TRUE.equals(r.getInitialisation().getRemoved());
        ReformeStockage.Etat avant = new ReformeStockage.Etat(r.getNombreSujets(), active, r.getMagasinStockage());
        com.diafarms.ml.models.Magasin stockageDemande = reformeStockage.stockageDemande(r.getProjet().getFarm(), data.getMagasinStockageUniqueId());

        // Poulailler et plafond du poulailler vérifiés AVANT toute modification de
        // l'entité (sinon la somme lue en base inclurait déjà la nouvelle valeur).
        Batiment ancienPoulailler = r.getBatiment();
        Batiment nouveauPoulailler = poulaillerObligatoire.resoudrePourModification(r.getProjet(), ancienPoulailler, data.getBatimentUniqueId());
        if (data.getNombreSujets() != null || data.getBatimentUniqueId() != null) {
            boolean memePoulailler = ancienPoulailler != null && ancienPoulailler.getId().equals(nouveauPoulailler.getId());
            int nouveauNombre = data.getNombreSujets() != null ? data.getNombreSujets() : r.getNombreSujets();
            validerEffectifPoulailler(r.getProjet(), nouveauPoulailler, nouveauNombre, memePoulailler ? r.getNombreSujets() : 0);
        }

        r.setDate(com.diafarms.ml.commons.DateSaisie.modifiee(data.getDate(), r.getDate()));
        if (data.getHeure() != null) r.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getNombreSujets() != null) {
            if (data.getNombreSujets() <= 0) {
                throw new IllegalArgumentException("Le nombre de sujets réformés doit être positif.");
            }
            Projets projet = r.getProjet();
            int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
            int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
            int totalReforme = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));
            int nouveauTotalReforme = totalReforme - r.getNombreSujets() + data.getNombreSujets();
            if (nouveauTotalReforme > (nbSujets - morts)) {
                throw new IllegalArgumentException(
                    "Effectif vivant insuffisant pour ce projet (" + ((nbSujets - morts) - (totalReforme - r.getNombreSujets())) + " sujet(s) restants)."
                );
            }
            r.setNombreSujets(data.getNombreSujets());
        }
        if (data.getCause() != null) r.setCause(data.getCause());
        r.setBatiment(nouveauPoulailler);
        if (r.getInitialisation() != null) {
            r.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        Utilisateurs currentUser = getCurrentUserSafe();
        // Transfert lié ajusté (même écart, nouvelle date, magasin de stockage éventuel) ;
        // refusé si ces réformés ont déjà quitté le magasin de stockage ou été vendus.
        com.diafarms.ml.models.Magasin stockageApres = stockageDemande != null ? stockageDemande : r.getMagasinStockage();
        reformeStockage.changer(r, avant, new ReformeStockage.Etat(r.getNombreSujets(), active, stockageApres), currentUser);
        r.setMagasinStockage(stockageApres);
        Reforme saved = reformeRepo.save(r);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Reforme", "Modification d'une saisie de réforme");
        }

        return ReformeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Reforme r = reformeRepo.findByUniqueId(uniqueId)
                .filter(x -> com.diafarms.ml.commons.FermeScope.memeFerme(x.getProjet().getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Réforme introuvable : " + uniqueId));

        boolean removed = !Boolean.TRUE.equals(r.getInitialisation().getRemoved());
        // Transfert lié supprimé/restauré avec la réforme (suppression refusée si ces
        // réformés ont déjà été transférés ou vendus).
        int n = nz(r.getNombreSujets());
        // removed = nouvel état : la réforme était active avant ssi on la supprime maintenant.
        reformeStockage.changer(r, new ReformeStockage.Etat(n, removed, r.getMagasinStockage()),
                new ReformeStockage.Etat(n, !removed, r.getMagasinStockage()), getCurrentUserSafe());
        r.getInitialisation().setRemoved(removed);
        reformeRepo.save(r);

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), r.getId(), "Reforme",
                    (removed ? "Suppression" : "Restauration") + " d'une saisie de réforme");
        }

        return removed ? "Saisie supprimée." : "Saisie récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<ReformeDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<Reforme> resultPage = reformeRepo.search(farmId, projetParam, batimentParam, searchParam, pageable);

        List<ReformeDTO> dtoList = resultPage.getContent().stream()
                .map(ReformeDTO::fromEntity)
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public EffectifReformeDTO getEffectif(String projetUniqueId) {
        Projets projet = projetsFerme.charger(projetUniqueId);

        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));

        return EffectifReformeDTO.builder()
                .nbSujetsInitial(nbSujets)
                .mortaliteCumulee(morts)
                .sujetsReformesCumulee(dejaReformes)
                .effectifVivant(nbSujets - morts - dejaReformes)
                .build();
    }
}
