package com.diafarms.ml.commons;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Contenu attendu de super-admin-seed.json (racine du projet en local, monté dans le
// conteneur en prod comme .env — voir MlApplication.run()/loadSuperAdminSeed()) :
// exécuté une seule fois, à la toute première mise en route de la plateforme — les
// démarrages suivants trouvent déjà un SUPER_ADMIN en base (peu importe son username)
// et ne recréent jamais rien à partir de ce fichier.
@Getter
@Setter
@NoArgsConstructor
public class SuperAdminSeed {
    private String username;
    private String password;
    private String email;
    private String telephone;
    private String fullName;
}
