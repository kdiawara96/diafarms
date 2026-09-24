# Circuit de l'argent client — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer les cinq façons d'enregistrer l'argent d'un client par un seul
« paiement client », imputé automatiquement sur les ventes, avec avance et reste à payer
recalculés, remboursements traçables, factures multi-ventes en lecture seule et
comptabilité « Vendu / Encaissé » séparée — sans jamais écraser un montant historique.

**Architecture:** Trois nouvelles entités (`PaiementClient`, `ImputationPaiement`,
`RemboursementClient`) et un moteur d'imputation pur (`CalculImputation`, testé en
JUnit) orchestré par `CompteClientService`, source unique du solde client (le compteur
`SoldeClient` n'est plus écrit ni lu). Les anciens points d'entrée (`payer-dette`,
`rembourser`, `marquer-payee`, acompte de commande, `montantRapporte` d'une vente avec
client) restent en place mais deviennent de simples adaptateurs vers le nouveau modèle :
le web et les APK déjà installés continuent de fonctionner pendant le déploiement. Une
reprise des données (simulation puis exécution) convertit l'existant.

**Tech Stack:** Spring Boot 3.5 / JPA / Postgres (`diafarms_back`), React / Vite /
TypeScript (`Diafarms_web`), Android Java (`Diafarms`). JUnit 5 déjà présent via
`spring-boot-starter-test`.

**Spec:** `docs/superpowers/specs/2026-09-23-circuit-argent-client-design.md`

## Global Constraints

- Branche `version_2` dans les trois dépôts ; commit à la fin de chaque tâche, push
  seulement à la fin d'une phase.
- Aucun montant historique n'est modifié pour « corriger » un compte : une erreur se
  corrige en **annulant** l'enregistrement (motif ≥ 3 caractères, qui, quand) puis en
  en créant un nouveau. Exception documentée : la reprise (Task 11) retire de
  `montantRapporte` les recopies de `marquerPayee`, avec rapport préalable validé.
- Solde client : positif = le client doit ; négatif = avance. Toujours calculé par
  `CompteClientService`, jamais stocké.
- Montants arrondis au centime avec `CalculImputation.arrondi(double)` avant toute
  comparaison ou enregistrement.
- Nouvelles valeurs d'enum `@Enumerated(STRING)` : **ALTER manuel de la contrainte
  CHECK** (ddl-auto=update ne la met pas à jour) ; SQL regroupé dans
  `docs/sql/2026-09-2x_circuit_client.sql` (Task 12).
- `SourceTransaction` : noms ≤ 20 caractères (`varchar(20)`) ; `sourceUniqueId` ≤ 50.
- Jamais de `(:param IS NULL OR ...)` en JPQL (crash Postgres) : bornes concrètes ou
  booléen `hasX`.
- JPQL : `LEFT JOIN` explicite dès qu'une association peut être nulle.
- Contrôle d'accès dans les services (`OtherService.getCurrentUser()` + rôles), style
  `isAdmin`/`hasRole` existant. Enregistrer un paiement : ADMIN, SUPER_ADMIN,
  RESPONSABLE, COMPTABLE, VENTE. Annuler un paiement, effectuer/annuler un
  remboursement, annuler/clôturer une commande, annuler une facture : ADMIN,
  SUPER_ADMIN, RESPONSABLE (+ COMPTABLE pour le remboursement, comme aujourd'hui).
- Libellés comptables : entrée « Paiement client » (source `PAIEMENT_CLIENT`), sortie
  « Remboursement au client » (source `REMBOURSEMENT_CLI`). Ces transactions sont
  verrouillées en Comptabilité (même mécanisme que les ventes).
- Vérification : `./mvnw -q -o compile` + `./mvnw -q -o test -Dtest=CalculImputationTest`
  (backend), `npx tsc --noEmit -p tsconfig.app.json` (web — jamais `tsc` nu),
  `./gradlew -q --offline assembleRelease` (mobile), et le script de scénarios
  (Task 10) contre un backend local sur Postgres temporaire.
- Backend local de test : Postgres temporaire du scratchpad (port 55432) et jar lancé
  depuis un dossier contenant un `.env` factice (voir Task 10, étape 1).

## Review Focus

- **Paiement annulé alors qu'une partie a été remboursée** → refusé avec message
  (« annulez d'abord le remboursement »), jamais d'avance négative. Test : Task 4.
- **Vente dont le montant baisse sous ce qui est déjà imputé** (modification ou
  livraison corrigée) → l'excédent retourne en avance par annulation + recréation
  d'imputation, historique conservé. Test : Task 2 (`reduire`) et Task 10 (scénario 10).
- **Changement de client sur une vente** → imputations de l'ancien client annulées et
  réimputées chez lui, avance du nouveau client imputée. Test : Task 10 (scénario 11).
- **Deux paiements saisis presque en même temps pour le même client** (deux vendeurs)
  → pas de double imputation : `imputer(client)` verrouille la ligne client
  (`PESSIMISTIC_WRITE`). Test : Task 3 (requête `findByIdForUpdate`), vérifié par
  lecture du SQL généré (`FOR UPDATE`).
- **Remboursement supérieur à l'avance** (ou client sans avance) → refusé, montant
  disponible indiqué. Test : Task 4 et Task 10 (scénario 7 bis).

---

## Phase A — Backend : modèle, moteur, adaptateurs

### Task 1: Entités, enums et dépôts

**Files:**
- Create: `src/main/java/com/diafarms/ml/enums/ModePaiement.java`
- Create: `src/main/java/com/diafarms/ml/enums/OriginePaiement.java`
- Create: `src/main/java/com/diafarms/ml/enums/CibleImputation.java`
- Create: `src/main/java/com/diafarms/ml/enums/StatutMouvement.java`
- Create: `src/main/java/com/diafarms/ml/models/PaiementClient.java`
- Create: `src/main/java/com/diafarms/ml/models/ImputationPaiement.java`
- Create: `src/main/java/com/diafarms/ml/models/RemboursementClient.java`
- Create: `src/main/java/com/diafarms/ml/repository/PaiementClientRepo.java`
- Create: `src/main/java/com/diafarms/ml/repository/ImputationPaiementRepo.java`
- Create: `src/main/java/com/diafarms/ml/repository/RemboursementClientRepo.java`
- Modify: `src/main/java/com/diafarms/ml/enums/SourceTransaction.java`
- Modify: `src/main/java/com/diafarms/ml/models/VenteOeufs.java`, `VenteReforme.java` (lien commande)
- Modify: `src/main/java/com/diafarms/ml/repository/ClientRepo.java` (verrou)

**Interfaces:**
- Produces: les entités ci-dessous, `SourceTransaction.PAIEMENT_CLIENT`,
  `SourceTransaction.REMBOURSEMENT_CLI`, `VenteOeufs.commande` / `VenteReforme.commande`,
  `ClientRepo.findByIdForUpdate(Long)`, méthodes de dépôt listées.

- [ ] **Step 1: Enums**

```java
// enums/ModePaiement.java
package com.diafarms.ml.enums;
public enum ModePaiement { ESPECES, ORANGE_MONEY, MOOV_MONEY, WAVE, VIREMENT, CHEQUE, AUTRE }

// enums/OriginePaiement.java
package com.diafarms.ml.enums;
// D'où vient le paiement — purement descriptif, l'argent est le même partout.
public enum OriginePaiement { ACOMPTE, LIVRAISON, VENTE, REGLEMENT, FACTURE, REPRISE }

// enums/CibleImputation.java
package com.diafarms.ml.enums;
// Ce qu'un paiement règle : une vente (œufs ou réforme) ou un remboursement.
public enum CibleImputation { VENTE_OEUFS, VENTE_REFORME, REMBOURSEMENT }

// enums/StatutMouvement.java
package com.diafarms.ml.enums;
// Paiement, imputation, remboursement : jamais modifiés, seulement annulés (motif).
public enum StatutMouvement { ACTIF, ANNULE }
```

- [ ] **Step 2: `SourceTransaction`** — ajouter après `VENTE_DIVERSE` :

```java
    VENTE_DIVERSE,
    // Paiement d'un client (voir PaiementClient) — sourceUniqueId = uniqueId du paiement.
    PAIEMENT_CLIENT,
    // Remboursement au client (voir RemboursementClient) — 17 caractères, varchar(20).
    REMBOURSEMENT_CLI
```

- [ ] **Step 3: `PaiementClient`**

```java
package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.enums.StatutMouvement;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Argent donné par un client. Jamais modifié : une erreur s'annule (motif) et on en
// saisit un nouveau. Sa Transaction "Paiement client" (source PAIEMENT_CLIENT) le suit.
@Entity
@Table(name = "paiements_client")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PaiementClient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Double montant;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ModePaiement mode;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private OriginePaiement origine;

    // Facultatifs : priorité d'imputation et traçabilité.
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "commande_id")
    private Commande commande;

    @Enumerated(EnumType.STRING) @Column(name = "vente_cible_type", length = 20)
    private CibleImputation venteCibleType;

    @Column(name = "vente_cible_unique_id", length = 50)
    private String venteCibleUniqueId;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "facture_id")
    private Facture facture;

    @Column(columnDefinition = "TEXT")
    private String observations;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "recu_par_id")
    private Utilisateurs recuPar;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private StatutMouvement statut = StatutMouvement.ACTIF;

    @Column(name = "motif_annulation", columnDefinition = "TEXT")
    private String motifAnnulation;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "annule_par_id")
    private Utilisateurs annulePar;

    private LocalDateTime dateAnnulation;

    @Embedded
    private Initialisation initialisation;
}
```

- [ ] **Step 4: `ImputationPaiement`**

```java
package com.diafarms.ml.models;

import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.StatutMouvement;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// "Tel paiement règle telle vente (ou tel remboursement) pour tel montant". Créée par
// CompteClientService.imputer, jamais modifiée : annulée puis recréée si besoin.
@Entity
@Table(name = "imputations_paiement", indexes = {
        @Index(name = "idx_imputation_cible", columnList = "cible_type,cible_unique_id"),
        @Index(name = "idx_imputation_paiement", columnList = "paiement_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ImputationPaiement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "paiement_id", nullable = false)
    private PaiementClient paiement;

    @Enumerated(EnumType.STRING) @Column(name = "cible_type", nullable = false, length = 20)
    private CibleImputation cibleType;

    @Column(name = "cible_unique_id", nullable = false, length = 50)
    private String cibleUniqueId;

    @Column(nullable = false)
    private Double montant;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private StatutMouvement statut = StatutMouvement.ACTIF;

    @Column(name = "motif_annulation", columnDefinition = "TEXT")
    private String motifAnnulation;

    private LocalDateTime dateAnnulation;

    @Embedded
    private Initialisation initialisation;
}
```

- [ ] **Step 5: `RemboursementClient`**

```java
package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.StatutMouvement;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Argent rendu au client, pris sur son avance (des imputations de cible REMBOURSEMENT
// consomment les paiements). Transaction "Remboursement au client" (REMBOURSEMENT_CLI).
@Entity
@Table(name = "remboursements_client")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RemboursementClient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "commande_id")
    private Commande commande;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Double montant;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ModePaiement mode;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String motif;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "effectue_par_id")
    private Utilisateurs effectuePar;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private StatutMouvement statut = StatutMouvement.ACTIF;

    @Column(name = "motif_annulation", columnDefinition = "TEXT")
    private String motifAnnulation;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "annule_par_id")
    private Utilisateurs annulePar;

    private LocalDateTime dateAnnulation;

    @Embedded
    private Initialisation initialisation;
}
```

- [ ] **Step 6: Lien vente → commande.** Dans `VenteOeufs.java` et `VenteReforme.java`,
  juste avant `@Embedded private Initialisation initialisation;` :

```java
    // Non null = cette vente est une LIVRAISON de cette commande (une livraison = une
    // vente). Remplace Commande.venteUniqueId, qui ne gardait que la dernière.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commande_id")
    private Commande commande;
```

- [ ] **Step 7: Dépôts**

```java
// repository/PaiementClientRepo.java
package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.diafarms.ml.models.PaiementClient;

public interface PaiementClientRepo extends JpaRepository<PaiementClient, Long> {
    Optional<PaiementClient> findByUniqueId(String uniqueId);

    @Query("SELECT p FROM PaiementClient p LEFT JOIN FETCH p.commande WHERE p.client.id = :clientId " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF ORDER BY p.date ASC, p.id ASC")
    List<PaiementClient> findActifsByClientId(@Param("clientId") Long clientId);

    @Query("SELECT p FROM PaiementClient p LEFT JOIN FETCH p.recuPar LEFT JOIN FETCH p.annulePar " +
           "WHERE p.client.id = :clientId ORDER BY p.date DESC, p.id DESC")
    List<PaiementClient> findAllByClientIdForHistorique(@Param("clientId") Long clientId);

    @Query("SELECT p FROM PaiementClient p WHERE p.commande.id = :commandeId ORDER BY p.date ASC, p.id ASC")
    List<PaiementClient> findByCommandeId(@Param("commandeId") Long commandeId);

    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM PaiementClient p WHERE p.client.id = :clientId " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActifsByClientId(@Param("clientId") Long clientId);

    // Comptabilité : encaissé sur une période (bornes toujours concrètes).
    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM PaiementClient p WHERE p.farm.id = :farmId " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND p.date >= :dateDebut AND p.date <= :dateFin")
    Double sumActifsByFarmAndDates(@Param("farmId") Long farmId,
                                   @Param("dateDebut") java.time.LocalDate dateDebut,
                                   @Param("dateFin") java.time.LocalDate dateFin);
}

// repository/ImputationPaiementRepo.java
package com.diafarms.ml.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.models.ImputationPaiement;

public interface ImputationPaiementRepo extends JpaRepository<ImputationPaiement, Long> {
    @Query("SELECT COALESCE(SUM(i.montant), 0) FROM ImputationPaiement i WHERE i.paiement.id = :paiementId " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActivesByPaiementId(@Param("paiementId") Long paiementId);

    @Query("SELECT COALESCE(SUM(i.montant), 0) FROM ImputationPaiement i WHERE i.cibleType = :type " +
           "AND i.cibleUniqueId = :uid AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActivesByCible(@Param("type") CibleImputation type, @Param("uid") String uid);

    // Plus récentes d'abord : c'est dans cet ordre qu'on les annule quand une vente baisse.
    @Query("SELECT i FROM ImputationPaiement i JOIN FETCH i.paiement WHERE i.cibleType = :type " +
           "AND i.cibleUniqueId = :uid AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF ORDER BY i.id DESC")
    List<ImputationPaiement> findActivesByCible(@Param("type") CibleImputation type, @Param("uid") String uid);

    @Query("SELECT i FROM ImputationPaiement i WHERE i.paiement.id = :paiementId " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    List<ImputationPaiement> findActivesByPaiementId(@Param("paiementId") Long paiementId);

    @Query("SELECT COALESCE(SUM(i.montant), 0) FROM ImputationPaiement i WHERE i.client.id = :clientId " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActivesByClientId(@Param("clientId") Long clientId);

    @Query("SELECT COALESCE(SUM(i.montant), 0) FROM ImputationPaiement i WHERE i.client.id = :clientId " +
           "AND i.cibleType <> com.diafarms.ml.enums.CibleImputation.REMBOURSEMENT " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActivesSurVentesByClientId(@Param("clientId") Long clientId);

    // Historique d'une vente ou d'un paiement (annulées comprises).
    @Query("SELECT i FROM ImputationPaiement i JOIN FETCH i.paiement WHERE i.client.id = :clientId ORDER BY i.id DESC")
    List<ImputationPaiement> findAllByClientId(@Param("clientId") Long clientId);
}

// repository/RemboursementClientRepo.java
package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.diafarms.ml.models.RemboursementClient;

public interface RemboursementClientRepo extends JpaRepository<RemboursementClient, Long> {
    Optional<RemboursementClient> findByUniqueId(String uniqueId);

    @Query("SELECT r FROM RemboursementClient r WHERE r.client.id = :clientId ORDER BY r.date DESC, r.id DESC")
    List<RemboursementClient> findAllByClientId(@Param("clientId") Long clientId);

    @Query("SELECT COALESCE(SUM(r.montant), 0) FROM RemboursementClient r WHERE r.farm.id = :farmId " +
           "AND r.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND r.date >= :dateDebut AND r.date <= :dateFin")
    Double sumActifsByFarmAndDates(@Param("farmId") Long farmId,
                                   @Param("dateDebut") java.time.LocalDate dateDebut,
                                   @Param("dateFin") java.time.LocalDate dateFin);
}
```

- [ ] **Step 8: Verrou client.** Dans `ClientRepo.java` :

```java
    // Sérialise les imputations d'un même client (deux paiements saisis en même temps
    // par deux vendeurs ne doivent pas imputer deux fois la même vente).
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Client c WHERE c.id = :id")
    java.util.Optional<Client> findByIdForUpdate(@Param("id") Long id);
```

- [ ] **Step 9: Ventes d'un client pour l'imputation.** Dans `VenteOeufsRepo.java` et
  `VenteReformeRepo.java` (remplacer `VenteOeufs` par `VenteReforme` dans le second) :

```java
    // Ventes actives d'un client, plus anciennes d'abord (ordre d'imputation).
    @Query("SELECT v FROM VenteOeufs v LEFT JOIN FETCH v.commande WHERE v.client.id = :clientId " +
           "AND v.initialisation.removed = false ORDER BY v.date ASC, v.id ASC")
    List<VenteOeufs> findActivesByClientIdPourImputation(@Param("clientId") Long clientId);

    @Query("SELECT COALESCE(SUM(v.montant), 0) FROM VenteOeufs v WHERE v.client.id = :clientId " +
           "AND v.initialisation.removed = false")
    Double sumMontantActifsByClientId(@Param("clientId") Long clientId);

    @Query("SELECT v FROM VenteOeufs v WHERE v.commande.id = :commandeId AND v.initialisation.removed = false ORDER BY v.date ASC, v.id ASC")
    List<VenteOeufs> findActivesByCommandeId(@Param("commandeId") Long commandeId);
```

- [ ] **Step 10: Compiler**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q -o compile`
Expected: aucune sortie (succès).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/diafarms/ml/enums src/main/java/com/diafarms/ml/models src/main/java/com/diafarms/ml/repository
git commit -m "Circuit client: entités paiement, imputation, remboursement et lien vente-commande"
```

---

### Task 2: Moteur d'imputation pur (`CalculImputation`) + tests JUnit

**Files:**
- Create: `src/main/java/com/diafarms/ml/commons/CalculImputation.java`
- Test: `src/test/java/com/diafarms/ml/commons/CalculImputationTest.java`

**Interfaces:**
- Produces:
  - `record Source(String paiementUniqueId, double reste, String commandeUniqueId, String venteCibleUniqueId)`
  - `record Besoin(String cibleType, String cibleUniqueId, double reste, String commandeUniqueId)`
  - `record Affectation(String paiementUniqueId, String cibleType, String cibleUniqueId, double montant)`
  - `static List<Affectation> repartir(List<Source> sources, List<Besoin> besoins)` — sources et besoins **déjà triés** du plus ancien au plus récent.
  - `static List<Affectation> prelever(List<Source> sources, String cibleType, String cibleUniqueId, double montant, String commandeUniqueId)` — pour un remboursement : sources de la commande d'abord, puis les plus récentes.
  - `static double arrondi(double)`

- [ ] **Step 1: Écrire les tests (ils échouent : la classe n'existe pas)**

```java
package com.diafarms.ml.commons;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.diafarms.ml.commons.CalculImputation.*;

class CalculImputationTest {

    private static double total(List<Affectation> a) {
        return a.stream().mapToDouble(Affectation::montant).sum();
    }

    @Test
    void acompteSurPremiereLivraisonPuisResteEnAvance() {
        // Scénario 3 : acompte 40 000, livraison 60 000.
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 40000, "C1", null)),
                List.of(new Besoin("VENTE_OEUFS", "V1", 60000, "C1")));
        assertEquals(1, a.size());
        assertEquals(40000, a.get(0).montant());
        assertEquals("V1", a.get(0).cibleUniqueId());
    }

    @Test
    void plusieursLivraisonsFifo() {
        // Scénario 9 : L1 déjà réglée de 40 000 par l'acompte ; nouveau paiement 60 000.
        var a = CalculImputation.repartir(
                List.of(new Source("P2", 60000, null, null)),
                List.of(new Besoin("VENTE_OEUFS", "L1", 20000, "C1"),
                        new Besoin("VENTE_OEUFS", "L2", 40000, "C1")));
        assertEquals(2, a.size());
        assertEquals("L1", a.get(0).cibleUniqueId());
        assertEquals(20000, a.get(0).montant());
        assertEquals("L2", a.get(1).cibleUniqueId());
        assertEquals(40000, a.get(1).montant());
    }

    @Test
    void venteCibleesPrioritaire() {
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 5000, null, "V2")),
                List.of(new Besoin("VENTE_OEUFS", "V1", 5000, null),
                        new Besoin("VENTE_OEUFS", "V2", 5000, null)));
        assertEquals("V2", a.get(0).cibleUniqueId());
        assertEquals(5000, total(a));
    }

    @Test
    void commandeDuPaiementPrioritaireSurLesAutresVentes() {
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 3000, "C2", null)),
                List.of(new Besoin("VENTE_OEUFS", "VieilleVente", 3000, null),
                        new Besoin("VENTE_REFORME", "LivraisonC2", 3000, "C2")));
        assertEquals("LivraisonC2", a.get(0).cibleUniqueId());
    }

    @Test
    void troppercuResteNonImpute() {
        // Scénario 8 : 40 000 payés, 30 000 livrés.
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 40000, "C1", null)),
                List.of(new Besoin("VENTE_OEUFS", "V1", 30000, "C1")));
        assertEquals(30000, total(a));
    }

    @Test
    void jamaisPlusQueLeResteDUneSourceNiDUnBesoin() {
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 100, null, null), new Source("P2", 100, null, null)),
                List.of(new Besoin("VENTE_OEUFS", "V1", 150, null)));
        assertEquals(150, total(a));
        assertEquals(100, a.get(0).montant());
        assertEquals(50, a.get(1).montant());
    }

    @Test
    void resteNulOuNegatifIgnore() {
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 0, null, null), new Source("P2", -5, null, null)),
                List.of(new Besoin("VENTE_OEUFS", "V1", 100, null)));
        assertTrue(a.isEmpty());
    }

    @Test
    void remboursementPrendDAbordLaCommandePuisLesPlusRecents() {
        var a = CalculImputation.prelever(
                List.of(new Source("Ancien", 10000, null, null),
                        new Source("AcompteC1", 5000, "C1", null),
                        new Source("Recent", 10000, null, null)),
                "REMBOURSEMENT", "R1", 12000, "C1");
        assertEquals("AcompteC1", a.get(0).paiementUniqueId());
        assertEquals(5000, a.get(0).montant());
        assertEquals("Recent", a.get(1).paiementUniqueId());
        assertEquals(7000, a.get(1).montant());
    }

    @Test
    void remboursementSuperieurALAvanceRefuse() {
        assertThrows(IllegalArgumentException.class, () -> CalculImputation.prelever(
                List.of(new Source("P1", 1000, null, null)), "REMBOURSEMENT", "R1", 1500, null));
    }

    @Test
    void arrondiAuCentime() {
        assertEquals(0.3, CalculImputation.arrondi(0.1 + 0.2));
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier l'échec**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q -o test -Dtest=CalculImputationTest`
Expected: échec de compilation (« cannot find symbol CalculImputation »).

- [ ] **Step 3: Implémentation**

```java
package com.diafarms.ml.commons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// Répartition pure (sans base de données) de l'argent disponible des paiements sur ce
// qui reste à régler. CompteClientService charge les données, appelle ceci, enregistre
// le résultat. Toute la règle métier d'ordre de priorité vit ici, testée à part.
public final class CalculImputation {

    private CalculImputation() {}

    public record Source(String paiementUniqueId, double reste, String commandeUniqueId, String venteCibleUniqueId) {}
    public record Besoin(String cibleType, String cibleUniqueId, double reste, String commandeUniqueId) {}
    public record Affectation(String paiementUniqueId, String cibleType, String cibleUniqueId, double montant) {}

    public static double arrondi(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Sources et besoins triés du plus ancien au plus récent. Pour chaque paiement :
     * d'abord la vente qu'il vise, puis les ventes de sa commande, puis les autres. */
    public static List<Affectation> repartir(List<Source> sources, List<Besoin> besoins) {
        double[] restesBesoins = besoins.stream().mapToDouble(b -> arrondi(b.reste())).toArray();
        List<Affectation> out = new ArrayList<>();
        for (Source s : sources) {
            double dispo = arrondi(s.reste());
            if (dispo <= 0) continue;
            for (int idx : ordrePour(s, besoins)) {
                if (dispo <= 0) break;
                double besoin = restesBesoins[idx];
                if (besoin <= 0) continue;
                double m = arrondi(Math.min(dispo, besoin));
                Besoin b = besoins.get(idx);
                out.add(new Affectation(s.paiementUniqueId(), b.cibleType(), b.cibleUniqueId(), m));
                dispo = arrondi(dispo - m);
                restesBesoins[idx] = arrondi(besoin - m);
            }
        }
        return out;
    }

    private static List<Integer> ordrePour(Source s, List<Besoin> besoins) {
        List<Integer> cible = new ArrayList<>(), commande = new ArrayList<>(), autres = new ArrayList<>();
        for (int i = 0; i < besoins.size(); i++) {
            Besoin b = besoins.get(i);
            if (s.venteCibleUniqueId() != null && s.venteCibleUniqueId().equals(b.cibleUniqueId())) cible.add(i);
            else if (s.commandeUniqueId() != null && s.commandeUniqueId().equals(b.commandeUniqueId())) commande.add(i);
            else autres.add(i);
        }
        List<Integer> ordre = new ArrayList<>(cible);
        ordre.addAll(commande);
        ordre.addAll(autres);
        return ordre;
    }

    /** Remboursement : prend l'argent non imputé, d'abord sur les paiements de la
     * commande concernée, puis sur les paiements les plus récents. */
    public static List<Affectation> prelever(List<Source> sources, String cibleType, String cibleUniqueId,
                                             double montant, String commandeUniqueId) {
        double voulu = arrondi(montant);
        double dispoTotal = arrondi(sources.stream().mapToDouble(s -> Math.max(0, s.reste())).sum());
        if (voulu <= 0) throw new IllegalArgumentException("Le montant à rembourser doit être positif.");
        if (voulu > dispoTotal) {
            throw new IllegalArgumentException("Le remboursement (" + voulu + " FCFA) dépasse l'avance disponible du client ("
                    + dispoTotal + " FCFA).");
        }
        List<Source> ordre = new ArrayList<>(sources);
        java.util.Collections.reverse(ordre); // plus récents d'abord
        ordre.sort(Comparator.comparing((Source s) -> !Objects.equals(commandeUniqueId, s.commandeUniqueId())
                || commandeUniqueId == null)); // tri stable : ceux de la commande devant
        List<Affectation> out = new ArrayList<>();
        for (Source s : ordre) {
            if (voulu <= 0) break;
            double dispo = arrondi(s.reste());
            if (dispo <= 0) continue;
            double m = arrondi(Math.min(dispo, voulu));
            out.add(new Affectation(s.paiementUniqueId(), cibleType, cibleUniqueId, m));
            voulu = arrondi(voulu - m);
        }
        return out;
    }
}
```

- [ ] **Step 4: Lancer les tests**

Run: `cd /home/mother/Bureau/diafarms_back && ./mvnw -q -o test -Dtest=CalculImputationTest`
Expected: `Tests run: 10, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/diafarms/ml/commons/CalculImputation.java src/test/java/com/diafarms/ml/commons/CalculImputationTest.java
git commit -m "Circuit client: moteur d'imputation pur et ses tests"
```

---

### Task 3: `CompteClientService` — solde calculé et imputation

**Files:**
- Create: `src/main/java/com/diafarms/ml/DTO/CompteClientDTO.java`
- Create: `src/main/java/com/diafarms/ml/ServiceImpl/CompteClientService.java`
- Modify: `src/main/java/com/diafarms/ml/ServiceImpl/SoldeClientServiceImpl.java` (lecture calculée)

**Interfaces:**
- Consumes: Task 1 (dépôts), Task 2 (`CalculImputation`).
- Produces:
  - `CompteClientDTO compte(Client client)` — `totalVendu, totalPaye, totalRembourse, totalImputeVentes, resteAPayer, avance, solde`.
  - `void imputer(Client client)` — verrouille le client puis crée les imputations manquantes.
  - `void annulerImputationsCible(CibleImputation type, String uid, String motif)`
  - `void ramenerImputationsCible(CibleImputation type, String uid, double nouveauMontant, String motif)`
  - `double resteAPayerVente(CibleImputation type, String uid, double montantVente)`
  - `double payeVente(CibleImputation type, String uid)`
  - `List<CalculImputation.Source> sourcesDisponibles(Client client)`
  - `SoldeClientServiceImpl.getSolde(Client)` et `listNonZero(Farm)` renvoient désormais le solde calculé (même signatures).

- [ ] **Step 1: DTO**

```java
package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// État du compte d'un client, entièrement recalculé (voir CompteClientService).
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
public class CompteClientDTO {
    private String clientUniqueId;
    private String clientNom;
    private double totalVendu;        // Σ ventes actives (œufs + réforme)
    private double totalPaye;         // Σ paiements actifs
    private double totalRembourse;    // Σ remboursements actifs
    private double totalImputeVentes; // Σ imputations actives sur des ventes
    private double resteAPayer;       // totalVendu − totalImputeVentes
    private double avance;            // totalPaye − toutes imputations actives
    private double solde;             // resteAPayer − avance (positif = doit)
}
```

- [ ] **Step 2: Service**

```java
package com.diafarms.ml.ServiceImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.CompteClientDTO;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.StatutMouvement;
import com.diafarms.ml.models.*;
import com.diafarms.ml.repository.*;

import lombok.RequiredArgsConstructor;

// Source unique du compte d'un client : rien n'est stocké, tout se recalcule à partir
// des ventes, paiements, imputations et remboursements.
@Service
@RequiredArgsConstructor
public class CompteClientService {

    private final ClientRepo clientRepo;
    private final PaiementClientRepo paiementRepo;
    private final ImputationPaiementRepo imputationRepo;
    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteReformeRepo venteReformeRepo;

    private static double nz(Double v) { return v == null ? 0.0 : v; }

    @Transactional(readOnly = true)
    public CompteClientDTO compte(Client client) {
        Long id = client.getId();
        double vendu = nz(venteOeufsRepo.sumMontantActifsByClientId(id)) + nz(venteReformeRepo.sumMontantActifsByClientId(id));
        double paye = nz(paiementRepo.sumActifsByClientId(id));
        double imputeVentes = nz(imputationRepo.sumActivesSurVentesByClientId(id));
        double imputeTout = nz(imputationRepo.sumActivesByClientId(id));
        double rembourse = CalculImputation.arrondi(imputeTout - imputeVentes);
        double reste = CalculImputation.arrondi(vendu - imputeVentes);
        double avance = CalculImputation.arrondi(paye - imputeTout);
        return CompteClientDTO.builder()
                .clientUniqueId(client.getUniqueId()).clientNom(client.getNom())
                .totalVendu(CalculImputation.arrondi(vendu)).totalPaye(CalculImputation.arrondi(paye))
                .totalRembourse(rembourse).totalImputeVentes(CalculImputation.arrondi(imputeVentes))
                .resteAPayer(reste).avance(avance).solde(CalculImputation.arrondi(reste - avance))
                .build();
    }

    public double payeVente(CibleImputation type, String uid) {
        return CalculImputation.arrondi(nz(imputationRepo.sumActivesByCible(type, uid)));
    }

    public double resteAPayerVente(CibleImputation type, String uid, double montantVente) {
        return CalculImputation.arrondi(montantVente - payeVente(type, uid));
    }

    @Transactional(readOnly = true)
    public List<CalculImputation.Source> sourcesDisponibles(Client client) {
        List<CalculImputation.Source> sources = new ArrayList<>();
        for (PaiementClient p : paiementRepo.findActifsByClientId(client.getId())) {
            double reste = CalculImputation.arrondi(p.getMontant() - nz(imputationRepo.sumActivesByPaiementId(p.getId())));
            if (reste > 0) {
                sources.add(new CalculImputation.Source(p.getUniqueId(), reste,
                        p.getCommande() != null ? p.getCommande().getUniqueId() : null, p.getVenteCibleUniqueId()));
            }
        }
        return sources;
    }

    /** Impute l'argent disponible du client sur ses ventes non réglées. Idempotent :
     * n'ajoute que ce qui manque. Verrouille la ligne client (deux saisies simultanées). */
    @Transactional
    public void imputer(Client clientNonVerrouille) {
        Client client = clientRepo.findByIdForUpdate(clientNonVerrouille.getId())
                .orElseThrow(() -> new IllegalArgumentException("Client introuvable."));
        List<CalculImputation.Source> sources = sourcesDisponibles(client);
        if (sources.isEmpty()) return;

        List<CalculImputation.Besoin> besoins = new ArrayList<>();
        // Œufs et réforme fusionnés par date puis id : l'ordre d'ancienneté est global.
        record V(java.time.LocalDate date, Long id, CalculImputation.Besoin besoin) {}
        List<V> ventes = new ArrayList<>();
        for (VenteOeufs v : venteOeufsRepo.findActivesByClientIdPourImputation(client.getId())) {
            double reste = resteAPayerVente(CibleImputation.VENTE_OEUFS, v.getUniqueId(), nz(v.getMontant()));
            if (reste > 0) ventes.add(new V(v.getDate(), v.getId(), new CalculImputation.Besoin("VENTE_OEUFS", v.getUniqueId(), reste,
                    v.getCommande() != null ? v.getCommande().getUniqueId() : null)));
        }
        for (VenteReforme v : venteReformeRepo.findActivesByClientIdPourImputation(client.getId())) {
            double reste = resteAPayerVente(CibleImputation.VENTE_REFORME, v.getUniqueId(), nz(v.getMontant()));
            if (reste > 0) ventes.add(new V(v.getDate(), v.getId(), new CalculImputation.Besoin("VENTE_REFORME", v.getUniqueId(), reste,
                    v.getCommande() != null ? v.getCommande().getUniqueId() : null)));
        }
        ventes.sort(java.util.Comparator.comparing(V::date).thenComparing(V::id));
        ventes.forEach(v -> besoins.add(v.besoin()));
        if (besoins.isEmpty()) return;

        for (CalculImputation.Affectation a : CalculImputation.repartir(sources, besoins)) {
            enregistrer(client, a);
        }
    }

    /** Enregistre des affectations déjà calculées (imputer, remboursement). */
    @Transactional
    public void enregistrer(Client client, CalculImputation.Affectation a) {
        PaiementClient p = paiementRepo.findByUniqueId(a.paiementUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Paiement introuvable : " + a.paiementUniqueId()));
        ImputationPaiement i = new ImputationPaiement();
        i.setUniqueId(UUID.randomUUID().toString());
        i.setFarm(client.getFarm());
        i.setClient(client);
        i.setPaiement(p);
        i.setCibleType(CibleImputation.valueOf(a.cibleType()));
        i.setCibleUniqueId(a.cibleUniqueId());
        i.setMontant(a.montant());
        i.setStatut(StatutMouvement.ACTIF);
        i.setInitialisation(Initialisation.init());
        imputationRepo.save(i);
    }

    private void annuler(ImputationPaiement i, String motif) {
        i.setStatut(StatutMouvement.ANNULE);
        i.setMotifAnnulation(motif);
        i.setDateAnnulation(java.time.LocalDateTime.now());
        imputationRepo.save(i);
    }

    /** Vente supprimée, client retiré, paiement annulé... : l'argent retourne en avance. */
    @Transactional
    public void annulerImputationsCible(CibleImputation type, String uid, String motif) {
        for (ImputationPaiement i : imputationRepo.findActivesByCible(type, uid)) annuler(i, motif);
    }

    /** Vente dont le montant baisse : on annule les imputations les plus récentes
     * jusqu'à ne pas dépasser le nouveau montant ; la dernière est recréée plus petite. */
    @Transactional
    public void ramenerImputationsCible(CibleImputation type, String uid, double nouveauMontant, String motif) {
        double exces = CalculImputation.arrondi(payeVente(type, uid) - nouveauMontant);
        if (exces <= 0) return;
        for (ImputationPaiement i : imputationRepo.findActivesByCible(type, uid)) {
            if (exces <= 0) break;
            annuler(i, motif);
            double garde = CalculImputation.arrondi(i.getMontant() - exces);
            if (garde > 0) {
                enregistrer(i.getClient(), new CalculImputation.Affectation(
                        i.getPaiement().getUniqueId(), type.name(), uid, garde));
                exces = 0;
            } else {
                exces = CalculImputation.arrondi(exces - i.getMontant());
            }
        }
    }
}
```

- [ ] **Step 3: `SoldeClientServiceImpl` en lecture calculée.** Remplacer le corps de
  `getSolde` et `listNonZero` pour qu'ils s'appuient sur `CompteClientService.compte`
  (garder les signatures et le DTO renvoyés, `SoldeClientDTO.solde = compte.solde`), et
  faire de `ajusterSolde(...)` une méthode **dépréciée qui ne fait rien** avec le
  commentaire : `// Le solde est désormais calculé (CompteClientService) : plus aucun appel ne doit l'ajuster.`
  Ajouter `double sumSoldePositif(Farm farm)` (somme des soldes > 0 des clients actifs
  de la ferme, via `clientRepo.findAllActiveByFarmId(farm.getId())` + `compte`), et
  `double sumAvances(Farm farm)` (somme des `avance` > 0).

- [ ] **Step 4: Compiler**

Run: `./mvnw -q -o compile`
Expected: succès.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Circuit client: compte client calculé et imputation automatique"
```

---

### Task 4: Paiements et remboursements (services, API)

**Files:**
- Create: `src/main/java/com/diafarms/ml/DTO/PaiementClientDTO.java`, `RemboursementClientDTO.java`, `ImputationDTO.java`
- Create: `src/main/java/com/diafarms/ml/request/create/PaiementClientCreate.java`, `RemboursementClientCreate.java`
- Create: `src/main/java/com/diafarms/ml/ServiceImpl/PaiementClientService.java`
- Create: `src/main/java/com/diafarms/ml/controllers/PaiementClientController.java`
- Modify: `src/main/java/com/diafarms/ml/services/TransactionService.java` + `TransactionServiceImpl.java` (`createMouvementClient`, verrou étendu)

**Interfaces:**
- Consumes: `CompteClientService` (Task 3).
- Produces:
  - `PaiementClientDTO enregistrer(PaiementClientCreate data)` et
    `PaiementClient enregistrerInterne(Client, double montant, ModePaiement, OriginePaiement, Commande, CibleImputation venteCibleType, String venteCibleUid, Facture, String observations, LocalDate date)` (utilisé par Tasks 5-8).
  - `PaiementClientDTO annuler(String uid, String motif)`
  - `RemboursementClientDTO rembourser(RemboursementClientCreate data)` et
    `RemboursementClient rembourserInterne(Client, double, ModePaiement, String motif, Commande)`
  - `RemboursementClientDTO annulerRemboursement(String uid, String motif)`
  - REST : `POST /paiements-client/create`, `PUT /paiements-client/annuler/{uid}` (corps `{motif}`),
    `POST /remboursements-client/create`, `PUT /remboursements-client/annuler/{uid}` (corps `{motif}`),
    `GET /clients/{uid}/compte` → `{compte, paiements[], remboursements[], imputations[]}`.
  - `TransactionService.createMouvementClient(TypeTransaction type, Farm farm, Client client, Double montant, String categorie, LocalDate date, String description, SourceTransaction source, String sourceUid, Utilisateurs creePar)`.

- [ ] **Step 1: Requêtes**

```java
// request/create/PaiementClientCreate.java
package com.diafarms.ml.request.create;
import lombok.Data;
@Data
public class PaiementClientCreate {
    private String clientUniqueId;      // obligatoire
    private Double montant;             // > 0
    private String mode;                // ModePaiement, défaut ESPECES
    private String origine;             // OriginePaiement, défaut REGLEMENT
    private String date;                // yyyy-MM-dd, défaut aujourd'hui
    private String commandeUniqueId;    // facultatif
    private String venteCibleType;      // facultatif : VENTE_OEUFS | VENTE_REFORME
    private String venteCibleUniqueId;  // facultatif
    private String factureUniqueId;     // facultatif (paiement d'une facture)
    private String observations;
}

// request/create/RemboursementClientCreate.java
package com.diafarms.ml.request.create;
import lombok.Data;
@Data
public class RemboursementClientCreate {
    private String clientUniqueId;
    private Double montant;
    private String mode;
    private String motif;               // obligatoire (≥ 3 caractères)
    private String commandeUniqueId;    // facultatif
}
```

- [ ] **Step 2: DTO** — `PaiementClientDTO` (uniqueId, clientUniqueId, clientNom, date,
  montant, mode, origine, commandeUniqueId, venteCibleUniqueId, factureNumero,
  observations, recuParNom, statut, motifAnnulation, annuleParNom, dateAnnulation,
  `double impute` (Σ imputations actives), `double disponible` = montant − impute si
  ACTIF sinon 0) ; `RemboursementClientDTO` (uniqueId, date, montant, mode, motif,
  commandeUniqueId, effectueParNom, statut, motifAnnulation, dateAnnulation) ;
  `ImputationDTO` (uniqueId, paiementUniqueId, paiementDate, cibleType,
  cibleUniqueId, montant, statut, motifAnnulation, createdAt). Chaque DTO a une méthode
  `static fromEntity(...)` au même style que `VenteDiverseDTO`.

- [ ] **Step 3: `TransactionService.createMouvementClient`** — dans l'interface :

```java
    /** Entrée (paiement) ou sortie (remboursement) d'argent d'un client, commune à la
     * ferme, validée, tracée jusqu'à sa source et rattachée au client. */
    TransactionDTO createMouvementClient(TypeTransaction type, Farm farm, com.diafarms.ml.models.Client client,
            Double montant, String categorie, LocalDate date, String description,
            SourceTransaction source, String sourceUniqueId, Utilisateurs creePar);
```

  Implémentation dans `TransactionServiceImpl` (copie de `createSortieCommune` avec
  `t.setType(type)` et `t.setClient(client)`). Étendre le verrou comptable :

```java
    private static boolean isSourceVerrouillee(SourceTransaction s) {
        return TransactionDTO.isSourceVente(s) || s == SourceTransaction.PAIEMENT_CLIENT
                || s == SourceTransaction.REMBOURSEMENT_CLI;
    }

    private void ensurePasLieeAUneVente(Transaction t) {
        if (TransactionDTO.isSourceVente(t.getSourceType())) {
            throw new IllegalArgumentException("Cette transaction vient d'une vente : modifiez ou supprimez la vente depuis la page Ventes.");
        }
        if (t.getSourceType() == SourceTransaction.PAIEMENT_CLIENT || t.getSourceType() == SourceTransaction.REMBOURSEMENT_CLI) {
            throw new IllegalArgumentException("Cette transaction vient d'un paiement ou d'un remboursement client : annulez-le depuis la fiche du client.");
        }
    }
```

  Dans `TransactionDTO.fromEntity`, `lieeAUneVente` devient `verrouillee` :
  `.verrouillee(isSourceVente(...) || source == PAIEMENT_CLIENT || source == REMBOURSEMENT_CLI)`
  (garder `lieeAUneVente` pour les seules ventes, ajouter `verrouillee`). Supprimer dans
  `TransactionServiceImpl.deleteOrRecover` le bloc `if (t.getClient() != null) soldeClientService.ajusterSolde(...)` :
  le solde n'est plus un compteur.

- [ ] **Step 4: `PaiementClientService`**

```java
package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.*;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.*;
import com.diafarms.ml.models.*;
import com.diafarms.ml.repository.*;
import com.diafarms.ml.request.create.PaiementClientCreate;
import com.diafarms.ml.request.create.RemboursementClientCreate;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaiementClientService {

    private final PaiementClientRepo paiementRepo;
    private final RemboursementClientRepo remboursementRepo;
    private final ImputationPaiementRepo imputationRepo;
    private final ClientRepo clientRepo;
    private final CommandeRepo commandeRepo;
    private final FactureRepo factureRepo;
    private final CompteClientService compteClientService;
    private final TransactionService transactionService;
    private final LogsServices logs;
    private final OtherService otherService;

    private Utilisateurs user() {
        try { return otherService.getCurrentUser(); } catch (Exception e) { return null; }
    }
    private boolean hasRole(Utilisateurs u, String r) {
        return u != null && u.getRoles() != null && u.getRoles().stream().anyMatch(x -> r.equalsIgnoreCase(x.getRole()));
    }
    private boolean isAdmin(Utilisateurs u) { return hasRole(u, "ADMIN") || hasRole(u, "SUPER_ADMIN"); }
    private void ensureCanEncaisser(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE") && !hasRole(u, "VENTE"))
            throw new IllegalArgumentException("Vous n'avez pas les droits pour enregistrer un paiement client.");
    }
    private void ensureCanAnnuler(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE"))
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut annuler un paiement.");
    }
    private void ensureCanRembourser(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE"))
            throw new IllegalArgumentException("Vous n'avez pas les droits pour rembourser un client.");
    }

    private Client client(String uid, Utilisateurs u) {
        Client c = clientRepo.findByUniqueId(uid);
        if (c == null || u == null || u.getFarm() == null || !c.getFarm().getId().equals(u.getFarm().getId()))
            throw new IllegalArgumentException("Client introuvable : " + uid);
        return c;
    }

    private static ModePaiement mode(String raw) {
        if (raw == null || raw.isBlank()) return ModePaiement.ESPECES;
        try { return ModePaiement.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Mode de paiement inconnu : " + raw); }
    }

    @Transactional
    public PaiementClientDTO enregistrer(PaiementClientCreate d) {
        Utilisateurs u = user();
        ensureCanEncaisser(u);
        Client c = client(d.getClientUniqueId(), u);
        Commande commande = d.getCommandeUniqueId() == null || d.getCommandeUniqueId().isBlank() ? null
                : commandeRepo.findByUniqueId(d.getCommandeUniqueId());
        Facture facture = d.getFactureUniqueId() == null || d.getFactureUniqueId().isBlank() ? null
                : factureRepo.findByUniqueId(d.getFactureUniqueId()).orElseThrow(() -> new IllegalArgumentException("Facture introuvable."));
        OriginePaiement origine = d.getOrigine() == null || d.getOrigine().isBlank() ? OriginePaiement.REGLEMENT
                : OriginePaiement.valueOf(d.getOrigine().trim().toUpperCase());
        CibleImputation cibleType = d.getVenteCibleType() == null || d.getVenteCibleType().isBlank() ? null
                : CibleImputation.valueOf(d.getVenteCibleType().trim().toUpperCase());
        PaiementClient p = enregistrerInterne(c, d.getMontant(), mode(d.getMode()), origine, commande, cibleType,
                d.getVenteCibleUniqueId(), facture, d.getObservations(),
                d.getDate() == null || d.getDate().isBlank() ? LocalDate.now() : LocalDate.parse(d.getDate()));
        return PaiementClientDTO.fromEntity(p, CalculImputation.arrondi(imputationRepo.sumActivesByPaiementId(p.getId())));
    }

    /** Point d'entrée unique de tout argent reçu d'un client (acompte, livraison,
     * vente, règlement, facture, reprise). Crée la transaction puis impute. */
    @Transactional
    public PaiementClient enregistrerInterne(Client c, Double montant, ModePaiement mode, OriginePaiement origine,
            Commande commande, CibleImputation venteCibleType, String venteCibleUid, Facture facture,
            String observations, LocalDate date) {
        if (montant == null || CalculImputation.arrondi(montant) <= 0)
            throw new IllegalArgumentException("Le montant payé doit être positif.");
        Utilisateurs u = user();
        PaiementClient p = new PaiementClient();
        p.setUniqueId(UUID.randomUUID().toString());
        p.setFarm(c.getFarm());
        p.setClient(c);
        p.setDate(date != null ? date : LocalDate.now());
        p.setMontant(CalculImputation.arrondi(montant));
        p.setMode(mode != null ? mode : ModePaiement.ESPECES);
        p.setOrigine(origine);
        p.setCommande(commande);
        p.setVenteCibleType(venteCibleType);
        p.setVenteCibleUniqueId(venteCibleUid);
        p.setFacture(facture);
        p.setObservations(observations);
        p.setRecuPar(u);
        p.setStatut(StatutMouvement.ACTIF);
        p.setInitialisation(Initialisation.init());
        PaiementClient saved = paiementRepo.save(p);

        transactionService.createMouvementClient(TypeTransaction.ENTREE, c.getFarm(), c, saved.getMontant(),
                "Paiement client", saved.getDate(),
                "Paiement de " + c.getNom() + " (" + libelleOrigine(origine) + ", " + saved.getMode() + ")",
                SourceTransaction.PAIEMENT_CLIENT, saved.getUniqueId(), u);

        compteClientService.imputer(c);
        if (u != null) logs.addLogs(u.getId(), saved.getId(), "PaiementClient",
                "Paiement de " + saved.getMontant() + " FCFA reçu de " + c.getNom() + " (" + origine + ")");
        return saved;
    }

    private static String libelleOrigine(OriginePaiement o) {
        return switch (o) {
            case ACOMPTE -> "acompte"; case LIVRAISON -> "à la livraison"; case VENTE -> "à la vente";
            case REGLEMENT -> "règlement"; case FACTURE -> "facture"; case REPRISE -> "reprise";
        };
    }

    @Transactional
    public PaiementClientDTO annuler(String uid, String motifBrut) {
        Utilisateurs u = user();
        ensureCanAnnuler(u);
        String motif = MotifSuppressionRequest.exiger(motifBrut);
        PaiementClient p = paiementRepo.findByUniqueId(uid).orElseThrow(() -> new IllegalArgumentException("Paiement introuvable."));
        if (p.getStatut() == StatutMouvement.ANNULE) throw new IllegalArgumentException("Ce paiement est déjà annulé.");
        List<ImputationPaiement> imps = imputationRepo.findActivesByPaiementId(p.getId());
        if (imps.stream().anyMatch(i -> i.getCibleType() == CibleImputation.REMBOURSEMENT))
            throw new IllegalArgumentException("Une partie de ce paiement a été remboursée au client : annulez d'abord le remboursement.");
        for (ImputationPaiement i : imps) {
            i.setStatut(StatutMouvement.ANNULE);
            i.setMotifAnnulation("Paiement annulé : " + motif);
            i.setDateAnnulation(LocalDateTime.now());
            imputationRepo.save(i);
        }
        p.setStatut(StatutMouvement.ANNULE);
        p.setMotifAnnulation(motif);
        p.setAnnulePar(u);
        p.setDateAnnulation(LocalDateTime.now());
        paiementRepo.save(p);
        transactionService.setRemovedBySource(p.getUniqueId(), true);
        compteClientService.imputer(p.getClient()); // les autres paiements recouvrent si possible
        if (u != null) logs.addLogs(u.getId(), p.getId(), "PaiementClient", "Paiement annulé — motif : " + motif);
        return PaiementClientDTO.fromEntity(p, 0);
    }

    @Transactional
    public RemboursementClientDTO rembourser(RemboursementClientCreate d) {
        Utilisateurs u = user();
        ensureCanRembourser(u);
        Client c = client(d.getClientUniqueId(), u);
        Commande commande = d.getCommandeUniqueId() == null || d.getCommandeUniqueId().isBlank() ? null
                : commandeRepo.findByUniqueId(d.getCommandeUniqueId());
        return RemboursementClientDTO.fromEntity(rembourserInterne(c, d.getMontant(), mode(d.getMode()),
                MotifSuppressionRequest.exiger(d.getMotif()), commande));
    }

    @Transactional
    public RemboursementClient rembourserInterne(Client clientNonVerrouille, Double montant, ModePaiement mode,
                                                 String motif, Commande commande) {
        Client c = clientRepo.findByIdForUpdate(clientNonVerrouille.getId()).orElseThrow();
        Utilisateurs u = user();
        RemboursementClient r = new RemboursementClient();
        r.setUniqueId(UUID.randomUUID().toString());
        // Calcul AVANT l'enregistrement : prelever() refuse un montant > avance.
        List<CalculImputation.Affectation> prises = CalculImputation.prelever(
                compteClientService.sourcesDisponibles(c), "REMBOURSEMENT", r.getUniqueId(),
                montant == null ? 0 : montant, commande != null ? commande.getUniqueId() : null);
        r.setFarm(c.getFarm());
        r.setClient(c);
        r.setCommande(commande);
        r.setDate(LocalDate.now());
        r.setMontant(CalculImputation.arrondi(montant));
        r.setMode(mode != null ? mode : ModePaiement.ESPECES);
        r.setMotif(motif);
        r.setEffectuePar(u);
        r.setStatut(StatutMouvement.ACTIF);
        r.setInitialisation(Initialisation.init());
        RemboursementClient saved = remboursementRepo.save(r);
        prises.forEach(a -> compteClientService.enregistrer(c, a));
        transactionService.createMouvementClient(TypeTransaction.SORTIE, c.getFarm(), c, saved.getMontant(),
                "Remboursement au client", saved.getDate(), "Remboursement à " + c.getNom() + " — " + motif,
                SourceTransaction.REMBOURSEMENT_CLI, saved.getUniqueId(), u);
        if (u != null) logs.addLogs(u.getId(), saved.getId(), "RemboursementClient",
                "Remboursement de " + saved.getMontant() + " FCFA à " + c.getNom() + " — motif : " + motif);
        return saved;
    }

    @Transactional
    public RemboursementClientDTO annulerRemboursement(String uid, String motifBrut) {
        Utilisateurs u = user();
        ensureCanAnnuler(u);
        String motif = MotifSuppressionRequest.exiger(motifBrut);
        RemboursementClient r = remboursementRepo.findByUniqueId(uid).orElseThrow(() -> new IllegalArgumentException("Remboursement introuvable."));
        if (r.getStatut() == StatutMouvement.ANNULE) throw new IllegalArgumentException("Ce remboursement est déjà annulé.");
        compteClientService.annulerImputationsCible(CibleImputation.REMBOURSEMENT, r.getUniqueId(), "Remboursement annulé : " + motif);
        r.setStatut(StatutMouvement.ANNULE);
        r.setMotifAnnulation(motif);
        r.setAnnulePar(u);
        r.setDateAnnulation(LocalDateTime.now());
        remboursementRepo.save(r);
        transactionService.setRemovedBySource(r.getUniqueId(), true);
        compteClientService.imputer(r.getClient());
        if (u != null) logs.addLogs(u.getId(), r.getId(), "RemboursementClient", "Remboursement annulé — motif : " + motif);
        return RemboursementClientDTO.fromEntity(r);
    }
}
```

  Ajouter `Optional<Facture> findByUniqueId(String)` dans `FactureRepo` s'il n'existe pas.

- [ ] **Step 5: Contrôleur** `PaiementClientController` (même style de réponses
  `ApiResponse` que `VenteDiverseControllers` : 201 à la création, 400 avec
  `List.of(e.getMessage())` sur `IllegalArgumentException`, 500 sinon) :

```java
@RestController
@RequestMapping("/diafarms/api/v1")
@RequiredArgsConstructor
public class PaiementClientController {
    private final PaiementClientService service;
    private final CompteClientService compteService;
    private final ClientRepo clientRepo;
    private final PaiementClientRepo paiementRepo;
    private final RemboursementClientRepo remboursementRepo;
    private final ImputationPaiementRepo imputationRepo;

    @PostMapping("/paiements-client/create")
    public ResponseEntity<ApiResponse<PaiementClientDTO>> create(@RequestBody PaiementClientCreate r) { /* service.enregistrer */ }

    @PutMapping("/paiements-client/annuler/{uid}")
    public ResponseEntity<ApiResponse<PaiementClientDTO>> annuler(@PathVariable String uid,
            @RequestBody(required = false) MotifSuppressionRequest r) { /* service.annuler(uid, r != null ? r.getMotif() : null) */ }

    @PostMapping("/remboursements-client/create")
    public ResponseEntity<ApiResponse<RemboursementClientDTO>> rembourser(@RequestBody RemboursementClientCreate r) { /* service.rembourser */ }

    @PutMapping("/remboursements-client/annuler/{uid}")
    public ResponseEntity<ApiResponse<RemboursementClientDTO>> annulerRemb(@PathVariable String uid,
            @RequestBody(required = false) MotifSuppressionRequest r) { /* service.annulerRemboursement */ }

    // Compte complet d'un client : chiffres + historique (annulés compris).
    @GetMapping("/clients/{uid}/compte")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> compte(@PathVariable String uid) {
        // client (même ferme que l'utilisateur, sinon 400), puis :
        // "compte" -> compteService.compte(c)
        // "paiements" -> paiementRepo.findAllByClientIdForHistorique(id) mappés avec impute
        // "remboursements" -> remboursementRepo.findAllByClientId(id)
        // "imputations" -> imputationRepo.findAllByClientId(id)
    }
}
```

  Écrire chaque méthode avec le bloc `try { return ApiResponse.createResponse(...) }
  catch (IllegalArgumentException e) {...} catch (Exception e) {...}` identique à
  `VenteDiverseControllers.create`.

- [ ] **Step 6: Compiler et commit**

Run: `./mvnw -q -o compile` → succès.

```bash
git add -A && git commit -m "Circuit client: paiements et remboursements (service, API), transactions verrouillées"
```

---

### Task 5: Brancher les anciens chemins (compatibilité)

**Files:**
- Modify: `ServiceImpl/ClientServiceImpl.java` (`payerDette`, `rembourser`, `getReport`, `list`)
- Modify: `ServiceImpl/SoldeClientServiceImpl.java` (appelants de `ajusterSolde`)
- Modify: `DTO/ClientReportDTO.java`, `DTO/ClientVenteLigneDTO.java`

**Interfaces:**
- Consumes: `PaiementClientService.enregistrerInterne`, `rembourserInterne`, `CompteClientService.compte/payeVente`.
- Produces: `ClientReportDTO` enrichi (`compte`, lignes avec `paye`, `resteAPayer`, `statutPaiement`), mêmes routes qu'avant.

- [ ] **Step 1: `payerDette`** — garder les deux signatures publiques ; le corps devient :

```java
        Client client = clientRepo.findByUniqueId(uniqueId);
        if (client == null) throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        OriginePaiement origine = "Acompte client".equals(categorie) ? OriginePaiement.ACOMPTE
                : (description != null && description.startsWith("Paiement facture")) ? OriginePaiement.FACTURE
                : OriginePaiement.REGLEMENT;
        paiementClientService.enregistrerInterne(client, montant, ModePaiement.ESPECES, origine,
                null, null, null, null, description, java.time.LocalDate.now());
        ClientDTO dto = ClientDTO.fromEntity(client);
        dto.setSolde(compteClientService.compte(client).getSolde());
        return dto;
```

  (supprimer la création manuelle de `TransactionCreate` et l'appel `ajusterSolde`).
  Injecter `PaiementClientService` avec `@Lazy` pour éviter un cycle
  (`PaiementClientService` → `TransactionService`, `ClientServiceImpl` ← `CommandeServiceImpl`).

- [ ] **Step 2: `rembourser`** → `paiementClientService.rembourserInterne(client, montant, ModePaiement.ESPECES,
  (description != null && description.trim().length() >= 3) ? description.trim() : "Remboursement d'une avance", null)`,
  puis renvoyer le `ClientDTO` avec le solde calculé.

- [ ] **Step 3: `getReport`** — reconstruire à partir du modèle :
  - lignes « vente » (œufs, réforme) : `montant`, `paye = compteClientService.payeVente(...)`,
    `resteAPayer`, `statutPaiement` (`PAYEE` si reste ≤ 0, `PARTIELLE` si payé > 0, sinon `NON_PAYEE`),
    `commandeUniqueId`, `type` ;
  - lignes « PAIEMENT » : paiements (`montant`, `mode`, `origine`, `statut`) ;
  - lignes « REMBOURSEMENT » : remboursements, montant affiché **négatif** ;
  - `totalAchete = compte.totalVendu`, `totalPaye = compte.totalPaye`, `solde = compte.solde`,
    nouveau champ `compte` (le `CompteClientDTO`).
  Ajouter à `ClientVenteLigneDTO` : `Double paye; Double resteAPayer; String statutPaiement;
  String mode; String origine; String statut; String commandeUniqueId;`.

- [ ] **Step 4: Supprimer tous les appels d'écriture du solde client.**

Run: `grep -rn "soldeClientService.ajusterSolde\|ajusterSolde(client\|ajusterEcart(" src/main/java`
Expected après la tâche : seuls restent les appels `ajusterEcart` des ventes (traités Task 6).

- [ ] **Step 5: Compiler et commit**

```bash
./mvnw -q -o compile && git commit -am "Circuit client: payer-dette et rembourser passent par le modèle paiement; rapport client recalculé"
```

---

### Task 6: Ventes avec client — paiement à la vente, corrections, suppressions

**Files:**
- Modify: `ServiceImpl/VenteOeufsImpl.java`, `ServiceImpl/VenteReformeImpl.java`
- Modify: `ServiceImpl/TransactionServiceImpl.java` (`enrichMontantReel`, `ratio`)
- Modify: `repository/VenteOeufsRepartitionRepo.java`, `VenteReformeRepartitionRepo.java` (`findRatiosByUniqueIds` renvoie aussi `clientId`)
- Modify: `DTO/RepartitionRatioDTO.java`, `DTO/VenteLigneDTO.java`, `ServiceImpl/VenteListeImpl.java`

**Interfaces:**
- Consumes: `CompteClientService`, `PaiementClientService.enregistrerInterne`.
- Produces: `VenteLigneDTO.paye`, `resteAPayer`, `statutPaiement`, `commandeUniqueId` ;
  création d'une vente avec client et `montantRapporte` = paiement `VENTE`.

- [ ] **Step 1: Création.** Dans `VenteOeufsImpl.create` (et l'équivalent réforme),
  remplacer :

```java
        v.setMontantRapporte(data.getMontantRapporte());
```
  par :
```java
        // Vente AVEC client : l'argent reçu est un paiement client (voir PaiementClient) ;
        // montantRapporte ne sert plus qu'au contrôle du vendeur sur une vente SANS client.
        v.setMontantRapporte(client == null ? data.getMontantRapporte() : null);
```
  et remplacer le bloc `if (data.getMontantRapporte() != null) { ajusterEcart(...) }` par :
```java
        if (client == null) {
            if (data.getMontantRapporte() != null) {
                soldeVendeurService.ajusterSolde(currentUser, farm, data.getMontant() - data.getMontantRapporte());
            }
        } else {
            if (data.getMontantRapporte() != null && data.getMontantRapporte() > 0) {
                paiementClientService.enregistrerInterne(client, data.getMontantRapporte(),
                        modeOuEspeces(data.getModePaiement()), OriginePaiement.VENTE, saved.getCommande(),
                        CibleImputation.VENTE_OEUFS, saved.getUniqueId(), null, null, saved.getDate());
            } else {
                compteClientService.imputer(client); // une avance éventuelle règle cette vente
            }
        }
```
  Ajouter `private String modePaiement;` (facultatif) à `VenteOeufsCreate`/`VenteReformeCreate`
  et un helper `modeOuEspeces(String)`. Supprimer la méthode `ajusterEcart` (plus
  d'appelant côté client ; les appels vendeur passent par `soldeVendeurService` direct).

- [ ] **Step 2: Modification (`update`).**
  - Si la vente a un client (avant ou après) : **refuser** un `montantRapporte` non nul
    avec le message « Pour une vente à un client, enregistrez un paiement depuis la fiche
    client. » (plus de troisième porte cachée).
  - Si le client change : `compteClientService.annulerImputationsCible(type, uid, "Client de la vente modifié")`,
    puis après sauvegarde `imputer(ancienClient)` (s'il existe) et `imputer(nouveauClient)`.
  - Si le montant baisse : `ramenerImputationsCible(type, uid, nouveauMontant, "Montant de la vente corrigé")`
    puis `imputer(client)` ; s'il monte : `imputer(client)`.
  - Vente sans client : comportement actuel conservé (écart au solde vendeur).

- [ ] **Step 3: Suppression / restauration.** Dans `deleteOrRecover` et
  `confirmerSuppression` : si la vente a un client, au lieu de `ajusterEcart` :
  suppression → `annulerImputationsCible(type, uid, "Vente supprimée : " + motif)` puis
  `imputer(client)` (l'argent libéré peut régler d'autres ventes) ; restauration →
  `imputer(client)`. Sans client : comportement vendeur actuel.

- [ ] **Step 4: Réel par transaction.** Dans `enrichMontantReel`, pour une vente avec
  client le ratio devient `payé / montant` :

```java
    private double ratio(RepartitionRatioDTO r) {
        if (r.getVenteMontant() == null || r.getVenteMontant() == 0) return 1.0;
        if (r.getClientId() != null) {
            double paye = compteClientService.payeVente(
                    r.getVenteType() == null ? CibleImputation.VENTE_OEUFS : CibleImputation.valueOf(r.getVenteType()),
                    r.getVenteUniqueId());
            return Math.min(1.0, paye / r.getVenteMontant());
        }
        double rapporte = r.getVenteMontantRapporte() != null ? r.getVenteMontantRapporte() : r.getVenteMontant();
        return rapporte / r.getVenteMontant();
    }
```
  Ajouter `Long clientId` et `String venteType` à `RepartitionRatioDTO` et aux deux
  requêtes `findRatiosByUniqueIds` (`c.id` et la constante `'VENTE_OEUFS'`/`'VENTE_REFORME'`).
  `CompteClientService` est injecté avec `@Lazy` dans `TransactionServiceImpl`.

- [ ] **Step 5: Liste des ventes.** `VenteListeImpl` : pour les ventes avec client,
  `montantReel = payé`, `montantRapporte = null`, nouveaux champs `paye`,
  `resteAPayer`, `statutPaiement`, `commandeUniqueId` (null pour les diverses, sans
  client : `statutPaiement = "COMPTANT"`).

- [ ] **Step 6: Compiler et commit**

```bash
./mvnw -q -o compile && git commit -am "Circuit client: ventes avec client payées par paiement client, imputations suivies sur modification et suppression"
```

---

### Task 7: Commandes — acompte, livraisons, clôture, annulation

**Files:**
- Modify: `models/Commande.java` (statuts, motif)
- Modify: `ServiceImpl/CommandeServiceImpl.java`, `services/CommandeService.java`
- Modify: `controllers/CommandeController.java`
- Modify: `DTO/CommandeDTO.java`
- Create: `request/others/AnnulationCommandeRequest.java`

**Interfaces:**
- Consumes: `PaiementClientService`, `CompteClientService`.
- Produces: `POST /commandes/{uid}/livrer?quantite=&montantRecu=&mode=`,
  `POST /commandes/{uid}/paiement` (corps `PaiementClientCreate` sans client),
  `PUT /commandes/{uid}/cloturer` (corps `{motif}`), `PUT /commandes/{uid}/annuler`
  (corps `{motif, rembourserAcompte, mode}`) ; `CommandeDTO` enrichi.

- [ ] **Step 1: Statuts et motif** dans `Commande` :

```java
    public enum StatutCommande { EN_ATTENTE, CONFIRMEE, EN_LIVRAISON, CONVERTIE, CLOTUREE, ANNULEE }

    // Motif de clôture (livrée en partie) ou d'annulation (rien livré).
    @Column(name = "motif_fin", columnDefinition = "TEXT")
    private String motifFin;
```
  (`CONVERTIE` garde son nom en base, libellé « Livrée » à l'écran.)

- [ ] **Step 2: Acompte.** `create` : remplacer l'appel `clientService.payerDette(...)` par
  `paiementClientService.enregistrerInterne(client, acompte, mode(data.getModePaiement()), OriginePaiement.ACOMPTE, saved, null, null, null, "Acompte sur commande", saved.getDateCommande())`.
  Ajouter `private String modePaiement;` à `CommandeCreate`. `update` : refuser toute
  modification de `montantAcompte` (« Un acompte supplémentaire s'enregistre comme un
  paiement sur la commande ») ; `montantAcompte` n'est plus écrit après la création
  (il reste un historique du premier acompte).

- [ ] **Step 3: Livraison.** Dans `livrer(uid, quantite, montantRecu, mode)` :
  - refuser si statut `CLOTUREE`, `ANNULEE` ou `CONVERTIE` ;
  - `VenteOeufsCreate`/`VenteReformeCreate` : `montantRapporte = null`, `modePaiement = null` ;
    après création, lire la vente et `vente.setCommande(c)` puis sauvegarder ;
  - si `montantRecu > 0` : `enregistrerInterne(client, montantRecu, mode, LIVRAISON, c, typeCible, venteUid, null, null, today)` ;
  - sinon `compteClientService.imputer(client)` (l'acompte règle la livraison) ;
  - statut : `CONVERTIE` si tout est livré, sinon `EN_LIVRAISON` ;
  - ne plus écrire `c.setVenteUniqueId(...)` (champ conservé en lecture pour l'historique).
  `convertirEnVente(uid)` = `livrer(uid, null, 0.0, null)`.

- [ ] **Step 4: Clôture et annulation.**

```java
    @Transactional
    public CommandeDTO cloturer(String uid, String motifBrut) {
        Utilisateurs u = getCurrentUserSafe();
        ensureCanDecider(u); // ADMIN, SUPER_ADMIN, RESPONSABLE
        String motif = MotifSuppressionRequest.exiger(motifBrut);
        Commande c = trouver(uid);
        if (nz(c.getQuantiteLivree()) == 0) throw new IllegalArgumentException("Rien n'a été livré : annulez la commande au lieu de la clôturer.");
        if (c.getStatut() == StatutCommande.CONVERTIE || c.getStatut() == StatutCommande.CLOTUREE || c.getStatut() == StatutCommande.ANNULEE)
            throw new IllegalArgumentException("Cette commande est déjà terminée.");
        c.setStatut(StatutCommande.CLOTUREE);
        c.setMotifFin(motif);
        // Un éventuel trop-perçu reste en avance du client (visible sur sa fiche).
        return enrichir(commandeRepo.save(c));
    }

    @Transactional
    public CommandeDTO annuler(String uid, String motifBrut, boolean rembourserAcompte, String mode) {
        Utilisateurs u = getCurrentUserSafe();
        ensureCanDecider(u);
        String motif = MotifSuppressionRequest.exiger(motifBrut);
        Commande c = trouver(uid);
        if (nz(c.getQuantiteLivree()) > 0) throw new IllegalArgumentException("Cette commande a déjà été livrée en partie : clôturez-la au lieu de l'annuler.");
        if (c.getStatut() == StatutCommande.ANNULEE) throw new IllegalArgumentException("Cette commande est déjà annulée.");
        c.setStatut(StatutCommande.ANNULEE);
        c.setMotifFin(motif);
        commandeRepo.save(c);
        if (rembourserAcompte) {
            double disponible = compteClientService.sourcesDisponibles(c.getClient()).stream()
                    .filter(s -> c.getUniqueId().equals(s.commandeUniqueId()))
                    .mapToDouble(CalculImputation.Source::reste).sum();
            if (disponible > 0) paiementClientService.rembourserInterne(c.getClient(), disponible,
                    modeOuEspeces(mode), "Annulation de la commande — " + motif, c);
        }
        return enrichir(c);
    }
```
  `ensureCanDecider` : ADMIN, SUPER_ADMIN, RESPONSABLE. Remplacer l'ancien
  `annuler(uid)` du contrôleur par le corps `AnnulationCommandeRequest { motif; Boolean rembourserAcompte; String mode; }`.

- [ ] **Step 5: `CommandeDTO` enrichi** par `enrichir(Commande)` : `montantLivre`
  (Σ ventes actives de la commande), `acompteRecu` (Σ paiements ACTIFS d'origine ACOMPTE
  de la commande), `payeSurCommande` (Σ imputations actives sur ses ventes),
  `resteAPayerLivre` (montantLivre − payeSurCommande), `resteALivrer` (quantité),
  `livraisons` (liste `{venteUniqueId, date, quantite, montant, paye, statutPaiement}`),
  `motifFin`, `statutLibelle` (« Livrée » pour `CONVERTIE`).

- [ ] **Step 6: Compiler et commit**

```bash
./mvnw -q -o compile && git commit -am "Circuit client: commandes avec livraisons multiples, acompte en paiement, clôture et annulation avec motif"
```

---

### Task 8: Factures — lignes, paiement calculé, annulation

**Files:**
- Create: `models/FactureLigne.java`, `repository/FactureLigneRepo.java`
- Modify: `models/Facture.java` (source `VENTES`, statut `EMISE`/`ANNULEE`, `legacy`, `motifAnnulation`)
- Modify: `ServiceImpl/FactureServiceImpl.java`, `services/FactureService.java`, `controllers/FactureController.java`, `DTO/FactureDTO.java`, `request/create/FactureGenerateRequest.java`
- Modify: la génération PDF de facture (dans `FactureServiceImpl` / méthode `pdf`) pour lister les lignes

**Interfaces:**
- Consumes: `CompteClientService.payeVente`, `PaiementClientService.enregistrerInterne`.
- Produces: `POST /factures/generer` accepte `{clientUniqueId, ventes: [{type, uniqueId}]}` ou `{sourceType: "COMMANDE", sourceUniqueId}` ;
  `POST /factures/{uid}/paiement` (corps `{montant, mode, date}`) ;
  `PUT /factures/{uid}/annuler` (corps `{motif}`) ;
  `PUT /factures/{uid}/marquer-payee` conservé = adaptateur vers `/paiement`.

- [ ] **Step 1: `FactureLigne`** — `id, uniqueId, facture (ManyToOne, nullable=false),
  venteType (CibleImputation), venteUniqueId (length 50), description (TEXT), quantite,
  prixUnitaire, montant, initialisation`. Dépôt :
  `List<FactureLigne> findByFacture_Id(Long)` et
  `@Query("SELECT COUNT(l) > 0 FROM FactureLigne l WHERE l.venteType = :t AND l.venteUniqueId = :u AND l.facture.statut <> com.diafarms.ml.models.Facture.StatutFacture.ANNULEE") boolean venteDejaFacturee(...)`.

- [ ] **Step 2: `Facture`** : `SourceFacture { VENTE_OEUFS, VENTE_REFORME, COMMANDE, VENTES }`,
  `StatutFacture { IMPAYEE, PARTIELLE, PAYEE, ANNULEE }` (IMPAYEE = « Émise » à l'écran),
  `@Column(nullable=false, columnDefinition="boolean not null default false") private Boolean legacy = false;`
  (vrai pour les factures d'avant la refonte : leur `montantPaye` stocké reste affiché),
  `@Column(name="motif_annulation", columnDefinition="TEXT") private String motifAnnulation;`.

- [ ] **Step 3: Génération.** Nouvelle factures non-legacy :
  - depuis une liste de ventes : toutes du même client, actives, non déjà facturées
    (`venteDejaFacturee`), sinon 400 avec la vente fautive ;
  - depuis une commande : ses ventes actives (livraisons) non encore facturées ; 400
    « Aucune livraison à facturer » si vide ;
  - une ligne par vente (description, quantité, prix, montant figés) ;
    `montantTotal = Σ lignes`, `montantPaye` non utilisé (laissé à 0).
  - `sourceType = VENTES` (ou `COMMANDE` avec `sourceUniqueId` de la commande).

- [ ] **Step 4: Paiement et statut calculés.** Dans `FactureDTO.fromEntity(f, lignes, payeCalcule)` :
  `montantPaye = legacy ? f.getMontantPaye() : Σ min(ligne.montant, payeVente(ligne))`,
  `resteAPayer`, `statut` : ANNULEE si annulée, sinon PAYEE / PARTIELLE / IMPAYEE
  d'après `montantPaye`. `lignes` exposées.
  `payer(uid, montant, mode, date)` : refuse une facture annulée ou déjà payée ; plafonne
  au reste ; crée un paiement `FACTURE` avec `facture = f` et
  `venteCible` = première ligne non soldée (les suivantes sont servies par l'ordre
  commande/ancienneté). **Supprimer `propagerPaiementVersVente`.**
  `marquerPayee(uid, montant)` → `payer(uid, montant, "ESPECES", null)`.

- [ ] **Step 5: Annulation** : ADMIN/RESPONSABLE, motif obligatoire, statut ANNULEE ;
  les ventes redeviennent facturables ; les paiements déjà faits ne bougent pas.

- [ ] **Step 6: PDF** : le tableau du PDF liste les lignes (legacy : une ligne reconstruite
  depuis les champs existants), puis Total / Payé / Reste.

- [ ] **Step 7: Compiler et commit**

```bash
./mvnw -q -o compile && git add -A && git commit -m "Circuit client: factures multi-ventes, payé calculé, paiement via paiement client, annulation"
```

---

### Task 9: Comptabilité — Vendu / Encaissé / Remboursé / Dû / Avances

**Files:**
- Modify: `DTO/TransactionStatsDTO.java`, `ServiceImpl/TransactionServiceImpl.java` (`getStats`)
- Modify: `repository/TransactionRepo.java` (somme des entrées hors ventes)
- Modify: `repository/VenteOeufsRepo.java`, `VenteReformeRepo.java` (rapporté des ventes **sans** client)

**Interfaces:**
- Produces: `TransactionStatsDTO.totalVendu, totalEncaisse, totalRembourse, totalDuClients, totalAvancesClients` ;
  `totalMontantRecuVentes` = encaissé lié aux ventes (paiements clients + comptant sans client) ;
  `totalDuParClients` = `SoldeClientServiceImpl.sumSoldePositif(farm)`.

- [ ] **Step 1: Requêtes**

```java
    // TransactionRepo : entrées d'argent hors transactions de vente (paiements clients,
    // entrées manuelles, ventes diverses au comptant).
    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.farm.id = :farmId " +
           "AND t.initialisation.removed = false AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE " +
           "AND t.type = com.diafarms.ml.enums.TypeTransaction.ENTREE " +
           "AND t.sourceType NOT IN (com.diafarms.ml.enums.SourceTransaction.VENTE_OEUFS, com.diafarms.ml.enums.SourceTransaction.VENTE_REFORME) " +
           "AND t.date >= :dateDebut AND t.date <= :dateFin")
    Double sumEntreesHorsVentesStock(@Param("farmId") Long farmId, @Param("dateDebut") LocalDate dateDebut, @Param("dateFin") LocalDate dateFin);

    // VenteOeufsRepo (idem VenteReformeRepo) : argent rapporté des ventes SANS client.
    @Query("SELECT COALESCE(SUM(COALESCE(v.montantRapporte, v.montant)), 0) FROM VenteOeufs v " +
           "WHERE v.farm.id = :farmId AND v.client IS NULL AND v.initialisation.removed = false " +
           "AND v.date >= :dateDebut AND v.date <= :dateFin")
    Double sumRapporteSansClient(@Param("farmId") Long farmId, @Param("dateDebut") java.time.LocalDate dateDebut,
                                 @Param("dateFin") java.time.LocalDate dateFin);
```

- [ ] **Step 2: `getStats`** (vue ferme ; la vue scopée par projet garde ses chiffres
  actuels et reçoit `totalVendu = totalVenteOeufs + totalVenteReforme`, les autres
  nouveaux champs à 0 et un booléen `vueParProjet = true`) :

```java
        double vendu = nz(totalVenteOeufs) + nz(totalVenteReforme)
                + nz(transactionRepo.sumMontantValideBySourceTypeAndDateRange(farmId, SourceTransaction.VENTE_DIVERSE, dDeb, dFin));
        double encaisse = nz(transactionRepo.sumEntreesHorsVentesStock(farmId, dDeb, dFin))
                + nz(venteOeufsRepo.sumRapporteSansClient(farmId, dDeb, dFin))
                + nz(venteReformeRepo.sumRapporteSansClient(farmId, dDeb, dFin));
        double rembourse = nz(remboursementClientRepo.sumActifsByFarmAndDates(farmId, dDeb, dFin));
        double paiementsClients = nz(paiementClientRepo.sumActifsByFarmAndDates(farmId, dDeb, dFin));
```
  `totalEntreesValidees` garde son sens historique (toutes les entrées) pour ne pas
  casser les écrans non encore migrés ; `totalMontantRecuVentes = paiementsClients +
  sumRapporteSansClient(œufs + réforme) + ventes diverses`.

- [ ] **Step 3: Compiler et commit**

```bash
./mvnw -q -o compile && git commit -am "Circuit client: comptabilité vendu, encaissé, remboursé, dû et avances séparés"
```

---

### Task 10: Script des scénarios (vérification de bout en bout)

**Files:**
- Create: `scripts/scenarios-circuit-client.sh`

**Interfaces:**
- Consumes: toute l'API des Tasks 4-9.

- [ ] **Step 1: Backend local.** Postgres temporaire (port 55432, dossier du scratchpad),
  base `diafarms_test`, jar lancé depuis un dossier contenant un `.env` factice
  (`DATABASE_HOST_NAME=localhost`, `POSTGRES_PORT=55432`, `DATABASES_NAME=diafarms_test`,
  `POSTGRES_USER_NAME=postgres`, `APP_PORT=9199`, valeurs factices pour mail/MinIO/secrets).
  Comptes : un ADMIN, un COMPTABLE, un VENTE dans la même ferme ; un projet avec stock
  d'œufs dans un magasin de vente (procédure du 2026-09-23 : race complète, poulailler,
  projet, collecte, transfert).

- [ ] **Step 2: Le script** — fonctions `login`, `call`, `compte(clientUid)` (lit
  `/clients/{uid}/compte`) et `verifier(libelle, attendu, obtenu)` qui affiche OK/ÉCHEC
  et incrémente un compteur d'échecs ; code de sortie non nul s'il y a un échec. Pour
  chaque scénario, un client neuf, une commande de 100 œufs à 1 000, puis les assertions
  sur `totalVendu, totalPaye, totalRembourse, totalImputeVentes, avance, resteAPayer` et
  les deux invariants :

```bash
invariants() { # $1 = JSON du compte
  python3 - "$1" <<'PY'
import json,sys
c=json.loads(sys.argv[1])
ok1 = abs((c["totalPaye"]-c["totalRembourse"]) - (c["totalImputeVentes"]+c["avance"])) < 0.01
ok2 = abs(c["totalVendu"] - (c["totalImputeVentes"]+c["resteAPayer"])) < 0.01
print("OK" if ok1 and ok2 else "ECHEC invariants", c)
sys.exit(0 if ok1 and ok2 else 1)
PY
}
```

  Scénarios (valeurs attendues = tableau de la conception) :
  1. livraison 100 + montantRecu 100 000 → vendu 100 000, payé 100 000, reste 0, avance 0 ;
  2. livraison 60 sans paiement → vendu 60 000, reste 60 000 ;
  3. acompte 40 000 → avance 40 000 ; puis livraison 60 → imputé 40 000, reste 20 000 ;
  4. suite : paiement 20 000 → reste 0 ;
  5. acompte 40 000, annulation `rembourserAcompte=false` → avance 40 000, statut ANNULEE ;
  6. idem avec `rembourserAcompte=true` → remboursé 40 000, avance 0 ;
  7. acompte 40 000, annulation sans remboursement, puis remboursement 15 000 → avance 25 000 ;
     7 bis : remboursement 30 000 → 400 « dépasse l'avance disponible » ;
  8. acompte 40 000, livraison 30, clôture → imputé 30 000, avance 10 000, statut CLOTUREE ;
  9. acompte 40 000, L1 = 60, L2 = 40, paiement 60 000 → toutes deux PAYEE, avance 0 ;
  10. vente 5 000 réglée par paiement 5 000, puis montant corrigé à 3 000 → payé vente
      3 000, avance 2 000, une imputation ANNULEE + une ACTIVE de 3 000 dans l'historique ;
  11. vente 5 000 au client A payée 5 000, client changé en B → A : avance 5 000,
      B : reste 5 000 ;
  12. paiement 10 000, remboursement 4 000, annulation du paiement → 400 « annulez d'abord
      le remboursement » ;
  13. facture sur les livraisons du scénario 9 avant le paiement final → PARTIELLE ;
      `POST /factures/{uid}/paiement` 60 000 → PAYEE ; `/comptes` inchangé à
      l'exception du paiement (pas de double comptage) ; deuxième facture sur L1 → 400.

- [ ] **Step 3: Exécuter**

Run: `bash scripts/scenarios-circuit-client.sh`
Expected: toutes les lignes « OK », code de sortie 0.

- [ ] **Step 4: Commit**

```bash
git add scripts/scenarios-circuit-client.sh && git commit -m "Circuit client: script des scénarios de bout en bout"
```

---

### Task 11: Reprise des données existantes (simulation puis exécution)

**Files:**
- Create: `ServiceImpl/RepriseCircuitClientService.java`
- Create: `controllers/RepriseCircuitClientController.java`
- Create: `DTO/RepriseRapportDTO.java`

**Interfaces:**
- Produces: `POST /admin/reprise-circuit-client?executer=false|true` (ADMIN de la ferme
  ou SUPER_ADMIN) → `RepriseRapportDTO { lignes: [{client, soldeAvant, soldeApres, ecart, notes[]}], paiementsCrees, remboursementsCrees, recopiesFacturesRetirees, avertissements[] }`.
  Idempotent : ne traite pas deux fois une transaction déjà `PAIEMENT_CLIENT`.

- [ ] **Step 1: Logique**, par ferme, dans une transaction ; si `executer=false` la
  méthode lève à la fin une exception interne capturée par le contrôleur pour forcer le
  **rollback** et renvoie le rapport (simulation sans rien écrire) :
  1. `soldeAvant` = ligne `soldes_client` de chaque client (0 si absente).
  2. Transactions ENTREE avec client, non supprimées, catégories « Remboursement client »,
     « Acompte client », « Paiement client » et source `MANUEL` → un `PaiementClient` par
     transaction (même date, montant, `mode = ESPECES`, origine ACOMPTE / FACTURE si la
     description commence par « Paiement facture » / sinon REGLEMENT, `recuPar = creePar`) ;
     la transaction passe à `source_type = PAIEMENT_CLIENT`, `source_unique_id = uid du
     paiement`, catégorie « Paiement client ». Transactions REJETE : ignorées et listées
     en avertissement.
  3. Transactions SORTIE avec client, catégorie « Remboursement au client » → un
     `RemboursementClient` (motif = description) ; source `REMBOURSEMENT_CLI`.
  4. Recopies de `marquerPayee` : pour chaque facture non annulée, la somme des
     paiements « Paiement facture <numéro> » a été ajoutée au `montantRapporte` de la
     vente source (ou de la vente `commande.venteUniqueId`) ; on la retire de ce
     `montantRapporte` (note dans le rapport), toutes les factures existantes passent
     `legacy = true`.
  5. Ventes œufs/réforme **avec client** et `montantRapporte > 0` (après l'étape 4) →
     un `PaiementClient` origine VENTE, daté de la vente, ciblé sur elle, avec sa
     transaction « Paiement client » ; puis `montantRapporte = null`.
  6. `commande.venteUniqueId` non nul → `vente.commande = commande` ; commandes non
     terminées avec `quantiteLivree > 0` → `EN_LIVRAISON`.
  7. `imputer(client)` pour chaque client ; `soldeApres = compte(client).solde`.
  8. Toute ligne avec `|soldeAvant − soldeApres| ≥ 1` est listée avec ses notes.

- [ ] **Step 2: Vérifier en local** sur les données de test (commande avec acompte,
  livraison partielle, facture marquée payée, remboursement) créées **avec l'ancien jar**
  (`git stash` / jar du commit `5d7a75b`), puis nouveau jar + simulation : le rapport
  liste les recopies retirées et chaque solde ; exécution réelle ; relancer la
  simulation → 0 paiement créé (idempotence).

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "Circuit client: reprise des données avec simulation et rapport par client"
```

---

### Task 12: SQL des contraintes et notes du projet

**Files:**
- Create: `docs/sql/2026-09-2x_circuit_client.sql`
- Modify: `VERSION_2_NOTES.md`

- [ ] **Step 1: SQL** (à lancer après le redémarrage qui crée les tables, avant la reprise) :

```sql
BEGIN;
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_source_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_source_type_check CHECK (source_type IN (
  'MANUEL','VENTE_OEUFS','VENTE_REFORME','SALAIRE','ALIMENTATION','SOINS','VACCINATION',
  'INVESTISSEMENT','PROJET_ACHAT_SUJETS','PROJET_CHARGES','VENTE_DIVERSE','PAIEMENT_CLIENT','REMBOURSEMENT_CLI'));
ALTER TABLE commandes DROP CONSTRAINT IF EXISTS commandes_statut_check;
ALTER TABLE commandes ADD CONSTRAINT commandes_statut_check CHECK (statut IN (
  'EN_ATTENTE','CONFIRMEE','EN_LIVRAISON','CONVERTIE','CLOTUREE','ANNULEE'));
ALTER TABLE factures DROP CONSTRAINT IF EXISTS factures_source_type_check;
ALTER TABLE factures ADD CONSTRAINT factures_source_type_check CHECK (source_type IN (
  'VENTE_OEUFS','VENTE_REFORME','COMMANDE','VENTES'));
ALTER TABLE factures DROP CONSTRAINT IF EXISTS factures_statut_check;
ALTER TABLE factures ADD CONSTRAINT factures_statut_check CHECK (statut IN (
  'IMPAYEE','PARTIELLE','PAYEE','ANNULEE'));
COMMIT;
```
  Vérifier d'abord les noms réels : `SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid IN ('transactions'::regclass,'commandes'::regclass,'factures'::regclass) AND contype='c';`

- [ ] **Step 2: Notes** : section « Mise à jour 2026-09-2x (circuit de l'argent client) »
  dans `VERSION_2_NOTES.md` (modèle, adaptateurs, reprise, écrans).

- [ ] **Step 3: Commit et push de la phase A**

```bash
git add -A && git commit -m "Circuit client: SQL des contraintes et notes" && git push origin version_2
```

---

## Phase B — Web

### Task 13: Couche API web

**Files:**
- Modify: `Diafarms_web/src/services/api.ts`

**Interfaces:**
- Produces (types et fonctions exportés) :
  `ModePaiement`, `MODES_PAIEMENT` (libellés : Espèces, Orange Money, Moov Money, Wave,
  Virement, Chèque, Autre), `CompteClient`, `PaiementClient`, `RemboursementClient`,
  `ImputationClient`, `getCompteClientAPI(uid)`, `createPaiementClientAPI(payload)`,
  `annulerPaiementClientAPI(uid, motif)`, `createRemboursementClientAPI(payload)`,
  `annulerRemboursementClientAPI(uid, motif)`, `livrerCommandeAPI(uid, quantite, montantRecu, mode)`,
  `payerCommandeAPI(uid, montant, mode)`, `cloturerCommandeAPI(uid, motif)`,
  `annulerCommandeAPI(uid, motif, rembourserAcompte, mode)`,
  `genererFactureVentesAPI(clientUid, ventes)`, `payerFactureAPI(uid, montant, mode)`,
  `annulerFactureAPI(uid, motif)` ; `VenteLigne` gagne `paye`, `resteAPayer`,
  `statutPaiement`, `commandeUniqueId` ; `CommandeDTO` et `FactureDTO` gagnent les champs
  des Tasks 7-8.

- [ ] **Step 1: Types et fonctions** (style des fonctions existantes,
  `authenticatedApiCallReal`, `PUT`/`POST` avec `JSON.stringify`).

```ts
export type ModePaiement = "ESPECES" | "ORANGE_MONEY" | "MOOV_MONEY" | "WAVE" | "VIREMENT" | "CHEQUE" | "AUTRE";
export const MODES_PAIEMENT: { value: ModePaiement; label: string }[] = [
  { value: "ESPECES", label: "Espèces" }, { value: "ORANGE_MONEY", label: "Orange Money" },
  { value: "MOOV_MONEY", label: "Moov Money" }, { value: "WAVE", label: "Wave" },
  { value: "VIREMENT", label: "Virement" }, { value: "CHEQUE", label: "Chèque" }, { value: "AUTRE", label: "Autre" },
];

export interface CompteClient {
  clientUniqueId: string; clientNom: string;
  totalVendu: number; totalPaye: number; totalRembourse: number; totalImputeVentes: number;
  resteAPayer: number; avance: number; solde: number;
}
export interface PaiementClient {
  uniqueId: string; date: string; montant: number; mode: ModePaiement;
  origine: "ACOMPTE" | "LIVRAISON" | "VENTE" | "REGLEMENT" | "FACTURE" | "REPRISE";
  commandeUniqueId: string | null; factureNumero: string | null; observations: string | null;
  recuParNom: string | null; statut: "ACTIF" | "ANNULE"; motifAnnulation: string | null;
  annuleParNom: string | null; dateAnnulation: string | null; impute: number; disponible: number;
}
export interface RemboursementClient {
  uniqueId: string; date: string; montant: number; mode: ModePaiement; motif: string;
  commandeUniqueId: string | null; effectueParNom: string | null; statut: "ACTIF" | "ANNULE";
  motifAnnulation: string | null; dateAnnulation: string | null;
}
export interface ImputationClient {
  uniqueId: string; paiementUniqueId: string; paiementDate: string;
  cibleType: "VENTE_OEUFS" | "VENTE_REFORME" | "REMBOURSEMENT"; cibleUniqueId: string;
  montant: number; statut: "ACTIF" | "ANNULE"; motifAnnulation: string | null; createdAt: string;
}
export interface CompteClientComplet {
  compte: CompteClient; paiements: PaiementClient[]; remboursements: RemboursementClient[]; imputations: ImputationClient[];
}

export const getCompteClientAPI = (uid: string) =>
  authenticatedApiCallReal<CompteClientComplet>(`/clients/${uid}/compte`, { method: "GET" });

export interface PaiementClientPayload {
  clientUniqueId: string; montant: number; mode: ModePaiement; date?: string;
  origine?: "REGLEMENT" | "ACOMPTE" | "FACTURE"; commandeUniqueId?: string | null;
  venteCibleType?: "VENTE_OEUFS" | "VENTE_REFORME" | null; venteCibleUniqueId?: string | null;
  observations?: string | null;
}
export const createPaiementClientAPI = (p: PaiementClientPayload) =>
  authenticatedApiCallReal<PaiementClient>("/paiements-client/create", { method: "POST", body: JSON.stringify(p) });
export const annulerPaiementClientAPI = (uid: string, motif: string) =>
  authenticatedApiCallReal<PaiementClient>(`/paiements-client/annuler/${uid}`, { method: "PUT", body: JSON.stringify({ motif }) });
export const createRemboursementClientAPI = (p: { clientUniqueId: string; montant: number; mode: ModePaiement; motif: string; commandeUniqueId?: string | null }) =>
  authenticatedApiCallReal<RemboursementClient>("/remboursements-client/create", { method: "POST", body: JSON.stringify(p) });
export const annulerRemboursementClientAPI = (uid: string, motif: string) =>
  authenticatedApiCallReal<RemboursementClient>(`/remboursements-client/annuler/${uid}`, { method: "PUT", body: JSON.stringify({ motif }) });
```
  Les fonctions commande/facture suivent le même modèle avec les routes des Tasks 7-8.
  Retirer `rembourserClientAPI`/`payerDetteClientAPI` des imports des écrans migrés
  (les fonctions restent exportées tant qu'un écran les utilise).

- [ ] **Step 2: Typage** — Run: `npx tsc --noEmit -p tsconfig.app.json` → aucune erreur.

- [ ] **Step 3: Commit**

```bash
git add src/services/api.ts && git commit -m "Circuit client: API web paiements, remboursements, compte client, commandes et factures"
```

---

### Task 14: Fiche client

**Files:**
- Modify: `src/components/dialogs/ClientDetailDialog.tsx`
- Create: `src/components/dialogs/PaiementClientDialog.tsx`, `src/components/dialogs/RemboursementClientDialog.tsx`, `src/components/MotifDialog.tsx` (motif obligatoire, réutilise le modèle de `DemandeSuppressionDialog`)

- [ ] **Step 1: En-tête chiffré** (depuis `getCompteClientAPI`) : quatre tuiles
  « Vendu », « Payé », « Reste à payer » (rouge si > 0), « Avance » (vert si > 0), et le
  texte « Remboursé : X » si > 0.
- [ ] **Step 2: Onglet « Ventes »** : date, type, quantité, montant, payé, reste, pastille
  Payée / Partielle / Non payée, lien commande ; bouton « Encaisser » sur une vente non
  soldée (ouvre `PaiementClientDialog` avec `venteCible` pré-rempli et le reste en
  montant par défaut) ; case à cocher + bouton « Créer une facture » pour les ventes non
  facturées.
- [ ] **Step 3: Onglet « Paiements »** : date, montant, mode, origine, reçu par, imputé /
  disponible ; paiement annulé barré avec motif ; « Annuler » (ADMIN/RESPONSABLE,
  `MotifDialog`).
- [ ] **Step 4: Onglet « Remboursements »** : liste ; « Rembourser » (ADMIN/RESPONSABLE/
  COMPTABLE) visible si avance > 0, plafonné à l'avance ; « Annuler » avec motif.
- [ ] **Step 5: `PaiementClientDialog`** : montant (obligatoire, > 0), mode (sélecteur
  `MODES_PAIEMENT`, défaut Espèces), date (défaut aujourd'hui), vente à régler
  (facultatif), observations ; texte d'aide « Le paiement règle d'abord la vente choisie,
  puis les plus anciennes ; le reste devient une avance. »
- [ ] **Step 6: Typage + contrôle visuel** — `npx tsc ...` ; copie de test du web pointée
  sur le backend local, capture de la fiche d'un client du scénario 9 : tuiles 100 000 /
  100 000 / 0 / 0.
- [ ] **Step 7: Commit** `git commit -am "Circuit client: fiche client (compte, ventes, paiements, remboursements)"`

---

### Task 15: Commandes

**Files:**
- Modify: `src/pages/Commandes.tsx`, `src/components/dialogs/LivrerCommandeDialog.tsx`, `src/components/dialogs/CreateCommandeDialog.tsx`
- Create: `src/components/dialogs/FinCommandeDialog.tsx` (clôturer / annuler)

- [ ] **Step 1: Liste** : colonnes Commandé / Livré / Reste à livrer, Montant livré, Payé,
  Reste à payer, Acompte reçu ; statut avec libellés En attente, Confirmée, En cours de
  livraison, Livrée, Clôturée, Annulée.
- [ ] **Step 2: Détail d'une commande** (ligne dépliable) : livraisons (date, quantité,
  montant, payé, pastille), paiements liés.
- [ ] **Step 3: `LivrerCommandeDialog`** : quantité, « Montant reçu maintenant »
  (facultatif) + mode ; aperçu calculé « Cette livraison : 60 000 — réglée par l'avance :
  40 000 — reste à payer : 20 000 » à partir de `getCompteClientAPI`.
- [ ] **Step 4: Actions** : « Enregistrer un paiement » (acompte complémentaire,
  `payerCommandeAPI`), « Clôturer » (si livrée en partie ; motif ; message « Le
  trop-perçu éventuel reste en avance du client »), « Annuler » (si rien livré ; motif ;
  choix « Garder l'acompte en avance » / « Rembourser l'acompte » + mode).
- [ ] **Step 5: `CreateCommandeDialog`** : mode de paiement de l'acompte.
- [ ] **Step 6: Typage, contrôle visuel (scénarios 3, 8), commit.**

---

### Task 16: Factures, Ventes, Comptabilité, Rapports

**Files:**
- Modify: `src/pages/Factures.tsx`, `src/pages/Ventes.tsx`, `src/components/dialogs/CreateVenteOeufsDialog.tsx`, `CreateVenteReformeDialog.tsx`, `EditVenteDialog.tsx`, `src/pages/Comptabilite.tsx`, `src/pages/Reporting.tsx`, `src/pages/Dashboard.tsx`

- [ ] **Step 1: Factures** : lignes, Payé (calculé), Reste, statut (Émise / Partiellement
  payée / Payée / Annulée) ; « Enregistrer un paiement » remplace « Marquer payée » ;
  « Annuler » avec motif ; les factures `legacy` portent la mention « Ancienne facture ».
- [ ] **Step 2: Ventes** : colonne « Paiement » (pastille) pour les ventes avec client ;
  dans les dialogues de création, le champ « Montant rapporté » devient « Montant reçu
  maintenant » + mode **quand un client est choisi**, et reste « Montant rapporté »
  (contrôle vendeur) sans client ; `EditVenteDialog` masque « Montant rapporté » pour une
  vente avec client (renvoi vers la fiche client).
- [ ] **Step 3: Comptabilité** : cartes « Vendu », « Encaissé », « Remboursé », « Dû par
  les clients », « Avances clients » (depuis `TransactionStatsDTO`) ; une transaction
  `verrouillee` affiche « Paiement client » / « Remboursement » avec un lien vers la
  fiche client au lieu des actions ; « Paiements clients récents » de Ventes lit
  `/paiements-client` via la fiche (plus la recherche par catégorie).
- [ ] **Step 4: Rapports / tableau de bord** : « Montant reçu (réel) » et « CA du mois »
  utilisent `totalMontantRecuVentes` / `totalVendu` recalculés (plus de double comptage).
- [ ] **Step 5: Typage, contrôle visuel, commit et push de la phase B.**

---

## Phase C — Mobile

### Task 17: Mode de paiement et libellés

**Files:**
- Modify: `Diafarms/app/src/main/java/com/mobile/diafarms/network/dto/CommandeCreateRequest.java`, `VenteOeufsCreateRequest.java`, `VenteReformeCreateRequest.java` (`public String modePaiement;`)
- Modify: `ui/saisie/SaisieFormActivity.java` + `res/layout/activity_saisie_form.xml`
- Modify: `app/build.gradle` (versionCode 26, versionName "1.25")

- [ ] **Step 1:** sélecteur « Mode de paiement » (Espèces par défaut) sous l'acompte d'une
  commande et sous le montant reçu d'une vente **avec client** ; libellé « Montant reçu
  maintenant » quand un client est choisi, « Montant rapporté » sinon.
- [ ] **Step 2:** saisies hors ligne déjà en file (sans `modePaiement`) : le serveur met
  Espèces par défaut (Task 4, `mode(null)`), rien à migrer côté téléphone.
- [ ] **Step 3:** `./gradlew -q --offline assembleRelease` ; installation `adb install -r`
  (données conservées) ; commit et push.

---

## Phase D — Mise en production

### Task 18: Déploiement dans l'ordre

(Réécrit après la revue finale du 2026-09-24 : SQL des contraintes AVANT le backend,
reprise réservée au SUPER_ADMIN, contrôle SQL après la reprise.)

- [ ] **Step 1 — sauvegarde** de la base (`backup_all.sh` du serveur) — **demander à
  l'utilisateur**.
- [ ] **Step 2 — SQL AVANT le déploiement du backend** (contraintes CHECK élargies
  seulement, sans effet pour l'ancien jar) : `docs/sql/2026-09-23_ventes_diverses.sql`
  (la table `ventes_diverses` n'existe pas encore : seule la contrainte s'applique, les
  étapes de reprise des fientes sont sautées avec une NOTICE) puis
  `docs/sql/2026-09-24_circuit_client.sql`.
- [ ] **Step 3 — (facultatif) essai à blanc** : restaurer la sauvegarde sur une base de
  staging, y lancer le nouveau jar, relancer `2026-09-23_ventes_diverses.sql` puis la
  **simulation** de la reprise (SUPER_ADMIN) ; lire le rapport.
- [ ] **Step 4 — fenêtre de maintenance** (personne ne saisit pendant ce temps) :
  1. déployer le backend (`deploy.sh`) ;
  2. relancer `docs/sql/2026-09-23_ventes_diverses.sql` (la table existe maintenant : les
     anciennes « Vente fientes » / « Autre vente » deviennent des ventes) ;
  3. **simulation** : `POST /admin/reprise-circuit-client?executer=false` en SUPER_ADMIN
     (toutes les fermes, ou `&farmUniqueId=`) ; transmettre le rapport à l'utilisateur
     (écarts par client, `ventesSansMontantRapporte`, avertissements — dont les
     transactions EN_ATTENTE à valider ou rejeter d'abord) et **attendre sa validation** ;
  4. **exécution** : `?executer=true` ;
  5. **simulation à nouveau** : 0 création attendue partout ;
  6. **contrôle** : `docs/sql/2026-09-24_controle_circuit_client.sql` (lecture seule,
     résultat vide attendu ; un remboursement ancien non couvert ressort au contrôle 1,
     déjà signalé par la reprise) ;
  7. déployer le web (dist → `/home/app/diafarms_web`, `docker compose build && up -d`) ;
  8. installer l'APK 1.25 sur le téléphone.
- [ ] **Step 5 — contrôle en production** sur un client réel choisi avec l'utilisateur :
  fiche client, commande, facture, Comptabilité — les deux invariants tiennent.
