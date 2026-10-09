package com.diafarms.ml.commons;

import java.time.LocalDate;

import com.diafarms.ml.DTO.AbonnementTarifDTO;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;

// Règle de prix de l'abonnement (décision du propriétaire, 2026-10-05), sans base de
// données ni utilisateur courant : utilisable partout, y compris la tâche des rappels.
//
//   prix du mois = prix par poule × poules comptées, arrondi AU-DESSUS au multiple de
//                  « arrondi », avec un minimum par mois ;
//   prix de l'an = prix du mois × (12 - mois offerts) ;
//   poules comptées = le plus grand nombre de sujets vivants des Projets en cours sur les
//                  30 derniers jours (voir AbonnementTarifService.compterPoules) ;
//   prix fixe (Abonnement.prixMensuelFixe, tarif spécial) : remplace la règle par poule.
//
// Réglages dans AbonnementConfig (colonnes nullables, null = valeur par défaut ci-dessous).
// Les anciennes colonnes prixMensuel/prixAnnuel ne servent plus au prix des fermes.
public final class AbonnementTarif {

    public static final double PRIX_PAR_POULE_DEFAUT = 6;
    public static final double PRIX_MINIMUM_MENSUEL_DEFAUT = 5000;
    public static final int MOIS_OFFERTS_ANNUEL_DEFAUT = 2;
    public static final int ARRONDI_DEFAUT = 100;
    public static final int FENETRE_JOURS = 30;

    private AbonnementTarif() {}

    public record Regles(double prixParPoule, double prixMinimumMensuel, int moisOffertsAnnuel, int arrondi,
            // Au-delà de ce nombre de poules : tarif sur devis (voir AbonnementCredit).
            int seuilSurDevis) {
        public Regles(double prixParPoule, double prixMinimumMensuel, int moisOffertsAnnuel, int arrondi) {
            this(prixParPoule, prixMinimumMensuel, moisOffertsAnnuel, arrondi, AbonnementCredit.SEUIL_SUR_DEVIS_DEFAUT);
        }
    }

    public static Regles regles(AbonnementConfig c) {
        Double ppp = c != null ? c.getPrixParPoule() : null;
        Double min = c != null ? c.getPrixMinimumMensuel() : null;
        Integer mois = c != null ? c.getMoisOffertsAnnuel() : null;
        Integer arr = c != null ? c.getArrondi() : null;
        return new Regles(
                ppp == null || ppp < 0 ? PRIX_PAR_POULE_DEFAUT : ppp,
                min == null || min < 0 ? PRIX_MINIMUM_MENSUEL_DEFAUT : min,
                mois == null || mois < 0 || mois > 11 ? MOIS_OFFERTS_ANNUEL_DEFAUT : mois,
                arr == null || arr < 1 ? ARRONDI_DEFAUT : arr,
                AbonnementCredit.regles(c).seuilSurDevis());
    }

    // Arrondi au-dessus au multiple de « arrondi » (5004 -> 5100 avec 100). Petite
    // tolérance pour qu'un calcul en virgule flottante (ex. 8100.0000001) ne monte pas
    // d'un cran de trop.
    public static double arrondirAuDessus(double montant, int arrondi) {
        if (montant <= 0) return 0;
        return Math.ceil(montant / arrondi - 1e-9) * arrondi;
    }

    public static double prixAnnuel(double prixMensuel, Regles r) {
        return prixMensuel * (12 - r.moisOffertsAnnuel());
    }

    // Tarif complet. poules < 0 traité comme 0. abonnement null : pas de prix fixe.
    public static AbonnementTarifDTO calculer(int poules, LocalDate dateMax, Abonnement abonnement, Regles r,
            boolean calculEnErreur) {
        int n = Math.max(0, poules);
        double brut = n * r.prixParPoule();
        double arrondi = arrondirAuDessus(brut, r.arrondi());
        boolean minimum = arrondi < r.prixMinimumMensuel();
        double selonPoules = minimum ? r.prixMinimumMensuel() : arrondi;
        Double fixe = abonnement != null ? abonnement.getPrixMensuelFixe() : null;
        boolean prixFixe = fixe != null && fixe > 0;
        double mensuel = prixFixe ? fixe : selonPoules;
        return new AbonnementTarifDTO(n, n > 0 ? dateMax : null, FENETRE_JOURS, mensuel, prixAnnuel(mensuel, r),
                brut, arrondi, minimum, selonPoules, prixFixe, prixFixe ? fixe : null,
                prixFixe ? abonnement.getMotifPrixFixe() : null,
                r.prixParPoule(), r.prixMinimumMensuel(), r.moisOffertsAnnuel(), r.arrondi(), calculEnErreur,
                n > r.seuilSurDevis(), r.seuilSurDevis());
    }

    // Tarif qu'on peut facturer ou annoncer : calcul réussi, ou prix fixe (qui ne dépend
    // pas du comptage). Un tarif en erreur n'est jamais facturé ni écrit dans un rappel.
    public static boolean facturable(AbonnementTarifDTO t) {
        return t != null && (!t.calculEnErreur() || t.prixFixe());
    }

    // Version montrée à la ferme (/abonnements/moi) : sans la raison du tarif spécial,
    // qui reste une information interne de l'équipe (console seulement).
    public static AbonnementTarifDTO pourLaFerme(AbonnementTarifDTO t) {
        if (t == null || t.motifPrixFixe() == null) return t;
        return new AbonnementTarifDTO(t.poulesComptees(), t.dateMax(), t.fenetreJours(), t.prixMensuel(),
                t.prixAnnuel(), t.montantParPoules(), t.montantArrondi(), t.minimumApplique(),
                t.prixMensuelSelonPoules(), t.prixFixe(), t.prixMensuelFixe(), null, t.prixParPoule(),
                t.prixMinimumMensuel(), t.moisOffertsAnnuel(), t.arrondi(), t.calculEnErreur(), t.surDevis(),
                t.seuilSurDevis());
    }

    public static String poules(int n) {
        return AbonnementEcheance.nombre(n) + (n > 1 ? " poules" : " poule");
    }

    // « Montant : 8 100 FCFA par mois (1 350 poules) ou 81 000 FCFA par an. » (rappels).
    public static String phraseMontant(AbonnementTarifDTO t) {
        String detail = t.prixFixe() ? "tarif spécial"
                : t.minimumApplique() ? poules(t.poulesComptees()) + ", prix minimum"
                : poules(t.poulesComptees());
        return "Montant : " + AbonnementEcheance.fcfa(t.prixMensuel()) + " par mois (" + detail + ") ou "
                + AbonnementEcheance.fcfa(t.prixAnnuel()) + " par an.";
    }
}
