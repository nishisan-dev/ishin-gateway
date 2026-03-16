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
package dev.nishisan.ngate.tunnel;

import dev.nishisan.ngate.cluster.ClusterService;
import dev.nishisan.ngate.configuration.ServerConfiguration;
import dev.nishisan.ngate.configuration.TunnelConfiguration;
import dev.nishisan.ngate.manager.ConfigurationManager;
import dev.nishisan.utils.ngrid.structures.DistributedMap;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Orquestrador do Tunnel Mode — substitui o {@code EndpointManager} quando
 * {@code mode: tunnel}.
 * <p>
 * Inicializa o {@link TunnelRegistry} e o {@link TunnelEngine}, conecta
 * os callbacks de lifecycle (open/close listener) e gerencia o shutdown graceful.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@Service
public class TunnelService {

    private static final Logger logger = LogManager.getLogger(TunnelService.class);

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private TunnelMetrics tunnelMetrics;

    private TunnelRegistry tunnelRegistry;
    private TunnelEngine tunnelEngine;

    @Order(30) // Mesmo slot do EndpointManager
    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        ServerConfiguration config = configurationManager.loadConfiguration();

        if (!config.isTunnelMode()) {
            logger.debug("TunnelService: skipping — mode={}", config.getMode());
            return;
        }

        logger.info("═══════════════════════════════════════════════════════");
        logger.info("  n-gate TUNNEL MODE — TCP L4 Load Balancer");
        logger.info("═══════════════════════════════════════════════════════");

        // Validar pré-requisitos
        if (!clusterService.isClusterMode()) {
            logger.error("Tunnel mode requires cluster mode (NGrid) — aborting");
            return;
        }

        TunnelConfiguration tunnelConfig = config.getTunnel();
        if (tunnelConfig == null) {
            tunnelConfig = new TunnelConfiguration(); // usar defaults
            logger.info("No tunnel config block — using defaults");
        }

        // Obter o DistributedMap para o registry
        DistributedMap<String, TunnelRegistryEntry> registryMap = clusterService.getDistributedMap(
                "ngate-tunnel-registry", String.class, TunnelRegistryEntry.class);

        if (registryMap == null) {
            logger.error("Failed to obtain tunnel registry DistributedMap — aborting");
            return;
        }

        // Inicializar TunnelRegistry
        this.tunnelRegistry = new TunnelRegistry(tunnelConfig, tunnelMetrics);
        this.tunnelRegistry.setRegistryMap(registryMap);

        // Inicializar TunnelEngine
        this.tunnelEngine = new TunnelEngine(tunnelRegistry, tunnelMetrics, tunnelConfig.getBindAddress());

        // Conectar callbacks de lifecycle
        tunnelRegistry.setOnGroupCreated(vPort -> {
            logger.info("VirtualPortGroup created — opening listener on vPort:{}", vPort);
            tunnelEngine.openListener(vPort);
        });

        tunnelRegistry.setOnGroupRemoved(vPort -> {
            logger.info("VirtualPortGroup removed — closing listener on vPort:{}", vPort);
            tunnelEngine.closeListener(vPort);
        });

        // Iniciar componentes
        tunnelEngine.start();
        tunnelRegistry.start();

        logger.info("Tunnel Mode fully initialized — LB algorithm: {}, missedKeepalives: {}, drainTimeout: {}s",
                tunnelConfig.getLoadBalancing(), tunnelConfig.getMissedKeepalives(), tunnelConfig.getDrainTimeout());
    }

    @PreDestroy
    private void shutdown() {
        if (tunnelRegistry != null) {
            logger.info("Tunnel Mode: graceful shutdown...");
            tunnelRegistry.stop();
        }
        if (tunnelEngine != null) {
            tunnelEngine.stop();
            logger.info("Tunnel Mode: shutdown complete — {} connections were active",
                    tunnelEngine.getTotalActiveConnections());
        }
    }
}
