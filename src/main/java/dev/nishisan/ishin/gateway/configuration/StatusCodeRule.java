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
 * Regra de passive health check para um status code HTTP específico.
 * <p>
 * Define quantas ocorrências ({@code maxOccurrences}) de um determinado status
 * code são toleradas dentro de uma janela temporal deslizante
 * ({@code slidingWindowSeconds}). Se o limiar for atingido, o membro é
 * marcado como passivamente DOWN.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-11
 */
public class StatusCodeRule {

    /**
     * Número máximo de ocorrências do status code dentro da janela
     * antes de marcar o membro como DOWN.
     */
    private int maxOccurrences = 5;

    /**
     * Tamanho da janela deslizante em segundos.
     */
    private int slidingWindowSeconds = 60;

    public int getMaxOccurrences() {
        return maxOccurrences;
    }

    public void setMaxOccurrences(int maxOccurrences) {
        this.maxOccurrences = maxOccurrences;
    }

    public int getSlidingWindowSeconds() {
        return slidingWindowSeconds;
    }

    public void setSlidingWindowSeconds(int slidingWindowSeconds) {
        this.slidingWindowSeconds = slidingWindowSeconds;
    }

    @Override
    public String toString() {
        return "StatusCodeRule{maxOccurrences=" + maxOccurrences
                + ", slidingWindowSeconds=" + slidingWindowSeconds + "}";
    }
}
