# Abonnement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter un cycle d'abonnement géré manuellement (essai 14 jours, déclaration
de paiement par la ferme, validation par le SUPER_ADMIN, blocage web après expiration
+ grâce) à Diafarms.

**Architecture:** Deux nouvelles entités par ferme (`Abonnement` état courant,
`PaiementAbonnement` historique des déclarations, même patron que
`Salaire`/`PaiementSalaire`) plus une config plateforme singleton
(`AbonnementConfig`). Le statut effectif (ESSAI/ACTIF/EXPIRE, avec grâce) est
recalculé à la lecture, jamais par un job planifié. Le blocage n'existe que côté web
(un composant de garde appelle `GET /abonnements/moi` et bloque l'UI si expiré) — le
backend ne refuse aucune requête API pour ce motif, donc le mobile n'est jamais
affecté.

**Tech Stack:** Spring Boot / JPA / Postgres (backend, `diafarms_back`), React /
Vite / TypeScript (web, `Diafarms_web`). Aucun nouveau paquet à installer.

**Spec:** `docs/superpowers/specs/2026-08-25-abonnement-design.md`

## Global Constraints

- Pas de job planifié (`@Scheduled`) : le statut effectif est toujours recalculé à la
  demande à partir de `dateFin` + `dureeGraceHeures` (voir spec, section "Calcul du
  statut effectif").
- Le prix et les durées (essai, grâce) ne sont **jamais codés en dur** : ils vivent
  dans `AbonnementConfig`, modifiable par le SUPER_ADMIN.
- Aucune vérification de sécurité au niveau HTTP/route : comme le reste du backend
  (`SecurityConfiguration` a `.anyRequest().permitAll()`), le contrôle d'accès se
  fait **dans le service**, en vérifiant le rôle de `OtherService.getCurrentUser()` —
  suivre exactement le patron `isAdmin`/`ensureCanManage` de
  `MagasinServiceImpl`/`MagasinTransfertServiceImpl`.
- Ce projet n'a pas de suite de tests unitaires (aucun test JUnit dans tout le
  backend, aucun test Vitest/Jest côté web — vérifié en explorant le repo). La
  vérification de chaque tâche se fait donc par **compilation** (`./mvnw -q compile`
  côté backend, `npx tsc -p tsconfig.app.json --noEmit` côté web) et, pour les tâches
  qui touchent un comportement observable, par un **appel `curl` manuel** contre le
  backend déjà démarré en local — pas par des tests automatisés à écrire. Ne pas
  introduire de framework de test dans ce plan : ce serait s'écarter de la convention
  établie du projet.
- Nouvelles tables entièrement neuves (`abonnements`, `paiements_abonnement`,
  `abonnement_config`) : pas besoin du patron `columnDefinition` avec `DEFAULT`
  explicite utilisé ailleurs dans ce projet pour ajouter une colonne NOT NULL à une
  table déjà peuplée (ex: `Transaction.sourceType`) — ces tables n'existent pas
  encore, `ddl-auto=update` les crée directement avec les contraintes voulues.
- Toutes les nouvelles entités suivent le style déjà présent partout dans ce projet :
  `@Getter @Setter @NoArgsConstructor @AllArgsConstructor`, `uniqueId` en
  `UUID.randomUUID().toString()`, `@Embedded Initialisation initialisation`.
- Le mobile Android (`Diafarms`) n'est touché par aucune tâche de ce plan.

---

## Task 1: Enums + AbonnementConfig (entité, repo, DTO)

**Files:**
- Create: `src/main/java/com/diafarms/ml/enums/StatutAbonnement.java`
- Create: `src/main/java/com/diafarms/ml/enums/Periodicite.java`
- Create: `src/main/java/com/diafarms/ml/enums/StatutPaiementAbonnement.java`
- Create: `src/main/java/com/diafarms/ml/models/AbonnementConfig.java`
- Create: `src/main/java/com/diafarms/ml/repository/AbonnementConfigRepo.java`
- Create: `src/main/java/com/diafarms/ml/DTO/AbonnementConfigDTO.java`

**Interfaces:**
- Produces: `StatutAbonnement{ESSAI, ACTIF, EXPIRE}`, `Periodicite{MENSUEL, ANNUEL}`,
  `StatutPaiementAbonnement{EN_ATTENTE, VALIDE, REJETE}` — utilisés par toutes les
  tâches suivantes.
- Produces: `AbonnementConfig` avec getters `getPrixMensuel()`, `getPrixAnnuel()`,
  `getDureeEssaiJours()`, `getDureeGraceHeures()` (tous `Double`/`Integer`), et
  `AbonnementConfigRepo extends JpaRepository<AbonnementConfig, Long>` (pas de
  méthode custom — la ligne unique est trouvée via `findAll()` côté service, voir
  Task 6).
- Produces: `AbonnementConfigDTO` (record-like DTO avec les 4 mêmes champs +
  `AbonnementConfigDTO.fromEntity(AbonnementConfig)` statique).

- [ ] **Step 1: Créer les trois enums**

`src/main/java/com/diafarms/ml/enums/StatutAbonnement.java` :
```java
package com.diafarms.ml.enums;

// Statut stocké à titre de trace (mis à jour à chaque validation de paiement) — le
// statut qui compte réellement pour bloquer/débloquer l'accès web est toujours
// recalculé à la lecture à partir de Abonnement.dateFin + AbonnementConfig.
// dureeGraceHeures, jamais lu directement en base pour cette décision (voir
// AbonnementServiceImpl.calculerStatutEffectif).
public enum StatutAbonnement {
    ESSAI,
    ACTIF,
    EXPIRE
}
```

`src/main/java/com/diafarms/ml/enums/Periodicite.java` :
```java
package com.diafarms.ml.enums;

public enum Periodicite {
    MENSUEL,
    ANNUEL
}
```

`src/main/java/com/diafarms/ml/enums/StatutPaiementAbonnement.java` :
```java
package com.diafarms.ml.enums;

// Même vocabulaire que StatutTransaction, pour rester cohérent dans toute l'app.
public enum StatutPaiementAbonnement {
    EN_ATTENTE,
    VALIDE,
    REJETE
}
```

- [ ] **Step 2: Créer l'entité AbonnementConfig**

`src/main/java/com/diafarms/ml/models/AbonnementConfig.java` :
```java
package com.diafarms.ml.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Réglages tarifaires de la plateforme, une seule ligne (voir
// AbonnementServiceImpl.getOuCreerConfig, créée avec des valeurs par défaut au
// premier accès si absente) — modifiable par le SUPER_ADMIN, jamais codé en dur.
@Entity
@Table(name = "abonnement_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AbonnementConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prix_mensuel", nullable = false)
    private Double prixMensuel;

    @Column(name = "prix_annuel", nullable = false)
    private Double prixAnnuel;

    @Column(name = "duree_essai_jours", nullable = false)
    private Integer dureeEssaiJours;

    @Column(name = "duree_grace_heures", nullable = false)
    private Integer dureeGraceHeures;
}
```

- [ ] **Step 3: Créer le repository**

`src/main/java/com/diafarms/ml/repository/AbonnementConfigRepo.java` :
```java
package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.AbonnementConfig;

@Repository
public interface AbonnementConfigRepo extends JpaRepository<AbonnementConfig, Long> {
}
```

- [ ] **Step 4: Créer le DTO**

`src/main/java/com/diafarms/ml/DTO/AbonnementConfigDTO.java` :
```java
package com.diafarms.ml.DTO;

import com.diafarms.ml.models.AbonnementConfig;

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
public class AbonnementConfigDTO {
    private Double prixMensuel;
    private Double prixAnnuel;
    private Integer dureeEssaiJours;
    private Integer dureeGraceHeures;

    public static AbonnementConfigDTO fromEntity(AbonnementConfig c) {
        if (c == null) return null;
        return AbonnementConfigDTO.builder()
                .prixMensuel(c.getPrixMensuel())
                .prixAnnuel(c.getPrixAnnuel())
                .dureeEssaiJours(c.getDureeEssaiJours())
                .dureeGraceHeures(c.getDureeGraceHeures())
                .build();
    }
}
```

- [ ] **Step 5: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q compile`
Expected: aucune sortie (compilation propre).

- [ ] **Step 6: Commit**

```bash
cd /home/mother/Bureau/diafarms_back
git add src/main/java/com/diafarms/ml/enums/StatutAbonnement.java \
        src/main/java/com/diafarms/ml/enums/Periodicite.java \
        src/main/java/com/diafarms/ml/enums/StatutPaiementAbonnement.java \
        src/main/java/com/diafarms/ml/models/AbonnementConfig.java \
        src/main/java/com/diafarms/ml/repository/AbonnementConfigRepo.java \
        src/main/java/com/diafarms/ml/DTO/AbonnementConfigDTO.java
git commit -m "Ajoute les enums Abonnement et la config tarifaire plateforme

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 2: Entité Abonnement + repo

**Files:**
- Create: `src/main/java/com/diafarms/ml/models/Abonnement.java`
- Create: `src/main/java/com/diafarms/ml/repository/AbonnementRepo.java`

**Interfaces:**
- Consumes: `StatutAbonnement`, `Periodicite` (Task 1), `Farm` (existant,
  `models/Farm.java`).
- Produces: `Abonnement` avec getters `getUniqueId()`, `getFarm()`, `getStatut()`,
  `getDateDebut()`, `getDateFin()`, `getPeriodicite()` (nullable), et setters
  correspondants. `AbonnementRepo.findByFarm_Id(Long farmId)` retourne
  `Optional<Abonnement>` — utilisé par le service pour la création paresseuse (Task
  6).

- [ ] **Step 1: Créer l'entité**

`src/main/java/com/diafarms/ml/models/Abonnement.java` :
```java
package com.diafarms.ml.models;

import java.time.LocalDate;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Periodicite;
import com.diafarms.ml.enums.StatutAbonnement;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// État courant de l'abonnement d'UNE ferme (une seule ligne par Farm, voir
// AbonnementServiceImpl.creerEssaiPourFarm/getOuCreerAbonnement) — l'historique des
// paiements déclarés/validés vit dans PaiementAbonnement, un par déclaration. Le
// champ statut est mis à jour à chaque validation de paiement mais n'est jamais lu
// directement pour décider d'un blocage : voir
// AbonnementServiceImpl.calculerStatutEffectif, toujours recalculé à partir de
// dateFin + AbonnementConfig.dureeGraceHeures.
@Entity
@Table(name = "abonnements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Abonnement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false, unique = true)
    private Farm farm;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutAbonnement statut;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    @Column(name = "date_fin", nullable = false)
    private LocalDate dateFin;

    // Nullable tant qu'aucun paiement n'a jamais été validé (pendant l'essai
    // initial) — renseignée à la première validation, voir
    // AbonnementServiceImpl.valider.
    @Enumerated(EnumType.STRING)
    @Column(name = "periodicite", length = 20)
    private Periodicite periodicite;

    @Embedded
    private Initialisation initialisation;
}
```

- [ ] **Step 2: Créer le repository**

`src/main/java/com/diafarms/ml/repository/AbonnementRepo.java` :
```java
package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Abonnement;

@Repository
public interface AbonnementRepo extends JpaRepository<Abonnement, Long> {

    Optional<Abonnement> findByFarm_Id(Long farmId);

    Optional<Abonnement> findByUniqueId(String uniqueId);
}
```

- [ ] **Step 3: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q compile`
Expected: aucune sortie.

- [ ] **Step 4: Commit**

```bash
cd /home/mother/Bureau/diafarms_back
git add src/main/java/com/diafarms/ml/models/Abonnement.java \
        src/main/java/com/diafarms/ml/repository/AbonnementRepo.java
git commit -m "Ajoute l'entité Abonnement (état courant par ferme)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 3: Entité PaiementAbonnement + repo

**Files:**
- Create: `src/main/java/com/diafarms/ml/models/PaiementAbonnement.java`
- Create: `src/main/java/com/diafarms/ml/repository/PaiementAbonnementRepo.java`

**Interfaces:**
- Consumes: `Abonnement` (Task 2), `Periodicite`, `StatutPaiementAbonnement` (Task
  1), `Utilisateurs` (existant).
- Produces: `PaiementAbonnement` avec getters `getUniqueId()`, `getAbonnement()`,
  `getMontant()`, `getPeriodicite()`, `getMoyenPaiement()`, `getReference()`,
  `getStatut()`, `getDateDeclaration()`, `getDeclarePar()`, `getDateValidation()`,
  `getValidePar()`, `getMotifRejet()`. `PaiementAbonnementRepo.
  findByAbonnement_IdAndStatut(Long abonnementId, StatutPaiementAbonnement statut)`
  retourne `Optional<PaiementAbonnement>` (garde-fou "un seul en attente à la fois",
  Task 6). `findByStatutOrderByDateDeclarationDesc(StatutPaiementAbonnement statut,
  Pageable pageable)` retourne `Page<PaiementAbonnement>` (liste SUPER_ADMIN, Task
  6).

- [ ] **Step 1: Créer l'entité**

`src/main/java/com/diafarms/ml/models/PaiementAbonnement.java` :
```java
package com.diafarms.ml.models;

import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Periodicite;
import com.diafarms.ml.enums.StatutPaiementAbonnement;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une déclaration de paiement ("J'ai payé") pour un Abonnement — historique complet,
// plusieurs lignes par ferme au fil du temps (même patron que
// Salaire/PaiementSalaire : Abonnement = état courant, PaiementAbonnement =
// historique des mouvements). Voir AbonnementServiceImpl.declarerPaiement/valider/
// rejeter.
@Entity
@Table(name = "paiements_abonnement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaiementAbonnement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "abonnement_id", nullable = false)
    private Abonnement abonnement;

    @Column(name = "montant", nullable = false)
    private Double montant;

    // Périodicité que CE paiement couvre (peut différer de celle actuellement sur
    // Abonnement si la ferme change de formule au renouvellement).
    @Enumerated(EnumType.STRING)
    @Column(name = "periodicite", nullable = false, length = 20)
    private Periodicite periodicite;

    @Column(name = "moyen_paiement", nullable = false, length = 50)
    private String moyenPaiement;

    @Column(name = "reference", length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutPaiementAbonnement statut;

    @Column(name = "date_declaration", nullable = false)
    private LocalDateTime dateDeclaration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declare_par_id")
    private Utilisateurs declarePar;

    @Column(name = "date_validation")
    private LocalDateTime dateValidation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "valide_par_id")
    private Utilisateurs validePar;

    @Column(name = "motif_rejet", columnDefinition = "TEXT")
    private String motifRejet;

    @Embedded
    private Initialisation initialisation;
}
```

- [ ] **Step 2: Créer le repository**

`src/main/java/com/diafarms/ml/repository/PaiementAbonnementRepo.java` :
```java
package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.enums.StatutPaiementAbonnement;
import com.diafarms.ml.models.PaiementAbonnement;

@Repository
public interface PaiementAbonnementRepo extends JpaRepository<PaiementAbonnement, Long> {

    Optional<PaiementAbonnement> findByUniqueId(String uniqueId);

    // Garde-fou "une seule déclaration en attente à la fois" pour une ferme donnée
    // (via son Abonnement) — voir AbonnementServiceImpl.declarerPaiement.
    Optional<PaiementAbonnement> findByAbonnement_IdAndStatut(Long abonnementId, StatutPaiementAbonnement statut);

    // Liste SUPER_ADMIN de toutes les déclarations en attente, toutes fermes
    // confondues — voir AbonnementServiceImpl.listEnAttente.
    @Query("SELECT p FROM PaiementAbonnement p WHERE p.statut = :statut ORDER BY p.dateDeclaration ASC")
    Page<PaiementAbonnement> findByStatutOrderByDateDeclarationAsc(@Param("statut") StatutPaiementAbonnement statut, Pageable pageable);
}
```

- [ ] **Step 3: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q compile`
Expected: aucune sortie.

- [ ] **Step 4: Commit**

```bash
cd /home/mother/Bureau/diafarms_back
git add src/main/java/com/diafarms/ml/models/PaiementAbonnement.java \
        src/main/java/com/diafarms/ml/repository/PaiementAbonnementRepo.java
git commit -m "Ajoute l'entité PaiementAbonnement (historique des déclarations)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 4: DTOs Abonnement/PaiementAbonnement + requêtes

**Files:**
- Create: `src/main/java/com/diafarms/ml/DTO/AbonnementDTO.java`
- Create: `src/main/java/com/diafarms/ml/DTO/PaiementAbonnementDTO.java`
- Create: `src/main/java/com/diafarms/ml/request/others/DeclarerPaiementAbonnementRequest.java`
- Create: `src/main/java/com/diafarms/ml/request/others/RejeterPaiementAbonnementRequest.java`
- Create: `src/main/java/com/diafarms/ml/request/others/AbonnementConfigUpdateRequest.java`

**Interfaces:**
- Consumes: `Abonnement`, `PaiementAbonnement` (Tasks 2-3).
- Produces: `AbonnementDTO` (champs : `uniqueId`, `farmUniqueId`, `farmNom`,
  `statutEffectif` (String), `enGrace` (boolean), `dateFin` (LocalDate),
  `joursRestants` (long, peut être négatif), `periodicite` (String nullable),
  `paiementEnAttente` (`PaiementAbonnementDTO`, nullable)) et un constructeur
  statique `AbonnementDTO.of(Abonnement, String statutEffectif, boolean enGrace,
  long joursRestants, PaiementAbonnementDTO enAttente)` — PAS de `fromEntity` seul,
  car le statut effectif est calculé côté service (Task 6), pas dérivable de
  l'entité seule. `PaiementAbonnementDTO.fromEntity(PaiementAbonnement)` classique.
  `DeclarerPaiementAbonnementRequest{periodicite, moyenPaiement, reference}`,
  `RejeterPaiementAbonnementRequest{motif}`,
  `AbonnementConfigUpdateRequest{prixMensuel, prixAnnuel, dureeEssaiJours,
  dureeGraceHeures}` (tous nullable, mise à jour partielle comme le reste de l'app).

- [ ] **Step 1: Créer PaiementAbonnementDTO**

`src/main/java/com/diafarms/ml/DTO/PaiementAbonnementDTO.java` :
```java
package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.PaiementAbonnement;

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
public class PaiementAbonnementDTO {
    private String uniqueId;
    private String farmNom;
    private Double montant;
    private String periodicite;
    private String moyenPaiement;
    private String reference;
    private String statut;
    private LocalDateTime dateDeclaration;
    private String declareParNom;
    private LocalDateTime dateValidation;
    private String valideParNom;
    private String motifRejet;

    public static PaiementAbonnementDTO fromEntity(PaiementAbonnement p) {
        if (p == null) return null;
        return PaiementAbonnementDTO.builder()
                .uniqueId(p.getUniqueId())
                .farmNom(p.getAbonnement() != null && p.getAbonnement().getFarm() != null
                        ? p.getAbonnement().getFarm().getNom() : null)
                .montant(p.getMontant())
                .periodicite(p.getPeriodicite() != null ? p.getPeriodicite().name() : null)
                .moyenPaiement(p.getMoyenPaiement())
                .reference(p.getReference())
                .statut(p.getStatut() != null ? p.getStatut().name() : null)
                .dateDeclaration(p.getDateDeclaration())
                .declareParNom(p.getDeclarePar() != null ? p.getDeclarePar().getFullName() : null)
                .dateValidation(p.getDateValidation())
                .valideParNom(p.getValidePar() != null ? p.getValidePar().getFullName() : null)
                .motifRejet(p.getMotifRejet())
                .build();
    }
}
```

- [ ] **Step 2: Créer AbonnementDTO**

`src/main/java/com/diafarms/ml/DTO/AbonnementDTO.java` :
```java
package com.diafarms.ml.DTO;

import java.time.LocalDate;

import com.diafarms.ml.models.Abonnement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// statutEffectif/enGrace/joursRestants ne sont JAMAIS dérivés de Abonnement seul :
// ils sont calculés par AbonnementServiceImpl.calculerStatutEffectif (voir spec,
// section "Calcul du statut effectif") et passés explicitement ici.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AbonnementDTO {
    private String uniqueId;
    private String farmUniqueId;
    private String farmNom;
    private String statutEffectif;
    private boolean enGrace;
    private LocalDate dateFin;
    private long joursRestants;
    private String periodicite;
    private PaiementAbonnementDTO paiementEnAttente;

    public static AbonnementDTO of(Abonnement a, String statutEffectif, boolean enGrace,
            long joursRestants, PaiementAbonnementDTO paiementEnAttente) {
        return AbonnementDTO.builder()
                .uniqueId(a.getUniqueId())
                .farmUniqueId(a.getFarm() != null ? a.getFarm().getUniqueId() : null)
                .farmNom(a.getFarm() != null ? a.getFarm().getNom() : null)
                .statutEffectif(statutEffectif)
                .enGrace(enGrace)
                .dateFin(a.getDateFin())
                .joursRestants(joursRestants)
                .periodicite(a.getPeriodicite() != null ? a.getPeriodicite().name() : null)
                .paiementEnAttente(paiementEnAttente)
                .build();
    }
}
```

- [ ] **Step 3: Créer les requêtes**

`src/main/java/com/diafarms/ml/request/others/DeclarerPaiementAbonnementRequest.java` :
```java
package com.diafarms.ml.request.others;

import lombok.Data;

@Data
public class DeclarerPaiementAbonnementRequest {
    private String periodicite; // "MENSUEL" ou "ANNUEL"
    private String moyenPaiement; // "Orange Money", "Wave", "Virement", "Espèces"...
    private String reference; // optionnel
}
```

`src/main/java/com/diafarms/ml/request/others/RejeterPaiementAbonnementRequest.java` :
```java
package com.diafarms.ml.request.others;

import lombok.Data;

@Data
public class RejeterPaiementAbonnementRequest {
    private String motif;
}
```

`src/main/java/com/diafarms/ml/request/others/AbonnementConfigUpdateRequest.java` :
```java
package com.diafarms.ml.request.others;

import lombok.Data;

// Tous les champs optionnels — mise à jour partielle (seuls les champs non-null sont
// appliqués), même convention que MagasinCreate/Update dans ce projet.
@Data
public class AbonnementConfigUpdateRequest {
    private Double prixMensuel;
    private Double prixAnnuel;
    private Integer dureeEssaiJours;
    private Integer dureeGraceHeures;
}
```

- [ ] **Step 4: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q compile`
Expected: aucune sortie.

- [ ] **Step 5: Commit**

```bash
cd /home/mother/Bureau/diafarms_back
git add src/main/java/com/diafarms/ml/DTO/AbonnementDTO.java \
        src/main/java/com/diafarms/ml/DTO/PaiementAbonnementDTO.java \
        src/main/java/com/diafarms/ml/request/others/DeclarerPaiementAbonnementRequest.java \
        src/main/java/com/diafarms/ml/request/others/RejeterPaiementAbonnementRequest.java \
        src/main/java/com/diafarms/ml/request/others/AbonnementConfigUpdateRequest.java
git commit -m "Ajoute les DTO et requêtes Abonnement

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 5: Requête SUPER_ADMIN + emails de notification

**Files:**
- Modify: `src/main/java/com/diafarms/ml/repository/UtilisateursRepo.java`
- Modify: `src/main/java/com/diafarms/ml/services/EmailService.java`
- Modify: `src/main/java/com/diafarms/ml/ServiceImpl/EmailServiceImpl.java`

**Interfaces:**
- Produces: `UtilisateursRepo.findAllSuperAdmins()` → `List<Utilisateurs>`.
- Produces: `EmailService.sendAbonnementAValider(String to, String farmNom, Double
  montant, String periodicite, String moyenPaiement, String reference)` → `boolean`,
  `EmailService.sendAbonnementValide(String to, String fullName, String farmNom,
  LocalDate dateFin)` → `boolean` — utilisés par `AbonnementServiceImpl` (Task 6).

- [ ] **Step 1: Ajouter la requête SUPER_ADMIN**

Dans `src/main/java/com/diafarms/ml/repository/UtilisateursRepo.java`, juste après
la méthode `existsSuperAdmin()` déjà présente (ajoutée précédemment), ajouter :
```java
    // Destinataires de l'email "abonnement à valider" (voir
    // AbonnementServiceImpl.declarerPaiement) — potentiellement plusieurs comptes
    // SUPER_ADMIN sur la plateforme.
    @Query("SELECT u FROM Utilisateurs u JOIN u.roles r WHERE r.role = 'SUPER_ADMIN'")
    List<Utilisateurs> findAllSuperAdmins();
```
Vérifier que `List` est déjà importé en tête de fichier (c'est le cas, voir
`import java.util.List;`).

- [ ] **Step 2: Étendre l'interface EmailService**

Dans `src/main/java/com/diafarms/ml/services/EmailService.java`, ajouter les deux
signatures à côté des trois existantes (`sendWelcomeEmail`, etc.) :
```java
    boolean sendAbonnementAValider(String to, String farmNom, Double montant,
            String periodicite, String moyenPaiement, String reference);

    boolean sendAbonnementValide(String to, String fullName, String farmNom,
            java.time.LocalDate dateFin);
```

- [ ] **Step 3: Implémenter dans EmailServiceImpl**

Dans `src/main/java/com/diafarms/ml/ServiceImpl/EmailServiceImpl.java`, ajouter en
haut du fichier `import java.time.LocalDate;` puis, après la méthode
`sendPasswordResetByAdmin` existante (avant les méthodes privées `build...Body`),
ajouter :
```java
    @Override
    public boolean sendAbonnementAValider(String to, String farmNom, Double montant,
            String periodicite, String moyenPaiement, String reference) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, "DiaFarms");
            helper.setTo(to);
            helper.setReplyTo(fromAddress);
            helper.setSubject("Abonnement à valider — " + farmNom);
            helper.setText(
                    buildAbonnementAValiderPlainTextBody(farmNom, montant, periodicite, moyenPaiement, reference),
                    buildAbonnementAValiderHtmlBody(farmNom, montant, periodicite, moyenPaiement, reference));
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Échec de l'envoi de l'email 'abonnement à valider' à {} : {}", to, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendAbonnementValide(String to, String fullName, String farmNom, LocalDate dateFin) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, "DiaFarms");
            helper.setTo(to);
            helper.setReplyTo(fromAddress);
            helper.setSubject("Votre abonnement DiaFarms est activé");
            helper.setText(
                    buildAbonnementValidePlainTextBody(fullName, farmNom, dateFin),
                    buildAbonnementValideHtmlBody(fullName, farmNom, dateFin));
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Échec de l'envoi de l'email 'abonnement validé' à {} : {}", to, e.getMessage());
            return false;
        }
    }

    private String buildAbonnementAValiderPlainTextBody(String farmNom, Double montant,
            String periodicite, String moyenPaiement, String reference) {
        return """
            Bonjour,

            La ferme %s a déclaré avoir payé son abonnement DiaFarms.

            Montant : %.0f FCFA
            Périodicité : %s
            Moyen de paiement : %s
            Référence : %s

            Connecte-toi à ton portail SUPER_ADMIN pour vérifier le paiement et valider.

            L'équipe DiaFarms
            """.formatted(farmNom, montant, periodicite, moyenPaiement,
                    (reference == null || reference.isBlank()) ? "—" : reference);
    }

    private String buildAbonnementAValiderHtmlBody(String farmNom, Double montant,
            String periodicite, String moyenPaiement, String reference) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #1f2937;">
              <h2 style="color: #15803d;">Abonnement à valider</h2>
              <p>La ferme <strong>%s</strong> a déclaré avoir payé son abonnement DiaFarms.</p>
              <div style="background: #f3f4f6; border-radius: 8px; padding: 16px; margin: 16px 0;">
                <p style="margin: 4px 0;"><strong>Montant :</strong> %.0f FCFA</p>
                <p style="margin: 4px 0;"><strong>Périodicité :</strong> %s</p>
                <p style="margin: 4px 0;"><strong>Moyen de paiement :</strong> %s</p>
                <p style="margin: 4px 0;"><strong>Référence :</strong> %s</p>
              </div>
              <p>Connecte-toi à ton portail SUPER_ADMIN pour vérifier le paiement et valider.</p>
              <p>L'équipe DiaFarms</p>
            </div>
            """.formatted(farmNom, montant, periodicite, moyenPaiement,
                    (reference == null || reference.isBlank()) ? "—" : reference);
    }

    private String buildAbonnementValidePlainTextBody(String fullName, String farmNom, LocalDate dateFin) {
        return """
            Bonjour %s,

            Le paiement de l'abonnement DiaFarms de %s a été validé.

            Votre abonnement est actif jusqu'au %s.

            Merci de votre confiance.

            L'équipe DiaFarms
            """.formatted(fullName, farmNom, dateFin);
    }

    private String buildAbonnementValideHtmlBody(String fullName, String farmNom, LocalDate dateFin) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #1f2937;">
              <h2 style="color: #15803d;">Abonnement activé</h2>
              <p>Bonjour %s,</p>
              <p>Le paiement de l'abonnement DiaFarms de <strong>%s</strong> a été validé.</p>
              <div style="background: #f3f4f6; border-radius: 8px; padding: 16px; margin: 16px 0;">
                <p style="margin: 4px 0;">Abonnement actif jusqu'au <strong>%s</strong>.</p>
              </div>
              <p>Merci de votre confiance.</p>
              <p>L'équipe DiaFarms</p>
            </div>
            """.formatted(fullName, farmNom, dateFin);
    }
```

- [ ] **Step 4: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q compile`
Expected: aucune sortie.

- [ ] **Step 5: Commit**

```bash
cd /home/mother/Bureau/diafarms_back
git add src/main/java/com/diafarms/ml/repository/UtilisateursRepo.java \
        src/main/java/com/diafarms/ml/services/EmailService.java \
        src/main/java/com/diafarms/ml/ServiceImpl/EmailServiceImpl.java
git commit -m "Ajoute les emails de notification d'abonnement

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 6: AbonnementService (logique métier)

**Files:**
- Create: `src/main/java/com/diafarms/ml/services/AbonnementService.java`
- Create: `src/main/java/com/diafarms/ml/ServiceImpl/AbonnementServiceImpl.java`

**Interfaces:**
- Consumes: `AbonnementRepo`, `PaiementAbonnementRepo`, `AbonnementConfigRepo`,
  `FarmsRepo`, `UtilisateursRepo.findAllSuperAdmins()`, `OtherService.
  getCurrentUser()`, `EmailService.sendAbonnementAValider/sendAbonnementValide`
  (Tasks 1-5).
- Produces (signatures exactes utilisées par le controller, Task 8) :
  ```java
  public interface AbonnementService {
      void creerEssaiPourFarm(Farm farm);
      AbonnementDTO getMoi();
      PaiementAbonnementDTO declarerPaiement(DeclarerPaiementAbonnementRequest request);
      PaginatedResponse<PaiementAbonnementDTO> listEnAttente(int page, int size);
      PaiementAbonnementDTO valider(String paiementUniqueId);
      PaiementAbonnementDTO rejeter(String paiementUniqueId, RejeterPaiementAbonnementRequest request);
      AbonnementConfigDTO getConfig();
      AbonnementConfigDTO updateConfig(AbonnementConfigUpdateRequest request);
  }
  ```

- [ ] **Step 1: Créer l'interface**

`src/main/java/com/diafarms/ml/services/AbonnementService.java` :
```java
package com.diafarms.ml.services;

import com.diafarms.ml.DTO.AbonnementConfigDTO;
import com.diafarms.ml.DTO.AbonnementDTO;
import com.diafarms.ml.DTO.PaiementAbonnementDTO;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.others.AbonnementConfigUpdateRequest;
import com.diafarms.ml.request.others.DeclarerPaiementAbonnementRequest;
import com.diafarms.ml.request.others.RejeterPaiementAbonnementRequest;

public interface AbonnementService {

    // Appelé une seule fois, juste après la création d'une nouvelle Farm (voir
    // UtilisateurImpl.save, Task 7) — crée l'essai initial (ESSAI, dateDebut =
    // aujourd'hui, dateFin = aujourd'hui + config.dureeEssaiJours).
    void creerEssaiPourFarm(Farm farm);

    // Ferme de l'utilisateur courant (OtherService.getCurrentUser()). Si la ferme
    // n'a pas encore d'Abonnement (fermes créées avant ce déploiement), en crée un
    // à la volée avec un essai complet à partir d'aujourd'hui (jamais rétroactif).
    // Retourne null si l'utilisateur courant n'a pas de ferme (SUPER_ADMIN).
    AbonnementDTO getMoi();

    // ADMIN/RESPONSABLE de la ferme courante uniquement. Refuse si une déclaration
    // est déjà EN_ATTENTE pour cette ferme.
    PaiementAbonnementDTO declarerPaiement(DeclarerPaiementAbonnementRequest request);

    // SUPER_ADMIN uniquement.
    PaginatedResponse<PaiementAbonnementDTO> listEnAttente(int page, int size);

    // SUPER_ADMIN uniquement. Étend Abonnement.dateFin, met periodicite/statut à
    // jour, envoie l'email de confirmation à declarePar.
    PaiementAbonnementDTO valider(String paiementUniqueId);

    // SUPER_ADMIN uniquement. Ne touche pas à Abonnement.dateFin.
    PaiementAbonnementDTO rejeter(String paiementUniqueId, RejeterPaiementAbonnementRequest request);

    // Lecture publique (tout utilisateur connecté) — pour afficher les prix courants
    // sur le bouton "J'ai payé".
    AbonnementConfigDTO getConfig();

    // SUPER_ADMIN uniquement. Mise à jour partielle (champs non-null seulement).
    AbonnementConfigDTO updateConfig(AbonnementConfigUpdateRequest request);
}
```

- [ ] **Step 2: Créer l'implémentation**

`src/main/java/com/diafarms/ml/ServiceImpl/AbonnementServiceImpl.java` :
```java
package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.AbonnementConfigDTO;
import com.diafarms.ml.DTO.AbonnementDTO;
import com.diafarms.ml.DTO.PaiementAbonnementDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Periodicite;
import com.diafarms.ml.enums.StatutAbonnement;
import com.diafarms.ml.enums.StatutPaiementAbonnement;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.PaiementAbonnement;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.AbonnementRepo;
import com.diafarms.ml.repository.PaiementAbonnementRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.request.others.AbonnementConfigUpdateRequest;
import com.diafarms.ml.request.others.DeclarerPaiementAbonnementRequest;
import com.diafarms.ml.request.others.RejeterPaiementAbonnementRequest;
import com.diafarms.ml.services.AbonnementService;
import com.diafarms.ml.services.EmailService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AbonnementServiceImpl implements AbonnementService {

    private final AbonnementRepo abonnementRepo;
    private final PaiementAbonnementRepo paiementAbonnementRepo;
    private final AbonnementConfigRepo configRepo;
    private final UtilisateursRepo utilisateursRepo;
    private final EmailService emailService;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSuperAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private boolean isAdminOuResponsable(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "RESPONSABLE".equalsIgnoreCase(r.getRole()));
    }

    private void ensureSuperAdmin(Utilisateurs u) {
        if (!isSuperAdmin(u)) {
            throw new IllegalArgumentException("Seul un SUPER_ADMIN peut effectuer cette action.");
        }
    }

    // Ligne unique de config, créée avec des valeurs par défaut si absente — voir
    // AbonnementConfig.
    private AbonnementConfig getOuCreerConfig() {
        return configRepo.findAll().stream().findFirst().orElseGet(() -> {
            AbonnementConfig config = new AbonnementConfig();
            config.setPrixMensuel(15000.0);
            config.setPrixAnnuel(150000.0);
            config.setDureeEssaiJours(14);
            config.setDureeGraceHeures(24);
            return configRepo.save(config);
        });
    }

    @Override
    @Transactional
    public void creerEssaiPourFarm(Farm farm) {
        AbonnementConfig config = getOuCreerConfig();
        Abonnement abonnement = new Abonnement();
        abonnement.setUniqueId(UUID.randomUUID().toString());
        abonnement.setFarm(farm);
        abonnement.setStatut(StatutAbonnement.ESSAI);
        LocalDate aujourdHui = LocalDate.now();
        abonnement.setDateDebut(aujourdHui);
        abonnement.setDateFin(aujourdHui.plusDays(config.getDureeEssaiJours()));
        abonnement.setInitialisation(Initialisation.init());
        abonnementRepo.save(abonnement);
    }

    // Création paresseuse pour les fermes créées avant ce déploiement (voir spec,
    // section "Erreurs et cas limites") — essai complet à partir d'AUJOURD'HUI,
    // jamais rétroactif à la vraie date d'inscription de la ferme.
    private Abonnement getOuCreerAbonnement(Farm farm) {
        return abonnementRepo.findByFarm_Id(farm.getId()).orElseGet(() -> {
            creerEssaiPourFarm(farm);
            return abonnementRepo.findByFarm_Id(farm.getId())
                    .orElseThrow(() -> new IllegalStateException("Échec de création de l'abonnement."));
        });
    }

    // Voir spec, section "Calcul du statut effectif" : dateFin est un LocalDate (la
    // ferme reste active toute la journée indiquée), l'instant de coupure réel est
    // dateFin+1 jour à minuit, plus la grâce en heures.
    private record StatutCalcule(String statut, boolean enGrace, long joursRestants) {}

    private StatutCalcule calculerStatutEffectif(Abonnement abonnement, AbonnementConfig config) {
        LocalDateTime maintenant = LocalDateTime.now();
        LocalDateTime finJournee = abonnement.getDateFin().plusDays(1).atStartOfDay();
        LocalDateTime instantLimite = finJournee.plusHours(config.getDureeGraceHeures());
        long joursRestants = ChronoUnit.DAYS.between(LocalDate.now(), abonnement.getDateFin());

        boolean estEssai = abonnement.getPeriodicite() == null;
        if (maintenant.isBefore(finJournee)) {
            return new StatutCalcule(estEssai ? "ESSAI" : "ACTIF", false, joursRestants);
        }
        if (maintenant.isBefore(instantLimite)) {
            return new StatutCalcule(estEssai ? "ESSAI" : "ACTIF", true, joursRestants);
        }
        return new StatutCalcule("EXPIRE", false, joursRestants);
    }

    @Override
    @Transactional
    public AbonnementDTO getMoi() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return null; // SUPER_ADMIN, ou utilisateur non authentifié.
        }
        Farm farm = currentUser.getFarm();
        Abonnement abonnement = getOuCreerAbonnement(farm);
        AbonnementConfig config = getOuCreerConfig();

        StatutCalcule effectif = calculerStatutEffectif(abonnement, config);

        PaiementAbonnement enAttente = paiementAbonnementRepo
                .findByAbonnement_IdAndStatut(abonnement.getId(), StatutPaiementAbonnement.EN_ATTENTE)
                .orElse(null);

        return AbonnementDTO.of(abonnement, effectif.statut(), effectif.enGrace(), effectif.joursRestants(),
                PaiementAbonnementDTO.fromEntity(enAttente));
    }

    @Override
    @Transactional
    public PaiementAbonnementDTO declarerPaiement(DeclarerPaiementAbonnementRequest request) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (!isAdminOuResponsable(currentUser)) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut déclarer un paiement.");
        }
        if (request.getPeriodicite() == null || request.getMoyenPaiement() == null || request.getMoyenPaiement().isBlank()) {
            throw new IllegalArgumentException("Périodicité et moyen de paiement sont obligatoires.");
        }
        Periodicite periodicite;
        try {
            periodicite = Periodicite.valueOf(request.getPeriodicite().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Périodicité invalide (attendu MENSUEL ou ANNUEL) : " + request.getPeriodicite());
        }

        Abonnement abonnement = getOuCreerAbonnement(currentUser.getFarm());

        if (paiementAbonnementRepo.findByAbonnement_IdAndStatut(abonnement.getId(), StatutPaiementAbonnement.EN_ATTENTE).isPresent()) {
            throw new IllegalArgumentException("Une déclaration de paiement est déjà en attente de validation.");
        }

        AbonnementConfig config = getOuCreerConfig();
        double montant = periodicite == Periodicite.ANNUEL ? config.getPrixAnnuel() : config.getPrixMensuel();

        PaiementAbonnement paiement = new PaiementAbonnement();
        paiement.setUniqueId(UUID.randomUUID().toString());
        paiement.setAbonnement(abonnement);
        paiement.setMontant(montant);
        paiement.setPeriodicite(periodicite);
        paiement.setMoyenPaiement(request.getMoyenPaiement());
        paiement.setReference(request.getReference());
        paiement.setStatut(StatutPaiementAbonnement.EN_ATTENTE);
        paiement.setDateDeclaration(LocalDateTime.now());
        paiement.setDeclarePar(currentUser);
        paiement.setInitialisation(Initialisation.init());
        PaiementAbonnement saved = paiementAbonnementRepo.save(paiement);

        String farmNom = currentUser.getFarm().getNom() != null ? currentUser.getFarm().getNom() : currentUser.getFarm().getUniqueId();
        for (Utilisateurs superAdmin : utilisateursRepo.findAllSuperAdmins()) {
            emailService.sendAbonnementAValider(superAdmin.getEmail(), farmNom, montant,
                    periodicite.name(), request.getMoyenPaiement(), request.getReference());
        }

        return PaiementAbonnementDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<PaiementAbonnementDTO> listEnAttente(int page, int size) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        Pageable pageable = PageRequest.of(page, size);
        Page<PaiementAbonnement> resultPage = paiementAbonnementRepo
                .findByStatutOrderByDateDeclarationAsc(StatutPaiementAbonnement.EN_ATTENTE, pageable);

        return new PaginatedResponse<>(
                resultPage.getContent().stream().map(PaiementAbonnementDTO::fromEntity).toList(),
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }

    @Override
    @Transactional
    public PaiementAbonnementDTO valider(String paiementUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        PaiementAbonnement paiement = paiementAbonnementRepo.findByUniqueId(paiementUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Déclaration de paiement introuvable : " + paiementUniqueId));
        if (paiement.getStatut() != StatutPaiementAbonnement.EN_ATTENTE) {
            throw new IllegalArgumentException("Cette déclaration a déjà été traitée.");
        }

        Abonnement abonnement = paiement.getAbonnement();
        int joursAjoutes = paiement.getPeriodicite() == Periodicite.ANNUEL ? 365 : 30;
        // À partir de la plus tardive entre l'échéance actuelle et aujourd'hui : ne
        // fait jamais perdre de jours déjà payés (renouvellement en avance), ne
        // repart jamais dans le passé (ferme qui a laissé expirer).
        LocalDate base = abonnement.getDateFin().isAfter(LocalDate.now()) ? abonnement.getDateFin() : LocalDate.now();
        abonnement.setDateFin(base.plusDays(joursAjoutes));
        abonnement.setPeriodicite(paiement.getPeriodicite());
        abonnement.setStatut(StatutAbonnement.ACTIF);
        abonnementRepo.save(abonnement);

        paiement.setStatut(StatutPaiementAbonnement.VALIDE);
        paiement.setDateValidation(LocalDateTime.now());
        paiement.setValidePar(currentUser);
        PaiementAbonnement saved = paiementAbonnementRepo.save(paiement);

        if (paiement.getDeclarePar() != null) {
            String farmNom = abonnement.getFarm().getNom() != null ? abonnement.getFarm().getNom() : abonnement.getFarm().getUniqueId();
            emailService.sendAbonnementValide(paiement.getDeclarePar().getEmail(),
                    paiement.getDeclarePar().getFullName(), farmNom, abonnement.getDateFin());
        }

        return PaiementAbonnementDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public PaiementAbonnementDTO rejeter(String paiementUniqueId, RejeterPaiementAbonnementRequest request) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        PaiementAbonnement paiement = paiementAbonnementRepo.findByUniqueId(paiementUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Déclaration de paiement introuvable : " + paiementUniqueId));
        if (paiement.getStatut() != StatutPaiementAbonnement.EN_ATTENTE) {
            throw new IllegalArgumentException("Cette déclaration a déjà été traitée.");
        }

        paiement.setStatut(StatutPaiementAbonnement.REJETE);
        paiement.setDateValidation(LocalDateTime.now());
        paiement.setValidePar(currentUser);
        paiement.setMotifRejet(request != null ? request.getMotif() : null);
        return PaiementAbonnementDTO.fromEntity(paiementAbonnementRepo.save(paiement));
    }

    @Override
    @Transactional(readOnly = true)
    public AbonnementConfigDTO getConfig() {
        return AbonnementConfigDTO.fromEntity(getOuCreerConfig());
    }

    @Override
    @Transactional
    public AbonnementConfigDTO updateConfig(AbonnementConfigUpdateRequest request) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureSuperAdmin(currentUser);

        AbonnementConfig config = getOuCreerConfig();
        if (request.getPrixMensuel() != null) config.setPrixMensuel(request.getPrixMensuel());
        if (request.getPrixAnnuel() != null) config.setPrixAnnuel(request.getPrixAnnuel());
        if (request.getDureeEssaiJours() != null) config.setDureeEssaiJours(request.getDureeEssaiJours());
        if (request.getDureeGraceHeures() != null) config.setDureeGraceHeures(request.getDureeGraceHeures());
        return AbonnementConfigDTO.fromEntity(configRepo.save(config));
    }
}
```

- [ ] **Step 3: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q compile`
Expected: aucune sortie. Si erreur sur `Utilisateurs.getFarm()`/`getRoles()`/
`getEmail()`/`getFullName()` : vérifier l'orthographe exacte des getters dans
`src/main/java/com/diafarms/ml/models/Utilisateurs.java` (ils existent déjà, utilisés
partout ailleurs dans le projet — voir par ex. `MagasinServiceImpl.isAdmin`).

- [ ] **Step 4: Commit**

```bash
cd /home/mother/Bureau/diafarms_back
git add src/main/java/com/diafarms/ml/services/AbonnementService.java \
        src/main/java/com/diafarms/ml/ServiceImpl/AbonnementServiceImpl.java
git commit -m "Ajoute AbonnementServiceImpl (essai, déclaration, validation, config)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 7: Créer l'essai à l'inscription

**Files:**
- Modify: `src/main/java/com/diafarms/ml/ServiceImpl/UtilisateurImpl.java`

**Interfaces:**
- Consumes: `AbonnementService.creerEssaiPourFarm(Farm)` (Task 6).

- [ ] **Step 1: Injecter AbonnementService**

Dans `src/main/java/com/diafarms/ml/ServiceImpl/UtilisateurImpl.java`, ajouter
l'import `import com.diafarms.ml.services.AbonnementService;` puis, dans la liste
des champs `private final ...` (à côté de `emailService`), ajouter :
```java
    private final AbonnementService abonnementService;
```
(Lombok `@RequiredArgsConstructor`, déjà sur la classe, génère automatiquement le
constructeur avec ce nouveau champ — rien d'autre à faire pour l'injection.)

- [ ] **Step 2: Appeler creerEssaiPourFarm après la sauvegarde de la ferme**

Toujours dans `save()`, juste après la ligne
`Farm savedFarm = farmsRepo.save(newFarm);` (ligne ~91), ajouter :
```java
        abonnementService.creerEssaiPourFarm(savedFarm);
```

- [ ] **Step 3: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q compile`
Expected: aucune sortie.

- [ ] **Step 4: Commit**

```bash
cd /home/mother/Bureau/diafarms_back
git add src/main/java/com/diafarms/ml/ServiceImpl/UtilisateurImpl.java
git commit -m "Crée l'essai d'abonnement à l'inscription d'une nouvelle ferme

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 8: AbonnementController + vérification manuelle backend

**Files:**
- Create: `src/main/java/com/diafarms/ml/controllers/AbonnementController.java`

**Interfaces:**
- Consumes: `AbonnementService` (Task 6).
- Produces (routes, toutes sous `/diafarms/api/v1/abonnements`) :
  `GET /moi`, `POST /declarer-paiement`, `GET /config`, `PUT /config`,
  `GET /en-attente?page=&size=`, `POST /{uniqueId}/valider`,
  `POST /{uniqueId}/rejeter`.

- [ ] **Step 1: Créer le controller**

`src/main/java/com/diafarms/ml/controllers/AbonnementController.java` :
```java
package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.AbonnementConfigDTO;
import com.diafarms.ml.DTO.AbonnementDTO;
import com.diafarms.ml.DTO.PaiementAbonnementDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.others.AbonnementConfigUpdateRequest;
import com.diafarms.ml.request.others.DeclarerPaiementAbonnementRequest;
import com.diafarms.ml.request.others.RejeterPaiementAbonnementRequest;
import com.diafarms.ml.services.AbonnementService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/abonnements")
@RequiredArgsConstructor
public class AbonnementController {

    private final AbonnementService service;

    @GetMapping("/moi")
    public ResponseEntity<ApiResponse<AbonnementDTO>> moi() {
        try {
            return ApiResponse.createResponse("Statut d'abonnement récupéré", HttpStatus.OK, service.getMoi(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/declarer-paiement")
    public ResponseEntity<ApiResponse<PaiementAbonnementDTO>> declarerPaiement(@RequestBody DeclarerPaiementAbonnementRequest request) {
        try {
            return ApiResponse.createResponse("Déclaration de paiement enregistrée", HttpStatus.CREATED, service.declarerPaiement(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<AbonnementConfigDTO>> getConfig() {
        try {
            return ApiResponse.createResponse("Configuration tarifaire récupérée", HttpStatus.OK, service.getConfig(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/config")
    public ResponseEntity<ApiResponse<AbonnementConfigDTO>> updateConfig(@RequestBody AbonnementConfigUpdateRequest request) {
        try {
            return ApiResponse.createResponse("Configuration tarifaire mise à jour", HttpStatus.OK, service.updateConfig(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/en-attente")
    public ResponseEntity<ApiResponse<PaginatedResponse<PaiementAbonnementDTO>>> enAttente(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.createResponse("Déclarations en attente récupérées", HttpStatus.OK, service.listEnAttente(page, size), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/{uniqueId}/valider")
    public ResponseEntity<ApiResponse<PaiementAbonnementDTO>> valider(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Paiement validé, abonnement activé", HttpStatus.OK, service.valider(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/{uniqueId}/rejeter")
    public ResponseEntity<ApiResponse<PaiementAbonnementDTO>> rejeter(@PathVariable String uniqueId, @RequestBody RejeterPaiementAbonnementRequest request) {
        try {
            return ApiResponse.createResponse("Paiement rejeté", HttpStatus.OK, service.rejeter(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
```

- [ ] **Step 2: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q compile`
Expected: aucune sortie.

- [ ] **Step 3: Démarrer le backend et vérifier manuellement**

Si le backend local (MinIO + Postgres + `./mvnw spring-boot:run`) n'est pas déjà
lancé, le démarrer (voir mémoire du projet : MinIO natif à relancer manuellement,
Postgres persiste). Puis, avec un token JWT valide d'un compte de test existant
(`curl` login préalable ou cookie de session déjà en place dans le navigateur pour
un test manuel) :

Run: `curl -s http://localhost:9093/diafarms/api/v1/abonnements/config`
Expected: `401` si pas de session (comportement normal, cette route n'est en fait
lisible qu'authentifié malgré le commentaire "lecture publique" du spec — "publique"
signifiait "sans restriction de rôle", pas "sans authentification").

Avec une session valide (ex: via le navigateur déjà connecté, onglet réseau) :
`GET /abonnements/moi` doit renvoyer un abonnement `ESSAI` avec `joursRestants`
proche de 14 pour un compte de ferme existant (création paresseuse déclenchée au
premier appel).

- [ ] **Step 4: Commit**

```bash
cd /home/mother/Bureau/diafarms_back
git add src/main/java/com/diafarms/ml/controllers/AbonnementController.java
git commit -m "Ajoute AbonnementController

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 9: Web — types et appels API

**Files:**
- Modify: `src/services/api.ts`

**Interfaces:**
- Consumes: réponses JSON de `AbonnementController` (Task 8) — mêmes noms de champs
  que `AbonnementDTO`/`PaiementAbonnementDTO`/`AbonnementConfigDTO` côté Java
  (Jackson sérialise les getters Lombok tels quels : `uniqueId`, `farmUniqueId`,
  `farmNom`, `statutEffectif`, `enGrace`, `dateFin`, `joursRestants`, `periodicite`,
  `paiementEnAttente`, etc.).
- Produces: `getMonAbonnementAPI()`, `declarerPaiementAbonnementAPI(payload)`,
  `getAbonnementConfigAPI()`, `updateAbonnementConfigAPI(payload)`,
  `getAbonnementsEnAttenteAPI(page, size)`, `validerAbonnementAPI(uniqueId)`,
  `rejeterAbonnementAPI(uniqueId, motif)` — utilisés par les tâches web suivantes.

- [ ] **Step 1: Ajouter les types et fonctions**

Trouver la fin du fichier `src/services/api.ts` (juste avant les exports "legacy"
mock en bas du fichier, ou après le dernier bloc `//======================...`) et
ajouter :
```typescript
//======================ABONNEMENT======================
// Cycle d'abonnement géré manuellement : essai 14 jours à l'inscription, déclaration
// "J'ai payé" par la ferme, validation par un SUPER_ADMIN — voir
// docs/superpowers/specs/2026-08-25-abonnement-design.md côté backend.
export interface PaiementAbonnementDTO {
  uniqueId: string;
  farmNom: string | null;
  montant: number;
  periodicite: "MENSUEL" | "ANNUEL";
  moyenPaiement: string;
  reference: string | null;
  statut: "EN_ATTENTE" | "VALIDE" | "REJETE";
  dateDeclaration: string;
  declareParNom: string | null;
  dateValidation: string | null;
  valideParNom: string | null;
  motifRejet: string | null;
}

export interface AbonnementDTO {
  uniqueId: string;
  farmUniqueId: string | null;
  farmNom: string | null;
  statutEffectif: "ESSAI" | "ACTIF" | "EXPIRE";
  enGrace: boolean;
  dateFin: string;
  joursRestants: number;
  periodicite: "MENSUEL" | "ANNUEL" | null;
  paiementEnAttente: PaiementAbonnementDTO | null;
}

export interface AbonnementConfigDTO {
  prixMensuel: number;
  prixAnnuel: number;
  dureeEssaiJours: number;
  dureeGraceHeures: number;
}

export interface DeclarerPaiementAbonnementPayload {
  periodicite: "MENSUEL" | "ANNUEL";
  moyenPaiement: string;
  reference?: string;
}

// null pour un SUPER_ADMIN (pas de ferme, pas d'abonnement) — voir
// AbonnementServiceImpl.getMoi côté back.
export const getMonAbonnementAPI = async (): Promise<AbonnementDTO | null> => {
  return authenticatedApiCallReal<AbonnementDTO | null>('/abonnements/moi', { method: 'GET' });
};

export const declarerPaiementAbonnementAPI = async (payload: DeclarerPaiementAbonnementPayload): Promise<PaiementAbonnementDTO> => {
  return authenticatedApiCallReal<PaiementAbonnementDTO>('/abonnements/declarer-paiement', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
};

export const getAbonnementConfigAPI = async (): Promise<AbonnementConfigDTO> => {
  return authenticatedApiCallReal<AbonnementConfigDTO>('/abonnements/config', { method: 'GET' });
};

export const updateAbonnementConfigAPI = async (payload: Partial<AbonnementConfigDTO>): Promise<AbonnementConfigDTO> => {
  return authenticatedApiCallReal<AbonnementConfigDTO>('/abonnements/config', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
};

export const getAbonnementsEnAttenteAPI = async (
  page: number,
  size: number
): Promise<{ data: PaiementAbonnementDTO[]; currentPage: number; totalPages: number; totalItems: number; size: number }> => {
  return authenticatedApiCallReal<any>(`/abonnements/en-attente?page=${page - 1}&size=${size}`, { method: 'GET' });
};

export const validerAbonnementAPI = async (uniqueId: string): Promise<PaiementAbonnementDTO> => {
  return authenticatedApiCallReal<PaiementAbonnementDTO>(`/abonnements/${uniqueId}/valider`, { method: 'POST' });
};

export const rejeterAbonnementAPI = async (uniqueId: string, motif: string): Promise<PaiementAbonnementDTO> => {
  return authenticatedApiCallReal<PaiementAbonnementDTO>(`/abonnements/${uniqueId}/rejeter`, {
    method: 'POST',
    body: JSON.stringify({ motif }),
  });
};
```

Vérifier au préalable le nom exact de la fonction d'appel authentifiée déjà utilisée
partout ailleurs dans ce fichier (`authenticatedApiCallReal`, confirmée par ex. dans
`getMagasinStockAPI` un peu plus haut dans le même fichier) — l'utiliser telle
quelle, ne pas en réinventer une variante.

- [ ] **Step 2: Typecheck**

Run: `cd /home/mother/Bureau/Diafarms_web && npx tsc -p tsconfig.app.json --noEmit`
Expected: aucune sortie.

- [ ] **Step 3: Commit**

```bash
cd /home/mother/Bureau/Diafarms_web
git add src/services/api.ts
git commit -m "Ajoute les appels API Abonnement

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 10: Web — page Abonnement (ferme)

**Files:**
- Create: `src/pages/Abonnement.tsx`
- Modify: `src/App.tsx`

**Interfaces:**
- Consumes: `getMonAbonnementAPI`, `declarerPaiementAbonnementAPI`,
  `getAbonnementConfigAPI` (Task 9).

- [ ] **Step 1: Créer la page**

`src/pages/Abonnement.tsx` :
```tsx
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useAuth } from "@/contexts/AuthContext";
import { hasOnlyRole } from "@/lib/roles";
import {
  getMonAbonnementAPI, getAbonnementConfigAPI, declarerPaiementAbonnementAPI,
  type AbonnementDTO, type AbonnementConfigDTO,
} from "@/services/api";

const statutLabel: Record<string, string> = {
  ESSAI: "Période d'essai",
  ACTIF: "Actif",
  EXPIRE: "Expiré",
};

export default function Abonnement() {
  const { user } = useAuth();
  const canDeclarer = !!user?.roles && (user.roles.includes("ADMIN") || user.roles.includes("RESPONSABLE"));

  const [abonnement, setAbonnement] = useState<AbonnementDTO | null>(null);
  const [config, setConfig] = useState<AbonnementConfigDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [periodicite, setPeriodicite] = useState<"MENSUEL" | "ANNUEL">("MENSUEL");
  const [moyenPaiement, setMoyenPaiement] = useState("");
  const [reference, setReference] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const charger = () => {
    Promise.all([getMonAbonnementAPI(), getAbonnementConfigAPI()])
      .then(([a, c]) => { setAbonnement(a); setConfig(c); })
      .catch(() => toast.error("Impossible de charger l'abonnement"))
      .finally(() => setLoading(false));
  };

  useEffect(charger, []);

  const handleDeclarer = async () => {
    if (!moyenPaiement.trim()) {
      toast.error("Indiquez le moyen de paiement utilisé");
      return;
    }
    setSubmitting(true);
    try {
      await declarerPaiementAbonnementAPI({ periodicite, moyenPaiement, reference: reference || undefined });
      toast.success("Déclaration envoyée — en attente de validation");
      setMoyenPaiement("");
      setReference("");
      charger();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Erreur lors de la déclaration");
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <div className="p-6 text-center text-muted-foreground">Chargement...</div>;
  if (!abonnement || !config) return <div className="p-6 text-center text-muted-foreground">Abonnement non disponible</div>;

  const montant = periodicite === "ANNUEL" ? config.prixAnnuel : config.prixMensuel;

  return (
    <div className="space-y-6 max-w-2xl">
      <h1 className="text-2xl font-bold text-foreground">Abonnement</h1>

      <div className="rounded-xl border border-border bg-card p-6 space-y-2">
        <p className="text-sm text-muted-foreground">Statut</p>
        <p className="text-xl font-bold text-foreground">
          {statutLabel[abonnement.statutEffectif]}
          {abonnement.enGrace && <span className="text-destructive text-sm ml-2">(délai de grâce)</span>}
        </p>
        <p className="text-sm text-muted-foreground">
          Échéance : {abonnement.dateFin} ({abonnement.joursRestants >= 0
            ? `${abonnement.joursRestants} jour(s) restant(s)`
            : `expiré depuis ${Math.abs(abonnement.joursRestants)} jour(s)`})
        </p>
        {abonnement.paiementEnAttente && (
          <p className="text-sm text-amber-600 mt-2">
            Une déclaration de paiement ({abonnement.paiementEnAttente.montant.toLocaleString('fr-FR')} FCFA) est en attente de validation.
          </p>
        )}
      </div>

      {canDeclarer && !abonnement.paiementEnAttente && (
        <div className="rounded-xl border border-border bg-card p-6 space-y-4">
          <h2 className="text-sm font-semibold text-foreground">J'ai payé</h2>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Formule</Label>
              <Select value={periodicite} onValueChange={(v) => setPeriodicite(v as "MENSUEL" | "ANNUEL")}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="MENSUEL">Mensuel — {config.prixMensuel.toLocaleString('fr-FR')} FCFA</SelectItem>
                  <SelectItem value="ANNUEL">Annuel — {config.prixAnnuel.toLocaleString('fr-FR')} FCFA</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label>Montant</Label>
              <Input value={`${montant.toLocaleString('fr-FR')} FCFA`} disabled />
            </div>
          </div>
          <div>
            <Label>Moyen de paiement *</Label>
            <Input value={moyenPaiement} onChange={(e) => setMoyenPaiement(e.target.value)} placeholder="Orange Money, Wave, Virement..." />
          </div>
          <div>
            <Label>Référence (optionnel)</Label>
            <Input value={reference} onChange={(e) => setReference(e.target.value)} placeholder="Référence de la transaction" />
          </div>
          <Button type="button" onClick={handleDeclarer} disabled={submitting}>
            {submitting ? "Envoi..." : "✓ J'ai payé"}
          </Button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Ajouter la route**

Dans `src/App.tsx`, ajouter l'import `import Abonnement from "@/pages/Abonnement";`
à côté des autres imports de pages, puis ajouter une route juste après celle de
`/parametres` (ligne ~127, à l'intérieur de `<Routes>`) :
```tsx
            <Route path="/abonnement" element={<ProtectedRoute><DashboardLayout><Abonnement /></DashboardLayout></ProtectedRoute>} />
```
(Pas de `blockRoles` : tous les rôles d'une ferme peuvent voir le statut, seul le
bouton "J'ai payé" est filtré côté composant par `canDeclarer`.)

- [ ] **Step 3: Typecheck**

Run: `cd /home/mother/Bureau/Diafarms_web && npx tsc -p tsconfig.app.json --noEmit`
Expected: aucune sortie.

- [ ] **Step 4: Commit**

```bash
cd /home/mother/Bureau/Diafarms_web
git add src/pages/Abonnement.tsx src/App.tsx
git commit -m "Ajoute la page Abonnement (ferme) et sa route

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 11: Web — garde de blocage

**Files:**
- Create: `src/components/AbonnementGate.tsx`
- Modify: `src/App.tsx`

**Interfaces:**
- Consumes: `getMonAbonnementAPI` (Task 9), `useAuth` (`AuthContext` existant).
- Produces: `<AbonnementGate>{children}</AbonnementGate>` — enveloppe tout le
  contenu applicatif authentifié.

- [ ] **Step 1: Créer le composant de garde**

`src/components/AbonnementGate.tsx` :
```tsx
import { useEffect, useState, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { useAuth } from "@/contexts/AuthContext";
import { hasOnlyRole } from "@/lib/roles";
import { getMonAbonnementAPI, type AbonnementDTO } from "@/services/api";

// Bloque l'usage du web (jamais l'API elle-même, jamais le mobile — voir
// AbonnementServiceImpl côté back) une fois l'abonnement expiré (grâce comprise).
// Le SUPER_ADMIN n'a pas de ferme, donc pas d'abonnement à vérifier : jamais bloqué.
export default function AbonnementGate({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const location = useLocation();
  const [abonnement, setAbonnement] = useState<AbonnementDTO | null>(null);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (!user || hasOnlyRole(user.roles, "SUPER_ADMIN")) {
      setChecked(true);
      return;
    }
    getMonAbonnementAPI()
      .then(setAbonnement)
      .catch(() => setAbonnement(null))
      .finally(() => setChecked(true));
  }, [user]);

  if (!checked || !user || hasOnlyRole(user.roles, "SUPER_ADMIN")) {
    return <>{children}</>;
  }

  const bloque = abonnement?.statutEffectif === "EXPIRE";
  const surPageAbonnement = location.pathname === "/abonnement";

  if (bloque && !surPageAbonnement) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background p-6">
        <div className="max-w-md text-center space-y-4">
          <h1 className="text-2xl font-bold text-foreground">Abonnement expiré</h1>
          <p className="text-muted-foreground">
            L'abonnement de votre ferme a expiré{abonnement ? ` depuis ${Math.abs(abonnement.joursRestants)} jour(s)` : ""}.
            Rendez-vous sur la page Abonnement pour régulariser.
          </p>
          <a href="/abonnement" className="inline-block px-4 py-2 rounded-lg bg-primary text-primary-foreground font-medium">
            Voir mon abonnement
          </a>
        </div>
      </div>
    );
  }

  return (
    <>
      {abonnement && abonnement.statutEffectif !== "EXPIRE" && abonnement.joursRestants <= 3 && (
        <div className="bg-amber-50 border-b border-amber-200 text-amber-800 text-sm text-center py-2 px-4">
          {abonnement.enGrace
            ? `Votre abonnement a expiré, délai de grâce en cours — régularisez rapidement.`
            : `Il vous reste ${abonnement.joursRestants} jour(s) avant l'échéance de votre abonnement.`}
        </div>
      )}
      {children}
    </>
  );
}
```

- [ ] **Step 2: Brancher la garde dans App.tsx**

Dans `src/App.tsx`, importer `import AbonnementGate from "@/components/
AbonnementGate";`, puis envelopper le contenu déjà présent à l'intérieur de
`<AuthProvider>` avec `<AbonnementGate>` — repérer le bloc actuel :
```tsx
        <AuthProvider>
          <Routes>
            ...
          </Routes>
        </AuthProvider>
```
et le remplacer par :
```tsx
        <AuthProvider>
          <AbonnementGate>
            <Routes>
              ...
            </Routes>
          </AbonnementGate>
        </AuthProvider>
```
(Ne pas toucher au contenu de `<Routes>` lui-même, seulement ajouter les deux
balises englobantes.)

- [ ] **Step 3: Typecheck**

Run: `cd /home/mother/Bureau/Diafarms_web && npx tsc -p tsconfig.app.json --noEmit`
Expected: aucune sortie.

- [ ] **Step 4: Commit**

```bash
cd /home/mother/Bureau/Diafarms_web
git add src/components/AbonnementGate.tsx src/App.tsx
git commit -m "Ajoute la garde de blocage web pour abonnement expiré

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 12: Web — portail SUPER_ADMIN minimal

**Files:**
- Create: `src/pages/SuperAdminAbonnements.tsx`
- Modify: `src/App.tsx`
- Modify: `src/components/layout/DashboardLayout.tsx`

**Interfaces:**
- Consumes: `getAbonnementsEnAttenteAPI`, `validerAbonnementAPI`,
  `rejeterAbonnementAPI`, `getAbonnementConfigAPI`, `updateAbonnementConfigAPI`
  (Task 9).

- [ ] **Step 1: Créer la page**

`src/pages/SuperAdminAbonnements.tsx` :
```tsx
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  getAbonnementsEnAttenteAPI, validerAbonnementAPI, rejeterAbonnementAPI,
  getAbonnementConfigAPI, updateAbonnementConfigAPI,
  type PaiementAbonnementDTO, type AbonnementConfigDTO,
} from "@/services/api";

export default function SuperAdminAbonnements() {
  const [enAttente, setEnAttente] = useState<PaiementAbonnementDTO[]>([]);
  const [config, setConfig] = useState<AbonnementConfigDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [savingConfig, setSavingConfig] = useState(false);

  const charger = () => {
    Promise.all([getAbonnementsEnAttenteAPI(1, 50), getAbonnementConfigAPI()])
      .then(([res, c]) => { setEnAttente(res.data || []); setConfig(c); })
      .catch(() => toast.error("Erreur lors du chargement"))
      .finally(() => setLoading(false));
  };

  useEffect(charger, []);

  const handleValider = async (uniqueId: string) => {
    try {
      await validerAbonnementAPI(uniqueId);
      toast.success("Abonnement activé");
      charger();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Erreur lors de la validation");
    }
  };

  const handleRejeter = async (uniqueId: string) => {
    const motif = window.prompt("Motif du rejet (optionnel) :") || "";
    try {
      await rejeterAbonnementAPI(uniqueId, motif);
      toast.success("Paiement rejeté");
      charger();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Erreur lors du rejet");
    }
  };

  const handleSaveConfig = async () => {
    if (!config) return;
    setSavingConfig(true);
    try {
      const updated = await updateAbonnementConfigAPI(config);
      setConfig(updated);
      toast.success("Configuration mise à jour");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Erreur lors de l'enregistrement");
    } finally {
      setSavingConfig(false);
    }
  };

  if (loading) return <div className="p-6 text-center text-muted-foreground">Chargement...</div>;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-foreground">Abonnements à valider</h1>

      <div className="rounded-xl border border-border bg-card overflow-hidden">
        {enAttente.length === 0 ? (
          <p className="p-6 text-center text-muted-foreground italic">Aucune déclaration en attente</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/50">
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">Ferme</th>
                <th className="text-right px-4 py-3 font-medium text-muted-foreground">Montant</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">Formule</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">Moyen de paiement</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">Référence</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">Déclaré le</th>
                <th className="px-4 py-3"></th>
              </tr>
            </thead>
            <tbody>
              {enAttente.map((p) => (
                <tr key={p.uniqueId} className="border-b border-border last:border-0">
                  <td className="px-4 py-3 text-foreground">{p.farmNom}</td>
                  <td className="px-4 py-3 text-right">{p.montant.toLocaleString('fr-FR')} FCFA</td>
                  <td className="px-4 py-3">{p.periodicite}</td>
                  <td className="px-4 py-3">{p.moyenPaiement}</td>
                  <td className="px-4 py-3">{p.reference || "—"}</td>
                  <td className="px-4 py-3 whitespace-nowrap">{new Date(p.dateDeclaration).toLocaleString('fr-FR')}</td>
                  <td className="px-4 py-3 flex gap-2 justify-end">
                    <Button size="sm" onClick={() => handleValider(p.uniqueId)}>Valider</Button>
                    <Button size="sm" variant="outline" onClick={() => handleRejeter(p.uniqueId)}>Rejeter</Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {config && (
        <div className="rounded-xl border border-border bg-card p-6 space-y-4 max-w-lg">
          <h2 className="text-sm font-semibold text-foreground">Réglages tarifaires</h2>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Prix mensuel (FCFA)</Label>
              <Input type="number" value={config.prixMensuel} onChange={(e) => setConfig({ ...config, prixMensuel: Number(e.target.value) })} />
            </div>
            <div>
              <Label>Prix annuel (FCFA)</Label>
              <Input type="number" value={config.prixAnnuel} onChange={(e) => setConfig({ ...config, prixAnnuel: Number(e.target.value) })} />
            </div>
            <div>
              <Label>Durée d'essai (jours)</Label>
              <Input type="number" value={config.dureeEssaiJours} onChange={(e) => setConfig({ ...config, dureeEssaiJours: Number(e.target.value) })} />
            </div>
            <div>
              <Label>Durée de grâce (heures)</Label>
              <Input type="number" value={config.dureeGraceHeures} onChange={(e) => setConfig({ ...config, dureeGraceHeures: Number(e.target.value) })} />
            </div>
          </div>
          <Button type="button" onClick={handleSaveConfig} disabled={savingConfig}>
            {savingConfig ? "Enregistrement..." : "Enregistrer"}
          </Button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Brancher HomeRoute**

Dans `src/App.tsx`, dans la fonction `HomeRoute` (ligne ~92), ajouter l'import
`import SuperAdminAbonnements from "@/pages/SuperAdminAbonnements";` puis, en toute
première condition (avant celle sur `RESPONSABLE`) :
```tsx
  if (hasOnlyRole(user?.roles, "SUPER_ADMIN")) return <SuperAdminAbonnements />;
```

- [ ] **Step 3: Adapter la navigation pour SUPER_ADMIN**

Dans `src/components/layout/DashboardLayout.tsx`, la ligne d'import existante est :
```tsx
import { isRestrictedTo } from "@/lib/roles";
```
La remplacer par :
```tsx
import { hasOnlyRole, isRestrictedTo } from "@/lib/roles";
```
Puis repérer les lignes :
```tsx
  const visibleNavItems = navItems.filter((item) => !item.hideFor || !isRestrictedTo(user?.roles, item.hideFor));
  const visibleBottomItems = bottomItems.filter((item) => !item.hideFor || !isRestrictedTo(user?.roles, item.hideFor));
```
et les remplacer par :
```tsx
  const isSuperAdmin = hasOnlyRole(user?.roles, "SUPER_ADMIN");
  const visibleNavItems = isSuperAdmin ? [] : navItems.filter((item) => !item.hideFor || !isRestrictedTo(user?.roles, item.hideFor));
  const visibleBottomItems = isSuperAdmin ? [] : bottomItems.filter((item) => !item.hideFor || !isRestrictedTo(user?.roles, item.hideFor));
```
(Le SUPER_ADMIN n'a pas de ferme : tous les items de menu existants pointent vers
des pages qui présupposent une ferme — inutile de les lui montrer. Il n'a qu'une
seule page, déjà servie directement par `HomeRoute`, pas besoin d'un lien de menu
dédié pour l'instant.)

- [ ] **Step 4: Typecheck**

Run: `cd /home/mother/Bureau/Diafarms_web && npx tsc -p tsconfig.app.json --noEmit`
Expected: aucune sortie.

- [ ] **Step 5: Commit**

```bash
cd /home/mother/Bureau/Diafarms_web
git add src/pages/SuperAdminAbonnements.tsx src/App.tsx src/components/layout/DashboardLayout.tsx
git commit -m "Ajoute le portail SUPER_ADMIN de validation des abonnements

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 13: Vérification manuelle de bout en bout

**Files:** aucun (vérification uniquement).

- [ ] **Step 1: Démarrer backend + web en local**

Si pas déjà lancés : MinIO (`/home/mother/minio-bin/minio server /home/mother/minio-data --address ":9000" --console-address ":9001"`), Postgres (déjà en service),
backend (`cd /home/mother/Bureau/diafarms_back && ./mvnw -q spring-boot:run`), web
(`cd /home/mother/Bureau/Diafarms_web && npm run dev`).

- [ ] **Step 2: Vérifier le parcours ferme**

Se connecter avec un compte ADMIN/RESPONSABLE existant → aller sur `/abonnement` →
vérifier que le statut ESSAI et l'échéance s'affichent (création paresseuse si
c'était un compte déjà existant avant ce déploiement) → remplir le formulaire "J'ai
payé" → vérifier le message de succès et que la section disparaît (une déclaration
déjà en attente).

- [ ] **Step 3: Vérifier le parcours SUPER_ADMIN**

Se connecter avec le compte `superadmin` (voir `super-admin-seed.json` local) →
vérifier l'arrivée directe sur la liste des déclarations en attente (pas le
Dashboard classique) → vérifier que le menu latéral est vide/minimal → cliquer
Valider sur la déclaration créée à l'étape précédente → vérifier qu'elle disparaît
de la liste.

- [ ] **Step 4: Vérifier l'email (si SMTP configuré en local)**

Vérifier dans les logs backend (`grep -i "Échec de l'envoi" ` sur la sortie du
serveur) qu'aucune erreur d'envoi n'apparaît pour les deux nouveaux templates lors
des étapes 2 et 3 ci-dessus. Si le compte Gmail configuré dans `.env` est valide,
vérifier la réception réelle des deux emails.

- [ ] **Step 5: Vérifier que le blocage ne casse rien pour un abonnement actif**

Recharger l'app connectée avec le compte ferme utilisé à l'étape 2 (abonnement ACTIF
après validation) → vérifier qu'aucun écran de blocage n'apparaît et que la
navigation normale fonctionne.
