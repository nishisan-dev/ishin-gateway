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
package dev.nishisan.ishin.gateway.configuration;

/**
 * Configuração do registro de um proxy no túnel via NGrid.
 * <p>
 * Usado apenas quando {@code mode: proxy} e {@code tunnel.registration.enabled: true}.
 * O proxy publica um {@code TunnelRegistryEntry} no NGrid {@code DistributedMap}
 * e mantém heartbeat periódico conforme {@code keepaliveInterval}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class TunnelRegistrationConfiguration {

    /**
     * Habilita o registro deste proxy no túnel.
     */
    private boolean enabled = false;

    /**
     * Intervalo em segundos entre heartbeats (atualização de lastKeepAlive no NMap).
     */
    private int keepaliveInterval = 3;

    /**
     * Status inicial do proxy no pool do túnel.
     * Valores: "ACTIVE", "STANDBY".
     */
    private String status = "ACTIVE";

    /**
     * Peso para weighted load balancing (default: 100).
     */
    private int weight = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getKeepaliveInterval() {
        return keepaliveInterval;
    }

    public void setKeepaliveInterval(int keepaliveInterval) {
        this.keepaliveInterval = keepaliveInterval;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }
}
