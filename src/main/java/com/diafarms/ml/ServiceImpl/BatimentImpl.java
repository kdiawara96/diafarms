package com.diafarms.ml.ServiceImpl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.BatimentsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.services.BatimentServices;
import com.diafarms.ml.services.LogsServices;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class BatimentImpl implements BatimentServices {

    private final BatimentRepo batimentRepo;
    private final LogsServices logs;
    private final OtherService OtherService;
    private final com.diafarms.ml.repository.InvestissementRepository investissementRepo;
    private final com.diafarms.ml.repository.OccupationBatimentRepo occupationBatimentRepo;
    private final com.diafarms.ml.repository.MortaliteRepo mortaliteRepo;
    private final com.diafarms.ml.repository.ReformeRepo reformeRepo;

    // Remplit BatimentsDTO.investissements (badge « Investissement : <nom> ») en une
    // seule requête pour toute la liste.
    private List<BatimentsDTO> avecInvestissements(List<BatimentsDTO> dtos) {
        List<Long> ids = dtos.stream().map(BatimentsDTO::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return dtos;
        java.util.Map<Long, List<String>> parBatiment = new java.util.HashMap<>();
        for (Object[] row : investissementRepo.nomsParBatiments(ids)) {
            parBatiment.computeIfAbsent((Long) row[0], k -> new java.util.ArrayList<>()).add((String) row[1]);
        }
        dtos.forEach(d -> d.setInvestissements(parBatiment.getOrDefault(d.getId(), List.of())));
        return dtos;
    }

    private Utilisateurs currentUserOuNull() {
        try {
            return OtherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public BatimentsDTO create(Batiment batiment) {

        batiment.setUniqueId(UUID.randomUUID().toString());
        batiment.setInitialisation(Initialisation.init());

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (currentUser != null && currentUser.getFarm() != null) {

            if (batimentRepo.existsByNomIgnoreCaseAndFarmId(
                    batiment.getNom().trim(),
                    currentUser.getFarm().getId())) {

                throw new RuntimeException(
                        "Un bâtiment portant ce nom existe déjà."
                );
            }

            batiment.setFarm(currentUser.getFarm());
        } else if (currentUser != null) {
            // Compte sans ferme (SUPER_ADMIN) : un bâtiment appartient forcément à
            // une ferme, impossible d'en créer un sans en avoir une.
            throw new RuntimeException("Votre compte n'est rattaché à aucune ferme : impossible de créer un bâtiment.");
        }

        Batiment savedBatiment = batimentRepo.save(batiment);

        if (currentUser != null) {
            logs.addLogs(
                    currentUser.getId(),
                    savedBatiment.getId(),
                    "Batiment",
                    "Ajout d'un bâtiment avec succès !"
            );
        }

        return BatimentsDTO.toDTO(savedBatiment);
    }

    @Transactional
    @Override
    public BatimentsDTO update(Batiment batiment) {
        
        // 1. Récupérer le bâtiment existant
        Batiment existingBatiment = batimentRepo.findByUniqueId(batiment.getUniqueId());
        if (existingBatiment == null) {
            throw new RuntimeException("Bâtiment non trouvé !");
        }
        com.diafarms.ml.commons.FermeScope.verifier(existingBatiment.getFarm(), currentUserOuNull(), "Bâtiment non trouvé !");

        // 2. Vérification du nom (uniquement si le nom a changé)
        String nouveauNom = batiment.getNom();
        if (nouveauNom != null && !nouveauNom.trim().isEmpty()) {
            
            if (!existingBatiment.getNom().equalsIgnoreCase(nouveauNom)) {
                
                Optional<Batiment> batimentExistant = batimentRepo.findOptionalByNom(nouveauNom.trim());
                
                if (batimentExistant.isPresent()) {
                    Batiment autreBatiment = batimentExistant.get();
                    
                    // On exclut bien l'entité en cours de modification
                    if (!autreBatiment.getUniqueId().equals(batiment.getUniqueId())) {
                        throw new RuntimeException("Un bâtiment avec ce nom existe déjà !");
                    }
                }
            }
        } else {
            throw new RuntimeException("Le nom du bâtiment est obligatoire !");
        }

        // 3. Mise à jour des champs
        existingBatiment.setNom(nouveauNom.trim());
        existingBatiment.setCapacite(batiment.getCapacite());
        existingBatiment.setStatut(batiment.getStatut());
        existingBatiment.setDescription(batiment.getDescription());
        existingBatiment.setDateDerniereMaintenance(batiment.getDateDerniereMaintenance());
        existingBatiment.setSuperficieM2(batiment.getSuperficieM2());
        existingBatiment.setLatitude(batiment.getLatitude());
        existingBatiment.setLongitude(batiment.getLongitude());

        existingBatiment.setInitialisation(Initialisation.updateDate(existingBatiment.getInitialisation()));

        // 4. Logs
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), existingBatiment.getId(), "Batiment", "Mise à jour d'un bâtiment");
        }

        return BatimentsDTO.toDTO(batimentRepo.save(existingBatiment));
    }


    @Transactional
    @Override
    public String deleteOrRecover(String uniqueIdBatiment) {
        
        Batiment batiment = batimentRepo.findByUniqueId(uniqueIdBatiment);
        if (batiment == null) {
            throw new RuntimeException("Bâtiment non trouvé !");
        }
        com.diafarms.ml.commons.FermeScope.verifier(batiment.getFarm(), currentUserOuNull(), "Bâtiment non trouvé !");

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (batiment.getInitialisation().getRemoved()) {
            // Récupération (Restore)
            batiment.getInitialisation().setRemoved(false);
            batimentRepo.save(batiment);
            
            if (currentUser != null) {
                logs.addLogs(currentUser.getId(), batiment.getId(), "Batiment", 
                            "Récupération d'un bâtiment");
            }
            return "Bâtiment récupéré avec succès !";
            
        } else {
            // Suppression logique (Soft Delete)
            batiment.getInitialisation().setRemoved(true);
            batimentRepo.save(batiment);
            // Retire seulement le lien avec les investissements (l'investissement reste
            // intact) ; une récupération ultérieure repart sans lien, à relier au besoin.
            investissementRepo.supprimerLiensBatiment(batiment.getId());
            
            if (currentUser != null) {
                logs.addLogs(currentUser.getId(), batiment.getId(), "Batiment", 
                            "Suppression d'un bâtiment");
            }
            return "Bâtiment supprimé avec succès !";
        }
    }

    @Override
    public List<BatimentsDTO> findAll() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        List<Batiment> batiments = List.of();

        if (currentUser != null && currentUser.getFarm() != null) {
            batiments = batimentRepo.findActiveByFarmId(currentUser.getFarm().getId()); // À créer dans le repo
        }
        return avecInvestissements(batiments.stream()
                        .map(BatimentsDTO::toDTO)
                        .collect(Collectors.toList()));
    }

   @Override
    public List<BatimentsDTO> search(String search) {
        if (search == null || search.trim().isEmpty()) {
            return findAll();
        }

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }


        List<Batiment> batiments = List.of();

        if (currentUser != null && currentUser.getFarm() != null) {
            batiments = batimentRepo.searchBatimentsByFarm(currentUser.getFarm().getId(), search.trim());
        }
        return avecInvestissements(batiments.stream()
                        .map(BatimentsDTO::toDTO)
                        .collect(Collectors.toList()));
    }

   @Override
   @Transactional(readOnly = true)
   // Vue plan : 4 requêtes au total (poulaillers, occupations actives, mortalité et
   // réforme groupées par poulailler), quel que soit le nombre de poulaillers. Effectif
   // vivant = même règle que EffectifVivantHelper.effectifBatiment.
   public List<com.diafarms.ml.DTO.BatimentPlanDTO> plan() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            return List.of();
        }
        if (currentUser == null || currentUser.getFarm() == null) return List.of();
        Long farmId = currentUser.getFarm().getId();

        java.util.Map<Long, List<com.diafarms.ml.models.OccupationBatiment>> occupations = occupationBatimentRepo
                .findActivesByFarmId(farmId).stream()
                .collect(Collectors.groupingBy(o -> o.getBatiment().getId()));
        java.util.Map<Long, Integer> morts = versMap(mortaliteRepo.sumMortsParBatimentDeLaFerme(farmId));
        java.util.Map<Long, Integer> reformes = versMap(reformeRepo.sumSujetsParBatimentDeLaFerme(farmId));

        return batimentRepo.findActiveByFarmId(farmId).stream()
                .sorted(java.util.Comparator.comparing(Batiment::getNom, String.CASE_INSENSITIVE_ORDER))
                .map(b -> {
                    List<com.diafarms.ml.models.OccupationBatiment> actives = occupations.getOrDefault(b.getId(), List.of());
                    // Comme effectifBatiment : l'occupation active la plus récente fait foi.
                    Integer base = actives.isEmpty() ? null : actives.get(0).getNbSujetsDansBatiment();
                    Integer effectif = base == null ? null
                            : Math.max(0, base - morts.getOrDefault(b.getId(), 0) - reformes.getOrDefault(b.getId(), 0));
                    return com.diafarms.ml.DTO.BatimentPlanDTO.builder()
                            .uniqueId(b.getUniqueId())
                            .nom(b.getNom())
                            .capacite(b.getCapacite())
                            .statut(b.getStatut() != null ? b.getStatut().name() : null)
                            .superficieM2(b.getSuperficieM2())
                            .occupe(!actives.isEmpty())
                            .sujetsPlaces(base)
                            .effectifVivant(effectif)
                            .projets(actives.stream().map(o -> com.diafarms.ml.DTO.BatimentPlanDTO.Occupant.builder()
                                    .uniqueId(o.getProjet().getUniqueId())
                                    .code(o.getProjet().getCode())
                                    .titre(o.getProjet().getTitre())
                                    .nbSujets(o.getNbSujetsDansBatiment())
                                    .dateEntree(o.getDateEntree() != null ? o.getDateEntree().toString() : null)
                                    .build()).toList())
                            .build();
                })
                .toList();
   }

   private static java.util.Map<Long, Integer> versMap(List<Object[]> lignes) {
        java.util.Map<Long, Integer> m = new java.util.HashMap<>();
        for (Object[] l : lignes) {
            if (l[0] != null && l[1] != null) m.put(((Number) l[0]).longValue(), ((Number) l[1]).intValue());
        }
        return m;
   }

   @Override
   @Transactional(readOnly = true)
   public List<BatimentsDTO> tous() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (currentUser == null || currentUser.getFarm() == null) return List.of();
        return batimentRepo.findActiveByFarmId(currentUser.getFarm().getId()).stream()
                .map(BatimentsDTO::select)
                .collect(Collectors.toList());
   }

   @Override
   public List<BatimentsDTO> select() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<Batiment> batiments = List.of();

        if (currentUser != null && currentUser.getFarm() != null) {
            batiments = batimentRepo.findAvailableByFarmId(currentUser.getFarm().getId());
        }
        return batiments.stream()
                        .map(BatimentsDTO::select)
                        .collect(Collectors.toList());
   }

    @Override
    public PaginatedResponse<BatimentsDTO> listPaginated(int page, int size, String search) {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createdAt"));
        Page<Batiment> batimentPage = Page.empty(pageable);

        if (currentUser != null && currentUser.getFarm() != null) {
            Long farmId = currentUser.getFarm().getId();
            batimentPage = (search != null && !search.trim().isEmpty())
                    ? batimentRepo.searchBatimentsByFarm(farmId, search.trim(), pageable)
                    : batimentRepo.findActiveByFarmId(farmId, pageable);
        }

        List<BatimentsDTO> dtoList = avecInvestissements(batimentPage.getContent().stream()
                .map(BatimentsDTO::toDTO)
                .collect(Collectors.toList()));

        return new PaginatedResponse<>(
                dtoList,
                batimentPage.getNumber(),
                batimentPage.getTotalPages(),
                batimentPage.getTotalElements(),
                batimentPage.getSize()
        );
    }

}
