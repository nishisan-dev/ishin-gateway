# Fase 2 — NGrid Cluster Mode (Escalabilidade Horizontal)

Habilitar o ishin-gateway para rodar em modo cluster, onde N instâncias compartilham estado via NGrid mesh TCP (sem dependências externas como Redis/etcd). O foco desta sessão é a **integração base do NGrid** e o **compartilhamento de tokens OAuth** — o rules deploy ficará para uma sessão dedicada.

## User Review Required

> [!IMPORTANT]
> O NGrid usa **Java Serialization** para comunicação inter-nó. Não há mTLS. Para ambientes de produção em redes não-confiáveis, as instâncias devem estar em VPC/VLAN isolada.

> [!IMPORTANT]
> A dependência `dev.nishisan:nishi-utils:3.1.0` está publicada no GitHub Packages (`maven.pkg.github.com/nishisan-dev/nishi-utils`). O repositório Maven do GitHub Packages já deve estar configurado no `settings.xml` local.

> [!WARNING]
> O modo cluster é **opt-in**. Sem o bloco `cluster:` no `adapter.yaml`, o ishin-gateway roda exatamente como antes (standalone puro). Não há fallback — se cluster está configurado, ele obrigatoriamente tenta formar o mesh.

---

## Proposed Changes

### Componente 1: Dependência Maven

#### [MODIFY] [pom.xml](file:///home/lucas/Projects/ishin-gateway/pom.xml)

Adicionar dependência do `nishi-utils` (NGrid, DistributedMap):

```xml
<dependency>
    <groupId>dev.nishisan</groupId>
    <artifactId>nishi-utils</artifactId>
    <version>3.1.0</version>
</dependency>
```

---

### Componente 2: Configuração do Cluster

#### [NEW] [ClusterConfiguration.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/configuration/ClusterConfiguration.java)

POJO Jackson para mapear o novo bloco `cluster:` do `adapter.yaml`:

```java
public class ClusterConfiguration {
    private boolean enabled = false;
    private String nodeId;           // ID do nó (default: hostname)
    private String host;             // Bind host para o NGrid (default: 0.0.0.0)
    private int port = 7100;         // Porta TCP do mesh NGrid
    private String clusterName = "ishin-cluster";
    private List<String> seeds;      // Ex: ["10.0.0.1:7100", "10.0.0.2:7100"]
    private int replicationFactor = 2;
    private String dataDirectory = "./data/ngrid";  // Dir para WAL/snapshots
}
```

Compatível com env vars: `${ISHIN_CLUSTER_NODE_ID:hostname}`, `${ISHIN_CLUSTER_PORT:7100}`, `${ISHIN_CLUSTER_SEEDS:}`.

#### [MODIFY] [ServerConfiguration.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/configuration/ServerConfiguration.java)

Adicionar campo `ClusterConfiguration cluster` (nullable, opt-in).

#### [MODIFY] [adapter.yaml](file:///home/lucas/Projects/ishin-gateway/config/adapter.yaml)

Adicionar bloco `cluster:` **comentado** como exemplo:

```yaml
# --- Cluster Mode (NGrid) ---
# Descomente para habilitar cluster mode
# cluster:
#   enabled: true
#   nodeId: "ishin-1"
#   host: "0.0.0.0"
#   port: 7100
#   clusterName: "ishin-cluster"
#   seeds:
#     - "10.0.0.2:7100"
#     - "10.0.0.3:7100"
#   replicationFactor: 2
#   dataDirectory: "./data/ngrid"
```

---

### Componente 3: ClusterService (NGridNode Lifecycle)

#### [NEW] [ClusterService.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/cluster/ClusterService.java)

Spring `@Service` que gerencia o ciclo de vida do `NGridNode`:

**Responsabilidades:**
- **Boot**: Se `cluster.enabled == true`, monta `NGridConfig` via `NGridConfig.Builder`, cria e starta `NGridNode`
- **Expor**: `isClusterMode()`, `isLeader()`, `getDistributedMap(name, K, V)`, `getNGridNode()`
- **Shutdown** (`@PreDestroy`): `NGridNode.close()` orderly
- **Listeners**: `LeadershipListener` exposto via `addLeadershipListener(callback)`
- **Standalone fallback**: Se `cluster.enabled == false`, todos os métodos retornam defaults (isLeader=true, maps são null)

**Construção do NGridConfig:**
```java
NodeId nodeId = NodeId.of(config.getNodeId());   // ou hostname
NodeInfo local = new NodeInfo(nodeId, config.getHost(), config.getPort());
NGridConfig.Builder builder = NGridConfig.builder(local)
    .clusterName(config.getClusterName())
    .dataDirectory(Path.of(config.getDataDirectory()))
    .replicationFactor(config.getReplicationFactor())
    .heartbeatInterval(Duration.ofMillis(500))
    .leaseTimeout(Duration.ofSeconds(5))
    .mapName("ishin-tokens");   // mapa default para tokens

// Adicionar peers a partir dos seeds
for (String seed : config.getSeeds()) {
    String[] parts = seed.split(":");
    NodeInfo peer = new NodeInfo(NodeId.of(parts[0]+":"+parts[1]), parts[0], Integer.parseInt(parts[1]));
    builder.addPeer(peer);
}
```

---

### Componente 4: Compartilhamento de Tokens OAuth

#### [MODIFY] [AuthToken.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/auth/wrapper/AuthToken.java)

- Implementar `java.io.Serializable` + `serialVersionUID`
- O field `TokenResponse currentResponse` (Google OAuth lib) **não** implementa Serializable → criar `SerializableTokenData` record que captura os campos essenciais (accessToken, refreshToken, expiresInSeconds, tokenType, scope)
- Substituir `TokenResponse` por `SerializableTokenData` nos campos replicados; manter `TokenResponse` como transient para uso local na API do Google
- Substituir `OauthServerClientConfiguration` (que contém clientSecret etc. sensível) por `transient` — followers não precisam da config do SSO, apenas do token

#### [NEW] [SerializableTokenData.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/auth/wrapper/SerializableTokenData.java)

```java
public record SerializableTokenData(
    String accessToken,
    String refreshToken,
    Long expiresInSeconds,
    String tokenType,
    String scope,
    String oauthName,
    Date lastTimeTokenRefreshed,
    Date expiresIn
) implements Serializable {}
```

#### [MODIFY] [OAuthClientManager.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/auth/OAuthClientManager.java)

**Mudanças:**

1. Injetar `ClusterService` via `@Autowired`
2. No `onStartup()`:
   - Se cluster mode: obter `DistributedMap<String, SerializableTokenData>` do `ClusterService`
   - Registrar `LeadershipListener` para start/stop da `TokenRefreshThread`
   - `TokenRefreshThread` roda **apenas no líder**
3. No `getAccessToken()`:
   - Se cluster mode + follower: ler do `DistributedMap` diretamente
   - Se standalone ou leader: manter lógica atual + publicar no `DistributedMap`
4. No refresh: ao renovar token, líder faz `distributedMap.put(ssoName, serializableTokenData)`

---

### Componente 5: Health Check e Observabilidade

#### [MODIFY] [IshinGatewayHealthIndicator.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/health/IshinGatewayHealthIndicator.java)

Adicionar detalhes do cluster quando ativo:

```java
if (clusterService.isClusterMode()) {
    builder.withDetail("clusterMode", true)
           .withDetail("isLeader", clusterService.isLeader())
           .withDetail("activeMembers", clusterService.getActiveMembersCount());
}
```

---

### Componente 6: Graceful Shutdown

#### [MODIFY] [EndpointManager.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/manager/EndpointManager.java)

O shutdown do `NGridNode` já será handled pelo `@PreDestroy` do `ClusterService` (ordem: Spring destrói beans em ordem inversa de criação). Nenhuma mudança necessária no `EndpointManager` — o `ClusterService.@PreDestroy` será adicionado internamente.

---

## Verification Plan

### Automated Tests

**1. Compilação (build sanity):**
```bash
cd /home/lucas/Projects/ishin-gateway
mvn clean compile -DskipTests
```
Deve compilar sem erros.

**2. Boot standalone (sem cluster):**
```bash
cd /home/lucas/Projects/ishin-gateway
# Garantir que adapter.yaml NÃO tem cluster.enabled = true
mvn spring-boot:run -Dspring-boot.run.profiles=dev 2>&1 | head -50
```
Deve botar normalmente, sem erros NGrid. Os logs devem mostrar "Cluster mode: DISABLED (standalone)" ou similar.

### Manual Verification

**3. Revisão de código:**
Após implementação, solicitar ao usuário revisão dos arquivos criados/modificados.

**4. Teste de cluster (2 instâncias locais):**
> Este teste requer o usuário validar manualmente pois depende de 2 terminais simultâneos e backend Keycloak rodando via docker-compose.
> Se o ambiente não estiver disponível, o teste de compilação + boot standalone é suficiente para validar a integração.
