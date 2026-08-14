package com.diafarms.ml.ServiceImpl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.PersonnelDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Personnel;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.PersonnelRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.request.create.PersonnelCreate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.PersonnelService;

import lombok.RequiredArgsConstructor;

// Membre du personnel de la ferme — voir Personnel.java. Distinct d'un compte
// Utilisateurs : sert notamment à payer un employé (gardien, ouvrier...) qui n'a
// jamais besoin de se connecter à l'application (voir Salaire.employe). Permissions
// alignées sur SalaireServiceImpl : gestion RH réservée à ADMIN/RESPONSABLE/COMPTABLE.
@Service
@RequiredArgsConstructor
public class PersonnelServiceImpl implements PersonnelService {

    private final PersonnelRepo personnelRepo;
    private final UtilisateursRepo utilisateursRepo;
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
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE") && !hasRole(u, "COMPTABLE")) {
            throw new IllegalArgumentException("Vous n'avez pas les droits pour gérer le personnel.");
        }
    }

    private Utilisateurs resolveCompte(String utilisateurCompteUniqueId) {
        if (utilisateurCompteUniqueId == null || utilisateurCompteUniqueId.isBlank()) return null;
        return utilisateursRepo.findByUniqueId(utilisateurCompteUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Compte utilisateur introuvable : " + utilisateurCompteUniqueId));
    }

    @Override
    @Transactional
    public PersonnelDTO create(PersonnelCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (data.getNom() == null || data.getNom().isBlank()) {
            throw new IllegalArgumentException("Le nom du personnel est obligatoire.");
        }
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Votre compte n'est rattaché à aucune ferme.");
        }

        Personnel p = new Personnel();
        p.setUniqueId(java.util.UUID.randomUUID().toString());
        p.setNom(data.getNom().trim());
        p.setPoste(data.getPoste());
        p.setTelephone(data.getTelephone());
        p.setUtilisateurCompte(resolveCompte(data.getUtilisateurCompteUniqueId()));
        p.setFarm(currentUser.getFarm());
        p.setInitialisation(Initialisation.init());

        Personnel saved = personnelRepo.save(p);
        logs.addLogs(currentUser.getId(), saved.getId(), "Personnel", "Ajout d'un membre du personnel : " + saved.getNom());
        return PersonnelDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public PersonnelDTO update(String uniqueId, PersonnelCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);

        Personnel p = personnelRepo.findByUniqueId(uniqueId);
        if (p == null) {
            throw new IllegalArgumentException("Personnel introuvable : " + uniqueId);
        }

        if (data.getNom() != null && !data.getNom().isBlank()) p.setNom(data.getNom().trim());
        if (data.getPoste() != null) p.setPoste(data.getPoste());
        if (data.getTelephone() != null) p.setTelephone(data.getTelephone());
        if (data.getUtilisateurCompteUniqueId() != null) {
            p.setUtilisateurCompte(resolveCompte(data.getUtilisateurCompteUniqueId().isBlank() ? null : data.getUtilisateurCompteUniqueId()));
        }
        if (p.getInitialisation() != null) p.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());

        Personnel saved = personnelRepo.save(p);
        return PersonnelDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PersonnelDTO> select() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) return List.of();
        return personnelRepo.findAllActiveByFarmId(currentUser.getFarm().getId()).stream()
                .map(PersonnelDTO::fromEntity)
                .collect(Collectors.toList());
    }
}
