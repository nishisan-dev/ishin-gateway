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
package dev.nishisan.ngate.http.ratelimit;

/**
 * Resultado da avaliação de rate limiting.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public enum RateLimitResult {

    /**
     * Request permitido sem restrição (dentro do limite ou rate limiting desabilitado).
     */
    ALLOWED,

    /**
     * Request rejeitado imediatamente (modo nowait, limite excedido).
     * Deve resultar em HTTP 429.
     */
    REJECTED,

    /**
     * Request atrasado mas eventualmente permitido (modo stall, aguardou slot).
     */
    DELAYED
}
