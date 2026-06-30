package com.diafarms.ml.ServiceImpl;

import com.diafarms.ml.DTO.InvestissementDTO;
import com.diafarms.ml.DTO.InvestissementRepartitionDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Investissement;
import com.diafarms.ml.models.InvestissementRepartition;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.repository.InvestissementRepartitionRepository;
import com.diafarms.ml.repository.InvestissementRepository;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.services.InvestissementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvestissementServiceImpl implements InvestissementService {

    private final InvestissementRepository investissementRepo;
    private final InvestissementRepartitionRepository repartitionRepo;
    private final ProjetsRepo projetsRepo; 

    @Override
    public List<InvestissementDTO> getInvestissementsParFerme(Long farmId) {
        return investissementRepo.findByFarmIdAndInitialisationRemovedFalse(farmId)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    public InvestissementDTO getInvestissementParUniqueId(String uniqueId) {
        Investissement inv = investissementRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Investissement introuvable avec l'ID: " + uniqueId));
        return toDTO(inv);
    }

    @Override
    @Transactional
    public InvestissementDTO creerInvestissement(Investissement investissement, Long farmId, Long utilisateurId) {
        // Logique de chargement / proxies utilisateur & farm ici si nécessaire
        
        // Exemple d'hydratation basique
        investissement.setUniqueId("INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        investissement.setInitialisation(Initialisation.init());
        investissement.setAmortiCumule(0.0);
        
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


    private InvestissementDTO toDTO(Investissement entity) {
        if (entity == null) return null;

        List<InvestissementRepartitionDTO> repartitionsDTO = null;
        if (entity.getRepartitions() != null) {
            repartitionsDTO = entity.getRepartitions().stream()
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
                .amortiCumule(entity.getAmortiCumule())
                .amortissementMensuel(entity.getAmortissementMensuel())
                .valeurNette(entity.getValeurNette())
                .repartitions(repartitionsDTO)
                .build();
    }
}