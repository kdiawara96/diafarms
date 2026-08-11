package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.Client;

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
public class ClientDTO {
    private Long id;
    private String uniqueId;
    private String nom;
    private String telephone;
    private String adresse;
    private String email;
    private LocalDateTime createdAt;

    public static ClientDTO fromEntity(Client c) {
        if (c == null) return null;
        return ClientDTO.builder()
                .id(c.getId())
                .uniqueId(c.getUniqueId())
                .nom(c.getNom())
                .telephone(c.getTelephone())
                .adresse(c.getAdresse())
                .email(c.getEmail())
                .createdAt(c.getInitialisation() != null ? c.getInitialisation().getCreatedAt() : null)
                .build();
    }

    // Projection légère pour les sélecteurs (dialogue de vente) — mêmes champs
    // que fromEntity ici, gardé distinct pour rester cohérent avec le patron
    // toDTO/select des autres entités (Batiment, Magasin) si des champs
    // supplémentaires (ex: solde dû) devaient un jour être ajoutés au détail
    // sans alourdir le sélecteur.
    public static ClientDTO select(Client c) {
        return fromEntity(c);
    }
}
