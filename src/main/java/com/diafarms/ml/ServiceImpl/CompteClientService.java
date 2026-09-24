package com.diafarms.ml.ServiceImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.CompteClientDTO;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.StatutMouvement;
import com.diafarms.ml.models.*;
import com.diafarms.ml.repository.*;

import lombok.RequiredArgsConstructor;

// Source unique du compte d'un client : rien n'est stocké, tout se recalcule à partir
// des ventes, paiements, imputations et remboursements.
@Service
@RequiredArgsConstructor
public class CompteClientService {

    private final ClientRepo clientRepo;
    private final PaiementClientRepo paiementRepo;
    private final ImputationPaiementRepo imputationRepo;
    private final VenteOeufsRepo venteOeufsRepo;
    private final VenteReformeRepo venteReformeRepo;

    private static double nz(Double v) { return v == null ? 0.0 : v; }

    @Transactional(readOnly = true)
    public CompteClientDTO compte(Client client) {
        Long id = client.getId();
        double vendu = nz(venteOeufsRepo.sumMontantActifsByClientId(id)) + nz(venteReformeRepo.sumMontantActifsByClientId(id));
        double paye = nz(paiementRepo.sumActifsByClientId(id));
        double imputeVentes = nz(imputationRepo.sumActivesSurVentesByClientId(id));
        double imputeTout = nz(imputationRepo.sumActivesByClientId(id));
        double rembourse = CalculImputation.arrondi(imputeTout - imputeVentes);
        double reste = CalculImputation.arrondi(vendu - imputeVentes);
        double avance = CalculImputation.arrondi(paye - imputeTout);
        return CompteClientDTO.builder()
                .clientUniqueId(client.getUniqueId()).clientNom(client.getNom())
                .totalVendu(CalculImputation.arrondi(vendu)).totalPaye(CalculImputation.arrondi(paye))
                .totalRembourse(rembourse).totalImputeVentes(CalculImputation.arrondi(imputeVentes))
                .resteAPayer(reste).avance(avance).solde(CalculImputation.arrondi(reste - avance))
                .build();
    }

    public double payeVente(CibleImputation type, String uid) {
        return CalculImputation.arrondi(nz(imputationRepo.sumActivesByCible(type, uid)));
    }

    public double resteAPayerVente(CibleImputation type, String uid, double montantVente) {
        return CalculImputation.arrondi(montantVente - payeVente(type, uid));
    }

    @Transactional(readOnly = true)
    public List<CalculImputation.Source> sourcesDisponibles(Client client) {
        List<CalculImputation.Source> sources = new ArrayList<>();
        for (PaiementClient p : paiementRepo.findActifsByClientId(client.getId())) {
            double reste = CalculImputation.arrondi(p.getMontant() - nz(imputationRepo.sumActivesByPaiementId(p.getId())));
            if (reste > 0) {
                sources.add(new CalculImputation.Source(p.getUniqueId(), reste,
                        p.getCommande() != null ? p.getCommande().getUniqueId() : null, p.getVenteCibleUniqueId()));
            }
        }
        return sources;
    }

    /** Impute l'argent disponible du client sur ses ventes non réglées. Idempotent :
     * n'ajoute que ce qui manque. Verrouille la ligne client (deux saisies simultanées). */
    @Transactional
    public void imputer(Client clientNonVerrouille) {
        Client client = clientRepo.findByIdForUpdate(clientNonVerrouille.getId())
                .orElseThrow(() -> new IllegalArgumentException("Client introuvable."));
        List<CalculImputation.Source> sources = sourcesDisponibles(client);
        if (sources.isEmpty()) return;

        List<CalculImputation.Besoin> besoins = new ArrayList<>();
        // Œufs et réforme fusionnés par date puis id : l'ordre d'ancienneté est global.
        record V(java.time.LocalDate date, Long id, CalculImputation.Besoin besoin) {}
        List<V> ventes = new ArrayList<>();
        for (VenteOeufs v : venteOeufsRepo.findActivesByClientIdPourImputation(client.getId())) {
            double reste = resteAPayerVente(CibleImputation.VENTE_OEUFS, v.getUniqueId(), nz(v.getMontant()));
            if (reste > 0) ventes.add(new V(v.getDate(), v.getId(), new CalculImputation.Besoin("VENTE_OEUFS", v.getUniqueId(), reste,
                    v.getCommande() != null ? v.getCommande().getUniqueId() : null)));
        }
        for (VenteReforme v : venteReformeRepo.findActivesByClientIdPourImputation(client.getId())) {
            double reste = resteAPayerVente(CibleImputation.VENTE_REFORME, v.getUniqueId(), nz(v.getMontant()));
            if (reste > 0) ventes.add(new V(v.getDate(), v.getId(), new CalculImputation.Besoin("VENTE_REFORME", v.getUniqueId(), reste,
                    v.getCommande() != null ? v.getCommande().getUniqueId() : null)));
        }
        ventes.sort(java.util.Comparator.comparing(V::date).thenComparing(V::id));
        ventes.forEach(v -> besoins.add(v.besoin()));
        if (besoins.isEmpty()) return;

        for (CalculImputation.Affectation a : CalculImputation.repartir(sources, besoins)) {
            enregistrer(client, a);
        }
    }

    /** Verrouille les lignes client (PESSIMISTIC_WRITE) AVANT de lire ou d'annuler des
     * imputations : sans ça, un remboursement simultané (rembourserInterne, qui verrouille
     * aussi) pouvait prendre de l'argent qu'on était en train de libérer ou de retirer, et
     * laisser une avance négative. Toujours dans l'ordre des id (deux clients à la fois :
     * changement de client d'une vente) pour ne jamais s'interbloquer. Les null sont ignorés. */
    @Transactional
    public void verrouiller(Client... clients) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        for (Client c : clients) if (c != null && c.getId() != null) ids.add(c.getId());
        for (Long id : ids) {
            clientRepo.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Client introuvable."));
        }
    }

    /** Enregistre des affectations déjà calculées (imputer, remboursement). */
    @Transactional
    public void enregistrer(Client client, CalculImputation.Affectation a) {
        PaiementClient p = paiementRepo.findByUniqueId(a.paiementUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Paiement introuvable : " + a.paiementUniqueId()));
        ImputationPaiement i = new ImputationPaiement();
        i.setUniqueId(UUID.randomUUID().toString());
        i.setFarm(client.getFarm());
        i.setClient(client);
        i.setPaiement(p);
        i.setCibleType(CibleImputation.valueOf(a.cibleType()));
        i.setCibleUniqueId(a.cibleUniqueId());
        i.setMontant(a.montant());
        i.setStatut(StatutMouvement.ACTIF);
        i.setInitialisation(Initialisation.init());
        imputationRepo.save(i);
    }

    private void annuler(ImputationPaiement i, String motif) {
        i.setStatut(StatutMouvement.ANNULE);
        i.setMotifAnnulation(motif);
        i.setDateAnnulation(java.time.LocalDateTime.now());
        imputationRepo.save(i);
    }

    /** Vente supprimée, client retiré, paiement annulé... : l'argent retourne en avance. */
    @Transactional
    public void annulerImputationsCible(CibleImputation type, String uid, String motif) {
        for (ImputationPaiement i : imputationRepo.findActivesByCible(type, uid)) annuler(i, motif);
    }

    /** Vente dont le montant baisse : on annule les imputations les plus récentes
     * jusqu'à ne pas dépasser le nouveau montant ; la dernière est recréée plus petite. */
    @Transactional
    public void ramenerImputationsCible(CibleImputation type, String uid, double nouveauMontant, String motif) {
        double exces = CalculImputation.arrondi(payeVente(type, uid) - nouveauMontant);
        if (exces <= 0) return;
        for (ImputationPaiement i : imputationRepo.findActivesByCible(type, uid)) {
            if (exces <= 0) break;
            annuler(i, motif);
            double garde = CalculImputation.arrondi(i.getMontant() - exces);
            if (garde > 0) {
                enregistrer(i.getClient(), new CalculImputation.Affectation(
                        i.getPaiement().getUniqueId(), type.name(), uid, garde));
                exces = 0;
            } else {
                exces = CalculImputation.arrondi(exces - i.getMontant());
            }
        }
    }
}
