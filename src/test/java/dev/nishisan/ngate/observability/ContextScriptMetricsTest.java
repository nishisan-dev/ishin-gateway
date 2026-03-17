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
package dev.nishisan.ngate.observability;

import dev.nishisan.ngate.dashboard.collector.MetricsCollectorService;
import dev.nishisan.ngate.dashboard.storage.DashboardStorageService;
import dev.nishisan.ngate.configuration.DashboardConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para as métricas de contexto HTTP e script Groovy
 * adicionadas ao {@link ProxyMetrics}.
 * <p>
 * Valida registro de Counter + Timer, presença de tags corretas,
 * coleta pelo {@link MetricsCollectorService} e persistência no
 * {@link DashboardStorageService}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContextScriptMetricsTest {

    private MeterRegistry registry;
    private ProxyMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ProxyMetrics(registry);
    }

    // ─── Context Metrics ────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("recordContextRequest registra Counter com tags corretas")
    void testContextRequestCounter() {
        metrics.recordContextRequest("http-noauth", "default", "GET", 200, 50);
        metrics.recordContextRequest("http-noauth", "default", "GET", 200, 30);

        Counter counter = registry.find("ngate.context.requests.total")
                .tag("listener", "http-noauth")
                .tag("context", "default")
                .tag("method", "GET")
                .tag("status", "200")
                .counter();

        assertNotNull(counter, "Counter ngate.context.requests.total deve existir");
        assertEquals(2.0, counter.count(), "Deve ter 2 incrementos");
    }

    @Test
    @Order(2)
    @DisplayName("recordContextRequest registra Timer com tags corretas")
    void testContextRequestTimer() {
        metrics.recordContextRequest("http-noauth", "api-users", "POST", 201, 120);

        Timer timer = registry.find("ngate.context.duration")
                .tag("listener", "http-noauth")
                .tag("context", "api-users")
                .tag("method", "POST")
                .timer();

        assertNotNull(timer, "Timer ngate.context.duration deve existir");
        assertEquals(1, timer.count(), "Deve ter 1 registro");
        assertTrue(timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS) > 0,
                "Mean deve ser > 0");
    }

    @Test
    @Order(3)
    @DisplayName("recordContextError registra Counter de erros")
    void testContextErrorCounter() {
        metrics.recordContextError("http-noauth", "default", "GET");

        Counter counter = registry.find("ngate.context.errors")
                .tag("listener", "http-noauth")
                .tag("context", "default")
                .tag("method", "GET")
                .counter();

        assertNotNull(counter, "Counter ngate.context.errors deve existir");
        assertEquals(1.0, counter.count());
    }

    // ─── Script Metrics ─────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("recordScriptExecution registra Counter + Timer por script")
    void testScriptExecutionMetrics() {
        metrics.recordScriptExecution("http-noauth", "default", "default/Rules.groovy", 15);
        metrics.recordScriptExecution("http-noauth", "default", "default/Rules.groovy", 25);

        Counter counter = registry.find("ngate.script.executions.total")
                .tag("listener", "http-noauth")
                .tag("context", "default")
                .tag("script", "default/Rules.groovy")
                .counter();

        assertNotNull(counter, "Counter ngate.script.executions.total deve existir");
        assertEquals(2.0, counter.count());

        Timer timer = registry.find("ngate.script.duration")
                .tag("listener", "http-noauth")
                .tag("context", "default")
                .tag("script", "default/Rules.groovy")
                .timer();

        assertNotNull(timer, "Timer ngate.script.duration deve existir");
        assertEquals(2, timer.count());
    }

    @Test
    @Order(5)
    @DisplayName("recordScriptError registra Counter de erros por script")
    void testScriptErrorCounter() {
        metrics.recordScriptError("http-noauth", "default", "default/Rules.groovy");

        Counter counter = registry.find("ngate.script.errors")
                .tag("listener", "http-noauth")
                .tag("context", "default")
                .tag("script", "default/Rules.groovy")
                .counter();

        assertNotNull(counter, "Counter ngate.script.errors deve existir");
        assertEquals(1.0, counter.count());
    }

    // ─── Diferentes contextos geram séries distintas ─────────────────────

    @Test
    @Order(6)
    @DisplayName("Contextos diferentes geram Counters independentes")
    void testDifferentContextsAreIsolated() {
        metrics.recordContextRequest("http-noauth", "ctx-a", "GET", 200, 10);
        metrics.recordContextRequest("http-noauth", "ctx-b", "GET", 200, 20);

        Counter counterA = registry.find("ngate.context.requests.total")
                .tag("context", "ctx-a")
                .counter();
        Counter counterB = registry.find("ngate.context.requests.total")
                .tag("context", "ctx-b")
                .counter();

        assertNotNull(counterA);
        assertNotNull(counterB);
        assertEquals(1.0, counterA.count());
        assertEquals(1.0, counterB.count());
    }

    // ─── MetricsCollectorService consegue coletar as novas métricas ─────

    @Test
    @Order(7)
    @DisplayName("getCurrentMetrics retorna métricas de context e script")
    void testCollectorSeesNewMetrics() {
        // Stub de storage (não persiste nada neste teste)
        DashboardStorageService storage = createInMemoryStorage();
        MetricsCollectorService collector = new MetricsCollectorService(registry, storage);

        // Gerar métricas
        metrics.recordContextRequest("http-noauth", "default", "GET", 200, 50);
        metrics.recordScriptExecution("http-noauth", "default", "default/Rules.groovy", 10);

        Map<String, Object> current = collector.getCurrentMetrics();

        // Verificar que as métricas de contexto e script aparecem no mapa
        boolean hasContextCounter = current.keySet().stream()
                .anyMatch(k -> k.startsWith("ngate.context.requests.total"));
        boolean hasContextTimer = current.keySet().stream()
                .anyMatch(k -> k.startsWith("ngate.context.duration"));
        boolean hasScriptCounter = current.keySet().stream()
                .anyMatch(k -> k.startsWith("ngate.script.executions.total"));
        boolean hasScriptTimer = current.keySet().stream()
                .anyMatch(k -> k.startsWith("ngate.script.duration"));

        assertTrue(hasContextCounter, "getCurrentMetrics deve conter ngate.context.requests.total");
        assertTrue(hasContextTimer, "getCurrentMetrics deve conter ngate.context.duration");
        assertTrue(hasScriptCounter, "getCurrentMetrics deve conter ngate.script.executions.total");
        assertTrue(hasScriptTimer, "getCurrentMetrics deve conter ngate.script.duration");
    }

    // ─── Persistência no DashboardStorageService ────────────────────────

    @Test
    @Order(8)
    @DisplayName("Métricas de contexto/script persistem e são recuperáveis no H2")
    void testPersistenceInStorage() {
        DashboardStorageService storage = createInMemoryStorage();
        MetricsCollectorService collector = new MetricsCollectorService(registry, storage);

        // Gerar métricas
        metrics.recordContextRequest("http-noauth", "default", "GET", 200, 100);
        metrics.recordScriptExecution("http-noauth", "default", "default/Rules.groovy", 25);

        // Coletar (persiste no storage)
        collector.collect();

        // Verificar que as métricas foram persistidas
        List<String> names = storage.listMetricNames();
        assertTrue(names.stream().anyMatch(n -> n.startsWith("ngate.context.")),
                "Storage deve conter métricas ngate.context.*");
        assertTrue(names.stream().anyMatch(n -> n.startsWith("ngate.script.")),
                "Storage deve conter métricas ngate.script.*");

        // Verificar que o histórico retorna dados
        Instant from = Instant.now().minusSeconds(60);
        Instant to = Instant.now().plusSeconds(60);

        List<DashboardStorageService.SeriesRecord> contextRecords =
                storage.queryMetrics("ngate.context.requests.total", from, to);
        assertFalse(contextRecords.isEmpty(),
                "Deve retornar registros de ngate.context.requests.total no histórico");

        // Timer: persistido como .mean, .max, .count
        List<DashboardStorageService.SeriesRecord> timerMeanRecords =
                storage.queryMetrics("ngate.context.duration.mean", from, to);
        assertFalse(timerMeanRecords.isEmpty(),
                "Deve retornar registros de ngate.context.duration.mean no histórico");

        List<DashboardStorageService.SeriesRecord> timerMaxRecords =
                storage.queryMetrics("ngate.context.duration.max", from, to);
        assertFalse(timerMaxRecords.isEmpty(),
                "Deve retornar registros de ngate.context.duration.max no histórico");
    }

    // ─── Helper: cria DashboardStorageService in-memory ─────────────────

    private DashboardStorageService createInMemoryStorage() {
        String tempDir = System.getProperty("java.io.tmpdir") + "/ngate-test-" + System.nanoTime();
        new File(tempDir).mkdirs();
        DashboardConfiguration.DashboardStorageConfiguration storageConfig =
                new DashboardConfiguration.DashboardStorageConfiguration();
        storageConfig.setPath(tempDir);
        return new DashboardStorageService(storageConfig);
    }
}
