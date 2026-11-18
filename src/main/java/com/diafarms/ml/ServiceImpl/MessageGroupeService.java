package com.diafarms.ml.ServiceImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.services.EmailService;
import com.diafarms.ml.services.LogsServices;


import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

// Message écrit par le SUPER_ADMIN et envoyé par e-mail à un groupe d'utilisateurs
// (console > E-mails et parrainages).
//   cible PROPRIETAIRES : comptes ADMIN de chaque ferme ; TOUS : tous les comptes.
//   situations : filtre facultatif sur la situation de la ferme (FermesInactivesService).
//   Comptes actifs avec un e-mail seulement ; jamais les SUPER_ADMIN ; une adresse ne
//   reçoit le message qu'une fois ; fermes hors statistiques exclues sauf demande.
// L'envoi tourne en arrière-plan (une seule file, 1 seconde entre deux e-mails pour rester
// sous les limites de Gmail) : la console reçoit tout de suite le nombre de destinataires.
@Service
@Slf4j
public class MessageGroupeService {

    // Gmail (compte simple) accepte environ 500 destinataires par jour.
    static final int MAX_DESTINATAIRES = 400;

    private final JdbcTemplate jdbc;
    private final EmailService emailService;
    private final FermesInactivesService fermes;
    private final AdminConsoleService console;
    private final OtherService otherService;
    private final LogsServices logs;
    private final ExecutorService file = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "message-groupe");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean enCours = new AtomicBoolean(false);

    public MessageGroupeService(JdbcTemplate jdbc, EmailService emailService, FermesInactivesService fermes,
            AdminConsoleService console, OtherService otherService, LogsServices logs) {
        this.jdbc = jdbc;
        this.emailService = emailService;
        this.fermes = fermes;
        this.console = console;
        this.otherService = otherService;
        this.logs = logs;
    }

    @PreDestroy
    void arreter() {
        file.shutdown();
    }

    public record Demande(String sujet, String message, String cible, List<String> situations, Boolean inclureHorsStats) {}

    public record Destinataire(String nom, String email) {}

    public record Resultat(int destinataires, List<String> apercu, boolean envoye, boolean envoiEnCours) {}

    // Nombre de destinataires et premières adresses, rien n'est envoyé.
    public Resultat apercu(Demande d) {
        console.verifierSuperAdmin();
        List<Destinataire> dest = destinataires(d);
        return new Resultat(dest.size(), dest.stream().limit(10).map(Destinataire::email).toList(), false, enCours.get());
    }

    // Essai : envoyé seulement à l'adresse du SUPER_ADMIN connecté.
    public Resultat essai(Demande d) {
        console.verifierSuperAdmin();
        verifierTexte(d);
        Utilisateurs moi = otherService.getCurrentUser();
        if (moi.getEmail() == null || moi.getEmail().isBlank()) {
            throw new IllegalArgumentException("Votre compte n'a pas d'adresse e-mail.");
        }
        boolean ok = emailService.sendMessageCocorico(moi.getEmail(), moi.getFullName(), "Cocorico", d.sujet().trim(), d.message().trim());
        if (!ok) throw new IllegalArgumentException("L'e-mail d'essai n'a pas pu partir.");
        return new Resultat(1, List.of(moi.getEmail()), true, enCours.get());
    }

    public Resultat envoyer(Demande d) {
        console.verifierSuperAdmin();
        verifierTexte(d);
        Utilisateurs moi = otherService.getCurrentUser();
        List<Destinataire> dest = destinataires(d);
        if (dest.isEmpty()) throw new IllegalArgumentException("Aucun destinataire.");
        if (dest.size() > MAX_DESTINATAIRES) {
            throw new IllegalArgumentException("Trop de destinataires (" + dest.size() + ") : " + MAX_DESTINATAIRES
                    + " au maximum par jour avec Gmail. Choisissez un groupe plus petit.");
        }
        if (!enCours.compareAndSet(false, true)) {
            throw new IllegalArgumentException("Un envoi est déjà en cours. Réessayez dans quelques minutes.");
        }
        String sujet = d.sujet().trim(), message = d.message().trim();
        Long auteur = moi.getId();
        logs.addLogs(auteur, null, AdminConsoleService.ENTITE_ADMIN_FERME,
                "Message à " + dest.size() + " destinataire(s) : " + sujet);
        file.submit(() -> {
            int ok = 0;
            try {
                for (Destinataire x : dest) {
                    try {
                        if (emailService.sendMessageCocorico(x.email(), x.nom(), "Cocorico", sujet, message)) ok++;
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("Message groupé à {} en échec : {}", x.email(), e.getMessage());
                    }
                }
            } finally {
                enCours.set(false);
                log.info("Message groupé « {} » : {} e-mail(s) envoyé(s) sur {}", sujet, ok, dest.size());
            }
        });
        return new Resultat(dest.size(), dest.stream().limit(10).map(Destinataire::email).toList(), true, true);
    }

    private static void verifierTexte(Demande d) {
        if (d.sujet() == null || d.sujet().isBlank()) throw new IllegalArgumentException("L'objet est obligatoire.");
        if (d.message() == null || d.message().isBlank()) throw new IllegalArgumentException("Le message est obligatoire.");
        if (d.sujet().length() > 200) throw new IllegalArgumentException("Objet trop long (200 caractères au maximum).");
    }

    List<Destinataire> destinataires(Demande d) {
        boolean tous = "TOUS".equalsIgnoreCase(d.cible());
        boolean horsStats = Boolean.TRUE.equals(d.inclureHorsStats());
        Set<String> filtre = d.situations() == null ? Set.of() : Set.copyOf(d.situations());
        Map<Long, FermesInactivesService.Situation> situations = filtre.isEmpty() ? Map.of() : fermes.situations();

        Map<String, Destinataire> parEmail = new LinkedHashMap<>();
        jdbc.query("SELECT u.farm_id, u.full_name, LOWER(TRIM(u.email)) FROM utilisateurs u JOIN farms f ON f.id = u.farm_id "
                + "WHERE u.email IS NOT NULL AND TRIM(u.email) <> '' "
                + "AND COALESCE(u.removed, false) = false AND COALESCE(u.archive, false) = false "
                + "AND COALESCE(u.statut, true) = true "
                + (horsStats ? "" : "AND COALESCE(f.exclure_statistiques, false) = false ")
                + "AND NOT EXISTS (SELECT 1 FROM roles_users ru JOIN roles r ON r.id = ru.id_roles "
                + "  WHERE ru.id_utilisateurs = u.id AND UPPER(r.role) = 'SUPER_ADMIN') "
                + (tous ? "" : "AND EXISTS (SELECT 1 FROM roles_users ru JOIN roles r ON r.id = ru.id_roles "
                        + "  WHERE ru.id_utilisateurs = u.id AND UPPER(r.role) = 'ADMIN') ")
                + "ORDER BY u.farm_id, u.id", rs -> {
                    long farmId = rs.getLong(1);
                    if (!filtre.isEmpty()) {
                        FermesInactivesService.Situation s = situations.get(farmId);
                        if (s == null || !filtre.contains(s.name())) return;
                    }
                    parEmail.putIfAbsent(rs.getString(3), new Destinataire(rs.getString(2), rs.getString(3)));
                });
        return new ArrayList<>(parEmail.values());
    }
}
