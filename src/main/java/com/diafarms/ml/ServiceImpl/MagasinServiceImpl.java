package com.diafarms.ml.ServiceImpl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.MagasinDTO;
import com.diafarms.ml.DTO.StockMagasinDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.enums.TypeVenteOeufs;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.Magasin.TypeMagasin;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.MagasinRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.repository.VenteOeufsRepartitionRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;
import com.diafarms.ml.request.create.MagasinCreate;
import com.diafarms.ml.services.MagasinService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MagasinServiceImpl implements MagasinService {

    private final MagasinRepo magasinRepo;
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

    private TypeMagasin parseType(String type) {
        if (type == null || type.isBlank()) return TypeMagasin.VENTE;
        try {
            return TypeMagasin.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Type de magasin invalide (attendu VENTE ou STOCKAGE) : " + type);
        }
    }

    @Override
    @Transactional
    public MagasinDTO create(MagasinCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (data.getNom() == null || data.getNom().isBlank()) {
            throw new IllegalArgumentException("Le nom du magasin est obligatoire.");
        }

        TypeMagasin type = parseType(data.getType());

        Magasin m = new Magasin();
        m.setUniqueId(java.util.UUID.randomUUID().toString());
        m.setNom(data.getNom());
        m.setType(type);
        m.setDescription(data.getDescription());
        m.setSeuilAlerteOeufs(data.getSeuilAlerteOeufs());
        m.setSeuilAlerteReforme(data.getSeuilAlerteReforme());
        m.setSeuilAlerteAlveoles(data.getSeuilAlerteAlveoles());
        m.setFarm(currentUser.getFarm());
        m.setVendeurs(resolveVendeurs(data.getVendeurUniqueIds()));
        m.setMagasinVenteParDefaut(resolveMagasinVenteParDefaut(data.getMagasinVenteParDefautUniqueId()));
        m.setInitialisation(Initialisation.init());

        return MagasinDTO.fromEntity(magasinRepo.save(m));
    }

    // Pertinent seulement pour un magasin de STOCKAGE — pas de vérification stricte du
    // type ici, même convention que seuilAlerteAlveoles (jamais imposé en base, juste
    // sans effet si le magasin est de type VENTE, voir CollecteOeufsImpl).
    private Magasin resolveMagasinVenteParDefaut(String uniqueId) {
        if (uniqueId == null || uniqueId.isBlank()) return null;
        Magasin cible = magasinRepo.findByUniqueId(uniqueId).orElse(null);
        if (cible == null || cible.getType() != TypeMagasin.VENTE) {
            throw new IllegalArgumentException("Le magasin de vente par défaut doit être un magasin de type VENTE existant.");
        }
        return cible;
    }

    @Override
    @Transactional
    public MagasinDTO update(String uniqueId, MagasinCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        Magasin m = magasinRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + uniqueId));

        if (data.getNom() != null && !data.getNom().isBlank()) m.setNom(data.getNom());
        if (data.getType() != null && !data.getType().isBlank()) m.setType(parseType(data.getType()));
        if (data.getDescription() != null) m.setDescription(data.getDescription());
        // Toujours écrasé (pas de "null = inchangé" ici) : c'est le seul moyen de
        // pouvoir désactiver une alerte déjà configurée en renvoyant explicitement null.
        m.setSeuilAlerteOeufs(data.getSeuilAlerteOeufs());
        m.setSeuilAlerteReforme(data.getSeuilAlerteReforme());
        m.setSeuilAlerteAlveoles(data.getSeuilAlerteAlveoles());
        if (data.getVendeurUniqueIds() != null) m.setVendeurs(resolveVendeurs(data.getVendeurUniqueIds()));
        m.setMagasinVenteParDefaut(resolveMagasinVenteParDefaut(data.getMagasinVenteParDefautUniqueId()));
        if (m.getInitialisation() != null) m.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());

        return MagasinDTO.fromEntity(magasinRepo.save(m));
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

        Magasin m = magasinRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + uniqueId));

        m.getInitialisation().setRemoved(!m.getInitialisation().getRemoved());
        magasinRepo.save(m);
        boolean removed = m.getInitialisation().getRemoved();
        return removed ? "Magasin supprimé." : "Magasin récupéré.";
    }

    @Override
    @Transactional(readOnly = true)
    public List<MagasinDTO> list(String type) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) return List.of();

        // Un magasin de STOCKAGE n'a pas de notion de vendeur assigné (voir
        // CollecteOeufs.magasinStockage, alimenté par collecte, pas par vente) : le
        // filtrage "VENTE pur ne voit que ses magasins" ne s'applique qu'au type VENTE.
        TypeMagasin typeFiltre = (type == null || type.isBlank()) ? null : parseType(type);

        boolean isPureVente = currentUser.getRoles() != null && !currentUser.getRoles().isEmpty()
                && currentUser.getRoles().stream().allMatch(r -> "VENTE".equalsIgnoreCase(r.getRole()));

        List<Magasin> magasins;
        if (typeFiltre != null) {
            magasins = magasinRepo.findAllActiveByFarmAndType(currentUser.getFarm().getId(), typeFiltre);
            if (typeFiltre == TypeMagasin.VENTE && isPureVente) {
                List<Magasin> assignes = magasinRepo.findAssignedToVendeur(currentUser.getFarm().getId(), currentUser.getUniqueId());
                magasins = magasins.stream().filter(assignes::contains).toList();
            }
        } else {
            magasins = isPureVente
                    ? magasinRepo.findAssignedToVendeur(currentUser.getFarm().getId(), currentUser.getUniqueId())
                    : magasinRepo.findAllActiveByFarm(currentUser.getFarm().getId());
        }

        return magasins.stream().map(MagasinDTO::fromEntity).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StockMagasinDTO getStock(String uniqueId) {
        Magasin m = magasinRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + uniqueId));

        int oeufsRecus = nz(magasinTransfertRepo.sumQuantiteByMagasinIdAndType(m.getId(), TypeStockMagasin.OEUFS));
        int oeufsVendus = nz(venteOeufsRepartitionRepo.sumQuantiteByMagasinId(m.getId(), TypeVenteOeufs.BON));
        int reformeRecus = nz(magasinTransfertRepo.sumQuantiteByMagasinIdAndType(m.getId(), TypeStockMagasin.REFORME));
        int reformeVendus = nz(venteReformeRepartitionRepo.sumSujetsByMagasinId(m.getId()));
        int oeufsCassesRecus = nz(magasinTransfertRepo.sumQuantiteByMagasinIdAndType(m.getId(), TypeStockMagasin.OEUFS_CASSES));
        int oeufsCassesVendus = nz(venteOeufsRepartitionRepo.sumQuantiteByMagasinId(m.getId(), TypeVenteOeufs.CASSE));

        return StockMagasinDTO.builder()
                .oeufsDisponible(oeufsRecus - oeufsVendus)
                .reformeDisponible(reformeRecus - reformeVendus)
                .oeufsCassesDisponible(oeufsCassesRecus - oeufsCassesVendus)
                .build();
    }
}
