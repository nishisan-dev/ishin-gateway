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
package dev.nishisan.ishin.gateway.tunnel;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Representa um membro ativo no pool de um {@link VirtualPortGroup}.
 * <p>
 * Mantém estado mutável (conexões ativas, falhas consecutivas) que é
 * atualizado pelo {@link TunnelEngine} durante o ciclo de vida das conexões TCP.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class BackendMember {

    private final String nodeId;
    private final String host;
    private final int realPort;
    private volatile String status;
    private volatile int weight;
    private volatile long lastKeepAlive;
    private volatile int keepaliveInterval;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public BackendMember(String nodeId, String host, int realPort, String status, int weight,
                         long lastKeepAlive, int keepaliveInterval) {
        this.nodeId = nodeId;
        this.host = host;
        this.realPort = realPort;
        this.status = status;
        this.weight = weight;
        this.lastKeepAlive = lastKeepAlive;
        this.keepaliveInterval = keepaliveInterval;
    }

    /**
     * Chave única para identificar este membro no pool.
     */
    public String getKey() {
        return nodeId + ":" + realPort;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getRealPort() {
        return realPort;
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

    public long getLastKeepAlive() {
        return lastKeepAlive;
    }

    public void setLastKeepAlive(long lastKeepAlive) {
        this.lastKeepAlive = lastKeepAlive;
        this.consecutiveFailures.set(0); // keepalive recebido → reset falhas
    }

    public int getKeepaliveInterval() {
        return keepaliveInterval;
    }

    public void setKeepaliveInterval(int keepaliveInterval) {
        this.keepaliveInterval = keepaliveInterval;
    }

    public AtomicInteger getActiveConnections() {
        return activeConnections;
    }

    public AtomicInteger getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    public boolean isDraining() {
        return "DRAINING".equalsIgnoreCase(status);
    }

    public boolean isStandby() {
        return "STANDBY".equalsIgnoreCase(status);
    }

    /**
     * Verifica se o keepalive expirou com base no threshold calculado.
     *
     * @param missedKeepalives quantidade de keepalives perdidos tolerados
     * @return true se expirou
     */
    public boolean isKeepaliveExpired(int missedKeepalives) {
        long timeoutMs = (long) keepaliveInterval * missedKeepalives * 1000L;
        return System.currentTimeMillis() - lastKeepAlive > timeoutMs;
    }

    @Override
    public String toString() {
        return "BackendMember{" + nodeId + " @ " + host + ":" + realPort +
                " [" + status + "] conns=" + activeConnections.get() +
                " weight=" + weight + "}";
    }
}
