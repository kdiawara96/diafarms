package com.diafarms.ml.enums;

// Distingue une VenteOeufs "normale" d'une vente d'œufs cassés — même entité, mêmes
// mécanismes de répartition par projet/écart théorique-rapporté/solde vendeur-client
// (voir VenteOeufsImpl), mais un pool de stock par magasin totalement séparé (voir
// TypeStockMagasin.OEUFS_CASSES) : une vente CASSE ne consomme jamais le stock BON et
// inversement. Fixé à la création, jamais modifié ensuite (voir VenteOeufsImpl.update).
public enum TypeVenteOeufs {
    BON,
    CASSE
}
