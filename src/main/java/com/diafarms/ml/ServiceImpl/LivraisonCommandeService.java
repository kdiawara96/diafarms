package com.diafarms.ml.ServiceImpl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.models.Commande;
import com.diafarms.ml.models.Commande.StatutCommande;
import com.diafarms.ml.repository.CommandeRepo;

import lombok.RequiredArgsConstructor;

// Une livraison de commande = une vente qui porte `commande` (voir CommandeServiceImpl.livrer).
// Supprimer cette vente (ou la restaurer) doit aussi retirer (ou remettre) sa quantité
// dans commande.quantiteLivree et recalculer le statut ; sinon la commande restait
// « livrée » pour une livraison qui n'existe plus (plus rien à livrer, plus d'annulation
// possible). Partagé par VenteOeufsImpl et VenteReformeImpl.
@Service
@RequiredArgsConstructor
public class LivraisonCommandeService {

    private final CommandeRepo commandeRepo;
    private final CompteClientService compteClientService;

    private static int nz(Integer v) { return v == null ? 0 : v; }

    /** Livraison supprimée : quantité retirée de la commande. */
    @Transactional
    public void livraisonSupprimee(Commande c, Integer quantite) {
        if (c == null) return;
        boolean etaitReservee = CompteClientService.estReservee(c);
        c.setQuantiteLivree(Math.max(0, nz(c.getQuantiteLivree()) - nz(quantite)));
        recalculerStatut(c);
        commandeRepo.save(c);
        // Commande qui se rouvre (CONVERTIE -> EN_LIVRAISON/CONFIRMEE) : son argent
        // redevient réservé, y compris ce qui avait déjà réglé d'autres ventes.
        if (!etaitReservee && CompteClientService.estReservee(c)) compteClientService.reReserver(c);
    }

    /** Vérifie AVANT de restaurer la vente qu'on peut remettre sa quantité sur la commande. */
    public void verifierRestauration(Commande c, Integer quantite) {
        if (c == null) return;
        if (c.getStatut() == StatutCommande.ANNULEE) {
            throw new IllegalArgumentException("La commande de cette livraison a été annulée entre-temps : restauration impossible.");
        }
        if (nz(c.getQuantiteLivree()) + nz(quantite) > nz(c.getQuantite())) {
            throw new IllegalArgumentException("La commande a été livrée entre-temps : restaurer cette livraison dépasserait la quantité commandée.");
        }
    }

    /** Livraison restaurée : quantité remise sur la commande (après verifierRestauration). */
    @Transactional
    public void livraisonRestauree(Commande c, Integer quantite) {
        if (c == null) return;
        c.setQuantiteLivree(nz(c.getQuantiteLivree()) + nz(quantite));
        recalculerStatut(c);
        commandeRepo.save(c);
    }

    // CLOTUREE / ANNULEE sont des décisions (motif) : on n'y touche pas. Sinon le statut
    // suit ce qui est réellement livré : tout -> CONVERTIE (« Livrée »), une partie ->
    // EN_LIVRAISON, plus rien -> CONFIRMEE (une commande livrée avait forcément été
    // prise en charge ; on ne la renvoie pas « en attente »).
    private static void recalculerStatut(Commande c) {
        if (c.getStatut() == StatutCommande.CLOTUREE || c.getStatut() == StatutCommande.ANNULEE) return;
        int livree = nz(c.getQuantiteLivree());
        if (livree <= 0) c.setStatut(StatutCommande.CONFIRMEE);
        else if (livree >= nz(c.getQuantite())) c.setStatut(StatutCommande.CONVERTIE);
        else c.setStatut(StatutCommande.EN_LIVRAISON);
    }
}
