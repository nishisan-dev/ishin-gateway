# Testes de Integração: Cluster Mode NGrid com Testcontainers

Validar o comportamento do cluster mode ishin-gateway (NGrid mesh, leader election, token sharing via DistributedMap) com 2 nós reais rodando em containers Docker, usando Testcontainers + JUnit 5.

## User Review Required

> [!IMPORTANT]
> **Abordagem de build da imagem**: O projeto não possui um `Dockerfile` standalone — hoje o build é feito diretamente via `maven:3.9.9-eclipse-temurin-21` no docker-compose. O plano propõe criar um `Dockerfile` multi-stage dedicado para o ishin-gateway, que será usado tanto nos testes (via `ImageFromDockerfile`) quanto como artefato reutilizável do projeto.

> [!WARNING]
> **GitHub Packages auth**: O `pom.xml` depende de `dev.nishisan:nishi-utils:3.1.0` publicado no GitHub Packages. O build do Docker Image precisa do `settings.xml` com credenciais. O plano injeta este arquivo via build context usando o `~/.m2/settings.xml` existente.

> [!IMPORTANT]
> **Escopo de testes OAuth**: O teste de token sharing POW-RBL requer um Keycloak real com o realm configurado. O plano inclui este cenário, usando o `compose/keycloak/realm-inventory-dev.json` existente. **Se preferir manter os testes rápidos e sem Keycloak, posso separar em dois test classes**: um para cluster básico (sem OAuth) e outro para token sharing (com Keycloak). Me diga sua preferência.

---

## Proposed Changes

### Dockerfile

#### [NEW] [Dockerfile](file:///home/lucas/Projects/ishin-gateway/Dockerfile)

Multi-stage Dockerfile para build e execução do ishin-gateway:

```dockerfile
# Stage 1: Build
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY settings.xml /tmp/settings.xml
RUN mvn -s /tmp/settings.xml dependency:go-offline -q || true
COPY src/ src/
COPY rules/ rules/
RUN mvn -s /tmp/settings.xml -DskipTests clean package -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/target/ishin-gateway-1.0-SNAPSHOT.jar app.jar
COPY rules/ rules/
EXPOSE 9091 9190 7100
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms128m", "-Xmx256m", "-jar", "app.jar"]
```

- O `settings.xml` é copiado apenas no stage de build (não vaza para a imagem runtime).
- As `rules/` são copiadas pois o ishin-gateway carrega scripts Groovy do filesystem.

---

### Dependências Maven

#### [MODIFY] [pom.xml](file:///home/lucas/Projects/ishin-gateway/pom.xml)

Adicionar dependências de teste:

```xml
<!-- Testcontainers (JUnit 5 integration + core) -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>

<!-- Awaitility — polling assíncrono sem Thread.sleep -->
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.2.2</version>
    <scope>test</scope>
</dependency>

<!-- OkHttp (já no classpath de compile, mas explicitamente no test scope para HTTP calls) -->
```

> **Nota**: `spring-boot-starter-test` já está no `pom.xml` (linha 277).

---

### Configuração de Teste

#### [NEW] [adapter-test-cluster.yaml](file:///home/lucas/Projects/ishin-gateway/src/test/resources/adapter-test-cluster.yaml)

Configuração mínima para os testes — sem OAuth, sem Keycloak, usando apenas o listener `http-noauth` apontando para um backend mock:

```yaml
endpoints:
  default:
    listeners:
      http-noauth:
        listenAddress: "0.0.0.0"
        listenPort: 9091
        ssl: false
        scriptOnly: false
        defaultScript: "Default.groovy"
        defaultBackend: "mock-backend"
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"
    backends:
      mock-backend:
        backendName: "mock-backend"
        xOriginalHost: null
        endPointUrl: "http://mock-backend:8080"
    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 8
    jettyMaxThreads: 100
    jettyIdleTimeout: 30000
    connectionPoolSize: 32
    connectionPoolKeepAliveMinutes: 1
    dispatcherMaxRequests: 64
    dispatcherMaxRequestsPerHost: 32

cluster:
  enabled: true
  host: "0.0.0.0"
  port: 7100
  clusterName: "ishin-test-cluster"
  seeds: []    # <-- Preenchido dinamicamente no código do teste
  replicationFactor: 2
  dataDirectory: "/tmp/ngrid-test-data"
```

#### [NEW] [application-test.properties](file:///home/lucas/Projects/ishin-gateway/src/test/resources/application-test.properties)

```properties
spring.profiles.active=test
server.port=18080
management.server.port=9190
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
debug=false
```

---

### Classe de Teste

#### [NEW] [NGridClusterIntegrationTest.java](file:///home/lucas/Projects/ishin-gateway/src/test/java/dev/nishisan/ishin/cluster/NGridClusterIntegrationTest.java)

Classe JUnit 5 + Testcontainers com os seguintes cenários:

```
Pacote: dev.nishisan.ishin.cluster
```

**Setup da infra (containers efêmeros):**

1. **Network**: `Network.newNetwork()` — rede Docker isolada para os 3 containers
2. **Backend mock**: `GenericContainer<>("nginx:alpine")` com volume de config que retorna `HTTP 200 OK` no `/*` + `/health`
3. **Nó 1 (ishin-gateway)**: `ImageFromDockerfile` buildando o `Dockerfile` com o settings.xml. Env vars:
   - `ISHIN_CONFIG=/app/config/adapter-test-cluster.yaml` (injetado via `withClasspathResourceMapping`)
   - `ISHIN_CLUSTER_NODE_ID=node-1`
   - `SPRING_PROFILES_DEFAULT=test`
   - Network alias: `ishin-node1`
4. **Nó 2 (ishin-gateway)**: Mesma imagem, `ISHIN_CLUSTER_NODE_ID=node-2`, alias `ishin-node2`
5. **Seeds dinâmicos**: O `adapter-test-cluster.yaml` terá seeds fixos `["ishin-node1:7100", "ishin-node2:7100"]`, usando os network aliases do Docker (similar ao compose cluster existente)

**Cenários de teste:**

| # | Teste | Descrição | Assertions |
|---|-------|-----------|------------|
| T1 | `testClusterMeshFormation` | Ambos os nós sobem e formam mesh | Health de ambos reporta `clusterMode: true`, `activeMembers: 2` |
| T2 | `testLeaderElection` | Exatamente 1 líder entre os 2 nós | Exatamente um `isLeader: true` e outro `isLeader: false` |
| T3 | `testProxyFunctional` | Requests ao proxy retornam 200 | `curl` em ambos os nós retorna 200 (sem hang da Sessão 3) |
| T4 | `testGracefulShutdown` | Parar Nó 2, Nó 1 continua operando | Nó 1 mantém `status: UP`, `activeMembers: 1`, requests continuam 200 |
| T5 | `testHealthReportsInstanceId` | Health contém `instanceId` distinto | `instanceId` de cada nó é diferente |

**Implementação com Awaitility:**
- Após cada startup de container, polling no `/actuator/health` com `await().atMost(60, SECONDS)` até `status: UP` e `activeMembers: 2`
- NGrid em ambiente de teste precisa de heartbeat relaxado (1s) + leaseTimeout relaxado (10s) — já configurado no `ClusterService.buildNGridConfig()`

---

### Nginx Config para Backend Mock

#### [NEW] [default.conf](file:///home/lucas/Projects/ishin-gateway/src/test/resources/testcontainers/mock-backend.conf)

Config Nginx mínima para o backend mock:

```nginx
server {
    listen 8080;
    location / {
        return 200 '{"status":"ok","backend":"mock"}';
        add_header Content-Type application/json;
    }
    location /health {
        return 200 'ok';
    }
}
```

---

## Verification Plan

### Automated Tests

**Execução dos testes:**
```bash
cd /home/lucas/Projects/ishin-gateway
mvn -s ~/.m2/settings.xml test -Dtest="NGridClusterIntegrationTest" -pl . 2>&1 | tail -50
```

**Critérios de sucesso:**
- Todos os 5 testes passam (`BUILD SUCCESS`)
- Tempo total < 120s (containers + mesh formation + assertions)
- Sem flaky tests (Awaitility absorve timing issues)

**Verificação de build limpo:**
```bash
cd /home/lucas/Projects/ishin-gateway
mvn -s ~/.m2/settings.xml clean compile -DskipTests
```

### Manual Verification

1. **Observar logs dos containers**: Durante a execução dos testes, verificar nos logs se o mesh NGrid se forma (mensagens `NGrid cluster started`, `Leadership change`)
2. **Confirmar que `mvn test` sem filtro não quebra**: Rodar `mvn test` completo para garantir que o teste de integração não conflita com outros testes existentes

---

## Estrutura final de arquivos novos

```
ishin-gateway/
├── Dockerfile                                           [NEW]
├── pom.xml                                              [MOD] +3 deps test
└── src/test/
    ├── java/dev/nishisan/ishin/cluster/
    │   └── NGridClusterIntegrationTest.java             [NEW]
    └── resources/
        ├── adapter-test-cluster.yaml                    [NEW]
        ├── application-test.properties                  [NEW]
        └── testcontainers/
            └── mock-backend.conf                        [NEW]
```
