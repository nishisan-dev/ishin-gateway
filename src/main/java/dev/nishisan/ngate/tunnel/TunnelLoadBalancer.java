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

import java.util.List;

/**
 * Interface para algoritmos de load balancing do Tunnel Mode.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public interface TunnelLoadBalancer {

    /**
     * Seleciona o próximo membro ativo para receber uma conexão.
     *
     * @param activeMembers lista de membros ativos elegíveis (não vazia)
     * @return o membro selecionado
     */
    BackendMember select(List<BackendMember> activeMembers);

    /**
     * Factory method para criar o load balancer a partir do nome do algoritmo.
     */
    static TunnelLoadBalancer forAlgorithm(String algorithm) {
        return switch (algorithm.toLowerCase()) {
            case "least-connections" ->
                    new dev.nishisan.ngate.tunnel.lb.LeastConnectionsBalancer();
            case "weighted-round-robin" ->
                    new dev.nishisan.ngate.tunnel.lb.WeightedRoundRobinBalancer();
            default -> // "round-robin" e qualquer valor desconhecido
                    new dev.nishisan.ngate.tunnel.lb.RoundRobinBalancer();
        };
    }
}
