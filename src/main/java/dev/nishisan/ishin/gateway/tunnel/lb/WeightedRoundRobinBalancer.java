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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smooth Weighted Round-Robin (SWRR) load balancer.
 * <p>
 * Distribui tráfego proporcionalmente ao peso de cada membro,
 * produzindo distribuição suave sem bursts.
 * <p>
 * Algoritmo baseado no NGINX upstream SWRR.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class WeightedRoundRobinBalancer implements TunnelLoadBalancer {

    /**
     * "Current weights" por membro — indexado pelo key do membro.
     * Como a lista de membros pode mudar, usamos fallback para round-robin
     * simples na primeira iteração de um membro novo.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> currentWeights =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public BackendMember select(List<BackendMember> activeMembers) {
        if (activeMembers.size() == 1) {
            return activeMembers.getFirst();
        }

        int totalWeight = 0;
        BackendMember best = null;
        int bestCurrentWeight = Integer.MIN_VALUE;

        for (BackendMember member : activeMembers) {
            int effectiveWeight = member.getWeight();
            totalWeight += effectiveWeight;

            // Incrementar current weight pelo effective weight
            AtomicInteger cw = currentWeights.computeIfAbsent(member.getKey(), k -> new AtomicInteger(0));
            int newCw = cw.addAndGet(effectiveWeight);

            if (newCw > bestCurrentWeight) {
                bestCurrentWeight = newCw;
                best = member;
            }
        }

        // Decrementar o selecionado pelo totalWeight (SWRR step)
        if (best != null) {
            currentWeights.get(best.getKey()).addAndGet(-totalWeight);
        }

        return best;
    }
}
