package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.MagasinTransfertDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.MagasinTransfertCreate;

public interface MagasinTransfertService {
    // Toujours une liste : REFORME crée une seule ligne (le projet est choisi
    // directement), OEUFS en crée une par projet contributeur du bâtiment de stockage
    // source (répartition automatique, voir l'implémentation).
    List<MagasinTransfertDTO> create(MagasinTransfertCreate data);
    PaginatedResponse<MagasinTransfertDTO> list(String magasinUniqueId, int page, int size);
    // REFORME : stock du projet pas encore transféré vers aucun magasin — plafond d'un nouveau transfert.
    int disponibleATransfererDepuisProjet(String projetUniqueId, String type);
    // OEUFS/OEUFS_CASSES : stock du magasin de stockage (tous projets confondus) pas
    // encore transféré vers aucun magasin de vente — plafond d'un nouveau transfert.
    // type null/vide = OEUFS (comportement historique, compat clients existants).
    int disponibleATransfererDepuisMagasinStockage(String magasinStockageUniqueId, String type);
}
