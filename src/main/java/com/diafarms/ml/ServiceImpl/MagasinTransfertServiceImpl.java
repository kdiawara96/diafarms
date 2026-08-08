package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.MagasinTransfertDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.MagasinTransfert;
import com.diafarms.ml.models.MagasinVente;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.MagasinVenteRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.request.create.MagasinTransfertCreate;
import com.diafarms.ml.services.MagasinTransfertService;

import lombok.RequiredArgsConstructor;

// Transfert = seule façon de faire entrer du stock (œufs/réforme) dans un magasin,
// plafonné par ce qui reste disponible pour LE PROJET SOURCE et n'a pas encore été
// transféré ailleurs (voir disponibleATransfererDepuisProjet) — remplace la
// répartition automatique proportionnelle qui se faisait avant à la vente
// (voir VenteOeufsImpl/VenteReformeImpl, désormais scopés par magasin).
@Service
@RequiredArgsConstructor
public class MagasinTransfertServiceImpl implements MagasinTransfertService {

    private final MagasinTransfertRepo magasinTransfertRepo;
    private final MagasinVenteRepo magasinVenteRepo;
    private final ProjetsRepo projetsRepo;
    private final BatimentRepo batimentRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;
    private final ReformeRepo reformeRepo;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private void ensureCanManage(Utilisateurs u) {
        boolean isResponsable = u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "RESPONSABLE".equalsIgnoreCase(r.getRole()));
        if (!isAdmin(u) && !isResponsable) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut transférer du stock vers un magasin.");
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private int stockTotalProjet(Projets projet, TypeStockMagasin type) {
        if (type == TypeStockMagasin.OEUFS) {
            int collecte = nz(collecteOeufsRepo.sumOeufsCollectesByProjetId(projet.getId()));
            int casse = nz(collecteOeufsRepo.sumOeufsCassesByProjetId(projet.getId()));
            return collecte - casse;
        }
        return nz(reformeRepo.sumSujetsByProjetId(projet.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public int disponibleATransfererDepuisProjet(String projetUniqueId, String type) {
        Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));
        TypeStockMagasin t = TypeStockMagasin.valueOf(type.toUpperCase());
        int total = stockTotalProjet(projet, t);
        int dejaTransfere = nz(magasinTransfertRepo.sumQuantiteByProjetIdAndType(projet.getId(), t));
        return total - dejaTransfere;
    }

    /** Combien chaque projet a déposé dans CE bâtiment de stockage (œufs collectés -
     * cassés) moins ce qui en a déjà été transféré DEPUIS ce même bâtiment — sert de
     * base à la répartition automatique d'un transfert OEUFS entre projets
     * contributeurs (voir create() ci-dessous), même rôle que
     * VenteOeufsImpl.disponibleParProjetDansMagasin mais un cran plus tôt dans la
     * chaîne (bâtiment de stockage, pas magasin de vente). */
    private Map<Long, Integer> disponibleParProjetDansBatimentStockage(Batiment batimentStockage) {
        Map<Long, Integer> disponible = new LinkedHashMap<>();
        for (Long projetId : collecteOeufsRepo.findDistinctProjetIdsByBatimentStockageId(batimentStockage.getId())) {
            int collecte = nz(collecteOeufsRepo.sumOeufsCollectesByProjetIdAndBatimentStockageId(projetId, batimentStockage.getId()));
            int casse = nz(collecteOeufsRepo.sumOeufsCassesByProjetIdAndBatimentStockageId(projetId, batimentStockage.getId()));
            int dejaTransfere = nz(magasinTransfertRepo.sumQuantiteByProjetIdAndBatimentStockageIdAndType(
                    projetId, batimentStockage.getId(), TypeStockMagasin.OEUFS));
            int restant = collecte - casse - dejaTransfere;
            if (restant > 0) disponible.put(projetId, restant);
        }
        return disponible;
    }

    @Override
    @Transactional(readOnly = true)
    public int disponibleATransfererDepuisBatimentStockage(String batimentStockageUniqueId) {
        Batiment batiment = batimentRepo.findByUniqueId(batimentStockageUniqueId);
        if (batiment == null) {
            throw new IllegalArgumentException("Bâtiment de stockage introuvable : " + batimentStockageUniqueId);
        }
        return disponibleParProjetDansBatimentStockage(batiment).values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    @Transactional
    public List<MagasinTransfertDTO> create(MagasinTransfertCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        if (data.getMagasinUniqueId() == null || data.getMagasinUniqueId().isBlank()) {
            throw new IllegalArgumentException("Magasin requis.");
        }
        if (data.getQuantite() == null || data.getQuantite() <= 0) {
            throw new IllegalArgumentException("La quantité transférée doit être positive.");
        }
        if (data.getType() == null || data.getType().isBlank()) {
            throw new IllegalArgumentException("Type de stock requis (OEUFS ou REFORME).");
        }

        MagasinVente magasin = magasinVenteRepo.findByUniqueId(data.getMagasinUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
        TypeStockMagasin type;
        try {
            type = TypeStockMagasin.valueOf(data.getType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Type de stock invalide (attendu OEUFS ou REFORME) : " + data.getType());
        }

        LocalDate date = data.getDate() != null && !data.getDate().isBlank() ? LocalDate.parse(data.getDate()) : LocalDate.now();

        if (type == TypeStockMagasin.REFORME) {
            // Réforme : pas de bâtiment de stockage, le projet source reste choisi
            // directement — comportement inchangé.
            if (data.getProjetUniqueId() == null || data.getProjetUniqueId().isBlank()) {
                throw new IllegalArgumentException("Projet source requis pour un transfert réforme.");
            }
            Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

            int disponible = disponibleATransfererDepuisProjet(data.getProjetUniqueId(), type.name());
            if (data.getQuantite() > disponible) {
                throw new IllegalArgumentException(
                    "Stock insuffisant pour ce projet (" + disponible + " unité(s) restantes à transférer)."
                );
            }

            MagasinTransfert t = new MagasinTransfert();
            t.setUniqueId(java.util.UUID.randomUUID().toString());
            t.setMagasin(magasin);
            t.setProjet(projet);
            t.setType(type);
            t.setQuantite(data.getQuantite());
            t.setDate(date);
            t.setFarm(currentUser.getFarm());
            t.setCreePar(currentUser);
            t.setInitialisation(Initialisation.init());

            return List.of(MagasinTransfertDTO.fromEntity(magasinTransfertRepo.save(t)));
        }

        // Œufs : la source est un bâtiment de stockage, pas un projet — le vendeur/
        // responsable ne se soucie pas de savoir quel projet a pondu quel œuf, tous
        // les œufs du bâtiment sont mélangés. La répartition entre projets
        // contributeurs reste nécessaire en coulisses pour que le chiffre d'affaires
        // remonte correctement à chacun (voir VenteOeufsImpl), donc on la fait ici
        // automatiquement — même algorithme que pour une vente (RepartitionUtil),
        // sans montant associé (un transfert ne génère pas d'argent, juste un
        // mouvement physique).
        if (data.getBatimentStockageUniqueId() == null || data.getBatimentStockageUniqueId().isBlank()) {
            throw new IllegalArgumentException("Bâtiment de stockage source requis pour un transfert d'œufs.");
        }
        Batiment batimentStockage = batimentRepo.findByUniqueId(data.getBatimentStockageUniqueId());
        if (batimentStockage == null || batimentStockage.getType() != Batiment.TypeBatiment.STOCKAGE) {
            throw new IllegalArgumentException("Bâtiment de stockage invalide : " + data.getBatimentStockageUniqueId());
        }

        Map<Long, Integer> disponibleParProjet = disponibleParProjetDansBatimentStockage(batimentStockage);
        int disponibleTotal = disponibleParProjet.values().stream().mapToInt(Integer::intValue).sum();
        if (data.getQuantite() > disponibleTotal) {
            throw new IllegalArgumentException(
                "Stock insuffisant dans ce bâtiment de stockage (" + disponibleTotal + " œuf(s) restant(s))."
            );
        }

        List<RepartitionUtil.Part> parts = RepartitionUtil.repartir(data.getQuantite(), 0.0, disponibleParProjet);
        Map<Long, Projets> projetsParId = new LinkedHashMap<>();
        for (Long projetId : disponibleParProjet.keySet()) {
            projetsRepo.findById(projetId).ifPresent(p -> projetsParId.put(projetId, p));
        }

        List<MagasinTransfertDTO> resultats = new java.util.ArrayList<>();
        for (RepartitionUtil.Part part : parts) {
            Projets projet = projetsParId.get(part.projetId);

            MagasinTransfert t = new MagasinTransfert();
            t.setUniqueId(java.util.UUID.randomUUID().toString());
            t.setMagasin(magasin);
            t.setProjet(projet);
            t.setBatimentStockage(batimentStockage);
            t.setType(type);
            t.setQuantite(part.quantite);
            t.setDate(date);
            t.setFarm(currentUser.getFarm());
            t.setCreePar(currentUser);
            t.setInitialisation(Initialisation.init());

            resultats.add(MagasinTransfertDTO.fromEntity(magasinTransfertRepo.save(t)));
        }
        return resultats;
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<MagasinTransfertDTO> list(String magasinUniqueId, int page, int size) {
        MagasinVente magasin = magasinVenteRepo.findByUniqueId(magasinUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + magasinUniqueId));

        Pageable pageable = PageRequest.of(page, size);
        Page<MagasinTransfert> resultPage = magasinTransfertRepo.searchByMagasin(magasin.getId(), pageable);

        return new PaginatedResponse<>(
                resultPage.getContent().stream().map(MagasinTransfertDTO::fromEntity).toList(),
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }
}
