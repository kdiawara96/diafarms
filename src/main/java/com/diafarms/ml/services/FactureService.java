package com.diafarms.ml.services;

import java.time.LocalDate;

import com.diafarms.ml.DTO.FactureDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.FactureGenerateRequest;

public interface FactureService {
    // Crée la facture depuis une liste de ventes (VENTES), une commande (COMMANDE) ou,
    // par compatibilité, une seule vente (VENTE_OEUFS/VENTE_REFORME) — voir
    // FactureServiceImpl pour la résolution exacte de la source.
    FactureDTO genererDepuis(FactureGenerateRequest data);

    // Enregistre un vrai paiement sur cette facture précise (PaiementClientService,
    // Transaction + imputations) et renvoie la facture avec montantPaye/statut
    // recalculés. montant/mode/date optionnels (voir FacturePaiementRequest).
    FactureDTO payer(String uniqueId, Double montant, String mode, LocalDate date);

    // Conservé pour compatibilité : adaptateur vers payer(uid, montant, "ESPECES", null).
    FactureDTO marquerPayee(String uniqueId, Double montant);

    // Motif obligatoire — voir MotifSuppressionRequest. Les ventes de la facture
    // redeviennent facturables, les paiements déjà faits ne bougent pas.
    FactureDTO annuler(String uniqueId, String motif);

    byte[] genererPdf(String uniqueId);
    PaginatedResponse<FactureDTO> list(int page, int size, String statut, String clientUniqueId);
}
