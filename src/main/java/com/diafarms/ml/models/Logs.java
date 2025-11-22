package com.diafarms.ml.models;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonFormat.Shape;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "logs")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter

public class Logs {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true)
    private String uniqueId;
    
    @Lob
    @Column(name = "action", nullable = false)
    private String texteAction;
  
    @Column(name="id_action", nullable = false)
    private Long idAction;
    
    @Column(name="class_name", nullable = false)
    private String className;

    @Column(name="telephone_actionnaire", nullable = false)
    private String telephoneActionnaire;

    @Column(name = "vu")
    private Boolean vu = false;

    @Column(name = "deleted")
    private Boolean deleted = false;
    
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = Shape.STRING)
    @Column(name = "date_creation", length = 50)
    private LocalDateTime dateCreation = LocalDateTime.now();
}
