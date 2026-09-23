package com.diafarms.ml.services;

import com.diafarms.ml.DTO.StockOeufsDTO;
import com.diafarms.ml.DTO.VenteOeufsDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.update.VenteOeufsUpdate;

public interface VenteOeufsService {

    VenteOeufsDTO create(VenteOeufsCreate data);

    VenteOeufsDTO update(String uniqueId, VenteOeufsUpdate data);

    // Réservé ADMIN/RESPONSABLE (toggle direct, delete ou restore) — voir
    // VenteOeufsImpl.deleteOrRecover.
    String deleteOrRecover(String uniqueId, String motif);

    /** Marque une demande de suppression — ADMIN/RESPONSABLE/COMPTABLE, jamais le
     * vendeur (VENTE), même pour sa propre vente : il ne doit pas pouvoir effacer la
     * preuve d'un manquant sur l'argent qu'il devait rapporter. */
    VenteOeufsDTO demanderSuppression(String uniqueId, String motif);

    /** Confirme une demande en attente — supprime réellement. ADMIN/RESPONSABLE seulement. */
    VenteOeufsDTO confirmerSuppression(String uniqueId);

    /** Refuse une demande en attente (la vente reste active). ADMIN/RESPONSABLE. */
    VenteOeufsDTO annulerDemandeSuppression(String uniqueId);

    PaginatedResponse<VenteOeufsDTO> list(int page, int size);

    StockOeufsDTO getStock();
}
