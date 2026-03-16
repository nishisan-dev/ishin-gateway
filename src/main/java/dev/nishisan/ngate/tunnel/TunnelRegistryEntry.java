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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry entry publicado pelo proxy no NGrid {@code DistributedMap}.
 * <p>
 * Cada nó proxy publica um registro sob a chave {@code tunnel:registry:{nodeId}}.
 * O túnel observa essas entradas para manter os {@link VirtualPortGroup}s atualizados.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class TunnelRegistryEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private String nodeId;
    private String host;
    private List<ListenerRegistration> listeners = new ArrayList<>();
    private String status;          // ACTIVE | DRAINING | STANDBY
    private int weight;
    private long lastKeepAlive;     // epoch millis
    private long registeredAt;      // epoch millis
    private int keepaliveInterval;  // segundos

    public TunnelRegistryEntry() {
    }

    public TunnelRegistryEntry(String nodeId, String host, String status, int weight, int keepaliveInterval) {
        this.nodeId = nodeId;
        this.host = host;
        this.status = status;
        this.weight = weight;
        this.keepaliveInterval = keepaliveInterval;
        this.registeredAt = System.currentTimeMillis();
        this.lastKeepAlive = this.registeredAt;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public List<ListenerRegistration> getListeners() {
        return listeners;
    }

    public void setListeners(List<ListenerRegistration> listeners) {
        this.listeners = listeners;
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
    }

    public long getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(long registeredAt) {
        this.registeredAt = registeredAt;
    }

    public int getKeepaliveInterval() {
        return keepaliveInterval;
    }

    public void setKeepaliveInterval(int keepaliveInterval) {
        this.keepaliveInterval = keepaliveInterval;
    }

    @Override
    public String toString() {
        return "TunnelRegistryEntry{nodeId='" + nodeId + "', host='" + host +
                "', status='" + status + "', listeners=" + listeners.size() +
                ", weight=" + weight + "}";
    }

    /**
     * Registro individual de um listener (virtualPort → realPort).
     */
    public static class ListenerRegistration implements Serializable {

        private static final long serialVersionUID = 1L;

        private int virtualPort;
        private int realPort;
        private String protocol = "tcp";

        public ListenerRegistration() {
        }

        public ListenerRegistration(int virtualPort, int realPort) {
            this.virtualPort = virtualPort;
            this.realPort = realPort;
        }

        public int getVirtualPort() {
            return virtualPort;
        }

        public void setVirtualPort(int virtualPort) {
            this.virtualPort = virtualPort;
        }

        public int getRealPort() {
            return realPort;
        }

        public void setRealPort(int realPort) {
            this.realPort = realPort;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        @Override
        public String toString() {
            return "ListenerRegistration{vPort=" + virtualPort + ", rPort=" + realPort +
                    ", proto=" + protocol + "}";
        }
    }
}
