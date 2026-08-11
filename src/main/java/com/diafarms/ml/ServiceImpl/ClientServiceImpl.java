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
import com.diafarms.ml.repository.ClientRepo;
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

    @Override
    @Transactional
    public ClientDTO create(ClientCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanCreate(currentUser);
        if (data.getNom() == null || data.getNom().isBlank()) {
            throw new IllegalArgumentException("Le nom du client est obligatoire.");
        }
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Votre compte n'est rattaché à aucune ferme.");
        }
        if (clientRepo.existsByNomIgnoreCaseAndFarmId(data.getNom().trim(), currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Un client portant ce nom existe déjà.");
        }

        Client c = new Client();
        c.setUniqueId(java.util.UUID.randomUUID().toString());
        c.setNom(data.getNom().trim());
        c.setTelephone(data.getTelephone());
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
        if (data.getTelephone() != null) c.setTelephone(data.getTelephone());
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
        txData.setCategorie("Paiement client");
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
}
