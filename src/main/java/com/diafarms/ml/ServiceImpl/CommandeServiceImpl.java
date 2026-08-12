package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.CommandeDTO;
import com.diafarms.ml.DTO.VenteOeufsDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Commande;
import com.diafarms.ml.models.Commande.StatutCommande;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.CommandeRepo;
import com.diafarms.ml.repository.MagasinRepo;
import com.diafarms.ml.request.create.CommandeCreate;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.services.CommandeService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.VenteOeufsService;
import com.diafarms.ml.services.VenteReformeService;

import lombok.RequiredArgsConstructor;

// Commande = ce qu'un client demande AVANT que la vente ne soit finalisée — voir
// Commande.java. Cycle de vie : EN_ATTENTE -> CONFIRMEE -> CONVERTIE (crée la vente,
// voir convertirEnVente) ou -> ANNULEE à tout moment avant conversion. Permissions
// alignées sur ClientServiceImpl : création/actions ouvertes à ADMIN/RESPONSABLE/VENTE
// (un vendeur prend des commandes sur le terrain), suppression réservée à ADMIN/RESPONSABLE.
@Service
@RequiredArgsConstructor
public class CommandeServiceImpl implements CommandeService {

    private final CommandeRepo commandeRepo;
    private final ClientRepo clientRepo;
    private final MagasinRepo magasinRepo;
    private final VenteOeufsService venteOeufsService;
    private final VenteReformeService venteReformeService;
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

    private void ensureCanManage(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "VENTE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour gérer les commandes.");
        }
    }

    private void ensureCanDelete(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE")) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut supprimer une commande.");
        }
    }

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    @Override
    @Transactional
    public CommandeDTO create(CommandeCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (data.getClientUniqueId() == null || data.getClientUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le client est obligatoire pour une commande.");
        }
        if (data.getQuantite() == null || data.getQuantite() <= 0) {
            throw new IllegalArgumentException("La quantité commandée doit être positive.");
        }
        if (data.getMontantEstime() == null || data.getMontantEstime() <= 0) {
            throw new IllegalArgumentException("Le montant estimé doit être positif.");
        }
        if (data.getMagasinUniqueId() == null || data.getMagasinUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le magasin de destination est obligatoire.");
        }

        Client client = clientRepo.findByUniqueId(data.getClientUniqueId());
        if (client == null) {
            throw new IllegalArgumentException("Client introuvable : " + data.getClientUniqueId());
        }
        Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
        if (magasin.getType() != Magasin.TypeMagasin.VENTE) {
            throw new IllegalArgumentException("Une commande ne peut viser qu'un magasin de type VENTE.");
        }
        TypeStockMagasin type;
        try {
            type = TypeStockMagasin.valueOf(data.getType().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Type de commande invalide (attendu OEUFS ou REFORME) : " + data.getType());
        }

        Commande c = new Commande();
        c.setUniqueId(java.util.UUID.randomUUID().toString());
        c.setClient(client);
        c.setMagasin(magasin);
        c.setType(type);
        c.setQuantite(data.getQuantite());
        c.setPrixUnitaireEstime(data.getPrixUnitaireEstime());
        c.setMontantEstime(data.getMontantEstime());
        c.setMontantAcompte(data.getMontantAcompte());
        c.setDateCommande(data.getDateCommande() != null && !data.getDateCommande().isBlank()
                ? LocalDate.parse(data.getDateCommande()) : LocalDate.now());
        c.setDateLivraisonPrevue(data.getDateLivraisonPrevue() != null && !data.getDateLivraisonPrevue().isBlank()
                ? LocalDate.parse(data.getDateLivraisonPrevue()) : null);
        c.setStatut(StatutCommande.EN_ATTENTE);
        c.setCreePar(currentUser);
        c.setFarm(currentUser.getFarm());
        c.setInitialisation(Initialisation.init());

        Commande saved = commandeRepo.save(c);
        logs.addLogs(currentUser.getId(), saved.getId(), "Commande",
                "Nouvelle commande de " + saved.getQuantite() + " (" + type + ") pour " + client.getNom());
        return CommandeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public CommandeDTO update(String uniqueId, CommandeCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) {
            throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        }
        if (c.getStatut() != StatutCommande.EN_ATTENTE) {
            throw new IllegalArgumentException("Seule une commande en attente peut être modifiée.");
        }

        if (data.getQuantite() != null) {
            if (data.getQuantite() <= 0) throw new IllegalArgumentException("La quantité commandée doit être positive.");
            c.setQuantite(data.getQuantite());
        }
        if (data.getPrixUnitaireEstime() != null) c.setPrixUnitaireEstime(data.getPrixUnitaireEstime());
        if (data.getMontantEstime() != null) {
            if (data.getMontantEstime() <= 0) throw new IllegalArgumentException("Le montant estimé doit être positif.");
            c.setMontantEstime(data.getMontantEstime());
        }
        if (data.getMontantAcompte() != null) c.setMontantAcompte(data.getMontantAcompte());
        if (data.getDateLivraisonPrevue() != null) {
            c.setDateLivraisonPrevue(data.getDateLivraisonPrevue().isBlank() ? null : LocalDate.parse(data.getDateLivraisonPrevue()));
        }
        if (data.getMagasinUniqueId() != null && !data.getMagasinUniqueId().isBlank()) {
            Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
            if (magasin.getType() != Magasin.TypeMagasin.VENTE) {
                throw new IllegalArgumentException("Une commande ne peut viser qu'un magasin de type VENTE.");
            }
            c.setMagasin(magasin);
        }
        if (c.getInitialisation() != null) c.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());

        Commande saved = commandeRepo.save(c);
        return CommandeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public CommandeDTO confirmer(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        if (c.getStatut() != StatutCommande.EN_ATTENTE) {
            throw new IllegalArgumentException("Seule une commande en attente peut être confirmée.");
        }
        c.setStatut(StatutCommande.CONFIRMEE);
        return CommandeDTO.fromEntity(commandeRepo.save(c));
    }

    @Override
    @Transactional
    public CommandeDTO annuler(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        if (c.getStatut() == StatutCommande.CONVERTIE) {
            throw new IllegalArgumentException("Une commande déjà convertie en vente ne peut plus être annulée.");
        }
        if (c.getStatut() == StatutCommande.ANNULEE) {
            throw new IllegalArgumentException("Cette commande est déjà annulée.");
        }
        c.setStatut(StatutCommande.ANNULEE);
        return CommandeDTO.fromEntity(commandeRepo.save(c));
    }

    @Override
    @Transactional
    public CommandeDTO convertirEnVente(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        if (c.getStatut() == StatutCommande.CONVERTIE) {
            throw new IllegalArgumentException("Cette commande a déjà été convertie en vente.");
        }
        if (c.getStatut() == StatutCommande.ANNULEE) {
            throw new IllegalArgumentException("Une commande annulée ne peut pas être convertie en vente.");
        }

        // L'acompte déjà versé (nz : 0.0 si aucun) devient le montant rapporté de la
        // vente générée — jamais laissé null, sinon la convention "null = pas d'écart
        // connu, compte pour le plein théorique" masquerait à tort la dette du client
        // sur ce qui n'a justement PAS encore été payé (voir VenteOeufs.montantRapporte).
        String venteUniqueId;
        if (c.getType() == TypeStockMagasin.OEUFS) {
            VenteOeufsCreate data = new VenteOeufsCreate();
            data.setMagasinUniqueId(c.getMagasin().getUniqueId());
            data.setClientUniqueId(c.getClient().getUniqueId());
            data.setQuantiteOeufs(c.getQuantite());
            data.setPrixUnitaire(c.getPrixUnitaireEstime());
            data.setMontant(c.getMontantEstime());
            data.setMontantRapporte(nz(c.getMontantAcompte()));
            data.setDate(LocalDate.now().toString());
            VenteOeufsDTO vente = venteOeufsService.create(data);
            venteUniqueId = vente.getUniqueId();
        } else {
            VenteReformeCreate data = new VenteReformeCreate();
            data.setMagasinUniqueId(c.getMagasin().getUniqueId());
            data.setClientUniqueId(c.getClient().getUniqueId());
            data.setNombreSujets(c.getQuantite());
            data.setPrixUnitaire(c.getPrixUnitaireEstime());
            data.setMontant(c.getMontantEstime());
            data.setMontantRapporte(nz(c.getMontantAcompte()));
            data.setDate(LocalDate.now().toString());
            VenteReformeDTO vente = venteReformeService.create(data);
            venteUniqueId = vente.getUniqueId();
        }

        c.setVenteUniqueId(venteUniqueId);
        c.setStatut(StatutCommande.CONVERTIE);
        Commande saved = commandeRepo.save(c);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Commande",
                    "Commande convertie en vente pour " + c.getClient().getNom());
        }
        return CommandeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanDelete(currentUser);
        Commande c = commandeRepo.findByUniqueId(uniqueId);
        if (c == null) throw new IllegalArgumentException("Commande introuvable : " + uniqueId);
        if (!c.getInitialisation().getRemoved() && c.getStatut() != StatutCommande.EN_ATTENTE) {
            throw new IllegalArgumentException("Seule une commande en attente peut être supprimée — annulez-la plutôt.");
        }
        c.getInitialisation().setRemoved(!c.getInitialisation().getRemoved());
        commandeRepo.save(c);
        boolean removed = c.getInitialisation().getRemoved();
        return removed ? "Commande supprimée." : "Commande récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<CommandeDTO> list(int page, int size, String statut, String clientUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dateCommande"));

        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }

        StatutCommande statutEnum = (statut == null || statut.isBlank() || "tous".equalsIgnoreCase(statut))
                ? null : StatutCommande.valueOf(statut.toUpperCase());
        String clientParam = (clientUniqueId == null || clientUniqueId.isBlank()) ? null : clientUniqueId;

        Page<Commande> commandePage = commandeRepo.search(currentUser.getFarm().getId(), statutEnum, clientParam, pageable);
        List<CommandeDTO> dtoList = commandePage.getContent().stream().map(CommandeDTO::fromEntity).toList();

        return new PaginatedResponse<>(
                dtoList,
                commandePage.getNumber() + 1,
                commandePage.getTotalPages(),
                commandePage.getTotalElements(),
                commandePage.getSize()
        );
    }
}
