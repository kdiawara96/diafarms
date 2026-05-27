package com.diafarms.ml.DTO.mappers;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import com.diafarms.ml.DTO.LogsDTO;
import com.diafarms.ml.models.Logs;
import com.diafarms.ml.repository.UtilisateursRepo;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class LogsMapper {

    private final UtilisateursRepo userRepository; // ou UserService

    public LogsDTO toDTO(Logs log) {
        if (log == null) return null;

        // Récupère le nom complet de l'utilisateur
        String fullName = resolveUserFullName(log.getUserId());

        return LogsDTO.builder()
                .id(log.getId())
                .uniqueId(log.getUniqueId())
                .fullName(fullName)           // ← Au lieu de userId
                .entityId(log.getEntityId())
                .entityType(log.getEntityType())
                .action(log.getAction())
                .ipAddress(log.getIpAddress())
                .createdAt(log.getInitialisation() != null ? log.getInitialisation().getCreatedAt() : null)
                .updatedAt(log.getInitialisation() != null ? log.getInitialisation().getUpdatedAt() : null)
                .build();
    }

    private String resolveUserFullName(Long userId) {
        if (userId == null) {
            return "Inconnu";
        }

        return userRepository.findById(userId)
                .map(user -> {
                    String fullName = user.getFullName() != null ? user.getFullName() : "";
                    return fullName.isBlank() ? "Utilisateur #" + userId : fullName;
                })
                .filter(name -> !name.isBlank())
                .orElse("Utilisateur #" + userId);
    }

    public Page<LogsDTO> toDTOPage(Page<Logs> logPage) {
        return logPage.map(this::toDTO);
    }
}