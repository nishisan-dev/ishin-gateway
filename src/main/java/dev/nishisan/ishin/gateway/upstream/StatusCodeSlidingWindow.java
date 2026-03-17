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
package dev.nishisan.ishin.gateway.upstream;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Janela deslizante baseada em timestamps para contagem de ocorrências
 * de um status code HTTP dentro de um intervalo temporal.
 * <p>
 * Thread-safe via {@link ConcurrentLinkedDeque} — suporta múltiplas
 * threads registrando/lendo simultaneamente sem locks pesados.
 * <p>
 * Cada instância rastreia UM status code para UM membro do upstream pool.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-11
 */
public class StatusCodeSlidingWindow {

    private final long windowMs;
    private final ConcurrentLinkedDeque<Long> timestamps = new ConcurrentLinkedDeque<>();

    /**
     * @param windowSeconds tamanho da janela em segundos
     */
    public StatusCodeSlidingWindow(int windowSeconds) {
        this.windowMs = windowSeconds * 1000L;
    }

    /**
     * Registra uma ocorrência no instante fornecido e faz eviction
     * dos timestamps expirados.
     *
     * @param timestampMs timestamp da ocorrência em milissegundos
     */
    public void record(long timestampMs) {
        timestamps.addLast(timestampMs);
        evict(timestampMs);
    }

    /**
     * Retorna o número de ocorrências dentro da janela ativa.
     * Faz eviction antes de contar para garantir precisão.
     *
     * @return contagem de ocorrências ativas
     */
    public int count() {
        evict(System.currentTimeMillis());
        return timestamps.size();
    }

    /**
     * Remove timestamps que estão fora da janela deslizante.
     *
     * @param now timestamp de referência
     */
    private void evict(long now) {
        long cutoff = now - windowMs;
        // ConcurrentLinkedDeque.peekFirst()/pollFirst() são thread-safe
        while (true) {
            Long oldest = timestamps.peekFirst();
            if (oldest == null || oldest > cutoff) {
                break;
            }
            timestamps.pollFirst();
        }
    }

    /**
     * Remove todos os registros da janela.
     */
    public void clear() {
        timestamps.clear();
    }

    /**
     * @return tamanho da janela em milissegundos
     */
    public long getWindowMs() {
        return windowMs;
    }
}
