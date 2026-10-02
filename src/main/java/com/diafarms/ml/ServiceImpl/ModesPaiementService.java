package com.diafarms.ml.ServiceImpl;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.DeviseFermeDTO;
import com.diafarms.ml.DTO.ModePaiementFermeDTO;
import com.diafarms.ml.commons.CataloguePays;
import com.diafarms.ml.commons.Devise;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.ModePaiementFerme;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.FarmsRepo;
import com.diafarms.ml.repository.ModePaiementFermeRepo;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

// Pays, devise et modes de paiement d'une ferme (Paramètres), et résolution du mode
// envoyé avec un paiement. Règle de compatibilité : une valeur de l'enum ModePaiement
// historique (ESPECES, ORANGE_MONEY, ... envoyée par les téléphones APK 1.34/1.35) est
// TOUJOURS acceptée, même décochée par la ferme : une saisie de téléphone n'est jamais
// refusée pour ça. Les autres modes (standard non historiques ou ajoutés par la ferme)
// doivent être actifs pour la ferme et sont enregistrés en AUTRE + libellé.
@Service
@RequiredArgsConstructor
public class ModesPaiementService {

    private final ModePaiementFermeRepo repo;
    private final FarmsRepo farmsRepo;
    private final OtherService otherService;
    private final LogsServices logs;

    /** Mode à enregistrer : valeur de l'enum + libellé (null pour une valeur historique). */
    public record ModeChoisi(ModePaiement mode, String libelle) {}

    private static final int LIBELLE_MAX = 60;

    // ---------------------------------------------------------------- utilisateur

    private Utilisateurs user() {
        try { return otherService.getCurrentUser(); } catch (Exception e) { return null; }
    }

    private static boolean isAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private Farm fermeCourante() {
        Utilisateurs u = user();
        if (u == null || u.getFarm() == null) throw new IllegalArgumentException("Aucune ferme rattachée à ce compte.");
        return farmsRepo.findById(u.getFarm().getId())
                .orElseThrow(() -> new IllegalArgumentException("Aucune ferme rattachée à ce compte."));
    }

    private Utilisateurs exigerAdmin() {
        Utilisateurs u = user();
        if (!isAdmin(u)) throw new IllegalArgumentException("Seul le propriétaire de la ferme (administrateur) peut modifier ces paramètres.");
        return u;
    }

    // ---------------------------------------------------------------- pays et devise

    public static DeviseFermeDTO devise(Farm f) {
        CataloguePays.Pays p = CataloguePays.paysOuDefaut(f != null ? f.getPaysCode() : null);
        Devise.Info d = Devise.info(f != null ? f.getDevise() : null);
        return DeviseFermeDTO.builder()
                .pays(p.code()).paysNom(p.nom())
                .devise(d.code()).symbole(d.symbole()).nomDevise(d.nom()).decimales(d.decimales())
                .build();
    }

    @Transactional(readOnly = true)
    public DeviseFermeDTO deviseCourante() {
        Utilisateurs u = user();
        if (u == null || u.getFarm() == null) return devise(null);
        return devise(farmsRepo.findById(u.getFarm().getId()).orElse(null));
    }

    @Transactional
    public DeviseFermeDTO changerDevise(String paysBrut, String deviseBrute) {
        Utilisateurs u = exigerAdmin();
        Farm f = fermeCourante();
        if (paysBrut == null || !CataloguePays.paysExiste(paysBrut))
            throw new IllegalArgumentException("Pays inconnu : " + paysBrut);
        String pays = paysBrut.trim().toUpperCase();
        String code = deviseBrute == null || deviseBrute.isBlank() ? CataloguePays.paysOuDefaut(pays).devise() : deviseBrute;
        if (code == null || !Devise.existe(code)) throw new IllegalArgumentException("Devise inconnue : " + deviseBrute);
        code = code.trim().toUpperCase();
        String avant = Devise.info(f.getDevise()).code() + " / " + CataloguePays.paysOuDefaut(f.getPaysCode()).code();
        f.setPaysCode(pays);
        f.setDevise(code);
        farmsRepo.save(f);
        Devise.definir(code); // la suite de la requête utilise déjà la nouvelle devise
        logs.addLogs(u.getId(), f.getId(), "Farm", "Pays et devise : " + avant + " -> " + code + " / " + pays);
        return devise(f);
    }

    public static Map<String, Object> catalogue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pays", CataloguePays.pays().stream().map(p -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", p.code()); x.put("nom", p.nom()); x.put("devise", p.devise()); x.put("modes", p.modes());
            return x;
        }).toList());
        m.put("devises", Devise.catalogue().stream().map(d -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", d.code()); x.put("symbole", d.symbole()); x.put("nom", d.nom()); x.put("decimales", d.decimales());
            return x;
        }).toList());
        m.put("modes", CataloguePays.modesStandard().stream().map(s -> ModePaiementFermeDTO.builder()
                .code(s.code()).libelle(s.libelle()).actif(false).historique(s.historique()).personnalise(false).build()).toList());
        return m;
    }

    // ---------------------------------------------------------------- modes

    /** Configuration complète de la ferme (actifs et inactifs). Aucune ligne en base =
     * modes proposés pour le pays de la ferme, tous actifs. */
    public List<ModePaiementFermeDTO> configuration(Farm f) {
        List<ModePaiementFerme> lignes = f == null ? List.of() : repo.findByFarm_IdOrderByOrdreAscIdAsc(f.getId());
        if (lignes.isEmpty()) {
            CataloguePays.Pays p = CataloguePays.paysOuDefaut(f != null ? f.getPaysCode() : null);
            return p.modes().stream().map(c -> {
                CataloguePays.ModeStandard s = CataloguePays.modeStandard(c);
                return ModePaiementFermeDTO.builder().code(c).libelle(s.libelle()).actif(true)
                        .historique(s.historique()).personnalise(false).build();
            }).toList();
        }
        return lignes.stream().map(l -> ModePaiementFermeDTO.builder()
                .code(l.getCode()).libelle(l.getLibelle()).actif(Boolean.TRUE.equals(l.getActif()))
                .historique(CataloguePays.estHistorique(l.getCode()))
                .personnalise(CataloguePays.modeStandard(l.getCode()) == null).build()).toList();
    }

    public List<ModePaiementFermeDTO> actifs(Farm f) {
        return configuration(f).stream().filter(ModePaiementFermeDTO::isActif).toList();
    }

    @Transactional(readOnly = true)
    public List<ModePaiementFermeDTO> actifsCourants() {
        Utilisateurs u = user();
        if (u == null || u.getFarm() == null) return actifs(null);
        return actifs(farmsRepo.findById(u.getFarm().getId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<ModePaiementFermeDTO> configurationCourante() {
        return configuration(fermeCourante());
    }

    @Transactional
    public List<ModePaiementFermeDTO> configurer(List<ModePaiementFermeDTO> demandes) {
        Utilisateurs u = exigerAdmin();
        Farm f = fermeCourante();
        if (demandes == null || demandes.isEmpty()) throw new IllegalArgumentException("La liste des modes de paiement est vide.");
        List<ModePaiementFerme> nouveaux = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        Set<String> libelles = new HashSet<>();
        int ordre = 0;
        for (ModePaiementFermeDTO d : demandes) {
            if (d == null) continue;
            String code = d.getCode() == null ? "" : d.getCode().trim().toUpperCase();
            String libelle = d.getLibelle() == null ? "" : d.getLibelle().trim().replaceAll("\\s+", " ");
            CataloguePays.ModeStandard std = CataloguePays.modeStandard(code);
            if (std != null) {
                libelle = std.libelle(); // libellé d'un mode standard non modifiable
            } else {
                if (libelle.isEmpty()) throw new IllegalArgumentException("Donnez un nom au mode de paiement ajouté.");
                if (libelle.length() > LIBELLE_MAX)
                    throw new IllegalArgumentException("Nom de mode de paiement trop long (" + LIBELLE_MAX + " caractères au plus) : " + libelle);
                if (code.isEmpty() || !code.startsWith("PERSO_")) code = codePerso(libelle);
            }
            String base = code;
            for (int i = 2; codes.contains(code); i++) code = tronquer(base, 36) + "_" + i;
            if (!libelles.add(libelle.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Le mode de paiement « " + libelle + " » apparaît deux fois.");
            codes.add(code);
            ModePaiementFerme m = new ModePaiementFerme();
            m.setFarm(f);
            m.setCode(code);
            m.setLibelle(libelle);
            m.setActif(d.isActif());
            m.setOrdre(ordre++);
            nouveaux.add(m);
        }
        if (nouveaux.stream().noneMatch(m -> Boolean.TRUE.equals(m.getActif())))
            throw new IllegalArgumentException("Gardez au moins un mode de paiement coché.");
        repo.supprimerPourFerme(f.getId());
        repo.flush();
        repo.saveAll(nouveaux);
        logs.addLogs(u.getId(), f.getId(), "ModePaiementFerme", "Modes de paiement : " + String.join(", ",
                nouveaux.stream().filter(m -> Boolean.TRUE.equals(m.getActif())).map(ModePaiementFerme::getLibelle).toList()));
        return configuration(f);
    }

    private static String codePerso(String libelle) {
        String s = Normalizer.normalize(libelle, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "MODE";
        return "PERSO_" + tronquer(s, 28);
    }

    private static String tronquer(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** Mode envoyé avec un paiement (web ou téléphone) -> valeur à enregistrer.
     * Vide = ESPECES ; valeur historique de l'enum = acceptée telle quelle, même
     * décochée (téléphones APK 1.34/1.35) ; sinon code (ou libellé) d'un mode ACTIF de
     * la ferme -> AUTRE + libellé ; sinon refus. */
    public ModeChoisi resoudre(String brut, Farm ferme) {
        if (brut == null || brut.isBlank()) return new ModeChoisi(ModePaiement.ESPECES, null);
        String v = brut.trim();
        if (CataloguePays.estHistorique(v)) return new ModeChoisi(ModePaiement.valueOf(v.toUpperCase(Locale.ROOT)), null);
        Farm f = ferme;
        if (f != null && f.getId() != null) f = farmsRepo.findById(f.getId()).orElse(f);
        for (ModePaiementFermeDTO m : actifs(f)) {
            if (m.getCode().equalsIgnoreCase(v) || m.getLibelle().equalsIgnoreCase(v)) {
                if (m.isHistorique()) return new ModeChoisi(ModePaiement.valueOf(m.getCode()), null);
                return new ModeChoisi(ModePaiement.AUTRE, m.getLibelle());
            }
        }
        throw new IllegalArgumentException("Mode de paiement non disponible pour cette ferme : " + brut
                + ". Choisissez un mode coché dans Paramètres > Modes de paiement.");
    }

    /** Libellé affiché d'un paiement enregistré : libellé stocké (mode non historique)
     * sinon libellé standard de la valeur de l'enum. */
    public static String libelleAffiche(ModePaiement mode, String libelle) {
        if (libelle != null && !libelle.isBlank()) return libelle;
        return CataloguePays.libelle(mode);
    }
}
