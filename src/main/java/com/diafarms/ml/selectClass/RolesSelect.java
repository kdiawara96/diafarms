package com.diafarms.ml.selectClass;

import com.diafarms.ml.models.Roles;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class RolesSelect {

    private long id;
    private String uniqueId;
    private String role;

    public static RolesSelect getLitRole(Roles data){
        RolesSelect newData = new RolesSelect(); 

       newData.setRole(data.getRole());
       newData.setId(data.getId());
       newData.setUniqueId(data.getUniqueId());

       return newData;
    }
}
