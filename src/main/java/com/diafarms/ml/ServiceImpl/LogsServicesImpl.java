package com.diafarms.ml.ServiceImpl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.LogsDTO;
import com.diafarms.ml.DTO.mappers.LogsMapper;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Logs;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.LogsRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.services.LogsServices;
import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Sort;

import java.util.Optional;
import java.util.UUID;



@Service
@RequiredArgsConstructor
public class LogsServicesImpl implements LogsServices {

    private final LogsRepo logsRepo;
    private final UtilisateursRepo uRepo;
    private final LogsMapper logsMapper;

    @Override
    public Logs addLogs(Long userId, Long entityId, String entityType, String action) {
        Logs logs = new Logs();
        logs.setUniqueId(UUID.randomUUID().toString());
        logs.setUserId(userId);
        logs.setEntityId(entityId);
        logs.setEntityType(entityType);
        logs.setAction(action);
        logs.setInitialisation(Initialisation.init());

        Utilisateurs currentUser = verificationUniqueId(); 
        
        if (currentUser != null) {
            logs.setFarm(currentUser.getFarm());
        }

        return logsRepo.save(logs);
    }
    @Override
    @Transactional
    public String delete(String uniqueId) {
        Optional<Logs> logOptional = logsRepo.findByUniqueId(uniqueId);
        if (logOptional.isPresent()) {
            Logs log = logOptional.get();
            boolean isRemoved = log.getInitialisation().getRemoved();
            log.getInitialisation().setRemoved(!isRemoved);

            Utilisateurs currentUser = verificationUniqueId();
            if (currentUser != null) {
                String action = isRemoved ? "Restauration d'un log" : "Suppression d'un log";
                this.addLogs(currentUser.getId(), log.getId(), "Log", action);
            }

            logsRepo.save(log);

            return isRemoved ? "SUCCESS_RESTORE" : "SUCCESS_DELETE";
        }
        throw new IllegalArgumentException("Le log avec l'ID unique " + uniqueId + " n'existe pas.");
    }
    @Override
    public PaginatedResponse<Logs> getAllByIdAction(Long idAction, int page, int size) {
        Page<Logs> logPage;


        logPage = logsRepo.findAllByEntityIdAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createdAt")), idAction);

        return new PaginatedResponse<>(
                logPage.getContent(),
                logPage.getNumber(),
                logPage.getSize(),
                logPage.getTotalElements(),
                logPage.getTotalPages()
        );
    }

    @Override
    public PaginatedResponse<Logs> getAllByNomClass(String nomClass, int page, int size) {
        Page<Logs> logPage;

          logPage = logsRepo.findAllByEntityTypeAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createdAt")), nomClass);

        return new PaginatedResponse<>(
                logPage.getContent(),
                logPage.getNumber(),
                logPage.getSize(),
                logPage.getTotalElements(),
                logPage.getTotalPages()
        );
    }

    public Utilisateurs verificationUniqueId() {
        // Vérification du JWT
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt) {
            String tenant = (String) jwt.getClaims().get("uniqueId");
            if (tenant != null && !tenant.isEmpty()) {
            
                return uRepo.findByUniqueIdAndInitialisationRemovedFalseAndInitialisationArchiveFalse(tenant).orElseThrow(() -> new IllegalArgumentException("L'utilisateur n'existe pas!"));
               
            } else {
                return null;
            }
        } else {
            return null;
        }
    }
 

    @Override
    public PaginatedResponse<LogsDTO> getAll(int page, int size, String search) {

            Pageable pageable = PageRequest.of(
                    page,
                    size,
                    Sort.by(Sort.Direction.DESC, "initialisation.createdAt")
            );

            Page<Logs> logPage;
            if (search != null && !search.isBlank()) {
                logPage = logsRepo.searchLogs(search, pageable);
            } else {
                logPage = logsRepo.findByFarmIdAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                    verificationUniqueId().getFarm().getId(), pageable);
            }

            // Map en DTO avant de renvoyer
            Page<LogsDTO> dtoPage = logsMapper.toDTOPage(logPage);

            return new PaginatedResponse<>(
                dtoPage.getContent(),        // 1. data
                dtoPage.getNumber(),         // 2. currentPage
                dtoPage.getTotalPages(),     // 3. totalPages (C'était dtoPage.getSize())
                dtoPage.getTotalElements(),  // 4. totalItems
                dtoPage.getSize()            // 5. size (C'était logPage.getTotalPages())
            );
        }
}
