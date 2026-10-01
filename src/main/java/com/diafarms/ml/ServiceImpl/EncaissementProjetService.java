package com.diafarms.ml.ServiceImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.EncaissementProjetDTO;
import com.diafarms.ml.DTO.PartProjetDTO;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.commons.ProjetsFerme;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.StatutMouvement;
import com.diafarms.ml.models.PaiementClient;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.repository.ImputationPaiementRepo;
import com.diafarms.ml.repository.PaiementClientRepo;
import com.diafarms.ml.repository.VenteOeufsRepartitionRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;

import lombok.RequiredArgsConstructor;

// Argent des clients vu par projet. Le modèle ne change pas : un paiement client reste
// « Commun » (sans projet) et règle des ventes par ses imputations ; chaque vente est
// partagée entre les projets par ses lignes de répartition (VenteOeufsRepartition,
// VenteReformeRepartition). On en DÉDUIT ici la part de chaque projet :
//   part d'un projet dans une vente = montantAttribue / montant de la vente
//     (à défaut de montant : quantité ou sujets attribués / total) ;
//   encaissé du projet = Σ imputations actives sur ses ventes × sa part
//     + montant rapporté des ventes sans client × sa part.
// Ce qui n'a pas encore réglé de vente reste au niveau de la ferme : acomptes réservés à
// une commande ouverte, avances libres. D'où, pour la ferme :
//   Σ encaissé des projets + acomptes en attente + avances libres
//     = Σ paiements actifs - Σ remboursements + Σ montant rapporté des ventes sans client
// (vérifié par scripts/scenarios-encaissement-projets.sh). Toutes les lectures sont
// groupées (quelques requêtes, jamais une par vente ou par paiement).
@Service
@RequiredArgsConstructor
public class EncaissementProjetService {

    public static final String ACOMPTE_RESERVE = "ACOMPTE_RESERVE";
    public static final String AVANCE = "AVANCE";
    private static final int TAILLE_PAQUET = 1000;

    private final ProjetsFerme projetsFerme;
    private final VenteOeufsRepartitionRepo venteOeufsRepartitionRepo;
    private final VenteReformeRepartitionRepo venteReformeRepartitionRepo;
    private final ImputationPaiementRepo imputationRepo;
    private final PaiementClientRepo paiementRepo;

    private static double nz(Object v) { return v == null ? 0.0 : ((Number) v).doubleValue(); }

    /** Part d'un projet dans une vente : montant attribué / montant de la vente, ou à
     * défaut quantité (œufs, sujets) attribuée / quantité vendue. */
    static double part(Object venteMontant, Object montantAttribue, Object venteQuantite, Object quantiteAttribuee) {
        double m = nz(venteMontant);
        if (m > 0) return nz(montantAttribue) / m;
        double q = nz(venteQuantite);
        return q > 0 ? nz(quantiteAttribuee) / q : 0.0;
    }

    /** Σ imputations actives par vente (uniqueId), par paquets. */
    private Map<String, Double> payeParVente(Collection<String> venteUids) {
        Map<String, Double> out = new HashMap<>();
        List<String> uids = new ArrayList<>(venteUids);
        for (int i = 0; i < uids.size(); i += TAILLE_PAQUET) {
            for (Object[] r : imputationRepo.sumActivesParVente(uids.subList(i, Math.min(uids.size(), i + TAILLE_PAQUET)))) {
                out.merge((String) r[0], nz(r[1]), Double::sum);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public EncaissementProjetDTO encaissementProjet(String projetUniqueId) {
        Projets projet = projetsFerme.charger(projetUniqueId);
        List<Object[]> parts = new ArrayList<>(venteOeufsRepartitionRepo.findPartsActivesParProjet(projet.getId()));
        parts.addAll(venteReformeRepartitionRepo.findPartsActivesParProjet(projet.getId()));

        List<String> ventesClient = new ArrayList<>();
        for (Object[] r : parts) if (r[3] != null) ventesClient.add((String) r[0]);
        Map<String, Double> paye = payeParVente(ventesClient);

        double vendu = 0, encClients = 0, encSansClient = 0;
        for (Object[] r : parts) {
            // [venteUid, venteMontant, montantAttribue, clientId, montantRapporte, venteQte, qteAttribuee]
            double p = part(r[1], r[2], r[5], r[6]);
            vendu += nz(r[2]);
            if (r[3] != null) {
                encClients += paye.getOrDefault((String) r[0], 0.0) * p;
            } else {
                // Montant rapporté jamais saisi : la vente compte pour son plein montant
                // (même convention que VenteOeufsRepo.sumRapporteSansClient).
                encSansClient += (r[4] != null ? nz(r[4]) : nz(r[1])) * p;
            }
        }
        double encaisse = CalculImputation.arrondi(encClients + encSansClient);
        vendu = CalculImputation.arrondi(vendu);
        return EncaissementProjetDTO.builder()
                .projetUniqueId(projet.getUniqueId())
                .vendu(vendu)
                .encaisse(encaisse)
                .resteAEncaisser(Math.max(0.0, CalculImputation.arrondi(vendu - encaisse)))
                .encaisseClients(CalculImputation.arrondi(encClients))
                .encaisseSansClient(CalculImputation.arrondi(encSansClient))
                .build();
    }

    /** Détail d'un paiement pour la Comptabilité. */
    public record RepartitionPaiement(List<PartProjetDTO> projets, double montantNonAttribue,
                                      String natureNonAttribue, String commandeUniqueId, java.time.LocalDate commandeDate,
                                      double montantRembourse) {}

    /** Répartition entre projets de plusieurs paiements (une page de la Comptabilité) :
     * 3 à 5 requêtes quel que soit le nombre de paiements. Clé = uniqueId du paiement. */
    @Transactional(readOnly = true)
    public Map<String, RepartitionPaiement> repartitionPaiements(Collection<String> paiementUids) {
        Map<String, RepartitionPaiement> out = new HashMap<>();
        if (paiementUids == null || paiementUids.isEmpty()) return out;
        List<PaiementClient> paiements = paiementRepo.findByUniqueIdsAvecCommande(paiementUids);
        Map<Long, PaiementClient> parId = new HashMap<>();
        for (PaiementClient p : paiements) parId.put(p.getId(), p);
        if (parId.isEmpty()) return out;

        List<Object[]> imputations = imputationRepo.sumActivesParPaiementEtCibleOrdonnees(parId.keySet());
        List<String> oeufs = new ArrayList<>();
        List<String> reforme = new ArrayList<>();
        for (Object[] r : imputations) {
            if (r[1] == CibleImputation.VENTE_OEUFS) oeufs.add((String) r[2]);
            else if (r[1] == CibleImputation.VENTE_REFORME) reforme.add((String) r[2]);
        }
        // venteUid -> lignes [venteUid, venteMontant, projetUid, code, titre, montantAttribue, venteQte, qteAttribuee]
        Map<String, List<Object[]>> partsParVente = new HashMap<>();
        if (!oeufs.isEmpty()) {
            for (Object[] r : venteOeufsRepartitionRepo.findPartsParVentes(oeufs)) {
                partsParVente.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add(r);
            }
        }
        if (!reforme.isEmpty()) {
            for (Object[] r : venteReformeRepartitionRepo.findPartsParVentes(reforme)) {
                partsParVente.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add(r);
            }
        }

        Map<Long, LinkedHashMap<String, PartProjetDTO>> projetsParPaiement = new HashMap<>();
        Map<Long, Double> imputeParPaiement = new HashMap<>();
        Map<Long, Double> rembourseParPaiement = new HashMap<>();
        for (Object[] r : imputations) {
            Long pid = (Long) r[0];
            double montant = nz(r[3]);
            imputeParPaiement.merge(pid, montant, Double::sum);
            if (r[1] == CibleImputation.REMBOURSEMENT) {
                rembourseParPaiement.merge(pid, montant, Double::sum);
                continue;
            }
            LinkedHashMap<String, PartProjetDTO> projets =
                    projetsParPaiement.computeIfAbsent(pid, k -> new LinkedHashMap<>());
            for (Object[] l : partsParVente.getOrDefault((String) r[2], List.of())) {
                double m = montant * part(l[1], l[5], l[6], l[7]);
                PartProjetDTO d = projets.computeIfAbsent((String) l[2], k -> PartProjetDTO.builder()
                        .projetUniqueId((String) l[2]).code((String) l[3]).titre((String) l[4]).montant(0.0).build());
                d.setMontant(d.getMontant() + m);
            }
        }

        for (PaiementClient p : paiements) {
            boolean actif = p.getStatut() == StatutMouvement.ACTIF;
            List<PartProjetDTO> projets = new ArrayList<>();
            if (actif) {
                for (PartProjetDTO d : projetsParPaiement.getOrDefault(p.getId(), new LinkedHashMap<>()).values()) {
                    d.setMontant(CalculImputation.arrondi(d.getMontant()));
                    if (d.getMontant() > 0) projets.add(d);
                }
            }
            double nonAttribue = actif
                    ? Math.max(0.0, CalculImputation.arrondi(nz(p.getMontant()) - imputeParPaiement.getOrDefault(p.getId(), 0.0)))
                    : 0.0;
            boolean reserve = CompteClientService.estReservee(p.getCommande());
            out.put(p.getUniqueId(), new RepartitionPaiement(projets, nonAttribue,
                    nonAttribue > 0 ? (reserve ? ACOMPTE_RESERVE : AVANCE) : null,
                    reserve ? p.getCommande().getUniqueId() : null,
                    reserve ? p.getCommande().getDateCommande() : null,
                    actif ? CalculImputation.arrondi(rembourseParPaiement.getOrDefault(p.getId(), 0.0)) : 0.0));
        }
        return out;
    }

    /** Argent des clients de la ferme qui n'a pas encore réglé de vente :
     * [acomptes réservés à une commande ouverte, avances libres]. */
    @Transactional(readOnly = true)
    public double[] attenteFerme(Long farmId) {
        if (farmId == null) return new double[] { 0, 0 };
        Map<Long, Double> impute = new HashMap<>();
        for (Object[] r : imputationRepo.sumImputeParCommandeOuverteByFarm(farmId)) impute.put((Long) r[0], nz(r[1]));
        double reserve = 0;
        for (Object[] r : paiementRepo.sumParCommandeOuverteByFarm(farmId)) {
            reserve += Math.max(0.0, nz(r[1]) - impute.getOrDefault((Long) r[0], 0.0));
        }
        reserve = CalculImputation.arrondi(reserve);
        double avance = CalculImputation.arrondi(nz(paiementRepo.sumActifsByFarm(farmId)) - nz(imputationRepo.sumActivesByFarm(farmId)));
        return new double[] { reserve, Math.max(0.0, CalculImputation.arrondi(avance - reserve)) };
    }
}
