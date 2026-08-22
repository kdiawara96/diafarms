package com.diafarms.ml.enums;

// Types de stock qu'un magasin de vente peut recevoir par transfert — volontairement
// limité à œufs/réforme/œufs cassés (les seuls types explicitement demandés avec un vrai
// stock par magasin) ; les fientes restent une vente farm-wide simple, sans stock ni magasin.
// OEUFS_CASSES : pool séparé de OEUFS (jamais mélangé) — voir CollecteOeufsImpl (transfert
// auto vers le magasin de vente par défaut, comme les œufs vendables) et VenteOeufs.typeOeuf.
public enum TypeStockMagasin {
    OEUFS,
    REFORME,
    OEUFS_CASSES
}
