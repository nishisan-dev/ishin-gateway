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
package dev.nishisan.ishin.gateway.tunnel.lb;

import dev.nishisan.ishin.gateway.tunnel.BackendMember;
import dev.nishisan.ishin.gateway.tunnel.TunnelLoadBalancer;

import java.util.Comparator;
import java.util.List;

/**
 * Least-Connections load balancer — roteia para o membro com menos conexões TCP ativas.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class LeastConnectionsBalancer implements TunnelLoadBalancer {

    @Override
    public BackendMember select(List<BackendMember> activeMembers) {
        return activeMembers.stream()
                .min(Comparator.comparingInt(m -> m.getActiveConnections().get()))
                .orElse(activeMembers.getFirst());
    }
}
