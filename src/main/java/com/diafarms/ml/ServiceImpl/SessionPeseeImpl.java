package com.diafarms.ml.ServiceImpl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.EvolutionPoidsDTO;
import com.diafarms.ml.DTO.PeseeDTO;
import com.diafarms.ml.DTO.SessionPeseeDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.StatutSessionPesee;
import com.diafarms.ml.models.Pesee;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.SessionPesee;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.PeseeRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.SessionPeseeRepo;
import com.diafarms.ml.request.others.SessionPeseeSyncRequest;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.SessionPeseeService;

import lombok.RequiredArgsConstructor;

// Sessions de pesée saisies hors ligne sur le téléphone puis synchronisées.
// Principe : le téléphone renvoie toujours la session ENTIÈRE (identifiants UUID
// générés par lui) ; le serveur n'ajoute que les pesées nouvelles, n'accepte que
// l'annulation (définitive) des pesées existantes et recalcule seul les totaux.
@Service
@RequiredArgsConstructor
public class SessionPeseeImpl implements SessionPeseeService {

    static final String MSG_TERMINEE = "Cette session de pesée est terminée : elle ne peut plus être modifiée.";
    private static final Set<String> ROLES_SAISIE = Set.of("ADMIN", "SUPER_ADMIN", "RESPONSABLE", "PRODUCTION");

    private final SessionPeseeRepo sessionRepo;
    private final PeseeRepo peseeRepo;
    private final ProjetsRepo projetsRepo;
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
    public SessionPeseeDTO sync(SessionPeseeSyncRequest req) {
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
            // Renvoi identique (réponse perdue) : même ensemble de pesées, aucune nouvelle
            // annulation, statut TERMINEE → on renvoie la session telle quelle.
            boolean aucuneAnnulationNouvelle = recues.entrySet().stream()
                    .noneMatch(e -> vrai(e.getValue().getAnnulee()) && !vrai(parUid.get(e.getKey()).getAnnulee()));
            boolean identique = statutDemande == StatutSessionPesee.TERMINEE
                    && inconnues.isEmpty()
                    && recues.keySet().equals(parUid.keySet())
                    && aucuneAnnulationNouvelle;
            if (!identique) throw new IllegalArgumentException(MSG_TERMINEE);
            return toDto(session, existantes);
        }

        if (nouvelle) {
            session = new SessionPesee();
            session.setUniqueId(uid);
            session.setProjet(projet);
            session.setFarm(projet.getFarm());
            session.setCreePar(user);
            session.setStatut(StatutSessionPesee.EN_COURS);
            LocalDateTime debut = parseDateHeure(req.getDateDebut(), "dateDebut");
            session.setDateDebut(debut != null ? debut : LocalDateTime.now());
            session.setInitialisation(Initialisation.init());
        }
        if (req.getNombreParDefaut() != null) {
            if (req.getNombreParDefaut() < 1) {
                throw new IllegalArgumentException("Le nombre de sujets par défaut doit être au moins 1.");
            }
            session.setNombreParDefaut(req.getNombreParDefaut());
        } else if (session.getNombreParDefaut() == null) {
            session.setNombreParDefaut(1);
        }
        if (nouvelle) {
            session.setNombreTotalSujets(0);
            session.setPoidsTotalKg(0.0);
            session.setPoidsMoyenKg(0.0);
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
            if (item.getNombreSujets() == null || item.getNombreSujets() < 1) {
                throw new IllegalArgumentException("Le nombre de sujets d'une pesée doit être au moins 1.");
            }
            if (item.getPoidsKg() == null || item.getPoidsKg().isNaN() || item.getPoidsKg() <= 0) {
                throw new IllegalArgumentException("Le poids d'une pesée doit être supérieur à 0.");
            }
            LocalDateTime dateHeure = parseDateHeure(item.getDateHeure(), "dateHeure");
            if (dateHeure == null) {
                throw new IllegalArgumentException("La date et l'heure de la pesée sont obligatoires.");
            }
            Pesee p = new Pesee();
            p.setUniqueId(e.getKey());
            p.setSession(session);
            p.setNombreSujets(item.getNombreSujets());
            p.setPoidsKg(arrondi3(item.getPoidsKg()));
            p.setDateHeure(dateHeure);
            p.setAnnulee(vrai(item.getAnnulee()));
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
            session.setDateFin(fin != null ? fin : LocalDateTime.now());
            terminee = true;
        }
        if (!nouvelle) Initialisation.updateDate(session.getInitialisation());
        session = sessionRepo.save(session);

        if (nouvelle || ajoutees > 0 || annulees > 0 || terminee) {
            StringBuilder action = new StringBuilder(nouvelle ? "Ouverture" : "Synchronisation")
                    .append(" d'une session de pesée pour le projet '").append(projet.getTitre()).append("'");
            if (ajoutees > 0) action.append(" — ").append(ajoutees).append(" pesée(s) ajoutée(s)");
            if (annulees > 0) action.append(" — ").append(annulees).append(" pesée(s) annulée(s)");
            if (terminee) action.append(" — session terminée (").append(session.getNombreTotalSujets())
                    .append(" sujets, poids moyen ").append(session.getPoidsMoyenKg()).append(" kg)");
            logs.addLogs(user.getId(), session.getId(), "SessionPesee", action.toString());
        }

        existantes.sort((a, b) -> {
            int c = a.getDateHeure().compareTo(b.getDateHeure());
            return c != 0 ? c : Long.compare(a.getId(), b.getId());
        });
        return toDto(session, existantes);
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
                PageRequest.of(Math.max(page, 0), Math.max(size, 1)));

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
                .build()).toList());
        return dto;
    }
}
