# Tuning P0 — Perfil Prod-Like para Inventory Adapter

## Contexto

O benchmark revelou que o adapter satura em **~4.000 req/s** no teste sustentado, independente da concorrência. A análise do código identificou que o **OkHttp `Dispatcher` nunca é configurado**, o que significa que o default do OkHttp limita a **64 requests concorrentes no total e 5 por host**. Este é o gargalo principal.

Além disso, o `Dispatcher` usa um `ExecutorService` interno com thread pool fixo. Em Java 21, podemos substituí-lo por um `VirtualThreadPerTaskExecutor` para eliminar esse bottleneck.

## User Review Required

> [!IMPORTANT]
> **Valores prod-like propostos**: Os valores abaixo são calibrados para um adapter fazendo proxy para backends internos (baixa latência, alta confiança). Se o cenário de produção for diferente (backends externos, alta latência), os valores devem ser ajustados.

> [!WARNING]
> **`socketTimeout` será reduzido de 3600s para 30s.** O valor atual (1 hora!) é extremamente alto e mascara problemas de conectividade. Para produção, 30s é mais que suficiente para a maioria dos backends internos.

## Diagnóstico Detalhado

| Componente | Atual | Problema |
|:---|:---|:---|
| OkHttp `Dispatcher.maxRequests` | **64** (default) | Limite global de chamadas simultâneas ao backend |
| OkHttp `Dispatcher.maxRequestsPerHost` | **5** (default) | Limite por host — **este é o real gargalo** |
| OkHttp `ConnectionPool` | 200 / 5min | Adequado, mas ineficaz sem dispatcher |
| Jetty `QueuedThreadPool` | 16–500 | Configuração OK, mas gera filas internas se o OkHttp não despacha |
| `socketTimeout` | **3600s** | Absurdamente alto para proxy; desperdiça threads e conexões |
| OkHttp `Dispatcher.executorService` | `ThreadPoolExecutor` default | Em Java 21, virtual threads são mais eficientes para I/O-bound |

## Proposed Changes

### Componente: Configuração (`EndPointConfiguration`)

#### [MODIFY] [EndPointConfiguration.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/configuration/EndPointConfiguration.java)

Adicionar campos configuráveis para o `Dispatcher` do OkHttp e ajustar defaults para prod-like:

```java
// OkHttp Dispatcher
private Integer dispatcherMaxRequests = 512;
private Integer dispatcherMaxRequestsPerHost = 256;

// Ajustar defaults existentes para prod-like
private Integer socketTimeout = 30;  // era 3600
```

Getters/setters correspondentes.

---

### Componente: HTTP Proxy (`HttpProxyManager`)

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java)

1. **Criar um `Dispatcher` compartilhado** com `VirtualThreadPerTaskExecutor` (Java 21) e os limites configuráveis.
2. **Aplicar o `Dispatcher`** em todos os 3 builders de `OkHttpClient` (linhas 182, 236, 283).
3. **Logar** as configurações de pool/dispatcher no startup para visibilidade operacional.

```java
// No construtor, junto com sharedConnectionPool:
private final okhttp3.Dispatcher sharedDispatcher;

// Construtor:
this.sharedDispatcher = new okhttp3.Dispatcher(Executors.newVirtualThreadPerTaskExecutor());
this.sharedDispatcher.setMaxRequests(configuration.getDispatcherMaxRequests());
this.sharedDispatcher.setMaxRequestsPerHost(configuration.getDispatcherMaxRequestsPerHost());
```

Nos 3 builders existentes, adicionar `.dispatcher(sharedDispatcher)`.

---

### Componente: Configuração YAML

#### [MODIFY] [adapter.yaml](file:///home/lucas/Projects/inventory-adapter/config/adapter.yaml)

Atualizar com os novos campos e valores prod-like:

```yaml
socketTimeout: 30

# Jetty Thread Pool
jettyMinThreads: 16
jettyMaxThreads: 500
jettyIdleTimeout: 120000

# OkHttp Connection Pool
connectionPoolSize: 256
connectionPoolKeepAliveMinutes: 5

# OkHttp Dispatcher
dispatcherMaxRequests: 512
dispatcherMaxRequestsPerHost: 256
```

---

### Componente: Docker Compose

#### [MODIFY] [docker-compose.yml](file:///home/lucas/Projects/inventory-adapter/docker-compose.yml)

Ajuste com JVM flags prod-like no command do container `inventory-adapter`:

```yaml
command:
  - /bin/bash
  - -lc
  - mvn -DskipTests package && java -Dspring.profiles.active=dev
    -XX:+UseZGC -XX:+ZGenerational
    -Xms256m -Xmx512m
    -jar target/inventory-adapter-1.0-SNAPSHOT.jar
```

> [!NOTE]
> **ZGC Generational** é o GC recomendado para workloads de baixa latência em Java 21. Ele reduz pausas de GC para sub-milissegundo.

---

## Resumo dos Valores Prod-Like

| Parâmetro | Antes | Depois | Justificativa |
|:---|:---:|:---:|:---|
| `socketTimeout` | 3600s | **30s** | Timeout de 1h mascara problemas |
| `connectionPoolSize` | 200 | **256** | Alinhado com dispatcher |
| `dispatcherMaxRequests` | 64 (default) | **512** | Desbloqueia throughput global |
| `dispatcherMaxRequestsPerHost` | 5 (default) | **256** | **Gargalo principal** resolvido |
| JVM GC | G1GC (default) | **ZGC Generational** | Menor latência de GC |
| JVM Heap | default | **256m–512m** | Controlado e previsível |

## Verification Plan

### Automated Tests

1. **Build Maven** — Validar que o código compila sem erros:
   ```bash
   cd /home/lucas/Projects/inventory-adapter && mvn -DskipTests clean package
   ```

2. **Re-deploy Docker** — Subir a stack com as novas configurações:
   ```bash
   cd /home/lucas/Projects/inventory-adapter && docker compose down inventory-adapter && docker compose up -d inventory-adapter
   ```

3. **Benchmark comparativo** — Rodar o mesmo benchmark para comparar com o baseline:
   ```bash
   cd /home/lucas/Projects/inventory-adapter && python3 scripts/benchmark.py
   ```
   Comparar os resultados com os salvos em `benchmark_results.json` e `benchmark_results_timed.json`.

### Critérios de Sucesso

- **Zero falhas** em todas as concorrências
- **Throughput sustained > 8.000 req/s** (era ~4.000)
- **p99 em c=500 < 100ms** (era 280ms)
- **Overhead médio em c=100 < 8ms** (era 16,7ms)
