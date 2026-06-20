package com.diafarms.ml.DTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.enums.AlertLevel;
import com.diafarms.ml.enums.AlertStatus;
import com.diafarms.ml.enums.AlertType;
import com.diafarms.ml.models.ProjectAlertConfig;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ProjectAlertConfigDTO {

    private Long id;
    private AlertType alertType;
    private String thresholdKey;
    private AlertLevel level; 
    private BigDecimal numericValue;
    private String stringValue;
    private LocalDate dateValue;
    private String unit;
    private AlertStatus status;
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;

    public static ProjectAlertConfigDTO fromEntity(ProjectAlertConfig data) {
        if (data == null) {
            return null;
        }

        return ProjectAlertConfigDTO.builder()
                .id(data.getId())
                .alertType(data.getAlertType())
                .thresholdKey(data.getThresholdKey().name())
                .level(data.getLevel())
                .numericValue(data.getNumericValue())
                .stringValue(data.getStringValue())
                .dateValue(data.getDateValue())
                .unit(data.getUnit())
                .status(data.getStatus())
                // .createdAt(data.getInitialisation().getCreatedAt())
                .createdAt(data.getInitialisation() != null ? data.getInitialisation().getCreatedAt() : null)
                .build();

    }
}
