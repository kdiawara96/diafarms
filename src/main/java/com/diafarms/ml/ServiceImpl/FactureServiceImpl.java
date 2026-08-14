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

import com.diafarms.ml.DTO.FactureDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Commande;
import com.diafarms.ml.models.Commande.StatutCommande;
import com.diafarms.ml.models.Facture;
import com.diafarms.ml.models.Facture.SourceFacture;
import com.diafarms.ml.models.Facture.StatutFacture;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.CommandeRepo;
import com.diafarms.ml.repository.FactureRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.FactureGenerateRequest;
import com.diafarms.ml.services.ClientService;
import com.diafarms.ml.services.FactureService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.MinioService;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

// Facture = document figé généré depuis une vente ou une commande — voir Facture.java.
// Permissions alignées sur la note du roadmap ("la facturation reste une action web
// ADMIN/COMPTABLE") : génération/gestion réservées à ADMIN/RESPONSABLE/COMPTABLE, pas
// VENTE (contrairement à Commande, où un vendeur agit sur le terrain).
@Service
@RequiredArgsConstructor
public class FactureServiceImpl implements FactureService {

    private final FactureRepo factureRepo;
    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteReformeRepo venteReformeRepo;
    private final CommandeRepo commandeRepo;
    private final ClientService clientService;
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

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private StatutFacture computeStatut(double montantTotal, double montantPaye) {
        if (montantPaye >= montantTotal) return StatutFacture.PAYEE;
        if (montantPaye > 0) return StatutFacture.PARTIELLE;
        return StatutFacture.IMPAYEE;
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

    @Override
    @Transactional
    public FactureDTO genererDepuis(FactureGenerateRequest data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        if (data.getSourceType() == null || data.getSourceUniqueId() == null || data.getSourceUniqueId().isBlank()) {
            throw new IllegalArgumentException("La source de la facture (vente ou commande) est requise.");
        }

        SourceFacture sourceType;
        try {
            sourceType = SourceFacture.valueOf(data.getSourceType().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Type de source invalide (attendu VENTE_OEUFS, VENTE_REFORME ou COMMANDE) : " + data.getSourceType());
        }
        if (factureRepo.existsBySourceTypeAndSourceUniqueId(sourceType, data.getSourceUniqueId())) {
            throw new IllegalArgumentException("Une facture existe déjà pour cette vente/commande.");
        }

        Client client;
        String description;
        Integer quantite;
        Double prixUnitaire;
        double montantTotal;
        double montantPaye;

        switch (sourceType) {
            case VENTE_OEUFS -> {
                VenteOeufs v = venteOeufsRepo.findByUniqueId(data.getSourceUniqueId())
                        .orElseThrow(() -> new IllegalArgumentException("Vente introuvable : " + data.getSourceUniqueId()));
                if (v.getClient() == null) {
                    throw new IllegalArgumentException("Cette vente n'a pas de client identifié — impossible de générer une facture.");
                }
                client = v.getClient();
                description = "Vente d'œufs — " + v.getQuantiteOeufs() + " unité(s)";
                quantite = v.getQuantiteOeufs();
                prixUnitaire = v.getPrixUnitaire();
                montantTotal = nz(v.getMontant());
                montantPaye = nz(v.getMontantRapporte());
            }
            case VENTE_REFORME -> {
                VenteReforme v = venteReformeRepo.findByUniqueId(data.getSourceUniqueId())
                        .orElseThrow(() -> new IllegalArgumentException("Vente introuvable : " + data.getSourceUniqueId()));
                if (v.getClient() == null) {
                    throw new IllegalArgumentException("Cette vente n'a pas de client identifié — impossible de générer une facture.");
                }
                client = v.getClient();
                description = "Vente de réforme — " + v.getNombreSujets() + " sujet(s)";
                quantite = v.getNombreSujets();
                prixUnitaire = v.getPrixUnitaire();
                montantTotal = nz(v.getMontant());
                montantPaye = nz(v.getMontantRapporte());
            }
            case COMMANDE -> {
                Commande c = commandeRepo.findByUniqueId(data.getSourceUniqueId());
                if (c == null) {
                    throw new IllegalArgumentException("Commande introuvable : " + data.getSourceUniqueId());
                }
                if (c.getStatut() == StatutCommande.ANNULEE) {
                    throw new IllegalArgumentException("Une commande annulée ne peut pas être facturée.");
                }
                client = c.getClient();
                description = "Commande — " + c.getQuantite() + " unité(s)";
                quantite = c.getQuantite();
                prixUnitaire = c.getPrixUnitaireEstime();
                montantTotal = nz(c.getMontantEstime());
                montantPaye = nz(c.getMontantAcompte());
            }
            default -> throw new IllegalArgumentException("Type de source invalide.");
        }

        Facture f = new Facture();
        f.setUniqueId(java.util.UUID.randomUUID().toString());
        f.setNumeroFacture(genererNumero(currentUser.getFarm().getId()));
        f.setClient(client);
        f.setFarm(currentUser.getFarm());
        f.setDateEmission(LocalDate.now());
        f.setSourceType(sourceType);
        f.setSourceUniqueId(data.getSourceUniqueId());
        f.setDescription(description);
        f.setQuantite(quantite);
        f.setPrixUnitaire(prixUnitaire);
        f.setMontantTotal(montantTotal);
        f.setMontantPaye(montantPaye);
        f.setStatut(computeStatut(montantTotal, montantPaye));
        f.setCreePar(currentUser);
        f.setInitialisation(Initialisation.init());

        Facture saved = factureRepo.save(f);
        logs.addLogs(currentUser.getId(), saved.getId(), "Facture",
                "Facture " + saved.getNumeroFacture() + " générée pour " + client.getNom());
        return FactureDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public FactureDTO marquerPayee(String uniqueId, Double montant) {
        Utilisateurs currentUser = getCurrentUserSafe();
        ensureCanManage(currentUser);
        Facture f = factureRepo.findByUniqueId(uniqueId);
        if (f == null) throw new IllegalArgumentException("Facture introuvable : " + uniqueId);

        double reste = f.getMontantTotal() - f.getMontantPaye();
        if (reste <= 0) {
            throw new IllegalArgumentException("Cette facture est déjà entièrement payée.");
        }
        double montantAPayer = (montant != null && montant > 0) ? Math.min(montant, reste) : reste;

        // Réutilise ClientService.payerDette : crée une vraie Transaction ("Paiement
        // client") et réduit SoldeClient d'autant — même mécanisme que le formulaire de
        // paiement de ClientDetailDialog, pas une simple case cochée côté Facture.
        clientService.payerDette(f.getClient().getUniqueId(), montantAPayer,
                "Paiement facture " + f.getNumeroFacture());

        f.setMontantPaye(f.getMontantPaye() + montantAPayer);
        f.setStatut(computeStatut(f.getMontantTotal(), f.getMontantPaye()));
        Facture saved = factureRepo.save(f);
        return FactureDTO.fromEntity(saved);
    }

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

    @Override
    @Transactional(readOnly = true)
    public byte[] genererPdf(String uniqueId) {
        Facture f = factureRepo.findByUniqueId(uniqueId);
        if (f == null) throw new IllegalArgumentException("Facture introuvable : " + uniqueId);
        Farm farm = f.getFarm();

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

            Paragraph title = new Paragraph("FACTURE", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);
            document.add(Chunk.NEWLINE);

            document.add(new Paragraph("N° facture : " + f.getNumeroFacture(), boldFont));
            document.add(new Paragraph("Date d'émission : " + f.getDateEmission().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), normalFont));
            document.add(Chunk.NEWLINE);

            document.add(new Paragraph("Client : " + f.getClient().getNom(), boldFont));
            if (f.getClient().getTelephone() != null) {
                document.add(new Paragraph("Téléphone : " + f.getClient().getTelephone(), normalFont));
            }
            if (f.getClient().getAdresse() != null) {
                document.add(new Paragraph("Adresse : " + f.getClient().getAdresse(), normalFont));
            }
            document.add(Chunk.NEWLINE);

            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{4, 1, 2, 2});
            for (String header : new String[]{"Description", "Quantité", "Prix unitaire", "Montant"}) {
                PdfPCell cell = new PdfPCell(new Paragraph(header, boldFont));
                cell.setBackgroundColor(new java.awt.Color(230, 230, 230));
                table.addCell(cell);
            }
            table.addCell(new Paragraph(f.getDescription(), normalFont));
            table.addCell(new Paragraph(f.getQuantite() != null ? f.getQuantite().toString() : "-", normalFont));
            table.addCell(new Paragraph(f.getPrixUnitaire() != null ? String.format("%.0f", f.getPrixUnitaire()) : "-", normalFont));
            table.addCell(new Paragraph(String.format("%.0f FCFA", f.getMontantTotal()), normalFont));
            document.add(table);
            document.add(Chunk.NEWLINE);

            double reste = f.getMontantTotal() - f.getMontantPaye();
            document.add(new Paragraph("Montant total : " + String.format("%.0f FCFA", f.getMontantTotal()), normalFont));
            document.add(new Paragraph("Montant payé : " + String.format("%.0f FCFA", f.getMontantPaye()), normalFont));
            document.add(new Paragraph("Reste dû : " + String.format("%.0f FCFA", reste), boldFont));
            document.add(new Paragraph("Statut : " + f.getStatut().name(), boldFont));

            Image tampon = farm != null ? chargerImage(farm.getTamponNomMinio()) : null;
            if (tampon != null) {
                tampon.scaleToFit(100, 100);
                tampon.setAlignment(Element.ALIGN_RIGHT);
                document.add(Chunk.NEWLINE);
                document.add(tampon);
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la génération du PDF : " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<FactureDTO> list(int page, int size, String statut, String clientUniqueId) {
        Utilisateurs currentUser = getCurrentUserSafe();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dateEmission"));

        if (currentUser == null || currentUser.getFarm() == null) {
            return new PaginatedResponse<>(List.of(), 1, 0, 0, size);
        }

        StatutFacture statutEnum = (statut == null || statut.isBlank() || "tous".equalsIgnoreCase(statut))
                ? null : StatutFacture.valueOf(statut.toUpperCase());
        String clientParam = (clientUniqueId == null || clientUniqueId.isBlank()) ? null : clientUniqueId;

        Page<Facture> facturePage = factureRepo.search(currentUser.getFarm().getId(), statutEnum, clientParam, pageable);
        List<FactureDTO> dtoList = facturePage.getContent().stream().map(FactureDTO::fromEntity).toList();

        return new PaginatedResponse<>(
                dtoList,
                facturePage.getNumber() + 1,
                facturePage.getTotalPages(),
                facturePage.getTotalElements(),
                facturePage.getSize()
        );
    }
}
