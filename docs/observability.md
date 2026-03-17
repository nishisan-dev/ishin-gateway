# Observabilidade — Zipkin / Brave

Documentação da camada de distributed tracing do ishin-gateway.

## Stack

| Componente | Versão | Função |
|------------|--------|--------|
| [Brave](https://github.com/openzipkin/brave) | 6.0.3 | Instrumentação e criação de spans |
| [Zipkin Reporter](https://github.com/openzipkin/zipkin-reporter-java) | 3.4.0 | Envio assíncrono dos spans |
| `zipkin-sender-okhttp3` | 3.4.0 | Transporte via OkHttp para o collector |
| [Micrometer](https://micrometer.io/) | (Spring Boot BOM) | Métricas Prometheus (counters, timers, gauges) |
| [Resilience4j](https://resilience4j.readme.io/) | 2.2.0 | Circuit breaker com métricas Micrometer |

O endpoint do Zipkin collector é resolvido via variável de ambiente `ZIPKIN_ENDPOINT` ou propriedade de sistema `zipkin.endpoint`.

### Configuração de Tracing

| Variável de Ambiente | System Property | Default | Descrição |
|---------------------|-----------------|---------|-----------|
| `ZIPKIN_ENDPOINT` | `zipkin.endpoint` | `http://zipkin:9411/api/v2/spans` | URL do Zipkin collector |
| `TRACING_ENABLED` | — | `true` | Habilita/desabilita tracing completamente |
| `TRACING_SAMPLE_RATE` | `tracing.sample.rate` | `1.0` | Taxa de amostragem (`0.0` a `1.0`) |

> [!TIP]
> Em produção, use `TRACING_SAMPLE_RATE=0.1` (10%) para reduzir overhead sem perder visibilidade. O valor `0.0` equivale a desabilitar tracing; `1.0` coleta 100% dos requests.

---

## Arquitetura de Tracing

Cada request que entra no adapter gera um **trace completo** com múltiplos spans hierárquicos. O trace segue o padrão B3 para propagação de contexto entre o adapter e os backends.

```
Cliente
  │
  ▼
┌─────────────────────────────────────────────────┐
│ ishin-gateway                                          │
│                                                 │
│  rootSpan (SERVER) ← extracted from B3 headers  │
│  ├── request-handler                            │
│  │   ├── dynamic-rules                          │
│  │   │   └── rules-execution (por script)       │
│  │   ├── upstream-request (CLIENT)              │
│  │   └── response-adapter                       │
│  │       ├── response-setup                     │
│  │       ├── response-processor (por closure)   │
│  │       ├── response-headers-copy              │
│  │       └── response-send-to-client            │
│  └── token-decoder (se endpoint secured)        │
└─────────────────────────────────────────────────┘
  │
  ▼ B3 headers injetados
Backend
```

---

## Spans — Referência Detalhada

### `any-handler-[<listener>]` — Root Span (SERVER)

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `EndpointWrapper.addServiceListener()` |
| **Kind** | `SERVER` |
| **Mede** | Ciclo completo do request no adapter |

**Tags semânticas:**
- `http.method` — Método HTTP (GET, POST, etc.)
- `http.url` — URL completa do request
- `http.path` — Path sem query string
- `http.query` — Query parameters
- `http.client_ip` — IP do cliente
- `http.status_code` — Status code da resposta
- `http.response_content_type` — Content-Type da resposta
- `listener` — Nome do listener Javalin

**Propagação B3:** Se o request de entrada contém headers B3 (`X-B3-TraceId`, `X-B3-SpanId`, etc.), o adapter **extrai o contexto** e cria o rootSpan como child do trace externo. Caso contrário, inicia um novo trace.

---

### `request-handler`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpProxyManager.handleRequest()` |
| **Parent** | rootSpan |
| **Mede** | Processamento completo do request (regras + upstream + response) |

**Tags:** `requestMethod`, todos os headers do request como `header-<name>`.

---

### `dynamic-rules`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpProxyManager.evalDynamicRules()` |
| **Parent** | request-handler |
| **Mede** | Setup do HttpWorkLoad + binding Groovy + execução de todos os scripts |

Contém child spans `rules-execution` para cada script executado.

---

### `rules-execution`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpProxyManager.evalDynamicRules()` (loop) |
| **Parent** | dynamic-rules |
| **Mede** | Execução de um script Groovy individual |

**Tags:** `script` — nome do arquivo `.groovy` executado.

**Nota:** O `GroovyScriptEngine` é configurado com `minimumRecompilationInterval = 60s` para evitar stat de filesystem a cada request. O tempo deste span (~600µs) é o piso irredutível do Groovy runtime (lookup + instância + execução).

---

### `upstream-request` (CLIENT)

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpProxyManager.handleRequest()` |
| **Kind** | `CLIENT` |
| **Parent** | request-handler |
| **Mede** | Chamada completa ao backend (DNS + TCP + TLS + request + response) |

**Tags:**
- `upstream-client-name` — Nome do backend configurado
- `upstream-member-url` — URL do membro selecionado pelo upstream pool
- `upstream-req-url` — URL completa do request ao backend (membro + path)
- `upstream-req-method` — Método HTTP usado
- `upstream.status_code` — Status code retornado pelo backend
- `upstream.content_type` — Content-Type da resposta do backend

**Propagação B3:** Headers B3 são **injetados** no request para o backend, permitindo que o trace continue end-to-end se o backend também suportar tracing.

**Relevância de performance:** Este span geralmente domina o trace (~70% do tempo total), representado principalmente pela latência de rede + processamento do backend.

---

### `response-adapter`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` |
| **Parent** | request-handler |
| **Mede** | Pipeline completo de envio da resposta ao cliente |

**Tags:**
- `return-pipe` — `true` se streaming direto, `false` se materializado em memória

Contém os seguintes child spans em sequência:

---

### `response-setup`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` |
| **Parent** | response-adapter |
| **Mede** | Setup do client response + adição do header `x-trace-id` |

**Tags:** `synth` — `true` se é uma resposta sintética definida pelo script Groovy.

---

### `response-processor`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` (loop) |
| **Parent** | response-adapter |
| **Mede** | Execução de uma closure Groovy de pós-processamento |

**Tags:** `processor-name` — nome do processor registrado via `workload.addResponseProcessor()`.

Haverá um span `response-processor` por closure registrada nos scripts Groovy.

---

### `response-headers-copy`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` |
| **Parent** | response-adapter |
| **Mede** | Cópia de status + headers do upstream para o client response |

**Tags:**
- `status-code` — Status HTTP copiado
- `upstream-headers-count` — Número de headers copiados

---

### `response-send-to-client`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` |
| **Parent** | response-adapter |
| **Mede** | Transferência efetiva dos bytes ao cliente |

Quando `return-pipe = true`, streaming direto de `InputStream` → `OutputStream` (buffer 8KB).
Quando `return-pipe = false`, envio do `byte[]` materializado via `ctx.result()`.

---

### `token-decoder`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `EndpointWrapper.getTokenDecoder()` |
| **Parent** | rootSpan |
| **Mede** | Decodificação e validação do JWT (endpoints secured) |

Só aparece em endpoints com autenticação habilitada.

---

## Header `x-trace-id`

Toda resposta do adapter inclui o header `x-trace-id` com o Trace ID do Zipkin. Isso permite **correlação direta** entre um request do cliente e o trace completo no Zipkin.

```bash
curl -v http://localhost:9090/api/resource
# < x-trace-id: f235a80ada773b83
```

Para consultar no Zipkin: `http://<zipkin>/zipkin/traces/f235a80ada773b83`

---

## Propagação B3

O adapter implementa propagação B3 bidirecional:

**Inbound (cliente → adapter):**
- Extrai `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-ParentSpanId`, `X-B3-Sampled` do request de entrada
- Se presentes, o rootSpan é criado como child do trace externo

**Outbound (adapter → backend):**
- Injeta headers B3 no request OkHttp para o backend
- Permite tracing end-to-end se o backend suportar B3

---

## Componentes Internos

### `TracerService`

Gerencia instâncias de `Tracing` e `Tracer` por service name. Cacheia instâncias via `ConcurrentHashMap`. O sender (`OkHttpSender`) e handler (`AsyncZipkinSpanHandler`) são inicializados uma única vez com double-checked locking.

O **sample rate** é resolvido na criação de cada instância de `Tracing` com a seguinte precedência:
1. Env `TRACING_SAMPLE_RATE` → 2. System property `tracing.sample.rate` → 3. Default `1.0`

Valores fora do range `[0.0, 1.0]` são clampados automaticamente. Valores inválidos geram warning no log e usam o default `1.0`.

### `TracerWrapper`

Wrapper sobre `Tracer` e `Tracing` que simplifica criação de spans. Métodos principais:
- `createSpan(name)` — cria span com parent automático
- `createChildSpan(name, parent)` — cria child span explícito
- `getTraceId()` — retorna o Trace ID em hexadecimal

### `SpanWrapper`

Wrapper sobre `Span` do Brave com API simplificada:
- `tag(key, value)` — adiciona tag (suporta String, int, long)
- `finish()` — finaliza o span
- `addError(exception)` — marca o span como erro

---

## Métricas Prometheus

Além do distributed tracing, o ishin-gateway exporta métricas operacionais via [Micrometer](https://micrometer.io/) no endpoint `/actuator/prometheus` (porta `9190`).

### Endpoint

```bash
curl http://localhost:9190/actuator/prometheus
```

### Métricas Inbound (por listener)

| Métrica | Tipo | Tags | Descrição |
|---------|------|------|-----------|
| `ishin.requests.total` | Counter | listener, method, status | Total de requests recebidos |
| `ishin.request.duration` | Timer | listener, method | Latência e2e do request (ms) |
| `ishin.request.errors` | Counter | listener, method | Erros internos (exceções) |

### Métricas Upstream (por backend)

| Métrica | Tipo | Tags | Descrição |
|---------|------|------|-----------|
| `ishin.upstream.requests` | Counter | backend, method, status | Total de requests ao backend |
| `ishin.upstream.duration` | Timer | backend, method | Latência da chamada upstream (ms) |
| `ishin.upstream.errors` | Counter | backend, method | Erros de I/O no upstream |

### Métricas Cluster (quando NGrid ativo)

| Métrica | Tipo | Descrição |
|---------|------|-----------|
| `ishin.cluster.active.members` | Gauge | Número de membros ativos no mesh NGrid |
| `ishin.cluster.is.leader` | Gauge | 1 se líder, 0 se follower |

---

## Circuit Breaker

O [Resilience4j](https://resilience4j.readme.io/) protege os backends contra overload. Quando habilitado (bloco `circuitBreaker:` no `adapter.yaml`), um `CircuitBreaker` independente é criado para cada backend.

### Comportamento

| Estado | Descrição |
|--------|-----------|
| **CLOSED** | Tráfego normal. Falhas são contabilizadas na sliding window. |
| **OPEN** | Requests rejeitados com **HTTP 503** + header `x-circuit-breaker: OPEN`. |
| **HALF_OPEN** | Número limitado de requests permitido para testar recuperação do backend. |

### Headers

| Header | Quando | Valor |
|--------|--------|-------|
| `x-circuit-breaker` | Status 503 (circuito aberto) | `OPEN` |

### Métricas

As métricas do circuit breaker são registradas automaticamente no Micrometer via `TaggedCircuitBreakerMetrics`:

| Métrica | Tipo | Descrição |
|---------|------|-----------|
| `resilience4j.circuitbreaker.state` | Gauge | Estado atual (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| `resilience4j.circuitbreaker.calls` | Counter | Chamadas por resultado (successful, failed, not_permitted) |
| `resilience4j.circuitbreaker.failure.rate` | Gauge | Taxa de falha atual (%) |

Para configuração detalhada, veja [docs/configuration.md](configuration.md#circuit-breaker).

---

## Rate Limiting

O ishin-gateway implementa rate limiting granular em 3 escopos (listener, rota, backend), controlado via bloco `rateLimiting:` no `adapter.yaml`.

### Modos

| Modo | Comportamento | Resposta |
|------|---------------|----------|
| **nowait** | Rejeita imediatamente | HTTP 429 + `x-rate-limit: REJECTED` |
| **stall** | Aguarda slot (bloqueia virtual thread) | HTTP 429 se timeout expirar |

### Headers de Resposta

| Header | Quando | Descrição |
|--------|--------|-----------|
| `x-rate-limit` | Sempre que rate limit atua | `REJECTED` ou `DELAYED` |
| `x-rate-limit-zone` | Rate limit ativado | Nome da zona |
| `x-rate-limit-scope` | Rate limit ativado | `route` ou `backend` |
| `Retry-After` | HTTP 429 | Timeout da zona (segundos) |

### Métricas

| Métrica | Tipo | Tags | Descrição |
|---------|------|------|-----------|
| `ishin.ratelimit.total` | Counter | scope, zone, result | Eventos de rate limiting (ALLOWED/REJECTED/DELAYED) |
| `ishin.ratelimit.available_permits` | Gauge | key | Permits disponíveis por rate limiter |

Para configuração detalhada, veja [docs/rate-limiting.md](rate-limiting.md) e [docs/configuration.md](configuration.md#rate-limiting).

---

## Tunnel Mode

Quando `mode: tunnel`, o ishin-gateway expõe métricas TCP específicas via `TunnelMetrics`:

### Métricas de Conexão

| Métrica | Tipo | Tags | Descrição |
|---------|------|------|-----------|
| `ishin.tunnel.connections.total` | Counter | vport, member | Total de conexões TCP aceitas |
| `ishin.tunnel.connections.active` | Gauge | vport, member | Conexões TCP ativas no momento |
| `ishin.tunnel.session.duration` | Timer | vport, member | Duração da sessão TCP (ms) |

### Métricas de Throughput

| Métrica | Tipo | Tags | Descrição |
|---------|------|------|-----------|
| `ishin.tunnel.bytes.sent` | Counter | vport, member | Bytes enviados (client → backend) |
| `ishin.tunnel.bytes.received` | Counter | vport, member | Bytes recebidos (backend → client) |

### Métricas de Erro

| Métrica | Tipo | Tags | Descrição |
|---------|------|------|-----------|
| `ishin.tunnel.connect.errors` | Counter | vport, member, error | Erros de connect (refused, timeout, no_route) |
| `ishin.tunnel.connect.duration` | Timer | vport, member | Latência de connect ao backend (ms) |

### Métricas de Pool

| Métrica | Tipo | Tags | Descrição |
|---------|------|------|-----------|
| `ishin.tunnel.pool.members` | Gauge | vport, status | Membros por status (ACTIVE, STANDBY, DRAINING) |
| `ishin.tunnel.pool.removals` | Counter | vport, member, reason | Remoções do pool (graceful, keepalive_timeout, io_exception) |
| `ishin.tunnel.pool.standby_promotions` | Counter | vport | Promoções de STANDBY → ACTIVE |
| `ishin.tunnel.listeners.active` | Gauge | — | Listeners TCP ativos |

Para configuração do Tunnel Mode, veja [docs/configuration.md](configuration.md#tunnel-mode).

---

## Dashboard de Observabilidade

O ishin-gateway embute um dashboard Web para monitoramento em tempo real, acessível via porta dedicada (default `9200`). O dashboard é um servidor Javalin standalone que serve uma SPA React e expõe endpoints REST + WebSocket.

![Arquitetura do Dashboard](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/ishin-gateway/main/docs/diagrams/dashboard_architecture.puml)

### Acesso

```bash
# Dashboard UI
http://localhost:9200/

# API REST
http://localhost:9200/api/v1/metrics/current
http://localhost:9200/api/v1/metrics/history?name=ishin.request.duration&from=...&to=...
http://localhost:9200/api/v1/topology
http://localhost:9200/api/v1/health
```

### Configuração YAML

```yaml
dashboard:
  enabled: true
  port: 9200
  bind-address: "0.0.0.0"
  allowed-ips:
    - "127.0.0.1"
    - "::1"
    - "10.0.0.0/8"
    - "172.16.0.0/12"
    - "192.168.0.0/16"
  storage:
    path: "./data/dashboard"
    scrape-interval-seconds: 15
  zipkin:
    enabled: false
    base-url: "http://localhost:9411"
```

### Modelo RRD (Round Robin Database)

O dashboard persiste métricas históricas em H2 embedded com um modelo inspirado em RRDtool. Os dados são armazenados em 4 tiers de resolução decrescente, com consolidação automática:

| Tier | Intervalo | Retenção | Campos | ~Pontos/métrica |
|------|-----------|----------|--------|-----------------|
| `raw` | 15s (scrape) | 6 horas | min, avg, max, count | ~1.440 |
| `5min` | 5 minutos | 7 dias | min, avg, max, count | ~2.016 |
| `10min` | 10 minutos | 30 dias | min, avg, max, count | ~4.320 |
| `1hour` | 1 hora | 365 dias | min, avg, max, count | ~8.760 |

**Consolidação:** A cada intervalo, dados do tier fonte são agregados para o tier destino via média ponderada pelo count. As consolidações são:

- `raw → 5min` — a cada 5 minutos
- `5min → 10min` — a cada 10 minutos
- `10min → 1hour` — a cada 1 hora

**Purge:** A cada 1 hora, dados mais antigos que a retenção de cada tier são removidos automaticamente.

**Volume previsto:** Com ~30 métricas monitoradas, o volume total no pior cenário é de ~500K registros (~50MB em H2), mantendo-se constante ao longo do tempo.

### API REST

| Endpoint | Método | Descrição |
|----------|--------|-----------|
| `/api/v1/metrics/current` | GET | Métricas atuais do MeterRegistry (snapshot) |
| `/api/v1/metrics/history` | GET | Série histórica com resolução automática de tier |
| `/api/v1/metrics/names` | GET | Nomes de métricas disponíveis |
| `/api/v1/metrics/tiers` | GET | Tiers RRD disponíveis com retenção |
| `/api/v1/topology` | GET | Grafo de topologia (listeners → gateway → backends) |
| `/api/v1/traces` | GET | Proxy para Zipkin API v2 (se habilitado) |
| `/api/v1/traces/{traceId}` | GET | Proxy para trace específico no Zipkin |
| `/api/v1/health` | GET | Status de saúde do ishin-gateway |
| `/api/v1/events` | GET | Últimos N eventos do sistema |
| `/ws/metrics` | WebSocket | Push de métricas em tempo real (a cada 5s) |

#### Parâmetros de `/api/v1/metrics/history`

| Parâmetro | Tipo | Obrigatório | Descrição |
|-----------|------|-------------|-----------|
| `name` | string | ✅ | Nome da métrica (ex: `ishin.request.duration`) |
| `from` | ISO 8601 | — | Início do range (default: 1h atrás) |
| `to` | ISO 8601 | — | Fim do range (default: agora) |
| `tier` | string | — | Forçar tier específico (default: auto) |

**Resolução automática de tier:**

| Range | Tier selecionado |
|-------|-----------------|
| ≤ 6h | `raw` |
| ≤ 24h | `5min` |
| ≤ 7d | `10min` |
| > 7d | `1hour` |

### IP Allowlisting

O dashboard valida o IP do cliente contra a lista `allowed-ips` antes de processar qualquer request. Suporta:

- IPv4 e IPv6
- Ranges CIDR (ex: `10.0.0.0/8`)
- Responde **403 Forbidden** para IPs não autorizados

### Frontend

O frontend é uma SPA React 19 com Vite, compilada para `src/main/resources/static/dashboard/` e servida como asset estático pelo Javalin.

**Componentes principais:**

| Componente | Função |
|-----------|--------|
| `MetricsCards` | 6 KPIs com cores dinâmicas + sparkline drill-down por click |
| `TopologyView` | Visualização de grafo com React Flow |
| `LatencyChart` | Gráfico Recharts com 3 linhas (min/avg/max) e badge de tier |
| `TracesPanel` | Lista de traces Zipkin com busca e detalhamento de spans |
| `EventTimeline` | Timeline vertical de eventos do sistema |

**Design:** Paleta "Nordic Tech" (fundo `#1A1B1E`, destaque `#748FFC`, secundário `#A5D8FF`, texto `#C1C2C5`).

### Desenvolvimento

```bash
# Instalar dependências do frontend
cd ishin-gateway-ui && npm install

# Dev server com hot reload (proxy para backend na porta 9200)
npm run dev   # http://localhost:3000

# Build de produção (output em src/main/resources/static/dashboard/)
npm run build
```

