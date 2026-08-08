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

import com.diafarms.ml.DTO.CollecteOeufsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.CollecteOeufs;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.request.create.CollecteOeufsCreate;
import com.diafarms.ml.request.update.CollecteOeufsUpdate;
import com.diafarms.ml.services.CollecteOeufsService;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CollecteOeufsImpl implements CollecteOeufsService {

    private final CollecteOeufsRepo collecteOeufsRepo;
    private final ProjetsRepo projetsRepo;
    private final BatimentRepo batimentRepo;
    private final MortaliteRepo mortaliteRepo;
    private final ReformeRepo reformeRepo;
    private final LogsServices logs;
    private final OtherService otherService;

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

    // Une poule ne pond qu'un œuf par collecte au plus : le nombre d'œufs collectés
    // en une saisie ne peut donc pas dépasser l'effectif vivant du projet au moment
    // de la saisie — même formule que ReformeImpl.effectifVivant (nbSujets - mortalité
    // cumulée - déjà réformés, un sujet réformé ne pondant plus), mais sans soustraire
    // les collectes précédentes : contrairement à un cheptel qu'on réforme (ressource
    // qui s'épuise), la ponte se renouvelle à chaque collecte, ce n'est pas un stock
    // qu'on consomme.
    private int effectifVivant(Projets projet) {
        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));
        return nbSujets - morts - dejaReformes;
    }

    @Override
    @Transactional
    public CollecteOeufsDTO create(CollecteOeufsCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        int oeufsCollectes = data.getOeufsCollectes() != null ? data.getOeufsCollectes() : 0;
        int effectif = effectifVivant(projet);
        if (oeufsCollectes > effectif) {
            throw new IllegalArgumentException(
                "Le nombre d'œufs collectés ne peut pas dépasser l'effectif vivant du projet (" + effectif + " poule(s))."
            );
        }

        // Bâtiment de STOCKAGE obligatoire (pas le bâtiment d'élevage, optionnel
        // lui) : c'est ce qui plafonne les transferts vers un magasin de vente plus
        // tard (MagasinTransfertServiceImpl), impossible de savoir où sont les œufs
        // sans ça.
        if (data.getBatimentStockageUniqueId() == null || data.getBatimentStockageUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le bâtiment de stockage est obligatoire.");
        }
        Batiment batimentStockage = batimentRepo.findByUniqueId(data.getBatimentStockageUniqueId());
        if (batimentStockage == null || batimentStockage.getType() != Batiment.TypeBatiment.STOCKAGE) {
            throw new IllegalArgumentException("Bâtiment de stockage invalide : " + data.getBatimentStockageUniqueId());
        }

        Utilisateurs currentUser = getCurrentUserSafe();

        CollecteOeufs c = new CollecteOeufs();
        c.setUniqueId(java.util.UUID.randomUUID().toString());
        c.setProjet(projet);
        c.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        c.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        c.setOeufsCollectes(data.getOeufsCollectes() != null ? data.getOeufsCollectes() : 0);
        c.setOeufsCasses(data.getOeufsCasses() != null ? data.getOeufsCasses() : 0);
        c.setBatimentStockage(batimentStockage);
        c.setInitialisation(Initialisation.init());

        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            c.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (currentUser != null) {
            c.setFarm(currentUser.getFarm());
        }

        CollecteOeufs saved = collecteOeufsRepo.save(c);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "CollecteOeufs",
                    "Saisie de collecte d'œufs (" + saved.getOeufsCollectes() + " œufs, " + saved.getOeufsCasses()
                            + " cassés) pour le projet '" + projet.getTitre() + "'");
        }

        return CollecteOeufsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public CollecteOeufsDTO update(String uniqueId, CollecteOeufsUpdate data) {
        CollecteOeufs c = collecteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Collecte introuvable : " + uniqueId));

        if (data.getDate() != null) c.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) c.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getOeufsCollectes() != null) {
            int effectif = effectifVivant(c.getProjet());
            if (data.getOeufsCollectes() > effectif) {
                throw new IllegalArgumentException(
                    "Le nombre d'œufs collectés ne peut pas dépasser l'effectif vivant du projet (" + effectif + " poule(s))."
                );
            }
            c.setOeufsCollectes(data.getOeufsCollectes());
        }
        if (data.getOeufsCasses() != null) c.setOeufsCasses(data.getOeufsCasses());
        if (data.getBatimentUniqueId() != null) {
            c.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (data.getBatimentStockageUniqueId() != null) {
            if (data.getBatimentStockageUniqueId().isBlank()) {
                throw new IllegalArgumentException("Le bâtiment de stockage est obligatoire.");
            }
            Batiment batimentStockage = batimentRepo.findByUniqueId(data.getBatimentStockageUniqueId());
            if (batimentStockage == null || batimentStockage.getType() != Batiment.TypeBatiment.STOCKAGE) {
                throw new IllegalArgumentException("Bâtiment de stockage invalide : " + data.getBatimentStockageUniqueId());
            }
            c.setBatimentStockage(batimentStockage);
        }
        if (c.getInitialisation() != null) {
            c.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        CollecteOeufs saved = collecteOeufsRepo.save(c);

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "CollecteOeufs", "Modification d'une collecte d'œufs");
        }

        return CollecteOeufsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        CollecteOeufs c = collecteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Collecte introuvable : " + uniqueId));

        c.getInitialisation().setRemoved(!c.getInitialisation().getRemoved());
        collecteOeufsRepo.save(c);
        boolean removed = c.getInitialisation().getRemoved();

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), c.getId(), "CollecteOeufs",
                    (removed ? "Suppression" : "Restauration") + " d'une collecte d'œufs");
        }

        return removed ? "Collecte supprimée." : "Collecte récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<CollecteOeufsDTO> list(int page, int size, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<CollecteOeufs> resultPage = collecteOeufsRepo.search(farmId, projetParam, batimentParam, pageable);

        List<CollecteOeufsDTO> dtoList = resultPage.getContent().stream()
                .map(CollecteOeufsDTO::fromEntity)
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }
}
