# AGENTS.md — ishin-gateway

## Overview

Java 25 programmable HTTP gateway / reverse proxy. Spring Boot 3.5 bootstraps configuration and Actuator; **Javalin 7 (Jetty 12)** handles inbound HTTP with virtual threads; **OkHttp 4** forwards to backends. Groovy scripts decide routing at runtime. NGrid provides cluster mode (mesh TCP, leader election, DistributedMap). Language: Java. Build: Maven. Config: YAML (`config/adapter.yaml`).

## Architecture & Request Flow

1. `IshinGatewayApplication.main()` → Spring Boot starts → `ApplicationReadyEvent` triggers ordered init:
   - `@Order(10)` `ConfigurationManager` — parses `config/adapter.yaml` into `ServerConfiguration` POJOs
   - `@Order(20)` `ClusterService` — starts NGrid mesh if `cluster.enabled=true`
   - `@Order(30)` `EndpointManager` — creates `EndpointWrapper` per endpoint, one `Javalin` instance per listener, upstream pools, health checkers
   - `@Order(40)` `RulesBundleManager` — hot-reload Groovy scripts, cluster replication
2. Per-request: Javalin handler → B3 trace extraction → JWT/OAuth validation (if `secured`) → Groovy rule execution via `ProtectedBinding` → `HttpProxyManager` forwards via OkHttp → response processors → pipe or buffer response back.

Key classes by layer:
- **Config POJOs**: `configuration/ServerConfiguration.java` (root), `EndPointConfiguration`, `BackendConfiguration`, `ClusterConfiguration`
- **Core proxy**: `http/HttpProxyManager.java` (915 lines — the central proxy engine), `EndpointWrapper.java`, `HttpWorkLoad.java`
- **Groovy sandbox**: `groovy/ProtectedBinding.java` — prevents scripts from overwriting read-only bindings
- **Resilience**: `http/circuit/BackendCircuitBreakerManager.java`, `http/ratelimit/RateLimitManager.java`
- **Upstream LB**: `upstream/UpstreamPool.java`, `UpstreamPoolManager.java`, `UpstreamHealthChecker.java`, `PassiveHealthChecker.java`
- **Observability**: `observability/service/TracerService.java` (Brave/Zipkin), `ProxyMetrics.java` (Micrometer)
- **Cluster**: `cluster/ClusterService.java` (NGrid lifecycle), `rules/RulesBundleManager.java` (deploy + replication)
- **Admin API**: `admin/AdminController.java` — Spring `@RestController` on management port (9190)

## Build & Test Commands

```bash
# Compile
mvn clean compile

# Package (skip tests)
mvn clean package -DskipTests

# Unit tests only (no Docker)
mvn test -Dtest='!*IntegrationTest' -DfailIfNoTests=false

# Integration tests (requires Docker — uses Testcontainers)
mvn test -Dtest='*IntegrationTest'

# Full suite
mvn clean verify

# Run with Docker (standalone)
docker compose up --build

# Run cluster mode (3 nodes + nginx LB)
docker compose -f docker-compose.yml -f docker-compose.cluster.yml up --build -d
```

> **GitHub Packages**: `dev.nishisan:nishi-utils-core` and `nishi-utils-spring` are hosted on GitHub Packages. `settings.xml` must have a GitHub token with `read:packages` scope configured in `~/.m2/settings.xml`.

## Test Conventions

- Unit tests: `*Test.java` — no Docker, fast. Examples: `ConfigurationManagerTest`, `UpstreamPoolTest`, `RateLimitManagerTest`.
- Integration tests: `*IntegrationTest.java` — use **Testcontainers** to build the Docker image and spin up real gateway nodes + mock backends. Examples: `NGridClusterIntegrationTest`, `NGridClusterRulesDeployIntegrationTest`.
- Framework: JUnit 5, `@DisplayName` in Portuguese, `@TestMethodOrder(OrderAnnotation.class)` when order matters, `@TempDir` for I/O tests, **Awaitility** for async polling (never `Thread.sleep`).

## Project Conventions

- **Language**: all production code is Java 25 (no Kotlin sources). Virtual threads via `javalinConfig.concurrency.useVirtualThreads = true`.
- **Logging**: Log4j2 + LMAX Disruptor (async). Use `LogManager.getLogger()`, never SLF4J directly in production code (tests may use SLF4J for Testcontainers log consumers).
- **Configuration**: all runtime config lives in `config/adapter.yaml`, mapped to `configuration/` POJOs via Jackson YAML. Spring `application.properties` handles only Actuator, management port, and profile selection.
- **Profiles**: `dev` (default, DEBUG + tracing) and `bench` (INFO, tracing disabled). Activated via `SPRING_PROFILES_DEFAULT` env var.
- **No Tomcat**: Spring Boot excludes Tomcat; Undertow is the embedded servlet container for the management/Actuator port. Javalin uses its own Jetty 12 for proxy listeners.
- **Groovy rules**: live in `rules/` directory, referenced by `ruleMapping` in `adapter.yaml`. Scripts receive a `ProtectedBinding` with `workload`, `request`, `upstreamRequest`, `context`, and `utils` variables. Never overwrite protected bindings.
- **Javadoc**: write in Brazilian Portuguese (PT-BR), Javadoc format, no `@author`/`@since`/`@version`, no HTML tags, no usage examples. See global copilot instructions for full template.

## Key Files & Directories

| Path | Purpose |
|------|---------|
| `config/adapter.yaml` | Primary runtime configuration (listeners, backends, cluster, circuit breaker, rate limiting) |
| `config/adapter-cluster.yaml` | Cluster-mode config overlay for 3-node setup |
| `rules/default/Rules.groovy` | Default Groovy routing script |
| `custom/` | Custom decoder scripts (loaded by separate `GroovyScriptEngine`) |
| `src/.../manager/EndpointManager.java` | Bootstrap orchestrator — creates Javalin listeners and wires all components |
| `src/.../http/HttpProxyManager.java` | Core proxy engine — OkHttp client, Groovy execution, response piping |
| `src/.../configuration/ServerConfiguration.java` | Root POJO for `adapter.yaml` deserialization |
| `docker-compose.yml` | Standalone dev environment (gateway + Keycloak + Zipkin + static backend) |
| `docker-compose.cluster.yml` | Cluster overlay (3 gateway nodes + nginx LB) |
| `docs/` | Architecture, configuration reference, Groovy rules API, security, observability |
| `debian/` | `.deb` packaging (systemd unit, `ishin-cli`, postinst/postrm scripts) |
| `ishin-gateway-ui/` | Vue/React dashboard UI (Vite + TypeScript) — separate from Java build |

## Ports

| Port | Service |
|------|---------|
| 9090 | Proxy listener (secured, OAuth to upstream) |
| 9091 | Proxy listener (no auth, benchmark) |
| 9190 | Management/Actuator (health, prometheus, admin API) |
| 7100 | NGrid cluster mesh (inter-node TCP) |
| 18080 | Spring Boot embedded server (internal, not exposed) |
