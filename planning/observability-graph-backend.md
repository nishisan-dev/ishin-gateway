# Backend do Grafo de Observabilidade — ishin-gateway

## Contexto

O ishin-gateway já possui métricas Micrometer para inbound (por listener) e upstream (por backend) via `ProxyMetrics`, coletadas automaticamente pelo `MetricsCollectorService` (prefixo `ishin.*`) e persistidas no H2/RRD via `DashboardStorageService`.

O objetivo é estender a instrumentação para incluir **contextos HTTP** e **scripts Groovy**, enriquecer a **topologia REST** com nós `context` e `script`, e garantir que tudo persista no RRD/H2.

**Arquivos modificados**: 3 existentes.  
**Arquivos criados**: 2 testes novos.

---

## Proposed Changes

### Componente 1: Instrumentação de Métricas

#### [MODIFY] [ProxyMetrics.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/observability/ProxyMetrics.java)

Adicionar 4 novos métodos seguindo o padrão existente (Counter + Timer com cache por chave):

1. **`recordContextRequest(listener, context, method, status, durationMs)`**
   - Counter: `ishin.context.requests.total` — tags: `listener`, `context`, `method`, `status`
   - Timer: `ishin.context.duration` — tags: `listener`, `context`, `method`
   - Permite derivar: req/s por contexto, avg/min/max latência por contexto

2. **`recordContextError(listener, context, method)`**
   - Counter: `ishin.context.errors` — tags: `listener`, `context`, `method`

3. **`recordScriptExecution(listener, context, script, durationMs)`**
   - Counter: `ishin.script.executions.total` — tags: `listener`, `context`, `script`
   - Timer: `ishin.script.duration` — tags: `listener`, `context`, `script`
   - Permite derivar: exec/s por script, avg/min/max do tempo de execução

4. **`recordScriptError(listener, context, script)`**
   - Counter: `ishin.script.errors` — tags: `listener`, `context`, `script`

> [!NOTE]
> O `MetricsCollectorService` já coleta automaticamente qualquer meter com prefixo `ishin.*`, expandindo Timers para `.count`, `.mean` e `.max`. As novas métricas serão **automaticamente** coletadas e persistidas no H2/RRD sem modificações no collector.

---

### Componente 2: Instrumentação no Hot Path

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/http/HttpProxyManager.java)

Modificar `evalDynamicRules()` (L409-472) para:

1. **Métricas de Script**: Antes/depois de `gse.run(runningScript, localBindings)` no loop `while` (L442-460):
   - Medir duração de cada execução de script
   - Chamar `proxyMetrics.recordScriptExecution(listenerName, contextName, scriptName, durationMs)`
   - No catch de erro, chamar `proxyMetrics.recordScriptError(listenerName, contextName, scriptName)`

2. **Métricas de Contexto**: Medir duração total do `evalDynamicRules()`:
   - Chamar `proxyMetrics.recordContextRequest(...)` no final do método com a duração total
   - No handler `handleRequest()` (L481), registrar erros de contexto quando aplicável

> [!IMPORTANT]
> A métrica de **contexto** será adicionada em `EndpointWrapper.registerRoutes()` (no bloco `finally` do handler), e **não** dentro de `evalDynamicRules()`, para capturar a latência completa do contexto (incluindo upstream). `evalDynamicRules()` mede apenas os scripts.

#### [MODIFY] [EndpointWrapper.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/http/EndpointWrapper.java)

No bloco `finally` de ambos os handlers (L252-261 para handler typed, L390-399 para ANY handler):
- Adicionar chamada `proxyMetrics.recordContextRequest(name, contextName, method, statusCode, durationMs)` ao lado do `recordInboundRequest` existente
- O `contextName` e `method` já estão disponíveis no escopo do lambda via `urlContext`

---

### Componente 3: Enriquecer Topologia

#### [MODIFY] [DashboardApiRoutes.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/dashboard/api/DashboardApiRoutes.java)

Modificar `buildTopology()` (L178-261) para adicionar nós e edges de contexto + script:

**Nós de contexto** (dentro do loop de listeners, após criar o listener node):
```json
{
  "id": "context:<listener>:<contextName>",
  "type": "context",
  "label": "<contextName>",
  "listener": "<listenerName>",
  "contextPath": "<urlContext.getContext()>",
  "method": "<urlContext.getMethod()>",
  "ruleMapping": "<urlContext.getRuleMapping()>",
  "secured": <urlContext.getSecured()>
}
```

**Nós de script** (quando ruleMapping != null e não vazio):
```json
{
  "id": "script:<listener>:<contextName>:<ruleMapping>",
  "type": "script",
  "label": "<ruleMapping>",
  "script": "<ruleMapping>",
  "context": "context:<listener>:<contextName>"
}
```

**Edges novos**:
- `listener → context` (type: `context`)
- `context → ishin` (type: `inbound-context`)
- `context → script` (type: `script-exec`) — quando script existir

> [!NOTE]
> A edge `listener → ishin` existente será mantida para backward-compatibility. Os nós e edges novos são aditivos.

---

### Componente 4: Testes

#### [NEW] [ContextScriptMetricsTest.java](file:///home/lucas/Projects/ishin-gateway/src/test/java/dev/nishisan/ishin/observability/ContextScriptMetricsTest.java)

Teste **unitário** (sem containers) que valida:
- `ProxyMetrics.recordContextRequest()` registra Counter + Timer no MeterRegistry
- `ProxyMetrics.recordScriptExecution()` registra Counter + Timer no MeterRegistry
- `ProxyMetrics.recordContextError()` registra Counter
- `ProxyMetrics.recordScriptError()` registra Counter
- Tags corretas em cada meter
- Coleta pelo `MetricsCollectorService.getCurrentMetrics()` retorna as novas métricas
- Persistência pelo `MetricsCollectorService.collect()` + `DashboardStorageService` salva e recupera

#### [NEW] [TopologyContextScriptTest.java](file:///home/lucas/Projects/ishin-gateway/src/test/java/dev/nishisan/ishin/observability/TopologyContextScriptTest.java)

Teste **unitário** que valida:
- Criar um `ServerConfiguration` com listeners contendo urlContexts com ruleMapping
- Chamar `DashboardApiRoutes.buildTopology()` (via reflexão ou extraindo o método para testável)
- Verificar que nodes contêm itens com `type: "context"` e `type: "script"`
- Verificar que edges contêm `listener → context`, `context → ishin`, `context → script`
- Verificar que nodes existentes (`gateway`, `listener`, `backend`) permanecem inalterados

---

## Verificação

### Testes Automatizados

```bash
# Executar todos os testes do módulo
cd /home/lucas/Projects/ishin-gateway && mvn test -pl .

# Executar apenas os novos testes
cd /home/lucas/Projects/ishin-gateway && mvn test -Dtest="ContextScriptMetricsTest,TopologyContextScriptTest"
```

### Checklist Manual

Após deploy em ambiente local ou staging, validar com `curl`:

```bash
# 1. Novas métricas visíveis em /metrics/names
curl http://localhost:<dashboard-port>/api/v1/metrics/names | jq '.[] | select(startswith("ishin.context") or startswith("ishin.script"))'

# 2. Métricas com tags em /metrics/current
curl http://localhost:<dashboard-port>/api/v1/metrics/current | jq 'to_entries[] | select(.key | startswith("ishin.context") or startswith("ishin.script"))'

# 3. Histórico funcional
curl "http://localhost:<dashboard-port>/api/v1/metrics/history?name=ishin.context.duration.mean"

# 4. Topologia enriquecida
curl http://localhost:<dashboard-port>/api/v1/topology | jq '.nodes[] | select(.type == "context" or .type == "script")'

# 5. Backward-compatibility: nós existentes intactos
curl http://localhost:<dashboard-port>/api/v1/topology | jq '.nodes[] | select(.type == "listener" or .type == "gateway" or .type == "backend")'
```

---

## Métricas Criadas (Referência)

| Métrica | Tipo | Tags | Deriváveis |
|---|---|---|---|
| `ishin.context.requests.total` | Counter | `listener`, `context`, `method`, `status` | req/s por contexto |
| `ishin.context.duration` | Timer | `listener`, `context`, `method` | avg/min/max latência por contexto |
| `ishin.context.errors` | Counter | `listener`, `context`, `method` | error rate por contexto |
| `ishin.script.executions.total` | Counter | `listener`, `context`, `script` | exec/s por script |
| `ishin.script.duration` | Timer | `listener`, `context`, `script` | avg/min/max execução do script |
| `ishin.script.errors` | Counter | `listener`, `context`, `script` | error rate por script |

> [!NOTE]
> O collector expande Timers automaticamente em `.count`, `.mean` e `.max`, todos persistidos no H2/RRD com consolidação multi-tier (raw 6h → 5min 7d → 10min 30d → 1hour 365d).
