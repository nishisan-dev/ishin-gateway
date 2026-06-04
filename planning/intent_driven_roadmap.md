# Intent-Driven Roadmap — ishin-gateway

> Documento de referência para a evolução do ishin-gateway rumo a uma arquitetura
> Intent-Driven, com foco pragmático em KPIs, APIs de mutação dinâmica e separação
> rigorosa entre Data Plane e Control Plane.

---

## 1. Conceito Central

**Intent-Driven** = O operador declara **o que** o sistema deve garantir (SLOs de negócio),
e o sistema decide **como** cumprir (atuando autonomamente sobre Rate Limit, Pool Weights,
Circuit Breaker, etc.).

| Paradigma Atual (Imperativo) | Paradigma Futuro (Intent-Driven) |
|---|---|
| Dev escreve regras Groovy e YAML estáticos | Dev declara SLOs e classes de tráfego |
| Rate limit fixo (ex: 1000 req/s hardcoded) | Rate limit adaptativo baseado em latência |
| Circuit Breaker com limiares estáticos | Limiares reativos ao estado real do upstream |
| Reação humana a incidentes (MTTD alto) | Reação autônoma em milissegundos (Closed-Loop) |

---

## 2. Decisões Arquiteturais

### 2.1. Separação Data Plane × Control Plane

O ishin-gateway **não** deve embutir lógica de Intent-Driven direto no core. Em vez disso:

- **Data Plane (ishin-gateway):** Roteamento rápido, proxying L4/L7, exportação
  agressiva de telemetria. Permanece leve e obediente.
- **Control Plane ("Brain"):** Módulo orquestrador **apartado**, que ingere KPIs,
  avalia intenções e injeta mutações de configuração no gateway.

> [!IMPORTANT]
> O gateway deve ser um executor performático. Toda inteligência de negócio
> reside no Brain. Isso evita acoplamento e facilita troca do Control Plane no futuro.

### 2.2. Protocolo de Comunicação Brain ↔ ishin-gateway

| Opção | Prós | Contras |
|---|---|---|
| **REST + Webhooks** | Simples, testável com `curl` | Sem push nativo, requer polling |
| **gRPC Bidirecional** | Push instantâneo, Protobuf compacto, contrato tipado via `.proto` | Ferramental mais complexo (`grpcurl`) |

**Decisão recomendada:** gRPC com streams bidirecionais.
- O gateway abre **1 stream** com o Brain no boot.
- Canal de **Telemetry (Gateway → Brain):** envia snapshots de KPIs a cada N segundos.
- Canal de **Config Push (Brain → Gateway):** o Brain envia mutações (pesos, rate limits,
  circuit breaker thresholds) em tempo real.

---

## 3. Roadmap de Fases

### Fase 1 — Maturidade de Telemetria (KPIs)
Instrumentar todos os sensores no Data Plane para que o futuro Brain tenha dados de
alta precisão. Ver **Seção 4** para o catálogo completo de KPIs.

### Fase 2 — APIs de Mutação Dinâmica (Hot-Reloading)
Expor mecanismos para reconfiguração em runtime sem restart da JVM:
- **Dynamic Routing & Upstreams:** Repesagem, inserção/eviction de backends via API.
- **Circuit Breaker Remote Mutation:** Alteração de limiares do Resilience4j sob comando.
- **Dynamic Rate Limiting:** Ativação/desativação de Load Shedding por zona e scope.
- **Tunnel L4 Lifecycle:** Abertura/fechamento de listeners TCP sob demanda.

### Fase 3 — Control Plane ("The Brain")
Serviço externo que implementa o **Closed-Loop Controller**:
1. **Percepção (Read):** Ingere KPIs do gateway via stream gRPC.
2. **Análise (Evaluate):** Cruza com Manifestos de Intenção (`intents.yaml`).
3. **Ação (Actuate):** Envia mutações de configuração pelo canal de Config Push.

---

## 4. Catálogo de KPIs

### 4.1. Métricas já existentes no código (Baseline)

Levantamento do estado atual de instrumentação (`ProxyMetrics`, `TunnelMetrics`, `RateLimitManager`):

**ProxyMetrics (L7 HTTP):**

| Métrica | Tipo | Tags |
|---|---|---|
| `ishin.requests.total` | Counter | listener, method, status |
| `ishin.request.duration` | Timer | listener, method |
| `ishin.request.errors` | Counter | listener, method |
| `ishin.upstream.requests` | Counter | backend, method, status |
| `ishin.upstream.duration` | Timer | backend, method |
| `ishin.upstream.errors` | Counter | backend, method |
| `ishin.ratelimit.total` | Counter | scope, zone, result |
| `ishin.ratelimit.available_permits` | Gauge | zone |
| `ishin.context.requests.total` | Counter | listener, context, method, status |
| `ishin.context.duration` | Timer | listener, context, method |
| `ishin.context.errors` | Counter | listener, context, method |
| `ishin.script.executions.total` | Counter | listener, context, script |
| `ishin.script.duration` | Timer | listener, context, script |
| `ishin.script.errors` | Counter | listener, context, script |

**TunnelMetrics (L4 TCP):**

| Métrica | Tipo | Tags |
|---|---|---|
| `ishin.tunnel.listener.ports.active` | Gauge | — |
| `ishin.tunnel.connections.total` | Counter | virtual_port, backend |
| `ishin.tunnel.connections.active` | Gauge | — |
| `ishin.tunnel.connections.active.per_backend` | Gauge | virtual_port, backend |
| `ishin.tunnel.session.duration.seconds` | Timer | virtual_port, backend |
| `ishin.tunnel.connect.duration.seconds` | Timer | virtual_port, backend |
| `ishin.tunnel.bytes.sent.total` | Counter | virtual_port, backend |
| `ishin.tunnel.bytes.received.total` | Counter | virtual_port, backend |
| `ishin.tunnel.connect.errors.total` | Counter | virtual_port, backend, error_type |
| `ishin.tunnel.pool.removals.total` | Counter | virtual_port, backend, reason |
| `ishin.tunnel.standby.promotions.total` | Counter | virtual_port |
| `ishin.tunnel.routing.duration.seconds` | Timer | virtual_port |

**Resilience4j + JVM (via MetricsCollectorService):**

| Prefixo | Fonte |
|---|---|
| `resilience4j.*` | Circuit Breaker states/events |
| `jvm.memory.*`, `jvm.threads.*`, `jvm.gc.*` | JVM internals |
| `system.cpu.*`, `process.cpu.*` | CPU host/process |

### 4.2. KPIs novos recomendados (Gap Analysis)

Métricas que **não existem** no código atual e precisam ser implementadas:

#### A. Saturação e Pressão Interna

| KPI | Nome sugerido | Tipo | Justificativa |
|---|---|---|---|
| **Gateway Overhead Latency** | `ishin.gateway.overhead.ms` | Timer | Tempo total − tempo do upstream = custo puro do gateway. Isola a culpa em incidentes de latência |
| **OkHttp Queue Depth** | `ishin.upstream.queue.depth` | Gauge | Fila de conexões aguardando socket no connection pool. Indicador antecipado de *Thundering Herd* |
| **Virtual Thread Active Count** | `ishin.threads.virtual.active` | Gauge | Quantas VTs estão executando. Mostra carga real na JVM |

#### B. Resiliência e Eficácia de Proteção

| KPI | Nome sugerido | Tipo | Justificativa |
|---|---|---|---|
| **Load Shedding Segregado** | `ishin.ratelimit.rejected` | Counter (+tag `priority`) | Diferenciar rejeições de tráfego premium vs gratuito. Fundamental para Intent-Driven saber *quem* está sendo afetado |
| **Silent Retry/Failover** | `ishin.upstream.failover.count` | Counter | Quantas vezes o gateway desviou transparentemente de um backend falho para um saudável. Mede o "esforço invisível" |

#### C. Segurança de Borda (SecOps)

| KPI | Nome sugerido | Tipo | Justificativa |
|---|---|---|---|
| **Auth Early Reject Rate** | `ishin.auth.reject.total` | Counter (+tag `reason`: expired/invalid/missing) | Picos anômalos de 401/403 denunciam botnets ou ataques de força bruta |
| **Payload Size Histogram** | `ishin.request.payload.bytes` | DistributionSummary | P95/P99 do tamanho de request/response. Detecta Data Exfiltration ou abuso de endpoints paginados |

#### D. Saúde do Cluster NGrid

| KPI | Nome sugerido | Tipo | Justificativa |
|---|---|---|---|
| **Cluster Peers Online** | `ishin.cluster.peers.count` | Gauge | Visibilidade imediata de quantos nós estão vivos no mesh |
| **DistributedMap Sync Lag** | `ishin.cluster.sync.lag.ms` | Timer | Latência de propagação de dados entre nós. Se crescer, o cluster está fragmentando |

---

## 5. Exemplo Concreto: Intent vs Imperativo

**Hoje (Imperativo — adapter.yaml + Rules.groovy):**
```yaml
# adapter.yaml — tudo hardcoded
rateLimit:
  mode: "nowait"
  capacity: 1000
```
```groovy
// Rules.groovy — lógica manual
if (context.request.path.startsWith("/v1/pay")) {
    routeTo("backend-pagamentos") // sem distinção de prioridade
}
```

**Futuro (Intent-Driven — intents.yaml):**
```yaml
intents:
  - name: "Proteger Checkout VIP"
    target:
      path: "/v1/pay"
    slo:
      max_latency_p95: 150ms
      success_rate: 99.9%
    traffic_classes:
      - condition: "header('X-Plano') == 'GOLD'"
        priority: CRITICAL
        guarantee_capacity: 100%
      - condition: "default"
        priority: LOW
        sheddable: true
```

O Control Loop do Brain:
1. Consulta `ishin.upstream.duration` (p95) a cada 2s.
2. p95 atinge 180ms → viola SLO de 150ms.
3. Envia push gRPC: ativa rate limit `nowait` apenas para `priority=LOW`.
4. Tráfego low-priority toma 429; p95 cai para 130ms.
5. Brain relaxa a restrição automaticamente.

---

## 6. Dependências Técnicas

| Dependência | Uso | Fase |
|---|---|---|
| Micrometer + Prometheus | Exportação de todos os KPIs | Fase 1 |
| gRPC + Protobuf (Java) | Comunicação Brain ↔ Gateway | Fase 2-3 |
| Resilience4j Actuator | Exposição de state do CB para mutação | Fase 2 |
| NGrid DistributedMap | Propagação de config changes no cluster | Fase 2 |
