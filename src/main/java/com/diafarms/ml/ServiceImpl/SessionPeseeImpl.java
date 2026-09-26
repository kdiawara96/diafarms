package com.diafarms.ml.ServiceImpl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.EvolutionPoidsDTO;
import com.diafarms.ml.DTO.PeseeDTO;
import com.diafarms.ml.DTO.SessionPeseeDTO;
import com.diafarms.ml.DTO.SessionPeseeEvenementDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.OriginePesee;
import com.diafarms.ml.enums.StatutSessionPesee;
import com.diafarms.ml.enums.TypeEvenementPesee;
import com.diafarms.ml.models.Pesee;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.SessionPesee;
import com.diafarms.ml.models.SessionPeseeEvenement;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.PeseeRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.SessionPeseeEvenementRepo;
import com.diafarms.ml.repository.SessionPeseeRepo;
import com.diafarms.ml.request.others.SessionPeseeSyncRequest;
import com.diafarms.ml.request.others.SessionPeseeWebRequest;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.SessionPeseeService;

import lombok.RequiredArgsConstructor;

// Sessions de pesée saisies hors ligne sur le téléphone puis synchronisées, ou
// menées directement depuis le web.
// Synchro : le téléphone renvoie toujours la session ENTIÈRE (identifiants UUID
// générés par lui) ; le serveur n'ajoute que les pesées nouvelles, n'accepte que
// l'annulation (définitive) des pesées existantes et recalcule seul les totaux. Les
// valeurs du serveur priment : une pesée corrigée ou ajoutée sur le web n'est jamais
// écrasée ni supprimée par une synchro.
// Web : création, ajout, correction, annulation, terminaison ; chaque action écrit un
// événement (sessions_pesee_evenements) et incrémente la version de la session.
@Service
@RequiredArgsConstructor
public class SessionPeseeImpl implements SessionPeseeService {

    static final String MSG_TERMINEE = "Cette session de pesée est terminée : elle ne peut plus être modifiée.";
    private static final Set<String> ROLES_SAISIE = Set.of("ADMIN", "SUPER_ADMIN", "RESPONSABLE", "PRODUCTION");

    private final SessionPeseeRepo sessionRepo;
    private final PeseeRepo peseeRepo;
    private final ProjetsRepo projetsRepo;
    private final SessionPeseeEvenementRepo evenementRepo;
    private final LogsServices logs;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Long farmIdOuErreur(Utilisateurs u) {
        if (u == null || u.getFarm() == null) {
            throw new IllegalArgumentException("Aucune ferme associée à l'utilisateur connecté.");
        }
        return u.getFarm().getId();
    }

    private boolean peutSaisir(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> r.getRole() != null && ROLES_SAISIE.contains(r.getRole().toUpperCase()));
    }

    // Projet de la ferme de l'utilisateur, sinon "introuvable" (on ne révèle pas
    // l'existence d'un projet d'une autre ferme).
    private Projets projetDeLaFerme(String projetUniqueId, Long farmId) {
        if (projetUniqueId == null || projetUniqueId.isBlank()) {
            throw new IllegalArgumentException("Le projet est obligatoire.");
        }
        Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));
        if (projet.getFarm() == null || !Objects.equals(projet.getFarm().getId(), farmId)) {
            throw new IllegalArgumentException("Projet introuvable : " + projetUniqueId);
        }
        if (projet.getInitialisation() != null && vrai(projet.getInitialisation().getRemoved())) {
            throw new IllegalArgumentException("Ce projet a été supprimé.");
        }
        return projet;
    }

    // Accepte "2026-09-25T08:30:00" (heure locale) ou une date avec fuseau
    // ("...Z", "+00:00"), ramenée à l'heure locale du serveur.
    private static LocalDateTime parseDateHeure(String brut, String champ) {
        if (brut == null || brut.isBlank()) return null;
        String s = brut.trim();
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Date invalide pour " + champ + " : " + brut);
            }
        }
    }

    private static double arrondi3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private static StatutSessionPesee parseStatut(String brut, StatutSessionPesee parDefaut) {
        if (brut == null || brut.isBlank()) return parDefaut;
        try {
            return StatutSessionPesee.valueOf(brut.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Statut de session invalide : " + brut);
        }
    }

    static final int MAX_SUJETS_PAR_PESEE = 10_000;
    static final double MAX_POIDS_PAR_PESEE_KG = 100_000.0;

    // Contrôles communs à la synchro et au web ; renvoie le poids arrondi à 3 décimales.
    private static double validerPesee(Integer nombreSujets, Double poidsKg) {
        if (nombreSujets == null || nombreSujets < 1) {
            throw new IllegalArgumentException("Le nombre de sujets d'une pesée doit être au moins 1.");
        }
        if (nombreSujets > MAX_SUJETS_PAR_PESEE) {
            throw new IllegalArgumentException("Le nombre de sujets d'une pesée ne peut pas dépasser " + MAX_SUJETS_PAR_PESEE + ".");
        }
        if (poidsKg == null || poidsKg.isNaN() || poidsKg.isInfinite() || arrondi3(poidsKg) <= 0) {
            throw new IllegalArgumentException("Le poids d'une pesée doit être supérieur à 0.");
        }
        double poids = arrondi3(poidsKg);
        if (poids > MAX_POIDS_PAR_PESEE_KG) {
            throw new IllegalArgumentException("Le poids d'une pesée ne peut pas dépasser 100 000 kg.");
        }
        return poids;
    }

    // Date d'une pesée reçue ; pour une pesée déjà annulée, une date illisible ne bloque pas.
    private static LocalDateTime dateHeureRecue(SessionPeseeSyncRequest.PeseeItem item) {
        if (vrai(item.getAnnulee())) {
            try {
                return parseDateHeure(item.getDateHeure(), "dateHeure");
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return parseDateHeure(item.getDateHeure(), "dateHeure");
    }

    static final long TOLERANCE_FUTUR_JOURS = 1;

    // Contrôles de la date de fin (web et téléphone) : pas avant le début ni avant la
    // dernière pesée non annulée ; dans le futur, tolérance d'1 jour (horloges de
    // téléphone décalées), au-delà ramenée à maintenant (ou à la dernière pesée si elle
    // est plus tardive) plutôt que refusée, pour ne jamais bloquer un téléphone.
    private static LocalDateTime dateFinValidee(SessionPesee s, LocalDateTime fin) {
        LocalDateTime maintenant = LocalDateTime.now();
        LocalDateTime derniere = s.getDerniereDatePesee();
        if (fin.isAfter(maintenant.plusDays(TOLERANCE_FUTUR_JOURS))) {
            fin = maintenant;
            if (derniere != null && fin.isBefore(derniere)) fin = derniere;
        }
        if (fin.isBefore(s.getDateDebut())) {
            throw new IllegalArgumentException("La date de fin ne peut pas précéder la date de début de la session.");
        }
        if (derniere != null && fin.isBefore(derniere)) {
            throw new IllegalArgumentException("La date de fin ne peut pas précéder la dernière pesée de la session ("
                    + derniere.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + ").");
        }
        return fin;
    }

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");

    // « Pesée de 08:10 » : les clients rapprochent événement et pesée par peseeUniqueId.
    private static String libellePesee(Pesee p) {
        return "Pesée de " + (p.getDateHeure() != null ? p.getDateHeure().format(HEURE) : "?");
    }

    private static long version(SessionPesee s) {
        return s.getVersion() != null ? s.getVersion() : 0L;
    }

    private static void incrementerVersion(SessionPesee s) {
        s.setVersion(version(s) + 1);
    }

    private static final DecimalFormatSymbols FR = DecimalFormatSymbols.getInstance(Locale.FRANCE);

    // 6.3 → "6,3" ; 2.1375 → "2,138".
    private static String kg(Double v) {
        return new DecimalFormat("0.###", FR).format(v != null ? v : 0.0);
    }

    private static String sujets(Integer n) {
        int x = n != null ? n : 0;
        return x + (x > 1 ? " sujets" : " sujet");
    }

    private static boolean vrai(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private static String nom(Utilisateurs u) {
        if (u == null) return null;
        return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
    }

    // ------------------------------------------------------------------ synchro

    @Override
    @Transactional
    public SessionPeseeDTO sync(SessionPeseeSyncRequest req, boolean contratV2) {
        if (req == null) throw new IllegalArgumentException("Requête vide.");
        Utilisateurs user = getCurrentUserSafe();
        Long farmId = farmIdOuErreur(user);
        if (!peutSaisir(user)) {
            throw new IllegalArgumentException("Vous n'êtes pas autorisé à saisir des pesées.");
        }
        String uid = req.getUniqueId() != null ? req.getUniqueId().trim() : "";
        if (uid.isEmpty() || uid.length() > 50) {
            throw new IllegalArgumentException("Identifiant de session invalide.");
        }
        Projets projet = projetDeLaFerme(req.getProjetUniqueId(), farmId);
        StatutSessionPesee statutDemande = parseStatut(req.getStatut(), StatutSessionPesee.EN_COURS);

        // Verrou du projet : les synchros concurrentes d'une même session passent l'une
        // après l'autre (la seconde voit ce que la première a inséré).
        sessionRepo.lockProjet(projet.getId());

        // Pesées reçues, dédoublonnées par uniqueId (la première occurrence gagne).
        Map<String, SessionPeseeSyncRequest.PeseeItem> recues = new LinkedHashMap<>();
        if (req.getPesees() != null) {
            for (SessionPeseeSyncRequest.PeseeItem item : req.getPesees()) {
                if (item == null) continue;
                String pid = item.getUniqueId() != null ? item.getUniqueId().trim() : "";
                if (pid.isEmpty() || pid.length() > 50) {
                    throw new IllegalArgumentException("Identifiant de pesée invalide.");
                }
                recues.putIfAbsent(pid, item);
            }
        }

        SessionPesee session = sessionRepo.findByUniqueId(uid).orElse(null);
        boolean nouvelle = session == null;
        if (!nouvelle) {
            if (session.getFarm() == null || !Objects.equals(session.getFarm().getId(), farmId)
                    || !Objects.equals(session.getProjet().getId(), projet.getId())) {
                throw new IllegalArgumentException("Cette session de pesée appartient à un autre projet.");
            }
        }

        List<Pesee> existantes = nouvelle ? new ArrayList<>() : peseeRepo.findBySessionIdOrdered(session.getId());
        Map<String, Pesee> parUid = new HashMap<>();
        for (Pesee p : existantes) parUid.put(p.getUniqueId(), p);

        // Un uniqueId de pesée déjà utilisé par une AUTRE session est refusé.
        Set<String> inconnues = new HashSet<>(recues.keySet());
        inconnues.removeAll(parUid.keySet());
        if (!inconnues.isEmpty()) {
            for (Pesee autre : peseeRepo.findByUniqueIdIn(inconnues)) {
                throw new IllegalArgumentException("La pesée " + autre.getUniqueId() + " appartient à une autre session.");
            }
        }

        if (!nouvelle && session.getStatut() == StatutSessionPesee.TERMINEE) {
            // Session déjà terminée sur le serveur (par le téléphone lui-même ou depuis le
            // web) : RIEN n'est écrit, la réponse (200) donne l'état du serveur. Les
            // nouvelles pesées du téléphone sont listées dans peseesRefusees ; le statut
            // demandé (EN_COURS ou TERMINEE), nombreParDefaut et les annulations de
            // pesées existantes sont ignorés : le serveur prime.
            // Compatibilité : un téléphone qui n'envoie pas l'en-tête X-Pesee-Contrat: 2
            // (APK ≤ 1.27) ne connaît pas peseesRefusees ; pour lui, des pesées refusées
            // donnent l'ancienne 400 « session terminée ».
            List<String> refusees = recues.keySet().stream().filter(inconnues::contains).toList();
            if (!contratV2 && !refusees.isEmpty()) throw new IllegalArgumentException(MSG_TERMINEE);
            SessionPeseeDTO dto = toDto(session, existantes);
            dto.setPeseesRefusees(new ArrayList<>(refusees));
            return dto;
        }

        if (nouvelle) {
            session = new SessionPesee();
            session.setUniqueId(uid);
            session.setProjet(projet);
            session.setFarm(projet.getFarm());
            session.setCreePar(user);
            session.setStatut(StatutSessionPesee.EN_COURS);
            LocalDateTime debut = parseDateHeure(req.getDateDebut(), "dateDebut");
            // À défaut : la première pesée reçue, sinon maintenant.
            if (debut == null) {
                for (SessionPeseeSyncRequest.PeseeItem item : recues.values()) {
                    LocalDateTime dh = dateHeureRecue(item);
                    if (dh != null && (debut == null || dh.isBefore(debut))) debut = dh;
                }
            }
            session.setDateDebut(debut != null ? debut : LocalDateTime.now());
            session.setInitialisation(Initialisation.init());
        }
        boolean defautChange = false;
        if (req.getNombreParDefaut() != null) {
            if (req.getNombreParDefaut() < 1) {
                throw new IllegalArgumentException("Le nombre de sujets par défaut doit être au moins 1.");
            }
            defautChange = !nouvelle && !Objects.equals(session.getNombreParDefaut(), req.getNombreParDefaut());
            session.setNombreParDefaut(req.getNombreParDefaut());
        } else if (session.getNombreParDefaut() == null) {
            session.setNombreParDefaut(1);
        }
        if (nouvelle) {
            session.setNombreTotalSujets(0);
            session.setPoidsTotalKg(0.0);
            session.setPoidsMoyenKg(0.0);
            session.setOrigine(OriginePesee.MOBILE);
            session.setVersion(0L);
            session = sessionRepo.save(session);
        }

        int ajoutees = 0;
        int annulees = 0;
        for (Map.Entry<String, SessionPeseeSyncRequest.PeseeItem> e : recues.entrySet()) {
            SessionPeseeSyncRequest.PeseeItem item = e.getValue();
            Pesee existante = parUid.get(e.getKey());
            if (existante != null) {
                // Seule l'annulation est acceptée (jamais d'annulation inverse, jamais
                // d'autre champ modifié).
                if (vrai(item.getAnnulee()) && !vrai(existante.getAnnulee())) {
                    existante.setAnnulee(true);
                    Initialisation.updateDate(existante.getInitialisation());
                    peseeRepo.save(existante);
                    annulees++;
                }
                continue;
            }
            boolean dejaAnnulee = vrai(item.getAnnulee());
            int nombre;
            double poids;
            LocalDateTime dateHeure = dateHeureRecue(item);
            if (dejaAnnulee) {
                // Pesée arrivée déjà annulée : elle ne compte pas dans les totaux, ses
                // valeurs ne sont pas contrôlées (elle ne doit jamais bloquer une synchro).
                Integer n = item.getNombreSujets();
                Double kg = item.getPoidsKg();
                nombre = n != null && n >= 0 ? n : 0;
                poids = kg != null && !kg.isNaN() && !kg.isInfinite() ? arrondi3(kg) : 0.0;
                if (dateHeure == null) dateHeure = session.getDateDebut();
            } else {
                poids = validerPesee(item.getNombreSujets(), item.getPoidsKg());
                nombre = item.getNombreSujets();
                if (dateHeure == null) {
                    throw new IllegalArgumentException("La date et l'heure de la pesée sont obligatoires.");
                }
            }
            Pesee p = new Pesee();
            p.setUniqueId(e.getKey());
            p.setSession(session);
            p.setNombreSujets(nombre);
            p.setPoidsKg(poids);
            p.setDateHeure(dateHeure);
            p.setAnnulee(vrai(item.getAnnulee()));
            p.setOrigine(OriginePesee.MOBILE);
            p.setCreePar(user);
            p.setInitialisation(Initialisation.init());
            existantes.add(peseeRepo.save(p));
            ajoutees++;
        }

        recalculer(session, existantes);

        boolean terminee = false;
        if (statutDemande == StatutSessionPesee.TERMINEE) {
            if (session.getNombreTotalSujets() == null || session.getNombreTotalSujets() <= 0) {
                throw new IllegalArgumentException("Impossible de terminer une session sans aucune pesée.");
            }
            session.setStatut(StatutSessionPesee.TERMINEE);
            LocalDateTime fin = parseDateHeure(req.getDateFin(), "dateFin");
            // À défaut : la dernière pesée non annulée (pas l'heure de réception, qui
            // peut être bien plus tardive pour une saisie hors ligne).
            if (fin == null) fin = session.getDerniereDatePesee();
            session.setDateFin(dateFinValidee(session, fin));
            terminee = true;
        }
        if (!nouvelle) Initialisation.updateDate(session.getInitialisation());
        if (nouvelle || ajoutees > 0 || annulees > 0 || terminee || defautChange) incrementerVersion(session);
        session = sessionRepo.save(session);

        if (nouvelle || ajoutees > 0 || annulees > 0 || terminee) {
            StringBuilder action = new StringBuilder(nouvelle ? "Ouverture" : "Synchronisation")
                    .append(" d'une session de pesée pour le projet '").append(projet.getTitre()).append("'");
            if (ajoutees > 0) action.append(", ").append(ajoutees).append(" pesée(s) ajoutée(s)");
            if (annulees > 0) action.append(", ").append(annulees).append(" pesée(s) annulée(s)");
            if (terminee) action.append(", session terminée (").append(session.getNombreTotalSujets())
                    .append(" sujets, poids moyen ").append(session.getPoidsMoyenKg()).append(" kg)");
            logs.addLogs(user.getId(), session.getId(), "SessionPesee", action.toString());
        }

        trier(existantes);
        return toDto(session, existantes);
    }

    // Ordre d'affichage : dateHeure puis id.
    private static void trier(List<Pesee> pesees) {
        pesees.sort((a, b) -> {
            int c = a.getDateHeure().compareTo(b.getDateHeure());
            return c != 0 ? c : Long.compare(a.getId(), b.getId());
        });
    }

    // ------------------------------------------------------------------ web

    // Contexte d'une action web sur une session existante, obtenu SOUS le verrou du
    // projet (même verrou que la synchro : une action web et une synchro du téléphone
    // sur le même projet passent l'une après l'autre).
    private record Ctx(Utilisateurs user, SessionPesee session, List<Pesee> pesees) {}

    private Utilisateurs utilisateurSaisie() {
        Utilisateurs user = getCurrentUserSafe();
        farmIdOuErreur(user);
        if (!peutSaisir(user)) {
            throw new IllegalArgumentException("Vous n'êtes pas autorisé à saisir des pesées.");
        }
        return user;
    }

    private Ctx ouvrirPourEcriture(String sessionUid) {
        Utilisateurs user = utilisateurSaisie();
        Long farmId = user.getFarm().getId();
        String uid = sessionUid != null ? sessionUid.trim() : "";
        Long projetId = sessionRepo.findProjetIdByUniqueIdAndFarm(uid, farmId)
                .orElseThrow(() -> new IllegalArgumentException("Session de pesée introuvable : " + sessionUid));
        sessionRepo.lockProjet(projetId);
        // Relue APRÈS le verrou : on voit ce qu'une synchro concurrente vient d'écrire.
        SessionPesee session = sessionRepo.findByUniqueId(uid)
                .filter(x -> !vrai(x.getInitialisation() != null ? x.getInitialisation().getRemoved() : null))
                .orElseThrow(() -> new IllegalArgumentException("Session de pesée introuvable : " + sessionUid));
        Projets projet = session.getProjet();
        if (projet.getInitialisation() != null && vrai(projet.getInitialisation().getRemoved())) {
            throw new IllegalArgumentException("Ce projet a été supprimé.");
        }
        List<Pesee> pesees = new ArrayList<>(peseeRepo.findBySessionIdOrdered(session.getId()));
        return new Ctx(user, session, pesees);
    }

    private static void exigerEnCours(SessionPesee s) {
        if (s.getStatut() == StatutSessionPesee.TERMINEE) throw new IllegalArgumentException(MSG_TERMINEE);
    }

    private static Pesee peseeDe(Ctx ctx, String peseeUid) {
        String pid = peseeUid != null ? peseeUid.trim() : "";
        return ctx.pesees().stream().filter(p -> p.getUniqueId().equals(pid)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Pesée introuvable dans cette session : " + peseeUid));
    }

    private void evenement(SessionPesee s, TypeEvenementPesee type, Pesee p, Integer ancienNombre, Double ancienPoids,
                           Integer nouveauNombre, Double nouveauPoids, Utilisateurs par, String description) {
        SessionPeseeEvenement e = new SessionPeseeEvenement();
        e.setUniqueId(UUID.randomUUID().toString());
        e.setSession(s);
        e.setType(type);
        e.setPeseeUniqueId(p != null ? p.getUniqueId() : null);
        e.setAncienNombre(ancienNombre);
        e.setAncienPoids(ancienPoids);
        e.setNouveauNombre(nouveauNombre);
        e.setNouveauPoids(nouveauPoids);
        e.setPar(par);
        e.setDate(LocalDateTime.now());
        e.setDescription(description);
        evenementRepo.save(e);
        logs.addLogs(par.getId(), s.getId(), "SessionPesee", description);
    }

    // Recalcule les totaux, incrémente la version, enregistre et renvoie le DTO complet.
    private SessionPeseeDTO enregistrer(Ctx ctx) {
        SessionPesee s = ctx.session();
        recalculer(s, ctx.pesees());
        incrementerVersion(s);
        Initialisation.updateDate(s.getInitialisation());
        sessionRepo.save(s);
        return toDto(s, ctx.pesees());
    }

    @Override
    @Transactional
    public SessionPeseeDTO creerWeb(SessionPeseeWebRequest req) {
        if (req == null) throw new IllegalArgumentException("Requête vide.");
        Utilisateurs user = utilisateurSaisie();
        Projets projet = projetDeLaFerme(req.getProjetUniqueId(), user.getFarm().getId());
        int parDefaut = req.getNombreParDefaut() != null ? req.getNombreParDefaut() : 1;
        if (parDefaut < 1) throw new IllegalArgumentException("Le nombre de sujets par défaut doit être au moins 1.");
        sessionRepo.lockProjet(projet.getId());

        SessionPesee s = new SessionPesee();
        s.setUniqueId(UUID.randomUUID().toString());
        s.setProjet(projet);
        s.setFarm(projet.getFarm());
        s.setCreePar(user);
        s.setStatut(StatutSessionPesee.EN_COURS);
        s.setDateDebut(LocalDateTime.now());
        s.setNombreParDefaut(parDefaut);
        s.setNombreTotalSujets(0);
        s.setPoidsTotalKg(0.0);
        s.setPoidsMoyenKg(0.0);
        s.setOrigine(OriginePesee.WEB);
        s.setVersion(1L);
        s.setInitialisation(Initialisation.init());
        s = sessionRepo.save(s);
        evenement(s, TypeEvenementPesee.CREATION_WEB, null, null, null, null, null, user,
                "Session ouverte depuis le web (" + sujets(parDefaut) + " par défaut) pour le projet "
                        + projet.getCode() + " par " + nom(user));
        return toDto(s, new ArrayList<>());
    }

    @Override
    @Transactional
    public SessionPeseeDTO ajouterWeb(String sessionUid, SessionPeseeWebRequest req) {
        if (req == null) throw new IllegalArgumentException("Requête vide.");
        Ctx ctx = ouvrirPourEcriture(sessionUid);
        exigerEnCours(ctx.session());
        double poids = validerPesee(req.getNombreSujets(), req.getPoidsKg());
        Pesee p = new Pesee();
        p.setUniqueId(UUID.randomUUID().toString());
        p.setSession(ctx.session());
        p.setNombreSujets(req.getNombreSujets());
        p.setPoidsKg(poids);
        p.setDateHeure(LocalDateTime.now());
        p.setAnnulee(false);
        p.setOrigine(OriginePesee.WEB);
        p.setModifiee(false);
        p.setCreePar(ctx.user());
        p.setInitialisation(Initialisation.init());
        p = peseeRepo.save(p);
        ctx.pesees().add(p);
        trier(ctx.pesees());
        evenement(ctx.session(), TypeEvenementPesee.AJOUT_WEB, p, null, null, p.getNombreSujets(), poids, ctx.user(),
                libellePesee(p) + " ajoutée : " + sujets(p.getNombreSujets()) + " " + kg(poids)
                        + " kg par " + nom(ctx.user()));
        return enregistrer(ctx);
    }

    @Override
    @Transactional
    public SessionPeseeDTO modifierWeb(String sessionUid, String peseeUid, SessionPeseeWebRequest req) {
        if (req == null) throw new IllegalArgumentException("Requête vide.");
        Ctx ctx = ouvrirPourEcriture(sessionUid);
        exigerEnCours(ctx.session());
        Pesee p = peseeDe(ctx, peseeUid);
        if (vrai(p.getAnnulee())) throw new IllegalArgumentException("Cette pesée est annulée : elle ne peut plus être modifiée.");
        double poids = validerPesee(req.getNombreSujets(), req.getPoidsKg());
        Integer ancienNombre = p.getNombreSujets();
        Double ancienPoids = p.getPoidsKg();
        if (Objects.equals(ancienNombre, req.getNombreSujets()) && Objects.equals(ancienPoids, poids)) {
            // Aucun changement : pas d'événement, pas de nouvelle version.
            return toDto(ctx.session(), ctx.pesees());
        }
        p.setNombreSujets(req.getNombreSujets());
        p.setPoidsKg(poids);
        p.setModifiee(true);
        Initialisation.updateDate(p.getInitialisation());
        peseeRepo.save(p);
        evenement(ctx.session(), TypeEvenementPesee.MODIFICATION_WEB, p, ancienNombre, ancienPoids, p.getNombreSujets(), poids,
                ctx.user(), libellePesee(p) + " modifiée : " + sujets(ancienNombre) + " " + kg(ancienPoids)
                        + " kg → " + sujets(p.getNombreSujets()) + " " + kg(poids) + " kg par " + nom(ctx.user()));
        return enregistrer(ctx);
    }

    @Override
    @Transactional
    public SessionPeseeDTO annulerWeb(String sessionUid, String peseeUid) {
        Ctx ctx = ouvrirPourEcriture(sessionUid);
        exigerEnCours(ctx.session());
        Pesee p = peseeDe(ctx, peseeUid);
        if (vrai(p.getAnnulee())) {
            // Déjà annulée (double clic, renvoi) : réponse inchangée.
            return toDto(ctx.session(), ctx.pesees());
        }
        p.setAnnulee(true);
        Initialisation.updateDate(p.getInitialisation());
        peseeRepo.save(p);
        evenement(ctx.session(), TypeEvenementPesee.ANNULATION_WEB, p, p.getNombreSujets(), p.getPoidsKg(), null, null,
                ctx.user(), libellePesee(p) + " annulée (" + sujets(p.getNombreSujets()) + " "
                        + kg(p.getPoidsKg()) + " kg) par " + nom(ctx.user()));
        return enregistrer(ctx);
    }

    @Override
    @Transactional
    public SessionPeseeDTO terminerWeb(String sessionUid, SessionPeseeWebRequest req) {
        Ctx ctx = ouvrirPourEcriture(sessionUid);
        SessionPesee s = ctx.session();
        exigerEnCours(s);
        if (ctx.pesees().stream().allMatch(p -> vrai(p.getAnnulee()))) {
            throw new IllegalArgumentException("Impossible de terminer une session sans aucune pesée.");
        }
        recalculer(s, ctx.pesees());
        LocalDateTime fin = parseDateHeure(req != null ? req.getDateFin() : null, "dateFin");
        if (fin == null) {
            // Maintenant, ou la dernière pesée si une horloge de téléphone en avance l'a
            // placée plus tard.
            fin = LocalDateTime.now();
            if (s.getDerniereDatePesee() != null && fin.isBefore(s.getDerniereDatePesee())) fin = s.getDerniereDatePesee();
        }
        s.setStatut(StatutSessionPesee.TERMINEE);
        s.setDateFin(dateFinValidee(s, fin));
        evenement(s, TypeEvenementPesee.TERMINAISON_WEB, null, null, null, null, null, ctx.user(),
                "Session terminée : " + sujets(s.getNombreTotalSujets()) + ", " + kg(s.getPoidsTotalKg())
                        + " kg, poids moyen " + kg(s.getPoidsMoyenKg()) + " kg par " + nom(ctx.user()));
        return enregistrer(ctx);
    }

    // Totaux toujours recalculés depuis les pesées non annulées.
    private void recalculer(SessionPesee s, List<Pesee> pesees) {
        int sujets = 0;
        double poids = 0.0;
        LocalDateTime derniere = null;
        for (Pesee p : pesees) {
            if (vrai(p.getAnnulee())) continue;
            sujets += p.getNombreSujets();
            poids += p.getPoidsKg();
            if (derniere == null || p.getDateHeure().isAfter(derniere)) derniere = p.getDateHeure();
        }
        double total = arrondi3(poids);
        s.setNombreTotalSujets(sujets);
        s.setPoidsTotalKg(total);
        s.setPoidsMoyenKg(sujets > 0 ? arrondi3(total / sujets) : 0.0);
        s.setDerniereDatePesee(derniere);
    }

    // ------------------------------------------------------------------ lecture

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<SessionPeseeDTO> list(String projetUniqueId, String statut, int page, int size) {
        Long farmId = farmIdOuErreur(getCurrentUserSafe());
        if (projetUniqueId == null || projetUniqueId.isBlank()) {
            throw new IllegalArgumentException("Le projet est obligatoire.");
        }
        StatutSessionPesee filtre = parseStatut(statut, null);
        Page<SessionPesee> res = sessionRepo.search(farmId, projetUniqueId.trim(), filtre != null,
                filtre != null ? filtre : StatutSessionPesee.EN_COURS,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));

        Map<Long, Integer> comptes = new HashMap<>();
        List<Long> ids = res.getContent().stream().map(SessionPesee::getId).toList();
        if (!ids.isEmpty()) {
            for (Object[] row : peseeRepo.countActivesBySessionIds(ids)) {
                comptes.put((Long) row[0], ((Number) row[1]).intValue());
            }
        }
        List<SessionPeseeDTO> data = res.getContent().stream().map(s -> {
            SessionPeseeDTO dto = base(s);
            dto.setNombrePesees(comptes.getOrDefault(s.getId(), 0));
            return dto;
        }).toList();
        return new PaginatedResponse<>(data, res.getNumber() + 1, res.getTotalPages(), res.getTotalElements(), res.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    public SessionPeseeDTO detail(String uniqueId) {
        Long farmId = farmIdOuErreur(getCurrentUserSafe());
        SessionPesee s = sessionRepo.findByUniqueId(uniqueId)
                .filter(x -> x.getFarm() != null && Objects.equals(x.getFarm().getId(), farmId))
                .filter(x -> !vrai(x.getInitialisation() != null ? x.getInitialisation().getRemoved() : null))
                .orElseThrow(() -> new IllegalArgumentException("Session de pesée introuvable : " + uniqueId));
        return toDto(s, peseeRepo.findBySessionIdOrdered(s.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvolutionPoidsDTO> evolution(String projetUniqueId) {
        Long farmId = farmIdOuErreur(getCurrentUserSafe());
        Projets projet = projetDeLaFerme(projetUniqueId, farmId);
        return sessionRepo.findTermineesByProjet(projet.getId(), farmId).stream()
                .map(s -> new EvolutionPoidsDTO(s.getUniqueId(), s.getDateFin(), s.getPoidsMoyenKg(), s.getNombreTotalSujets()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public com.diafarms.ml.DTO.DernierPoidsMoyenDTO dernierPoidsMoyen(String projetUniqueId) {
        Long farmId = farmIdOuErreur(getCurrentUserSafe());
        Projets projet = projetDeLaFerme(projetUniqueId, farmId);
        List<SessionPesee> derniere = sessionRepo.findDerniereTerminee(projet.getId(), farmId, PageRequest.of(0, 1));
        if (derniere.isEmpty()) return null;
        SessionPesee s = derniere.get(0);
        return com.diafarms.ml.DTO.DernierPoidsMoyenDTO.builder()
                .projetUniqueId(projet.getUniqueId())
                .poidsMoyenKg(s.getPoidsMoyenKg())
                .dateFin(s.getDateFin())
                .sessionUniqueId(s.getUniqueId())
                .nombreTotalSujets(s.getNombreTotalSujets())
                .build();
    }

    // ------------------------------------------------------------------ DTO

    private SessionPeseeDTO base(SessionPesee s) {
        return SessionPeseeDTO.builder()
                .uniqueId(s.getUniqueId())
                .projetUniqueId(s.getProjet() != null ? s.getProjet().getUniqueId() : null)
                .projetCode(s.getProjet() != null ? s.getProjet().getCode() : null)
                .statut(s.getStatut() != null ? s.getStatut().name() : null)
                .nombreParDefaut(s.getNombreParDefaut())
                .dateDebut(s.getDateDebut())
                .dateFin(s.getDateFin())
                .derniereDatePesee(s.getDerniereDatePesee())
                .nombreTotalSujets(s.getNombreTotalSujets())
                .poidsTotalKg(s.getPoidsTotalKg())
                .poidsMoyenKg(s.getPoidsMoyenKg())
                .creeParNom(nom(s.getCreePar()))
                .version(version(s))
                .origine(s.getOrigine() != null ? s.getOrigine().name() : OriginePesee.MOBILE.name())
                .pesees(new ArrayList<>())
                .build();
    }

    private SessionPeseeDTO toDto(SessionPesee s, List<Pesee> pesees) {
        SessionPeseeDTO dto = base(s);
        dto.setNombrePesees((int) pesees.stream().filter(p -> !vrai(p.getAnnulee())).count());
        dto.setPesees(pesees.stream().map(p -> PeseeDTO.builder()
                .uniqueId(p.getUniqueId())
                .nombreSujets(p.getNombreSujets())
                .poidsKg(p.getPoidsKg())
                .dateHeure(p.getDateHeure())
                .annulee(vrai(p.getAnnulee()))
                .creeParNom(nom(p.getCreePar()))
                .origine(p.getOrigine() != null ? p.getOrigine().name() : OriginePesee.MOBILE.name())
                .modifiee(vrai(p.getModifiee()))
                .build()).toList());
        dto.setEvenements(evenementRepo.findBySessionIdOrdered(s.getId()).stream().map(e -> SessionPeseeEvenementDTO.builder()
                .uniqueId(e.getUniqueId())
                .type(e.getType().name())
                .peseeUniqueId(e.getPeseeUniqueId())
                .ancienNombre(e.getAncienNombre())
                .ancienPoids(e.getAncienPoids())
                .nouveauNombre(e.getNouveauNombre())
                .nouveauPoids(e.getNouveauPoids())
                .description(e.getDescription())
                .parNom(nom(e.getPar()))
                .date(e.getDate())
                .build()).toList());
        return dto;
    }
}
