package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// Réponses de la console d'administration SUPER_ADMIN (voir AdminConsoleService,
// AdminConsoleController, endpoints /admin/**). Tous les montants sont en FCFA (prix
// d'abonnement Cocorico), jamais dans la devise de la ferme.
public final class AdminConsoleDTO {

    private AdminConsoleDTO() {}

    // Une ferme dans la liste « Fermes » (une ligne du tableau).
    // statut : SUSPENDU, EXPIRE, GRACE, ESSAI, ACTIF, ou AUCUN (ferme qui ne s'est jamais
    // reconnectée depuis l'arrivée des abonnements : son essai démarrera à sa prochaine visite).
    public record Ferme(
            String farmUniqueId,
            String nom,
            String proprietaireNom,
            String proprietaireTelephone,
            String proprietaireEmail,
            String paysCode,
            String devise,
            String ville,
            LocalDate inscriteLe,
            String statut,
            LocalDate dateFin,
            Long joursRestants,
            LocalDate dernierJourAcces,
            String periodicite,
            boolean suspendu,
            String motifSuspension,
            LocalDateTime suspenduLe,
            long nbUtilisateurs,
            long projetsEnCours,
            long sujetsVivants,
            LocalDateTime derniereActivite,
            double totalPaye,
            boolean paiementEnAttente,
            boolean exclureStatistiques,
            // Tarif actuel (voir AbonnementTarifService) : poules comptées sur 30 jours, prix
            // du mois et de l'an, tarif spécial ou non.
            int poulesComptees,
            double prixMensuel,
            double prixAnnuel,
            boolean prixFixe) {}

    public record MoisValeur(String mois, double montant, long nombre) {}

    public record CleValeur(String cle, double montant, long nombre) {}

    public record FermeCourte(String farmUniqueId, String nom, LocalDate dateFin, String statut) {}

    public record TableauDeBord(
            long totalFermes,
            long enEssai,
            long activesPayantes,
            long enGrace,
            long expirees,
            long suspendues,
            long sansAbonnement,
            long nouvellesCeMois,
            long actives7Jours,
            long actives30Jours,
            long totalSujetsVivants,
            double revenuCeMois,
            double revenuMoisPrecedent,
            double revenuCetteAnnee,
            double revenuMensuelEstime,
            long paiementsEnAttente,
            List<FermeCourte> essaisFinCetteSemaine,
            List<MoisValeur> revenusParMois,
            List<MoisValeur> nouvellesFermesParMois,
            // Fermes payantes dont le prix n'a pas pu être calculé (comptage des poules en
            // échec) : revenuMensuelEstime les compte au minimum, il est alors incomplet.
            long tarifsEnErreur) {}

    public record Utilisateur(
            String uniqueId,
            String fullName,
            String telephone,
            String email,
            List<String> roles,
            boolean actif,
            LocalDateTime derniereConnexion,
            LocalDateTime creeLe) {}

    public record RappelEnvoye(String type, LocalDate dateFin, LocalDateTime envoyeLe, Integer destinataires,
            Integer emailsEnvoyes) {}

    public record Note(String uniqueId, String contenu, String auteurNom, LocalDateTime creeLe) {}

    public record JournalEntree(
            String uniqueId,
            LocalDateTime date,
            String categorie,
            String action,
            String auteurNom,
            String farmUniqueId,
            String farmNom) {}

    public record FermeDetail(
            Ferme ferme,
            List<Utilisateur> utilisateurs,
            List<PaiementAbonnementDTO> paiements,
            List<RappelEnvoye> rappels,
            List<Note> notes,
            List<JournalEntree> journal,
            // Détail du calcul du prix et tarif spécial éventuel.
            AbonnementTarifDTO tarif) {}

    public record Finances(
            int annee,
            List<MoisValeur> parMois,
            List<CleValeur> parAnnee,
            List<CleValeur> parPeriodicite,
            List<CleValeur> parMoyen,
            double totalAnnee,
            double totalDepuisDebut,
            // Conversion : essais terminés (date de fin passée) et fermes passées au payant.
            long essaisTermines,
            long essaisConvertis,
            double tauxConversion,
            // Fermes perdues : ont déjà payé, abonnement expiré (grâce passée), pas renouvelé.
            long fermesAyantPaye,
            long fermesPerdues,
            double tauxPerte,
            List<FermeCourte> listeFermesPerdues) {}

    public record JournalPage(List<JournalEntree> data, int currentPage, int totalPages, long totalItems, int size) {}
}
