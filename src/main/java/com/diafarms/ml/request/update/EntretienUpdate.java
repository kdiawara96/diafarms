package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class EntretienUpdate {
    private String batimentUniqueId;
    private String date;
    private String heure;
    private String niveau;
    private String type;
    private String description;
    private String observations;
}
