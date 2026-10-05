package com.diafarms.ml.request.others;

import lombok.Data;

// Console SUPER_ADMIN : exclure (true) ou réintégrer (false) une ferme dans les statistiques.
@Data
public class AdminStatistiquesRequest {
    private Boolean exclure;
}
