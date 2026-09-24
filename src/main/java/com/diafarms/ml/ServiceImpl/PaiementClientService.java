package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.*;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.commons.DateSaisie;
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

    // Commande / facture rattachées à un paiement ou un remboursement : elles doivent être
    // de la même ferme ET du même client, sinon l'argent d'un client irait prioritairement
    // régler (ou rembourser) la commande d'un autre. Même message qu'introuvable, pour ne
    // pas révéler ce qui existe dans une autre ferme.
    private Commande commandeDuClient(String uid, Client c) {
        if (uid == null || uid.isBlank()) return null;
        Commande k = commandeRepo.findByUniqueId(uid);
        if (k == null || k.getFarm() == null || !k.getFarm().getId().equals(c.getFarm().getId())
                || k.getClient() == null || !k.getClient().getId().equals(c.getId()))
            throw new IllegalArgumentException("Commande introuvable pour ce client : " + uid);
        return k;
    }

    private Facture factureDuClient(String uid, Client c) {
        if (uid == null || uid.isBlank()) return null;
        // FactureRepo.findByUniqueId renvoie directement Facture (pas Optional).
        Facture f = factureRepo.findByUniqueId(uid);
        if (f == null || f.getFarm() == null || !f.getFarm().getId().equals(c.getFarm().getId())
                || f.getClient() == null || !f.getClient().getId().equals(c.getId()))
            throw new IllegalArgumentException("Facture introuvable pour ce client : " + uid);
        return f;
    }

    private static boolean memeFerme(Farm farm, Utilisateurs u) {
        return farm != null && u != null && u.getFarm() != null && farm.getId().equals(u.getFarm().getId());
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
        Commande commande = commandeDuClient(d.getCommandeUniqueId(), c);
        Facture facture = factureDuClient(d.getFactureUniqueId(), c);
        OriginePaiement origine = d.getOrigine() == null || d.getOrigine().isBlank() ? OriginePaiement.REGLEMENT
                : OriginePaiement.valueOf(d.getOrigine().trim().toUpperCase());
        CibleImputation cibleType = d.getVenteCibleType() == null || d.getVenteCibleType().isBlank() ? null
                : CibleImputation.valueOf(d.getVenteCibleType().trim().toUpperCase());
        PaiementClient p = enregistrerInterne(c, d.getMontant(), mode(d.getMode()), origine, commande, cibleType,
                d.getVenteCibleUniqueId(), facture, d.getObservations(),
                DateSaisie.parse(d.getDate(), LocalDate.now()));
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
        PaiementClient p = paiementRepo.findByUniqueId(uid)
                .filter(x -> memeFerme(x.getFarm(), u))
                .orElseThrow(() -> new IllegalArgumentException("Paiement introuvable."));
        // Verrou client AVANT de regarder les imputations : un remboursement simultané
        // (rembourserInterne verrouille le même client) ne peut plus prendre l'argent de
        // ce paiement entre notre contrôle et son annulation.
        compteClientService.verrouiller(p.getClient());
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
        Commande commande = commandeDuClient(d.getCommandeUniqueId(), c);
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
        RemboursementClient r = remboursementRepo.findByUniqueId(uid)
                .filter(x -> memeFerme(x.getFarm(), u))
                .orElseThrow(() -> new IllegalArgumentException("Remboursement introuvable."));
        compteClientService.verrouiller(r.getClient()); // voir annuler()
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

    // Compte complet d'un client : chiffres + historique (annulés compris). Anciennement
    // assemblé dans PaiementClientController, hors transaction : avec open-in-view=false,
    // les associations paresseuses (p.client, r.effectuePar, i.paiement) n'étaient plus
    // chargeables au moment du mapping vers les DTO -> LazyInitializationException. Toute
    // la lecture + le mapping se fait maintenant ici, dans une seule transaction en lecture
    // seule.
    @Transactional(readOnly = true)
    public Map<String, Object> compteComplet(String uid) {
        Utilisateurs u = user();
        Client c = client(uid, u);

        List<PaiementClientDTO> paiements = paiementRepo.findAllByClientIdForHistorique(c.getId()).stream()
                .map((PaiementClient p) -> PaiementClientDTO.fromEntity(p,
                        CalculImputation.arrondi(imputationRepo.sumActivesByPaiementId(p.getId()))))
                .toList();
        List<RemboursementClientDTO> remboursements = remboursementRepo.findAllByClientId(c.getId()).stream()
                .map(RemboursementClientDTO::fromEntity)
                .toList();
        List<ImputationDTO> imputations = imputationRepo.findAllByClientId(c.getId()).stream()
                .map(ImputationDTO::fromEntity)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("compte", compteClientService.compte(c));
        data.put("paiements", paiements);
        data.put("remboursements", remboursements);
        data.put("imputations", imputations);
        return data;
    }
}
