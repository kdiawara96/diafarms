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
    // d'œufs collectés ne peut donc pas dépasser l'effectif vivant — même formule que
    // ReformeImpl.effectifVivant (nbSujets - mortalité cumulée - déjà réformés, un
    // sujet réformé ne pondant plus). La ponte se renouvelle chaque jour (ce n'est pas
    // un stock qu'on consomme, contrairement au cheptel qu'on réforme) donc rien à
    // soustraire ici d'un jour sur l'autre — mais DEUX collectes le MÊME jour (matin +
    // soir) doivent, elles, être cumulées avant comparaison : voir
    // validerPlafondJournalier, appelé séparément par create/update.
    private int effectifVivant(Projets projet) {
        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));
        return nbSujets - morts - dejaReformes;
    }

    // Effectif vivant d'UN bâtiment précis, à partir de son occupation active
    // (nbSujetsDansBatiment, saisi à l'assignation — voir OccupationBatiment) moins la
    // mortalité/réforme attribuées à CE bâtiment. Retourne null si inconnu (aucune
    // occupation active, ou nbSujetsDansBatiment jamais renseigné) : dans ce cas on
    // retombe sur l'effectif du projet entier plutôt que de bloquer une saisie faute
    // de donnée — voir plafondEffectif ci-dessous.
    private Integer effectifVivantBatiment(Batiment batiment) {
        List<com.diafarms.ml.models.OccupationBatiment> actives = occupationBatimentRepo.findActiveByBatimentId(batiment.getId());
        if (actives.isEmpty() || actives.get(0).getNbSujetsDansBatiment() == null) return null;
        int base = actives.get(0).getNbSujetsDansBatiment();
        int morts = nz(mortaliteRepo.sumMortsByBatimentId(batiment.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByBatimentId(batiment.getId()));
        return base - morts - dejaReformes;
    }

    // Plafond à appliquer pour CETTE saisie : celui du bâtiment sélectionné s'il est
    // connu, sinon celui du projet entier (bâtiment non sélectionné, ou effectif du
    // bâtiment inconnu faute de donnée d'occupation).
    private int plafondEffectif(Projets projet, Batiment batiment) {
        if (batiment != null) {
            Integer effectifBatiment = effectifVivantBatiment(batiment);
            if (effectifBatiment != null) return effectifBatiment;
        }
        return effectifVivant(projet);
    }

    // Cumule tout ce qui a déjà été collecté CE JOUR-LÀ (même périmètre que
    // plafondEffectif : bâtiment si sélectionné, sinon tout le projet) et vérifie que
    // ce cumul + la nouvelle saisie ne dépasse pas le plafond. excludeId : la collecte
    // en cours d'édition ne doit pas se compter contre elle-même (update).
    private void validerPlafondJournalier(Projets projet, Batiment batiment, LocalDate date, int oeufsCollectes, Long excludeId) {
        int plafond = plafondEffectif(projet, batiment);
        int dejaCollectes = batiment != null
                ? nz(collecteOeufsRepo.sumOeufsByBatimentIdAndDateExcluding(batiment.getId(), date, excludeId))
                : nz(collecteOeufsRepo.sumOeufsByProjetIdAndDateExcluding(projet.getId(), date, excludeId));
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
