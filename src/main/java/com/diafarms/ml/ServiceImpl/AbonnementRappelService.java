package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.diafarms.ml.commons.AbonnementEcheance;
import com.diafarms.ml.enums.StatutPaiementAbonnement;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.AbonnementRappelRepo;
import com.diafarms.ml.repository.AbonnementRepo;
import com.diafarms.ml.repository.PaiementAbonnementRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.services.EmailService;

import lombok.extern.slf4j.Slf4j;

// Rappels de fin d'abonnement : J-7, J-1, puis début du délai de grâce (voir
// AbonnementEcheance pour les fenêtres). Destinataires : les ADMIN actifs de la ferme.
// Canaux :
//   - dans l'application : la ligne AbonnementRappel suffit, la cloche des ADMIN la
//     lit (NotificationServiceImpl.addAbonnementNotification), lien vers /abonnement ;
//   - par email (EmailService), sans jamais bloquer la tâche si l'envoi échoue.
// Une fois par période : la ligne est réservée (INSERT ... ON CONFLICT DO NOTHING) AVANT
// tout envoi, donc deux exécutions (redémarrage, tâche + déclenchement manuel) ne
// renvoient jamais le même rappel. Un renouvellement change dateFin : réarmé.
//
// La tâche tourne sans utilisateur connecté : rien ici ne lit l'utilisateur courant ni
// la devise (le prix de l'abonnement est toujours en FCFA).
@Service
@Slf4j
public class AbonnementRappelService {

    private final AbonnementRepo abonnementRepo;
    private final AbonnementConfigRepo configRepo;
    private final AbonnementRappelRepo rappelRepo;
    private final PaiementAbonnementRepo paiementAbonnementRepo;
    private final UtilisateursRepo utilisateursRepo;
    private final EmailService emailService;
    private final OtherService otherService;
    private final TransactionTemplate tx;
    private final TransactionTemplate txLecture;

    public AbonnementRappelService(AbonnementRepo abonnementRepo, AbonnementConfigRepo configRepo,
            AbonnementRappelRepo rappelRepo, PaiementAbonnementRepo paiementAbonnementRepo,
            UtilisateursRepo utilisateursRepo, EmailService emailService, OtherService otherService,
            PlatformTransactionManager transactionManager) {
        this.abonnementRepo = abonnementRepo;
        this.configRepo = configRepo;
        this.rappelRepo = rappelRepo;
        this.paiementAbonnementRepo = paiementAbonnementRepo;
        this.utilisateursRepo = utilisateursRepo;
        this.emailService = emailService;
        this.otherService = otherService;
        this.tx = new TransactionTemplate(transactionManager);
        this.txLecture = new TransactionTemplate(transactionManager);
        this.txLecture.setReadOnly(true);
    }

    public record Destinataire(String nom, String email) {}

    // Un rappel à envoyer (simulation) ou envoyé (exécution).
    public record RappelDTO(
            String farmUniqueId,
            String farmNom,
            String type,
            LocalDate dateFin,
            String sujet,
            String message,
            List<String> destinataires,
            boolean envoye,
            int emailsEnvoyes) {}

    private record Candidat(Long abonnementId, String farmUniqueId, String farmNom, String type,
            LocalDate dateFin, String sujet, String messageComplet, List<Destinataire> admins) {}

    // Tous les jours à 8 h, heure de Bamako (= UTC).
    @Scheduled(cron = "${abonnement.rappels.cron:0 0 8 * * *}", zone = "Africa/Bamako")
    public void tacheQuotidienne() {
        try {
            List<RappelDTO> envoyes = executer(true);
            long sansEmail = envoyes.stream().filter(r -> r.destinataires() != null && !r.destinataires().isEmpty() && r.emailsEnvoyes() == 0).count();
            log.info("Rappels d'abonnement : {} rappel(s) envoyé(s)", envoyes.size());
            if (sansEmail > 0) log.warn("Rappels d'abonnement : {} rappel(s) sans aucun e-mail parti (vérifier le serveur d'e-mails)", sansEmail);
        } catch (Exception e) {
            log.error("Tâche des rappels d'abonnement en échec : {}", e.getMessage(), e);
        }
    }

    // Déclenchement manuel (SUPER_ADMIN) : envoyer=false ne fait qu'une simulation, rien
    // n'est enregistré ni envoyé.
    public List<RappelDTO> executerManuellement(boolean envoyer) {
        Utilisateurs u = otherService.getCurrentUser();
        boolean superAdmin = u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
        if (!superAdmin) {
            throw new IllegalArgumentException("Seul un SUPER_ADMIN peut effectuer cette action.");
        }
        return executer(envoyer);
    }

    public List<RappelDTO> executer(boolean envoyer) {
        LocalDate aujourdHui = LocalDate.now();
        List<Candidat> candidats = txLecture.execute(status -> listerCandidats(aujourdHui));
        List<RappelDTO> resultat = new ArrayList<>();
        for (Candidat c : candidats) {
            List<String> emails = c.admins().stream().map(Destinataire::email).filter(e -> e != null && !e.isBlank()).toList();
            if (!envoyer) {
                resultat.add(new RappelDTO(c.farmUniqueId(), c.farmNom(), c.type(), c.dateFin(), c.sujet(),
                        c.messageComplet(), emails, false, 0));
                continue;
            }
            try {
                Integer reserve = tx.execute(status -> rappelRepo.reserver(c.abonnementId(), c.type(), c.dateFin()));
                if (reserve == null || reserve == 0) {
                    continue; // déjà envoyé pour cette période (exécution concurrente)
                }
                int emailsEnvoyes = 0;
                for (Destinataire d : c.admins()) {
                    if (d.email() == null || d.email().isBlank()) continue;
                    try {
                        if (emailService.sendRappelAbonnement(d.email(), d.nom(), c.sujet(), c.messageComplet())) {
                            emailsEnvoyes++;
                        }
                    } catch (Exception e) {
                        log.error("Rappel d'abonnement : email à {} en échec : {}", d.email(), e.getMessage());
                    }
                }
                final int nbEmails = emailsEnvoyes;
                tx.execute(status -> rappelRepo.enregistrerEnvoi(c.abonnementId(), c.type(), c.dateFin(),
                        c.admins().size(), nbEmails));
                resultat.add(new RappelDTO(c.farmUniqueId(), c.farmNom(), c.type(), c.dateFin(), c.sujet(),
                        c.messageComplet(), emails, true, nbEmails));
            } catch (Exception e) {
                // Une ferme en erreur ne doit jamais empêcher les rappels des autres.
                log.error("Rappel d'abonnement de la ferme {} en échec : {}", c.farmNom(), e.getMessage(), e);
            }
        }
        return resultat;
    }

    // Lecture seule, dans une transaction : tout ce dont l'envoi a besoin est copié dans
    // des records (open-in-view=false, aucune entité paresseuse ne sort d'ici).
    private List<Candidat> listerCandidats(LocalDate aujourdHui) {
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        List<Candidat> candidats = new ArrayList<>();
        for (Abonnement a : abonnementRepo.findAllAvecFerme()) {
          try {
            AbonnementEcheance.Etat etat = AbonnementEcheance.calculer(a, config, aujourdHui);
            String type = etat.rappelDuJour();
            if (type == null) continue;
            if (rappelRepo.existsByAbonnement_IdAndTypeAndDateFin(a.getId(), type, a.getDateFin())) continue;

            Farm farm = a.getFarm();
            List<Utilisateurs> admins = utilisateursRepo.findAdminsActifsByFarmId(farm.getId());
            String farmNom = farm.getNom() != null ? farm.getNom()
                    : admins.stream().map(Utilisateurs::getFarmName).filter(n -> n != null && !n.isBlank())
                            .findFirst().orElse("votre ferme");
            boolean paiementEnAttente = paiementAbonnementRepo
                    .findByAbonnement_IdAndStatut(a.getId(), StatutPaiementAbonnement.EN_ATTENTE).isPresent();
            candidats.add(new Candidat(a.getId(), farm.getUniqueId(), farmNom, type, a.getDateFin(),
                    AbonnementEcheance.sujetEmail(etat),
                    AbonnementEcheance.messageComplet(etat, farmNom, config, paiementEnAttente),
                    admins.stream().map(u -> new Destinataire(u.getFullName(), u.getEmail())).toList()));
          } catch (Exception e) {
            // Une ferme en erreur ne bloque pas les rappels des autres.
            log.warn("Rappel d'abonnement ignoré pour l'abonnement {} : {}", a.getId(), e.getMessage());
          }
        }
        return candidats;
    }
}
