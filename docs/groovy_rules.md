# Regras Groovy — n-gate

O n-gate utiliza o **GroovyScriptEngine** como motor de regras dinâmicas. Scripts `.groovy` no diretório `rules/` são executados a cada request, permitindo modificar roteamento, headers, payloads e gerar respostas programáticas.

---

## Como Funciona

1. O `adapter.yaml` define o script Groovy para cada listener/contexto via `ruleMapping`
2. A cada request, o `HttpProxyManager` executa o script com um **binding** contendo variáveis do contexto
3. O script pode modificar o request, selecionar backends, criar respostas sintéticas e registrar processors
4. O GroovyScriptEngine faz **hot-reload**: recompila scripts automaticamente a cada **60 segundos** (configurável)

---

## Variáveis Disponíveis no Binding

| Variável | Tipo | Descrição |
|----------|------|-----------|
| `workload` | `HttpWorkLoad` | Objeto principal — carrega request, response e configuração do pipeline |
| `context` | `CustomContextWrapper` | Wrapper do contexto Javalin (acesso a headers, params, body) |
| `upstreamRequest` | `HttpAdapterServletRequest` | Request que será enviado ao backend (modificável) |
| `utils` | `Map<String, Object>` | Utilitários: `gson`, `json`, `xml`, `httpClient` |
| `listener` | `String` | Nome do listener que recebeu o request |
| `contextName` | `String` | Nome do URL Context ativo |
| `requestMethod` | `String` | Método HTTP do contexto |
| `include` | `String` | Próximo script a executar (cadeia de scripts) |

---

## API do HttpWorkLoad

### Propriedades

| Propriedade | Tipo | Default | Descrição |
|-------------|------|---------|-----------|
| `returnPipe` | `Boolean` | `true` | `true` = streaming direto, `false` = materializa body em memória |
| `body` | `String` | `""` | Body do request (leitura/escrita) |
| `objects` | `ConcurrentMap<String, Object>` | vazio | Map para compartilhar dados entre scripts e processors |

### Métodos

| Método | Retorno | Descrição |
|--------|---------|-----------|
| `createSynthResponse()` | `SyntHttpResponse` | Cria resposta sintética (bypass do backend) |
| `addResponseProcessor(name, closure)` | `void` | Registra closure de pós-processamento |
| `addObject(name, obj)` | `void` | Adiciona objeto ao map compartilhado |
| `getRequest()` | `HttpAdapterServletRequest` | Request HTTP adaptado |
| `getContext()` | `CustomContextWrapper` | Contexto Javalin |
| `getUpstreamResponse()` | `SyntHttpResponse` | Resposta do backend (disponível nos processors) |
| `clientResponse()` | `HttpAdapterServletResponse` | Resposta que será enviada ao cliente |

---

## API do HttpAdapterServletRequest

O `upstreamRequest` permite modificar o request antes de enviá-lo ao backend:

| Método | Descrição |
|--------|-----------|
| `setRequestURI(uri)` | Altera o path do request upstream |
| `setQueryString(qs)` | Define a query string |
| `setBackend(name)` | Muda o backend de destino (chave do `adapter.yaml`) |
| `getBackend()` | Retorna o backend atual |
| `getRequestURI()` | Retorna o path atual |

---

## Utilitários (`utils`)

| Chave | Tipo | Descrição |
|-------|------|-----------|
| `utils.gson` | `Gson` | Serialização/deserialização JSON (Google Gson) |
| `utils.json` | `JsonSlurper` | Parser JSON nativo do Groovy |
| `utils.xml` | `XmlSlurper` | Parser XML nativo do Groovy |
| `utils.httpClient` | `HttpClientUtils` | Utilitário para criar HTTP clients para chamadas secundárias |

### HttpClientUtils

| Método | Descrição |
|--------|-----------|
| `getAssyncBackend(name)` | Cria um client HTTP assíncrono para um backend nomeado |

---

## Cadeia de Scripts (`include`)

Scripts podem encadear a execução de outros scripts:

```groovy
// Rules.groovy
include = "default/PostProcess.groovy"

// O engine vai executar PostProcess.groovy após este script
```

O loop continua enquanto `include` não for vazio. Para interromper, não defina `include` ou defina como `""`.

---

## Exemplos Práticos

### 1. Roteamento Dinâmico por Path

Seleciona o backend com base no path do request:

```groovy
// rules/default/Rules.groovy

def path = context.path()

if (path.startsWith("/api/users")) {
    upstreamRequest.setBackend("users-service")
} else if (path.startsWith("/api/products")) {
    upstreamRequest.setBackend("products-service")
} else if (path.startsWith("/api/orders")) {
    upstreamRequest.setBackend("orders-service")
}
```

**Pré-requisito:** Backends `users-service`, `products-service` e `orders-service` devem estar definidos no `adapter.yaml`.

---

### 2. Resposta Sintética (Mock/Stub)

Gera uma resposta sem chamar nenhum backend — ideal para dev/test:

```groovy
// rules/default/Rules.groovy

def path = context.path()

if (path == "/health") {
    def synth = workload.createSynthResponse()
    synth.setContent('{"status": "UP", "service": "n-gate"}')
    synth.setStatus(200)
    synth.addHeader("Content-Type", "application/json")
}
```

Resultado:
```bash
curl http://localhost:9090/health
# {"status": "UP", "service": "n-gate"}
```

---

### 3. Injeção de Headers Customizados

Adiciona headers ao request upstream:

```groovy
// rules/default/Rules.groovy

// Adiciona header de correlação
upstreamRequest.addHeader("X-Correlation-Id", java.util.UUID.randomUUID().toString())

// Adiciona header com o listener de origem
upstreamRequest.addHeader("X-Source-Listener", listener)

// Copia headers específicos do cliente
def clientToken = context.header("X-Custom-Token")
if (clientToken != null) {
    upstreamRequest.addHeader("X-Forwarded-Token", clientToken)
}
```

---

### 4. Response Processor — Streaming Condicional

Materializa o body apenas para respostas pequenas, faz streaming para grandes:

```groovy
// rules/default/Rules.groovy

def binaryDataProcessor = { wl ->
    def contentSize = wl.upstreamResponse.getHeader("Content-Length")
    if (contentSize) {
        contentSize = contentSize.toLong()
        if (contentSize > 100000) {
            wl.clientResponse.addHeader('x-big', 'yes')
            wl.returnPipe = true
        } else {
            wl.clientResponse.addHeader('x-big', 'no')
            wl.returnPipe = false
        }
    }

    // Streaming automático para imagens
    def contentType = wl.upstreamResponse.getHeader("Content-Type")
    if (contentType?.contains("image")) {
        wl.returnPipe = true
        wl.clientResponse.addHeader('x-content-type', contentType)
    }
}

workload.addResponseProcessor('binaryDataProcessor', binaryDataProcessor)
```

> **⚠️ Nota:** Response processors são executados no hot path. Use com sabedoria — cada processor adiciona latência.

---

### 5. Chamada Assíncrona a Backend Secundário

Chama outro backend e armazena o resultado para uso posterior:

```groovy
// rules/default/Rules.groovy

// Cria um client para o backend secundário
def secondaryBe = utils.httpClient.getAssyncBackend("metadata-service")

// Faz uma chamada GET assíncrona
def future = secondaryBe.get("http://metadata-service:8080/metadata/" + context.pathParam("id"), [:])

// Aguarda a resposta
def response = future.join()
workload.addObject("metadata", response.body().string())
```

---

### 6. Resposta Sintética com Composição de Dados

Combina dados de múltiplas fontes em uma resposta única:

```groovy
// rules/default/Rules.groovy

if (context.path() == "/api/dashboard") {
    // Dados do usuário (do token JWT)
    def userPrincipal = workload.objects.get("USER_PRINCIPAL")

    // Cria resposta composta
    def dashboard = [
        user: userPrincipal?.id,
        timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss"),
        service: listener
    ]

    def synth = workload.createSynthResponse()
    synth.setContent(utils.gson.toJson(dashboard))
    synth.setStatus(200)
    synth.addHeader("Content-Type", "application/json")
}
```

---

### 7. Rewrite de URL

Modifica o path/query antes de encaminhar ao backend:

```groovy
// rules/default/Rules.groovy

def path = context.path()

// Remove prefixo do gateway
if (path.startsWith("/gateway/v1")) {
    upstreamRequest.setRequestURI(path.replace("/gateway/v1", ""))
}

// Adiciona parâmetros de query
def existingQs = context.queryString() ?: ""
upstreamRequest.setQueryString(existingQs + "&source=n-gate")
```

---

## Boas Práticas

1. **Mantenha scripts leves** — O script é executado a cada request. Evite operações pesadas como I/O de disco ou computação complexa.
2. **Use `returnPipe = true`** (padrão) — Streaming é mais performático. Só desabilite quando precisar inspecionar/modificar o body.
3. **Response Processors são custosos** — Cada processor adiciona overhead no hot path. Use apenas quando necessário.
4. **Recompilação** — Scripts são recompilados automaticamente a cada 60 segundos. Para testar mudanças, aguarde o intervalo ou reinicie o gateway.
5. **Isolamento** — Use `ProtectedBinding` (automático) para evitar vazamento de estado entre requests concorrentes.
6. **Encadeamento** — Use `include` para modularizar regras complexas em múltiplos scripts.
