package com.diafarms.ml.ServiceImpl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.MagasinVenteDTO;
import com.diafarms.ml.DTO.StockMagasinDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.MagasinVente;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.MagasinVenteRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.repository.VenteOeufsRepartitionRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;
import com.diafarms.ml.request.create.MagasinVenteCreate;
import com.diafarms.ml.services.MagasinVenteService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MagasinVenteServiceImpl implements MagasinVenteService {

    private final MagasinVenteRepo magasinVenteRepo;
    private final MagasinTransfertRepo magasinTransfertRepo;
    private final VenteOeufsRepartitionRepo venteOeufsRepartitionRepo;
    private final VenteReformeRepartitionRepo venteReformeRepartitionRepo;
    private final UtilisateursRepo utilisateursRepo;
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

    // RESPONSABLE peut aussi gérer les magasins (fait partie de "gérer ses projets"),
    // mais la gestion des magasins n'étant pas scopée par projet, on reste permissif :
    // ADMIN ou RESPONSABLE, pas de restriction par magasin précis pour l'instant.
    private void ensureCanManage(Utilisateurs u) {
        boolean isResponsable = u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "RESPONSABLE".equalsIgnoreCase(r.getRole()));
        if (!isAdmin(u) && !isResponsable) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut gérer les magasins de vente.");
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    @Override
    @Transactional
    public MagasinVenteDTO create(MagasinVenteCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (data.getNom() == null || data.getNom().isBlank()) {
            throw new IllegalArgumentException("Le nom du magasin est obligatoire.");
        }

        MagasinVente m = new MagasinVente();
        m.setUniqueId(java.util.UUID.randomUUID().toString());
        m.setNom(data.getNom());
        m.setDescription(data.getDescription());
        m.setFarm(currentUser.getFarm());
        m.setVendeurs(resolveVendeurs(data.getVendeurUniqueIds()));
        m.setInitialisation(Initialisation.init());

        return MagasinVenteDTO.fromEntity(magasinVenteRepo.save(m));
    }

    @Override
    @Transactional
    public MagasinVenteDTO update(String uniqueId, MagasinVenteCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        MagasinVente m = magasinVenteRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + uniqueId));

        if (data.getNom() != null && !data.getNom().isBlank()) m.setNom(data.getNom());
        if (data.getDescription() != null) m.setDescription(data.getDescription());
        if (data.getVendeurUniqueIds() != null) m.setVendeurs(resolveVendeurs(data.getVendeurUniqueIds()));
        if (m.getInitialisation() != null) m.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());

        return MagasinVenteDTO.fromEntity(magasinVenteRepo.save(m));
    }

    private List<Utilisateurs> resolveVendeurs(List<String> uniqueIds) {
        if (uniqueIds == null || uniqueIds.isEmpty()) return new java.util.ArrayList<>();
        return uniqueIds.stream()
                .map(id -> utilisateursRepo.findByUniqueId(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        MagasinVente m = magasinVenteRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + uniqueId));

        m.getInitialisation().setRemoved(!m.getInitialisation().getRemoved());
        magasinVenteRepo.save(m);
        boolean removed = m.getInitialisation().getRemoved();
        return removed ? "Magasin supprimé." : "Magasin récupéré.";
    }

    @Override
    @Transactional(readOnly = true)
    public List<MagasinVenteDTO> list() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) return List.of();

        boolean isPureVente = currentUser.getRoles() != null && !currentUser.getRoles().isEmpty()
                && currentUser.getRoles().stream().allMatch(r -> "VENTE".equalsIgnoreCase(r.getRole()));

        List<MagasinVente> magasins = isPureVente
                ? magasinVenteRepo.findAssignedToVendeur(currentUser.getFarm().getId(), currentUser.getUniqueId())
                : magasinVenteRepo.findAllActiveByFarm(currentUser.getFarm().getId());

        return magasins.stream().map(MagasinVenteDTO::fromEntity).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StockMagasinDTO getStock(String uniqueId) {
        MagasinVente m = magasinVenteRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + uniqueId));

        int oeufsRecus = nz(magasinTransfertRepo.sumQuantiteByMagasinIdAndType(m.getId(), TypeStockMagasin.OEUFS));
        int oeufsVendus = nz(venteOeufsRepartitionRepo.sumQuantiteByMagasinId(m.getId()));
        int reformeRecus = nz(magasinTransfertRepo.sumQuantiteByMagasinIdAndType(m.getId(), TypeStockMagasin.REFORME));
        int reformeVendus = nz(venteReformeRepartitionRepo.sumSujetsByMagasinId(m.getId()));

        return StockMagasinDTO.builder()
                .oeufsDisponible(oeufsRecus - oeufsVendus)
                .reformeDisponible(reformeRecus - reformeVendus)
                .build();
    }
}
