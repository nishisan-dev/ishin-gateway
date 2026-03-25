# SSE Pass-Through — ishin-gateway como MCP Proxy

Adicionar capacidade de pass-through transparente de Server-Sent Events (SSE) ao pipeline HTTP do ishin-gateway. Quando o backend upstream retorna `Content-Type: text/event-stream`, o gateway deve preservar o framing SSE fazendo flush por evento (a cada `\n\n`) em vez do buffered pipe atual.

Isso habilita o ishin a atuar como **proxy reverso para MCP Servers** usando o transporte Streamable HTTP, que utiliza SSE para streaming de server→client.

## User Review Required

> [!IMPORTANT]
> A abordagem escolhida é **modificar o `HttpResponseAdapter` existente** para detectar SSE e fazer flush por evento, em vez de criar um endpoint SSE dedicado via `config.routes.sse()` do Javalin. **Motivo:** o ishin é um proxy genérico — os paths vêm do `adapter.yaml`, não hardcoded. O pipe mode já faz I/O streaming; basta adicionar flush granular para SSE. Usar `config.routes.sse()` forçaria registrar rotas SSE separadas e perderia a integração com Groovy rules, rate limiting, circuit breaker, etc.

> [!WARNING]
> O OkHttp por default tem `callTimeout` (30s default no ishin). Conexões SSE de longa duração serão cortadas. O plano inclui desabilitar `callTimeout` quando o upstream responde com `text/event-stream`, recriando o client com `.callTimeout(0)` para SSE streams.

---

## Proposed Changes

### HTTP Response Pipeline

#### [MODIFY] [HttpResponseAdapter.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/http/HttpResponseAdapter.java)

No pipe mode (`returnPipe = true`), adicionar detecção de SSE **antes** do loop de streaming:

1. Verificar se `Content-Type` do upstream contém `text/event-stream`
2. Se SSE detectado:
   - Setar headers SSE obrigatórios no client response: `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `Connection: keep-alive`
   - Remover `Content-Length` (SSE é chunked/unbounded)
   - Fazer streaming **line-by-line** com flush a cada linha vazia (`\n\n` = delimitador de evento SSE)
   - Span tag `sse: true`
3. Se não SSE: manter comportamento atual (bulk pipe 8KB buffer)

```diff
 // --- Fase 5: Pipe de streaming ---
 SpanWrapper clientSpan = tracer.createChildSpan("response-send-to-client", responseSpan);
+String upstreamContentType = w.getUpstreamResponse().getHeader("Content-Type");
+boolean isSse = upstreamContentType != null
+        && upstreamContentType.contains("text/event-stream");
+clientSpan.tag("sse", String.valueOf(isSse));
 
-try (InputStream inputStream = ...; OutputStream outputStream = ...) {
+if (isSse) {
+    writeSseStream(w, clientSpan);
+} else {
     // ... existing bulk pipe ...
+}
```

Novo método `writeSseStream()`:
- Lê do upstream `InputStream` byte a byte, acumulando em buffer de linha
- A cada `\n` vazio (delimitador SSE), faz `outputStream.flush()` imediato
- Trata `IOException` com cleanup e log (client disconnect)

---

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/http/HttpProxyManager.java)

Após receber a `Response` do OkHttp (linha ~700), se `Content-Type` é `text/event-stream`:

1. Setar `w.setReturnPipe(true)` (forçar pipe mode, ignorar response processors)
2. **Skip** a materialização via `SynthHttpResponseAdapter` — passar a `Response` diretamente no workload para o response adapter fazer streaming
3. Adicionar span tag `upstream.sse: true`

Para isso, o `HttpWorkLoad` precisa de um campo para carregar a `Response` raw do OkHttp (o `SyntHttpResponse` já tem `okHttpResponse`, mas o pipe SSE precisa acessar o `InputStream` diretamente).

---

#### [MODIFY] [HttpWorkLoad.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/http/HttpWorkLoad.java)

Adicionar campo `sseMode` (boolean) para sinalizar ao `HttpResponseAdapter` que deve usar o path SSE:

```java
private boolean sseMode = false;
public boolean isSseMode() { return sseMode; }
public void setSseMode(boolean sseMode) { this.sseMode = sseMode; }
```

---

### Timeout para SSE

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/http/HttpProxyManager.java)

Para conexões SSE, o `callTimeout` do OkHttp (default 30s) precisa ser desabilitado. Usar `client.newBuilder().callTimeout(0, SECONDS).build()` para criar um client derivado (share pool/dispatcher) especificamente para a call SSE:

```java
// Dentro do bloco de execução upstream, após construir req:
OkHttpClient upstreamClient = this.getHttpClientByListenerName(backendname);
// Detectar Accept header para SSE (client pede text/event-stream)
if ("text/event-stream".equals(handler.header("Accept"))) {
    upstreamClient = upstreamClient.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build();
}
```

---

### Observabilidade

#### [MODIFY] [ProxyMetrics.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/observability/ProxyMetrics.java)

Adicionar counter `ishin.sse.streams.total` com tags `backend`, `listener`:
```java
public void recordSseStream(String listener, String backend) { ... }
```

---

### Documentação

#### [MODIFY] [architecture.md](file:///home/lucas/Projects/ishin-gateway/docs/architecture.md)

Adicionar seção "SSE Pass-Through" descrevendo o comportamento de detecção automática e flush por evento.

#### [MODIFY] [configuration.md](file:///home/lucas/Projects/ishin-gateway/docs/configuration.md)

Nota sobre timeout extended para SSE e como o gateway detecta automaticamente.

---

## Verification Plan

### Automated Tests

#### 1. Teste unitário: `SsePassThroughTest`

Novo teste unitário usando `MockWebServer` (já no pom.xml):

```bash
mvn test -Dtest='SsePassThroughTest' -DfailIfNoTests=false
```

Cenários:
- **SSE stream forward**: MockWebServer retorna `text/event-stream` com eventos SSE. Verificar que o gateway faz forward preservando os eventos e o content-type.
- **Non-SSE unchanged**: Verificar que requests normais continuam usando o pipe bulk (regressão).
- **Client disconnect**: Client fecha a conexão mid-stream. Verificar que o gateway fecha a conexão upstream sem leak.

#### 2. Build completo

```bash
mvn clean compile -DskipTests
```

### Manual Verification

> Criei um cenário de teste manual com docker compose caso você queira validar visualmente mais tarde. Mas podemos fazer isso após a implementação.

1. Subir o ambiente dev: `docker compose up --build`
2. Subir um MCP Server local que responda SSE (ex: `npx @modelcontextprotocol/server-memory`)
3. Configurar um backend no `adapter.yaml` apontando para o MCP Server
4. Fazer request `curl -N -H "Accept: text/event-stream" http://localhost:9091/mcp` e verificar que os eventos SSE chegam em real-time (sem buffer)
