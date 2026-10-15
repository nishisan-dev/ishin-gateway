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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import dev.nishisan.ishin.gateway.test.GatewayTestImage;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Valida comutação e reintegração de três nós com tráfego OAuth e atualização
 * de regras e tokens enquanto o antigo líder permanece desligado.
 */
class NGridClusterFailoverIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NGridClusterFailoverIntegrationTest.class);
    private static final ObjectMapper json = new ObjectMapper();
    private static final String API_KEY = "test-failover-key";
    private static final String CONFIG = "adapter-test-cluster-failover.yaml";
    private static final int MGMT_PORT = 9190;
    private static final int PROXY_PORT = 9091;
    private static final int LATE_PROXY_PORT = 9092;
    private static final ImageFromDockerfile image = GatewayTestImage.create("ishin-failover-test");
    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(8))
            .build();

    enum ShutdownMode { GRACEFUL, CRASH }

    private Network network;
    private GenericContainer<?> keycloak;
    private GenericContainer<?> backend;
    private final List<GenericContainer<?>> nodes = new ArrayList<>();

    @BeforeEach
    void startCluster() {
        network = Network.newNetwork();
        keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
                .withNetwork(network).withNetworkAliases("keycloak")
                .withCopyFileToContainer(MountableFile.forClasspathResource("testcontainers/realm-failover.json"),
                        "/opt/keycloak/data/import/realm.json")
                .withCommand("start-dev", "--import-realm")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/inventory-dev").forPort(8080)
                        .withStartupTimeout(Duration.ofSeconds(120)));
        backend = new GenericContainer<>("nginx:alpine")
                .withNetwork(network).withNetworkAliases("mock-backend")
                .withCopyFileToContainer(MountableFile.forClasspathResource("testcontainers/mock-backend-failover.conf"),
                        "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(8080).dependsOn(keycloak)
                .waitingFor(Wait.forHttp("/health").forPort(8080));
        for (int i = 1; i <= 3; i++) {
            String id = "ishin-failover-node" + i;
            nodes.add(new GenericContainer<>(image)
                    .withNetwork(network).withNetworkAliases(id)
                    .withEnv("SPRING_PROFILES_DEFAULT", "bench")
                    .withEnv("SERVER_PORT", "9190")
                    .withEnv("MANAGEMENT_PORT", "9190")
                    .withEnv("ISHIN_CONFIG", "/app/config/" + CONFIG)
                    .withEnv("ISHIN_CLUSTER_NODE_ID", id)
                    .withEnv("ISHIN_CLUSTER_HOST", id)
                    .withEnv("ISHIN_INSTANCE_ID", id)
                    .withCopyFileToContainer(MountableFile.forClasspathResource(CONFIG), "/app/config/" + CONFIG)
                    .withExposedPorts(PROXY_PORT, LATE_PROXY_PORT, MGMT_PORT, 7100)
                    .dependsOn(backend)
                    .waitingFor(Wait.forHttp("/actuator/health").forPort(MGMT_PORT)
                            .withStartupTimeout(Duration.ofSeconds(120)))
                    .withLogConsumer(new Slf4jLogConsumer(log).withPrefix(id)));
        }
        Startables.deepStart(nodes.stream()).join();
    }

    @AfterEach
    void stopCluster() {
        for (var node : nodes) {
            node.stop();
        }
        if (backend != null) backend.stop();
        if (keycloak != null) keycloak.stop();
        if (network != null) network.close();
    }

    @ParameterizedTest(name = "Comutação e retorno do líder: {0}")
    @EnumSource(ShutdownMode.class)
    @DisplayName("Três nós mantêm serviço e sincronizam regras e tokens após o retorno do líder")
    void failsOverAndRejoins(ShutdownMode mode) throws Exception {
        try {
            runFailoverCycle(mode);
        } catch (Exception | AssertionError failure) {
            log.error("Falha no ciclo {}", mode, failure);
            for (var node : nodes) {
                String logs = node.getLogs();
                log.error("Diagnóstico {}: {}", node.getNetworkAliases(),
                        logs.substring(Math.max(0, logs.length() - 24000)));
            }
            throw failure;
        }
    }

    private void runFailoverCycle(ShutdownMode mode) throws Exception {
        awaitTopology(nodes, 3);
        var originalLeader = leader(nodes);
        String originalId = nodeId(originalLeader);
        long initialVersion = deploy(originalLeader, "before-failover");
        awaitRules(nodes, initialVersion, "before-failover");

        // Aquecer o cache de cada sobrevivente sem permitir logins independentes.
        String initialToken = requestProxy(originalLeader, PROXY_PORT, "before-failover");
        for (var node : nodes) {
            assertEquals(initialToken, requestProxy(node, PROXY_PORT, "before-failover"));
        }
        assertEquals(1, loginCount(), "O token inicial deve ser compartilhado pelos três nós");
        log.info("Token inicial confirmado nos três nós com apenas um login OAuth");
        var survivors = nodes.stream().filter(node -> node != originalLeader).toList();
        var failures = new ConcurrentLinkedQueue<String>();
        var samples = new AtomicInteger();

        try (var traffic = Executors.newSingleThreadScheduledExecutor()) {
            traffic.scheduleWithFixedDelay(() -> {
                for (var node : survivors) {
                    try (Response response = http.newCall(new Request.Builder()
                            .url(url(node, PROXY_PORT, "/authenticated")).build()).execute()) {
                        if (response.code() != 200) failures.add(nodeId(node) + ": HTTP " + response.code());
                        samples.incrementAndGet();
                    } catch (Exception ex) {
                        failures.add(nodeId(node) + ": " + ex.getClass().getSimpleName());
                    }
                }
            }, 0, 200, TimeUnit.MILLISECONDS);
            await().atMost(Duration.ofSeconds(10)).until(() -> samples.get() >= 4);

            log.info("Desligando líder {} via {}", originalId, mode);
            String containerId = originalLeader.getContainerId();
            if (mode == ShutdownMode.CRASH) {
                originalLeader.getDockerClient().killContainerCmd(containerId).withSignal("SIGKILL").exec();
            } else {
                originalLeader.getDockerClient().stopContainerCmd(containerId).withTimeout(30).exec();
            }
            awaitTopology(survivors, 2);
            var newLeader = leader(survivors);
            String electedId = nodeId(newLeader);
            assertNotEquals(originalId, electedId);
            log.info("Novo líder confirmado: {}", electedId);

            // Estes dados ainda não existem no disco nem no cache do nó desligado.
            long updatedVersion = deploy(newLeader, "during-failover");
            assertTrue(updatedVersion > initialVersion, "A nova liderança deve preservar a sequência de versões");
            awaitRules(survivors, updatedVersion, "during-failover");
            String lateToken = requestProxy(newLeader, LATE_PROXY_PORT, "during-failover");
            assertNotEquals(initialToken, lateToken, "O token adquirido durante a ausência deve ser novo");
            for (var node : survivors) {
                assertEquals(lateToken, requestProxy(node, LATE_PROXY_PORT, "during-failover"));
            }
            assertEquals(2, loginCount(), "Apenas um login por configuração SSO");

            // Reiniciar o mesmo container preserva identidade, filesystem e estado NGrid.
            originalLeader.getDockerClient().startContainerCmd(containerId).exec();
            awaitTopology(nodes, 3);
            // A afinidade pode devolver a liderança ao nó que retornou após a sincronização.
            var stableLeader = new AtomicReference<String>();
            await().atMost(Duration.ofSeconds(60)).during(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                        assertTopology(nodes, 3);
                        String observed = nodeId(leader(nodes));
                        String previous = stableLeader.getAndSet(observed);
                        if (previous != null) {
                            assertEquals(previous, observed, "A liderança deve estabilizar após a reintegração");
                        }
                    });
            log.info("Liderança estabilizada após retorno: {}", stableLeader.get());
            awaitRules(nodes, updatedVersion, "during-failover");
            traffic.shutdown();
            assertTrue(traffic.awaitTermination(15, TimeUnit.SECONDS));
            var checks = new ArrayList<Executable>();
            for (var node : nodes) {
                checks.add(() -> assertEquals(initialToken, requestProxy(node, PROXY_PORT, "during-failover"),
                        "Token inicial deve ser recuperado em " + nodeId(node)));
                checks.add(() -> assertEquals(lateToken, requestProxy(node, LATE_PROXY_PORT, "during-failover"),
                        "Token criado durante a ausência deve ser recuperado em " + nodeId(node)));
            }
            checks.add(() -> {
                int logins = loginCount();
                log.info("Resultado {}: {} amostras HTTP, {} falhas HTTP, {} logins OAuth (esperado: 2)",
                        mode, samples.get(), failures.size(), logins);
                assertEquals(2, logins, "O nó reintegrado deve ler os tokens do cluster sem novo login");
            });
            checks.add(() -> assertTrue(samples.get() >= 10, "Deve haver tráfego durante a comutação e o retorno"));
            checks.add(() -> assertTrue(failures.isEmpty(), "Falhas no tráfego dos sobreviventes: " + failures));
            assertAll("Estado após reintegração em " + mode, checks);
        }
        log.info("Ciclo {} aprovado: {} amostras HTTP, líder {} reintegrado, regras v{} e tokens sincronizados",
                mode, samples.get(), originalId, initialVersion + 1);
    }

    private void awaitTopology(List<GenericContainer<?>> members, int expected) {
        await().atMost(Duration.ofSeconds(90)).ignoreExceptionsInstanceOf(IOException.class)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertTopology(members, expected));
    }

    private void assertTopology(List<GenericContainer<?>> members, int expected) throws IOException {
        int leaders = 0;
        for (var node : members) {
            JsonNode state = details(node);
            assertTrue(state.path("clusterMode").asBoolean(), "O nó deve permanecer em modo cluster");
            assertEquals(expected, state.path("activeMembers").asInt(), "Todos os nós devem enxergar a mesma composição");
            if (state.path("isLeader").asBoolean()) leaders++;
        }
        assertEquals(1, leaders, "Deve existir exatamente um líder");
    }

    private GenericContainer<?> leader(List<GenericContainer<?>> members) throws IOException {
        for (var node : members) {
            if (details(node).path("isLeader").asBoolean()) return node;
        }
        throw new AssertionError("Nenhum líder encontrado");
    }

    private JsonNode details(GenericContainer<?> node) throws IOException {
        try (Response response = http.newCall(new Request.Builder().url(url(node, MGMT_PORT, "/actuator/health")).build()).execute()) {
            assertEquals(200, response.code());
            JsonNode health = json.readTree(response.body().string());
            for (var component : health.path("components")) {
                if (component.path("details").has("clusterMode")) return component.path("details");
            }
            throw new AssertionError("Health não informou o estado do cluster");
        }
    }

    private long deploy(GenericContainer<?> node, String marker) throws IOException {
        String script = "workload.addResponseProcessor('failover-marker', { wl -> "
                + "wl.clientResponse.addHeader('X-Failover-Rules', '" + marker + "') });";
        var body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("scripts", "default/Rules.groovy",
                        RequestBody.create(script, MediaType.get("text/plain"))).build();
        try (Response response = http.newCall(new Request.Builder().url(url(node, MGMT_PORT, "/admin/rules/deploy"))
                .header("X-API-Key", API_KEY).post(body).build()).execute()) {
            assertEquals(200, response.code(), "O líder deve aceitar a escrita do bundle");
            return json.readTree(response.body().string()).path("version").asLong();
        }
    }

    private void awaitRules(List<GenericContainer<?>> members, long version, String marker) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            for (var node : members) {
                try (Response response = http.newCall(new Request.Builder().url(url(node, MGMT_PORT, "/admin/rules/version"))
                        .header("X-API-Key", API_KEY).build()).execute()) {
                    assertEquals(200, response.code());
                    JsonNode bundle = json.readTree(response.body().string());
                    assertEquals("active", bundle.path("status").asText());
                    assertEquals(version, bundle.path("version").asLong());
                }
            }
        });
        log.info("Bundle {} (v{}) confirmado em {} nós", marker, version, members.size());
    }

    private String requestProxy(GenericContainer<?> node, int port, String marker) throws Exception {
        try (Response response = http.newCall(new Request.Builder().url(url(node, port, "/authenticated")).build()).execute()) {
            assertEquals(200, response.code(), "O backend deve receber o token Bearer");
            assertEquals(marker, response.header("X-Failover-Rules"), "A regra esperada deve ser executada");
            JsonNode payload = json.readTree(response.body().string());
            assertTrue(payload.path("authenticated").asBoolean());
            String authorization = payload.path("authorization").asText();
            assertTrue(authorization.startsWith("Bearer "));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(authorization.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private int loginCount() throws Exception {
        var result = backend.execInContainer("sh", "-c", "wc -l < /tmp/token-requests.log");
        assertEquals(0, result.getExitCode());
        return Integer.parseInt(result.getStdout().trim());
    }

    private String nodeId(GenericContainer<?> node) {
        return node.getEnvMap().get("ISHIN_CLUSTER_NODE_ID");
    }

    private String url(GenericContainer<?> node, int port, String path) {
        // O Docker pode atribuir outra porta publicada ao reiniciar o mesmo container.
        var inspection = node.getDockerClient().inspectContainerCmd(node.getContainerId()).exec();
        String mapped = inspection.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(port))[0].getHostPortSpec();
        return "http://" + node.getHost() + ":" + mapped + path;
    }
}
