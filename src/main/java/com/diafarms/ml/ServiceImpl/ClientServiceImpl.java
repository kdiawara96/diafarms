package com.diafarms.ml.ServiceImpl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.ClientDTO;
import com.diafarms.ml.DTO.ClientReportDTO;
import com.diafarms.ml.DTO.ClientVenteLigneDTO;
import com.diafarms.ml.DTO.CompteClientDTO;
import com.diafarms.ml.DTO.SoldeClientDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.PaiementClient;
import com.diafarms.ml.models.RemboursementClient;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.FactureLigneRepo;
import com.diafarms.ml.repository.PaiementClientRepo;
import com.diafarms.ml.repository.RemboursementClientRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.ClientCreate;
import com.diafarms.ml.services.ClientService;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

// Clients d'une ferme (acheteurs) — voir Client.java. Un client est optionnel sur
// une vente (VenteOeufs/VenteReforme), gestion accessible à ADMIN/RESPONSABLE/VENTE
// en création (un vendeur rencontre de nouveaux clients sur le terrain), modification
// et suppression réservées à ADMIN/RESPONSABLE.
@Service
@RequiredArgsConstructor
public class ClientServiceImpl implements ClientService {

    private final ClientRepo clientRepo;
    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteReformeRepo venteReformeRepo;
    private final PaiementClientRepo paiementClientRepo;
    private final RemboursementClientRepo remboursementClientRepo;
    private final FactureLigneRepo factureLigneRepo;
    private final SoldeClientServiceImpl soldeClientService;
    private final CompteClientService compteClientService;
    private final LogsServices logs;
    private final OtherService otherService;

    // @Lazy évite un cycle : PaiementClientService → TransactionService, et
    // ClientServiceImpl ← CommandeServiceImpl → ... → PaiementClientService.
    @Autowired
    @Lazy
    private PaiementClientService paiementClientService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasRole(Utilisateurs u, String role) {
        return u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> role.equalsIgnoreCase(r.getRole()));
    }

    private boolean isAdmin(Utilisateurs u) {
        return hasRole(u, "ADMIN") || hasRole(u, "SUPER_ADMIN");
    }

    private void ensureCanCreate(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "VENTE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour créer un client.");
        }
    }

    private void ensureCanManage(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE")) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut modifier/supprimer un client.");
        }
    }

    // Même périmètre que la visibilité de la page Clients côté web (tout sauf
    // PRODUCTION) : n'importe lequel de ces rôles peut être celui qui encaisse le
    // paiement d'un client sur le terrain ou au comptoir.
    private void ensureCanRecordPayment(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE") && !hasRole(u, "VENTE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour enregistrer un paiement client.");
        }
    }

    // Rembourser fait sortir de l'argent de la caisse (contrairement à payerDette, qui
    // en fait entrer) : un cran plus strict, VENTE exclu — un vendeur ne doit pas
    // pouvoir décider seul de sortir de l'argent de la ferme, même pour rendre une
    // avance à un client.
    private void ensureCanRembourser(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour rembourser un client.");
        }
    }

    @Override
    @Transactional
    public ClientDTO create(ClientCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanCreate(currentUser);
        if (data.getNom() == null || data.getNom().isBlank()) {
            throw new IllegalArgumentException("Le nom du client est obligatoire.");
        }
        if (data.getTelephone() == null || data.getTelephone().isBlank()) {
            throw new IllegalArgumentException("Le numéro de téléphone du client est obligatoire.");
        }
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Votre compte n'est rattaché à aucune ferme.");
        }
        if (clientRepo.existsByNomIgnoreCaseAndFarmId(data.getNom().trim(), currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Un client portant ce nom existe déjà.");
        }
        if (clientRepo.existsByTelephoneAndFarmId(data.getTelephone().trim(), currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Un client avec ce numéro de téléphone existe déjà.");
        }

        Client c = new Client();
        c.setUniqueId(java.util.UUID.randomUUID().toString());
        c.setNom(data.getNom().trim());
        c.setTelephone(data.getTelephone().trim());
        c.setAdresse(data.getAdresse());
        c.setEmail(data.getEmail());
        c.setFarm(currentUser.getFarm());
        c.setInitialisation(Initialisation.init());

        Client saved = clientRepo.save(c);
        logs.addLogs(currentUser.getId(), saved.getId(), "Client", "Ajout d'un client : " + saved.getNom());
        return ClientDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public ClientDTO update(String uniqueId, ClientCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        Client c = clientRepo.findByUniqueId(uniqueId);
        if (c == null) {
            throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        }

        if (data.getNom() != null && !data.getNom().isBlank()) c.setNom(data.getNom().trim());
        if (data.getTelephone() != null && !data.getTelephone().isBlank()) {
            String telephone = data.getTelephone().trim();
            if (!telephone.equals(c.getTelephone())
                    && clientRepo.existsByTelephoneAndFarmIdExcludingUniqueId(telephone, c.getFarm().getId(), c.getUniqueId())) {
                throw new IllegalArgumentException("Un client avec ce numéro de téléphone existe déjà.");
            }
            c.setTelephone(telephone);
        }
        if (data.getAdresse() != null) c.setAdresse(data.getAdresse());
        if (data.getEmail() != null) c.setEmail(data.getEmail());
        if (c.getInitialisation() != null) c.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());

        Client saved = clientRepo.save(c);
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Client", "Modification d'un client");
        }
        return ClientDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        Client c = clientRepo.findByUniqueId(uniqueId);
        if (c == null) {
            throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        }

        c.getInitialisation().setRemoved(!c.getInitialisation().getRemoved());
        clientRepo.save(c);
        boolean removed = c.getInitialisation().getRemoved();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), c.getId(), "Client", (removed ? "Suppression" : "Restauration") + " d'un client");
        }
        return removed ? "Client supprimé." : "Client récupéré.";
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClientDTO> select() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) return List.of();
        return clientRepo.findAllActiveByFarmId(currentUser.getFarm().getId()).stream()
                .map(ClientDTO::select)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<ClientDTO> list(int page, int size, String search) {
        Utilisateurs currentUser = getCurrentUserSafe();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "nom"));

        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }
        Long farmId = currentUser.getFarm().getId();

        Page<Client> clientPage = (search != null && !search.trim().isEmpty())
                ? clientRepo.searchByFarm(farmId, search.trim(), pageable)
                : clientRepo.findActiveByFarmId(farmId, pageable);

        List<ClientDTO> dtoList = clientPage.getContent().stream().map(ClientDTO::fromEntity).toList();

        // Un seul appel groupé (pas un par client) pour rester utilisable sur une
        // liste paginée — voir SoldeClientServiceImpl.listNonZero, déjà farm-scopé.
        Map<String, Double> soldesParClient = soldeClientService.listNonZero(currentUser.getFarm()).stream()
                .collect(Collectors.toMap(SoldeClientDTO::getClientUniqueId, SoldeClientDTO::getSolde));
        dtoList.forEach(dto -> {
            Double solde = soldesParClient.get(dto.getUniqueId());
            if (solde != null) dto.setSolde(solde);
        });

        return new PaginatedResponse<>(
                dtoList,
                clientPage.getNumber() + 1,
                clientPage.getTotalPages(),
                clientPage.getTotalElements(),
                clientPage.getSize()
        );
    }

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    // Numéro de la facture ACTIVE (non ANNULEE) portant cette vente, null si aucune —
    // voir FactureLigneRepo.numeroFactureActive. Au plus une facture active par vente
    // (FactureServiceImpl.genererDepuis l'empêche), on prend la première par sécurité.
    private String factureNumeroActive(CibleImputation type, String venteUniqueId) {
        List<String> numeros = factureLigneRepo.numeroFactureActive(type, venteUniqueId);
        return numeros.isEmpty() ? null : numeros.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public ClientReportDTO getReport(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Client client = clientRepo.findByUniqueId(uniqueId);
        if (client == null || client.getFarm() == null
                || !client.getFarm().getId().equals(currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        }
        Long farmId = currentUser.getFarm().getId();

        List<VenteOeufs> ventesOeufs = venteOeufsRepo.findByClientUniqueIdAndFarmId(uniqueId, farmId);
        List<VenteReforme> ventesReforme = venteReformeRepo.findByClientUniqueIdAndFarmId(uniqueId, farmId);

        List<ClientVenteLigneDTO> historique = new java.util.ArrayList<>();

        for (VenteOeufs v : ventesOeufs) {
            double paye = compteClientService.payeVente(CibleImputation.VENTE_OEUFS, v.getUniqueId());
            double reste = compteClientService.resteAPayerVente(CibleImputation.VENTE_OEUFS, v.getUniqueId(), nz(v.getMontant()));
            historique.add(ClientVenteLigneDTO.builder()
                    .uniqueId(v.getUniqueId())
                    .date(v.getDate())
                    .type("OEUFS")
                    .magasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null)
                    .montant(v.getMontant())
                    .montantRapporte(v.getMontantRapporte())
                    .paye(paye)
                    .resteAPayer(reste)
                    .statutPaiement(reste <= 0 ? "PAYEE" : (paye > 0 ? "PARTIELLE" : "NON_PAYEE"))
                    .commandeUniqueId(v.getCommande() != null ? v.getCommande().getUniqueId() : null)
                    .quantite(v.getQuantiteOeufs())
                    .factureNumero(factureNumeroActive(CibleImputation.VENTE_OEUFS, v.getUniqueId()))
                    .build());
        }
        for (VenteReforme v : ventesReforme) {
            double paye = compteClientService.payeVente(CibleImputation.VENTE_REFORME, v.getUniqueId());
            double reste = compteClientService.resteAPayerVente(CibleImputation.VENTE_REFORME, v.getUniqueId(), nz(v.getMontant()));
            historique.add(ClientVenteLigneDTO.builder()
                    .uniqueId(v.getUniqueId())
                    .date(v.getDate())
                    .type("REFORME")
                    .magasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null)
                    .montant(v.getMontant())
                    .montantRapporte(v.getMontantRapporte())
                    .paye(paye)
                    .resteAPayer(reste)
                    .statutPaiement(reste <= 0 ? "PAYEE" : (paye > 0 ? "PARTIELLE" : "NON_PAYEE"))
                    .commandeUniqueId(v.getCommande() != null ? v.getCommande().getUniqueId() : null)
                    .quantite(v.getNombreSujets())
                    .factureNumero(factureNumeroActive(CibleImputation.VENTE_REFORME, v.getUniqueId()))
                    .build());
        }
        // Paiements/avances directs (voir payerDette) : pas de vente associée, donc
        // absents de venteOeufsRepo/venteReformeRepo, mais ils affectent bien le solde
        // (compte, ci-dessous) — sans ça, le solde du client change sans qu'aucune ligne
        // de l'historique n'explique pourquoi (ce que remontait l'utilisateur : un client
        // avec un solde non nul mais "aucun achat pour l'instant"). Les paiements annulés
        // sont inclus (statut ANNULE) pour que l'historique reste complet, mais seuls les
        // paiements actifs comptent dans les totaux (compte, calculé côté CompteClientService).
        for (PaiementClient p : paiementClientRepo.findAllByClientIdForHistorique(client.getId())) {
            historique.add(ClientVenteLigneDTO.builder()
                    .uniqueId(p.getUniqueId())
                    .date(p.getDate())
                    .type("PAIEMENT")
                    .montant(p.getMontant())
                    .mode(p.getMode() != null ? p.getMode().name() : null)
                    .origine(p.getOrigine() != null ? p.getOrigine().name() : null)
                    .statut(p.getStatut() != null ? p.getStatut().name() : null)
                    .commandeUniqueId(p.getCommande() != null ? p.getCommande().getUniqueId() : null)
                    .build());
        }
        // Remboursements : montant affiché négatif (argent qui sort de l'avance du client).
        for (RemboursementClient r : remboursementClientRepo.findAllByClientId(client.getId())) {
            historique.add(ClientVenteLigneDTO.builder()
                    .uniqueId(r.getUniqueId())
                    .date(r.getDate())
                    .type("REMBOURSEMENT")
                    .montant(r.getMontant() != null ? -r.getMontant() : null)
                    .mode(r.getMode() != null ? r.getMode().name() : null)
                    .statut(r.getStatut() != null ? r.getStatut().name() : null)
                    .commandeUniqueId(r.getCommande() != null ? r.getCommande().getUniqueId() : null)
                    .build());
        }
        historique.sort((a, b) -> {
            if (a.getDate() == null && b.getDate() == null) return 0;
            if (a.getDate() == null) return 1;
            if (b.getDate() == null) return -1;
            return b.getDate().compareTo(a.getDate());
        });

        CompteClientDTO compte = compteClientService.compte(client);

        return ClientReportDTO.builder()
                .clientUniqueId(client.getUniqueId())
                .clientNom(client.getNom())
                .totalAchete(compte.getTotalVendu())
                .totalPaye(compte.getTotalPaye())
                .solde(compte.getSolde())
                .compte(compte)
                .historique(historique)
                .build();
    }

    // Un client vient payer (tout ou partie de) sa dette en cours — pas liée à une
    // nouvelle vente, donc pas de VenteOeufs/VenteReforme ici : juste une Transaction
    // "entrée" classique (l'argent rentre vraiment dans la caisse à ce moment-là,
    // voir TransactionServiceImpl.create) et l'ajustement du solde en conséquence.
    // Autorise un montant supérieur au solde dû (le surplus devient une avance,
    // solde négatif, pour une prochaine vente) plutôt que de le bloquer arbitrairement.
    @Override
    @Transactional
    public ClientDTO payerDette(String uniqueId, Double montant, String description) {
        return payerDette(uniqueId, montant, "Remboursement client", description);
    }

    @Override
    @Transactional
    public ClientDTO payerDette(String uniqueId, Double montant, String categorie, String description) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanRecordPayment(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (montant == null || montant <= 0) {
            throw new IllegalArgumentException("Le montant payé doit être positif.");
        }
        Client client = clientRepo.findByUniqueId(uniqueId);
        if (client == null || client.getFarm() == null
                || !client.getFarm().getId().equals(currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        }
        OriginePaiement origine = "Acompte client".equals(categorie) ? OriginePaiement.ACOMPTE
                : (description != null && description.startsWith("Paiement facture")) ? OriginePaiement.FACTURE
                : OriginePaiement.REGLEMENT;
        paiementClientService.enregistrerInterne(client, montant, ModePaiement.ESPECES, origine,
                null, null, null, null, description, java.time.LocalDate.now());
        ClientDTO dto = ClientDTO.fromEntity(client);
        dto.setSolde(compteClientService.compte(client).getSolde());
        return dto;
    }

    // Rend en argent au client une avance qu'il a déjà payée (ex: acompte sur une
    // commande d'œufs, mais il préfère finalement récupérer son argent plutôt que
    // d'attendre la livraison, ou l'échanger contre autre chose) — jumeau de payerDette
    // mais dans l'autre sens : une Transaction "sortie" (l'argent sort vraiment de la
    // caisse à ce moment-là) et le solde qui remonte d'autant vers zéro. Plafonné à
    // l'avance réellement disponible : on ne peut pas rembourser plus que ce que le
    // client a payé d'avance, ni rembourser un client qui n'a justement pas d'avance
    // (solde positif ou nul = il doit encore, ou rien du tout).
    @Override
    @Transactional
    public ClientDTO rembourser(String uniqueId, Double montant, String description) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanRembourser(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (montant == null || montant <= 0) {
            throw new IllegalArgumentException("Le montant remboursé doit être positif.");
        }
        Client client = clientRepo.findByUniqueId(uniqueId);
        if (client == null || client.getFarm() == null
                || !client.getFarm().getId().equals(currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        }

        // Plafond à l'avance réellement disponible appliqué par
        // PaiementClientService.rembourserInterne (CalculImputation.prelever).
        paiementClientService.rembourserInterne(client, montant, ModePaiement.ESPECES,
                (description != null && description.trim().length() >= 3) ? description.trim() : "Remboursement d'une avance",
                null);

        ClientDTO dto = ClientDTO.fromEntity(client);
        dto.setSolde(compteClientService.compte(client).getSolde());
        return dto;
    }
}
