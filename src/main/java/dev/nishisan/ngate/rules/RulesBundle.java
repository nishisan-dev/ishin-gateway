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
package dev.nishisan.ngate.rules;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * Bundle atômico de scripts Groovy para deploy via Admin API.
 * <p>
 * Todos os scripts são empacotados juntos — deploy é tudo-ou-nada.
 * Serializable para transporte via {@code DistributedMap} do NGrid.
 *
 * @param version    Número sequencial do deploy (monotônico crescente)
 * @param deployedAt Timestamp do deploy
 * @param deployedBy Identificador de quem realizou o deploy (e.g., "cli", "api")
 * @param scripts    Mapa de path relativo → conteúdo do script como bytes.
 *                   Exemplo: "default/Rules.groovy" → bytes
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
public record RulesBundle(
        long version,
        Instant deployedAt,
        String deployedBy,
        Map<String, byte[]> scripts
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
