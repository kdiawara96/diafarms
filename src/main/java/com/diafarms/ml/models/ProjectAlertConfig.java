package com.diafarms.ml.models;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.AlertLevel;
import com.diafarms.ml.enums.AlertStatus;
import com.diafarms.ml.enums.AlertType;

@Entity
@Table(name = "project_alert_configs", indexes = {
    @Index(name = "idx_proj_project", columnList = "projet_id"), // Corrigé pour correspondre au nom de la colonne de jointure
    @Index(name = "idx_proj_type_seuil", columnList = "alert_type, threshold_key")
})
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ProjectAlertConfig { 

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //La combinaison nécessaire
    //Champ	        Rôle	                            Exemple
    //  ||           ||                                   ||
    //alertType	    La catégorie	                    MORTALITE
    //thresholdKey	Le seuil précis dans la catégorie	DAILY_WARNING
    //projectId	    Le projet concerné	                abc-123
    
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false)
    private AlertType alertType;     // MORTALITE, ALIMENTATION, VACCINATION, METEO

    @Column(name = "threshold_key", nullable = false)
    private String thresholdKey;     // "DAILY_WARNING", "TEMP_CRITICAL", "VACCIN_MANQUANT"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertLevel level;        // INFO, WARNING, CRITIQUE

    @Column(name = "numeric_value")
    private BigDecimal numericValue; // 0.05 (ex: 5% mortalité), 35 (°C pour météo)

    @Column(name = "string_value")
    private String stringValue;      // "Gumboro" (pour cibler un vaccin précis)

    @Column(name = "date_value")
    private LocalDate dateValue;     // Date limite programmée pour la vaccination

    @Column(name = "unit")
    private String unit;             // "%", "jours", "°C", "kg"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertStatus status = AlertStatus.ACTIF;

    @Embedded
    private Initialisation initialisation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;     

}