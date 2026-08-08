package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.MagasinTransfertDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.MagasinTransfert;
import com.diafarms.ml.models.MagasinVente;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
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

    @Override
    @Transactional
    public MagasinTransfertDTO create(MagasinTransfertCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        if (data.getMagasinUniqueId() == null || data.getMagasinUniqueId().isBlank()) {
            throw new IllegalArgumentException("Magasin requis.");
        }
        if (data.getProjetUniqueId() == null || data.getProjetUniqueId().isBlank()) {
            throw new IllegalArgumentException("Projet source requis.");
        }
        if (data.getQuantite() == null || data.getQuantite() <= 0) {
            throw new IllegalArgumentException("La quantité transférée doit être positive.");
        }
        if (data.getType() == null || data.getType().isBlank()) {
            throw new IllegalArgumentException("Type de stock requis (OEUFS ou REFORME).");
        }

        MagasinVente magasin = magasinVenteRepo.findByUniqueId(data.getMagasinUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));
        TypeStockMagasin type;
        try {
            type = TypeStockMagasin.valueOf(data.getType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Type de stock invalide (attendu OEUFS ou REFORME) : " + data.getType());
        }

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
        t.setDate(data.getDate() != null && !data.getDate().isBlank() ? LocalDate.parse(data.getDate()) : LocalDate.now());
        t.setFarm(currentUser.getFarm());
        t.setCreePar(currentUser);
        t.setInitialisation(Initialisation.init());

        return MagasinTransfertDTO.fromEntity(magasinTransfertRepo.save(t));
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
