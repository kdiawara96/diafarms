package com.diafarms.ml.ServiceImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

// Destinataires des e-mails automatiques de croissance (essai, parrainage, résumé de la
// semaine) : les ADMIN de la ferme (le propriétaire), comptes actifs (non supprimés, non
// archivés, non désactivés) qui ont un e-mail. Une requête pour toutes les fermes demandées.
@Component
@RequiredArgsConstructor
public class DestinatairesAdmin {

    public record Destinataire(String nom, String email) {}

    private final JdbcTemplate jdbc;

    public Map<Long, List<Destinataire>> parFerme(Collection<Long> farmIds) {
        Map<Long, List<Destinataire>> res = new HashMap<>();
        if (farmIds == null || farmIds.isEmpty()) return res;
        // Identifiants numériques (Long) : la liste est sûre à insérer telle quelle.
        String in = String.join(",", farmIds.stream().map(String::valueOf).toList());
        jdbc.query("SELECT DISTINCT u.farm_id, u.id, u.full_name, u.email FROM utilisateurs u "
                + "JOIN roles_users ru ON ru.id_utilisateurs = u.id JOIN roles r ON r.id = ru.id_roles "
                + "WHERE r.role = 'ADMIN' AND u.farm_id IN (" + in + ") AND u.email IS NOT NULL AND u.email <> '' "
                + "AND COALESCE(u.removed, false) = false AND COALESCE(u.archive, false) = false "
                + "AND COALESCE(u.statut, true) = true ORDER BY u.farm_id, u.id", rs -> {
                    res.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Destinataire(rs.getString(3), rs.getString(4)));
                });
        return res;
    }
}
