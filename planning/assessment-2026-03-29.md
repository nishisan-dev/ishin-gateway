# Assessment Completo — ishin-gateway

**Data:** 2026-03-29

Análise realizada em 6 dimensões: **qualidade de código**, **cobertura de testes**, **dependências**, **arquitetura/configuração**, **CI/CD & Docker**, e **observabilidade/resiliência**.

---

## 1. Qualidade de Código — `HttpProxyManager` (God Class)

O `HttpProxyManager.java` é a classe mais crítica do projeto (~915 linhas) e concentra **11 responsabilidades distintas**, caracterizando claramente o antipattern *God Class*:

| Problema | Severidade | Detalhes |
|----------|-----------|---------|
| **`handleRequest()` com 315 linhas** | Critica | Método único contém: rules evaluation, backend selection, virtual host, rate limiting, circuit breaker, upstream selection, tracing, SSE detection, error handling |
| **`getHttpClientByListenerName()` com 160 linhas** | Alta | Lógica duplicada entre criação de client OAuth vs non-OAuth (linhas 215-259 vs 268-292) |
| **Leak de Response no circuit breaker path** | Critica | Se exceção ocorre entre `newCall().execute()` (linha 694) e a conversão (linha 755), o Response nunca é fechado — leak de file descriptors e exaustão do connection pool |
| **`httpClients` ConcurrentHashMap sem limite** | Alta | Cresce indefinidamente com cada backend name único — memory leak em proxies de longa duração |
| **Código morto: `handleGet()`** | Baixa | Marcado como `@deprecated` (linhas 829-862), deveria ser removido |
| **Magic strings** | Baixa | `"AUTO"`, `"-TMP"`, `"backend:"` hardcoded sem constantes |

**Sugestão**: Extrair em classes especializadas — `HttpClientFactory`, `RequestPipeline`, `ResponsePipeline`, `GroovyRulesExecutor`. O `handleRequest()` deveria ser composto por chamadas a métodos bem definidos (`selectBackend()`, `checkRateLimits()`, `executeUpstreamRequest()`, etc.).

---

## 2. Cobertura de Testes — 21% (21 de 100 classes)

A qualidade dos testes existentes é **excelente** (assertions detalhados, edge cases, state transitions). O problema é a **cobertura extremamente baixa** nas áreas mais críticas:

| Classe Sem Teste | Linhas | Risco |
|-----------------|--------|-------|
| **HttpProxyManager** | ~915 | Engine central de proxy — ZERO testes unitários |
| **OAuthClientManager** | ~468 | Lifecycle de tokens OAuth, cache, cluster sharing |
| **EndpointManager** | ~216 | Bootstrap e lifecycle de endpoints |
| **TunnelEngine** | ~195 | Core TCP tunneling, retry, failover |
| **TunnelService** | ~195 | Registro de tunnels, keepalive |
| **JWTTokenDecoder / CustomClosureDecoder** | ~150 | Validação de tokens — nenhum teste |
| **UpstreamPoolManager** | ~100 | Factory de pools, coordenação de health checks |
| **BackendCircuitBreakerManager** | ~100 | Tem integration test mas ZERO unit tests |
| **Todos os adapters HTTP** (5 classes) | ~800+ | Transformação request/response |
| **Dashboard** (8 classes) | ~500+ | Coleta de métricas, storage, API |

**Gap por categoria:**

| Categoria | Classes | Testadas | Gap |
|-----------|---------|----------|-----|
| Authentication | 15 | 0 | 100% |
| HTTP Proxy | 22 | 1 | 95% |
| Dashboard | 8 | 1 | 87% |
| Tunnel Mode | 12 | 5 | 58% |

**Sugestão**: Priorizar testes unitários para `HttpProxyManager`, `OAuthClientManager`, e `JWTTokenDecoder` — são os caminhos mais críticos e de maior risco.

---

## 3. Dependências — Outdated & Riscos

| Dependência | Versão Atual | Status | Ação |
|-------------|-------------|--------|------|
| **Groovy** | 3.0.12 | **EOL** (sem patches de segurança desde Nov/2023) | Migrar para 4.0.x |
| **Google OAuth Client** | 1.37.0 | **Legacy** (última atualização 2021) | Avaliar `com.google.auth:google-auth-library-oauth2-http` |
| **Google HTTP Client** | 1.45.3 | **Maintenance mode** | Acompanha o OAuth Client |
| **jwks-rsa** | 0.22.1 | Última atualização 2022 | Verificar patches |
| **Spring Boot** | 3.5.11 | Funcional mas existe 3.6.x | Avaliar upgrade path |

**Problemas estruturais no `pom.xml`:**
- Sem seção `<dependencyManagement>` — versões hardcoded em cada `<dependency>`
- Sem `maven-enforcer-plugin` — nada garante convergência de dependências
- **Sem OWASP Dependency-Check** — nenhum scan de CVEs automatizado
- Sem JaCoCo — nenhuma métrica de cobertura

---

## 4. Arquitetura & Configuração — Problemas Estruturais

### 4.1 Segurança — CRÍTICO

| Problema | Arquivo | Linhas |
|----------|---------|--------|
| **`System.out.println("TOKEN:" + token)`** — token JWT impresso no stdout | `JWTTokenDecoder.java` | 73 |
| **Múltiplos `System.out.println()` de claims JWT** | `JWTUserPrincipal.java` | 41, 47-66 |
| **`settings.xml` com credenciais em plain text no repo** | `settings.xml` | 7-8, 14, 21, 28 |
| **Credenciais OAuth em plain text no `adapter.yaml`** | `adapter.yaml` | 47, 49-50 |

Os dois primeiros itens são vazamentos diretos de tokens em logs. Os `System.out.println` devem ser removidos imediatamente.

### 4.2 Validação de Configuração — Inexistente

Nenhum POJO de configuração em `configuration/` possui validação:
- Sem `@NotNull`, `@NotBlank`, `@NotEmpty`, `@Min`, `@Max`
- `members` em `BackendConfiguration` pode estar vazio sem erro
- `listenPort` aceita qualquer valor (99999, -1)
- `defaultBackend` não valida se o backend referenciado existe
- `ListenerSecurityConfiguration` é basicamente um POJO vazio — implementação incompleta

**Resultado**: Configurações inválidas são aceitas silenciosamente e só falham em runtime.

### 4.3 Shutdown Graceful — Incompleto

- **`EndpointManager.@PreDestroy`**: Pára listeners imediatamente sem drain de requests in-flight
- **`OAuthClientManager`**: Thread de refresh de token (`tokenRefreshThread`) **não tem `@PreDestroy`** — continua rodando após shutdown
- **`ClusterService.@PreDestroy`**: `gridNode.close()` sem timeout — pode bloquear indefinidamente

### 4.4 Null Safety & Resource Leaks

- `ConfigurationManager:152` — `break` no catch deveria ser `continue` para tentar próximo path de config
- `ConfigurationManager:141-150` — `FileReader` não usa try-with-resources — leak se `readValue()` lança exceção
- `EndpointManager:141` — `upstreamPoolManager.initialize(epConfig.getBackends())` sem null check, mas `epConfig.getBackends()` é verificado em outra linha

### 4.5 Imutabilidade

- Todos os POJOs de configuração são mutáveis (setters públicos) — risco de race condition se config é modificada durante request processing
- `BackendConfiguration.members` é `ArrayList` (não thread-safe), enquanto `listeners` usa `ConcurrentHashMap`

---

## 5. CI/CD & Docker

### 5.1 Pipeline (`release.yml`)

| Problema | Severidade |
|----------|-----------|
| **Integration tests excluídos do CI** — `!*IntegrationTest` (linha 78) | Alta |
| **Sem scan de vulnerabilidades** — Docker image nunca é escaneada (Trivy/Grype) | Alta |
| **Sem `npm audit`** no build do frontend | Média |
| **Sem Docker build cache** — `cache-from`/`cache-to` não configurados | Média |
| **Sem static analysis** — Checkstyle, SpotBugs ausentes | Média |
| **Builds sequenciais** — frontend e backend poderiam rodar em paralelo | Baixa |

### 5.2 Docker

| Problema | Severidade |
|----------|-----------|
| **Todos os Dockerfiles rodam como root** — nenhum `USER` directive | Alta |
| **Sem `HEALTHCHECK`** nos Dockerfiles de produção | Média |
| **Imagem não otimizada** — usa `eclipse-temurin:21-jre` (~200MB) em vez de `-slim` (~100MB) | Baixa |
| **docker-compose.yml**: gateway sem healthcheck (outros serviços têm) | Média |
| **docker-compose**: sem resource limits (`deploy.resources.limits`) | Média |

### 5.3 Debian Package

- `debian/control`: `curl` e `jq` como hard dependencies (`Depends`) — deveriam ser `Recommends`
- Systemd service sem `PrivateTmp=yes`, `ProtectHome=yes`

---

## 6. Observabilidade & Resiliência

### 6.1 Métricas — Blind Spots Significativos

| Gap | Impacto |
|-----|---------|
| **Erros sem tipo** — `recordUpstreamError()` não distingue timeout vs connection refused vs 5xx | Impossível diagnosticar causa de outages |
| **Health checks passivos sem métricas** — transições DOWN/UP só logadas, não metrificadas | Degradação passiva invisível em dashboards |
| **Health checks ativos sem métricas** — probe success/failure não registrado | Sem trending de saúde dos pools |
| **Sem gauges de pool** — `members.healthy`, `members.total` por backend | Não se vê degradação de pool |
| **Seleção de upstream falhando sem métrica** — `selectMember()` retornando empty não é contado | Sem visibilidade de "todos os backends down" |
| **Script Groovy errors sem tipo** — compile error vs runtime exception indistinguíveis | Debugging de rules prejudicado |

### 6.2 Rate Limiter — Possível Bug de Design

Em `RateLimitManager.java:177-211`, quando um permit é adquirido do `Semaphore`, **ele nunca é devolvido** (`release()` nunca é chamado). Os permits só retornam via reset scheduler periódico. Isso significa que o semáforo drena monotonicamente até o próximo reset — o que pode ser intencional (rate limiting baseado em janela), mas se não for, é um bug que esgota os permits antes do período configurado.

### 6.3 Circuit Breaker

- Sem métrica customizada de rejeições por backend (depende apenas do default do Resilience4j)
- Não suporta hot-reload de configuração — requer restart para retunar thresholds

### 6.4 Tracing

- Zipkin sender failures **silenciosos** — spans dropados sem métrica de "send failures"
- `SpanWrapper.finish()` não verifica se erro foi registrado — traces de erro podem ficar incompletos
- Log4j2 sem structured logging (JSON) — dificulta parsing em log aggregation

### 6.5 Health Indicator

O `/actuator/health` reporta info de cluster mas **não inclui**:
- Quantidade de backends healthy/degraded
- Estado dos circuit breakers (OPEN/HALF_OPEN/CLOSED)
- Status dos passive health checks

---

## Resumo por Prioridade

### P0 — Correção Imediata
1. Remover `System.out.println` de tokens em `JWTTokenDecoder` e `JWTUserPrincipal`
2. Remover `settings.xml` do repositório e rotacionar as credenciais expostas
3. Corrigir leak de `Response` no path do circuit breaker em `HttpProxyManager`

### P1 — Curto Prazo
4. Adicionar validação nos POJOs de configuração (Jakarta Bean Validation)
5. Implementar shutdown graceful com drain de requests e timeout
6. Corrigir resource leak no `ConfigurationManager` (try-with-resources)
7. Adicionar `USER` non-root nos Dockerfiles
8. Incluir integration tests no pipeline de CI

### P2 — Médio Prazo
9. Refatorar `HttpProxyManager` — extrair responsabilidades em classes coesas
10. Aumentar cobertura de testes (priorizar `HttpProxyManager`, `OAuthClientManager`, `JWTTokenDecoder`)
11. Migrar Groovy 3.0.12 → 4.0.x (EOL)
12. Adicionar OWASP Dependency-Check e JaCoCo no build
13. Adicionar métricas de error type, health check transitions, pool health gauges
14. Implementar structured logging (JSON) no Log4j2

### P3 — Longo Prazo
15. Avaliar migração das Google OAuth/HTTP Client libraries (legacy)
16. Implementar hot-reload de circuit breaker config
17. Enriquecer `/actuator/health` com status de backends e circuit breakers
18. Otimizar imagens Docker (`-slim`, build cache, vulnerability scanning)
