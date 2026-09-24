package com.diafarms.ml.ServiceImpl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.diafarms.ml.DTO.RepriseRapportDTO;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.*;
import com.diafarms.ml.models.*;
import com.diafarms.ml.repository.ImputationPaiementRepo;
import com.diafarms.ml.repository.PaiementClientRepo;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// Reprise des données d'avant la refonte du circuit de l'argent client (voir
// docs/superpowers/specs/2026-09-23-circuit-argent-client-design.md, « Reprise de
// l'existant »). Une ferme = une transaction ; en simulation elle est annulée à la fin
// (rollback) : le rapport est calculé sur les données transformées puis rien n'est
// écrit. Idempotent : ce qui a déjà été repris n'est plus sélectionné (transactions
// déjà PAIEMENT_CLIENT / REMBOURSEMENT_CLI, ventes dont montantRapporte est déjà null,
// factures déjà legacy). Aucun REQUIRES_NEW : les services appelés (@Transactional
// REQUIRED) rejoignent la transaction de la ferme et sont annulés avec elle.
@Service
public class RepriseCircuitClientService {

    static final List<String> CATEGORIES_PAIEMENT = List.of("Remboursement client", "Acompte client", "Paiement client");
    static final String CATEGORIE_REMBOURSEMENT = "Remboursement au client";
    static final String PREFIXE_FACTURE = "Paiement facture ";

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate txTemplate;
    private final CompteClientService compteClientService;
    private final TransactionService transactionService;
    private final LogsServices logs;
    private final PaiementClientRepo paiementRepo;
    private final ImputationPaiementRepo imputationRepo;

    public RepriseCircuitClientService(PlatformTransactionManager txManager, CompteClientService compteClientService,
                                       TransactionService transactionService, LogsServices logs,
                                       PaiementClientRepo paiementRepo, ImputationPaiementRepo imputationRepo) {
        this.txTemplate = new TransactionTemplate(txManager);
        this.compteClientService = compteClientService;
        this.transactionService = transactionService;
        this.logs = logs;
        this.paiementRepo = paiementRepo;
        this.imputationRepo = imputationRepo;
    }

    private static double nz(Double v) { return v == null ? 0.0 : v; }
    private static double r2(double v) { return CalculImputation.arrondi(v); }
    private static String fcfa(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + " FCFA";
    }

    /** farms : fermes à traiter (une transaction chacune). lanceur : pour les logs. */
    public RepriseRapportDTO lancer(List<Farm> farms, boolean executer, Utilisateurs lanceur) {
        RepriseRapportDTO rapport = new RepriseRapportDTO();
        rapport.setExecute(executer);
        boolean plusieurs = farms.size() > 1;
        for (Farm farm : farms) {
            Long farmId = farm.getId();
            String nomFerme = farm.getNom() != null && !farm.getNom().isBlank() ? farm.getNom() : farm.getUniqueId();
            String prefixe = plusieurs ? "[" + nomFerme + "] " : "";
            // Rapport partiel par ferme, fusionné seulement si la ferme aboutit : un échec
            // (rollback de toute la ferme) ne laisse ni compteurs ni lignes trompeurs.
            RepriseRapportDTO partiel = new RepriseRapportDTO();
            try {
                txTemplate.executeWithoutResult(status -> {
                    traiterFerme(farmId, partiel, prefixe);
                    if (executer) {
                        if (lanceur != null) logs.addLogs(lanceur.getId(), farmId, "Farm",
                                "Reprise du circuit de l'argent client exécutée");
                    } else {
                        status.setRollbackOnly(); // simulation : rien n'est écrit
                    }
                });
            } catch (RuntimeException e) {
                rapport.getAvertissements().add("Ferme " + nomFerme
                        + " : échec, rien n'a été appliqué pour cette ferme — " + e.getMessage());
                continue;
            }
            rapport.getLignes().addAll(partiel.getLignes());
            rapport.getAvertissements().addAll(partiel.getAvertissements());
            rapport.setPaiementsCrees(rapport.getPaiementsCrees() + partiel.getPaiementsCrees());
            rapport.setRemboursementsCrees(rapport.getRemboursementsCrees() + partiel.getRemboursementsCrees());
            rapport.setRecopiesFacturesRetirees(rapport.getRecopiesFacturesRetirees() + partiel.getRecopiesFacturesRetirees());
            rapport.setVentesConverties(rapport.getVentesConverties() + partiel.getVentesConverties());
        }
        return rapport;
    }

    // ------------------------------------------------------------------------------

    private static final class Suivi {
        final Client client;
        final List<String> notes = new ArrayList<>();
        Suivi(Client c) { this.client = c; }
    }

    private void traiterFerme(Long farmId, RepriseRapportDTO rapport, String prefixe) {
        List<String> avert = rapport.getAvertissements();

        // Clients de la ferme (supprimés compris : leurs mouvements existent toujours).
        List<Client> clients = em.createQuery(
                "SELECT c FROM Client c WHERE c.farm.id = :f ORDER BY c.nom, c.id", Client.class)
                .setParameter("f", farmId).getResultList();
        Map<Long, Suivi> suivis = new LinkedHashMap<>();
        for (Client c : clients) suivis.put(c.getId(), new Suivi(c));

        // 1. Solde avant = ancien solde stocké (soldes_client), 0 si absent.
        Map<Long, Double> soldeAvant = new HashMap<>();
        for (Object[] row : em.createQuery(
                "SELECT s.client.id, s.solde FROM SoldeClient s WHERE s.client.farm.id = :f", Object[].class)
                .setParameter("f", farmId).getResultList()) {
            soldeAvant.merge((Long) row[0], nz((Double) row[1]), Double::sum);
        }

        // 6 (fait en premier : les paiements de l'étape 5 reprennent vente.commande).
        lierCommandes(farmId, suivis, avert, prefixe);

        // 2. Paiements saisis comme transactions manuelles.
        rapport.setPaiementsCrees(rapport.getPaiementsCrees() + reprendrePaiements(farmId, suivis, avert, prefixe));

        // 3. Remboursements saisis comme transactions manuelles (imputations à l'étape 7).
        List<RemboursementClient> remboursements = reprendreRemboursements(farmId, suivis, avert, prefixe);
        rapport.setRemboursementsCrees(rapport.getRemboursementsCrees() + remboursements.size());

        // 4. Recopies de « marquer payée » dans montantRapporte, factures existantes -> legacy.
        rapport.setRecopiesFacturesRetirees(rapport.getRecopiesFacturesRetirees()
                + retirerRecopiesFactures(farmId, suivis, avert, prefixe));

        // 5. montantRapporte des ventes avec client -> paiement client.
        int[] ventes = convertirMontantsRapportes(farmId, suivis);
        rapport.setVentesConverties(rapport.getVentesConverties() + ventes[0]);
        rapport.setPaiementsCrees(rapport.getPaiementsCrees() + ventes[1]);

        // 7. Imputations. Les remboursements repris D'ABORD, dans l'ordre des dates, chacun
        // sur l'argent reçu À SA DATE (paiements datés au plus tard du remboursement, moins
        // ce que les remboursements précédents ont déjà pris) : sinon des ventes
        // postérieures absorberaient l'avance qui a réellement été rendue, et le
        // remboursement disparaîtrait de la dette. PUIS les ventes (imputer, idempotent).
        remboursements.sort(Comparator.comparing(RemboursementClient::getDate).thenComparing(RemboursementClient::getId));
        for (RemboursementClient r : remboursements) imputerRemboursement(r, suivis, avert, prefixe);
        for (Suivi s : suivis.values()) compteClientService.imputer(s.client);

        // 8. Rapport par client.
        for (Suivi s : suivis.values()) {
            double avant = r2(soldeAvant.getOrDefault(s.client.getId(), 0.0));
            double apres = compteClientService.compte(s.client).getSolde();
            double ecart = r2(apres - avant);
            if (!s.notes.isEmpty() || Math.abs(ecart) >= 1) {
                RepriseRapportDTO.Ligne l = new RepriseRapportDTO.Ligne();
                l.setClientUniqueId(s.client.getUniqueId());
                l.setClientNom(prefixe + s.client.getNom());
                l.setSoldeAvant(avant);
                l.setSoldeApres(apres);
                l.setEcart(ecart);
                l.setNotes(s.notes);
                rapport.getLignes().add(l);
            }
        }
    }

    private Suivi suivi(Map<Long, Suivi> suivis, Client c) {
        return suivis.computeIfAbsent(c.getId(), k -> new Suivi(c));
    }

    // --- Étape 6 -------------------------------------------------------------------

    private void lierCommandes(Long farmId, Map<Long, Suivi> suivis, List<String> avert, String prefixe) {
        List<Commande> commandes = em.createQuery(
                "SELECT c FROM Commande c WHERE c.farm.id = :f ORDER BY c.id", Commande.class)
                .setParameter("f", farmId).getResultList();
        for (Commande c : commandes) {
            if (c.getVenteUniqueId() != null && !c.getVenteUniqueId().isBlank()) {
                Integer quantiteVente = null;
                boolean trouvee = false;
                if (c.getType() == TypeStockMagasin.OEUFS) {
                    VenteOeufs v = venteOeufs(c.getVenteUniqueId());
                    if (v != null) {
                        trouvee = true;
                        quantiteVente = v.getQuantiteOeufs();
                        if (v.getCommande() == null) { v.setCommande(c); em.merge(v); }
                    }
                } else {
                    VenteReforme v = venteReforme(c.getVenteUniqueId());
                    if (v != null) {
                        trouvee = true;
                        quantiteVente = v.getNombreSujets();
                        if (v.getCommande() == null) { v.setCommande(c); em.merge(v); }
                    }
                }
                if (!trouvee) {
                    avert.add(prefixe + "Commande " + c.getUniqueId() + " : vente " + c.getVenteUniqueId() + " introuvable.");
                } else if (c.getQuantiteLivree() != null && quantiteVente != null && c.getQuantiteLivree() > quantiteVente) {
                    // L'ancien modèle ne gardait que la dernière livraison.
                    avert.add(prefixe + "Commande " + c.getUniqueId() + " (" + (c.getClient() != null ? c.getClient().getNom() : "?")
                            + ") : " + c.getQuantiteLivree() + " livrés mais seule la dernière livraison (" + quantiteVente
                            + ") est rattachée ; les livraisons antérieures restent des ventes directes du client.");
                }
            }
            if ((c.getStatut() == Commande.StatutCommande.EN_ATTENTE || c.getStatut() == Commande.StatutCommande.CONFIRMEE)
                    && c.getQuantiteLivree() != null && c.getQuantiteLivree() > 0) {
                c.setStatut(Commande.StatutCommande.EN_LIVRAISON);
                em.merge(c);
                if (c.getClient() != null) suivi(suivis, c.getClient()).notes.add(
                        "Commande " + c.getUniqueId() + " passée EN_LIVRAISON (" + c.getQuantiteLivree() + "/" + c.getQuantite() + " livrés).");
            }
        }
    }

    private VenteOeufs venteOeufs(String uid) {
        return em.createQuery("SELECT v FROM VenteOeufs v WHERE v.uniqueId = :u", VenteOeufs.class)
                .setParameter("u", uid).getResultStream().findFirst().orElse(null);
    }

    private VenteReforme venteReforme(String uid) {
        return em.createQuery("SELECT v FROM VenteReforme v WHERE v.uniqueId = :u", VenteReforme.class)
                .setParameter("u", uid).getResultStream().findFirst().orElse(null);
    }

    // --- Étapes 2 et 3 -------------------------------------------------------------

    /** Transactions manuelles à reprendre ; REJETE et supprimées écartées. */
    private List<Transaction> transactionsManuelles(Long farmId, TypeTransaction type, List<String> categories,
                                                    List<String> avert, String prefixe) {
        List<Transaction> brutes = em.createQuery(
                "SELECT t FROM Transaction t JOIN FETCH t.client c WHERE c.farm.id = :f AND t.type = :type " +
                "AND t.categorie IN :cats AND t.sourceType = :manuel ORDER BY t.date, t.id", Transaction.class)
                .setParameter("f", farmId).setParameter("type", type).setParameter("cats", categories)
                .setParameter("manuel", SourceTransaction.MANUEL).getResultList();
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : brutes) {
            if (t.getInitialisation() != null && Boolean.TRUE.equals(t.getInitialisation().getRemoved())) {
                avert.add(prefixe + "Transaction " + t.getRef() + " (" + t.getCategorie() + ", " + fcfa(nz(t.getMontant()))
                        + ", " + t.getClient().getNom() + ", " + t.getDate() + ") supprimée : ignorée.");
                continue;
            }
            if (t.getStatut() == StatutTransaction.REJETE) {
                avert.add(prefixe + "Transaction " + t.getRef() + " (" + t.getCategorie() + ", " + fcfa(nz(t.getMontant()))
                        + ", " + t.getClient().getNom() + ", " + t.getDate() + ") rejetée : ignorée.");
                continue;
            }
            if (t.getMontant() == null || r2(t.getMontant()) <= 0) {
                avert.add(prefixe + "Transaction " + t.getRef() + " (" + t.getClient().getNom() + ") sans montant positif : ignorée.");
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private int reprendrePaiements(Long farmId, Map<Long, Suivi> suivis, List<String> avert, String prefixe) {
        int n = 0;
        for (Transaction t : transactionsManuelles(farmId, TypeTransaction.ENTREE, CATEGORIES_PAIEMENT, avert, prefixe)) {
            Client c = t.getClient();
            String desc = t.getDescription() == null ? "" : t.getDescription();
            OriginePaiement origine;
            Facture facture = null;
            Commande commande = null;
            if ("Acompte client".equals(t.getCategorie())) {
                origine = OriginePaiement.ACOMPTE;
                commande = commandeDeLAcompte(c, t);
            } else if (desc.startsWith(PREFIXE_FACTURE.trim())) {
                origine = OriginePaiement.FACTURE;
                facture = factureParNumero(farmId, numeroFacture(desc));
            } else {
                origine = OriginePaiement.REGLEMENT;
            }

            PaiementClient p = new PaiementClient();
            p.setUniqueId(UUID.randomUUID().toString());
            p.setFarm(c.getFarm());
            p.setClient(c);
            p.setDate(t.getDate());
            p.setMontant(r2(t.getMontant()));
            p.setMode(ModePaiement.ESPECES);
            p.setOrigine(origine);
            p.setCommande(commande);
            p.setFacture(facture);
            p.setObservations("Reprise de la transaction " + t.getRef() + " (" + t.getCategorie() + ")"
                    + (desc.isBlank() ? "" : " : " + desc));
            p.setRecuPar(t.getCreePar());
            p.setStatut(StatutMouvement.ACTIF);
            p.setInitialisation(Initialisation.init());
            em.persist(p);

            t.setSourceType(SourceTransaction.PAIEMENT_CLIENT);
            t.setSourceUniqueId(p.getUniqueId());
            t.setCategorie("Paiement client");
            if (t.getInitialisation() != null) Initialisation.updateDate(t.getInitialisation());
            em.merge(t);

            suivi(suivis, c).notes.add("Paiement repris de " + t.getRef() + " (" + t.getDate() + ", " + fcfa(p.getMontant())
                    + ", " + origine + (commande != null ? ", commande " + commande.getUniqueId() : "")
                    + (facture != null ? ", facture " + facture.getNumeroFacture() : "") + ").");
            n++;
        }
        return n;
    }

    // Ancien libellé : "Acompte sur commande — ..." sans référence à la commande. Rattaché
    // seulement s'il n'y a aucune ambiguïté (une seule commande du client avec cet acompte).
    private Commande commandeDeLAcompte(Client c, Transaction t) {
        List<Commande> candidates = em.createQuery(
                "SELECT k FROM Commande k WHERE k.client.id = :c AND k.montantAcompte > 0", Commande.class)
                .setParameter("c", c.getId()).getResultList();
        if (candidates.size() == 1) return candidates.get(0);
        List<Commande> memeMontant = candidates.stream()
                .filter(k -> Math.abs(nz(k.getMontantAcompte()) - nz(t.getMontant())) < 0.005).toList();
        return memeMontant.size() == 1 ? memeMontant.get(0) : null;
    }

    private static String numeroFacture(String desc) {
        String reste = desc.substring(PREFIXE_FACTURE.trim().length()).trim();
        int esp = reste.indexOf(' ');
        return esp < 0 ? reste : reste.substring(0, esp);
    }

    private Facture factureParNumero(Long farmId, String numero) {
        return em.createQuery("SELECT f FROM Facture f WHERE f.numeroFacture = :n AND f.farm.id = :f", Facture.class)
                .setParameter("n", numero).setParameter("f", farmId).getResultStream().findFirst().orElse(null);
    }

    private List<RemboursementClient> reprendreRemboursements(Long farmId, Map<Long, Suivi> suivis, List<String> avert,
                                                              String prefixe) {
        List<RemboursementClient> out = new ArrayList<>();
        for (Transaction t : transactionsManuelles(farmId, TypeTransaction.SORTIE, List.of(CATEGORIE_REMBOURSEMENT), avert, prefixe)) {
            Client c = t.getClient();
            RemboursementClient r = new RemboursementClient();
            r.setUniqueId(UUID.randomUUID().toString());
            r.setFarm(c.getFarm());
            r.setClient(c);
            r.setDate(t.getDate());
            r.setMontant(r2(t.getMontant()));
            r.setMode(ModePaiement.ESPECES);
            r.setMotif(t.getDescription() != null && !t.getDescription().isBlank() ? t.getDescription()
                    : "Reprise de la transaction " + t.getRef());
            r.setEffectuePar(t.getCreePar());
            r.setStatut(StatutMouvement.ACTIF);
            r.setInitialisation(Initialisation.init());
            em.persist(r);

            t.setSourceType(SourceTransaction.REMBOURSEMENT_CLI);
            t.setSourceUniqueId(r.getUniqueId());
            t.setCategorie(CATEGORIE_REMBOURSEMENT);
            if (t.getInitialisation() != null) Initialisation.updateDate(t.getInitialisation());
            em.merge(t);

            suivi(suivis, c).notes.add("Remboursement repris de " + t.getRef() + " (" + t.getDate() + ", " + fcfa(r.getMontant()) + ").");
            out.add(r);
        }
        return out;
    }

    // --- Étape 4 -------------------------------------------------------------------

    private int retirerRecopiesFactures(Long farmId, Map<Long, Suivi> suivis, List<String> avert, String prefixe) {
        // Factures d'avant la refonte : pas encore legacy et sans lignes.
        List<Facture> factures = em.createQuery(
                "SELECT f FROM Facture f JOIN FETCH f.client WHERE f.farm.id = :f AND f.legacy = false " +
                "AND NOT EXISTS (SELECT l FROM FactureLigne l WHERE l.facture = f) ORDER BY f.id", Facture.class)
                .setParameter("f", farmId).getResultList();
        int n = 0;
        for (Facture f : factures) {
            // Indépendant du statut actuel : une facture payée puis ANNULEE a quand même eu
            // ses paiements recopiés dans montantRapporte. Même libellé que l'ancien
            // marquerPayee : "Paiement facture <numéro>". Chaque paiement a été recopié tel
            // quel au moment où il a été saisi : on compte donc aussi les transactions
            // rejetées/supprimées depuis.
            String libelle = PREFIXE_FACTURE + f.getNumeroFacture();
            List<Transaction> txs = em.createQuery(
                    "SELECT t FROM Transaction t WHERE t.client.id = :c AND t.type = :e AND t.description LIKE :d",
                    Transaction.class)
                    .setParameter("c", f.getClient().getId()).setParameter("e", TypeTransaction.ENTREE)
                    .setParameter("d", libelle + "%").getResultList();
            double recopie = r2(txs.stream()
                    .filter(t -> t.getDescription().equals(libelle) || t.getDescription().startsWith(libelle + " "))
                    .mapToDouble(t -> nz(t.getMontant())).sum());
            Suivi s = suivi(suivis, f.getClient());
            if (Math.abs(recopie - nz(f.getMontantPaye())) >= 0.01) {
                avert.add(prefixe + "Facture " + f.getNumeroFacture() + " : paiements trouvés " + fcfa(recopie)
                        + " ≠ montant payé enregistré " + fcfa(nz(f.getMontantPaye())) + " (on retire les paiements trouvés).");
            }
            if (recopie > 0) {
                if (retirerDeLaVente(f, recopie, s, avert, prefixe)) n++;
            }
            f.setLegacy(true);
            em.merge(f);
        }
        return n;
    }

    private boolean retirerDeLaVente(Facture f, double recopie, Suivi s, List<String> avert, String prefixe) {
        String uid = f.getSourceUniqueId();
        boolean oeufs;
        switch (f.getSourceType()) {
            case VENTE_OEUFS -> oeufs = true;
            case VENTE_REFORME -> oeufs = false;
            case COMMANDE -> {
                Commande c = em.createQuery("SELECT c FROM Commande c WHERE c.uniqueId = :u", Commande.class)
                        .setParameter("u", uid).getResultStream().findFirst().orElse(null);
                if (c == null || c.getVenteUniqueId() == null) {
                    avert.add(prefixe + "Facture " + f.getNumeroFacture() + " : commande sans vente, recopie de "
                            + fcfa(recopie) + " non retirée.");
                    return false;
                }
                uid = c.getVenteUniqueId();
                oeufs = c.getType() == TypeStockMagasin.OEUFS;
            }
            default -> { return false; }
        }
        Object vente = oeufs ? venteOeufs(uid) : venteReforme(uid);
        if (vente == null) {
            avert.add(prefixe + "Facture " + f.getNumeroFacture() + " : vente " + uid + " introuvable, recopie de "
                    + fcfa(recopie) + " non retirée.");
            return false;
        }
        Double mr = oeufs ? ((VenteOeufs) vente).getMontantRapporte() : ((VenteReforme) vente).getMontantRapporte();
        if (mr == null) {
            avert.add(prefixe + "Facture " + f.getNumeroFacture() + " : la vente " + uid
                    + " n'a pas de montant rapporté, recopie de " + fcfa(recopie) + " non retirée.");
            return false;
        }
        double nouveau = r2(mr - recopie);
        if (nouveau < 0) {
            avert.add(prefixe + "Facture " + f.getNumeroFacture() + " : recopie " + fcfa(recopie)
                    + " supérieure au montant rapporté de la vente (" + fcfa(mr) + ") ; ramené à 0 (écart " + fcfa(-nouveau) + ").");
            nouveau = 0;
        }
        if (oeufs) { ((VenteOeufs) vente).setMontantRapporte(nouveau); em.merge(vente); }
        else { ((VenteReforme) vente).setMontantRapporte(nouveau); em.merge(vente); }
        s.notes.add("Facture " + f.getNumeroFacture() + " : " + fcfa(r2(mr - nouveau))
                + " de paiements recopiés retirés du montant rapporté de la vente (" + fcfa(mr) + " → " + fcfa(nouveau) + ").");
        return true;
    }

    // --- Étape 5 -------------------------------------------------------------------

    /** Retourne {ventes normalisées, paiements créés}. */
    private int[] convertirMontantsRapportes(Long farmId, Map<Long, Suivi> suivis) {
        int ventes = 0, paiements = 0;
        List<VenteOeufs> vo = em.createQuery(
                "SELECT v FROM VenteOeufs v JOIN FETCH v.client c WHERE c.farm.id = :f AND v.montantRapporte IS NOT NULL " +
                "AND (v.initialisation.removed IS NULL OR v.initialisation.removed = false) ORDER BY v.date, v.id", VenteOeufs.class)
                .setParameter("f", farmId).getResultList();
        for (VenteOeufs v : vo) {
            if (convertir(v.getClient(), v.getMontantRapporte(), v.getDate(), v.getCreePar(), v.getCommande(),
                    CibleImputation.VENTE_OEUFS, v.getUniqueId(), "vente d'œufs du " + v.getDate(), suivis)) paiements++;
            v.setMontantRapporte(null);
            em.merge(v);
            ventes++;
        }
        List<VenteReforme> vr = em.createQuery(
                "SELECT v FROM VenteReforme v JOIN FETCH v.client c WHERE c.farm.id = :f AND v.montantRapporte IS NOT NULL " +
                "AND (v.initialisation.removed IS NULL OR v.initialisation.removed = false) ORDER BY v.date, v.id", VenteReforme.class)
                .setParameter("f", farmId).getResultList();
        for (VenteReforme v : vr) {
            if (convertir(v.getClient(), v.getMontantRapporte(), v.getDate(), v.getCreePar(), v.getCommande(),
                    CibleImputation.VENTE_REFORME, v.getUniqueId(), "vente de réforme du " + v.getDate(), suivis)) paiements++;
            v.setMontantRapporte(null);
            em.merge(v);
            ventes++;
        }
        return new int[] { ventes, paiements };
    }

    private boolean convertir(Client c, Double montantRapporte, java.time.LocalDate date, Utilisateurs creePar,
                              Commande commande, CibleImputation type, String venteUid, String libelle,
                              Map<Long, Suivi> suivis) {
        double montant = r2(nz(montantRapporte));
        if (montant <= 0) return false;
        PaiementClient p = new PaiementClient();
        p.setUniqueId(UUID.randomUUID().toString());
        p.setFarm(c.getFarm());
        p.setClient(c);
        p.setDate(date);
        p.setMontant(montant);
        p.setMode(ModePaiement.ESPECES);
        p.setOrigine(OriginePaiement.VENTE);
        p.setCommande(commande);
        p.setVenteCibleType(type);
        p.setVenteCibleUniqueId(venteUid);
        p.setObservations("Reprise : montant rapporté de la " + libelle);
        p.setRecuPar(creePar);
        p.setStatut(StatutMouvement.ACTIF);
        p.setInitialisation(Initialisation.init());
        em.persist(p);
        transactionService.createMouvementClient(TypeTransaction.ENTREE, c.getFarm(), c, montant, "Paiement client", date,
                "Paiement de " + c.getNom() + " (à la vente, " + ModePaiement.ESPECES + ") — reprise",
                SourceTransaction.PAIEMENT_CLIENT, p.getUniqueId(), creePar);
        suivi(suivis, c).notes.add("Montant rapporté de la " + libelle + " (" + fcfa(montant) + ") converti en paiement client.");
        return true;
    }

    // --- Étape 7 (remboursements) ----------------------------------------------------

    private void imputerRemboursement(RemboursementClient r, Map<Long, Suivi> suivis, List<String> avert, String prefixe) {
        Client c = r.getClient();
        List<CalculImputation.Source> sources = sourcesALaDate(c, r.getDate());
        double dispo = r2(sources.stream().mapToDouble(s -> Math.max(0, s.reste())).sum());
        double pris = r2(Math.min(dispo, r.getMontant()));
        if (pris > 0) {
            for (CalculImputation.Affectation a : CalculImputation.prelever(sources, CibleImputation.REMBOURSEMENT.name(),
                    r.getUniqueId(), pris, r.getCommande() != null ? r.getCommande().getUniqueId() : null)) {
                compteClientService.enregistrer(c, a);
            }
        }
        double manque = r2(r.getMontant() - pris);
        if (manque > 0) {
            String msg = "Remboursement du " + r.getDate() + " (" + fcfa(r.getMontant()) + ") à " + c.getNom()
                    + " : argent reçu disponible à cette date " + fcfa(pris) + ", " + fcfa(manque) + " non couverts par des paiements.";
            avert.add(prefixe + msg);
            suivi(suivis, c).notes.add(msg);
        }
    }

    /** Argent du client encore libre, reçu au plus tard à cette date (plus anciens
     * d'abord, comme CompteClientService.sourcesDisponibles). */
    private List<CalculImputation.Source> sourcesALaDate(Client c, java.time.LocalDate date) {
        List<CalculImputation.Source> sources = new ArrayList<>();
        for (PaiementClient p : paiementRepo.findActifsByClientId(c.getId())) {
            if (date != null && p.getDate() != null && p.getDate().isAfter(date)) continue;
            double reste = r2(p.getMontant() - nz(imputationRepo.sumActivesByPaiementId(p.getId())));
            if (reste > 0) sources.add(new CalculImputation.Source(p.getUniqueId(), reste,
                    p.getCommande() != null ? p.getCommande().getUniqueId() : null, p.getVenteCibleUniqueId()));
        }
        return sources;
    }
}
