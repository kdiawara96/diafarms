package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.Personnel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PersonnelDTO {
    private String uniqueId;
    private String nom;
    private String poste;
    private String telephone;
    private String utilisateurCompteUniqueId;
    private String utilisateurCompteNom;
    private LocalDateTime createdAt;

    public static PersonnelDTO fromEntity(Personnel p) {
        if (p == null) return null;
        return PersonnelDTO.builder()
                .uniqueId(p.getUniqueId())
                .nom(p.getNom())
                .poste(p.getPoste())
                .telephone(p.getTelephone())
                .utilisateurCompteUniqueId(p.getUtilisateurCompte() != null ? p.getUtilisateurCompte().getUniqueId() : null)
                .utilisateurCompteNom(p.getUtilisateurCompte() != null ? p.getUtilisateurCompte().getFullName() : null)
                .createdAt(p.getInitialisation() != null ? p.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
