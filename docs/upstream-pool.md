# Upstream Pool

O Upstream Pool permite definir múltiplos servidores backend para cada serviço, com balanceamento de carga, priority groups e health checks ativos.

## Arquitetura

![Upstream Pool](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/ishin-gateway/main/docs/diagrams/upstream_pool.puml)

## Configuração

### Estrutura Básica

```yaml
backends:
  my-api:
    backendName: "my-api"
    strategy: "round-robin"     # round-robin | failover | random
    members:
      - url: "http://server1:8080"
        priority: 1              # 1 = mais prioritário (default: 1)
        weight: 3                # peso relativo no round-robin (default: 1)
      - url: "http://server2:8080"
        priority: 1
        weight: 1
      - url: "http://backup:8080"
        priority: 2              # só usado se tier 1 estiver 100% down
        enabled: true            # pode ser desabilitado sem remover (default: true)
    healthCheck:
      enabled: true
      path: "/health"
      intervalSeconds: 10
      timeoutMs: 3000
      unhealthyThreshold: 3     # falhas consecutivas para marcar DOWN
      healthyThreshold: 2       # sucessos consecutivos para marcar UP
```

### Estratégias de Balanceamento

| Estratégia    | Comportamento |
|---------------|---------------|
| `round-robin` | **Weighted Round-Robin** — distribui requests proporcionalmente ao `weight` de cada membro. Default. |
| `failover`    | Sempre usa o primeiro membro disponível. Só muda quando o primário cai. |
| `random`      | Seleção aleatória ponderada pelo `weight`. |

### Priority Groups

Membros são agrupados por `priority` (1 = mais prioritário). O pool sempre tenta usar o tier de menor valor numérico. Só faz fallback para o próximo tier quando **todos** os membros do tier atual estão DOWN ou desabilitados.

**Exemplo**: primário + backup

```yaml
members:
  - url: "http://primary:8080"
    priority: 1
  - url: "http://backup:8080"
    priority: 2
```

### Health Check Ativo

Quando habilitado (`healthCheck.enabled: true`), o ishin-gateway faz probes periódicos em cada membro:

- **Probe**: `GET <member-url><path>` (ex: `http://server1:8080/health`)
- **Sucesso**: HTTP 2xx
- **Falha**: HTTP não-2xx ou erro de conexão/timeout
- **Transição DOWN**: após `unhealthyThreshold` falhas consecutivas
- **Transição UP**: após `healthyThreshold` sucessos consecutivos
- **Virtual Threads**: cada probe roda em Virtual Thread (Java 21), sem bloquear threads de plataforma

### Sem Health Check

Se `healthCheck` não for configurado ou `enabled: false`, todos os membros são considerados permanentemente saudáveis. Útil para backends internos confiáveis ou quando há health check externo.

### Passive Health Check

O passive health check monitora as **respostas reais do tráfego de produção** para detectar membros degradados com base em status codes observados dentro de uma janela temporal deslizante. Opera de forma independente do active health check.

```yaml
backends:
  my-api:
    passiveHealthCheck:
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
      recoverySeconds: 30
```

**Semântica:**

- **Por membro, por status code**: cada membro mantém janelas deslizantes independentes
- **Sliding window**: se `count(ocorrências no último slidingWindowSeconds) >= maxOccurrences`, o membro é marcado **DOWN**
- **Recovery**: após `recoverySeconds`, o membro é automaticamente restaurado para receber tráfego de teste. Se continuar falhando, volta imediatamente para DOWN
- **Coexistência**: active e passive operam independentemente — qualquer um pode marcar DOWN

## Comportamento em Runtime

1. Request chega no ishin-gateway
2. `HttpProxyManager` resolve o backend via regras/default
3. `UpstreamPoolManager.selectMember(backendName)` seleciona um membro saudável
4. Se nenhum membro disponível → **503 Service Unavailable** com header `x-upstream-pool`
5. O membro selecionado é usado para construir a request upstream
6. Tag `upstream-member-url` adicionada ao span de tracing

## Componentes

| Classe | Responsabilidade |
|--------|------------------|
| `UpstreamMemberConfiguration` | Configuração estática: url, priority, weight, enabled |
| `UpstreamHealthCheckConfiguration` | Configuração do health check: path, interval, thresholds |
| `BackendConfiguration` | Agrupa members, strategy, healthCheck para um backend |
| `UpstreamMemberState` | Estado runtime thread-safe: healthy, contadores de falhas/sucessos |
| `UpstreamPool` | Lógica de seleção com priority groups + estratégia de LB |
| `UpstreamPoolManager` | Spring `@Component` — gerencia pools por backendName |
| `UpstreamHealthChecker` | Probing periódico com Virtual Threads e transições threshold-based |
| `PassiveHealthChecker` | Monitoramento de status codes do tráfego real com sliding windows |
| `StatusCodeSlidingWindow` | Janela deslizante thread-safe por (membro, status code) |
| `PassiveHealthCheckConfiguration` | Configuração do passive HC: regras por status code, recovery |
| `StatusCodeRule` | Regra individual: maxOccurrences e slidingWindowSeconds |

## API Breaker

> **Breaking Change**: O campo `endPointUrl` foi removido de `BackendConfiguration`.  
> Todos os backends devem definir `members` explicitamente.

**Antes:**
```yaml
backends:
  my-api:
    backendName: "my-api"
    endPointUrl: "http://server1:8080"
```

**Depois:**
```yaml
backends:
  my-api:
    backendName: "my-api"
    members:
      - url: "http://server1:8080"
```
