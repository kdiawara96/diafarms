package com.diafarms.ml.ServiceImpl;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.request.create.ProjetCreate;
import com.diafarms.ml.services.ProjetServices;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjetImpl implements ProjetServices {

    private final ProjetsRepo projetsRepo;
    private final OtherService OtherService;

    @Override
    public PaginatedResponse<ProjetsDTO> getAllProjets(int page, int size, String search, String filter) {
        
        // Tri par date de création descendante (champ dans l'objet embedded Initialisation)
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "initialisation.createdAt")
        );

        // Détermination de l'état d'archivage selon le filtre
        Boolean isArchive = null;
        if (filter != null) {
            if (filter.equalsIgnoreCase("actif")) {
                isArchive = false;
            } else if (filter.equalsIgnoreCase("archive")) {
                isArchive = true;
            }
        }

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        
        Long farmId = null;
        if (currentUser != null) {
            farmId = currentUser.getFarm().getId();
        }
        
        String searchParam = (search == null || search.isBlank()) ? null : search.trim();
        
        if (search != null && !search.isBlank()) {
            searchParam = "%" + search.trim().toLowerCase() + "%";
        }
        
        // Appel du repo
        Page<Projets> projetsPage = projetsRepo.searchProjets(farmId, isArchive, searchParam, pageable);

        // Mapping des entités vers le DTO de listage
        List<ProjetsDTO> dtoList = projetsPage.getContent().stream()
                .map(ProjetsDTO::fromEntityList)
                .toList();

        return new PaginatedResponse<>(
            dtoList,
            projetsPage.getNumber() + 1, // Conversion vers index 1 pour le Front
            projetsPage.getTotalPages(),
            projetsPage.getTotalElements(),
            projetsPage.getSize()
        );
    }

    @Override
    public ProjetsDTO getProjetByUniqueId(String uniqueId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getProjetByUniqueId'");
    }

    @Override
    public ProjetsDTO createProjet(ProjetCreate data) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'createProjet'");
    }

    @Override
    public String deleteOrRecoverProjet(String uniqueId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteOrRecoverProjet'");
    }

    
    
}
