package com.diafarms.ml.DTO;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Projet associé à un utilisateur (responsable production et/ou finance), utilisé par
 * la modale "Profil & Accès Mobile Utilisateur" côté web pour lister ses derniers
 * projets — actifs et archivés confondus (voir ProjetsRepo.findRecentAssignedToUser).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ProjetAssigneDTO {

    private String uniqueId;
    private String code;
    private String titre;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;

    private boolean active; // !initialisation.archive
    private List<String> roles; // "PRODUCTEUR" et/ou "FINANCIER"
}
