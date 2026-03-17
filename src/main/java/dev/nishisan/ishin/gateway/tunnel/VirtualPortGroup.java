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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agrupamento lógico de backends que servem a mesma porta virtual.
 * <p>
 * Cada grupo mantém seu pool de membros e load balancer independente.
 * O túnel abre um {@code ServerSocketChannel} na porta virtual quando
 * o primeiro membro se registra e fecha quando o último é removido.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class VirtualPortGroup {

    private static final Logger logger = LogManager.getLogger(VirtualPortGroup.class);

    private final int virtualPort;
    private final ConcurrentHashMap<String, BackendMember> members = new ConcurrentHashMap<>();
    private final TunnelLoadBalancer loadBalancer;

    public VirtualPortGroup(int virtualPort, TunnelLoadBalancer loadBalancer) {
        this.virtualPort = virtualPort;
        this.loadBalancer = loadBalancer;
    }

    public int getVirtualPort() {
        return virtualPort;
    }

    /**
     * Adiciona ou atualiza um membro no grupo.
     *
     * @return true se é um novo membro (primeira inserção)
     */
    public boolean addOrUpdateMember(BackendMember member) {
        BackendMember existing = members.put(member.getKey(), member);
        if (existing == null) {
            logger.info("Member added to group vPort:{} — {}", virtualPort, member);
            return true;
        }
        return false;
    }

    /**
     * Remove um membro do grupo.
     *
     * @return true se o grupo ficou vazio após a remoção
     */
    public boolean removeMember(String memberKey) {
        BackendMember removed = members.remove(memberKey);
        if (removed != null) {
            logger.info("Member removed from group vPort:{} — {}", virtualPort, removed);
        }
        return members.isEmpty();
    }

    /**
     * Retorna a lista de membros ACTIVE elegíveis para tráfego.
     */
    public List<BackendMember> getActiveMembers() {
        List<BackendMember> active = new ArrayList<>();
        for (BackendMember m : members.values()) {
            if (m.isActive()) {
                active.add(m);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * Retorna a lista de membros STANDBY (para promoção).
     */
    public List<BackendMember> getStandbyMembers() {
        List<BackendMember> standby = new ArrayList<>();
        for (BackendMember m : members.values()) {
            if (m.isStandby()) {
                standby.add(m);
            }
        }
        return Collections.unmodifiableList(standby);
    }

    /**
     * Retorna todos os membros (qualquer status).
     */
    public List<BackendMember> getAllMembers() {
        return Collections.unmodifiableList(new ArrayList<>(members.values()));
    }

    /**
     * Seleciona o próximo backend via load balancer.
     *
     * @return o membro selecionado, ou null se não há ativos
     */
    public BackendMember selectNext() {
        List<BackendMember> active = getActiveMembers();
        if (active.isEmpty()) {
            return null;
        }
        return loadBalancer.select(active);
    }

    /**
     * Promove o STANDBY com maior peso para ACTIVE.
     *
     * @return o membro promovido, ou null se não há STANDBY
     */
    public BackendMember promoteStandby() {
        List<BackendMember> standby = getStandbyMembers();
        if (standby.isEmpty()) {
            return null;
        }
        // Promover o de maior peso
        BackendMember promoted = standby.stream()
                .max(Comparator.comparingInt(BackendMember::getWeight))
                .orElse(null);
        if (promoted != null) {
            promoted.setStatus("ACTIVE");
            logger.info("STANDBY promoted to ACTIVE in group vPort:{} — {}", virtualPort, promoted);
        }
        return promoted;
    }

    public int getMemberCount() {
        return members.size();
    }

    public int getActiveMemberCount() {
        return (int) members.values().stream().filter(BackendMember::isActive).count();
    }

    public int getStandbyMemberCount() {
        return (int) members.values().stream().filter(BackendMember::isStandby).count();
    }

    public int getDrainingMemberCount() {
        return (int) members.values().stream().filter(BackendMember::isDraining).count();
    }

    @Override
    public String toString() {
        return "VirtualPortGroup{vPort=" + virtualPort + ", members=" + members.size() +
                ", active=" + getActiveMemberCount() + "}";
    }
}
