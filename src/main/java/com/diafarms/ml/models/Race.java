package com.diafarms.ml.models;

import java.time.LocalDate;
import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "races")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Race {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 20)
    private String uniqueId;

    @Column(name = "nom", nullable = false, length = 100)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TypeRace type;

    @Column(name = "origine", nullable = false, length = 100)
    private String origine;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "date_creation")
    private LocalDate dateCreation;

    @Column(name = "esperance_vie_annees")
    private Integer esperanceVieAnnees;

    @Column(name = "poids_adulte_kg")
    private Double poidsAdulteKg;

    @Column(name = "production_oeufs_an")
    private Integer productionOeufsAn;

    // Couleur des coquilles d'oeufs produits par cette race
    @Enumerated(EnumType.STRING)
    @Column(name = "couleur_oeuf", length = 20)
    private CouleurOeuf couleurOeuf;

    // Nombre de jours necessaires pour atteindre le poids d'abattage
    @Enumerated(EnumType.STRING)
    @Column(name = "temps_croissance", length = 20)
    private TempsCroissance tempsCroissance;

    // Poids optimal pour l'abattage (en kilogrammes)
    @Enumerated(EnumType.STRING)
    @Column(name = "poids_abattage", length = 20)
    private PoidsAbattage poidsAbattage;

    // Niveau de rusticite = capacite a resister aux maladies / climat
    @Enumerated(EnumType.STRING)
    @Column(name = "rusticite", length = 20)
    private Rusticite rusticite;

    // Types de climats ou la race s'adapte le mieux
    @Enumerated(EnumType.STRING)
    @Column(name = "adaptation_climat", length = 30)
    private AdaptationClimat adaptationClimat;

    @Enumerated(EnumType.STRING)
    @Column(name = "certification_race", length = 30)
    private CertificationRace certificationRace;

    // ============================================================
    // ENUMERATIONS
    // ============================================================

    public enum TypeRace {
        PONDEUSE("pondeuse"),
        CHAIR("chair"),
        MIXTE("mixte");

        private final String value;

        TypeRace(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // Couleur des coquilles d'oeufs
    public enum CouleurOeuf {
        BLANC("Blanc"),
        BRUN("Brun"),
        VERT("Vert"),
        BLEU("Bleu"),
        ROSE("Rose"),
        CREME("Creme"),
        CHOCOLAT("Chocolat"),
        GRIS("Gris"),
        TACHETE("Tachete"),
        NON_APPLICABLE("N/A");

        private final String value;

        CouleurOeuf(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // Temps de croissance jusqu'a l'abattage
    public enum TempsCroissance {
        TRES_RAPIDE("Tres rapide", "25-35 jours"),
        RAPIDE("Rapide", "35-45 jours"),
        MOYEN("Moyen", "45-60 jours"),
        LENT("Lent", "60-90 jours"),
        TRES_LENT("Tres lent", "90+ jours"),
        NON_APPLICABLE("N/A", "Race pondeuse");

        private final String label;
        private final String description;

        TempsCroissance(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    // Poids optimal d'abattage
    public enum PoidsAbattage {
        PETIT("Petit", "< 1.5 kg"),
        MOYEN("Moyen", "1.5 - 2.5 kg"),
        STANDARD("Standard", "2.5 - 3.5 kg"),
        GROS("Gros", "3.5 - 5.0 kg"),
        TRES_GROS("Tres gros", "> 5.0 kg"),
        NON_APPLICABLE("N/A", "Race pondeuse");

        private final String label;
        private final String description;

        PoidsAbattage(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    // Niveau de rusticite (resistance aux maladies / climat)
    public enum Rusticite {
        ELEVEE("Elevee", "Tres resistante, peu de soins necessaires"),
        MOYENNE("Moyenne", "Resistance moyenne, soins standards"),
        FAIBLE("Faible", "Fragile, soins intensifs necessaires"),
        TRES_FAIBLE("Tres faible", "Tres fragile, environnement controle obligatoire");

        private final String label;
        private final String description;

        Rusticite(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    // Climats adaptes
    public enum AdaptationClimat {
        CHAUD_SEC("Chaud et sec", "Zones arides et semi-arides"),
        CHAUD_HUMIDE("Chaud et humide", "Zones tropicales"),
        TEMPERE("Tempere", "Climat oceanique ou continental doux"),
        FROID("Froid", "Zones montagneuses ou nordiques"),
        TRES_FROID("Tres froid", "Climats polaires ou haute altitude"),
        UNIVERSEL("Universel", "S'adapte a tous les climats");

        private final String label;
        private final String description;

        AdaptationClimat(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    // Certifications et labels de qualite
    public enum CertificationRace {
        AUCUNE("Aucune", "Pas de certification specifique"),
        LABEL_ROUGE("Label Rouge", "Qualite superieure garantie"),
        IGP("IGP", "Indication Geographique Protegee"),
        AOP("AOP", "Appellation d'Origine Protegee"),
        BIO("Bio", "Agriculture biologique"),
        BIEN_ETRE_ANIMAL("Bien-etre animal", "Certification bien-etre"),
        HALAL("Halal", "Conforme aux preceptes halal"),
        CASHER("Casher", "Conforme aux preceptes casher");

        private final String label;
        private final String description;

        CertificationRace(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    // ============================================================
    // METHODES UTILITAIRES
    // ============================================================

    public boolean estPondeuse() {
        return this.type == TypeRace.PONDEUSE;
    }

    public boolean estChair() {
        return this.type == TypeRace.CHAIR;
    }

    public boolean estMixte() {
        return this.type == TypeRace.MIXTE;
    }

    public boolean estBonnePondeuse() {
        return this.productionOeufsAn != null && this.productionOeufsAn >= 250;
    }

    public boolean estRapideCroissance() {
        return this.tempsCroissance == TempsCroissance.TRES_RAPIDE 
            || this.tempsCroissance == TempsCroissance.RAPIDE;
    }

    public boolean estRustique() {
        return this.rusticite == Rusticite.ELEVEE || this.rusticite == Rusticite.MOYENNE;
    }
}
