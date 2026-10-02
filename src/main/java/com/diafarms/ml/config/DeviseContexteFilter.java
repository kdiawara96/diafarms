package com.diafarms.ml.config;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.diafarms.ml.commons.Devise;
import com.diafarms.ml.repository.FarmsRepo;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Devise de la ferme de l'utilisateur pour toute la requête (voir commons.Devise).
// Filtre servlet d'ordre par défaut (le plus bas) : il passe APRÈS la chaîne Spring
// Security, l'utilisateur est donc déjà authentifié et sa devise est lue ici, une fois,
// hors de toute transaction métier. Vidée en fin de requête (threads réutilisés).
@Component
public class DeviseContexteFilter extends OncePerRequestFilter {

    private final FarmsRepo farmsRepo;

    public DeviseContexteFilter(FarmsRepo farmsRepo) {
        this.farmsRepo = farmsRepo;
    }

    @PostConstruct
    void brancher() {
        Devise.brancherResolveur(farmsRepo::findDeviseByUtilisateur);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Devise.effacer();
        try {
            Devise.courante(); // résolution anticipée (no-op sans utilisateur)
            chain.doFilter(request, response);
        } finally {
            Devise.effacer();
        }
    }
}
