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

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Collections;


import java.util.List;
import java.util.Optional;



@Service
@RequiredArgsConstructor
public class LogsServicesImpl implements LogsServices {

    private final LogsRepo logsRepo;
    private final UtilisateursRepo uRepo;

    @Override
    public Logs addLogs(Long idAction, String nomClass, String action) {
        // NOUS RECUPERONS L'UTILISATEUR
        Utilisateurs admin = verificationUniqueId();
        if (admin == null) {
            throw new IllegalArgumentException("L'utilisateur n'existe pas!");
        }
        String nomUser = admin.getFullName();
        String username = admin.getUsername();
        Logs logs = new Logs();
        logs.setUniqueId(Initialisation.generateUniqueId());
        logs.setTexteAction(action + " " + nomUser + " Matricule = " + username);
        logs.setClassName(nomClass);
        logs.setTelephoneActionnaire(admin.getTelephone());
        logs.setIdAction(idAction);

        return logsRepo.save(logs);
    }
    @Override
    @Transactional
    public String delete(String uniqueId) {
        Optional<Logs> logOptional = logsRepo.findByUniqueId(uniqueId);
        if (logOptional.isPresent()) {
            boolean logDeletedState = logOptional.get().getDeleted();
            logOptional.get().setDeleted(!logDeletedState);

            if (logDeletedState) {
                this.addLogs(logOptional.get().getId(), logOptional.get().getClass().getSimpleName(), "Restauration d'un Logs");
                return "SUCCESS_RESTORE";
            }else{
                this.addLogs(logOptional.get().getId(), logOptional.get().getClass().getSimpleName(), "Suppression d'un Logs");
                return "SUCCESS_DELETE";
            }
        }
        throw new IllegalArgumentException("Le Logs avec l'ID unique " + uniqueId + " n'existe pas.");
    }
    @Override
    public PaginatedResponse<Logs> getAllByIdAction(Long idAction, int page, int size, String type) {
        // Récupération d'une page via Spring Data
        Page<Logs> logPage = new PageImpl<>(Collections.emptyList());

        if (type.equals("folder")) {
            logPage =  logsRepo
                    .findAllByIdActionAndDeletedFalse(PageRequest.of(page, size), idAction);
        } else if(type.equals("trash")) {
            logPage = logsRepo.findAllByIdActionAndDeletedTrue(PageRequest.of(page, size), idAction);
        }

        // Transformation des entités en DTOs
        List<Logs> chargeFixeDtos = logPage.getContent();

        // Construction explicite de PaginatedResponse
        return new PaginatedResponse<>(
                chargeFixeDtos,                     // Liste des DTOs
                page,                                 // Page actuelle
                logPage.getTotalPages(),     // Total de pages
                logPage.getTotalElements(),  // Total d'éléments
                size                                  // Taille de la page
        );
    }

    @Override
    public PaginatedResponse<Logs> getAllByNomClass(String nomClass, int page, int size, String type) {
        // Récupération d'une page via Spring Data
        Page<Logs> logPage = new PageImpl<>(Collections.emptyList());

        if (type.equals("folder")) {
            logPage =  logsRepo
                    .findAllByClassNameAndDeletedFalse(PageRequest.of(page, size), nomClass);
        } else if(type.equals("trash")) {
            logPage = logsRepo.findAllByClassNameAndDeletedTrue(PageRequest.of(page, size), nomClass);
        }

        // Transformation des entités en DTOs
        List<Logs> chargeFixeDtos = logPage.getContent();

        // Construction explicite de PaginatedResponse
        return new PaginatedResponse<>(
                chargeFixeDtos,                     // Liste des DTOs
                page,                                 // Page actuelle
                logPage.getTotalPages(),     // Total de pages
                logPage.getTotalElements(),  // Total d'éléments
                size                                  // Taille de la page
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
        // Récupération d'une page via Spring Data
        Page<Logs> logPage = new PageImpl<>(Collections.emptyList());
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dateCreation"));

        if (type.equals("folder")) {
            logPage =  logsRepo
                    .findAllByDeletedFalse(pageable);
        } else if(type.equals("trash")) {
            logPage = logsRepo.findAllByDeletedTrue(pageable);
        }

        // Transformation des entités en DTOs
        List<Logs> chargeFixeDtos = logPage.getContent();

        // Construction explicite de PaginatedResponse
        return new PaginatedResponse<>(
                chargeFixeDtos,                     // Liste des DTOs
                page,                                 // Page actuelle
                logPage.getTotalPages(),     // Total de pages
                logPage.getTotalElements(),  // Total d'éléments
                size                                  // Taille de la page
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
