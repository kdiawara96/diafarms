package com.diafarms.ml.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Tâches planifiées (première du projet) : rappels de fin d'abonnement, voir
// AbonnementRappelService.tacheQuotidienne.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
