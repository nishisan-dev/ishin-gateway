# Casos de Uso — ishin-gateway

Cenários end-to-end com configuração, scripts Groovy e comandos de validação.

---

## 1. API Gateway Simples — Proxy Transparente

O cenário mais básico: o ishin-gateway atua como proxy transparente para um backend único.

```
Cliente ──▶ ishin-gateway :8080 ──▶ Backend API :3000
```

### adapter.yaml

```yaml
---
endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        ssl: false
        scriptOnly: false
        defaultBackend: "my-api"
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

    backends:
      my-api:
        backendName: "my-api"
        members:
          - url: "http://api-server:3000"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 8
    jettyMaxThreads: 200
    jettyIdleTimeout: 60000
    connectionPoolSize: 64
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 128
    dispatcherMaxRequestsPerHost: 64
```

### Rules.groovy

```groovy
// Vazio — proxy transparente, nenhuma regra necessária
```

### Validação

```bash
# Request é encaminhado transparentemente ao backend
curl -i http://localhost:8080/api/users

# O header x-trace-id é adicionado na resposta
# < x-trace-id: a1b2c3d4e5f67890
```

---

## 2. Multi-Backend com Roteamento por Path

O ishin-gateway roteia requests para diferentes backends com base no path.

```
                         ┌──▶ users-service :3001
Cliente ──▶ ishin-gateway :8080─┼──▶ products-service :3002
                         └──▶ orders-service :3003
```

### adapter.yaml

```yaml
---
endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        ssl: false
        scriptOnly: false
        defaultBackend: "users-service"
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

    backends:
      users-service:
        backendName: "users-service"
        members:
          - url: "http://users-service:3001"

      products-service:
        backendName: "products-service"
        members:
          - url: "http://products-service:3002"

      orders-service:
        backendName: "orders-service"
        members:
          - url: "http://orders-service:3003"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 8
    jettyMaxThreads: 200
    jettyIdleTimeout: 60000
    connectionPoolSize: 128
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 256
    dispatcherMaxRequestsPerHost: 128
```

### Rules.groovy

```groovy
def path = context.path()

if (path.startsWith("/api/users")) {
    upstreamRequest.setBackend("users-service")
} else if (path.startsWith("/api/products")) {
    upstreamRequest.setBackend("products-service")
} else if (path.startsWith("/api/orders")) {
    upstreamRequest.setBackend("orders-service")
}
```

### Validação

```bash
curl -i http://localhost:8080/api/users/1        # → users-service
curl -i http://localhost:8080/api/products/42     # → products-service
curl -i http://localhost:8080/api/orders?status=open  # → orders-service
```

---

## 3. Gateway com Autenticação OAuth2

O ishin-gateway valida JWT na entrada e injeta OAuth2 token nas chamadas ao backend.

```
Cliente ──JWT──▶ ishin-gateway :9090 ──Bearer──▶ API Protegida
                    │
                    └── Keycloak (obtém token + valida JWT)
```

### adapter.yaml

```yaml
---
endpoints:
  default:
    listeners:
      api:
        listenAddress: "0.0.0.0"
        listenPort: 9090
        ssl: false
        scriptOnly: false
        defaultBackend: "protected-api"
        secured: true
        secureProvider:
          providerClass: "dev.nishisan.ishin.auth.jwt.JWTTokenDecoder"
          name: "keycloak-jwt"
          options:
            issuerUri: http://keycloak:8080/realms/my-realm
            jwkSetUri: http://keycloak:8080/realms/my-realm/protocol/openid-connect/certs
        urlContexts:
          api:
            context: "/api/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"
            secured: true
          health:
            context: "/health"
            method: "GET"
            secured: false

    backends:
      protected-api:
        backendName: "protected-api"
        members:
          - url: "http://backend:3000"
        oauthClientConfig:
          ssoName: "backend-sso"
          clientId: "gateway-client"
          clientSecret: "gateway-secret"
          userName: "svc-account"
          password: "svc-pass"
          tokenServerUrl: "http://keycloak:8080/realms/my-realm/protocol/openid-connect/token"
          useRefreshToken: true
          renewBeforeSecs: 30
          authScopes:
            - "openid"
            - "profile"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 16
    jettyMaxThreads: 500
    jettyIdleTimeout: 120000
    connectionPoolSize: 256
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 512
    dispatcherMaxRequestsPerHost: 256
```

### Rules.groovy

```groovy
// Acessa informações do usuário autenticado
def principal = workload.objects.get("USER_PRINCIPAL")
if (principal != null) {
    // Injeta o ID do usuário como header para o backend
    upstreamRequest.addHeader("X-User-Id", principal.id)
}
```

### Validação

```bash
# Obter token JWT
TOKEN=$(curl -s -X POST 'http://localhost:8081/realms/my-realm/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=gateway-client' \
  -d 'client_secret=gateway-secret' \
  -d 'username=test-user' \
  -d 'password=test-pass' | jq -r .access_token)

# Chamar API protegida
curl -i http://localhost:9090/api/resource \
  -H "Authorization: Bearer ${TOKEN}"

# Health check (sem auth)
curl -i http://localhost:9090/health
```

---

## 4. Mock/Stub de API

O ishin-gateway gera respostas sintéticas para endpoints que ainda não têm backend, ideal para desenvolvimento e testes.

```
Cliente ──▶ ishin-gateway :8080 ──✗──▶ (nenhum backend chamado)
                │
                └── Resposta gerada pelo Groovy
```

### adapter.yaml

```yaml
---
endpoints:
  default:
    listeners:
      mock:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        ssl: false
        scriptOnly: true          # ← modo script-only
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/MockRules.groovy"

    backends: {}

    ruleMapping: "default/MockRules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 4
    jettyMaxThreads: 50
    jettyIdleTimeout: 60000
    connectionPoolSize: 10
    connectionPoolKeepAliveMinutes: 1
    dispatcherMaxRequests: 50
    dispatcherMaxRequestsPerHost: 25
```

### MockRules.groovy

```groovy
// rules/default/MockRules.groovy

def path = context.path()
def method = context.method().name()

def synth = workload.createSynthResponse()

if (path == "/api/users" && method == "GET") {
    synth.setContent(utils.gson.toJson([
        [id: 1, name: "Alice", email: "alice@example.com"],
        [id: 2, name: "Bob", email: "bob@example.com"]
    ]))
    synth.setStatus(200)
} else if (path.startsWith("/api/users/") && method == "GET") {
    def userId = path.split("/").last()
    synth.setContent(utils.gson.toJson([
        id: userId.toInteger(),
        name: "User ${userId}",
        email: "user${userId}@example.com"
    ]))
    synth.setStatus(200)
} else if (path == "/api/users" && method == "POST") {
    synth.setContent(utils.gson.toJson([
        id: 999,
        message: "Created"
    ]))
    synth.setStatus(201)
} else {
    synth.setContent(utils.gson.toJson([
        error: "Not Found",
        path: path,
        method: method
    ]))
    synth.setStatus(404)
}

synth.addHeader("Content-Type", "application/json")
```

### Validação

```bash
curl http://localhost:8080/api/users
# [{"id":1,"name":"Alice",...}, {"id":2,"name":"Bob",...}]

curl http://localhost:8080/api/users/42
# {"id":42,"name":"User 42","email":"user42@example.com"}

curl -X POST http://localhost:8080/api/users
# {"id":999,"message":"Created"}

curl http://localhost:8080/api/unknown
# {"error":"Not Found","path":"/api/unknown","method":"GET"}
```

---

## 5. Transformação de Resposta

Response processors modificam a resposta do backend antes de enviar ao cliente.

```
Cliente ◀── Response modificada ── ishin-gateway ◀── Response original ── Backend
```

### Rules.groovy

```groovy
// rules/default/Rules.groovy

// Desabilita streaming para poder inspecionar o body
workload.returnPipe = false

// Processor que adiciona metadata à resposta
def enrichProcessor = { wl ->
    wl.clientResponse.addHeader("X-Gateway", "ishin-gateway")
    wl.clientResponse.addHeader("X-Processed-At", new Date().format("yyyy-MM-dd'T'HH:mm:ss"))
    wl.clientResponse.addHeader("X-Listener", listener)
}

workload.addResponseProcessor('enrichProcessor', enrichProcessor)
```

### Validação

```bash
curl -i http://localhost:8080/api/data

# Headers adicionados pelo processor:
# < X-Gateway: ishin-gateway
# < X-Processed-At: 2026-03-08T15:00:00
# < X-Listener: http
```

---

## 6. Benchmark e Performance

Cenário isolado para medir o overhead puro do gateway, usando backend estático sem autenticação.

```
                              ┌──▶ static-backend :8080 (Nginx)
Cliente ──▶ benchmark.py ───┬┤
                            └──▶ ishin-gateway :9091 ──▶ static-backend :8080
```

### adapter.yaml (trecho)

```yaml
listeners:
  http-noauth:
    listenAddress: "0.0.0.0"
    listenPort: 9091
    ssl: false
    scriptOnly: false
    defaultBackend: "static-backend"
    secured: false
    urlContexts:
      default:
        context: "/*"
        method: "ANY"
        ruleMapping: "default/Rules.groovy"

backends:
  static-backend:
    backendName: "static-backend"
    members:
      - url: "http://static-backend:8080"
```

### Executar

```bash
# Profile bench (sem tracing, log level INFO)
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d

# Medir overhead
python3 scripts/benchmark.py
```

### Interpretação

O benchmark compara:
- **Porta 3080** — Nginx direto (baseline, sem proxy)
- **Porta 9091** — ishin-gateway → Nginx (overhead do gateway)

A diferença entre os dois é o **overhead puro** do ishin-gateway (parsing, Groovy, OkHttp, tracing, etc.).

---

## 7. Composição de APIs

O ishin-gateway chama múltiplos backends e compõe a resposta.

```
                              ┌──▶ users-service
Cliente ──▶ ishin-gateway :8080 ────┼──▶ products-service
                              └── Resposta combinada
```

### Rules.groovy

```groovy
// rules/default/Rules.groovy

if (context.path() == "/api/dashboard") {
    // Chama backends em paralelo
    def usersBe = utils.httpClient.getAssyncBackend("users-service")
    def productsBe = utils.httpClient.getAssyncBackend("products-service")

    def usersReq = usersBe.get("http://users-service:3001/api/users?limit=5", [:])
    def productsReq = productsBe.get("http://products-service:3002/api/products?limit=5", [:])

    // Aguarda ambas
    def usersResp = usersReq.join()
    def productsResp = productsReq.join()

    // Compõe resposta
    def dashboard = [
        users: utils.json.parseText(usersResp.body().string()),
        products: utils.json.parseText(productsResp.body().string()),
        generatedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss")
    ]

    def synth = workload.createSynthResponse()
    synth.setContent(utils.gson.toJson(dashboard))
    synth.setStatus(200)
    synth.addHeader("Content-Type", "application/json")
}
```

### Validação

```bash
curl http://localhost:8080/api/dashboard
# {
#   "users": [...],
#   "products": [...],
#   "generatedAt": "2026-03-08T15:00:00"
# }
```

---

## Resumo dos Cenários

| # | Caso de Uso | Auth Entrada | Auth Saída | Groovy | Backend |
|---|------------|:------------:|:----------:|:------:|---------|
| 1 | Proxy transparente | ❌ | ❌ | Vazio | 1 |
| 2 | Roteamento por path | ❌ | ❌ | Routing | N |
| 3 | Gateway OAuth2 | ✅ JWT | ✅ OAuth2 | Headers | 1 |
| 4 | Mock/Stub | ❌ | ❌ | Full | 0 (scriptOnly) |
| 5 | Transformação | ❌ | ❌ | Processor | 1 |
| 6 | Benchmark | ❌ | ❌ | Vazio | 1 (estático) |
| 7 | Composição | ❌ | ❌ | Full | N (async) |
| 8 | Cluster + Token Sharing | ❌ | ❌ | Vazio | 1 (cluster) |

---

## 8. Cluster com Token Sharing (NGrid)

Múltiplas instâncias do ishin-gateway operam como cluster coordenado, compartilhando tokens OAuth2 e permitindo deploy atômico de regras Groovy.

```
                     ┌────────────────────────────────┐
 Cliente ──────────▶ │  nginx LB (round-robin :5080)  │
                     └──────┬──────────┬──────────┬───┘
                            │          │          │
                     ┌──────▼──┐ ┌────▼────┐ ┌──▼──────┐
                     │ ishin-gateway  │ │ ishin-gateway  │ │ ishin-gateway  │
                     │ node-1  │ │ node-2  │ │ node-3  │
                     │  :9091  │ │  :9091  │ │  :9091  │
                     │  :7100◄─┼─►:7100◄──┼─►:7100   │
                     └──────┬──┘ └────┬────┘ └──┬──────┘
                            │   NGrid Mesh     │
                            └────────┬─────────┘
                                     │
                              Backend Service

          DistributedMap: tokens OAuth + rules bundles
```

### adapter-cluster.yaml

```yaml
---
endpoints:
  default:
    listeners:
      http-noauth:
        listenAddress: "0.0.0.0"
        listenPort: 9091
        ssl: false
        scriptOnly: false
        defaultBackend: "static-backend"
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

    backends:
      static-backend:
        backendName: "static-backend"
        members:
          - url: "http://static-backend:8080"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 16
    jettyMaxThreads: 500
    jettyIdleTimeout: 120000
    connectionPoolSize: 256
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 512
    dispatcherMaxRequestsPerHost: 256

cluster:
  enabled: true
  host: "0.0.0.0"
  port: 7100
  clusterName: "ishin-cluster"
  seeds:
    - "ishin-node1:7100"
    - "ishin-node2:7100"
    - "ishin-node3:7100"
  replicationFactor: 2
  dataDirectory: "/tmp/ngrid-data"
```

### Subir o cluster

```bash
# Subir com docker compose (standalone fica com 0 réplicas, cluster com 3 nós)
docker compose -f docker-compose.yml -f docker-compose.cluster.yml up -d
```

### Validação

```bash
# Health check dos 3 nós (Actuator — porta 929x)
curl -s http://localhost:9291/actuator/health | jq '.components.cluster'
# {"status":"UP","details":{"clusterMode":"ACTIVE","clusterNodeId":"ishin-node1","isLeader":true,"activeMembers":["ishin-node1","ishin-node2","ishin-node3"]}}

curl -s http://localhost:9292/actuator/health | jq '.components.cluster'
# {"status":"UP","details":{"clusterMode":"ACTIVE","clusterNodeId":"ishin-node2","isLeader":false,...}}

# Proxy via LB (round-robin entre os 3 nós)
curl -i http://localhost:5080/qualquer/path

# Proxy direto por nó
curl -i http://localhost:9191/qualquer/path   # nó 1
curl -i http://localhost:9192/qualquer/path   # nó 2
curl -i http://localhost:9193/qualquer/path   # nó 3

# Verificar métricas do cluster via Prometheus
curl -s http://localhost:9291/actuator/prometheus | grep ishin_cluster
# ishin_cluster_active_members 3.0
# ishin_cluster_is_leader 1.0
```

> Para testes automatizados de cluster (Testcontainers), veja [docs/cluster_integration_tests.md](cluster_integration_tests.md).

