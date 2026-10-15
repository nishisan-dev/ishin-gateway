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
package dev.nishisan.ishin.gateway.cluster;

import dev.nishisan.utils.ngrid.cluster.transport.JacksonMessageCodec;
import dev.nishisan.utils.ngrid.common.ClusterMessage;
import dev.nishisan.utils.ngrid.common.MessageType;
import dev.nishisan.utils.ngrid.common.NodeId;
import java.util.HashMap;
import java.io.IOException;
import dev.nishisan.ishin.gateway.rules.RulesBundle;
import dev.nishisan.utils.ngrid.map.MapReplicationCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClusterReplicationCodecTest {

    @Test
    @DisplayName("Codec NGrid preserva datas e scripts do bundle de regras")
    void preservesRulesBundleDuringReplication() throws IOException {
        var deployedAt = Instant.parse("2026-10-15T12:34:56.123456789Z");
        byte[] script = "println 'replicado'".getBytes(StandardCharsets.UTF_8);
        var bundle = new RulesBundle(7L, deployedAt, "test", Map.of("default/Rules.groovy", script));

        var transportCodec = new JacksonMessageCodec();
        var message = ClusterMessage.request(
                MessageType.CLIENT_RESPONSE,
                "test", NodeId.of("node1"),
                NodeId.of("node2"), bundle);
        var transported = assertInstanceOf(RulesBundle.class,
                transportCodec.decode(transportCodec.encode(message)).payload());
        assertEquals(deployedAt, transported.deployedAt());
        assertArrayEquals(script, transported.scripts().get("default/Rules.groovy"));

        var snapshot = MapReplicationCodec.decodeSnapshot(
                MapReplicationCodec.encodeSnapshot(new HashMap<>(Map.of("active", bundle))));
        var restored = assertInstanceOf(RulesBundle.class, snapshot.get("active"));

        assertEquals(bundle.version(), restored.version());
        assertEquals(deployedAt, restored.deployedAt());
        assertEquals(bundle.deployedBy(), restored.deployedBy());
        assertEquals(bundle.scripts().keySet(), restored.scripts().keySet());
        assertArrayEquals(script, restored.scripts().get("default/Rules.groovy"));
    }
}
