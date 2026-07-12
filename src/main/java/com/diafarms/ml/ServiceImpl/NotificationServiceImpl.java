package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.NotificationDTO;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.models.NotificationRead;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AlimentationRepo;
import com.diafarms.ml.repository.ConsommationAlimentRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.NotificationReadRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.TransactionRepo;
import com.diafarms.ml.services.NotificationService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final int RECENT_CONSO_WINDOW_DAYS = 7;
    private static final int STOCK_WARNING_DAYS_THRESHOLD = 3;
    private static final double MORTALITE_WARNING_PCT = 2.0;
    private static final double MORTALITE_CRITIQUE_PCT = 5.0;

    private final ProjetsRepo projetsRepo;
    private final AlimentationRepo alimentationRepo;
    private final ConsommationAlimentRepo consommationAlimentRepo;
    private final MortaliteRepo mortaliteRepo;
    private final TransactionRepo transactionRepo;
    private final NotificationReadRepo notificationReadRepo;
    private final OtherService otherService;

    @Override
    @Transactional
    public List<NotificationDTO> getActiveNotifications() {
        Utilisateurs currentUser = otherService.getCurrentUser();
        if (currentUser == null || currentUser.getFarm() == null) return List.of();
        Long farmId = currentUser.getFarm().getId();

        List<Projets> projets = projetsRepo.searchProjets(farmId, false, null, Pageable.unpaged()).getContent();
        List<NotificationDTO> result = new ArrayList<>();

        for (Projets p : projets) {
            addStockNotification(result, p);
            addMortaliteNotification(result, p);
        }

        long nbAttente = transactionRepo.countByFarmIdAndStatut(farmId, StatutTransaction.EN_ATTENTE);
        if (nbAttente > 0) {
            result.add(NotificationDTO.builder()
                .key("transactions-attente")
                .type("TRANSACTION")
                .level("WARNING")
                .message(nbAttente + " transaction(s) en attente de validation")
                .actionPath("/comptabilite")
                .build());
        }

        applyReadState(result, currentUser.getId());

        result.sort(Comparator.comparing((NotificationDTO n) -> "CRITIQUE".equals(n.getLevel()) ? 0 : 1)
            .thenComparing(NotificationDTO::getKey));

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationDTO> getActiveNotificationsForProjet(String projetUniqueId) {
        return projetsRepo.findByUniqueId(projetUniqueId)
            .map(p -> {
                List<NotificationDTO> result = new ArrayList<>();
                addStockNotification(result, p);
                addMortaliteNotification(result, p);
                result.sort(Comparator.comparing((NotificationDTO n) -> "CRITIQUE".equals(n.getLevel()) ? 0 : 1));
                return result;
            })
            .orElse(List.of());
    }

    private void addStockNotification(List<NotificationDTO> result, Projets p) {
        double achete = nz(alimentationRepo.sumAcheteByProjetId(p.getId()));
        if (achete <= 0) return; // pas encore d'achat : rien à signaler

        double consomme = nz(consommationAlimentRepo.sumConsommeByProjetId(p.getId()));
        double restant = achete - consomme;
        double recent = nz(consommationAlimentRepo.sumConsommeByProjetIdSince(p.getId(), LocalDate.now().minusDays(RECENT_CONSO_WINDOW_DAYS)));
        double dailyAvg = recent / RECENT_CONSO_WINDOW_DAYS;

        if (restant <= 0) {
            result.add(stockNotif(p, "CRITIQUE", "Stock d'aliment épuisé — " + p.getCode()));
        } else if (dailyAvg > 0 && restant / dailyAvg < STOCK_WARNING_DAYS_THRESHOLD) {
            long jours = Math.round(restant / dailyAvg);
            result.add(stockNotif(p, "WARNING", "Stock d'aliment faible — " + p.getCode() + " (~" + jours + " j restants)"));
        }
    }

    private void addMortaliteNotification(List<NotificationDTO> result, Projets p) {
        if (p.getNbSujets() == null || p.getNbSujets() <= 0) return;
        int morts = mortaliteRepo.sumMortsByProjetId(p.getId()) == null ? 0 : mortaliteRepo.sumMortsByProjetId(p.getId());
        double taux = (morts * 100.0) / p.getNbSujets();

        if (taux >= MORTALITE_CRITIQUE_PCT) {
            result.add(mortaliteNotif(p, "CRITIQUE", "Mortalité cumulée élevée — " + p.getCode() + " (" + round1(taux) + "%)"));
        } else if (taux >= MORTALITE_WARNING_PCT) {
            result.add(mortaliteNotif(p, "WARNING", "Mortalité cumulée à surveiller — " + p.getCode() + " (" + round1(taux) + "%)"));
        }
    }

    private NotificationDTO stockNotif(Projets p, String level, String message) {
        return NotificationDTO.builder()
            .key("stock-" + p.getUniqueId())
            .type("STOCK")
            .level(level)
            .message(message)
            .projetCode(p.getCode())
            .projetUniqueId(p.getUniqueId())
            .actionPath("/projets/" + p.getUniqueId())
            .build();
    }

    private NotificationDTO mortaliteNotif(Projets p, String level, String message) {
        return NotificationDTO.builder()
            .key("mortalite-" + p.getUniqueId())
            .type("MORTALITE")
            .level(level)
            .message(message)
            .projetCode(p.getCode())
            .projetUniqueId(p.getUniqueId())
            .actionPath("/projets/" + p.getUniqueId())
            .build();
    }

    private void applyReadState(List<NotificationDTO> result, Long userId) {
        List<String> activeKeys = result.stream().map(NotificationDTO::getKey).toList();
        notificationReadRepo.deleteStale(userId, activeKeys.isEmpty() ? List.of("__none__") : activeKeys);

        Set<String> readKeys = notificationReadRepo.findByUserId(userId).stream()
            .map(NotificationRead::getNotificationKey)
            .collect(Collectors.toSet());
        result.forEach(n -> n.setRead(readKeys.contains(n.getKey())));
    }

    @Override
    @Transactional
    public void markRead(String key) {
        Utilisateurs currentUser = otherService.getCurrentUser();
        if (currentUser == null) return;
        boolean exists = notificationReadRepo.findByUserId(currentUser.getId()).stream()
            .anyMatch(r -> r.getNotificationKey().equals(key));
        if (exists) return;

        NotificationRead read = new NotificationRead();
        read.setUser(currentUser);
        read.setNotificationKey(key);
        read.setReadAt(LocalDateTime.now());
        notificationReadRepo.save(read);
    }

    @Override
    @Transactional
    public void markAllRead() {
        List<NotificationDTO> active = getActiveNotifications();
        for (NotificationDTO n : active) {
            if (!n.isRead()) markRead(n.getKey());
        }
    }

    private double nz(Double v) { return v == null ? 0.0 : v; }
    private double round1(double v) { return Math.round(v * 10) / 10.0; }
}
