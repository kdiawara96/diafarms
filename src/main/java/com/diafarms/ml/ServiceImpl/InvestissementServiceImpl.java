package com.diafarms.ml.ServiceImpl;

import com.diafarms.ml.DTO.InvestissementDTO;
import com.diafarms.ml.DTO.InvestissementRepartitionDTO;
import com.diafarms.ml.DTO.InvestissementStatsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeAffectation;
import com.diafarms.ml.models.Investissement;
import com.diafarms.ml.models.InvestissementRepartition;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.InvestissementRepartitionRepository;
import com.diafarms.ml.repository.InvestissementRepository;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.request.create.InvestissementRequest;
import com.diafarms.ml.services.InvestissementService;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvestissementServiceImpl implements InvestissementService {

    private final InvestissementRepository investissementRepo;
    private final InvestissementRepartitionRepository repartitionRepo;
    private final ProjetsRepo projetsRepo; 
    private final UtilisateursRepo utilisateursRepo;
   

        @Override
        @Transactional(readOnly = true)
        public PaginatedResponse<InvestissementDTO> getInvestissementsPagines(String uniqueIdUser, int page, int size) {
                // 1. Récupération de l'utilisateur et de sa ferme
                Utilisateurs u = utilisateursRepo.findByUniqueId(uniqueIdUser)
                        .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));
                Long farmId = u.getFarm().getId();

                // 2. Préparation du Pageable (Spring Data commence à 0)
                Pageable pageable = PageRequest.of(page, size, Sort.by("dateAchat").descending());

                // 3. Récupération de la page d'entités depuis le Repository
                Page<Investissement> investissementPage = investissementRepo.findByFarmIdAndInitialisationRemovedFalse(farmId, pageable);

                // 4. Conversion des entités en DTOs
                List<InvestissementDTO> dtoList = investissementPage.getContent()
                        .stream()
                        .map(this::toDTO)
                        .toList();

                // 5. Instanciation de ton PaginatedResponse avec tes attributs exacts
                return new PaginatedResponse<>(
                        dtoList,                                // data
                        investissementPage.getNumber() + 1,     // currentPage (base 1 pour le Front)
                        investissementPage.getTotalPages(),     // totalPages
                        investissementPage.getTotalElements(),  // totalItems
                        investissementPage.getSize()            // size
                );
        }

    @Override
    public InvestissementDTO getInvestissementParUniqueId(String uniqueId) {
        Investissement inv = investissementRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Investissement introuvable avec l'ID: " + uniqueId));
        return toDTO(inv);
    }
    
    @Override
        @Transactional
        public InvestissementDTO creerInvestissement(InvestissementRequest dto, String utilisateurUniqueId) {
        // 1. Récupération de l'utilisateur et de sa ferme
        Utilisateurs u = utilisateursRepo.findByUniqueId(utilisateurUniqueId)
                .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

        // 2. Création et hydratation de l'entité Investissement principale
        Investissement investissement = new Investissement();
        investissement.setUniqueId("INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        investissement.setCategorie(dto.getCategorie());
        investissement.setNom(dto.getNom());
        investissement.setMontant(dto.getMontant());
        investissement.setDateAchat(dto.getDateAchat());
        investissement.setFournisseur(dto.getFournisseur());
        investissement.setDureeAmortissement(dto.getDureeAmortissement());
        investissement.setCommentaire(dto.getCommentaire());
        
        // Protection & Fallback pour le type d'investissement
        investissement.setType(dto.getType() != null ? dto.getType() : "Lineaire");

        // Conversion String -> Enum TypeAffectation (COMMUN ou DEDIE)
        try {
                investissement.setAffectation(TypeAffectation.valueOf(dto.getAffectation().toUpperCase()));
        } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("Type d'affectation invalide : " + dto.getAffectation());
        }

        // Gestion des métadonnées
        investissement.setAmortiCumule(0.0);
        investissement.setInitialisation(Initialisation.init());
        investissement.setUtilisateur(u);
        investissement.setFarm(u.getFarm());
        
        // Initialiser la liste pour éviter un NullPointerException lors de l'ajout
        investissement.setRepartitions(new ArrayList<>());

        // 3. 🟢 CRÉATION DE LA RÉPARTITION DIRECTE SI L'AFFECTATION EST DÉDIÉE
        if (TypeAffectation.DEDIE.equals(investissement.getAffectation())) {
                if (dto.getProjetId() == null || dto.getProjetId().trim().isEmpty()) {
                throw new IllegalArgumentException("Le projetId est obligatoire pour un investissement dédié.");
                }

                // Récupération du projet ciblé par son uniqueId
                Projets projet = projetsRepo.findByUniqueId(dto.getProjetId())
                        .orElseThrow(() -> new IllegalArgumentException("Projet introuvable avec l'ID unique: " + dto.getProjetId()));

                // Construction de la ligne pivot d'affectation initiale
                InvestissementRepartition repartition = new InvestissementRepartition();
                repartition.setInvestissement(investissement);
                repartition.setProjet(projet);
                repartition.setDateDebut(dto.getDateAchat()); // Débute le jour de l'achat
                repartition.setDateFin(null);                 // Toujours actif sur ce projet
                repartition.setMoisUtilises(dto.getDureeAmortissement());
                repartition.setMontantAlloue(dto.getMontant()); // Dédié = 100% du montant alloué au projet

                // Ajout bidirectionnel pour que CascadeType.ALL fasse son travail lors du .save()
                investissement.getRepartitions().add(repartition);
        }

    // 4. Une seule sauvegarde persistée en cascade (Investissement + Répartition)
    Investissement saved = investissementRepo.save(investissement);
    return toDTO(saved);
}
    @Override
    @Transactional
    public InvestissementDTO modifierInvestissement(String uniqueId, Investissement details) {
        Investissement inv = investissementRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Investissement introuvable avec l'ID: " + uniqueId));
        
        inv.setNom(details.getNom());
        inv.setCategorie(details.getCategorie());
        inv.setType(details.getType());
        inv.setMontant(details.getMontant());
        inv.setDureeAmortissement(details.getDureeAmortissement());
        inv.setAffectation(details.getAffectation());
        inv.setFournisseur(details.getFournisseur());
        inv.setCommentaire(details.getCommentaire());
        inv.setIcon(details.getIcon());
        
        inv.getInitialisation().setUpdatedAt(LocalDateTime.now());
        
        return toDTO(investissementRepo.save(inv));
    }

    @Override
    @Transactional
    public void supprimerInvestissement(String uniqueId) {
        Investissement inv = investissementRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Investissement introuvable avec l'ID: " + uniqueId));
        inv.getInitialisation().setRemoved(true);
        inv.getInitialisation().setUpdatedAt(LocalDateTime.now());
        investissementRepo.save(inv);
    }

    @Override
    public List<InvestissementRepartitionDTO> getRepartitionsParInvestissement(String uniqueId) {
        return repartitionRepo.findByInvestissementUniqueId(uniqueId)
                .stream()
                .map(r -> InvestissementRepartitionDTO.builder()
                        .id(r.getId())
                        .codeProjet(r.getProjet() != null ? r.getProjet().getUniqueId() : null)
                        .titreProjet(r.getProjet() != null ? r.getProjet().getTitre() : null)
                        .dateDebut(r.getDateDebut())
                        .dateFin(r.getDateFin())
                        .moisUtilises(r.getMoisUtilises())
                        .montantAlloue(r.getMontantAlloue())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public InvestissementRepartitionDTO ajouterRepartition(String invUniqueId, String projetUniqueId, InvestissementRepartition repartition) {
        Investissement inv = investissementRepo.findByUniqueId(invUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Investissement introuvable avec l'ID: " + invUniqueId));
        
        Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));

        repartition.setInvestissement(inv);
        repartition.setProjet(projet);
        
        InvestissementRepartition saved = repartitionRepo.save(repartition);
        
        return InvestissementRepartitionDTO.builder()
                .id(saved.getId())
                .codeProjet(projet.getUniqueId())
                .titreProjet(projet.getTitre())
                .dateDebut(saved.getDateDebut())
                .dateFin(saved.getDateFin())
                .moisUtilises(saved.getMoisUtilises())
                .montantAlloue(saved.getMontantAlloue())
                .build();
    }

    @Override
    public Double getCoutAmortissementProjet(String projetUniqueId) {
        return repartitionRepo.getSommeAmortissementParProjet(projetUniqueId);
    }


    @Override
        @Transactional(readOnly = true)
        public InvestissementStatsDTO getInvestissementsStats(String utilisateurUniqueId) {
        // 1. Récupérer l'utilisateur
        Utilisateurs u = utilisateursRepo.findByUniqueId(utilisateurUniqueId)
                .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

        // 2. Récupérer TOUS les investissements liés à la ferme de cet utilisateur
        // (Il est préférable de filtrer par la ferme pour englober toute l'exploitation)
        List<Investissement> tousLesInvestissements = investissementRepo.findByFarm(u.getFarm());

        double totalBrut = 0.0;
        double totalValeurNette = 0.0;
        long totalActifs = tousLesInvestissements.size();

        java.time.LocalDate aujourdhui = java.time.LocalDate.now();

        for (Investissement inv : tousLesInvestissements) {
                // Ajout au montant brut global
                if (inv.getMontant() != null) {
                totalBrut += inv.getMontant();
                }

                // Calcul des mois écoulés pour obtenir la valeur nette dynamique actuelle
                long moisEcoules = 0;
                if (inv.getDateAchat() != null) {
                long calculMois = java.time.temporal.ChronoUnit.MONTHS.between(
                        inv.getDateAchat().withDayOfMonth(1), 
                        aujourdhui.withDayOfMonth(1)
                );
                moisEcoules = Math.max(0, Math.min(calculMois, inv.getDureeAmortissement()));
                }

                double amortissementMensuel = inv.getAmortissementMensuel();
                double amortiCumuleDynamique = Math.round((moisEcoules * amortissementMensuel) * 100.0) / 100.0;
                double valeurNetteDynamique = Math.max(0.0, (inv.getMontant() != null ? inv.getMontant() : 0.0) - amortiCumuleDynamique);

                totalValeurNette += valeurNetteDynamique;
        }

        // Arrondis propres à deux décimales pour le FCFA
        return InvestissementStatsDTO.builder()
                .totalBrut(Math.round(totalBrut * 100.0) / 100.0)
                .totalValeurNette(Math.round(totalValeurNette * 100.0) / 100.0)
                .totalActifs(totalActifs)
                .build();
        }

   
    private InvestissementDTO toDTO(Investissement entity) {
    if (entity == null) return null;

    // 1. Calcul dynamique des mois écoulés depuis l'achat
    long moisEcoules = 0;
    if (entity.getDateAchat() != null) {
        // Calcule la différence exacte en mois entre la date d'achat et aujourd'hui
        long calculMois = java.time.temporal.ChronoUnit.MONTHS.between(
            entity.getDateAchat().withDayOfMonth(1), 
            java.time.LocalDate.now().withDayOfMonth(1)
        );
        // On s'assure que si l'achat est récent ou futur, on ne descend pas en dessous de 0
        // et qu'on ne dépasse pas la durée maximale d'amortissement
        moisEcoules = Math.max(0, Math.min(calculMois, entity.getDureeAmortissement()));
    }

    // 2. Calcul des montants financiers dynamiques
    double amortissementMensuel = entity.getAmortissementMensuel();
    
    // L'amorti cumulé devient dynamique : mois écoulés * mensualité
    double amortiCumuleDynamique = Math.round((moisEcoules * amortissementMensuel) * 100.0) / 100.0;
    
    // La valeur nette réelle : Montant d'achat - l'amorti cumulé dynamique
    double valeurNetteDynamique = Math.max(0.0, entity.getMontant() - amortiCumuleDynamique);

    // [Le reste de ton code pour les répartitions reste identique...]
    List<InvestissementRepartitionDTO> repartitionsDTO = null;
    if (entity.getRepartitions() != null) {
        repartitionsDTO = entity.getRepartitions().stream()
                .map(r -> {
                    java.time.LocalDate finTheorique = null;
                    if (r.getDateDebut() != null && r.getMoisUtilises() != null) {
                        finTheorique = r.getDateDebut().plusMonths(r.getMoisUtilises());
                    }
                    return InvestissementRepartitionDTO.builder()
                            .id(r.getId())
                            .codeProjet(r.getProjet() != null ? r.getProjet().getUniqueId() : null)
                            .titreProjet(r.getProjet() != null ? r.getProjet().getTitre() : null)
                            .dateDebut(r.getDateDebut())
                            .dateFin(r.getDateFin() != null ? r.getDateFin() : finTheorique)
                            .moisUtilises(r.getMoisUtilises())
                            .montantAlloue(r.getMontantAlloue())
                            .build();
                })
                .toList();
    }

    return InvestissementDTO.builder()
            .uniqueId(entity.getUniqueId())
            .categorie(entity.getCategorie())
            .nom(entity.getNom())
            .icon(entity.getIcon())
            .montant(entity.getMontant())
            .dateAchat(entity.getDateAchat())
            .fournisseur(entity.getFournisseur())
            .type(entity.getType())
            .dureeAmortissement(entity.getDureeAmortissement())
            .affectation(entity.getAffectation())
            .commentaire(entity.getCommentaire())
            // 🟢 On envoie les valeurs dynamiques calculées en temps réel au Front
            .amortiCumule(amortiCumuleDynamique)
            .amortissementMensuel(amortissementMensuel)
            .valeurNette(valeurNetteDynamique)
            .repartitions(repartitionsDTO)
            .build();
}

}