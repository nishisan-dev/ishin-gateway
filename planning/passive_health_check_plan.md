# Passive Health Check — Status Code com Sliding Window

O n-gate atualmente possui apenas **active health check** (probing periódico via `UpstreamHealthChecker`). Esta feature adiciona **passive health check** — monitoramento das respostas reais do tráfego de produção para detectar membros degradados com base em status codes observados dentro de uma janela temporal deslizante.

## Contrato YAML proposto

```yaml
backends:
  my-api:
    backendName: "my-api"
    members:
      - url: "http://server1:8080"
      - url: "http://server2:8080"
    healthCheck:          # active (já existe)
      enabled: true
      path: "/health"
    passiveHealthCheck:   # ← NOVO
      enabled: true
      statusCodes:
        503:
          maxOccurrences: 4
          slidingWindowSeconds: 60
        502:
          maxOccurrences: 3
          slidingWindowSeconds: 30
        500:
          maxOccurrences: 10
          slidingWindowSeconds: 120
      recoverySeconds: 30   # tempo que o membro fica DOWN antes de ser reavaliado
```

### Semântica

- **Por membro, por status code**: cada `UpstreamMemberState` mantém janelas deslizantes independentes para cada status code monitorado.
- **Sliding window**: registra os timestamps das ocorrências. Se `count(ocorrências no último slidingWindowSeconds) >= maxOccurrences`, o membro é marcado **DOWN**.
- **Recovery**: após `recoverySeconds`, o membro é automaticamente marcado **UP** para receber tráfego de teste. Se continuar falhando, volta para DOWN imediatamente.
- **Coexistência**: o passive e o active health check operam de forma **independente**. Qualquer um dos dois pode marcar DOWN. O active pode restaurar um membro que o passive derrubou (e vice-versa).

---

## Proposed Changes

### Componente 1: Configuração

#### [NEW] [PassiveHealthCheckConfiguration.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/PassiveHealthCheckConfiguration.java)

Configuração raiz do passive health check:
- `boolean enabled` (default: `false`)
- `Map<Integer, StatusCodeRule> statusCodes` — mapa de HTTP status → regra
- `int recoverySeconds` (default: `30`)

#### [NEW] [StatusCodeRule.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/StatusCodeRule.java)

POJO para cada regra de status code:
- `int maxOccurrences`
- `int slidingWindowSeconds`

#### [MODIFY] [BackendConfiguration.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/BackendConfiguration.java)

- Adicionar campo `PassiveHealthCheckConfiguration passiveHealthCheck` com getter/setter.

---

### Componente 2: Estrutura de dados — Sliding Window

#### [NEW] [StatusCodeSlidingWindow.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/upstream/StatusCodeSlidingWindow.java)

Janela deslizante baseada em timestamps — uma instância por (membro, status code):
- Internamente usa `ConcurrentLinkedDeque<Long>` com timestamps em millis.
- `record(long timestampMs)` — adiciona ocorrência e faz eviction dos expirados.
- `count()` — retorna ocorrências dentro da janela ativa.
- Thread-safe sem locks pesados.

---

### Componente 3: Motor de avaliação

#### [NEW] [PassiveHealthChecker.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/upstream/PassiveHealthChecker.java)

Responsável por:
1. **`reportStatusCode(backendName, memberUrl, statusCode)`** — chamado pelo `HttpProxyManager` após cada resposta upstream. Registra na sliding window correspondente e avalia se o threshold foi violado.
2. **Recovery scheduler** — `ScheduledExecutorService` com Virtual Threads que, a cada segundo, verifica membros marcados DOWN pelo passive checker e restaura após `recoverySeconds`.
3. **`start(UpstreamPoolManager, Map<String, BackendConfiguration>)`** / **`stop()`** — ciclo de vida.

Mantém referência para `UpstreamPoolManager` para acessar os `UpstreamMemberState`.

Estrutura interna:
```
Map<String, Map<String, Map<Integer, StatusCodeSlidingWindow>>>
     ↑ backendName  ↑ memberUrl   ↑ statusCode
```

#### [MODIFY] [UpstreamMemberState.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/upstream/UpstreamMemberState.java)

- Adicionar `AtomicBoolean passivelyMarkedDown` para distinguir quem derrubou o membro.
- Adicionar `AtomicLong passiveDownSince` para controle de recovery.
- Métodos: `markPassivelyUnhealthy()`, `markPassivelyHealthy()`, `isPassivelyMarkedDown()`.
- Ajustar `isAvailable()` para considerar: `enabled && healthy && !passivelyMarkedDown`.

---

### Componente 4: Integração no fluxo de proxy

#### [MODIFY] [HttpProxyManager.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpProxyManager.java)

Após receber a resposta do upstream (em torno da linha 667, onde `res.code()` é lido), adicionar:

```java
// Passive health check: reporta status code observado
if (passiveHealthChecker != null) {
    passiveHealthChecker.reportStatusCode(backendname, memberUrl, res.code());
}
```

- Injetar `PassiveHealthChecker` via construtor (mesmo padrão dos demais managers).
- Adicionar tag de tracing: `requestSpan.tag("passive.hc.reported", true)`.

#### [MODIFY] [EndpointManager.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/manager/EndpointManager.java)

- Instanciar `PassiveHealthChecker` e passá-lo ao `HttpProxyManager`.
- Chamar `passiveHealthChecker.start(...)` junto com `healthChecker.start(...)`.
- Chamar `passiveHealthChecker.stop()` no shutdown.

---

### Componente 5: Configuração upstream do pool

#### [MODIFY] [UpstreamPool.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/upstream/UpstreamPool.java)

- Armazenar `PassiveHealthCheckConfiguration` recebida do `BackendConfiguration`.
- Expôr via `getPassiveHealthCheckConfig()`.

---

### Componente 6: Documentação

#### [MODIFY] [upstream-pool.md](file:///home/g0004218/Projects/n-gate/docs/upstream-pool.md)

- Adicionar seção "### Passive Health Check" com configuração YAML e semântica.

#### [MODIFY] [upstream_pool.puml](file:///home/g0004218/Projects/n-gate/docs/diagrams/upstream_pool.puml)

- Adicionar componentes `PassiveHealthChecker` e `StatusCodeSlidingWindow`.

---

## Verification Plan

### Automated Tests

#### [NEW] [PassiveHealthCheckerTest.java](file:///home/g0004218/Projects/n-gate/src/test/java/dev/nishisan/ngate/upstream/PassiveHealthCheckerTest.java)

| Test | Descrição |
|------|-----------|
| T1 | `statusCodeWithinWindow_marksMemberDown` — Reporta N ocorrências de 503 dentro da janela → membro fica DOWN |
| T2 | `statusCodeOutsideWindow_doesNotMarkDown` — Reporta N ocorrências de 503 espalhadas além da janela → membro continua UP |
| T3 | `multipleStatusCodes_independentWindows` — Regras para 502 e 503 operam independentemente |
| T4 | `recoveryAfterTimeout_restoresMemberUp` — Membro marcado DOWN é restaurado após `recoverySeconds` |
| T5 | `successfulStatusCode_doesNotAffectWindows` — Status 200 não registra em nenhuma janela |
| T6 | `disabledPassiveCheck_noEffect` — Com `enabled: false`, nenhum reporte afeta o membro |

#### [NEW] [StatusCodeSlidingWindowTest.java](file:///home/g0004218/Projects/n-gate/src/test/java/dev/nishisan/ngate/upstream/StatusCodeSlidingWindowTest.java)

| Test | Descrição |
|------|-----------|
| T1 | `recordAndCount_withinWindow` — Registra N eventos e valida contagem |
| T2 | `eviction_removesExpiredEntries` — Eventos antigos são removidos automaticamente |
| T3 | `concurrentAccess_threadSafe` — Múltiplas threads escrevem/lêem simultaneamente sem corrupção |

#### Comando para rodar os testes:

```bash
cd /home/g0004218/Projects/n-gate && mvn test -Dtest="PassiveHealthCheckerTest,StatusCodeSlidingWindowTest,UpstreamHealthCheckerTest" -DfailIfNoTests=false
```

### Build completo:

```bash
cd /home/g0004218/Projects/n-gate && mvn clean compile test
```
