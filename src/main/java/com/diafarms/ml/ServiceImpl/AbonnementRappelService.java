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
    private final com.diafarms.ml.services.LogsServices logs;
    private final AbonnementTarifService tarifService;
    private final CreditService creditService;

    public AbonnementRappelService(AbonnementRepo abonnementRepo, AbonnementConfigRepo configRepo,
            AbonnementRappelRepo rappelRepo, PaiementAbonnementRepo paiementAbonnementRepo,
            UtilisateursRepo utilisateursRepo, EmailService emailService, OtherService otherService,
            PlatformTransactionManager transactionManager, com.diafarms.ml.services.LogsServices logs,
            AbonnementTarifService tarifService, CreditService creditService) {
        this.creditService = creditService;
        this.logs = logs;
        this.tarifService = tarifService;
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
            LocalDate dateFin, String sujet, String messageComplet, List<Destinataire> admins, LocalDate cle) {}

    // Candidat lu en base, avant le calcul de son prix (fait ensuite pour tous en une fois).
    private record Lu(Long abonnementId, Long farmId, String farmUniqueId, String farmNom, String type,
            LocalDate dateFin, AbonnementEcheance.Etat etat, boolean paiementEnAttente, Double prixMensuelFixe,
            String motifPrixFixe, List<Destinataire> admins, LocalDate creditDepuis, double solde, LocalDate cle) {}

    // Crédit prépayé : « crédit bas », un e-mail quand il reste moins d'environ 30 jours
    // (et plus de 7 : ensuite les rappels J-7 et J-1 prennent le relais). Une fois par
    // échéance estimée (même unicité que les autres rappels).
    // ATTENTION : abonnement_rappels.type est un varchar(10) ; "CREDIT_BAS" fait exactement 10
    // caractères. Tout nouveau type doit tenir en 10 caractères (vérifié au démarrage).
    public static final String RAPPEL_CREDIT_BAS = "CREDIT_BAS";

    static {
        for (String t : new String[] { AbonnementEcheance.RAPPEL_J7, AbonnementEcheance.RAPPEL_J1,
                AbonnementEcheance.RAPPEL_GRACE, RAPPEL_CREDIT_BAS }) {
            if (t.length() > 10) throw new IllegalStateException("Type de rappel trop long pour abonnement_rappels.type : " + t);
        }
    }

    // Clé d'unicité d'un rappel (colonne date_fin de abonnement_rappels). Ferme au crédit
    // (hors période déjà payée) : la fin estimée peut passer d'une fin de mois à l'autre
    // quand le nombre de poules change ; J-7, J-1 et « crédit bas » sont donc envoyés au plus
    // une fois par MOIS (clé = 1er du mois en cours), jamais deux fois pour un aller-retour
    // de l'estimation. Grâce, essai et période déjà payée : la date de fin, stable.
    public static LocalDate cleRappel(AbonnementEcheance.Etat e, String type, LocalDate aujourdHui) {
        boolean parMois = e.credit() && !e.periodePayee() && !AbonnementEcheance.RAPPEL_GRACE.equals(type);
        return parMois ? aujourdHui.withDayOfMonth(1) : e.dateFin();
    }

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
        List<RappelDTO> res = executer(envoyer);
        if (envoyer) {
            try {
                // Journal de la console SUPER_ADMIN (catégorie RAPPELS).
                logs.addLogs(u.getId(), null, "AbonnementRappel",
                        "Envoi manuel des rappels d'abonnement : " + res.size() + " rappel(s) envoyé(s)");
            } catch (Exception e) {
                log.warn("Journal de l'envoi manuel des rappels impossible : {}", e.getMessage());
            }
        }
        return res;
    }

    public List<RappelDTO> executer(boolean envoyer) {
        LocalDate aujourdHui = LocalDate.now();
        List<Lu> lus = txLecture.execute(status -> listerCandidats(aujourdHui));
        List<Candidat> candidats = avecMontant(lus);
        List<RappelDTO> resultat = new ArrayList<>();
        for (Candidat c : candidats) {
            List<String> emails = c.admins().stream().map(Destinataire::email).filter(e -> e != null && !e.isBlank()).toList();
            if (!envoyer) {
                resultat.add(new RappelDTO(c.farmUniqueId(), c.farmNom(), c.type(), c.dateFin(), c.sujet(),
                        c.messageComplet(), emails, false, 0));
                continue;
            }
            try {
                Integer reserve = tx.execute(status -> rappelRepo.reserver(c.abonnementId(), c.type(), c.cle()));
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
                tx.execute(status -> rappelRepo.enregistrerEnvoi(c.abonnementId(), c.type(), c.cle(),
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
    private List<Lu> listerCandidats(LocalDate aujourdHui) {
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        List<Lu> candidats = new ArrayList<>();
        java.util.Map<Long, Double> soldes = creditService.soldes();
        for (Abonnement a : abonnementRepo.findAllAvecFerme()) {
          try {
            AbonnementEcheance.Etat etat = AbonnementEcheance.calculer(a, config, aujourdHui);
            String type = etat.rappelDuJour();
            double solde = soldes.getOrDefault(a.getId(), 0.0);
            if (type == null && etat.credit() && !etat.bloque() && solde > 0 && etat.joursRestants() > 7
                    && etat.joursRestants() < com.diafarms.ml.commons.AbonnementCredit.CREDIT_BAS_JOURS) {
                type = RAPPEL_CREDIT_BAS;
            }
            if (type == null) continue;
            LocalDate cle = cleRappel(etat, type, aujourdHui);
            if (rappelRepo.existsByAbonnement_IdAndTypeAndDateFin(a.getId(), type, cle)) continue;

            Farm farm = a.getFarm();
            List<Utilisateurs> admins = utilisateursRepo.findAdminsActifsByFarmId(farm.getId());
            String farmNom = farm.getNom() != null ? farm.getNom()
                    : admins.stream().map(Utilisateurs::getFarmName).filter(n -> n != null && !n.isBlank())
                            .findFirst().orElse("votre ferme");
            boolean paiementEnAttente = paiementAbonnementRepo
                    .findByAbonnement_IdAndStatut(a.getId(), StatutPaiementAbonnement.EN_ATTENTE).isPresent();
            candidats.add(new Lu(a.getId(), farm.getId(), farm.getUniqueId(), farmNom, type, a.getDateFin(), etat,
                    paiementEnAttente, a.getPrixMensuelFixe(), a.getMotifPrixFixe(),
                    admins.stream().map(u -> new Destinataire(u.getFullName(), u.getEmail())).toList(),
                    a.getCreditDepuis(), solde, cle));
          } catch (Exception e) {
            // Une ferme en erreur ne bloque pas les rappels des autres.
            log.warn("Rappel d'abonnement ignoré pour l'abonnement {} : {}", a.getId(), e.getMessage());
          }
        }
        return candidats;
    }

    // Montant du renouvellement dans le message : tarif de chaque ferme (prix par poule ou
    // tarif spécial), calculé pour toutes les fermes concernées en une fois. Un calcul en
    // échec retombe sur le prix minimum : la tâche ne s'arrête jamais pour ça.
    private List<Candidat> avecMontant(List<Lu> lus) {
        if (lus == null || lus.isEmpty()) return List.of();
        AbonnementConfig config = null;
        java.util.Map<Long, com.diafarms.ml.DTO.AbonnementTarifDTO> tarifs = java.util.Map.of();
        java.util.Map<Long, Abonnement> abos = new java.util.HashMap<>();
        for (Lu l : lus) {
            // Copie détachée : seul le prix fixe sert au calcul.
            Abonnement a = new Abonnement();
            a.setPrixMensuelFixe(l.prixMensuelFixe());
            a.setMotifPrixFixe(l.motifPrixFixe());
            abos.put(l.farmId(), a);
        }
        try {
            config = configRepo.findFirstByOrderByIdAsc();
            tarifs = tarifService.tarifsFermes(abos.keySet(), abos, config);
        } catch (Exception e) {
            log.error("Rappels d'abonnement : calcul des prix en échec, prix minimum utilisé : {}", e.getMessage(), e);
        }
        // Crédit prépayé : coût d'un mois au rythme du mois en cours (un seul comptage).
        java.util.Map<Long, CreditService.Rythme> rythmes = null;
        try {
            java.util.Map<Long, LocalDate> depuis = new java.util.HashMap<>();
            for (Lu l : lus) depuis.put(l.farmId(), l.creditDepuis());
            rythmes = creditService.rythmes(depuis.keySet(), depuis, LocalDate.now());
        } catch (Exception e) {
            log.error("Rappels d'abonnement : rythme du mois en échec : {}", e.getMessage(), e);
        }
        com.diafarms.ml.commons.AbonnementTarif.Regles regles = com.diafarms.ml.commons.AbonnementTarif.regles(config);
        com.diafarms.ml.commons.AbonnementCredit.Regles rc = com.diafarms.ml.commons.AbonnementCredit.regles(config);
        String ligneBonus = rc.bonusPourcent() > 0 ? "Une recharge de " + AbonnementEcheance.fcfa(rc.bonusSeuil())
                + " ou plus reçoit " + CreditService.pourcent(rc.bonusPourcent()) + " % de crédit en plus." : null;
        List<Candidat> res = new ArrayList<>();
        for (Lu l : lus) {
            if (l.etat().credit()) {
                CreditService.Rythme r = rythmes == null ? null : rythmes.get(l.farmId());
                boolean fixe = l.prixMensuelFixe() != null && l.prixMensuelFixe() > 0;
                Double cout = r == null && !fixe ? null
                        : com.diafarms.ml.commons.AbonnementCredit.coutMensuel(r == null ? 0 : r.moyenne(), regles, l.prixMensuelFixe());
                String lignePrix = cout == null ? null
                        : "Au rythme actuel, votre ferme coûte environ " + AbonnementEcheance.fcfa(cout) + " par mois.";
                if (RAPPEL_CREDIT_BAS.equals(l.type())) {
                    String phrase = com.diafarms.ml.commons.AbonnementCredit.phraseMois(
                            com.diafarms.ml.commons.AbonnementCredit.moisRestants(l.solde(), cout == null ? regles.prixMinimumMensuel() : cout));
                    String msg = "Le crédit Cocorico de la ferme " + l.farmNom() + " est de " + AbonnementEcheance.fcfa(l.solde())
                            + " : " + phrase + " au rythme actuel. Il sera épuisé vers le " + AbonnementEcheance.date(l.dateFin()) + ".\n\n"
                            + (lignePrix != null ? lignePrix + "\n\n" : "")
                            + (l.paiementEnAttente() ? "Vous avez déjà déclaré une recharge : elle est en cours de vérification.\n\n"
                                    : "Pour recharger : envoyez le montant de votre choix par mobile money au +223 83 91 86 99, puis ouvrez "
                                    + "la page Abonnement dans Cocorico et cliquez sur « J'ai rechargé ». Une recharge de "
                                    + AbonnementEcheance.fcfa(com.diafarms.ml.commons.AbonnementCredit.regles(config).bonusSeuil())
                                    + " ou plus reçoit un bonus de " + CreditService.pourcent(com.diafarms.ml.commons.AbonnementCredit.regles(config).bonusPourcent())
                                    + " %.\n\n")
                            + "Une question ? Écrivez-nous sur WhatsApp au +223 83 91 86 99.";
                    res.add(new Candidat(l.abonnementId(), l.farmUniqueId(), l.farmNom(), l.type(), l.dateFin(),
                            "Votre crédit Cocorico sera bientôt épuisé", msg, l.admins(), l.cle()));
                } else {
                    res.add(new Candidat(l.abonnementId(), l.farmUniqueId(), l.farmNom(), l.type(), l.dateFin(),
                            AbonnementEcheance.sujetEmail(l.etat()),
                            AbonnementEcheance.messageCompletCredit(l.etat(), l.farmNom(), lignePrix, l.paiementEnAttente()),
                            l.admins(), l.cle()));
                }
                continue;
            }
            com.diafarms.ml.DTO.AbonnementTarifDTO t = tarifs.get(l.farmId());
            if (t == null) {
                // Échec : le prix fixe reste connu, le prix par poule non.
                t = com.diafarms.ml.commons.AbonnementTarif.calculer(0, null, abos.get(l.farmId()),
                        com.diafarms.ml.commons.AbonnementTarif.regles(config), true);
            }
            // Tarif en erreur (sans prix fixe) : pas de ligne de montant plutôt qu'un faux prix.
            if (!com.diafarms.ml.commons.AbonnementTarif.facturable(t)) t = null;
            res.add(new Candidat(l.abonnementId(), l.farmUniqueId(), l.farmNom(), l.type(), l.dateFin(),
                    AbonnementEcheance.sujetEmail(l.etat()),
                    AbonnementEcheance.messageComplet(l.etat(), l.farmNom(), t, l.paiementEnAttente(), ligneBonus),
                    l.admins(), l.cle()));
        }
        return res;
    }
}
