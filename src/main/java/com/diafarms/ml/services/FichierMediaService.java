package com.diafarms.ml.services;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.diafarms.ml.DTO.FichierMediaDTO;

public interface FichierMediaService {
    FichierMediaDTO uploadFichierProjet(MultipartFile file, String projetUniqueId) throws Exception;
    List<FichierMediaDTO> getFichiersByProjet(String projetUniqueId);
    String deleteFichier(Long fichierId) throws Exception;
    FichierMediaDTO getFichierById(Long fichierId);
}
