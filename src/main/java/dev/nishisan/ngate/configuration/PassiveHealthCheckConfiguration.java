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

import java.util.HashMap;
import java.util.Map;

/**
 * Configuração do passive health check para upstream pools.
 * <p>
 * O passive health check monitora as respostas reais do tráfego de produção
 * para detectar membros degradados com base em status codes observados
 * dentro de uma janela temporal deslizante.
 * <p>
 * Cada status code pode ter sua própria regra ({@link StatusCodeRule}) com
 * thresholds e janelas independentes.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-11
 */
public class PassiveHealthCheckConfiguration {

    /**
     * Se true, o passive health check está habilitado para este pool.
     */
    private boolean enabled = false;

    /**
     * Mapa de HTTP status code → regra de sliding window.
     * Exemplo: {503 → StatusCodeRule{maxOccurrences=4, slidingWindowSeconds=60}}
     */
    private Map<Integer, StatusCodeRule> statusCodes = new HashMap<>();

    /**
     * Tempo em segundos que o membro fica DOWN antes de ser
     * automaticamente restaurado para receber tráfego de teste.
     */
    private int recoverySeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<Integer, StatusCodeRule> getStatusCodes() {
        return statusCodes;
    }

    public void setStatusCodes(Map<Integer, StatusCodeRule> statusCodes) {
        this.statusCodes = statusCodes;
    }

    public int getRecoverySeconds() {
        return recoverySeconds;
    }

    public void setRecoverySeconds(int recoverySeconds) {
        this.recoverySeconds = recoverySeconds;
    }

    @Override
    public String toString() {
        return "PassiveHealthCheck{enabled=" + enabled
                + ", statusCodes=" + statusCodes
                + ", recoverySeconds=" + recoverySeconds + "}";
    }
}
