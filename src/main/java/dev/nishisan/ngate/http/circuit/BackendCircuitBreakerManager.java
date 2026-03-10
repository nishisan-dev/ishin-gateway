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
package dev.nishisan.ngate.http.circuit;

import dev.nishisan.ngate.configuration.CircuitBreakerConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Gerencia instâncias de {@link CircuitBreaker} por nome de backend.
 * <p>
 * Quando habilitado via {@code circuitBreaker.enabled=true} no adapter.yaml,
 * protege os backends contra overload — requests para backends com circuito
 * aberto são rejeitados imediatamente com HTTP 503.
 * <p>
 * Métricas do circuit breaker são automaticamente registradas no Micrometer
 * via {@link TaggedCircuitBreakerMetrics}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
@Component
public class BackendCircuitBreakerManager {

    private static final Logger logger = LogManager.getLogger(BackendCircuitBreakerManager.class);

    private volatile CircuitBreakerRegistry registry;
    private volatile boolean enabled;
    private final MeterRegistry meterRegistry;

    public BackendCircuitBreakerManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Inicializa com defaults; será reconfigurado quando a config for carregada
        this.registry = CircuitBreakerRegistry.ofDefaults();
        this.enabled = false;

        // Registra métricas do circuit breaker no Micrometer
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        logger.info("BackendCircuitBreakerManager initialized (waiting for configuration)");
    }

    /**
     * Inicializa o manager com a configuração do adapter.yaml.
     * Deve ser chamado após o carregamento da configuração.
     */
    public void configure(CircuitBreakerConfiguration config) {
        if (config == null || !config.isEnabled()) {
            this.enabled = false;
            logger.info("Circuit breaker: DISABLED");
            return;
        }

        CircuitBreakerConfig customConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(config.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(config.getWaitDurationInOpenState()))
                .slidingWindowSize(config.getSlidingWindowSize())
                .permittedNumberOfCallsInHalfOpenState(config.getPermittedCallsInHalfOpenState())
                .slowCallDurationThreshold(Duration.ofSeconds(config.getSlowCallDurationThreshold()))
                .slowCallRateThreshold(config.getSlowCallRateThreshold())
                .build();

        // Cria um novo registry com a config customizada como default
        // (Resilience4j não permite addConfiguration("default") — nome reservado)
        this.registry = CircuitBreakerRegistry.of(customConfig);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        this.enabled = true;

        logger.info("Circuit breaker configured: failureRate={}%, waitDuration={}s, slidingWindow={}, " +
                        "halfOpenCalls={}, slowCallThreshold={}s, slowCallRate={}%",
                config.getFailureRateThreshold(),
                config.getWaitDurationInOpenState(),
                config.getSlidingWindowSize(),
                config.getPermittedCallsInHalfOpenState(),
                config.getSlowCallDurationThreshold(),
                config.getSlowCallRateThreshold());
    }

    /**
     * @return true se o circuit breaker está habilitado
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Obtém ou cria um CircuitBreaker para o backend especificado.
     *
     * @param backendName nome do backend
     * @return CircuitBreaker do backend
     */
    public CircuitBreaker getOrCreate(String backendName) {
        return registry.circuitBreaker(backendName);
    }

    /**
     * @return o registry completo (útil para diagnóstico)
     */
    public CircuitBreakerRegistry getRegistry() {
        return registry;
    }
}
