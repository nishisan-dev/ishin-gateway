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
package dev.nishisan.ngate.configuration;

/**
 * Configuração do Tunnel Mode.
 * <p>
 * Quando {@code mode: tunnel} no {@code adapter.yaml}, este bloco define
 * os parâmetros do load balancer TCP L4.
 * <p>
 * Quando {@code mode: proxy}, o sub-bloco {@code registration} controla
 * o registro deste proxy no túnel via NGrid.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class TunnelConfiguration {

    /**
     * Algoritmo de load balancing global.
     * Valores: "round-robin", "least-connections", "weighted-round-robin".
     */
    private String loadBalancing = "round-robin";

    /**
     * Quantidade de keepalives perdidos consecutivos tolerados antes
     * de remover um membro do pool.
     * Timeout efetivo = keepaliveInterval (do proxy) × missedKeepalives.
     */
    private int missedKeepalives = 3;

    /**
     * Tempo máximo (segundos) para aguardar conexões ativas drenarem
     * durante shutdown graceful de um membro.
     */
    private int drainTimeout = 30;

    /**
     * Endereço de bind para os listeners TCP virtuais.
     */
    private String bindAddress = "0.0.0.0";

    /**
     * Se true, promove automaticamente membros STANDBY para ACTIVE
     * quando todos os membros ACTIVE de um grupo morrerem.
     */
    private boolean autoPromoteStandby = true;

    /**
     * Configuração de registro do proxy no túnel (usado quando mode=proxy).
     */
    private TunnelRegistrationConfiguration registration;

    public String getLoadBalancing() {
        return loadBalancing;
    }

    public void setLoadBalancing(String loadBalancing) {
        this.loadBalancing = loadBalancing;
    }

    public int getMissedKeepalives() {
        return missedKeepalives;
    }

    public void setMissedKeepalives(int missedKeepalives) {
        this.missedKeepalives = missedKeepalives;
    }

    public int getDrainTimeout() {
        return drainTimeout;
    }

    public void setDrainTimeout(int drainTimeout) {
        this.drainTimeout = drainTimeout;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public boolean isAutoPromoteStandby() {
        return autoPromoteStandby;
    }

    public void setAutoPromoteStandby(boolean autoPromoteStandby) {
        this.autoPromoteStandby = autoPromoteStandby;
    }

    public TunnelRegistrationConfiguration getRegistration() {
        return registration;
    }

    public void setRegistration(TunnelRegistrationConfiguration registration) {
        this.registration = registration;
    }
}
