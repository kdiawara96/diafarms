package com.diafarms.ml.services;

import com.diafarms.ml.DTO.StockReformeDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.update.VenteReformeUpdate;

public interface VenteReformeService {

    VenteReformeDTO create(VenteReformeCreate data);

    VenteReformeDTO update(String uniqueId, VenteReformeUpdate data);

    // Réservé ADMIN/RESPONSABLE (toggle direct, delete ou restore) — voir
    // VenteReformeImpl.deleteOrRecover.
    String deleteOrRecover(String uniqueId);

    /** Marque une demande de suppression — ADMIN/RESPONSABLE/COMPTABLE, jamais le
     * vendeur (VENTE), même pour sa propre vente — voir VenteOeufsService (même règle). */
    VenteReformeDTO demanderSuppression(String uniqueId);

    /** Confirme une demande en attente — supprime réellement. ADMIN/RESPONSABLE seulement. */
    VenteReformeDTO confirmerSuppression(String uniqueId);

    /** Refuse une demande en attente (la vente reste active). ADMIN/RESPONSABLE. */
    VenteReformeDTO annulerDemandeSuppression(String uniqueId);

    PaginatedResponse<VenteReformeDTO> list(int page, int size);

    StockReformeDTO getStock();
}
