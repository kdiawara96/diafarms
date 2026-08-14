package com.diafarms.ml.ServiceImpl;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.PaiementSalaireDTO;
import com.diafarms.ml.DTO.SalaireDTO;
import com.diafarms.ml.DTO.TauxSalaireDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.PaiementSalaire;
import com.diafarms.ml.models.Personnel;
import com.diafarms.ml.models.Salaire;
import com.diafarms.ml.models.Salaire.ModePaiement;
import com.diafarms.ml.models.SalaireHistorique;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.PaiementSalaireRepo;
import com.diafarms.ml.repository.PersonnelRepo;
import com.diafarms.ml.repository.SalaireHistoriqueRepo;
import com.diafarms.ml.repository.SalaireRepo;
import com.diafarms.ml.request.create.SalaireDefinirRequest;
import com.diafarms.ml.request.others.SalairePayerRequest;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.MinioService;
import com.diafarms.ml.services.SalaireService;
import com.diafarms.ml.services.TransactionService;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

// Salaire = grille salariale d'un employé (mode MENSUEL/JOURNALIER/HORAIRE + taux) ;
// PaiementSalaire = un paiement réel pour une période — voir Salaire.java/
// PaiementSalaire.java. "Payer le salaire" calcule le montant selon le mode
// (tauxBase directement en MENSUEL, tauxBase × quantite en JOURNALIER/HORAIRE) et
// génère une vraie Transaction (TransactionService.createSortieCommune) plutôt que
// de laisser ressaisir une transaction manuelle non structurée. Permissions alignées
// sur FactureServiceImpl : gestion RH/finance réservée à ADMIN/RESPONSABLE/COMPTABLE,
// pas VENTE/PRODUCTION.
@Service
@RequiredArgsConstructor
public class SalaireServiceImpl implements SalaireService {

    private final SalaireRepo salaireRepo;
    private final PaiementSalaireRepo paiementSalaireRepo;
    private final SalaireHistoriqueRepo salaireHistoriqueRepo;
    private final PersonnelRepo personnelRepo;
    private final TransactionService transactionService;
    private final LogsServices logs;
    private final OtherService otherService;
    private final MinioService minioService;

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
            throw new IllegalArgumentException("Vous n'avez pas les droits pour gérer les salaires.");
        }
    }

    @Override
    @Transactional
    public SalaireDTO definir(SalaireDefinirRequest data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (data.getEmployeUniqueId() == null || data.getEmployeUniqueId().isBlank()) {
            throw new IllegalArgumentException("L'employé est obligatoire.");
        }
        if (data.getTauxBase() == null || data.getTauxBase() <= 0) {
            throw new IllegalArgumentException("Le taux (mensuel/journalier/horaire) doit être positif.");
        }
        ModePaiement modePaiement;
        try {
            modePaiement = ModePaiement.valueOf(data.getModePaiement().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Mode de paiement invalide (attendu MENSUEL, JOURNALIER ou HORAIRE) : " + data.getModePaiement());
        }
        Personnel employe = personnelRepo.findByUniqueId(data.getEmployeUniqueId());
        if (employe == null) {
            throw new IllegalArgumentException("Employé introuvable : " + data.getEmployeUniqueId());
        }

        Salaire s = salaireRepo.findByEmploye_UniqueIdAndFarm_Id(data.getEmployeUniqueId(), currentUser.getFarm().getId());
        boolean nouveau = (s == null);
        if (nouveau) {
            s = new Salaire();
            s.setUniqueId(java.util.UUID.randomUUID().toString());
            s.setEmploye(employe);
            s.setFarm(currentUser.getFarm());
            s.setInitialisation(Initialisation.init());
        } else if (s.getInitialisation() != null) {
            s.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }
        // Le taux change réellement (ou c'est un tout nouveau Salaire) : ferme
        // l'enregistrement historique actif et en ouvre un nouveau — sans ça, payer
        // en retard une période antérieure à ce changement proposerait à tort le
        // NOUVEAU taux (voir resolveTauxPourPeriode, utilisé par payer()).
        boolean changementReel = nouveau || !modePaiement.equals(s.getModePaiement()) || !data.getTauxBase().equals(s.getTauxBase());
        if (changementReel) {
            if (!nouveau) {
                SalaireHistorique actif = salaireHistoriqueRepo.findFirstBySalaire_IdAndDateFinIsNull(s.getId());
                if (actif != null) {
                    actif.setDateFin(LocalDate.now());
                    salaireHistoriqueRepo.save(actif);
                }
            }
            SalaireHistorique nouvelHistorique = new SalaireHistorique();
            nouvelHistorique.setUniqueId(java.util.UUID.randomUUID().toString());
            nouvelHistorique.setSalaire(s);
            nouvelHistorique.setModePaiement(modePaiement);
            nouvelHistorique.setTauxBase(data.getTauxBase());
            nouvelHistorique.setDateEffective(LocalDate.now());
            nouvelHistorique.setInitialisation(Initialisation.init());
            s.setModePaiement(modePaiement);
            s.setTauxBase(data.getTauxBase());
            Salaire saved = salaireRepo.save(s);
            nouvelHistorique.setSalaire(saved);
            salaireHistoriqueRepo.save(nouvelHistorique);

            logs.addLogs(currentUser.getId(), saved.getId(), "Salaire",
                    (nouveau ? "Grille salariale définie pour " : "Grille salariale mise à jour pour ") + employe.getNom()
                            + " (" + modePaiement + ", " + data.getTauxBase() + " FCFA)");
            return SalaireDTO.fromEntity(saved, paiementSalaireRepo.findFirstBySalaire_IdOrderByPeriodeDesc(saved.getId()));
        }

        Salaire saved = salaireRepo.save(s);
        return SalaireDTO.fromEntity(saved, paiementSalaireRepo.findFirstBySalaire_IdOrderByPeriodeDesc(saved.getId()));
    }

    // Taux réellement en vigueur pour la période demandée (format "AAAA-MM") — le
    // dernier enregistrement SalaireHistorique dont dateEffective ne dépasse pas la
    // fin de ce mois-là. Si aucun (données antérieures à cette fonctionnalité, jamais
    // migrées), retombe sur le taux ACTUEL de la grille plutôt que d'échouer.
    private TauxSalaireDTO resolveTauxPourPeriode(Salaire s, String periode) {
        java.time.YearMonth ym = java.time.YearMonth.parse(periode);
        SalaireHistorique h = salaireHistoriqueRepo.findFirstBySalaire_IdAndDateEffectiveLessThanEqualOrderByDateEffectiveDesc(
                s.getId(), ym.atEndOfMonth());
        if (h != null) {
            return TauxSalaireDTO.builder().modePaiement(h.getModePaiement().name()).tauxBase(h.getTauxBase()).build();
        }
        return TauxSalaireDTO.builder().modePaiement(s.getModePaiement().name()).tauxBase(s.getTauxBase()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public TauxSalaireDTO getTauxPourPeriode(String employeUniqueId, String periode) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (periode == null || !periode.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("La période est obligatoire (format AAAA-MM).");
        }
        Salaire s = salaireRepo.findByEmploye_UniqueIdAndFarm_Id(employeUniqueId, currentUser.getFarm().getId());
        if (s == null) {
            throw new IllegalArgumentException("Aucun salaire de base défini pour cet employé.");
        }
        return resolveTauxPourPeriode(s, periode);
    }

    @Override
    @Transactional
    public PaiementSalaireDTO payer(SalairePayerRequest data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (data.getEmployeUniqueId() == null || data.getEmployeUniqueId().isBlank()) {
            throw new IllegalArgumentException("L'employé est obligatoire.");
        }
        if (data.getPeriode() == null || !data.getPeriode().matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("La période est obligatoire (format AAAA-MM).");
        }

        Salaire s = salaireRepo.findByEmploye_UniqueIdAndFarm_Id(data.getEmployeUniqueId(), currentUser.getFarm().getId());
        if (s == null) {
            throw new IllegalArgumentException("Aucun salaire de base défini pour cet employé — définissez-le d'abord.");
        }
        if (paiementSalaireRepo.existsBySalaire_IdAndPeriode(s.getId(), data.getPeriode())) {
            throw new IllegalArgumentException("Le salaire de " + data.getPeriode() + " a déjà été payé pour " + s.getEmploye().getNom() + ".");
        }

        // Taux réellement en vigueur pour CETTE période (pas forcément le taux actuel
        // de la grille — voir resolveTauxPourPeriode) : sert au calcul automatique ET
        // gardé sur le paiement pour que le bulletin reste exact même si la grille
        // change plus tard (ex: paiement en retard d'un mois à l'ancien taux).
        TauxSalaireDTO tauxPeriode = resolveTauxPourPeriode(s, data.getPeriode());
        ModePaiement modePeriode = ModePaiement.valueOf(tauxPeriode.getModePaiement());

        Double quantite = null;
        double montant;
        if (data.getMontant() != null && data.getMontant() > 0) {
            // Montant forcé explicitement — prioritaire sur le calcul automatique, quel
            // que soit le mode (permet une prime/retenue ponctuelle sans changer la grille).
            montant = data.getMontant();
            if (modePeriode != ModePaiement.MENSUEL) quantite = data.getQuantite();
        } else if (modePeriode == ModePaiement.MENSUEL) {
            montant = tauxPeriode.getTauxBase();
        } else {
            if (data.getQuantite() == null || data.getQuantite() <= 0) {
                throw new IllegalArgumentException(modePeriode == ModePaiement.HORAIRE
                        ? "Veuillez indiquer le nombre d'heures travaillées."
                        : "Veuillez indiquer le nombre de jours travaillés.");
            }
            quantite = data.getQuantite();
            montant = tauxPeriode.getTauxBase() * quantite;
        }

        PaiementSalaire p = new PaiementSalaire();
        p.setUniqueId(java.util.UUID.randomUUID().toString());
        p.setSalaire(s);
        p.setPeriode(data.getPeriode());
        p.setMontantPaye(montant);
        p.setQuantite(quantite);
        p.setModePaiementApplique(modePeriode);
        p.setTauxApplique(tauxPeriode.getTauxBase());
        p.setDatePaiement(LocalDate.now());
        p.setCreePar(currentUser);
        p.setInitialisation(Initialisation.init());
        PaiementSalaire saved = paiementSalaireRepo.save(p);

        String description = (data.getDescription() != null && !data.getDescription().isBlank())
                ? data.getDescription()
                : "Salaire " + data.getPeriode() + " — " + s.getEmploye().getNom();
        transactionService.createSortieCommune(currentUser.getFarm(), montant, "Salaires", LocalDate.now(),
                description, SourceTransaction.SALAIRE, saved.getUniqueId(), currentUser);

        logs.addLogs(currentUser.getId(), saved.getId(), "PaiementSalaire",
                "Salaire de " + montant + " FCFA payé à " + s.getEmploye().getNom() + " pour " + data.getPeriode());
        return PaiementSalaireDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<SalaireDTO> list(int page, int size) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "employe.nom"));
        Page<Salaire> salairePage = salaireRepo.search(currentUser.getFarm().getId(), pageable);
        List<SalaireDTO> dtoList = salairePage.getContent().stream()
                .map(s -> SalaireDTO.fromEntity(s, paiementSalaireRepo.findFirstBySalaire_IdOrderByPeriodeDesc(s.getId())))
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                salairePage.getNumber() + 1,
                salairePage.getTotalPages(),
                salairePage.getTotalElements(),
                salairePage.getSize()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<PaiementSalaireDTO> listPaiements(String employeUniqueId, int page, int size) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }
        Salaire s = salaireRepo.findByEmploye_UniqueIdAndFarm_Id(employeUniqueId, currentUser.getFarm().getId());
        if (s == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periode"));
        Page<PaiementSalaire> paiementPage = paiementSalaireRepo.findBySalaireId(s.getId(), pageable);
        List<PaiementSalaireDTO> dtoList = paiementPage.getContent().stream().map(PaiementSalaireDTO::fromEntity).toList();

        return new PaginatedResponse<>(
                dtoList,
                paiementPage.getNumber() + 1,
                paiementPage.getTotalPages(),
                paiementPage.getTotalElements(),
                paiementPage.getSize()
        );
    }

    // Logo/tampon optionnels (voir Farm.logoNomMinio/tamponNomMinio) — mirroir de
    // FactureServiceImpl.chargerImage, un échec de chargement est traité comme
    // absent plutôt que de faire échouer toute la génération du PDF.
    private Image chargerImage(String nomMinio) {
        if (nomMinio == null) return null;
        try (java.io.InputStream stream = minioService.downloadFile(nomMinio)) {
            return Image.getInstance(stream.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] genererBulletinPdf(String paiementUniqueId) {
        PaiementSalaire p = paiementSalaireRepo.findByUniqueId(paiementUniqueId);
        if (p == null) throw new IllegalArgumentException("Paiement introuvable : " + paiementUniqueId);
        Salaire s = p.getSalaire();
        Personnel employe = s.getEmploye();
        Farm farm = s.getFarm();
        // Mode/taux réellement appliqués à CE paiement (voir payer()) — retombe sur le
        // taux ACTUEL de la grille seulement pour un paiement antérieur à cette
        // fonctionnalité (modePaiementApplique/tauxApplique alors null).
        ModePaiement modeBulletin = p.getModePaiementApplique() != null ? p.getModePaiementApplique() : s.getModePaiement();
        double tauxBulletin = p.getTauxApplique() != null ? p.getTauxApplique() : s.getTauxBase();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 20, Font.BOLD);
            Font normalFont = new Font(Font.HELVETICA, 11, Font.NORMAL);
            Font boldFont = new Font(Font.HELVETICA, 11, Font.BOLD);

            Image logo = farm != null ? chargerImage(farm.getLogoNomMinio()) : null;
            if (logo != null) {
                logo.scaleToFit(150, 80);
                logo.setAlignment(Element.ALIGN_LEFT);
                document.add(logo);
            }

            Paragraph title = new Paragraph("BULLETIN DE PAIE", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);
            document.add(Chunk.NEWLINE);

            document.add(new Paragraph("Période : " + p.getPeriode(), boldFont));
            document.add(new Paragraph("Date de paiement : " + p.getDatePaiement().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), normalFont));
            document.add(Chunk.NEWLINE);

            document.add(new Paragraph("Employé : " + employe.getNom(), boldFont));
            if (employe.getPoste() != null) document.add(new Paragraph("Poste : " + employe.getPoste(), normalFont));
            if (employe.getTelephone() != null) document.add(new Paragraph("Téléphone : " + employe.getTelephone(), normalFont));
            document.add(Chunk.NEWLINE);

            String modeLabel = switch (modeBulletin) {
                case MENSUEL -> "Mensuel";
                case JOURNALIER -> "Journalier";
                case HORAIRE -> "Horaire";
            };
            document.add(new Paragraph("Mode de paiement : " + modeLabel, normalFont));
            String suffixeTaux = switch (modeBulletin) {
                case MENSUEL -> "/ mois";
                case JOURNALIER -> "/ jour";
                case HORAIRE -> "/ heure";
            };
            document.add(new Paragraph("Taux : " + String.format("%.0f FCFA %s", tauxBulletin, suffixeTaux), normalFont));
            if (p.getQuantite() != null) {
                String uniteQuantite = modeBulletin == ModePaiement.HORAIRE ? "heure(s)" : "jour(s)";
                document.add(new Paragraph("Quantité : " + p.getQuantite() + " " + uniteQuantite, normalFont));
            }
            document.add(Chunk.NEWLINE);

            document.add(new Paragraph("Montant net payé : " + String.format("%.0f FCFA", p.getMontantPaye()), titleFont));
            document.add(Chunk.NEWLINE);

            Image tampon = farm != null ? chargerImage(farm.getTamponNomMinio()) : null;
            if (tampon != null) {
                tampon.scaleToFit(100, 100);
                tampon.setAlignment(Element.ALIGN_RIGHT);
                document.add(tampon);
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la génération du bulletin : " + e.getMessage(), e);
        }
    }
}
