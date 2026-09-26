package com.diafarms.ml.ServiceImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.diafarms.ml.DTO.RepriseAcompteReserveRapportDTO;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.models.*;
import com.diafarms.ml.repository.ImputationPaiementRepo;
import com.diafarms.ml.services.LogsServices;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// Reprise « acompte réservé » (règle du 2026-09-26, voir CompteClientService.estReservee).
// Avant cette règle, l'acompte d'une commande pouvait régler une ANCIENNE vente du client.
// Pour chaque commande encore ouverte, les imputations de ses paiements qui visent une
// vente qui n'est PAS une livraison de cette commande sont annulées (motif tracé) : cet
// argent redevient réservé à la commande. Puis imputer() repasse sur le client : les
// acomptes réservés règlent d'éventuelles livraisons de leur commande, l'argent libre
// règle les anciennes ventes ainsi libérées. Les remboursements ne sont pas touchés
// (l'argent est déjà rendu). Idempotent : après passage, plus rien ne correspond.
// Une ferme = une transaction ; en simulation (défaut) elle est annulée à la fin.
@Service
public class RepriseAcompteReserveService {

    static final String MOTIF = "Reprise acompte réservé : l'acompte reste réservé à sa commande";
    static final String ACTION_LOG_EXECUTION = "Reprise acompte réservé exécutée";

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate txTemplate;
    private final CompteClientService compteClientService;
    private final ImputationPaiementRepo imputationRepo;
    private final LogsServices logs;

    public RepriseAcompteReserveService(PlatformTransactionManager txManager, CompteClientService compteClientService,
                                        ImputationPaiementRepo imputationRepo, LogsServices logs) {
        this.txTemplate = new TransactionTemplate(txManager);
        this.compteClientService = compteClientService;
        this.imputationRepo = imputationRepo;
        this.logs = logs;
    }

    private static String fcfa(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + " FCFA";
    }

    public RepriseAcompteReserveRapportDTO lancer(List<Farm> farms, boolean executer, Utilisateurs lanceur) {
        RepriseAcompteReserveRapportDTO rapport = new RepriseAcompteReserveRapportDTO();
        rapport.setExecute(executer);
        for (Farm farm : farms) {
            String nomFerme = farm.getNom() != null && !farm.getNom().isBlank() ? farm.getNom() : farm.getUniqueId();
            RepriseAcompteReserveRapportDTO partiel = new RepriseAcompteReserveRapportDTO();
            try {
                txTemplate.executeWithoutResult(status -> {
                    traiterFerme(farm.getId(), nomFerme, executer, partiel);
                    if (executer) {
                        if (lanceur != null && !partiel.getLignes().isEmpty())
                            logs.addLogs(lanceur.getId(), farm.getId(), "Farm", ACTION_LOG_EXECUTION);
                    } else {
                        status.setRollbackOnly(); // simulation : rien n'est écrit
                    }
                });
            } catch (RuntimeException e) {
                rapport.getAvertissements().add("Ferme " + nomFerme
                        + " : échec, rien n'a été appliqué pour cette ferme (" + e.getMessage() + ")");
                continue;
            }
            rapport.getLignes().addAll(partiel.getLignes());
            rapport.getAvertissements().addAll(partiel.getAvertissements());
            rapport.setClientsConcernes(rapport.getClientsConcernes() + partiel.getClientsConcernes());
            rapport.setImputationsRetirees(rapport.getImputationsRetirees() + partiel.getImputationsRetirees());
            rapport.setMontantRetire(CalculImputation.arrondi(rapport.getMontantRetire() + partiel.getMontantRetire()));
        }
        return rapport;
    }

    private void traiterFerme(Long farmId, String nomFerme, boolean executer, RepriseAcompteReserveRapportDTO rapport) {
        List<Client> clients = em.createQuery(
                "SELECT c FROM Client c WHERE c.farm.id = :f ORDER BY c.nom, c.id", Client.class)
                .setParameter("f", farmId).getResultList();
        for (Client c : clients) {
            // Exécution : verrou client AVANT de lire ses imputations (une saisie
            // simultanée ne peut plus les changer entre la lecture et l'annulation). En
            // simulation on ne verrouille pas toute la ferme : seuls les clients concernés
            // le sont, par imputer(), et tout est annulé à la fin.
            if (executer) compteClientService.verrouiller(c);
            Map<Long, Commande> commandes = new LinkedHashMap<>();
            for (ImputationPaiement i : imputationRepo.findActivesDePaiementsDeCommandeByClientId(c.getId())) {
                Commande k = i.getPaiement().getCommande();
                if (CompteClientService.estReservee(k)) commandes.putIfAbsent(k.getId(), k);
            }
            List<ImputationPaiement> fautives = new ArrayList<>();
            for (Commande k : commandes.values()) fautives.addAll(compteClientService.imputationsHorsCommande(k));
            if (fautives.isEmpty()) continue;

            RepriseAcompteReserveRapportDTO.Ligne ligne = new RepriseAcompteReserveRapportDTO.Ligne();
            ligne.setFerme(nomFerme);
            ligne.setClientUniqueId(c.getUniqueId());
            ligne.setClientNom(c.getNom());
            ligne.setAvant(compteClientService.compte(c));
            double total = 0;
            for (ImputationPaiement i : fautives) {
                Commande k = i.getPaiement().getCommande();
                ligne.getMouvements().add(fcfa(i.getMontant()) + " du paiement du " + i.getPaiement().getDate()
                        + " (commande du " + k.getDateCommande() + ") retirés de la vente " + i.getCibleUniqueId()
                        + " : de nouveau réservés à la commande");
                total += i.getMontant();
            }
            // Même traitement que la réouverture d'une commande (CompteClientService.reReserver).
            for (Commande k : commandes.values()) compteClientService.annulerHorsCommande(k, MOTIF);
            em.flush();
            compteClientService.imputer(c);
            em.flush();
            ligne.setApres(compteClientService.compte(c));
            rapport.getLignes().add(ligne);
            rapport.setClientsConcernes(rapport.getClientsConcernes() + 1);
            rapport.setImputationsRetirees(rapport.getImputationsRetirees() + fautives.size());
            rapport.setMontantRetire(CalculImputation.arrondi(rapport.getMontantRetire() + total));
        }
    }
}
