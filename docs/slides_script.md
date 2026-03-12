# n-gate — Prompt para Apresentação de Slides

> Prompt para geração de deck de slides (Google Slides, Keynote, PowerPoint ou ferramentas AI como Gamma, Beautiful.ai, Tome).

---

## Prompt

Crie uma apresentação de slides profissional para o **n-gate** — um API Gateway & Reverse Proxy programável de alta performance construído em Java 21. A apresentação é direcionada a **engenheiros de software, arquitetos de soluções e tech leads** que avaliam soluções de API Gateway para ambientes de produção.

**Estilo visual:** Dark mode com fundo `#0d1117`. Tipografia moderna (Inter ou similar sans-serif). Cor primária `#58a6ff` (azul-elétrico), accent `#f78166` (laranja para destaques), success `#3fb950` (verde para status/health). Minimalista e técnico — nada de clip art ou imagens genéricas. Use diagramas técnicos, blocos de código estilizados e ícones flat.

**Tom:** Técnico mas acessível. Direto ao ponto. Cada slide deve comunicar **uma ideia central** com suporte visual claro.

---

## Estrutura dos Slides

### Slide 1 — Capa

- **Título:** n-gate
- **Subtítulo:** Programmable API Gateway & Reverse Proxy
- **Tagline:** Alta performance · Observável por padrão · Escalável horizontalmente
- **Badges visuais:** Java 21 · Virtual Threads · Groovy · Open Source
- **Elemento visual:** Ícone minimalista de gateway com fluxo de requests

---

### Slide 2 — O Problema

**Título:** Por que mais um API Gateway?

Apresentar em formato de lista com ícones:

| Problema | Impacto |
|----------|---------|
| Roteamento estático e rígido | Deploys desnecessários para mudanças simples |
| Backends frágeis sem proteção | Cascading failures em produção |
| Spikes de tráfego incontroláveis | Sobrecarga e queda de serviços |
| Single point of failure | Indisponibilidade total |
| Falta de visibilidade no tráfego | Debugging cego em produção |
| Operação de rules complexa | Processos manuais propensos a erro |

---

### Slide 3 — A Solução

**Título:** n-gate resolve cada um desses problemas

Tabela visual lado a lado (Problema → Solução):

- Roteamento estático → **Scripts Groovy com hot-reload em runtime**
- Backends frágeis → **Circuit breaker + Upstream Pool com health check ativo e passivo**
- Spikes de tráfego → **Rate limiting granular (listener / rota / backend)**
- Single point of failure → **Cluster mode NGrid com leader election**
- Falta de visibilidade → **13 spans semânticos por request + Prometheus**
- Operação complexa → **CLI dedicado (`ngate-cli`) + Admin API REST**

---

### Slide 4 — Arquitetura de Alto Nível

**Título:** Arquitetura

Diagrama visual mostrando o fluxo:

```
Clients → [n-gate] → Backends
              │
   ┌──────────┼──────────┐
   │          │          │
  JWT/    Groovy     OkHttp 4
  OAuth   Rules     (Virtual
  Decoder  Engine    Threads)
              │
        ─── Brave/Zipkin ───
```

Componentrar: Javalin 7 (Jetty 12), múltiplos listeners, connection pooling, async logging (LMAX Disruptor).

---

### Slide 5 — Upstream Pool & Load Balancing

**Título:** Load Balancing Inteligente

**Coluna esquerda — Estratégias:**
- Weighted Round-Robin
- Failover (active-passive)
- Random ponderado
- Priority Groups (Tier 1 primário, Tier 2 backup)

**Coluna direita — Health Checks:**
- **Active:** Probes periódicos em Virtual Threads
- **Passive:** Sliding windows monitoram status codes do tráfego real
- Auto-recovery com período configurável
- Coexistência: qualquer mecanismo pode marcar DOWN

Bloco de código YAML compacto:
```yaml
backends:
  api:
    strategy: "round-robin"
    members:
      - url: "http://server1:8080"
        priority: 1
        weight: 3
      - url: "http://backup:8080"
        priority: 2
    healthCheck:
      enabled: true
      path: "/health"
    passiveHealthCheck:
      enabled: true
      statusCodes:
        503: { maxOccurrences: 4, slidingWindowSeconds: 60 }
```

---

### Slide 6 — Motor de Regras Groovy

**Título:** Programável em Runtime

**Bullet points:**
- Scripts Groovy executados no hot path
- Hot-reload automático a cada 60s
- ~600µs por execução de script

**Capacidades (ícones + texto curto):**
- 🔀 Roteamento dinâmico por path, header, IP
- 🎭 Respostas sintéticas (mock/stub)
- 🔧 Response Processors (transformação de resposta)
- 🍪 Manipulação de cookies
- ↪️ Redirecionamentos condicionais
- 🛡️ Controle de acesso e validação
- 🔗 Composição de múltiplos backends

Bloco de código Groovy compacto:
```groovy
def path = context.path()
if (path.startsWith("/api/v2/"))
    upstreamRequest.setBackend("api-v2")
else if (path == "/health") {
    def synth = workload.createSynthResponse()
    synth.setContent('{"status":"UP"}')
    synth.setStatus(200)
}
```

---

### Slide 7 — Resiliência

**Título:** Circuit Breaker + Rate Limiting

**Lado esquerdo — Circuit Breaker (Resilience4j):**
- Diagrama de estados: CLOSED → OPEN → HALF_OPEN → CLOSED
- Por backend, com sliding window configurável
- HTTP 503 + header `x-circuit-breaker: OPEN`

**Lado direito — Rate Limiting:**
- 3 escopos: listener, rota, backend
- Modo `nowait` → rejeição imediata (429)
- Modo `stall` → aguarda slot em Virtual Thread
- Zonas nomeadas reutilizáveis

---

### Slide 8 — Autenticação

**Título:** JWT + OAuth2 — Transparente para seus serviços

**Diagrama de fluxo bidirecional:**

```
Cliente ──[JWT]──► n-gate ──[OAuth2 Bearer]──► Backend
          validação              injeção
          via JWKS             automática
```

- **Inbound:** Validação JWT com JWKS (Keycloak, Auth0, etc.) — decoders customizáveis via Groovy
- **Outbound:** Client credentials / password grant com refresh proativo e cache
- **Cluster:** Tokens compartilhados via POW-RBL (evita logins duplicados entre nós)

---

### Slide 9 — Cluster Mode

**Título:** Escalabilidade Horizontal com NGrid

**Diagrama de 3 nós em mesh:**

```
┌──────────┐   ┌──────────┐   ┌──────────┐
│ n-gate-1 │◄─►│ n-gate-2 │◄─►│ n-gate-3 │
│  :9091   │   │  :9091   │   │  :9091   │
│  :7100   │   │  :7100   │   │  :7100   │
└──────────┘   └──────────┘   └──────────┘
         NGrid Mesh (TCP)
    DistributedMap: tokens + rules
```

**Bullet points:**
- Leader election com quorum e epoch fencing
- Token sharing (POW-RBL) — sem logins duplicados
- Rules deploy atômico — um nó recebe, todos sincronizam
- Persistência em disco com replicação configurável

---

### Slide 10 — Operação com CLI

**Título:** Admin API & CLI — Operação simplificada

**Tabela de endpoints:**

| Endpoint | Descrição |
|----------|-----------|
| `POST /admin/rules/deploy` | Upload de scripts Groovy |
| `GET /admin/rules/list` | Lista scripts do bundle ativo |
| `GET /admin/rules/version` | Versão do bundle ativo |

**CLI — `ngate-cli` (instalado via .deb):**
```bash
ngate-cli deploy /etc/n-gate/rules
ngate-cli list
ngate-cli version
```

**Destaque:** Scripts materializados em disco (não em temp dirs) — persistência e auditabilidade.

---

### Slide 11 — Observabilidade

**Título:** 13 Spans Semânticos por Request

**Diagrama de trace hierárquico:**
```
rootSpan (SERVER)
├── request-handler
│   ├── dynamic-rules
│   │   └── rules-execution
│   ├── upstream-request (CLIENT)
│   └── response-adapter
│       ├── response-setup
│       ├── response-processor
│       ├── response-headers-copy
│       └── response-send-to-client
└── token-decoder (se secured)
```

**Métricas Prometheus:**
- `ngate.requests.total` — por listener/método/status
- `ngate.upstream.duration` — latência upstream
- `ngate.ratelimit.total` — eventos de rate limiting
- `resilience4j.circuitbreaker.*` — estado do CB
- `ngate.cluster.active.members` — saúde do cluster

---

### Slide 12 — Performance

**Título:** Otimizado para o Hot Path

Tabela visual com ícones:

| Característica | Detalhe |
|----------------|---------|
| ⚡ **Threading** | Jetty 12 + OkHttp com Virtual Threads (Java 21) |
| 📡 **Streaming** | Zero-copy InputStream → OutputStream (buffer 8KB) |
| 🔌 **Connection Pool** | Pool compartilhado com keep-alive configurável |
| 📝 **Groovy** | ~600µs por execução de script |
| 📋 **Logging** | LMAX Disruptor — fora do hot path |
| 💓 **Health Check** | Virtual Threads dedicadas |

---

### Slide 13 — Tech Stack

**Título:** Tecnologias

Layout em grid com logos/ícones:

| Componente | Versão | Função |
|------------|--------|--------|
| Java (OpenJDK) | 21 | Runtime + Virtual Threads |
| Spring Boot | 3.5 | Configuração, Actuator |
| Javalin | 7 | HTTP Framework (Jetty 12) |
| OkHttp | 4 | HTTP Client |
| Groovy | 3 | Motor de regras |
| NGrid (nishi-utils) | 3.2.0 | Cluster mesh + DistributedMap |
| Resilience4j | 2.2.0 | Circuit Breaker |
| Brave/Zipkin | 6.0.3 | Distributed Tracing |
| Micrometer | — | Métricas Prometheus |

**Deploy:** `.deb` package + systemd + Docker Compose

---

### Slide 14 — Quick Start

**Título:** Pronto para rodar

```bash
# Standalone
docker compose up --build -d

# Cluster (3 nós + LB)
docker compose -f docker-compose.yml \
  -f docker-compose.cluster.yml up --build -d

# Teste rápido
curl -i http://localhost:9091/qualquer/path
```

Link: `github.com/nishisan-dev/n-gate`

---

### Slide 15 — Encerramento

**Título:** n-gate

**Três palavras centrais (grandes):**
> **Programmable. Observable. Resilient.**

**Subtítulo:**
> Java 21 · Open Source · Production Ready

**Call to action:**
> ⭐ github.com/nishisan-dev/n-gate  
> 📖 Documentação completa em `docs/`

---

## Diretrizes Gerais

| Aspecto | Especificação |
|---------|---------------|
| **Total de slides** | 15 |
| **Tempo estimado** | 15–20 minutos de apresentação |
| **Aspect ratio** | 16:9 (widescreen) |
| **Paleta** | Background `#0d1117`, texto `#e6edf3`, primária `#58a6ff`, accent `#f78166`, success `#3fb950` |
| **Tipografia** | Títulos: Inter Bold 36pt · Body: Inter Regular 18pt · Código: JetBrains Mono 14pt |
| **Máx. bullet points** | 5–6 por slide |
| **Blocos de código** | Máx. 8–10 linhas por slide, com syntax highlighting |
| **Diagramas** | Flat, minimalistas, sem sombras |
| **Animações** | Apenas fade in sequencial para bullet points. Sem animações chamativas |
