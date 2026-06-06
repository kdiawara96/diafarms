package com.diafarms.ml.controllers;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.services.VaccinationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/vaccinations")
@RequiredArgsConstructor
public class VaccinationControllers {

    private final VaccinationService services;
    
}
