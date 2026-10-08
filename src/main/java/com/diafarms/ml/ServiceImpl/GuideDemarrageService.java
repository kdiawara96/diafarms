package com.diafarms.ml.ServiceImpl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.models.Utilisateurs;

import lombok.RequiredArgsConstructor;

// Guide « Bien démarrer » (carte du tableau de bord de l'ADMIN) : 6 étapes calculées sur
// les vraies données de la ferme, jamais cochées à la main.
//   1. Créer un site et un poulailler      (au moins un site ET un poulailler)
//   2. Créer un Projet                     (au moins un Projet, même clôturé)
//   3. Créer un magasin de stockage et un point de vente (un de chaque type)
//   4. Ajouter un membre de l'équipe       (au moins 2 comptes actifs dans la ferme)
//   5. Installer l'application mobile      (une connexion mobile enregistrée, voir
//      Farm.mobileConnecteLe, ou un QR déjà créé pour un compte de la ferme : les
//      téléphones connectés avant cette version n'ont jamais été enregistrés)
//   6. Faire une première saisie           (collecte, consommation d'aliment ou mortalité)
//
// Le même calcul sert à la console SUPER_ADMIN (« Essais inactifs ») et aux e-mails
// d'essai : UNE requête pour toutes les fermes (sous-requêtes EXISTS, aucune requête
// par ferme).
@Service
@RequiredArgsConstructor
public class GuideDemarrageService {

    public static final int TOTAL = 6;

    private final JdbcTemplate jdbc;
    private final OtherService otherService;

    public record Etapes(boolean siteEtPoulailler, boolean projet, boolean magasins, boolean equipe,
            boolean mobile, boolean saisie, LocalDateTime masqueLe) {
        public int faites() {
            int n = 0;
            for (boolean b : new boolean[] { siteEtPoulailler, projet, magasins, equipe, mobile, saisie }) if (b) n++;
            return n;
        }

        public boolean termine() {
            return faites() == TOTAL;
        }

        public static Etapes vide() {
            return new Etapes(false, false, false, false, false, false, null);
        }
    }

    public record EtapeDTO(String cle, String titre, String description, boolean fait, String lien, String libelleLien) {}

    public record GuideDTO(boolean afficher, boolean masque, boolean termine, int faites, int total, List<EtapeDTO> etapes) {}

    private static final String ACTIF = "COALESCE(%s.removed, false) = false";

    private static String actif(String alias) {
        return String.format(ACTIF, alias);
    }

    // farmId null : toutes les fermes ; sinon une seule.
    public Map<Long, Etapes> etapes(Long farmId) {
        String sql = "SELECT f.id, "
                + "EXISTS (SELECT 1 FROM sites s WHERE s.farm_id = f.id AND " + actif("s") + ") "
                + "  AND EXISTS (SELECT 1 FROM batiments b WHERE b.farm_id = f.id AND " + actif("b") + "), "
                + "EXISTS (SELECT 1 FROM projets p WHERE p.farm_id = f.id AND " + actif("p") + "), "
                + "EXISTS (SELECT 1 FROM magasins_vente m WHERE m.farm_id = f.id AND m.type = 'STOCKAGE' AND " + actif("m") + ") "
                + "  AND EXISTS (SELECT 1 FROM magasins_vente m WHERE m.farm_id = f.id AND m.type = 'VENTE' AND " + actif("m") + "), "
                + "(SELECT COUNT(*) FROM utilisateurs u WHERE u.farm_id = f.id AND " + actif("u")
                + "   AND COALESCE(u.archive, false) = false) >= 2, "
                + "f.mobile_connecte_le IS NOT NULL OR EXISTS (SELECT 1 FROM utilisateurs u WHERE u.farm_id = f.id "
                + "   AND u.qr_generated_at IS NOT NULL), "
                + "EXISTS (SELECT 1 FROM collectes_oeufs c JOIN projets p ON p.id = c.projet_id WHERE p.farm_id = f.id AND " + actif("c") + ") "
                + "  OR EXISTS (SELECT 1 FROM consommations_aliment c JOIN projets p ON p.id = c.projet_id WHERE p.farm_id = f.id AND " + actif("c") + ") "
                + "  OR EXISTS (SELECT 1 FROM mortalites c JOIN projets p ON p.id = c.projet_id WHERE p.farm_id = f.id AND " + actif("c") + "), "
                + "f.guide_demarrage_masque_le "
                + "FROM farms f" + (farmId != null ? " WHERE f.id = ?" : "");
        Map<Long, Etapes> res = new HashMap<>();
        Object[] args = farmId != null ? new Object[] { farmId } : new Object[0];
        jdbc.query(sql, rs -> {
            java.sql.Timestamp t = rs.getTimestamp(8);
            res.put(rs.getLong(1), new Etapes(rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5),
                    rs.getBoolean(6), rs.getBoolean(7), t != null ? t.toLocalDateTime() : null));
        }, args);
        return res;
    }

    public static List<EtapeDTO> liste(Etapes e) {
        List<EtapeDTO> liste = new ArrayList<>();
        liste.add(new EtapeDTO("SITE", "Créer un site et un poulailler",
                "Le site est l'endroit de votre ferme, le poulailler le bâtiment où vivent les sujets.",
                e.siteEtPoulailler(), "/sites", "Créer un site"));
        liste.add(new EtapeDTO("PROJET", "Créer un Projet",
                "Un Projet est une bande de sujets : date d'arrivée, nombre de sujets, poulailler.",
                e.projet(), "/projets", "Créer un Projet"));
        liste.add(new EtapeDTO("MAGASINS", "Créer un magasin de stockage et un point de vente",
                "Le magasin de stockage reçoit les œufs collectés, le point de vente sert à vendre.",
                e.magasins(), "/magasins", "Créer les magasins"));
        liste.add(new EtapeDTO("EQUIPE", "Ajouter un membre de l'équipe",
                "Donnez un compte à la personne qui fait les saisies chaque jour.",
                e.equipe(), "/utilisateurs", "Ajouter un membre"));
        liste.add(new EtapeDTO("MOBILE", "Installer l'application mobile",
                "Installez l'application sur le téléphone et connectez-vous avec le QR code.",
                e.mobile(), "/application-mobile", "Voir le QR code"));
        liste.add(new EtapeDTO("SAISIE", "Faire une première saisie",
                "Saisissez une collecte d'œufs, l'aliment donné ou une mortalité.",
                e.saisie(), "/production", "Faire une saisie"));
        return liste;
    }

    private static boolean estAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private Utilisateurs utilisateur() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    // Guide de la ferme de l'utilisateur. Seul l'ADMIN le voit (afficher=false sinon).
    @Transactional(readOnly = true)
    public GuideDTO guide() {
        Utilisateurs u = utilisateur();
        if (u == null || u.getFarm() == null || !estAdmin(u)) {
            return new GuideDTO(false, false, false, 0, TOTAL, List.of());
        }
        Etapes e = etapes(u.getFarm().getId()).getOrDefault(u.getFarm().getId(), Etapes.vide());
        List<EtapeDTO> liste = liste(e);
        boolean masque = e.masqueLe() != null;
        return new GuideDTO(!masque && !e.termine(), masque, e.termine(), e.faites(), TOTAL, liste);
    }

    // L'ADMIN ferme la carte : elle ne revient plus (par ferme).
    @Transactional
    public GuideDTO masquer() {
        Utilisateurs u = utilisateur();
        if (u == null || u.getFarm() == null || !estAdmin(u)) {
            throw new IllegalArgumentException("Seul le propriétaire de la ferme peut fermer ce guide.");
        }
        jdbc.update("UPDATE farms SET guide_demarrage_masque_le = now() WHERE id = ? AND guide_demarrage_masque_le IS NULL",
                u.getFarm().getId());
        return guide();
    }
}
