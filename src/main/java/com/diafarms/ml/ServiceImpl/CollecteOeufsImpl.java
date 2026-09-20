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
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.MagasinRepo;
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
    private final MagasinRepo magasinRepo;
    private final MortaliteRepo mortaliteRepo;
    private final ReformeRepo reformeRepo;
    private final com.diafarms.ml.repository.OccupationBatimentRepo occupationBatimentRepo;
    private final com.diafarms.ml.repository.MagasinTransfertRepo magasinTransfertRepo;
    private final LogsServices logs;
    private final OtherService otherService;
    private final com.diafarms.ml.commons.EffectifVivantHelper effectifVivantHelper;

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

    // Une poule ne pond qu'un œuf par JOUR au plus (pas par collecte) : le nombre
    // d'œufs collectés ne peut donc pas dépasser l'effectif vivant. Le calcul de
    // l'effectif (projet ou bâtiment) vit dans EffectifVivantHelper, partagé avec la
    // mortalité et l'endpoint d'alerte en direct. DEUX collectes le MÊME jour (matin +
    // soir) doivent, elles, être cumulées avant comparaison : voir validerPlafondJournalier.
    private int plafondEffectif(Projets projet, Batiment batiment) {
        return effectifVivantHelper.plafond(projet, batiment);
    }

    // Cassés et non utilisables sont des SOUS-ENSEMBLES des œufs collectés (le bon état
    // = collectés - cassés - non utilisables) : leur somme ne peut ni être négative ni
    // dépasser le total collecté, sinon le stock vendable serait négatif.
    private void validerCassesEtNonUtilisables(int collectes, int casses, int nonUtilisables) {
        if (casses < 0 || nonUtilisables < 0) {
            throw new IllegalArgumentException("Le nombre d'œufs cassés ou non utilisables ne peut pas être négatif.");
        }
        if (casses + nonUtilisables > collectes) {
            throw new IllegalArgumentException(
                "Les œufs cassés (" + casses + ") et non utilisables (" + nonUtilisables
                        + ") dépassent le nombre d'œufs collectés (" + collectes + ")."
            );
        }
    }

    // Cumule tout ce qui a déjà été collecté CE JOUR-LÀ (même périmètre que
    // plafondEffectif : bâtiment si sélectionné, sinon tout le projet) et vérifie que
    // ce cumul + la nouvelle saisie ne dépasse pas le plafond. excludeId : la collecte
    // en cours d'édition ne doit pas se compter contre elle-même (update).
    private void validerPlafondJournalier(Projets projet, Batiment batiment, LocalDate date, int oeufsCollectes, Long excludeId) {
        int plafond = plafondEffectif(projet, batiment);
        int dejaCollectes = effectifVivantHelper.oeufsDejaCollectes(projet, batiment, date, excludeId);
        if (dejaCollectes + oeufsCollectes > plafond) {
            String perimetre = batiment != null ? "ce bâtiment" : "le projet";
            throw new IllegalArgumentException(
                "Le cumul des œufs collectés aujourd'hui pour " + perimetre + " (" + dejaCollectes + " + " + oeufsCollectes
                        + ") dépasserait l'effectif vivant (" + plafond + " poule(s))."
            );
        }
    }

    private com.diafarms.ml.models.MagasinTransfert nouveauTransfert(Magasin magasinStockage, Projets projet,
            CollecteOeufs saved, Utilisateurs currentUser, com.diafarms.ml.enums.TypeStockMagasin type, int quantite) {
        com.diafarms.ml.models.MagasinTransfert t = new com.diafarms.ml.models.MagasinTransfert();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setMagasin(magasinStockage.getMagasinVenteParDefaut());
        t.setProjet(projet);
        t.setMagasinStockage(magasinStockage);
        t.setType(type);
        t.setQuantite(quantite);
        t.setDate(saved.getDate());
        t.setFarm(saved.getFarm());
        t.setCreePar(currentUser);
        t.setInitialisation(Initialisation.init());
        return t;
    }

    @Override
    @Transactional
    public CollecteOeufsDTO create(CollecteOeufsCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        Batiment batiment = (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank())
                ? batimentRepo.findByUniqueId(data.getBatimentUniqueId()) : null;
        int oeufsCollectes = data.getOeufsCollectes() != null ? data.getOeufsCollectes() : 0;
        LocalDate date = data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now();
        validerPlafondJournalier(projet, batiment, date, oeufsCollectes, null);
        validerCassesEtNonUtilisables(oeufsCollectes,
                data.getOeufsCasses() != null ? data.getOeufsCasses() : 0,
                data.getOeufsNonUtilisables() != null ? data.getOeufsNonUtilisables() : 0);

        // Magasin de STOCKAGE obligatoire (pas le bâtiment/poulailler d'élevage,
        // optionnel lui) : c'est ce qui plafonne les transferts vers un magasin de
        // vente plus tard (MagasinTransfertServiceImpl), impossible de savoir où sont
        // les œufs sans ça.
        if (data.getMagasinStockageUniqueId() == null || data.getMagasinStockageUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le magasin de stockage est obligatoire.");
        }
        Magasin magasinStockage = magasinRepo.findByUniqueId(data.getMagasinStockageUniqueId()).orElse(null);
        if (magasinStockage == null || magasinStockage.getType() != Magasin.TypeMagasin.STOCKAGE) {
            throw new IllegalArgumentException("Magasin de stockage invalide : " + data.getMagasinStockageUniqueId());
        }

        Utilisateurs currentUser = getCurrentUserSafe();

        CollecteOeufs c = new CollecteOeufs();
        c.setUniqueId(java.util.UUID.randomUUID().toString());
        c.setProjet(projet);
        c.setDate(date);
        c.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        c.setOeufsCollectes(oeufsCollectes);
        c.setOeufsCasses(data.getOeufsCasses() != null ? data.getOeufsCasses() : 0);
        c.setOeufsNonUtilisables(data.getOeufsNonUtilisables() != null ? data.getOeufsNonUtilisables() : 0);
        c.setMagasinStockage(magasinStockage);
        c.setBatiment(batiment);
        c.setInitialisation(Initialisation.init());

        if (currentUser != null) {
            c.setFarm(currentUser.getFarm());
        }

        CollecteOeufs saved = collecteOeufsRepo.save(c);

        // Transfert automatique vers le magasin de vente par défaut de CE magasin de
        // stockage (voir Magasin.magasinVenteParDefaut) — pour qu'une ferme sans
        // admin/responsable disponible en permanence puisse quand même vendre sans
        // attendre un transfert manuel (voir MagasinTransfertServiceImpl, réservé à
        // ADMIN/RESPONSABLE). Rien ne se passe si non configuré (comportement inchangé).
        // Deux transferts distincts, même magasin cible : le vendable/bon état (OEUFS)
        // et les cassés (OEUFS_CASSES) — deux pools de stock totalement séparés, voir
        // VenteOeufsImpl (une vente d'œufs cassés ne peut jamais puiser dans le stock
        // vendable et inversement). Les non utilisables ne sont JAMAIS transférés nulle
        // part (perte pure, voir CollecteOeufs.oeufsNonUtilisables).
        if (magasinStockage.getMagasinVenteParDefaut() != null) {
            int quantiteVendable = saved.getOeufsCollectes() - saved.getOeufsCasses() - saved.getOeufsNonUtilisables();
            if (quantiteVendable > 0) {
                magasinTransfertRepo.save(nouveauTransfert(magasinStockage, projet, saved, currentUser,
                        com.diafarms.ml.enums.TypeStockMagasin.OEUFS, quantiteVendable));
            }
            if (saved.getOeufsCasses() > 0) {
                magasinTransfertRepo.save(nouveauTransfert(magasinStockage, projet, saved, currentUser,
                        com.diafarms.ml.enums.TypeStockMagasin.OEUFS_CASSES, saved.getOeufsCasses()));
            }
        }

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
        if (data.getBatimentUniqueId() != null) {
            c.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (data.getOeufsCollectes() != null) {
            // Validé avec la date/le bâtiment déjà à jour ci-dessus (au cas où l'un des
            // deux change en même temps que la quantité) — voir validerPlafondJournalier.
            validerPlafondJournalier(c.getProjet(), c.getBatiment(), c.getDate(), data.getOeufsCollectes(), c.getId());
            c.setOeufsCollectes(data.getOeufsCollectes());
        }
        if (data.getOeufsCasses() != null) c.setOeufsCasses(data.getOeufsCasses());
        if (data.getOeufsNonUtilisables() != null) c.setOeufsNonUtilisables(data.getOeufsNonUtilisables());
        if (data.getOeufsCollectes() != null || data.getOeufsCasses() != null || data.getOeufsNonUtilisables() != null) {
            validerCassesEtNonUtilisables(c.getOeufsCollectes(), c.getOeufsCasses(), c.getOeufsNonUtilisables());
        }
        if (data.getMagasinStockageUniqueId() != null) {
            if (data.getMagasinStockageUniqueId().isBlank()) {
                throw new IllegalArgumentException("Le magasin de stockage est obligatoire.");
            }
            Magasin magasinStockage = magasinRepo.findByUniqueId(data.getMagasinStockageUniqueId()).orElse(null);
            if (magasinStockage == null || magasinStockage.getType() != Magasin.TypeMagasin.STOCKAGE) {
                throw new IllegalArgumentException("Magasin de stockage invalide : " + data.getMagasinStockageUniqueId());
            }
            c.setMagasinStockage(magasinStockage);
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
