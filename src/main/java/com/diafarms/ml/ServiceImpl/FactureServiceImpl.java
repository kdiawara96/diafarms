package com.diafarms.ml.ServiceImpl;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.FactureDTO;
import com.diafarms.ml.DTO.FactureLigneDTO;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.commons.PdfStyle;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Commande;
import com.diafarms.ml.models.Commande.StatutCommande;
import com.diafarms.ml.models.Facture;
import com.diafarms.ml.models.Facture.SourceFacture;
import com.diafarms.ml.models.Facture.StatutFacture;
import com.diafarms.ml.models.FactureLigne;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.CommandeRepo;
import com.diafarms.ml.repository.FactureLigneRepo;
import com.diafarms.ml.repository.FactureRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.FactureGenerateRequest;
import com.diafarms.ml.request.create.FactureGenerateRequest.VenteRef;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.services.FactureService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.MinioService;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

// Facture = document figé généré depuis une ou plusieurs ventes (FactureLigne) ou une
// commande — voir Facture.java/FactureLigne.java. montantPaye/statut ne sont plus
// stockés pour les nouvelles factures (legacy = false) : ils sont recalculés à la volée
// depuis les imputations de chaque ligne (CompteClientService.payeVente), voir toDto.
// Permissions alignées sur la note du roadmap ("la facturation reste une action web
// ADMIN/COMPTABLE") : génération/paiement réservés à ADMIN/RESPONSABLE/COMPTABLE,
// annulation réservée à ADMIN/SUPER_ADMIN/RESPONSABLE (pas COMPTABLE).
@Service
@RequiredArgsConstructor
public class FactureServiceImpl implements FactureService {

    private final FactureRepo factureRepo;
    private final FactureLigneRepo factureLigneRepo;
    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteReformeRepo venteReformeRepo;
    private final CommandeRepo commandeRepo;
    private final CompteClientService compteClientService;
    private final PaiementClientService paiementClientService;
    private final com.diafarms.ml.repository.PaiementClientRepo paiementClientRepo;
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
            throw new IllegalArgumentException("Vous n'avez pas les droits pour gérer la facturation.");
        }
    }

    private void ensureCanAnnuler(Utilisateurs u) {
        if (!isAdmin(u) && !hasRole(u, "RESPONSABLE")) {
            throw new IllegalArgumentException("Seul un administrateur ou un responsable peut annuler une facture.");
        }
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static boolean estActive(Initialisation init) {
        return init == null || !Boolean.TRUE.equals(init.getRemoved());
    }

    // La vente d'une ligne existe encore et n'est pas supprimée.
    private boolean venteActive(FactureLigne l) {
        if (l.getVenteType() == CibleImputation.VENTE_OEUFS) {
            return venteOeufsRepo.findByUniqueId(l.getVenteUniqueId()).map(v -> estActive(v.getInitialisation())).orElse(false);
        }
        if (l.getVenteType() == CibleImputation.VENTE_REFORME) {
            return venteReformeRepo.findByUniqueId(l.getVenteUniqueId()).map(v -> estActive(v.getInitialisation())).orElse(false);
        }
        return false;
    }

    private String genererNumero(Long farmId) {
        int year = LocalDate.now().getYear();
        long seq = factureRepo.countByFarm_Id(farmId) + 1;
        String numero;
        do {
            numero = String.format("FAC-%d-%04d", year, seq);
            seq++;
        } while (factureRepo.existsByNumeroFacture(numero));
        return numero;
    }

    // Facture/vente/commande introuvable OU d'une autre ferme -> même message, pour ne
    // pas révéler l'existence d'un enregistrement d'une autre ferme.
    private Facture factureFarmScoped(String uniqueId, Utilisateurs currentUser) {
        Facture f = factureRepo.findByUniqueId(uniqueId);
        if (f == null || currentUser == null || currentUser.getFarm() == null
                || f.getFarm() == null || !f.getFarm().getId().equals(currentUser.getFarm().getId())) {
            throw new IllegalArgumentException("Facture introuvable : " + uniqueId);
        }
        return f;
    }

    // ===================== Génération =====================

    private record LigneAGenerer(CibleImputation type, String venteUniqueId, String description,
                                  Integer quantite, Double prixUnitaire, double montant) {}

    private static VenteRef ref(String type, String uniqueId) {
        VenteRef r = new VenteRef();
        r.setType(type);
        r.setUniqueId(uniqueId);
        return r;
    }

    // Détermine la liste explicite de ventes à facturer à partir de la requête — ou
    // renvoie null si la source est une commande (voir venteRefsDeCommande, appelé
    // séparément par genererDepuis une fois la commande résolue et vérifiée).
    private List<VenteRef> resolveVenteRefs(FactureGenerateRequest data) {
        if (data.getVentes() != null && !data.getVentes().isEmpty()) {
            return data.getVentes();
        }
        if (data.getSourceType() != null && !data.getSourceType().isBlank()) {
            String st = data.getSourceType().trim().toUpperCase();
            if ("VENTE_OEUFS".equals(st) || "VENTE_REFORME".equals(st)) {
                if (data.getSourceUniqueId() == null || data.getSourceUniqueId().isBlank()) {
                    throw new IllegalArgumentException("La source de la facture (vente ou commande) est requise.");
                }
                return List.of(ref(st, data.getSourceUniqueId()));
            }
            if (!"COMMANDE".equals(st)) {
                throw new IllegalArgumentException("Type de source invalide (attendu VENTE_OEUFS, VENTE_REFORME ou COMMANDE) : " + data.getSourceType());
            }
        }
        return null;
    }

    private List<VenteRef> venteRefsDeCommande(Commande c) {
        List<VenteRef> refs = new ArrayList<>();
        if (c.getType() == TypeStockMagasin.REFORME) {
            for (VenteReforme v : venteReformeRepo.findActivesByCommandeId(c.getId())) {
                if (!factureLigneRepo.venteDejaFacturee(CibleImputation.VENTE_REFORME, v.getUniqueId())) {
                    refs.add(ref("VENTE_REFORME", v.getUniqueId()));
                }
            }
        } else {
            for (VenteOeufs v : venteOeufsRepo.findActivesByCommandeId(c.getId())) {
                if (!factureLigneRepo.venteDejaFacturee(CibleImputation.VENTE_OEUFS, v.getUniqueId())) {
                    refs.add(ref("VENTE_OEUFS", v.getUniqueId()));
                }
            }
        }
        return refs;
    }

    private Client verifierMemeClient(Client actuel, Client nouveau, String venteUid) {
        if (nouveau == null) {
            throw new IllegalArgumentException("Cette vente n'a pas de client identifié — impossible de générer une facture.");
        }
        if (actuel != null && !actuel.getId().equals(nouveau.getId())) {
            throw new IllegalArgumentException("Toutes les ventes doivent appartenir au même client (vente en cause : " + venteUid + ").");
        }
        return nouveau;
    }

    @Override
    @Transactional
    public FactureDTO genererDepuis(FactureGenerateRequest data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Long farmId = currentUser.getFarm().getId();

        List<VenteRef> refs = resolveVenteRefs(data);
        SourceFacture sourceType;
        String sourceUniqueIdCommande = null;

        if (refs == null) {
            if (data.getSourceUniqueId() == null || data.getSourceUniqueId().isBlank()) {
                throw new IllegalArgumentException("La source de la facture (vente ou commande) est requise.");
            }
            Commande commande = commandeRepo.findByUniqueId(data.getSourceUniqueId());
            if (commande == null || commande.getFarm() == null || !commande.getFarm().getId().equals(farmId)) {
                throw new IllegalArgumentException("Commande introuvable : " + data.getSourceUniqueId());
            }
            if (commande.getStatut() == StatutCommande.ANNULEE) {
                throw new IllegalArgumentException("Une commande annulée ne peut pas être facturée.");
            }
            sourceType = SourceFacture.COMMANDE;
            sourceUniqueIdCommande = commande.getUniqueId();
            refs = venteRefsDeCommande(commande);
            if (refs.isEmpty()) {
                throw new IllegalArgumentException("Aucune livraison à facturer.");
            }
        } else {
            sourceType = SourceFacture.VENTES;
        }

        Client client = null;
        List<LigneAGenerer> lignesAGenerer = new ArrayList<>();
        for (VenteRef v : refs) {
            if (v == null || v.getType() == null || v.getUniqueId() == null || v.getUniqueId().isBlank()) {
                throw new IllegalArgumentException("Référence de vente invalide.");
            }
            CibleImputation type;
            try {
                type = CibleImputation.valueOf(v.getType().trim().toUpperCase());
            } catch (Exception e) {
                type = null;
            }
            if (type != CibleImputation.VENTE_OEUFS && type != CibleImputation.VENTE_REFORME) {
                throw new IllegalArgumentException("Type de vente invalide : " + v.getType());
            }
            if (factureLigneRepo.venteDejaFacturee(type, v.getUniqueId())) {
                throw new IllegalArgumentException("Cette vente est déjà facturée : " + v.getUniqueId());
            }

            if (type == CibleImputation.VENTE_OEUFS) {
                VenteOeufs ve = venteOeufsRepo.findByUniqueId(v.getUniqueId())
                        .orElseThrow(() -> new IllegalArgumentException("Vente introuvable : " + v.getUniqueId()));
                if (ve.getFarm() == null || !ve.getFarm().getId().equals(farmId) || !estActive(ve.getInitialisation())) {
                    throw new IllegalArgumentException("Vente introuvable : " + v.getUniqueId());
                }
                client = verifierMemeClient(client, ve.getClient(), v.getUniqueId());
                lignesAGenerer.add(new LigneAGenerer(type, ve.getUniqueId(),
                        "Vente d'œufs — " + ve.getQuantiteOeufs() + " unité(s)",
                        ve.getQuantiteOeufs(), ve.getPrixUnitaire(), nz(ve.getMontant())));
            } else {
                VenteReforme ve = venteReformeRepo.findByUniqueId(v.getUniqueId())
                        .orElseThrow(() -> new IllegalArgumentException("Vente introuvable : " + v.getUniqueId()));
                if (ve.getFarm() == null || !ve.getFarm().getId().equals(farmId) || !estActive(ve.getInitialisation())) {
                    throw new IllegalArgumentException("Vente introuvable : " + v.getUniqueId());
                }
                client = verifierMemeClient(client, ve.getClient(), v.getUniqueId());
                lignesAGenerer.add(new LigneAGenerer(type, ve.getUniqueId(),
                        "Vente de réforme — " + ve.getNombreSujets() + " sujet(s)",
                        ve.getNombreSujets(), ve.getPrixUnitaire(), nz(ve.getMontant())));
            }
        }

        if (lignesAGenerer.isEmpty()) {
            throw new IllegalArgumentException("Aucune vente à facturer.");
        }
        if (data.getClientUniqueId() != null && !data.getClientUniqueId().isBlank()
                && !data.getClientUniqueId().equals(client.getUniqueId())) {
            throw new IllegalArgumentException("Les ventes ne correspondent pas au client indiqué.");
        }

        double montantTotal = CalculImputation.arrondi(lignesAGenerer.stream().mapToDouble(LigneAGenerer::montant).sum());

        Facture f = new Facture();
        f.setUniqueId(java.util.UUID.randomUUID().toString());
        f.setNumeroFacture(genererNumero(farmId));
        f.setClient(client);
        f.setFarm(currentUser.getFarm());
        f.setDateEmission(LocalDate.now());
        f.setSourceType(sourceType);
        // VENTES n'a pas d'identifiant de source unique (plusieurs ventes) : on utilise
        // l'identifiant de la facture elle-même — sourceUniqueId est NOT NULL en base.
        f.setSourceUniqueId(sourceType == SourceFacture.COMMANDE ? sourceUniqueIdCommande : f.getUniqueId());
        f.setDescription(lignesAGenerer.size() == 1 ? lignesAGenerer.get(0).description()
                : lignesAGenerer.size() + " vente(s)");
        f.setQuantite(lignesAGenerer.size() == 1 ? lignesAGenerer.get(0).quantite() : null);
        f.setPrixUnitaire(lignesAGenerer.size() == 1 ? lignesAGenerer.get(0).prixUnitaire() : null);
        f.setMontantTotal(montantTotal);
        f.setMontantPaye(0.0);
        f.setStatut(StatutFacture.IMPAYEE);
        f.setLegacy(false);
        f.setCreePar(currentUser);
        f.setInitialisation(Initialisation.init());

        Facture saved = factureRepo.save(f);

        for (LigneAGenerer l : lignesAGenerer) {
            FactureLigne ligne = new FactureLigne();
            ligne.setUniqueId(java.util.UUID.randomUUID().toString());
            ligne.setFacture(saved);
            ligne.setVenteType(l.type());
            ligne.setVenteUniqueId(l.venteUniqueId());
            ligne.setDescription(l.description());
            ligne.setQuantite(l.quantite());
            ligne.setPrixUnitaire(l.prixUnitaire());
            ligne.setMontant(CalculImputation.arrondi(l.montant()));
            ligne.setInitialisation(Initialisation.init());
            factureLigneRepo.save(ligne);
        }

        logs.addLogs(currentUser.getId(), saved.getId(), "Facture",
                "Facture " + saved.getNumeroFacture() + " générée pour " + client.getNom()
                        + " (" + lignesAGenerer.size() + " vente(s))");
        return toDto(saved);
    }

    // ===================== Paiement =====================

    @Override
    @Transactional
    public FactureDTO payer(String uniqueId, Double montant, String mode, LocalDate date) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Facture f = factureFarmScoped(uniqueId, currentUser);

        if (f.getStatut() == StatutFacture.ANNULEE) {
            throw new IllegalArgumentException("Cette facture est annulée.");
        }

        // Verrou client AVANT de lire ce qui est déjà payé : deux paiements simultanés de la
        // même facture (ou un remboursement) ne peuvent pas lire le même reste.
        compteClientService.verrouiller(f.getClient());

        List<FactureLigne> lignes = factureLigneRepo.findByFacture_Id(f.getId());
        lignes.sort(Comparator.comparing(FactureLigne::getId));
        for (FactureLigne l : lignes) {
            if (!venteActive(l)) {
                throw new IllegalArgumentException("Une vente de cette facture a été supprimée : annulez la facture.");
            }
        }
        double montantPaye = calculerMontantPaye(f, lignes);
        double reste = CalculImputation.arrondi(nz(f.getMontantTotal()) - montantPaye);
        if (reste <= 0) {
            throw new IllegalArgumentException("Cette facture est déjà entièrement payée.");
        }
        double montantAPayer = CalculImputation.arrondi((montant != null && montant > 0) ? Math.min(montant, reste) : reste);

        ModePaiement modePaiement;
        try {
            modePaiement = (mode == null || mode.isBlank()) ? ModePaiement.ESPECES : ModePaiement.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mode de paiement inconnu : " + mode);
        }
        LocalDate datePaiement = date != null ? date : LocalDate.now();
        String observations = "Paiement facture " + f.getNumeroFacture();

        if (Boolean.TRUE.equals(f.getLegacy())) {
            // Facture d'avant la refonte : son « payé » est le montant payé historique +
            // les paiements reçus sur elle depuis (voir calculerMontantPaye), pas les
            // imputations de sa ligne. Un seul paiement, qui vise la vente de sa ligne
            // (créée par la reprise) ; plafonné au reste de la facture ci-dessus.
            FactureLigne ligne = lignes.isEmpty() ? null : lignes.get(0);
            paiementClientService.enregistrerInterne(f.getClient(), montantAPayer, modePaiement, OriginePaiement.FACTURE,
                    null, ligne != null ? ligne.getVenteType() : null, ligne != null ? ligne.getVenteUniqueId() : null,
                    f, observations, datePaiement);
        } else {
            // Un paiement client PAR LIGNE non soldée, dans l'ordre des lignes, chacun visant
            // la vente de sa ligne pour au plus ce qui reste dû sur cette ligne, jusqu'à
            // épuisement du montant. Un seul paiement visant la première ligne laissait
            // l'imputation automatique (plus anciennes ventes d'abord) envoyer le reste sur
            // d'autres ventes impayées du client, hors facture : la facture n'était jamais
            // soldée. Somme des restes des lignes = reste de la facture, donc tout est
            // réparti.
            double aRepartir = montantAPayer;
            for (FactureLigne l : lignes) {
                if (aRepartir <= 0) break;
                double resteLigne = CalculImputation.arrondi(nz(l.getMontant())
                        - Math.min(nz(l.getMontant()), compteClientService.payeVente(l.getVenteType(), l.getVenteUniqueId())));
                if (resteLigne <= 0) continue;
                double part = CalculImputation.arrondi(Math.min(aRepartir, resteLigne));
                paiementClientService.enregistrerInterne(f.getClient(), part, modePaiement, OriginePaiement.FACTURE,
                        null, l.getVenteType(), l.getVenteUniqueId(), f, observations, datePaiement);
                aRepartir = CalculImputation.arrondi(aRepartir - part);
            }
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), f.getId(), "Facture",
                    "Paiement de " + montantAPayer + " FCFA enregistré sur la facture " + f.getNumeroFacture());
        }

        return toDto(f);
    }

    @Override
    @Transactional
    public FactureDTO marquerPayee(String uniqueId, Double montant) {
        return payer(uniqueId, montant, "ESPECES", null);
    }

    // ===================== Annulation =====================

    @Override
    @Transactional
    public FactureDTO annuler(String uniqueId, String motifBrut) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanAnnuler(currentUser);
        String motif = MotifSuppressionRequest.exiger(motifBrut);
        Facture f = factureFarmScoped(uniqueId, currentUser);
        if (f.getStatut() == StatutFacture.ANNULEE) {
            throw new IllegalArgumentException("Cette facture est déjà annulée.");
        }

        f.setStatut(StatutFacture.ANNULEE);
        f.setMotifAnnulation(motif);
        Facture saved = factureRepo.save(f);
        // Les ventes redeviennent facturables : FactureLigneRepo.venteDejaFacturee
        // exclut les factures ANNULEE, rien d'autre à faire. Les paiements déjà
        // encaissés (PaiementClient) ne bougent pas.

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Facture",
                    "Facture " + saved.getNumeroFacture() + " annulée — motif : " + motif);
        }
        return toDto(saved);
    }

    // ===================== DTO / montant payé =====================

    // Facture non-legacy : Σ min(ligne.montant, payeVente(ligne)) — plafonné ligne par
    // ligne. Facture legacy (d'avant la refonte) : montantPaye historique (colonne, qui
    // comptait déjà l'argent reçu à la vente et les anciens « marquer payée ») + paiements
    // reçus sur cette facture depuis la reprise (payer), plafonné au total — sinon elle
    // restait payable indéfiniment sans jamais changer de statut.
    private double calculerMontantPaye(Facture f, List<FactureLigne> lignes) {
        if (Boolean.TRUE.equals(f.getLegacy())) {
            double depuisReprise = nz(paiementClientRepo.sumActifsHorsRepriseByFactureId(f.getId()));
            return CalculImputation.arrondi(Math.min(nz(f.getMontantTotal()), nz(f.getMontantPaye()) + depuisReprise));
        }
        return CalculImputation.arrondi(lignes.stream()
                .mapToDouble(l -> Math.min(nz(l.getMontant()), compteClientService.payeVente(l.getVenteType(), l.getVenteUniqueId())))
                .sum());
    }

    private FactureDTO toDto(Facture f) {
        List<FactureLigne> lignes = factureLigneRepo.findByFacture_Id(f.getId());
        lignes.sort(Comparator.comparing(FactureLigne::getId));
        List<FactureLigneDTO> lignesDto = lignes.stream().map(FactureLigneDTO::fromEntity).toList();
        double payeCalcule = calculerMontantPaye(f, lignes);
        return FactureDTO.fromEntity(f, lignesDto, payeCalcule);
    }

    // ===================== PDF =====================

    // Logo/tampon sont optionnels (voir Farm.logoNomMinio/tamponNomMinio) — laissés
    // vides si la ferme n'en a pas encore fourni, jamais d'espace réservé/placeholder.
    // Échec de chargement (MinIO indisponible...) traité comme absent plutôt que de
    // faire échouer toute la génération du PDF.
    private Image chargerImage(String nomMinio) {
        if (nomMinio == null) return null;
        try (java.io.InputStream stream = minioService.downloadFile(nomMinio)) {
            return Image.getInstance(stream.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    private String statutLabelFr(String statut) {
        if (statut == null) return "-";
        return switch (statut) {
            case "IMPAYEE" -> "IMPAYÉE";
            case "PARTIELLE" -> "PARTIELLE";
            case "PAYEE" -> "PAYÉE";
            case "ANNULEE" -> "ANNULÉE";
            default -> statut;
        };
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] genererPdf(String uniqueId) {
        Facture f = factureFarmScoped(uniqueId, getCurrentUserSafe());
        Farm farm = f.getFarm();
        FactureDTO dto = toDto(f);
        // Une facture sans ligne (legacy, ou ancienne facture pas encore reprise —
        // Task 11) : on reconstruit une ligne unique depuis les champs historiques de
        // Facture, seule trace disponible pour cette facture — voir FactureLigne.java.
        boolean reconstruire = dto.getLignes() == null || dto.getLignes().isEmpty();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 45, 45, 40, 40);
            PdfWriter.getInstance(document, out);
            document.open();

            // ===== En-tête : logo + identité de la ferme à gauche, titre + n°/date à
            // droite — voir Farm.nom/quartier/ville/pays/telephone1/telephone2/email,
            // configurables depuis Paramètres > Identité de la ferme côté web.
            PdfPTable header = new PdfPTable(2);
            header.setWidthPercentage(100);
            header.setWidths(new float[]{1, 1});

            java.util.List<Element> farmCellElements = new java.util.ArrayList<>();
            Image logo = farm != null ? chargerImage(farm.getLogoNomMinio()) : null;
            if (logo != null) {
                logo.scaleToFit(140, 70);
                farmCellElements.add(logo);
            }
            if (farm != null) {
                farmCellElements.addAll(PdfStyle.farmBlockLines(farm.getNom(), farm.getQuartier(), farm.getVille(), farm.getPays(), farm.getTelephone1(), farm.getTelephone2(), farm.getEmail()));
            }
            if (farmCellElements.isEmpty()) farmCellElements.add(new Paragraph(" ", PdfStyle.normal()));
            header.addCell(PdfStyle.layoutCell(farmCellElements.toArray(new Element[0])));

            Paragraph title = new Paragraph("FACTURE", PdfStyle.title());
            title.setAlignment(Element.ALIGN_RIGHT);
            Paragraph numero = new Paragraph("N° " + f.getNumeroFacture(), PdfStyle.bold());
            numero.setAlignment(Element.ALIGN_RIGHT);
            Paragraph date = new Paragraph("Émise le " + f.getDateEmission().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), PdfStyle.small());
            date.setAlignment(Element.ALIGN_RIGHT);
            header.addCell(PdfStyle.layoutCell(title, numero, date));
            document.add(header);

            document.add(new Paragraph(" "));
            document.add(PdfStyle.colorBand(3f));
            document.add(new Paragraph(" "));

            // Statut, aligné à droite
            PdfPTable statutTable = new PdfPTable(1);
            statutTable.setWidthPercentage(28);
            statutTable.setHorizontalAlignment(Element.ALIGN_RIGHT);
            statutTable.addCell(PdfStyle.badgeCell(statutLabelFr(dto.getStatut()), PdfStyle.statutFactureColor(dto.getStatut())));
            document.add(statutTable);
            document.add(new Paragraph(" "));

            // ===== Facturé à =====
            document.add(new Paragraph("FACTURÉ À", PdfStyle.sectionLabel()));
            document.add(new Paragraph(" "));
            java.util.List<Paragraph> clientLignes = new java.util.ArrayList<>();
            clientLignes.add(new Paragraph(f.getClient().getNom(), PdfStyle.bold()));
            if (f.getClient().getTelephone() != null) clientLignes.add(new Paragraph(f.getClient().getTelephone(), PdfStyle.normal()));
            if (f.getClient().getAdresse() != null) clientLignes.add(new Paragraph(f.getClient().getAdresse(), PdfStyle.normal()));
            PdfPTable clientBox = new PdfPTable(1);
            clientBox.setWidthPercentage(100);
            clientBox.addCell(PdfStyle.infoBox(clientLignes));
            document.add(clientBox);
            document.add(new Paragraph(" "));

            // ===== Lignes de facturation =====
            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{3.5f, 1.5f, 2, 2});
            table.addCell(PdfStyle.tableHeaderCell("Description"));
            table.addCell(PdfStyle.tableHeaderCell("Quantité"));
            table.addCell(PdfStyle.tableHeaderCell("Prix unitaire"));
            table.addCell(PdfStyle.tableHeaderCell("Montant"));
            if (reconstruire) {
                table.addCell(PdfStyle.bodyCell(f.getDescription()));
                table.addCell(PdfStyle.bodyCell(f.getQuantite() != null ? f.getQuantite().toString() : "-", Element.ALIGN_RIGHT));
                table.addCell(PdfStyle.bodyCell(f.getPrixUnitaire() != null ? String.format("%.0f", f.getPrixUnitaire()) : "-", Element.ALIGN_RIGHT));
                table.addCell(PdfStyle.bodyCell(String.format("%,.0f FCFA", nz(f.getMontantTotal())), Element.ALIGN_RIGHT));
            } else {
                for (FactureLigneDTO l : dto.getLignes()) {
                    table.addCell(PdfStyle.bodyCell(l.getDescription()));
                    table.addCell(PdfStyle.bodyCell(l.getQuantite() != null ? l.getQuantite().toString() : "-", Element.ALIGN_RIGHT));
                    table.addCell(PdfStyle.bodyCell(l.getPrixUnitaire() != null ? String.format("%.0f", l.getPrixUnitaire()) : "-", Element.ALIGN_RIGHT));
                    table.addCell(PdfStyle.bodyCell(String.format("%,.0f FCFA", nz(l.getMontant())), Element.ALIGN_RIGHT));
                }
            }
            document.add(table);
            document.add(new Paragraph(" "));

            // ===== Récapitulatif (aligné à droite) =====
            double reste = dto.getResteAPayer() != null ? dto.getResteAPayer() : 0.0;
            PdfPTable recap = new PdfPTable(2);
            recap.setWidthPercentage(55);
            recap.setHorizontalAlignment(Element.ALIGN_RIGHT);
            recap.setWidths(new float[]{1, 1});
            recap.addCell(PdfStyle.layoutCell(new Paragraph("Montant total", PdfStyle.normal())));
            recap.addCell(PdfStyle.layoutCell(alignRight(new Paragraph(String.format("%,.0f FCFA", nz(f.getMontantTotal())), PdfStyle.normal()))));
            recap.addCell(PdfStyle.layoutCell(new Paragraph("Montant payé", PdfStyle.normal())));
            recap.addCell(PdfStyle.layoutCell(alignRight(new Paragraph(String.format("%,.0f FCFA", nz(dto.getMontantPaye())), PdfStyle.normal()))));
            document.add(recap);
            document.add(new Paragraph(" "));

            document.add(PdfStyle.highlightAmount(
                    reste > 0 ? "RESTE DÛ" : "FACTURE SOLDÉE",
                    String.format("%,.0f FCFA", reste)));
            document.add(new Paragraph(" "));
            document.add(new Paragraph(" "));

            Image tampon = farm != null ? chargerImage(farm.getTamponNomMinio()) : null;
            if (tampon != null) {
                tampon.scaleToFit(90, 90);
                tampon.setAlignment(Element.ALIGN_RIGHT);
                document.add(tampon);
                document.add(new Paragraph(" "));
            }

            Paragraph footer = new Paragraph("Cocorico — document généré le " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), PdfStyle.small());
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la génération du PDF : " + e.getMessage(), e);
        }
    }

    private Paragraph alignRight(Paragraph p) {
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    // ===================== Liste =====================

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<FactureDTO> list(int page, int size, String statut, String clientUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }
        Long farmId = currentUser.getFarm().getId();

        String statutNorm = (statut == null || statut.isBlank() || "tous".equalsIgnoreCase(statut))
                ? null : statut.trim().toUpperCase();
        // Valide le statut demandé (lève IllegalArgumentException -> 400 si inconnu),
        // même comportement qu'avant.
        StatutFacture statutEnum = statutNorm == null ? null : StatutFacture.valueOf(statutNorm);
        boolean hasClient = clientUniqueId != null && !clientUniqueId.isBlank();
        String clientParam = hasClient ? clientUniqueId : "";

        // PAYEE/PARTIELLE/IMPAYEE ne sont plus des colonnes fiables pour les factures
        // non-legacy : FactureDTO.fromEntity les recalcule depuis les imputations des
        // lignes, la colonne Facture.statut en base n'est plus mise à jour après la
        // génération (voir toDto). On ne peut donc pas les filtrer en base : on charge
        // toutes les factures correspondant aux AUTRES filtres, on calcule leur DTO, on
        // filtre par dto.getStatut() en mémoire, puis on pagine nous-mêmes. ANNULEE
        // reste un vrai statut stocké (annuler() l'écrit) : filtré directement en base.
        if (statutEnum != null && statutEnum != StatutFacture.ANNULEE) {
            List<Facture> toutes = factureRepo.searchToutes(farmId, false, StatutFacture.IMPAYEE, hasClient, clientParam,
                    Sort.by(Sort.Direction.DESC, "dateEmission"));
            List<FactureDTO> filtres = toutes.stream().map(this::toDto)
                    .filter(dto -> statutNorm.equals(dto.getStatut()))
                    .toList();
            int totalItems = filtres.size();
            int totalPages = size > 0 ? (int) Math.ceil(totalItems / (double) size) : 0;
            int from = Math.min(page * size, totalItems);
            int to = Math.min(from + size, totalItems);
            List<FactureDTO> pageContent = from < to ? filtres.subList(from, to) : List.of();
            return new PaginatedResponse<>(pageContent, page + 1, totalPages, totalItems, size);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dateEmission"));
        boolean hasStatut = statutEnum != null;
        StatutFacture statutParam = hasStatut ? statutEnum : StatutFacture.IMPAYEE;
        Page<Facture> facturePage = factureRepo.search(farmId, hasStatut, statutParam, hasClient, clientParam, pageable);
        List<FactureDTO> dtoList = facturePage.getContent().stream().map(this::toDto).toList();

        return new PaginatedResponse<>(
                dtoList,
                facturePage.getNumber() + 1,
                facturePage.getTotalPages(),
                facturePage.getTotalElements(),
                facturePage.getSize()
        );
    }
}
