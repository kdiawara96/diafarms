package com.diafarms.ml.request.others;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

// Synchronisation (idempotente) d'une session de pesée depuis le téléphone : on renvoie
// toujours la session entière avec toutes ses pesées ; le serveur n'ajoute que ce qui
// est nouveau et n'accepte que l'annulation des pesées existantes.
@Data
public class SessionPeseeSyncRequest {
    private String uniqueId;          // UUID généré par le téléphone
    private String projetUniqueId;
    private Integer nombreParDefaut;
    private String dateDebut;         // date-heure ISO (ex. 2026-09-25T08:30:00)
    private String statut;            // "EN_COURS" | "TERMINEE"
    private String dateFin;           // date-heure ISO, facultative
    private List<PeseeItem> pesees = new ArrayList<>();

    @Data
    public static class PeseeItem {
        private String uniqueId;      // UUID généré par le téléphone
        private Integer nombreSujets;
        private Double poidsKg;
        private String dateHeure;     // date-heure ISO
        private Boolean annulee;
    }
}
