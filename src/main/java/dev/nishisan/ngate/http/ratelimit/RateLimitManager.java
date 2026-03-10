/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.nishisan.ngate.http.ratelimit;

import dev.nishisan.ngate.configuration.RateLimitConfiguration;
import dev.nishisan.ngate.configuration.RateLimitRefConfiguration;
import dev.nishisan.ngate.configuration.RateLimitZoneConfiguration;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Gerencia instâncias de {@link RateLimiter} por chave composta (scope + zone).
 * <p>
 * Quando habilitado via {@code rateLimiting.enabled=true} no adapter.yaml,
 * controla a taxa de requests em 3 escopos: listener, rota (urlContext) e backend.
 * <p>
 * Dois modos de operação:
 * <ul>
 *   <li><b>stall</b>: aguarda um slot livre até o timeout (equivalente ao {@code delay} do Nginx)</li>
 *   <li><b>nowait</b>: rejeita imediatamente com HTTP 429</li>
 * </ul>
 * <p>
 * Métricas do rate limiter são automaticamente registradas no Micrometer
 * via {@link TaggedRateLimiterMetrics}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
@Component
public class RateLimitManager {

    private static final Logger logger = LogManager.getLogger(RateLimitManager.class);

    private volatile RateLimiterRegistry registry;
    private volatile boolean enabled;
    private volatile String defaultMode = "nowait";
    private volatile RateLimitConfiguration currentConfig;
    private final MeterRegistry meterRegistry;

    public RateLimitManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.registry = RateLimiterRegistry.ofDefaults();
        this.enabled = false;
        logger.info("RateLimitManager initialized (waiting for configuration)");
    }

    /**
     * Inicializa o manager com a configuração do adapter.yaml.
     * Deve ser chamado após o carregamento da configuração.
     */
    public void configure(RateLimitConfiguration config) {
        if (config == null || !config.isEnabled()) {
            this.enabled = false;
            logger.info("Rate limiting: DISABLED");
            return;
        }

        this.currentConfig = config;
        this.defaultMode = config.getDefaultMode() != null ? config.getDefaultMode() : "nowait";

        // Cria registry com config default e registra cada zona
        RateLimiterConfig defaultRlConfig = RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build();

        this.registry = RateLimiterRegistry.of(defaultRlConfig);

        // Pré-registra todas as zonas configuradas
        if (config.getZones() != null) {
            for (Map.Entry<String, RateLimitZoneConfiguration> entry : config.getZones().entrySet()) {
                String zoneName = entry.getKey();
                RateLimitZoneConfiguration zone = entry.getValue();

                RateLimiterConfig zoneConfig = RateLimiterConfig.custom()
                        .limitForPeriod(zone.getLimitForPeriod())
                        .limitRefreshPeriod(Duration.ofSeconds(zone.getLimitRefreshPeriodSeconds()))
                        .timeoutDuration(Duration.ofSeconds(zone.getTimeoutSeconds()))
                        .build();

                registry.rateLimiter(zoneName, zoneConfig);

                logger.info("Rate limit zone [{}] configured: limit={}/{}s, timeout={}s",
                        zoneName,
                        zone.getLimitForPeriod(),
                        zone.getLimitRefreshPeriodSeconds(),
                        zone.getTimeoutSeconds());
            }
        }

        // Registra métricas no Micrometer
        TaggedRateLimiterMetrics.ofRateLimiterRegistry(registry).bindTo(meterRegistry);

        this.enabled = true;
        logger.info("Rate limiting: ENABLED (defaultMode={}, zones={})",
                defaultMode, config.getZones() != null ? config.getZones().size() : 0);
    }

    /**
     * @return true se o rate limiting está habilitado
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Avalia se um request deve ser permitido, atrasado ou rejeitado.
     *
     * @param scope identificador do escopo (ex: "listener:http", "route:payments", "backend:keycloak")
     * @param ref   referência à zona e modo override (pode ser null = ALLOWED)
     * @return resultado da avaliação
     */
    public RateLimitResult acquirePermission(String scope, RateLimitRefConfiguration ref) {
        if (!enabled || ref == null || ref.getZone() == null) {
            return RateLimitResult.ALLOWED;
        }

        String zoneName = ref.getZone();
        String effectiveMode = ref.getMode() != null ? ref.getMode() : defaultMode;

        // Busca ou cria o rate limiter com chave composta scope:zone
        String rateLimiterKey = scope + ":" + zoneName;
        RateLimiter rateLimiter = getOrCreateRateLimiter(rateLimiterKey, zoneName);

        if ("stall".equalsIgnoreCase(effectiveMode)) {
            return acquireStall(rateLimiter, rateLimiterKey);
        } else {
            return acquireNowait(rateLimiter, rateLimiterKey);
        }
    }

    /**
     * Modo stall: aguarda um slot livre até o timeout configurado na zona.
     * Bloqueia a virtual thread (sem impacto em platform threads).
     */
    private RateLimitResult acquireStall(RateLimiter rateLimiter, String key) {
        try {
            long waitStart = System.nanoTime();
            rateLimiter.acquirePermission();
            long waitMs = (System.nanoTime() - waitStart) / 1_000_000;

            if (waitMs > 1) {
                logger.debug("Rate limit stall [{}]: delayed {}ms", key, waitMs);
                return RateLimitResult.DELAYED;
            }
            return RateLimitResult.ALLOWED;
        } catch (RequestNotPermitted e) {
            logger.debug("Rate limit stall [{}]: rejected after timeout", key);
            return RateLimitResult.REJECTED;
        }
    }

    /**
     * Modo nowait: tenta adquirir permissão sem espera.
     * Rejeita imediatamente se não houver slot disponível.
     * <p>
     * Resilience4j 2.x: altera temporariamente o timeout para zero,
     * tenta adquirir, e restaura o timeout original.
     */
    private RateLimitResult acquireNowait(RateLimiter rateLimiter, String key) {
        // Salva timeout original e troca para zero (nowait)
        Duration originalTimeout = rateLimiter.getRateLimiterConfig().getTimeoutDuration();
        rateLimiter.changeTimeoutDuration(Duration.ZERO);
        try {
            rateLimiter.acquirePermission();
            return RateLimitResult.ALLOWED;
        } catch (RequestNotPermitted e) {
            logger.debug("Rate limit nowait [{}]: rejected immediately", key);
            return RateLimitResult.REJECTED;
        } finally {
            // Restaura o timeout original para não afetar outros escopos
            rateLimiter.changeTimeoutDuration(originalTimeout);
        }
    }

    /**
     * Obtém ou cria um RateLimiter para a chave composta.
     * Se a zona estiver configurada, usa a config da zona; caso contrário usa defaults do registry.
     */
    private RateLimiter getOrCreateRateLimiter(String key, String zoneName) {
        // Tenta buscar pelo key composto (scope:zone)
        if (registry.find(key).isPresent()) {
            return registry.rateLimiter(key);
        }

        // Se a zona existe como template, cria o rate limiter com a mesma config
        if (registry.find(zoneName).isPresent()) {
            RateLimiter zoneTemplate = registry.rateLimiter(zoneName);
            RateLimiterConfig zoneConfig = zoneTemplate.getRateLimiterConfig();
            return registry.rateLimiter(key, zoneConfig);
        }

        // Fallback: usa config default do registry
        logger.warn("Rate limit zone [{}] not found in configuration, using defaults", zoneName);
        return registry.rateLimiter(key);
    }

    /**
     * Retorna o timeout da zona configurada (útil para header Retry-After).
     */
    public int getZoneTimeoutSeconds(String zoneName) {
        if (currentConfig != null && currentConfig.getZones() != null) {
            RateLimitZoneConfiguration zone = currentConfig.getZones().get(zoneName);
            if (zone != null) {
                return zone.getTimeoutSeconds();
            }
        }
        return 5; // default
    }

    /**
     * @return o registry completo (útil para diagnóstico)
     */
    public RateLimiterRegistry getRegistry() {
        return registry;
    }
}
