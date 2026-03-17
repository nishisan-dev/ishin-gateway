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
package dev.nishisan.ishin.gateway.http.ratelimit;

import dev.nishisan.ishin.gateway.configuration.RateLimitConfiguration;
import dev.nishisan.ishin.gateway.configuration.RateLimitRefConfiguration;
import dev.nishisan.ishin.gateway.configuration.RateLimitZoneConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Engine de rate limiting baseada em fixed-window com {@link Semaphore}.
 * <p>
 * Cada zona de rate limiting é implementada como um semáforo com N permits,
 * resetado periodicamente por um {@link ScheduledExecutorService}.
 * Isso garante comportamento determinístico para os modos stall e nowait:
 * <ul>
 *   <li><b>stall</b>: {@code semaphore.tryAcquire(timeout)} — bloqueia a virtual
 *       thread até que um permit esteja disponível ou o timeout expire</li>
 *   <li><b>nowait</b>: {@code semaphore.tryAcquire(0)} — rejeita imediatamente
 *       se não houver permit disponível</li>
 * </ul>
 * <p>
 * Métricas são registradas via Micrometer gauges para monitoramento em tempo real
 * dos permits disponíveis por zona.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
@Component
public class RateLimitManager {

    private static final Logger logger = LogManager.getLogger(RateLimitManager.class);

    private volatile boolean enabled;
    private volatile String defaultMode = "nowait";
    private volatile RateLimitConfiguration currentConfig;
    private final MeterRegistry meterRegistry;

    /**
     * Mapa de rate limiters por chave composta (scope:zone).
     * Cada entrada contém um Semaphore e sua configuração de zona associada.
     */
    private final ConcurrentHashMap<String, RateLimitEntry> limiters = new ConcurrentHashMap<>();

    /**
     * Cache das configurações de zona por nome.
     */
    private final ConcurrentHashMap<String, RateLimitZoneConfiguration> zoneConfigs = new ConcurrentHashMap<>();

    /**
     * Scheduler para reset periódico dos semáforos (um thread é suficiente).
     */
    private ScheduledExecutorService scheduler;

    public RateLimitManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
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

        // Limpa estado anterior
        limiters.clear();
        zoneConfigs.clear();
        if (scheduler != null) {
            scheduler.shutdown();
        }

        // Registra zonas
        if (config.getZones() != null) {
            for (Map.Entry<String, RateLimitZoneConfiguration> entry : config.getZones().entrySet()) {
                String zoneName = entry.getKey();
                RateLimitZoneConfiguration zone = entry.getValue();
                zoneConfigs.put(zoneName, zone);

                logger.info("Rate limit zone [{}] configured: limit={}/{}s, timeout={}s",
                        zoneName,
                        zone.getLimitForPeriod(),
                        zone.getLimitRefreshPeriodSeconds(),
                        zone.getTimeoutSeconds());
            }
        }

        // Inicializa scheduler para reset periódico dos semáforos
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-reset");
            t.setDaemon(true);
            return t;
        });

        this.enabled = true;
        logger.info("Rate limiting: ENABLED (defaultMode={}, zones={})",
                defaultMode, zoneConfigs.size());
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

        RateLimitZoneConfiguration zoneConfig = zoneConfigs.get(zoneName);
        if (zoneConfig == null) {
            logger.warn("Rate limit zone [{}] not found in configuration, allowing request", zoneName);
            return RateLimitResult.ALLOWED;
        }

        String key = scope + ":" + zoneName;
        RateLimitEntry entry = limiters.computeIfAbsent(key, k -> createEntry(k, zoneConfig));

        if ("stall".equalsIgnoreCase(effectiveMode)) {
            return acquireStall(entry, key, zoneConfig);
        } else {
            return acquireNowait(entry, key);
        }
    }

    /**
     * Modo stall: tenta adquirir um permit com timeout.
     * Bloqueia a virtual thread (sem impacto em platform threads via Loom).
     */
    private RateLimitResult acquireStall(RateLimitEntry entry, String key, RateLimitZoneConfiguration zoneConfig) {
        try {
            long startNanos = System.nanoTime();
            boolean acquired = entry.semaphore.tryAcquire(zoneConfig.getTimeoutSeconds(), TimeUnit.SECONDS);
            long waitMs = (System.nanoTime() - startNanos) / 1_000_000;

            if (!acquired) {
                logger.debug("Rate limit stall [{}]: rejected after {}ms timeout", key, waitMs);
                return RateLimitResult.REJECTED;
            }

            if (waitMs > 1) {
                logger.debug("Rate limit stall [{}]: delayed {}ms", key, waitMs);
                return RateLimitResult.DELAYED;
            }
            return RateLimitResult.ALLOWED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Rate limit stall [{}]: interrupted", key);
            return RateLimitResult.REJECTED;
        }
    }

    /**
     * Modo nowait: tenta adquirir um permit sem espera.
     * Rejeita imediatamente se não houver permit disponível.
     */
    private RateLimitResult acquireNowait(RateLimitEntry entry, String key) {
        boolean acquired = entry.semaphore.tryAcquire();
        if (acquired) {
            return RateLimitResult.ALLOWED;
        }
        logger.debug("Rate limit nowait [{}]: rejected immediately", key);
        return RateLimitResult.REJECTED;
    }

    /**
     * Cria uma nova entrada de rate limiting com semáforo e schedule de reset.
     */
    private RateLimitEntry createEntry(String key, RateLimitZoneConfiguration zoneConfig) {
        int permits = zoneConfig.getLimitForPeriod();
        Semaphore semaphore = new Semaphore(permits, true); // fair = true

        // Registra gauge Micrometer para monitorar permits disponíveis
        meterRegistry.gauge("ishin.ratelimit.available_permits",
                Tags.of("key", key), semaphore, Semaphore::availablePermits);

        // Agenda reset periódico
        long periodMs = zoneConfig.getLimitRefreshPeriodSeconds() * 1000L;
        scheduler.scheduleAtFixedRate(() -> {
            int available = semaphore.availablePermits();
            int toRelease = permits - available;
            if (toRelease > 0) {
                semaphore.release(toRelease);
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);

        logger.debug("Rate limiter created [{}]: permits={}, refreshPeriod={}ms", key, permits, periodMs);
        return new RateLimitEntry(semaphore, permits);
    }

    /**
     * Retorna o timeout da zona configurada (útil para header Retry-After).
     */
    public int getZoneTimeoutSeconds(String zoneName) {
        RateLimitZoneConfiguration zone = zoneConfigs.get(zoneName);
        if (zone != null) {
            return zone.getTimeoutSeconds();
        }
        return 5; // default
    }

    @PreDestroy
    private void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            logger.info("RateLimitManager scheduler stopped");
        }
    }

    // ─── Inner class ────────────────────────────────────────────────────

    /**
     * Entrada interna que associa um semáforo ao seu limite máximo de permits.
     */
    private static class RateLimitEntry {
        final Semaphore semaphore;
        final int maxPermits;

        RateLimitEntry(Semaphore semaphore, int maxPermits) {
            this.semaphore = semaphore;
            this.maxPermits = maxPermits;
        }
    }
}
