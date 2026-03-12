# n-gate — Video Script Prompt

> Prompt para geração de vídeo promocional/demonstrativo do projeto n-gate.

---

## Prompt Principal

Crie um vídeo de **2 a 3 minutos** estilo **tech product showcase** para o **n-gate** — um API Gateway & Reverse Proxy de alta performance construído em Java 21. O vídeo deve transmitir **confiabilidade, velocidade e controle total** sobre o tráfego HTTP. Tom visual: **dark mode**, tipografia moderna (Inter/JetBrains Mono), cores predominantes em **azul-elétrico e ciano** com acentos em **laranja** para destaques. Estilo de motion graphics minimalista e técnico, similar a vídeos de produto da Cloudflare, Datadog ou HashiCorp.

---

## Estrutura do Vídeo

### Cena 1 — Hook (0:00–0:10)

**Visual:** Tela escura. Aparecem linhas de request HTTP animadas fluindo da esquerda para a direita, passando por um ícone central do n-gate que as distribui para múltiplos backends.

**Texto em tela:**
> "Your traffic. Your rules. Zero compromise."

**Narração/Texto:**
> "E se você tivesse controle total sobre cada request HTTP — em runtime, com observabilidade completa e sem sacrificar performance?"

---

### Cena 2 — O que é (0:10–0:25)

**Visual:** Logo do n-gate com tagline animada. Badges de tecnologia aparecem ao redor: Java 21, Virtual Threads, Groovy, NGrid.

**Texto em tela:**
> **n-gate** — Programmable API Gateway & Reverse Proxy  
> Built on Java 21 · Virtual Threads · Javalin 7 · Jetty 12

**Pontos-chave (aparecem em sequência):**
- Proxy reverso HTTP de alta performance
- Motor de regras Groovy com hot-reload
- Cluster mode com coordenação distribuída
- Observabilidade nativa de ponta a ponta

---

### Cena 3 — Upstream Pool & Load Balancing (0:25–0:50)

**Visual:** Diagrama animado mostrando requests chegando e sendo distribuídos entre múltiplos servidores backend. Servidores piscam verde (saudáveis) e vermelho (degradados). Um servidor é removido automaticamente do pool.

**Conceitos demonstrados:**
- **3 estratégias de balanceamento:** Round-Robin ponderado, Failover, Random
- **Priority Groups:** Tier 1 primário, Tier 2 backup — failover automático
- **Active Health Check:** Probes periódicos em Virtual Threads detectam falhas
- **Passive Health Check:** Sliding windows monitoram status codes do tráfego real (503, 502, 500)
- **Auto-recovery:** Membros degradados são restaurados automaticamente após período configurável

**Texto em tela:**
> Load balancing inteligente com health check ativo E passivo.  
> Failover automático. Zero downtime.

---

### Cena 4 — Motor de Regras Groovy (0:50–1:20)

**Visual:** Editor de código com syntax highlighting mostrando scripts Groovy. O código se anima conforme cada feature é mencionada. Ao lado, um diagrama mostra o request passando pelo script antes de chegar ao backend.

**Exemplos animados no código:**

1. **Roteamento dinâmico** — Script decide o backend em runtime baseado no path
2. **Resposta sintética** — Mock/stub sem chamar backend nenhum
3. **Response Processor** — Injeta headers de segurança, cookies e metadados no response
4. **Redirect condicional** — Migra clientes de API v1 para v2 com 301

**Texto em tela:**
> Scripts Groovy no hot path. Hot-reload a cada 60s.  
> Routing, mocking, transformação, composição — tudo em runtime.

---

### Cena 5 — Resiliência (1:20–1:40)

**Visual:** Gráfico animado estilo dashboard mostrando métricas. Um backend começa a falhar — o circuit breaker abre (animação visual de "porta fechando"). Rate limiting aparece como um medidor que limita o fluxo.

**Conceitos demonstrados:**
- **Circuit Breaker** (Resilience4j) — CLOSED → OPEN → HALF_OPEN → CLOSED
- **Rate Limiting** — Modo `nowait` (rejeição imediata 429) e `stall` (delay em Virtual Thread)
- **3 escopos de rate limiting:** por listener, por rota, por backend

**Texto em tela:**
> Circuit breaker por backend. Rate limiting em 3 escopos.  
> Proteja seus backends. Controle seu tráfego.

---

### Cena 6 — Autenticação (1:40–1:55)

**Visual:** Diagrama de fluxo mostrando dois caminhos: JWT entrando pela esquerda (validação) e OAuth2 token sendo injetado na saída para o backend.

**Conceitos demonstrados:**
- **Inbound:** Validação JWT via JWKS (Keycloak, Auth0, etc.)
- **Outbound:** Injeção automática de Bearer token com refresh proativo
- Decoders customizáveis via Groovy

**Texto em tela:**
> JWT na entrada. OAuth2 na saída. Transparente para seus serviços.

---

### Cena 7 — Cluster Mode & Operação (1:55–2:20)

**Visual:** 3 nós n-gate conectados via mesh NGrid. Um operador executa `ngate-cli deploy` — uma onda de sincronização percorre os 3 nós simultaneamente.

**Conceitos demonstrados:**
- **NGrid mesh TCP** — Leader election com epoch fencing
- **Token sharing (POW-RBL)** — Tokens OAuth2 compartilhados entre nós
- **Rules deploy atômico** — Um nó recebe, todos sincronizam via DistributedMap
- **ngate-cli** — CLI operacional: `deploy`, `list`, `version`
- **Rules Materialization** — Scripts persistidos em disco, não em temp dirs

**Texto em tela:**
> Cluster mode. Deploy atômico. Um comando, todos os nós sincronizados.  
> `ngate-cli deploy /etc/n-gate/rules`

---

### Cena 8 — Observabilidade (2:20–2:40)

**Visual:** Trace completo no Zipkin com 13 spans expandidos. Métricas Prometheus em gráficos de linha. Dashboard Grafana com latência, throughput e estado do circuit breaker.

**Conceitos demonstrados:**
- **13 spans semânticos por request** — rootSpan, request-handler, dynamic-rules, upstream-request, response-adapter, token-decoder
- **Propagação B3 bidirecional** — contexto extrai na entrada, injeta no upstream
- **Header `x-trace-id`** em toda resposta
- **Métricas Prometheus** — requests, latência, rate limiting, cluster, circuit breaker

**Texto em tela:**
> 13 spans por request. Trace completo do cliente ao backend.  
> Prometheus + Zipkin. Observabilidade que não é afterthought.

---

### Cena 9 — Fechamento (2:40–3:00)

**Visual:** Tela escura com logo centralizado. Tech stack badges (Java 21, Spring Boot 3.5, Javalin 7, NGrid, Resilience4j, Brave/Zipkin) aparecem ao redor do logo. Badges de empacotamento (.deb, Docker, systemd).

**Texto em tela:**
> **n-gate**  
> Programmable. Observable. Resilient.  
> Java 21 · Open Source · Production Ready  
>  
> ⭐ github.com/nishisan-dev/n-gate

---

## Diretrizes Visuais

| Aspecto | Especificação |
|---------|---------------|
| **Duração** | 2–3 minutos |
| **Resolução** | 1920×1080 (16:9) ou 3840×2160 (4K) |
| **Paleta** | Background `#0d1117`, primária `#58a6ff` (azul), accent `#f78166` (laranja), success `#3fb950` (verde) |
| **Tipografia** | Títulos: Inter Bold · Código: JetBrains Mono · Body: Inter Regular |
| **Estilo** | Motion graphics minimalista. Sem footage de pessoas. Diagramas técnicos animados |
| **Transições** | Fade + slide suave (300–500ms). Sem transições chamativas |
| **Música** | Instrumental tech/ambient sutil. Referência: vídeos da Vercel ou Stripe |
| **Ritmo** | 1 conceito a cada 10–15 segundos. Não apressar |

## Referências Visuais

- [Cloudflare Workers](https://www.cloudflare.com/developer-platform/workers/) — estilo de apresentação técnica
- [HashiCorp Consul](https://www.hashicorp.com/products/consul) — diagramas de service mesh
- [Datadog APM](https://www.datadoghq.com/product/apm/) — visualização de traces e métricas

## Palavras-Chave para o Tom

`programável` · `alta performance` · `zero-copy streaming` · `observável por padrão` · `Virtual Threads` · `hot-reload` · `deploy atômico` · `resiliente` · `production-ready`
