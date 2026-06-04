# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Compile
mvn clean compile

# Package (skip tests)
mvn clean package -DskipTests

# Unit tests only (no Docker needed)
mvn test -Dtest='!*IntegrationTest' -DfailIfNoTests=false

# Single unit test
mvn test -Dtest='ConfigurationManagerTest'

# Integration tests (requires Docker — Testcontainers builds the image)
mvn test -Dtest='*IntegrationTest'

# Single integration test
mvn test -Dtest='NGridClusterIntegrationTest'

# Full suite
mvn clean verify

# Run locally via Docker Compose
docker compose up --build

# Run cluster mode (3 nodes + nginx LB)
docker compose -f docker-compose.yml -f docker-compose.cluster.yml up --build -d
```

**GitHub Packages**: `dev.nishisan:nishi-utils-core` e `nishi-utils-spring` requerem `~/.m2/settings.xml` com token GitHub (`read:packages`).

## Architecture

Java 25 programmable HTTP gateway / reverse proxy. Spring Boot 3.5 bootstraps e Actuator; **Javalin 7 (Jetty 12)** recebe HTTP com virtual threads; **OkHttp 4** encaminha para backends. Groovy scripts decidem roteamento em runtime. NGrid provê cluster mode (mesh TCP, leader election, DistributedMap).

### Startup Sequence (ordered)

1. `@Order(10)` **ConfigurationManager** — parse `config/adapter.yaml` → POJOs em `configuration/`
2. `@Order(20)` **ClusterService** — inicia NGrid mesh se `cluster.enabled=true`
3. `@Order(30)` **EndpointManager** — cria `Javalin` por listener, upstream pools, health checkers
4. `@Order(40)` **RulesBundleManager** — hot-reload Groovy scripts, replicação cluster

### Request Flow

Javalin handler → B3 trace extraction → JWT/OAuth validation (se `secured`) → Groovy rule execution via `ProtectedBinding` → `HttpProxyManager` forward via OkHttp → response processors → pipe ou buffer response de volta ao client.

### Key Classes by Layer

- **Config POJOs**: `configuration/ServerConfiguration.java` (root), `EndPointConfiguration`, `BackendConfiguration`, `ClusterConfiguration`
- **Core proxy**: `http/HttpProxyManager.java` (~915 linhas — engine central), `EndpointWrapper.java` (Javalin route handler), `HttpWorkLoad.java` (request context)
- **Groovy sandbox**: `groovy/ProtectedBinding.java` — impede scripts de sobrescrever bindings read-only
- **Resilience**: `http/circuit/BackendCircuitBreakerManager.java` (Resilience4j), `http/ratelimit/RateLimitManager.java` (semaphore engine)
- **Upstream LB**: `upstream/UpstreamPool.java`, `UpstreamPoolManager.java`, `UpstreamHealthChecker.java`, `PassiveHealthChecker.java`
- **Observability**: `observability/service/TracerService.java` (Brave/Zipkin), `ProxyMetrics.java` (Micrometer)
- **Cluster**: `cluster/ClusterService.java` (NGrid lifecycle)
- **Admin API**: `admin/AdminController.java` — Spring `@RestController` na management port

### Ports

| Port  | Service                                        |
|-------|------------------------------------------------|
| 9090  | Proxy listener (secured, OAuth to upstream)    |
| 9091  | Proxy listener (no auth, benchmark)            |
| 9190  | Management/Actuator (health, prometheus, admin) |
| 7100  | NGrid cluster mesh (inter-node TCP)            |
| 18080 | Spring Boot embedded server (internal)         |

## Project Conventions

- **Language**: Java 25 only (no Kotlin). Virtual threads habilitadas no Javalin.
- **No Tomcat**: Spring Boot usa Undertow para management/Actuator. Javalin usa Jetty 12 separado para proxy.
- **Logging**: Log4j2 + LMAX Disruptor (async). Usar `LogManager.getLogger()`, nunca SLF4J direto em produção.
- **Configuration**: runtime config vive em `config/adapter.yaml` → Jackson YAML → POJOs em `configuration/`. Spring `application.properties` apenas para Actuator, management port e profile.
- **Profiles**: `dev` (DEBUG + tracing) e `bench` (INFO, tracing disabled). Ativados via `SPRING_PROFILES_DEFAULT`.
- **Groovy rules**: diretório `rules/`, referenciadas por `ruleMapping` no `adapter.yaml`. Scripts recebem `ProtectedBinding` com `workload`, `request`, `upstreamRequest`, `context`, `utils`.
- **Javadoc**: PT-BR, sem `@author`/`@since`/`@version`, sem HTML tags.

## Test Conventions

- **Unit tests**: `*Test.java` — sem Docker, rápidos. Exemplos: `ConfigurationManagerTest`, `UpstreamPoolTest`, `RateLimitManagerTest`.
- **Integration tests**: `*IntegrationTest.java` — usam **Testcontainers** para buildar a Docker image e subir gateway nodes + mock backends.
- JUnit 5 com `@DisplayName` em PT-BR, `@TestMethodOrder(OrderAnnotation.class)` quando ordem importa, `@TempDir` para I/O.
- **Awaitility** para polling async — nunca `Thread.sleep`.
