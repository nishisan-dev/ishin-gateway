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
 * Configuração do circuit breaker para proteção de backends.
 * Mapeado do bloco {@code circuitBreaker:} no {@code adapter.yaml}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
public class CircuitBreakerConfiguration {

    private boolean enabled = false;
    private int failureRateThreshold = 50;
    private int waitDurationInOpenState = 60;
    private int slidingWindowSize = 100;
    private int permittedCallsInHalfOpenState = 10;
    private int slowCallDurationThreshold = 5;
    private int slowCallRateThreshold = 80;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(int failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public int getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    public void setWaitDurationInOpenState(int waitDurationInOpenState) {
        this.waitDurationInOpenState = waitDurationInOpenState;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public int getPermittedCallsInHalfOpenState() {
        return permittedCallsInHalfOpenState;
    }

    public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
        this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
    }

    public int getSlowCallDurationThreshold() {
        return slowCallDurationThreshold;
    }

    public void setSlowCallDurationThreshold(int slowCallDurationThreshold) {
        this.slowCallDurationThreshold = slowCallDurationThreshold;
    }

    public int getSlowCallRateThreshold() {
        return slowCallRateThreshold;
    }

    public void setSlowCallRateThreshold(int slowCallRateThreshold) {
        this.slowCallRateThreshold = slowCallRateThreshold;
    }
}
