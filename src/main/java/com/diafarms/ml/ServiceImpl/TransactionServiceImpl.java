package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.ProjetVenteReelDTO;
import com.diafarms.ml.DTO.RepartitionRatioDTO;
import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.DTO.TransactionStatsDTO;
import com.diafarms.ml.DTO.VenteRepartitionReelDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Transaction;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.SoldeClientRepo;
import com.diafarms.ml.repository.SoldeVendeurRepo;
import com.diafarms.ml.repository.TransactionRepo;
import com.diafarms.ml.repository.VenteOeufsRepartitionRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.TransactionCreate;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.request.others.RejectTransactionRequest;
import com.diafarms.ml.request.update.TransactionUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepo transactionRepo;
    private final com.diafarms.ml.repository.VenteDiverseRepo venteDiverseRepo;
    private final ProjetsRepo projetsRepo;
    private final com.diafarms.ml.repository.SiteRepo siteRepo;
    private final com.diafarms.ml.repository.BatimentRepo batimentRepo;
    private final LogsServices logs;
    private final OtherService otherService;
    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteReformeRepo venteReformeRepo;
    private final SoldeVendeurRepo soldeVendeurRepo;
    private final SoldeClientRepo soldeClientRepo;
    private final ClientRepo clientRepo;
    private final VenteOeufsRepartitionRepo venteOeufsRepartitionRepo;
    private final VenteReformeRepartitionRepo venteReformeRepartitionRepo;
    private final SoldeClientServiceImpl soldeClientService;
    private final com.diafarms.ml.repository.PaiementClientRepo paiementClientRepo;
    private final com.diafarms.ml.repository.RemboursementClientRepo remboursementClientRepo;
    // @Lazy : évite tout risque de cycle de construction avec CompteClientService (lui-même
    // consommé par PaiementClientService, ServiceImpl côté ventes/clients) — seul le ratio
    // réel/théorique d'une vente à client en a besoin, voir ratio(RepartitionRatioDTO).
    @Autowired
    @Lazy
    private CompteClientService compteClientService;

    // Sentinelles "pas de filtre" pour les requêtes agrégat par date (voir
    // TransactionRepo.countByProjetIdsAndStatut) — Postgres échoue à déterminer le
    // type d'un paramètre comparé directement à NULL dans une requête COUNT/SUM
    // ("could not determine data type of parameter", SQLState 42P18), quelle que soit
    // la valeur réelle passée. deb()/fin() résolvent donc TOUJOURS une borne concrète
    // avant d'appeler ces requêtes, jamais null.
    private static final LocalDate DATE_MIN = LocalDate.of(1900, 1, 1);
    private static final LocalDate DATE_MAX = LocalDate.of(2999, 12, 31);
    private LocalDate deb(LocalDate d) { return d != null ? d : DATE_MIN; }
    private LocalDate fin(LocalDate d) { return d != null ? d : DATE_MAX; }

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private String generateRef() {
        String ref;
        do {
            ref = "TRX-" + String.format("%04d", (int) (Math.random() * 9999));
        } while (transactionRepo.existsByRef(ref));
        return ref;
    }

    private boolean isAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    private boolean hasRole(Utilisateurs u, String role) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> role.equalsIgnoreCase(r.getRole()));
    }

    // Peut DEMANDER une suppression — même population que SalaireServiceImpl/
    // PersonnelServiceImpl.ensureCanManage.
    private void ensureCanDemanderSuppression(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour demander la suppression d'une transaction.");
        }
    }

    // Peut CONFIRMER/REFUSER une demande, ou supprimer/restaurer directement — même
    // autorité que valider/rejeter : ADMIN, ou responsable DU PROJET concerné.
    private void ensureCanConfirmerSuppression(Utilisateurs u, Projets projet) {
        if (!isAdmin(u) && !isResponsableDuProjet(u, projet)) {
            throw new IllegalArgumentException("Seul un administrateur ou le responsable de ce projet peut confirmer ou refuser cette suppression.");
        }
    }

    // Une transaction générée par une vente n'est que la conséquence financière de cette
    // vente : la modifier, la rejeter ou la supprimer seule laissait la vente active (stock,
    // historique client, page Ventes) alors que l'argent disparaissait de la comptabilité.
    // Tout passe donc par la vente, qui entraîne sa transaction avec elle.
    private void ensurePasLieeAUneVente(Transaction t) {
        if (TransactionDTO.isSourceVente(t.getSourceType())) {
            throw new IllegalArgumentException("Cette transaction vient d'une vente : modifiez ou supprimez la vente depuis la page Ventes.");
        }
        if (t.getSourceType() == SourceTransaction.PAIEMENT_CLIENT || t.getSourceType() == SourceTransaction.REMBOURSEMENT_CLI) {
            throw new IllegalArgumentException("Cette transaction vient d'un paiement ou d'un remboursement client : annulez-le depuis la fiche du client.");
        }
        // Même principe pour les dépenses générées par une saisie (soins, aliment,
        // investissement, salaire, coûts de démarrage du projet) : la saisie source et sa
        // transaction doivent rester d'accord, donc tout passe par la saisie, dont la
        // modification ou la suppression met déjà la transaction à jour (syncSortie,
        // updateMontantBySource, setRemovedBySource).
        String saisie = TransactionDTO.saisieSourceGeneree(t.getSourceType());
        if (saisie != null) {
            throw new IllegalArgumentException("Cette transaction est générée automatiquement par " + saisie
                    + " : modifiez ou supprimez cette saisie à la place.");
        }
    }

    /**
     * Restriction du RAPPORT (/transactions/stats, qui alimente les cartes KPI et le
     * "Rapport général" de Comptabilité) : null = pas de restriction (vue ferme entière) ;
     * liste (éventuellement vide) = restreint aux transactions des projets où l'utilisateur
     * ciblé est responsableFinance.
     *
     * Un COMPTABLE (pur ou cumulé) reste farm-wide, non scopé par cet axe — voir la classe
     * (COMPTABLE n'est jamais restreint par projet, contrairement à l'ancien FINANCIER).
     * Seul un RESPONSABLE pur est auto-restreint à SES projets (champ Projets.responsable),
     * pour son propre tableau de bord. Un ADMIN/SUPER_ADMIN peut se placer dans la vue d'un
     * comptable choisi ("voir comme", financierUniqueId conservé tel quel pour compat).
     */
    private List<Long> resolveProjetIdsScope(Utilisateurs currentUser, Long farmId, String financierUniqueId) {
        if (currentUser == null || farmId == null) {
            return isAdmin(currentUser) ? null : List.of();
        }
        if (isAdmin(currentUser)) {
            if (financierUniqueId == null || financierUniqueId.isBlank()) {
                return null;
            }
            return projetsRepo.findProjetIdsAssignedAsFinanceToUser(farmId, financierUniqueId);
        }
        if (isPureResponsable(currentUser)) {
            return projetsRepo.findProjetIdsAssignedAsResponsableToUser(farmId, currentUser.getUniqueId());
        }
        return null;
    }

    /**
     * Restriction de la LISTE (/transactions/list) : un RESPONSABLE pur est auto-restreint
     * à ses propres projets (sa table Comptabilité scopée, avec pouvoir de valider/rejeter
     * dessus). Pour tout le monde d'autre (COMPTABLE farm-wide, cumul de rôles, Dashboard
     * admin, page Ventes) la liste n'est PAS auto-restreinte — seul un ADMIN/SUPER_ADMIN
     * qui fournit explicitement financierUniqueId se place dans la vue scopée d'un
     * comptable choisi (filtre "voir comme" de Comptabilité).
     */
    private List<Long> resolveProjetIdsScopeForList(Utilisateurs currentUser, Long farmId, String financierUniqueId) {
        if (isPureResponsable(currentUser) && farmId != null) {
            return projetsRepo.findProjetIdsAssignedAsResponsableToUser(farmId, currentUser.getUniqueId());
        }
        if (!isAdmin(currentUser) || farmId == null || financierUniqueId == null || financierUniqueId.isBlank()) {
            return null;
        }
        return projetsRepo.findProjetIdsAssignedAsFinanceToUser(farmId, financierUniqueId);
    }

    // Un rôle est son SEUL rôle (pas de cumul, ex: PRODUCTION + COMPTABLE) : un
    // compte qui cumule les rôles garde l'accès complet, un autre rôle justifiant
    // déjà l'accès non restreint (même règle que hasOnlyRole côté front, voir
    // src/lib/roles.ts).
    private boolean isPureRole(Utilisateurs u, String role) {
        return u != null && u.getRoles() != null && !u.getRoles().isEmpty()
                && u.getRoles().stream().allMatch(r -> role.equalsIgnoreCase(r.getRole()));
    }

    private boolean isPureComptable(Utilisateurs u) {
        return isPureRole(u, "COMPTABLE");
    }

    private boolean isPureVente(Utilisateurs u) {
        return isPureRole(u, "VENTE");
    }

    private boolean isPureResponsable(Utilisateurs u) {
        return isPureRole(u, "RESPONSABLE");
    }

    // Un RESPONSABLE a autorité sur les transactions/ventes rattachées à SES projets
    // (champ Projets.responsable, distinct de responsableProduction/responsableFinance
    // qui ne donnent aucun pouvoir de validation) — jamais sur une transaction commune
    // (pas de projet précis) ni sur un autre projet que le sien.
    private boolean isResponsableDuProjet(Utilisateurs u, Projets projet) {
        return u != null && projet != null && projet.getResponsable() != null
                && projet.getResponsable().getId().equals(u.getId());
    }

    /**
     * Restriction par CRÉATEUR de la LISTE : un VENTE pur ne voit que ses propres
     * ventes, quel que soit vendeurUniqueId reçu du client — jamais celui d'un
     * collègue (page Ventes, route bloquée pour COMPTABLE côté front — voir
     * App.tsx). Un ADMIN/SUPER_ADMIN ou un COMPTABLE pur peut choisir n'importe quel
     * vendeur pour filtrer la page Comptabilité (ou aucun = ferme entière — COMPTABLE
     * y voit tout par défaut, jamais auto-scopé à ses propres transactions ; avant ce
     * correctif il l'était par erreur, en confondant avec la restriction VENTE
     * ci-dessus). Un RESPONSABLE/PRODUCTION (ou un cumul de rôles) n'est pas
     * restreint par cet axe (le RESPONSABLE voit tout pour pouvoir valider/rejeter).
     */
    private String resolveVendeurScopeForList(Utilisateurs currentUser, String vendeurUniqueId) {
        if (isAdmin(currentUser) || isPureComptable(currentUser)) {
            return (vendeurUniqueId == null || vendeurUniqueId.isBlank()) ? null : vendeurUniqueId;
        }
        if (isPureVente(currentUser)) {
            return currentUser.getUniqueId();
        }
        return null;
    }

    // Résolution des rattachements facultatifs site/poulailler : appartiennent à la MÊME
    // ferme que l'utilisateur et ne sont pas supprimés (une transaction ne doit jamais
    // pointer vers le site ou le poulailler d'une autre ferme).
    private com.diafarms.ml.models.Site resoudreSite(String uniqueId, Utilisateurs currentUser) {
        if (uniqueId == null || uniqueId.isBlank()) return null;
        com.diafarms.ml.models.Site site = siteRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Site introuvable : " + uniqueId));
        if (Boolean.TRUE.equals(site.getInitialisation() != null ? site.getInitialisation().getRemoved() : false)
                || !memeFerme(site.getFarm(), currentUser)) {
            throw new IllegalArgumentException("Site introuvable : " + uniqueId);
        }
        return site;
    }

    private com.diafarms.ml.models.Batiment resoudreBatiment(String uniqueId, Utilisateurs currentUser) {
        if (uniqueId == null || uniqueId.isBlank()) return null;
        com.diafarms.ml.models.Batiment batiment = batimentRepo.findByUniqueId(uniqueId);
        if (batiment == null
                || Boolean.TRUE.equals(batiment.getInitialisation() != null ? batiment.getInitialisation().getRemoved() : false)
                || !memeFerme(batiment.getFarm(), currentUser)) {
            throw new IllegalArgumentException("Poulailler introuvable : " + uniqueId);
        }
        return batiment;
    }

    // Projets concernés d'une transaction commune : ceux d'une autre ferme sont
    // ignorés (comme un uniqueId inconnu), jamais rattachés.
    private List<Projets> projetsDeLaFerme(List<String> uniqueIds) {
        Utilisateurs u = getCurrentUserSafe();
        return new java.util.ArrayList<>(projetsRepo.findByUniqueIdIn(uniqueIds).stream()
                .filter(p -> memeFerme(p.getFarm(), u))
                .toList());
    }

    private boolean memeFerme(Farm farm, Utilisateurs currentUser) {
        return farm != null && currentUser != null && currentUser.getFarm() != null
                && farm.getId().equals(currentUser.getFarm().getId());
    }

    @Override
    @Transactional
    public TransactionDTO create(TransactionCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();

        Transaction t = new Transaction();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setRef(generateRef());
        t.setType(TypeTransaction.valueOf(data.getType()));
        t.setDate(data.getDate() != null ? data.getDate() : java.time.LocalDate.now());
        t.setDescription(data.getDescription());
        t.setMontant(data.getMontant());
        t.setCategorie(data.getCategorie());
        // Toute transaction est validée dès la création, quel que soit le créateur —
        // seul un rejet a posteriori (voir rejeter()) peut encore la faire basculer.
        // Avant : seul un ADMIN était auto-validé, les autres restaient EN_ATTENTE ;
        // changé sur demande explicite (le contrôle a priori n'apportait rien, le
        // rejet suffit comme filet de sécurité).
        t.setStatut(StatutTransaction.VALIDE);
        // Même trace que la validation manuelle (voir valider()) : sans ça, "Dernière
        // décision par" resterait vide pour une transaction pourtant déjà validée.
        t.setValidateur(currentUser);
        t.setDateValidation(LocalDateTime.now());
        t.setCreePar(currentUser);
        t.setInitialisation(Initialisation.init());

        if (data.getClientUniqueId() != null && !data.getClientUniqueId().isBlank()) {
            Client client = clientRepo.findByUniqueId(data.getClientUniqueId());
            // Client d'une autre ferme : même message qu'introuvable (pas de rattachement
            // d'une transaction à un client qui n'est pas celui de la ferme).
            if (client == null || !memeFerme(client.getFarm(), currentUser)) {
                throw new IllegalArgumentException("Client introuvable : " + data.getClientUniqueId());
            }
            t.setClient(client);
        }

        boolean commun = !Boolean.FALSE.equals(data.getCommun())
                && (Boolean.TRUE.equals(data.getCommun()) || data.getProjetUniqueId() == null || data.getProjetUniqueId().isBlank());

        if (!commun) {
            Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                    .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                    .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));
            t.setProjet(projet);
        } else if (data.getProjetsConcernesUniqueIds() != null && !data.getProjetsConcernesUniqueIds().isEmpty()) {
            t.setProjetsConcernes(projetsDeLaFerme(data.getProjetsConcernesUniqueIds()));
        }

        t.setSite(resoudreSite(data.getSiteUniqueId(), currentUser));
        t.setBatiment(resoudreBatiment(data.getBatimentUniqueId(), currentUser));

        if (currentUser != null) {
            t.setFarm(currentUser.getFarm());
        }

        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Création de la transaction '" + saved.getRef() + "' (" + saved.getType() + ", " + saved.getMontant() + " FCFA)");
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO createFromSource(Projets projet, Farm farm, Double montant, String categorie, java.time.LocalDate date,
                                            String description, SourceTransaction sourceType, String sourceUniqueId, Utilisateurs creePar) {
        Transaction t = new Transaction();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setRef(generateRef());
        t.setType(TypeTransaction.ENTREE);
        t.setDate(date != null ? date : java.time.LocalDate.now());
        t.setDescription(description);
        t.setMontant(montant);
        t.setCategorie(categorie);
        // Toute vente est validée dès la création, quel que soit son créateur — voir
        // le même changement dans create() ci-dessus. Un RESPONSABLE/ADMIN garde la
        // main pour rejeter une vente a posteriori (voir rejeter()), mais n'a plus à
        // "accepter" une vente d'un vendeur pur avant qu'elle compte réellement.
        t.setStatut(StatutTransaction.VALIDE);
        t.setValidateur(creePar);
        t.setDateValidation(LocalDateTime.now());
        t.setProjet(projet);
        t.setSourceType(sourceType);
        t.setSourceUniqueId(sourceUniqueId);
        t.setFarm(farm);
        t.setCreePar(creePar);
        t.setInitialisation(Initialisation.init());

        Transaction saved = transactionRepo.save(t);
        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO createSortieCommune(Farm farm, Double montant, String categorie, java.time.LocalDate date,
                                               String description, SourceTransaction sourceType, String sourceUniqueId, Utilisateurs creePar) {
        Transaction t = new Transaction();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setRef(generateRef());
        t.setType(TypeTransaction.SORTIE);
        t.setDate(date != null ? date : java.time.LocalDate.now());
        t.setDescription(description);
        t.setMontant(montant);
        t.setCategorie(categorie);
        t.setStatut(StatutTransaction.VALIDE);
        t.setValidateur(creePar);
        t.setDateValidation(LocalDateTime.now());
        t.setSourceType(sourceType);
        t.setSourceUniqueId(sourceUniqueId);
        t.setFarm(farm);
        t.setCreePar(creePar);
        t.setInitialisation(Initialisation.init());

        Transaction saved = transactionRepo.save(t);
        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO createMouvementClient(TypeTransaction type, Farm farm, Client client, Double montant,
            String categorie, java.time.LocalDate date, String description, SourceTransaction sourceType,
            String sourceUniqueId, Utilisateurs creePar) {
        Transaction t = new Transaction();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setRef(generateRef());
        t.setType(type);
        t.setDate(date != null ? date : java.time.LocalDate.now());
        t.setDescription(description);
        t.setMontant(montant);
        t.setCategorie(categorie);
        t.setStatut(StatutTransaction.VALIDE);
        t.setValidateur(creePar);
        t.setDateValidation(LocalDateTime.now());
        t.setClient(client);
        t.setSourceType(sourceType);
        t.setSourceUniqueId(sourceUniqueId);
        t.setFarm(farm);
        t.setCreePar(creePar);
        t.setInitialisation(Initialisation.init());

        Transaction saved = transactionRepo.save(t);
        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void setRemovedBySource(String sourceUniqueId, boolean removed) {
        transactionRepo.findBySourceUniqueId(sourceUniqueId).ifPresent(t -> {
            t.getInitialisation().setRemoved(removed);
            transactionRepo.save(t);
        });
    }

    // Signature d'origine, conservée telle quelle pour tous les appelants existants : ne
    // touche JAMAIS aux rattachements site/poulailler (voir la surcharge ci-dessous).
    @Override
    @Transactional
    public void syncSortie(Projets projet, Farm farm, Double montant, String categorie, LocalDate date,
                            String description, SourceTransaction sourceType, String sourceUniqueId, Utilisateurs creePar) {
        syncSortie(projet, farm, montant, categorie, date, description, sourceType, sourceUniqueId, creePar, null, null, false);
    }

    // Surcharge pour les sources qui CONNAISSENT leur poulailler (aliment, soins) :
    // appliquerRattachement = true fait suivre la transaction (site du projet, poulailler
    // de la source) à chaque nouvelle saisie ou modification de la source.
    @Override
    @Transactional
    public void syncSortie(Projets projet, Farm farm, Double montant, String categorie, LocalDate date,
                            String description, SourceTransaction sourceType, String sourceUniqueId, Utilisateurs creePar,
                            com.diafarms.ml.models.Batiment batiment, com.diafarms.ml.models.Site site, boolean appliquerRattachement) {
        var existante = transactionRepo.findBySourceUniqueId(sourceUniqueId);
        boolean doitExister = montant != null && montant > 0;

        if (!doitExister) {
            existante.ifPresent(t -> {
                if (!Boolean.TRUE.equals(t.getInitialisation().getRemoved())) {
                    t.getInitialisation().setRemoved(true);
                    transactionRepo.save(t);
                }
            });
            return;
        }

        if (existante.isPresent()) {
            Transaction t = existante.get();
            t.setMontant(montant);
            t.setDescription(description);
            t.setProjet(projet);
            if (appliquerRattachement) {
                t.setBatiment(batiment);
                t.setSite(site);
            }
            if (Boolean.TRUE.equals(t.getInitialisation().getRemoved())) {
                t.getInitialisation().setRemoved(false);
            }
            t.getInitialisation().setUpdatedAt(LocalDateTime.now());
            transactionRepo.save(t);
            return;
        }

        Transaction t = new Transaction();
        t.setUniqueId(java.util.UUID.randomUUID().toString());
        t.setRef(generateRef());
        t.setType(TypeTransaction.SORTIE);
        t.setDate(date != null ? date : LocalDate.now());
        t.setDescription(description);
        t.setMontant(montant);
        t.setCategorie(categorie);
        t.setStatut(StatutTransaction.VALIDE);
        t.setValidateur(creePar);
        t.setDateValidation(LocalDateTime.now());
        t.setProjet(projet);
        if (appliquerRattachement) {
            t.setBatiment(batiment);
            t.setSite(site);
        }
        t.setSourceType(sourceType);
        t.setSourceUniqueId(sourceUniqueId);
        t.setFarm(farm);
        t.setCreePar(creePar);
        t.setInitialisation(Initialisation.init());
        transactionRepo.save(t);
    }

    @Override
    @Transactional
    public void updateMontantBySource(String sourceUniqueId, Double montant) {
        transactionRepo.findBySourceUniqueId(sourceUniqueId).ifPresent(t -> {
            t.setMontant(montant);
            t.getInitialisation().setUpdatedAt(LocalDateTime.now());
            transactionRepo.save(t);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionDTO findDtoBySource(String sourceUniqueId) {
        return transactionRepo.findBySourceUniqueId(sourceUniqueId).map(TransactionDTO::fromEntity).orElse(null);
    }

    @Override
    @Transactional
    public void updateDateBySource(String sourceUniqueId, LocalDate date) {
        transactionRepo.findBySourceUniqueId(sourceUniqueId).ifPresent(t -> {
            t.setDate(date);
            t.getInitialisation().setUpdatedAt(LocalDateTime.now());
            transactionRepo.save(t);
        });
    }

    @Override
    @Transactional
    public void updateDescriptionBySource(String sourceUniqueId, String description) {
        transactionRepo.findBySourceUniqueId(sourceUniqueId).ifPresent(t -> {
            t.setDescription(description);
            t.getInitialisation().setUpdatedAt(LocalDateTime.now());
            transactionRepo.save(t);
        });
    }

    @Override
    @Transactional
    public TransactionDTO update(String uniqueId, TransactionUpdate data) {
        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));
        if (TransactionDTO.saisieSourceGeneree(t.getSourceType()) != null) {
            return updateRattachementSeul(t, data);
        }
        ensurePasLieeAUneVente(t);

        if (data.getType() != null) t.setType(TypeTransaction.valueOf(data.getType()));
        if (data.getDate() != null) t.setDate(data.getDate());
        if (data.getDescription() != null) t.setDescription(data.getDescription());
        if (data.getMontant() != null) t.setMontant(data.getMontant());
        if (data.getCategorie() != null) t.setCategorie(data.getCategorie());
        if (Boolean.TRUE.equals(data.getCommun())) {
            t.setProjet(null);
            t.setProjetsConcernes(data.getProjetsConcernesUniqueIds() != null && !data.getProjetsConcernesUniqueIds().isEmpty()
                    ? projetsDeLaFerme(data.getProjetsConcernesUniqueIds())
                    : new java.util.ArrayList<>());
        } else if (Boolean.FALSE.equals(data.getCommun())) {
            if (data.getProjetUniqueId() == null || data.getProjetUniqueId().isBlank()) {
                throw new IllegalArgumentException("Un projet doit être sélectionné si la transaction n'est pas commune.");
            }
            Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                    .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                    .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));
            t.setProjet(projet);
            t.setProjetsConcernes(new java.util.ArrayList<>());
        }
        // Rattachements facultatifs : null = inchangé, "" = retiré, valeur = défini.
        Utilisateurs utilisateurCourant = getCurrentUserSafe();
        if (data.getSiteUniqueId() != null) {
            t.setSite(resoudreSite(data.getSiteUniqueId(), utilisateurCourant));
        }
        if (data.getBatimentUniqueId() != null) {
            t.setBatiment(resoudreBatiment(data.getBatimentUniqueId(), utilisateurCourant));
        }
        if (t.getInitialisation() != null) {
            t.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }

        Transaction saved = transactionRepo.save(t);

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Modification de la transaction '" + saved.getRef() + "'");
        }

        return TransactionDTO.fromEntity(saved);
    }

    // Transaction générée par une saisie : seul le rattachement (site, poulailler) se
    // corrige depuis la Comptabilité ; montant, date, type, projet, catégorie et
    // description appartiennent à la saisie source (réécrits à chaque modification de
    // celle-ci par syncSortie). Un champ envoyé à l'identique n'est pas un changement : le
    // formulaire web peut renvoyer toute la transaction. Pour l'aliment et les soins, une
    // modification ultérieure de la saisie source réapplique son propre poulailler.
    private TransactionDTO updateRattachementSeul(Transaction t, TransactionUpdate data) {
        java.util.List<String> changes = new java.util.ArrayList<>();
        if (data.getType() != null && (t.getType() == null || !data.getType().equals(t.getType().name()))) changes.add("type");
        if (data.getDate() != null && !data.getDate().equals(t.getDate())) changes.add("date");
        if (data.getMontant() != null && (t.getMontant() == null || Math.abs(data.getMontant() - t.getMontant()) > 0.005)) changes.add("montant");
        if (data.getCategorie() != null && !data.getCategorie().equals(t.getCategorie())) changes.add("catégorie");
        if (data.getDescription() != null && !data.getDescription().equals(t.getDescription() == null ? "" : t.getDescription())) changes.add("description");
        String projetActuel = t.getProjet() != null ? t.getProjet().getUniqueId() : null;
        if (Boolean.TRUE.equals(data.getCommun()) && projetActuel != null) changes.add("projet");
        if (Boolean.FALSE.equals(data.getCommun()) && projetActuel == null) changes.add("projet");
        if (data.getProjetUniqueId() != null && !data.getProjetUniqueId().isBlank() && !data.getProjetUniqueId().equals(projetActuel)) changes.add("projet");
        if (Boolean.TRUE.equals(data.getCommun()) && data.getProjetsConcernesUniqueIds() != null) {
            java.util.Set<String> actuels = new java.util.HashSet<>();
            if (t.getProjetsConcernes() != null) t.getProjetsConcernes().forEach(p -> actuels.add(p.getUniqueId()));
            if (!actuels.equals(new java.util.HashSet<>(data.getProjetsConcernesUniqueIds()))) changes.add("projets concernés");
        }
        if (!changes.isEmpty()) {
            throw new IllegalArgumentException("Cette transaction est générée automatiquement par "
                    + TransactionDTO.saisieSourceGeneree(t.getSourceType())
                    + " : modifiez cette saisie à la place (" + String.join(", ", new java.util.LinkedHashSet<>(changes))
                    + "). Ici, seuls le site et le poulailler se corrigent.");
        }
        Utilisateurs utilisateurCourant = getCurrentUserSafe();
        if (data.getSiteUniqueId() != null) {
            t.setSite(resoudreSite(data.getSiteUniqueId(), utilisateurCourant));
        }
        if (data.getBatimentUniqueId() != null) {
            t.setBatiment(resoudreBatiment(data.getBatimentUniqueId(), utilisateurCourant));
        }
        if (t.getInitialisation() != null) {
            t.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }
        Transaction saved = transactionRepo.save(t);
        if (utilisateurCourant != null) {
            logs.addLogs(utilisateurCourant.getId(), saved.getId(), "Transaction",
                    "Modification du rattachement (site, poulailler) de la transaction '" + saved.getRef() + "'");
        }
        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId, String motif) {
        Utilisateurs currentUser = getCurrentUserSafe();

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));
        ensurePasLieeAUneVente(t);
        // Motif exigé pour supprimer, pas pour restaurer.
        String motifValide = Boolean.TRUE.equals(t.getInitialisation().getRemoved()) ? null : MotifSuppressionRequest.exiger(motif);

        // Même autorité que valider/rejeter/confirmerSuppression — plus de
        // suppression/restauration directe sans passer par une demande, voir
        // demanderSuppression/confirmerSuppression.
        ensureCanConfirmerSuppression(currentUser, t.getProjet());

        t.getInitialisation().setRemoved(!t.getInitialisation().getRemoved());
        if (motifValide != null) t.setMotifSuppression(motifValide);
        transactionRepo.save(t);
        boolean removed = t.getInitialisation().getRemoved();

        // Le solde client n'est plus un compteur ajusté à la volée : il se recalcule
        // entièrement à partir des ventes/paiements/imputations/remboursements actifs
        // (voir CompteClientService). Rien à rattraper ici.

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), t.getId(), "Transaction",
                    (removed ? "Suppression" : "Restauration") + " de la transaction '" + t.getRef() + "'"
                            + (removed ? ", motif : " + motifValide : ""));
        }

        return removed ? "Transaction supprimée." : "Transaction récupérée.";
    }

    @Override
    @Transactional
    public TransactionDTO demanderSuppression(String uniqueId, String motif) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanDemanderSuppression(currentUser);
        String motifValide = MotifSuppressionRequest.exiger(motif);

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));
        ensurePasLieeAUneVente(t);
        if (t.getDemandeSuppressionPar() != null) {
            throw new IllegalArgumentException("Une demande de suppression est déjà en attente pour cette transaction.");
        }

        t.setDemandeSuppressionPar(currentUser);
        t.setDateDemandeSuppression(LocalDateTime.now());
        t.setMotifSuppression(motifValide);
        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Demande de suppression de la transaction '" + saved.getRef() + "', motif : " + motifValide);
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO confirmerSuppression(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));
        ensureCanConfirmerSuppression(currentUser, t.getProjet());
        // Une demande faite avant ce verrou sur une transaction de vente ne peut plus
        // qu'être refusée (annulerDemandeSuppression reste permis pour la nettoyer).
        ensurePasLieeAUneVente(t);
        if (t.getDemandeSuppressionPar() == null) {
            throw new IllegalArgumentException("Aucune demande de suppression en attente pour cette transaction.");
        }

        // demandeSuppressionPar/dateDemandeSuppression volontairement conservés
        // (pas remis à null) : trace de qui a demandé, même après confirmation.
        t.getInitialisation().setRemoved(true);
        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Suppression confirmée pour la transaction '" + saved.getRef() + "'");
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO annulerDemandeSuppression(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));
        ensureCanConfirmerSuppression(currentUser, t.getProjet());
        if (t.getDemandeSuppressionPar() == null) {
            throw new IllegalArgumentException("Aucune demande de suppression en attente pour cette transaction.");
        }

        t.setDemandeSuppressionPar(null);
        t.setDateDemandeSuppression(null);
        t.setMotifSuppression(null);
        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Demande de suppression refusée pour la transaction '" + saved.getRef() + "'");
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO valider(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));

        // Un RESPONSABLE peut valider une transaction rattachée à SON projet (voir
        // isResponsableDuProjet) ; une transaction commune (pas de projet précis) reste
        // réservée à un ADMIN, faute de responsable unique et non-ambigu à qui confier ce
        // pouvoir.
        if (!isAdmin(currentUser) && !isResponsableDuProjet(currentUser, t.getProjet())) {
            throw new IllegalArgumentException("Seul un administrateur ou le responsable de ce projet peut valider cette transaction.");
        }

        t.setStatut(StatutTransaction.VALIDE);
        t.setCommentaireRejet(null);
        t.setValidateur(currentUser);
        t.setDateValidation(LocalDateTime.now());
        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Validation de la transaction '" + saved.getRef() + "'");
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public TransactionDTO rejeter(String uniqueId, RejectTransactionRequest data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (data.getCommentaire() == null || data.getCommentaire().isBlank()) {
            throw new IllegalArgumentException("Un commentaire est obligatoire pour rejeter une transaction.");
        }

        Transaction t = transactionRepo.findByUniqueId(uniqueId)
                .filter(x -> memeFerme(x.getFarm(), getCurrentUserSafe()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + uniqueId));

        if (!isAdmin(currentUser) && !isResponsableDuProjet(currentUser, t.getProjet())) {
            throw new IllegalArgumentException("Seul un administrateur ou le responsable de ce projet peut rejeter cette transaction.");
        }
        // Rejeter une vente = la retirer des comptes sans rendre le stock ni corriger le
        // solde : même désynchronisation qu'une suppression, passer par la vente.
        // Transaction générée par une saisie : la rejeter revient à supprimer la saisie.
        String saisie = TransactionDTO.saisieSourceGeneree(t.getSourceType());
        if (saisie != null) {
            throw new IllegalArgumentException("Cette transaction est générée automatiquement par " + saisie
                    + " : pour la rejeter, supprimez cette saisie (sa transaction sera retirée avec elle).");
        }
        ensurePasLieeAUneVente(t);

        t.setStatut(StatutTransaction.REJETE);
        t.setCommentaireRejet(data.getCommentaire());
        t.setValidateur(currentUser);
        t.setDateValidation(LocalDateTime.now());
        Transaction saved = transactionRepo.save(t);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Transaction",
                    "Rejet de la transaction '" + saved.getRef() + "' : " + data.getCommentaire());
        }

        return TransactionDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<TransactionDTO> list(int page, int size, String search, TypeTransaction type, StatutTransaction statut,
                                                   String projetUniqueId, String financierUniqueId, String vendeurUniqueId,
                                                   LocalDate dateDebut, LocalDate dateFin) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createdAt"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        // hasX/valeurs factices quand absent — voir TransactionRepo.search() : jamais
        // comparer un paramètre à NULL dans ces requêtes (Postgres ne peut pas en
        // déterminer le type). Les valeurs factices (TypeTransaction.ENTREE,
        // StatutTransaction.VALIDE, "") ne sont jamais évaluées utilement grâce au
        // court-circuit "hasX = false OR ...".
        boolean hasType = type != null;
        TypeTransaction typeParam = type != null ? type : TypeTransaction.ENTREE;
        boolean hasStatut = statut != null;
        StatutTransaction statutParam = statut != null ? statut : StatutTransaction.VALIDE;
        boolean hasSearch = search != null && !search.isBlank();
        String searchParam = hasSearch ? "%" + search.trim().toLowerCase() + "%" : "";
        boolean hasProjet = projetUniqueId != null && !projetUniqueId.isBlank();
        String projetParam = hasProjet ? projetUniqueId : "";
        LocalDate dDeb = deb(dateDebut);
        LocalDate dFin = fin(dateFin);

        String vendeurScope = resolveVendeurScopeForList(currentUser, vendeurUniqueId);
        List<Long> scopedProjetIds = resolveProjetIdsScopeForList(currentUser, farmId, financierUniqueId);

        Page<Transaction> transactionsPage;
        if (vendeurScope != null) {
            transactionsPage = transactionRepo.searchByCreePar(farmId, vendeurScope, hasType, typeParam, hasStatut, statutParam,
                    dDeb, dFin, hasSearch, searchParam, pageable);
        } else if (scopedProjetIds != null && scopedProjetIds.isEmpty()) {
            transactionsPage = Page.empty(pageable);
        } else if (scopedProjetIds != null) {
            transactionsPage = transactionRepo.searchScoped(scopedProjetIds, hasType, typeParam, hasStatut, statutParam,
                    hasProjet, projetParam, dDeb, dFin, hasSearch, searchParam, pageable);
        } else {
            transactionsPage = transactionRepo.search(farmId, hasType, typeParam, hasStatut, statutParam,
                    hasProjet, projetParam, dDeb, dFin, hasSearch, searchParam, pageable);
        }

        List<TransactionDTO> dtoList = transactionsPage.getContent().stream()
                .map(TransactionDTO::fromEntity)
                .toList();
        enrichMontantReel(dtoList);

        return new PaginatedResponse<>(
                dtoList,
                transactionsPage.getNumber() + 1,
                transactionsPage.getTotalPages(),
                transactionsPage.getTotalElements(),
                transactionsPage.getSize()
        );
    }

    /** Corrige montantReel (initialisé = montant par TransactionDTO.fromEntity) pour
     * les transactions issues d'une vente à crédit partielle/totale (voir
     * VenteOeufs/VenteReforme.montantRapporte) — montant reste la valeur théorique
     * (quantité × prix), montantReel ce que le vendeur a effectivement rapporté ce
     * jour-là. sourceUniqueId pointe vers la LIGNE de répartition précise (voir
     * Transaction.sourceUniqueId), donc le ratio réel/théorique de LA VENTE ENTIÈRE
     * s'applique tel quel à la part (montant) déjà attribuée à ce projet — même
     * hypothèse proportionnelle que getVentesReelParProjet. Enrichit aussi clientNom
     * (même source, aucune requête supplémentaire). Recherche groupée (2 requêtes
     * max, pas une par transaction) pour rester utilisable sur une liste paginée. */
    private void enrichMontantReel(List<TransactionDTO> dtoList) {
        List<String> oeufsIds = dtoList.stream()
                .filter(d -> d.getSourceType() == SourceTransaction.VENTE_OEUFS && d.getSourceUniqueId() != null)
                .map(TransactionDTO::getSourceUniqueId).distinct().toList();
        List<String> reformeIds = dtoList.stream()
                .filter(d -> d.getSourceType() == SourceTransaction.VENTE_REFORME && d.getSourceUniqueId() != null)
                .map(TransactionDTO::getSourceUniqueId).distinct().toList();

        // Vente diverse : pas de ratio (pas de montant rapporté), juste l'éventuelle demande
        // de suppression de LA VENTE, pour que la Comptabilité propose confirmer/refuser.
        List<String> diversesIds = dtoList.stream()
                .filter(d -> d.getSourceType() == SourceTransaction.VENTE_DIVERSE && d.getSourceUniqueId() != null)
                .map(TransactionDTO::getSourceUniqueId).distinct().toList();
        if (!diversesIds.isEmpty()) {
            Map<String, String> demandeParVente = new HashMap<>();
            for (com.diafarms.ml.models.VenteDiverse v : venteDiverseRepo.findByUniqueIds(diversesIds)) {
                if (v.getDemandeSuppressionPar() != null) {
                    demandeParVente.put(v.getUniqueId(), v.getDemandeSuppressionPar().getFullName());
                }
            }
            for (TransactionDTO d : dtoList) {
                if (d.getSourceType() == SourceTransaction.VENTE_DIVERSE) {
                    d.setVenteDemandeSuppressionParNom(demandeParVente.get(d.getSourceUniqueId()));
                }
            }
        }

        if (oeufsIds.isEmpty() && reformeIds.isEmpty()) return;

        Map<String, RepartitionRatioDTO> infoParRepartition = new HashMap<>();
        if (!oeufsIds.isEmpty()) {
            for (RepartitionRatioDTO r : venteOeufsRepartitionRepo.findRatiosByUniqueIds(oeufsIds)) {
                infoParRepartition.put(r.getRepartitionUniqueId(), r);
            }
        }
        if (!reformeIds.isEmpty()) {
            for (RepartitionRatioDTO r : venteReformeRepartitionRepo.findRatiosByUniqueIds(reformeIds)) {
                infoParRepartition.put(r.getRepartitionUniqueId(), r);
            }
        }

        for (TransactionDTO d : dtoList) {
            RepartitionRatioDTO info = infoParRepartition.get(d.getSourceUniqueId());
            if (info == null) continue;
            if (d.getMontant() != null) {
                d.setMontantReel(d.getMontant() * ratio(info));
            }
            d.setClientNom(info.getClientNom());
            d.setVenteUniqueId(info.getVenteUniqueId());
            d.setVenteDemandeSuppressionParNom(info.getVenteDemandeSuppressionParNom());
        }
    }

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

    @Override
    @Transactional(readOnly = true)
    public TransactionStatsDTO getStats(String financierUniqueId, LocalDate dateDebut, LocalDate dateFin) {
        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        List<Long> scopedProjetIds = resolveProjetIdsScope(currentUser, farmId, financierUniqueId);

        if (scopedProjetIds != null && scopedProjetIds.isEmpty()) {
            return TransactionStatsDTO.builder()
                    .nbValide(0).nbAttente(0).nbRejete(0)
                    .totalEntreesValidees(0.0).totalSortiesValidees(0.0)
                    .totalVenteOeufs(0.0).totalVenteReforme(0.0)
                    .totalMontantRecuVentes(0.0).totalDuParVendeurs(0.0).totalDuParClients(0.0)
                    .totalVendu(0.0).totalEncaisse(0.0).totalRembourse(0.0)
                    .totalDuClients(0.0).totalAvancesClients(0.0)
                    .vueParProjet(true)
                    .build();
        }

        long nbValide, nbAttente, nbRejete;
        Double totalEntrees, totalSorties, totalVenteOeufs, totalVenteReforme;
        // Bornes toujours concrètes (jamais null) — voir deb()/fin().
        LocalDate dDeb = deb(dateDebut);
        LocalDate dFin = fin(dateFin);

        if (scopedProjetIds != null) {
            nbValide = transactionRepo.countByProjetIdsAndStatut(scopedProjetIds, StatutTransaction.VALIDE, dDeb, dFin);
            nbAttente = transactionRepo.countByProjetIdsAndStatut(scopedProjetIds, StatutTransaction.EN_ATTENTE, dDeb, dFin);
            nbRejete = transactionRepo.countByProjetIdsAndStatut(scopedProjetIds, StatutTransaction.REJETE, dDeb, dFin);
            totalEntrees = transactionRepo.sumMontantValideByProjetIdsAndType(scopedProjetIds, TypeTransaction.ENTREE, dDeb, dFin);
            totalSorties = transactionRepo.sumMontantValideByProjetIdsAndType(scopedProjetIds, TypeTransaction.SORTIE, dDeb, dFin);
            totalVenteOeufs = transactionRepo.sumMontantValideByProjetIdsAndSourceType(scopedProjetIds, SourceTransaction.VENTE_OEUFS, dDeb, dFin);
            totalVenteReforme = transactionRepo.sumMontantValideByProjetIdsAndSourceType(scopedProjetIds, SourceTransaction.VENTE_REFORME, dDeb, dFin);
        } else {
            nbValide = transactionRepo.countByFarmIdAndStatutAndDateRange(farmId, StatutTransaction.VALIDE, dDeb, dFin);
            nbAttente = transactionRepo.countByFarmIdAndStatutAndDateRange(farmId, StatutTransaction.EN_ATTENTE, dDeb, dFin);
            nbRejete = transactionRepo.countByFarmIdAndStatutAndDateRange(farmId, StatutTransaction.REJETE, dDeb, dFin);
            totalEntrees = transactionRepo.sumMontantValideByTypeAndDateRange(farmId, TypeTransaction.ENTREE, dDeb, dFin);
            totalSorties = transactionRepo.sumMontantValideByTypeAndDateRange(farmId, TypeTransaction.SORTIE, dDeb, dFin);
            totalVenteOeufs = transactionRepo.sumMontantValideBySourceTypeAndDateRange(farmId, SourceTransaction.VENTE_OEUFS, dDeb, dFin);
            totalVenteReforme = transactionRepo.sumMontantValideBySourceTypeAndDateRange(farmId, SourceTransaction.VENTE_REFORME, dDeb, dFin);
        }

        // Ferme entière, jamais scopé par projet/comptable (voir TransactionStatsDTO) —
        // le montant réellement rapporté, la dette vendeur et la dette client sont des
        // notions de vendeur/client/ferme, pas de projet. La décomposition PAR PROJET
        // existe quand même séparément (voir getVentesReelParProjet ci-dessous,
        // consommée par Reporting.tsx) : ces chiffres-ci restent volontairement un
        // simple complément global, pas un rapport scopé. totalMontantRecuVentes ne
        // compte plus le théorique rapporté des ventes À CLIENT (celles-ci sont payées
        // via PaiementClient, plus jamais via montantRapporte — voir
        // VenteOeufsRepo/VenteReformeRepo.sumRapporteSansClient), sous peine de
        // double-compter le même encaissement.
        Farm farm = currentUser != null ? currentUser.getFarm() : null;
        double rapporteSansClientOeufs = nz(venteOeufsRepo.sumRapporteSansClient(farmId, dDeb, dFin));
        double rapporteSansClientReforme = nz(venteReformeRepo.sumRapporteSansClient(farmId, dDeb, dFin));
        double venteDiverseValidee = nz(transactionRepo.sumMontantValideBySourceTypeAndDateRange(farmId, SourceTransaction.VENTE_DIVERSE, dDeb, dFin));
        double paiementsClients = nz(paiementClientRepo.sumActifsByFarmAndDates(farmId, dDeb, dFin));

        double montantRecuVentes = paiementsClients + rapporteSansClientOeufs + rapporteSansClientReforme + venteDiverseValidee;
        double duParVendeurs = nz(soldeVendeurRepo.sumSoldePositifByFarmId(farmId));
        // STALE : soldeClientRepo.sumSoldePositifByFarmId lisait une table de solde qui
        // n'est plus tenue à jour (voir SoldeClientServiceImpl) — le solde est désormais
        // entièrement recalculé à partir des ventes/paiements/imputations/remboursements actifs.
        double duParClients = soldeClientService.sumSoldePositif(farm);
        double avancesClients = soldeClientService.sumAvances(farm);

        // Vendu/Encaissé/Remboursé/Dû/Avances (circuit argent client) : uniquement en
        // vue ferme entière (jamais scopée) — encaissé/remboursé/dû/avances ne peuvent
        // pas être rattachés fiablement à un seul projet (paiements et remboursements
        // clients n'ont pas de projet).
        boolean vueParProjet = scopedProjetIds != null;
        double vendu;
        double encaisse;
        double rembourse;
        double totalDuClients;
        double totalAvancesClients;
        if (vueParProjet) {
            vendu = nz(totalVenteOeufs) + nz(totalVenteReforme);
            encaisse = 0.0;
            rembourse = 0.0;
            totalDuClients = 0.0;
            totalAvancesClients = 0.0;
        } else {
            vendu = nz(totalVenteOeufs) + nz(totalVenteReforme) + venteDiverseValidee;
            encaisse = nz(transactionRepo.sumEntreesHorsVentesStock(farmId, dDeb, dFin))
                    + rapporteSansClientOeufs + rapporteSansClientReforme;
            rembourse = nz(remboursementClientRepo.sumActifsByFarmAndDates(farmId, dDeb, dFin));
            totalDuClients = duParClients;
            totalAvancesClients = avancesClients;
        }

        return TransactionStatsDTO.builder()
                .nbValide(nbValide)
                .nbAttente(nbAttente)
                .nbRejete(nbRejete)
                .totalEntreesValidees(totalEntrees != null ? totalEntrees : 0.0)
                .totalSortiesValidees(totalSorties != null ? totalSorties : 0.0)
                .totalMontantRecuVentes(montantRecuVentes)
                .totalDuParVendeurs(duParVendeurs)
                .totalDuParClients(duParClients)
                .totalVenteOeufs(totalVenteOeufs != null ? totalVenteOeufs : 0.0)
                .totalVenteReforme(totalVenteReforme != null ? totalVenteReforme : 0.0)
                .totalVendu(vendu)
                .totalEncaisse(encaisse)
                .totalRembourse(rembourse)
                .totalDuClients(totalDuClients)
                .totalAvancesClients(totalAvancesClients)
                .vueParProjet(vueParProjet)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.diafarms.ml.DTO.DepenseRattachementDTO> getDepensesParRattachement(LocalDate dateDebut, LocalDate dateFin) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null || isPureResponsable(currentUser)) {
            return java.util.List.of();
        }
        return transactionRepo.sumSortiesParRattachement(currentUser.getFarm().getId(), deb(dateDebut), fin(dateFin)).stream()
                .map(r -> com.diafarms.ml.DTO.DepenseRattachementDTO.builder()
                        .siteUniqueId((String) r[0])
                        .siteNom((String) r[1])
                        .batimentUniqueId((String) r[2])
                        .batimentNom((String) r[3])
                        .total(((Number) r[4]).doubleValue())
                        .nombre(((Number) r[5]).longValue())
                        .build())
                .sorted((a, b) -> Double.compare(b.getTotal(), a.getTotal()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjetVenteReelDTO> getVentesReelParProjet(LocalDate dateDebut, LocalDate dateFin) {
        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;
        if (farmId == null) return List.of();

        List<VenteRepartitionReelDTO> lignes = new java.util.ArrayList<>();
        lignes.addAll(venteOeufsRepartitionRepo.findReelParProjet(farmId, deb(dateDebut), fin(dateFin)));
        lignes.addAll(venteReformeRepartitionRepo.findReelParProjet(farmId, deb(dateDebut), fin(dateFin)));

        java.util.Map<String, ProjetVenteReelDTO> parProjet = new java.util.LinkedHashMap<>();
        for (VenteRepartitionReelDTO ligne : lignes) {
            // Aucun écart déclaré sur cette vente (montantRapporte jamais saisi) : pas
            // de dette connue, la part théorique du projet compte pour son plein
            // montant — même convention que sumMontantRapporteByFarmIdAndDateRange.
            double ratio = (ligne.getVenteMontantRapporte() != null && ligne.getVenteMontant() != null && ligne.getVenteMontant() != 0)
                    ? ligne.getVenteMontantRapporte() / ligne.getVenteMontant()
                    : 1.0;
            double theorique = nz(ligne.getMontantAttribue());
            double reel = theorique * ratio;

            ProjetVenteReelDTO r = parProjet.get(ligne.getProjetUniqueId());
            if (r == null) {
                r = ProjetVenteReelDTO.builder()
                        .projetUniqueId(ligne.getProjetUniqueId())
                        .projetCode(ligne.getProjetCode())
                        .montantTheorique(0.0)
                        .montantReel(0.0)
                        .build();
                parProjet.put(ligne.getProjetUniqueId(), r);
            }
            r.setMontantTheorique(r.getMontantTheorique() + theorique);
            r.setMontantReel(r.getMontantReel() + reel);
        }
        return new java.util.ArrayList<>(parProjet.values());
    }
}
