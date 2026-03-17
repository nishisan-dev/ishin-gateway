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
package dev.nishisan.ishin.gateway.ratelimit;

import dev.nishisan.ishin.gateway.configuration.RateLimitConfiguration;
import dev.nishisan.ishin.gateway.configuration.RateLimitRefConfiguration;
import dev.nishisan.ishin.gateway.configuration.RateLimitZoneConfiguration;
import dev.nishisan.ishin.gateway.http.ratelimit.RateLimitManager;
import dev.nishisan.ishin.gateway.http.ratelimit.RateLimitResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do {@link RateLimitManager}.
 * Valida os modos nowait e stall, isolamento de zonas,
 * e comportamento quando desabilitado.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitManagerTest {

    private RateLimitManager manager;

    @BeforeEach
    void setup() {
        manager = new RateLimitManager(new SimpleMeterRegistry());
    }

    @Test
    @Order(1)
    @DisplayName("T1: Disabled — todos os requests passam quando rate limiting está desabilitado")
    void testDisabledAllowsAll() {
        // Não chama configure() — manager está desabilitado por padrão
        assertFalse(manager.isEnabled());

        RateLimitRefConfiguration ref = new RateLimitRefConfiguration();
        ref.setZone("test-zone");
        ref.setMode("nowait");

        for (int i = 0; i < 100; i++) {
            assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("test", ref),
                    "Should ALLOW all requests when disabled");
        }
    }

    @Test
    @Order(2)
    @DisplayName("T2: Disabled — config com enabled=false retorna ALLOWED")
    void testExplicitlyDisabled() {
        RateLimitConfiguration config = new RateLimitConfiguration();
        config.setEnabled(false);
        manager.configure(config);

        assertFalse(manager.isEnabled());

        RateLimitRefConfiguration ref = new RateLimitRefConfiguration();
        ref.setZone("test-zone");
        assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("test", ref));
    }

    @Test
    @Order(3)
    @DisplayName("T3: Null ref — ALLOWED quando ref é null")
    void testNullRefAllowed() {
        configureWithZone("test-zone", 5, 1, 1);
        assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("test", null));
    }

    @Test
    @Order(4)
    @DisplayName("T4: Nowait — dentro do limite, requests são ALLOWED")
    void testNowaitWithinLimit() {
        configureWithZone("api-test", 10, 1, 1);

        RateLimitRefConfiguration ref = new RateLimitRefConfiguration();
        ref.setZone("api-test");
        ref.setMode("nowait");

        // 10 requests dentro do limite
        for (int i = 0; i < 10; i++) {
            assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("listener:http", ref),
                    "Request " + i + " should be ALLOWED");
        }
    }

    @Test
    @Order(5)
    @DisplayName("T5: Nowait — acima do limite, requests são REJECTED")
    void testNowaitExceedsLimit() {
        configureWithZone("api-strict", 5, 1, 1);

        RateLimitRefConfiguration ref = new RateLimitRefConfiguration();
        ref.setZone("api-strict");
        ref.setMode("nowait");

        // Consome todos os 5 permits
        for (int i = 0; i < 5; i++) {
            assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("listener:test", ref));
        }

        // Próximo deve ser REJECTED
        assertEquals(RateLimitResult.REJECTED, manager.acquirePermission("listener:test", ref),
                "Should REJECT when limit exceeded in nowait mode");
    }

    @Test
    @Order(6)
    @DisplayName("T6: Zonas independentes — não interferem entre si")
    void testZoneIsolation() {
        Map<String, RateLimitZoneConfiguration> zones = new HashMap<>();

        RateLimitZoneConfiguration zone1 = new RateLimitZoneConfiguration();
        zone1.setLimitForPeriod(3);
        zone1.setLimitRefreshPeriodSeconds(1);
        zone1.setTimeoutSeconds(1);
        zones.put("zone-a", zone1);

        RateLimitZoneConfiguration zone2 = new RateLimitZoneConfiguration();
        zone2.setLimitForPeriod(3);
        zone2.setLimitRefreshPeriodSeconds(1);
        zone2.setTimeoutSeconds(1);
        zones.put("zone-b", zone2);

        RateLimitConfiguration config = new RateLimitConfiguration();
        config.setEnabled(true);
        config.setDefaultMode("nowait");
        config.setZones(zones);
        manager.configure(config);

        RateLimitRefConfiguration refA = new RateLimitRefConfiguration();
        refA.setZone("zone-a");

        RateLimitRefConfiguration refB = new RateLimitRefConfiguration();
        refB.setZone("zone-b");

        // Consome zona A completamente
        for (int i = 0; i < 3; i++) {
            assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("listener:x", refA));
        }
        assertEquals(RateLimitResult.REJECTED, manager.acquirePermission("listener:x", refA),
                "Zone A should be exhausted");

        // Zona B deve estar intacta
        for (int i = 0; i < 3; i++) {
            assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("listener:y", refB),
                    "Zone B should be independent from Zone A");
        }
    }

    @Test
    @Order(7)
    @DisplayName("T7: DefaultMode — usa defaultMode quando ref.mode é null")
    void testDefaultMode() {
        configureWithZone("test-default", 2, 1, 1);

        RateLimitRefConfiguration ref = new RateLimitRefConfiguration();
        ref.setZone("test-default");
        ref.setMode(null); // usa defaultMode (nowait)

        // 2 permitidos
        assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("scope:a", ref));
        assertEquals(RateLimitResult.ALLOWED, manager.acquirePermission("scope:a", ref));
        // 3º deve ser rejeitado (nowait é o default)
        assertEquals(RateLimitResult.REJECTED, manager.acquirePermission("scope:a", ref));
    }

    @Test
    @Order(8)
    @DisplayName("T8: getZoneTimeoutSeconds — retorna timeout correto da zona")
    void testGetZoneTimeoutSeconds() {
        configureWithZone("timeout-test", 10, 1, 7);
        assertEquals(7, manager.getZoneTimeoutSeconds("timeout-test"));
    }

    @Test
    @Order(9)
    @DisplayName("T9: getZoneTimeoutSeconds — retorna default (5) para zona inexistente")
    void testGetZoneTimeoutSecondsDefault() {
        configureWithZone("exists", 10, 1, 3);
        assertEquals(5, manager.getZoneTimeoutSeconds("nonexistent"));
    }

    // ─── Helper ──────────────────────────────────────────────────────────

    private void configureWithZone(String zoneName, int limit, int refreshSeconds, int timeoutSeconds) {
        RateLimitZoneConfiguration zone = new RateLimitZoneConfiguration();
        zone.setLimitForPeriod(limit);
        zone.setLimitRefreshPeriodSeconds(refreshSeconds);
        zone.setTimeoutSeconds(timeoutSeconds);

        Map<String, RateLimitZoneConfiguration> zones = new HashMap<>();
        zones.put(zoneName, zone);

        RateLimitConfiguration config = new RateLimitConfiguration();
        config.setEnabled(true);
        config.setDefaultMode("nowait");
        config.setZones(zones);

        manager.configure(config);
    }
}
