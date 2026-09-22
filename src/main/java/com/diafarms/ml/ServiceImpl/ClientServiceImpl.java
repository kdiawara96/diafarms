package com.diafarms.ml.ServiceImpl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.ClientDTO;
import com.diafarms.ml.DTO.ClientReportDTO;
import com.diafarms.ml.DTO.ClientVenteLigneDTO;
import com.diafarms.ml.DTO.SoldeClientDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.models.Transaction;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.TransactionRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.ClientCreate;
import com.diafarms.ml.request.create.TransactionCreate;
import com.diafarms.ml.services.ClientService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;

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
    private final TransactionRepo transactionRepo;
    private final SoldeClientServiceImpl soldeClientService;
    private final TransactionService transactionService;
    private final LogsServices logs;
    private final OtherService otherService;

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

    @Override
    @Transactional(readOnly = true)
    public ClientReportDTO getReport(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Client client = clientRepo.findByUniqueId(uniqueId);
        if (client == null) {
            throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        }
        Long farmId = currentUser.getFarm().getId();

        List<VenteOeufs> ventesOeufs = venteOeufsRepo.findByClientUniqueIdAndFarmId(uniqueId, farmId);
        List<VenteReforme> ventesReforme = venteReformeRepo.findByClientUniqueIdAndFarmId(uniqueId, farmId);

        List<ClientVenteLigneDTO> historique = new java.util.ArrayList<>();
        double totalAchete = 0.0;
        double totalPaye = 0.0;

        for (VenteOeufs v : ventesOeufs) {
            totalAchete += nz(v.getMontant());
            totalPaye += v.getMontantRapporte() != null ? v.getMontantRapporte() : nz(v.getMontant());
            historique.add(ClientVenteLigneDTO.builder()
                    .uniqueId(v.getUniqueId())
                    .date(v.getDate())
                    .type("OEUFS")
                    .magasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null)
                    .montant(v.getMontant())
                    .montantRapporte(v.getMontantRapporte())
                    .build());
        }
        for (VenteReforme v : ventesReforme) {
            totalAchete += nz(v.getMontant());
            totalPaye += v.getMontantRapporte() != null ? v.getMontantRapporte() : nz(v.getMontant());
            historique.add(ClientVenteLigneDTO.builder()
                    .uniqueId(v.getUniqueId())
                    .date(v.getDate())
                    .type("REFORME")
                    .magasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null)
                    .montant(v.getMontant())
                    .montantRapporte(v.getMontantRapporte())
                    .build());
        }
        // Paiements/avances directs (voir payerDette) : pas de vente associée, donc
        // absents de venteOeufsRepo/venteReformeRepo, mais ils affectent bien le solde
        // ci-dessous — sans ça, le solde du client change sans qu'aucune ligne de
        // l'historique n'explique pourquoi (ce que remontait l'utilisateur : un client
        // avec un solde non nul mais "aucun achat pour l'instant").
        for (Transaction t : transactionRepo.findByClient_UniqueIdAndFarm_IdAndInitialisation_RemovedFalse(uniqueId, farmId)) {
            totalPaye += nz(t.getMontant());
            historique.add(ClientVenteLigneDTO.builder()
                    .uniqueId(t.getUniqueId())
                    .date(t.getDate())
                    .type("PAIEMENT")
                    .magasinNom(null)
                    .montant(t.getMontant())
                    .montantRapporte(t.getMontant())
                    .build());
        }
        historique.sort((a, b) -> b.getDate().compareTo(a.getDate()));

        double solde = soldeClientService.getSolde(client).getSolde();

        return ClientReportDTO.builder()
                .clientUniqueId(client.getUniqueId())
                .clientNom(client.getNom())
                .totalAchete(totalAchete)
                .totalPaye(totalPaye)
                .solde(solde)
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
        if (client == null) {
            throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        }

        TransactionCreate txData = new TransactionCreate();
        txData.setType("ENTREE");
        txData.setCommun(true);
        txData.setDate(java.time.LocalDate.now());
        txData.setMontant(montant);
        txData.setCategorie(categorie != null && !categorie.isBlank() ? categorie : "Remboursement client");
        txData.setClientUniqueId(client.getUniqueId());
        txData.setDescription((description != null && !description.isBlank())
                ? description
                : "Paiement de dette — " + client.getNom());
        transactionService.create(txData);

        soldeClientService.ajusterSolde(client, currentUser.getFarm(), -montant);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), client.getId(), "Client",
                    "Paiement de " + montant + " FCFA enregistré pour " + client.getNom());
        }

        ClientDTO dto = ClientDTO.fromEntity(client);
        dto.setSolde(soldeClientService.getSolde(client).getSolde());
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
        if (client == null) {
            throw new IllegalArgumentException("Client introuvable : " + uniqueId);
        }

        double soldeActuel = soldeClientService.getSolde(client).getSolde();
        if (soldeActuel >= 0) {
            throw new IllegalArgumentException(
                "Ce client n'a aucune avance à rembourser (son solde est " + (soldeActuel == 0 ? "à zéro" : "positif : il doit encore de l'argent à la ferme") + ")."
            );
        }
        double avanceDisponible = -soldeActuel;
        if (montant > avanceDisponible) {
            throw new IllegalArgumentException(
                "Le remboursement (" + montant + " FCFA) dépasse l'avance disponible de ce client (" + avanceDisponible + " FCFA)."
            );
        }

        TransactionCreate txData = new TransactionCreate();
        txData.setType("SORTIE");
        txData.setCommun(true);
        txData.setDate(java.time.LocalDate.now());
        txData.setMontant(montant);
        // Catégorie volontairement absente de "Nouvelle transaction" (liste manuelle) —
        // même raison que "Salaire" : passer par cet écran dédié applique le contrôle
        // ci-dessus (plafond à l'avance réellement disponible), une transaction manuelle
        // le contournerait.
        txData.setCategorie("Remboursement au client");
        txData.setClientUniqueId(client.getUniqueId());
        txData.setDescription((description != null && !description.isBlank())
                ? description
                : "Remboursement d'une avance — " + client.getNom());
        transactionService.create(txData);

        // Le remboursement CONSOMME l'avance : le solde remonte vers zéro (même sens
        // que l'écart d'une vente qui consomme une avance, voir VenteOeufsImpl.ajusterEcart) —
        // jamais un -montant, qui creuserait l'avance au lieu de la réduire.
        soldeClientService.ajusterSolde(client, currentUser.getFarm(), montant);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), client.getId(), "Client",
                    "Remboursement de " + montant + " FCFA enregistré pour " + client.getNom());
        }

        ClientDTO dto = ClientDTO.fromEntity(client);
        dto.setSolde(soldeClientService.getSolde(client).getSolde());
        return dto;
    }
}
