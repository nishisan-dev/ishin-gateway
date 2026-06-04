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
package dev.nishisan.ishin.gateway.health;

import dev.nishisan.ishin.gateway.cluster.ClusterService;
import dev.nishisan.ishin.gateway.manager.ConfigurationManager;
import dev.nishisan.ishin.gateway.observability.service.TracerService;
import dev.nishisan.ishin.gateway.tunnel.TunnelService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator customizado para o Ishin Gateway.
 * <p>
 * Verifica se a configuração foi carregada com sucesso e reporta
 * informações operacionais (instanceId, endpoints configurados, cluster status).
 * <p>
 * Acessível via {@code GET /actuator/health}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-08
 */
@Component
public class IshinHealthIndicator implements HealthIndicator {

    private static final Logger logger = LogManager.getLogger(IshinHealthIndicator.class);

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private TracerService tracerService;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private ObjectProvider<TunnelService> tunnelServiceProvider;

    @Override
    public Health health() {
        try {
            var config = configurationManager.loadConfiguration();
            if (config == null) {
                return Health.down()
                        .withDetail("reason", "Configuration not loaded")
                        .withDetail("instanceId", tracerService.getInstanceId())
                        .build();
            }

            Health.Builder builder = Health.up()
                    .withDetail("instanceId", tracerService.getInstanceId())
                    .withDetail("mode", config.getMode());

            // Cluster info
            if (clusterService.isClusterMode()) {
                builder.withDetail("clusterMode", true)
                       .withDetail("clusterNodeId", clusterService.getLocalNodeId())
                       .withDetail("isLeader", clusterService.isLeader())
                       .withDetail("activeMembers", clusterService.getActiveMembersCount());
            } else {
                builder.withDetail("clusterMode", false);
            }

            if (config.isTunnelMode()) {
                TunnelService tunnelService = tunnelServiceProvider.getIfAvailable();
                boolean tunnelRunning = tunnelService != null && tunnelService.isRunning();
                builder.withDetail("tunnelRunning", tunnelRunning);

                if (!clusterService.isClusterMode()) {
                    return Health.down()
                            .withDetail("reason", "Tunnel mode requires cluster mode")
                            .withDetail("instanceId", tracerService.getInstanceId())
                            .withDetail("mode", config.getMode())
                            .build();
                }

                if (!tunnelRunning) {
                    return Health.down()
                            .withDetail("reason", "Tunnel service not running")
                            .withDetail("instanceId", tracerService.getInstanceId())
                            .withDetail("mode", config.getMode())
                            .withDetail("clusterMode", true)
                            .withDetail("clusterNodeId", clusterService.getLocalNodeId())
                            .withDetail("activeMembers", clusterService.getActiveMembersCount())
                            .build();
                }

                if (tunnelService.getTunnelRegistry() != null) {
                    builder.withDetail("virtualPorts", tunnelService.getTunnelRegistry().getActiveVirtualPorts().size())
                            .withDetail("tunnelMembers", tunnelService.getTunnelRegistry().getTotalMemberCount());
                }
                return builder.build();
            }

            if (config.getEndpoints() == null || config.getEndpoints().isEmpty()) {
                return Health.down()
                        .withDetail("reason", "No endpoints configured")
                        .withDetail("instanceId", tracerService.getInstanceId())
                        .withDetail("mode", config.getMode())
                        .build();
            }

            builder.withDetail("endpointsConfigured", config.getEndpoints().size());
            return builder.build();
        } catch (Exception e) {
            logger.error("Health check failed", e);
            return Health.down()
                    .withDetail("instanceId", tracerService.getInstanceId())
                    .withException(e)
                    .build();
        }
    }
}
