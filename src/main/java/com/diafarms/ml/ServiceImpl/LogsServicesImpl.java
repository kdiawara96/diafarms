package com.diafarms.ml.ServiceImpl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Logs;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.LogsRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.services.LogsServices;

import org.springframework.data.domain.Sort;

import java.util.Collections;


import java.util.List;
import java.util.Optional;
import java.util.UUID;



@Service
@RequiredArgsConstructor
public class LogsServicesImpl implements LogsServices {

    private final LogsRepo logsRepo;
    private final UtilisateursRepo uRepo;

    @Override
    public Logs addLogs(Long userId, Long entityId, String entityType, String action) {
        Logs logs = new Logs();
        logs.setUniqueId(UUID.randomUUID().toString());
        logs.setUserId(userId);
        logs.setEntityId(entityId);
        logs.setEntityType(entityType);
        logs.setAction(action);
        logs.setInitialisation(Initialisation.init());

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
    public PaginatedResponse<Logs> getAllByIdAction(Long idAction, int page, int size, String type) {
        Page<Logs> logPage;

        if ("folder".equals(type)) {
            logPage = logsRepo.findAllByEntityIdAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")), idAction);
        } else if ("trash".equals(type)) {
            logPage = logsRepo.findAllByEntityIdAndInitialisationRemovedTrue(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")), idAction);
        } else {
            logPage = new PageImpl<>(Collections.emptyList());
        }

        return new PaginatedResponse<>(
                logPage.getContent(),
                logPage.getNumber(),
                logPage.getSize(),
                logPage.getTotalElements(),
                logPage.getTotalPages()
        );
    }

    @Override
    public PaginatedResponse<Logs> getAllByNomClass(String nomClass, int page, int size, String type) {
        Page<Logs> logPage;

        if ("folder".equals(type)) {
            logPage = logsRepo.findAllByEntityTypeAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")), nomClass);
        } else if ("trash".equals(type)) {
            // Pour trash, on peut utiliser une requête différente ou simplifier
            logPage = new PageImpl<>(Collections.emptyList());
        } else {
            logPage = new PageImpl<>(Collections.emptyList());
        }

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
    public PaginatedResponse<Logs> getAll(int page, int size, String type) {
        Page<Logs> logPage;

        if ("folder".equals(type)) {
            logPage = logsRepo.findAllByInitialisationRemovedFalseAndInitialisationArchiveFalse(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else if ("trash".equals(type)) {
            logPage = logsRepo.findAllByInitialisationRemovedTrue(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            logPage = new PageImpl<>(Collections.emptyList());
        }

        return new PaginatedResponse<>(
                logPage.getContent(),
                logPage.getNumber(),
                logPage.getSize(),
                logPage.getTotalElements(),
                logPage.getTotalPages()
        );
    }



    @Override
    public PaginatedResponse<Logs> search(String search) {
        List<Logs> logs = logsRepo.searchLogs(search.trim());

        // Construction explicite de PaginatedResponse avec des valeurs fictives pour la pagination
        return new PaginatedResponse<>(
                logs,                  
                1,                                    
                1,                                   
                logs.size(),             
                logs.size()               
        );
    }
   
}
