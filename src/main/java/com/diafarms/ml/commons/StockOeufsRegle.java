package com.diafarms.ml.commons;

import com.diafarms.ml.models.CollecteOeufs;

// UNE seule règle pour le stock d'œufs "bon état" (vendable au prix normal, pool
// TypeStockMagasin.OEUFS) : collectés - cassés - non utilisables. Les cassés forment
// un pool séparé (OEUFS_CASSES, vendables moins cher) et les non utilisables ne
// sont vendables nulle part (perte pure). Partagée par le transfert automatique à la
// collecte (CollecteOeufsImpl), le transfert manuel (MagasinTransfertServiceImpl),
// le stock farm-wide (VenteOeufsImpl.getStock) et les rapports : avant, le transfert
// manuel oubliait les non utilisables et laissait passer des œufs invendables en
// point de vente.
public final class StockOeufsRegle {

    private StockOeufsRegle() {}

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    public static int bonEtat(Integer collectes, Integer casses, Integer nonUtilisables) {
        return nz(collectes) - nz(casses) - nz(nonUtilisables);
    }

    public static int bonEtat(CollecteOeufs c) {
        return bonEtat(c.getOeufsCollectes(), c.getOeufsCasses(), c.getOeufsNonUtilisables());
    }
}
