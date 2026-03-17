# Tunnel Mode — Plano de Implementação

Implementar o **Tunnel Mode** conforme `planning/tunnel_mode_spec.md`. O tunnel opera como load balancer TCP L4 na frente de múltiplas instâncias proxy, usando NGrid para registro dinâmico de backends.

## User Review Required

> [!IMPORTANT]
> O processo ishin-gateway passa a ter dois modos mutuamente exclusivos (`proxy` / `tunnel`), controlado pelo campo `mode` no `adapter.yaml`. O default será `proxy` para retrocompatibilidade total.

> [!IMPORTANT]
> O tunnel engine usa `java.nio.channels.ServerSocketChannel` com Virtual Threads (Java 21) para o pipe TCP — não usa Javalin nem Jetty. Isso mantém o hot-path leve e sem overhead HTTP.

---

## Proposed Changes

### Componente 1: Configuração YAML

Adicionar suporte ao campo `mode` e blocos `tunnel` no modelo de configuração.

#### [MODIFY] [ServerConfiguration.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/configuration/ServerConfiguration.java)

- Adicionar campo `String mode = "proxy"` com getter/setter
- Adicionar campo `TunnelConfiguration tunnel` com getter/setter

#### [NEW] [TunnelConfiguration.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/configuration/TunnelConfiguration.java)

Config do nó tunnel:
```java
public class TunnelConfiguration {
    private String loadBalancing = "round-robin";      // round-robin | least-connections | weighted-round-robin
    private int missedKeepalives = 3;
    private int drainTimeout = 30;                      // segundos
    private String bindAddress = "0.0.0.0";
    private boolean autoPromoteStandby = true;
    private TunnelRegistrationConfiguration registration; // config do lado proxy
}
```

#### [NEW] [TunnelRegistrationConfiguration.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/configuration/TunnelRegistrationConfiguration.java)

Config do registro do proxy no tunnel:
```java
public class TunnelRegistrationConfiguration {
    private boolean enabled = false;
    private int keepaliveInterval = 3;     // segundos
    private String status = "ACTIVE";       // ACTIVE | STANDBY
    private int weight = 100;
}
```

#### [MODIFY] [EndPointListenersConfiguration.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/configuration/EndPointListenersConfiguration.java)

- Adicionar campo `Integer virtualPort` com getter/setter (nullable — quando null, inferido de `listenPort`)

---

### Componente 2: Registry (Proxy → NGrid)

O proxy publica seu registro no NGrid `DistributedMap` e mantém keepalive periódico.

#### [NEW] [TunnelRegistryEntry.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/TunnelRegistryEntry.java)

DTO `Serializable` publicado pelo proxy no NMap:
```java
public class TunnelRegistryEntry implements Serializable {
    private String nodeId;
    private String host;
    private List<ListenerRegistration> listeners;   // virtualPort → realPort
    private String status;          // ACTIVE | DRAINING | STANDBY
    private int weight;
    private long lastKeepAlive;     // epoch millis
    private long registeredAt;      // epoch millis
    private int keepaliveInterval;  // segundos (declarado pelo proxy)
}
```

Inner class `ListenerRegistration`:
```java
public static class ListenerRegistration implements Serializable {
    private int virtualPort;
    private int realPort;
    private String protocol = "tcp";
}
```

#### [NEW] [TunnelRegistrationService.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/TunnelRegistrationService.java)

Spring `@Service` ativo apenas se `mode=proxy` e `tunnel.registration.enabled=true`:
- `@Order(25)` (entre ClusterService e EndpointManager)
- No startup: coleta listeners com `virtualPort`, monta `TunnelRegistryEntry`, publica no NMap
- Scheduler: atualiza `lastKeepAlive` a cada `keepaliveInterval` segundos
- `@PreDestroy`: muda status para `DRAINING`, aguarda `drainTimeout`, remove registro

---

### Componente 3: Tunnel Engine (Core TCP)

O coração do tunnel mode — abre listeners TCP dinâmicos e roteia para backends registrados.

#### [NEW] [VirtualPortGroup.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/VirtualPortGroup.java)

Pool de membros para uma porta virtual:
- `ConcurrentHashMap<String, BackendMember>` (key: `nodeId:realPort`)
- Referência ao `TunnelLoadBalancer`
- Track de `activeConnections` por membro (AtomicInteger)
- Métodos: `addMember`, `removeMember`, `getActiveMembers`, `getNextMember`

#### [NEW] [BackendMember.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/BackendMember.java)

Representa um membro do pool:
```java
public class BackendMember {
    private String nodeId;
    private String host;
    private int realPort;
    private String status;          // ACTIVE | DRAINING | STANDBY
    private int weight;
    private long lastKeepAlive;
    private int keepaliveInterval;
    private AtomicInteger activeConnections = new AtomicInteger(0);
    private AtomicInteger consecutiveFailures = new AtomicInteger(0);
}
```

#### [NEW] [TunnelLoadBalancer.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/TunnelLoadBalancer.java)

Interface:
```java
public interface TunnelLoadBalancer {
    BackendMember select(List<BackendMember> activeMembers);
}
```

#### [NEW] [RoundRobinBalancer.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/lb/RoundRobinBalancer.java)

AtomicInteger counter + modulo.

#### [NEW] [LeastConnectionsBalancer.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/lb/LeastConnectionsBalancer.java)

Min por `activeConnections`.

#### [NEW] [WeightedRoundRobinBalancer.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/lb/WeightedRoundRobinBalancer.java)

Smooth Weighted Round-Robin (SWRR).

#### [NEW] [TunnelRegistry.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/TunnelRegistry.java)

Observa o NGrid NMap e mantém `ConcurrentHashMap<Integer, VirtualPortGroup>`:
- Polling periódico do NMap (a cada 1s) para detectar mudanças
- Adiciona/remove membros dos `VirtualPortGroup`s
- Keepalive timeout checker: remove membros com `lastKeepAlive` expirado
- Promoção automática de STANDBY quando zero ACTIVE

#### [NEW] [TunnelEngine.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/TunnelEngine.java)

Core do pipe TCP:
- Gerencia `ServerSocketChannel` por virtual port (dynamic open/close)
- Para cada conexão aceita: seleciona backend via LB, conecta, pipe bidirecional
- Pipe com `SocketChannel.transferTo`/`transferFrom` para zero-copy quando possível
- Fallback: buffer de 8KB com Virtual Thread para cada direção
- IOException handling conforme spec (Camada 1)
- Métricas por conexão

#### [NEW] [TunnelService.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/TunnelService.java)

Spring `@Service` — orquestrador do tunnel mode:
- `@Order(30)` — mesmo slot do `EndpointManager`
- Ativo apenas se `mode=tunnel`
- Inicializa `TunnelRegistry`, `TunnelEngine`
- `@PreDestroy`: fecha listeners, drena conexões ativas

---

### Componente 4: Detecção de Falha

Implementada dentro de `TunnelEngine` e `TunnelRegistry`:

- **Camada 1 (IOException)**: No `connect()` do `TunnelEngine`, captura `ConnectException`, `NoRouteToHostException`, `SocketException` → remoção imediata. `SocketTimeoutException` → incrementa counter, remove após threshold (3).
- **Camada 2 (Keepalive timeout)**: Thread no `TunnelRegistry` verifica `now() - lastKeepAlive > keepaliveInterval × missedKeepalives` → remove.
- **Auto-promote STANDBY**: Quando zero ACTIVE, promove STANDBY com menor `weight` desc.

---

### Componente 5: Observabilidade

#### [NEW] [TunnelMetrics.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/tunnel/TunnelMetrics.java)

Spring `@Component` — métricas Prometheus via Micrometer seguindo o padrão de `ProxyMetrics`:
- Counters: `ishin_tunnel_connections_total`, `ishin_tunnel_bytes_sent_total`, `ishin_tunnel_bytes_received_total`, `ishin_tunnel_connect_errors_total`, `ishin_tunnel_pool_removals_total`, `ishin_tunnel_standby_promotions_total`
- Gauges: `ishin_tunnel_connections_active`, `ishin_tunnel_pool_members`, `ishin_tunnel_keepalive_age_seconds`, `ishin_tunnel_listener_ports_active`
- Histograms: `ishin_tunnel_session_duration_seconds`, `ishin_tunnel_connect_duration_seconds`

#### [MODIFY] [IshinGatewayHealthIndicator.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/health/IshinGatewayHealthIndicator.java)

- Reportar `mode: tunnel` quando em tunnel mode
- Adicionar `tunnelListeners` (portas virtuais ativas) e `tunnelPoolSize` (total de backends)

---

### Componente 6: Integração & Boot Condicional

#### [MODIFY] [EndpointManager.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/manager/EndpointManager.java)

- No `onStartup()`, verificar se `mode=proxy`. Se `mode=tunnel`, retornar sem inicializar endpoints Javalin.

#### [MODIFY] [ConfigurationManager.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/manager/ConfigurationManager.java)

- No `onStartup()`, se `mode=tunnel`, não iterar `endpoints` para OAuth/Circuit Breaker/Rate Limit.

---

## Verification Plan

### Testes Automatizados

**Testes unitários de parsing YAML** — extensão do `ConfigurationManagerTest`:

```bash
mvn test -pl . -Dtest="ConfigurationManagerTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Novos test cases:
- `T6: YAML com mode=tunnel e bloco tunnel é parseado corretamente`
- `T7: YAML com mode=proxy e tunnel.registration é parseado corretamente`
- `T8: YAML com virtualPort nos listeners é parseado corretamente`

**Testes unitários de LB**:

```bash
mvn test -pl . -Dtest="TunnelLoadBalancerTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Novos test cases:
- `T1: RoundRobin distribui sequencialmente`
- `T2: LeastConnections seleciona membro com menor count`
- `T3: WeightedRoundRobin respeita pesos`

**Testes unitários de VirtualPortGroup**:

```bash
mvn test -pl . -Dtest="VirtualPortGroupTest" -Dsurefire.failIfNoSpecifiedTests=false
```

- `T1: addMember/removeMember lifecycle`
- `T2: getActiveMembers filtra DRAINING e STANDBY`
- `T3: Promoção de STANDBY quando zero ACTIVE`

**Build completo**:

```bash
mvn clean package -DskipTests=false
```

### Verificação Manual

> [!NOTE]
> Testes de integração end-to-end do pipe TCP (com containers) serão adicionados em uma fase posterior. Para esta implementação, a verificação foca em testes unitários, build, e revisão manual da lógica TCP.
