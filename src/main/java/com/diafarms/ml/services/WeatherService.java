package com.diafarms.ml.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Météo courante via Open-Meteo (gratuit, sans clé API) — géocode le nom de ville de
 * la ferme puis récupère température/humidité actuelles. Résultat mis en cache en
 * mémoire par ville (TTL) car cet appel est fait à chaque calcul de notifications,
 * potentiellement pour plusieurs projets d'une même ferme en boucle.
 */
@Service
public class WeatherService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";

    private final RestClient restClient = RestClient.create();
    private final Map<String, CachedWeather> cache = new ConcurrentHashMap<>();

    public record WeatherSnapshot(double temperatureC, double humidityPct) {}

    private record CachedWeather(WeatherSnapshot snapshot, Instant fetchedAt) {
        boolean isFresh() {
            return fetchedAt.plus(CACHE_TTL).isAfter(Instant.now());
        }
    }

    public Optional<WeatherSnapshot> getCurrentWeather(String ville) {
        if (ville == null || ville.isBlank()) return Optional.empty();

        CachedWeather cached = cache.get(ville);
        if (cached != null && cached.isFresh()) {
            return Optional.of(cached.snapshot());
        }

        try {
            GeocodingResponse geo = restClient.get()
                    .uri(GEOCODING_URL + "?name={ville}&count=1&language=fr", ville)
                    .retrieve()
                    .body(GeocodingResponse.class);

            if (geo == null || geo.results == null || geo.results.isEmpty()) {
                return cached != null ? Optional.of(cached.snapshot()) : Optional.empty();
            }

            GeoResult loc = geo.results.get(0);

            ForecastResponse forecast = restClient.get()
                    .uri(FORECAST_URL + "?latitude={lat}&longitude={lon}&current=temperature_2m,relative_humidity_2m",
                            loc.latitude, loc.longitude)
                    .retrieve()
                    .body(ForecastResponse.class);

            if (forecast == null || forecast.current == null) {
                return cached != null ? Optional.of(cached.snapshot()) : Optional.empty();
            }

            WeatherSnapshot snapshot = new WeatherSnapshot(
                    forecast.current.temperature_2m,
                    forecast.current.relative_humidity_2m);
            cache.put(ville, new CachedWeather(snapshot, Instant.now()));
            return Optional.of(snapshot);
        } catch (Exception e) {
            // Ne jamais faire échouer le calcul des alertes à cause d'une API externe
            // indisponible : on retombe sur la dernière valeur connue si elle existe.
            return cached != null ? Optional.of(cached.snapshot()) : Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GeocodingResponse {
        public List<GeoResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GeoResult {
        public double latitude;
        public double longitude;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ForecastResponse {
        public CurrentWeather current;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CurrentWeather {
        public double temperature_2m;
        public double relative_humidity_2m;
    }
}
