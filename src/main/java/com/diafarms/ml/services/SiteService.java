package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.SiteDTO;
import com.diafarms.ml.request.create.SiteCreate;

public interface SiteService {
    SiteDTO create(SiteCreate data);
    SiteDTO update(String uniqueId, SiteCreate data);
    // Archive (ou restaure) — jamais une vraie suppression : des poulaillers/magasins
    // peuvent encore pointer vers ce site (voir Batiment.site/Magasin.site, lien
    // optionnel jamais cassé par cette opération).
    String deleteOrRecover(String uniqueId);
    List<SiteDTO> list();
}
