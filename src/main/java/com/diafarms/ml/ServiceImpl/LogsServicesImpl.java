package com.diafarms.ml.ServiceImpl;

import org.springframework.transaction.annotation.Transactional;
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

import org.springframework.security.access.AccessDeniedException;
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
    // Accès aux logs : un SUPER_ADMIN voit tout ; tout autre compte ne voit QUE les logs de
    // sa propre ferme (jamais un log sans ferme, jamais une action de la console
    // d'administration, entity_type AdminFerme). Avant, /logs/list?search=,
    // /logs/list/by-action et /logs/list/by-class lisaient toutes les fermes.
    private static boolean estSuperAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null
                && u.getRoles().stream().anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private static boolean estAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null
                && u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private Utilisateurs utilisateurCourant() {
        try {
            return verificationUniqueId();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Transactional
    public String delete(String uniqueId) {
        Utilisateurs currentUser = utilisateurCourant();
        Logs log = logsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Le log avec l'ID unique " + uniqueId + " n'existe pas."));
        // Suppression : SUPER_ADMIN, ou ADMIN sur un log de SA ferme (hors console d'administration).
        boolean autorise = estSuperAdmin(currentUser)
                || (estAdmin(currentUser) && currentUser.getFarm() != null && log.getFarm() != null
                    && currentUser.getFarm().getId().equals(log.getFarm().getId())
                    && !"AdminFerme".equals(log.getEntityType()));
        if (!autorise) {
            if (currentUser != null && currentUser.getFarm() != null && log.getFarm() != null
                    && currentUser.getFarm().getId().equals(log.getFarm().getId())
                    && !"AdminFerme".equals(log.getEntityType())) {
                throw new AccessDeniedException("Seul un administrateur de la ferme peut supprimer un log.");
            }
            // Log d'une autre ferme : répond comme un log inexistant.
            throw new IllegalArgumentException("Le log avec l'ID unique " + uniqueId + " n'existe pas.");
        }
        boolean isRemoved = Boolean.TRUE.equals(log.getInitialisation().getRemoved());
        log.getInitialisation().setRemoved(!isRemoved);
        this.addLogs(currentUser.getId(), log.getId(), "Log", isRemoved ? "Restauration d'un log" : "Suppression d'un log");
        logsRepo.save(log);
        return isRemoved ? "SUCCESS_RESTORE" : "SUCCESS_DELETE";
    }

    private PaginatedResponse<LogsDTO> page(Page<Logs> logPage) {
        Page<LogsDTO> dtoPage = logsMapper.toDTOPage(logPage);
        return new PaginatedResponse<>(
                dtoPage.getContent(),
                dtoPage.getNumber(),
                dtoPage.getTotalPages(),
                dtoPage.getTotalElements(),
                dtoPage.getSize());
    }

    private Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)),
                Sort.by(Sort.Direction.DESC, "initialisation.createdAt"));
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<LogsDTO> getAllByIdAction(Long idAction, int page, int size) {
        Utilisateurs u = utilisateurCourant();
        Pageable p = pageable(page, size);
        if (estSuperAdmin(u)) {
            return page(logsRepo.findAllByEntityIdAndInitialisationRemovedFalseAndInitialisationArchiveFalse(p, idAction));
        }
        if (u == null || u.getFarm() == null) return page(Page.empty(p));
        return page(logsRepo.findDeLaFermeParEntityId(u.getFarm().getId(), idAction, p));
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<LogsDTO> getAllByNomClass(String nomClass, int page, int size) {
        Utilisateurs u = utilisateurCourant();
        Pageable p = pageable(page, size);
        if (estSuperAdmin(u)) {
            return page(logsRepo.findAllByEntityTypeAndInitialisationRemovedFalseAndInitialisationArchiveFalse(p, nomClass));
        }
        if (u == null || u.getFarm() == null) return page(Page.empty(p));
        return page(logsRepo.findDeLaFermeParEntityType(u.getFarm().getId(), nomClass, p));
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
    @Transactional(readOnly = true)
    public PaginatedResponse<LogsDTO> getAll(int page, int size, String search) {
        Utilisateurs u = utilisateurCourant();
        Pageable p = pageable(page, size);
        boolean recherche = search != null && !search.isBlank();
        if (estSuperAdmin(u)) {
            // SUPER_ADMIN : recherche globale ; sans recherche, rien (son journal est dans
            // la console d'administration, /admin/journal).
            return page(recherche ? logsRepo.searchLogs(search, p) : Page.empty(p));
        }
        if (u == null || u.getFarm() == null) return page(Page.empty(p));
        Long farmId = u.getFarm().getId();
        return page(recherche ? logsRepo.searchLogsDeLaFerme(farmId, search, p) : logsRepo.findDeLaFerme(farmId, p));
    }
}
