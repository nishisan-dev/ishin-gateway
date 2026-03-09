# Rules Deploy + Admin API Auth — Plano de Implementação

## Contexto

Continuar a Fase 2 do horizontal scaling do n-gate, implementando os itens **#6 (Rules Deploy)** e **#7 (Auth do Admin API)** do Mapa de Prioridades. Estes são os últimos itens pendentes da Fase 2. O cluster NGrid (#4) e token sharing OAuth (#5) já estão implementados e validados com testes de integração T1-T7.

> [!IMPORTANT]
> - O GroovyScriptEngine (GSE) é criado **por endpoint** dentro do `HttpProxyManager.init()`. O swap atômico precisa substituir a referência `gse` no `HttpProxyManager`, não no `EndpointManager`.
> - O `HttpProxyManager` **não é um bean Spring** — é criado manualmente pela `EndpointWrapper`. Precisamos de uma forma de propagar o novo bundle para todos os `HttpProxyManager` ativos.
> - A dependência circular Spring (ClusterService ↔ ConfigurationManager ↔ OAuthClientManager) já foi resolvida na Sessão 3 com `ApplicationContext.getBean()`. O mesmo padrão será usado aqui.

---

## Proposed Changes

### Componente: Model (RulesBundle)

#### [NEW] [RulesBundle.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/rules/RulesBundle.java)

Record `Serializable` que encapsula um deploy atômico de scripts:

```java
public record RulesBundle(
    long version,
    Instant deployedAt,
    String deployedBy,
    Map<String, byte[]> scripts  // "default/Rules.groovy" → bytes
) implements Serializable {}
```

- Footprint mínimo (<1MB mesmo com dezenas de scripts)
- Serializable para transporte via `DistributedMap` do NGrid

---

### Componente: Rules Bundle Manager

#### [NEW] [RulesBundleManager.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/rules/RulesBundleManager.java)

`@Service` Spring que gerencia o ciclo de vida dos bundles:

**Responsabilidades:**
1. **Receber** bundle via Admin API
2. **Persistir** em disco (`data/rules-bundle.dat`) para sobreviver restarts
3. **Materializar** scripts em diretório temporário
4. **Criar** novo `GroovyScriptEngine` apontando para o dir temporário
5. **Swap atômico**: notificar todos os `HttpProxyManager` ativos para trocar o GSE (via `volatile` reference)
6. **Cluster**: publicar no `DistributedMap("ngate-rules")` para replicação
7. **Listener** do `DistributedMap` para aplicar bundles recebidos de outros nós

**Integração com HttpProxyManager:**
- `HttpProxyManager` ganha um método público `swapGroovyEngine(GroovyScriptEngine newGse)`
- `EndpointManager` registra cada `wrapper` no `RulesBundleManager` para propagação do swap
- Alternativa mais simples: `EndpointManager` expõe `List<EndpointWrapper>` e o `RulesBundleManager` itera sobre eles

**Boot behavior:**
```
if (existeBundlePersistedInDisk()) {
    carregaDoBundlePersistido();
} else {
    // sem bundle → GSE carrega normalmente de rules/ (comportamento legado)
}
```

---

### Componente: Admin API (Endpoints + Auth)

#### [NEW] [AdminApiConfiguration.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/AdminApiConfiguration.java)

POJO Jackson para o bloco `admin:` do adapter.yaml:

```java
public class AdminApiConfiguration {
    private boolean enabled = false;
    private String apiKey;
    // getters/setters
}
```

#### [MODIFY] [ServerConfiguration.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/ServerConfiguration.java)

Adicionar campo `AdminApiConfiguration admin` (nullable).

#### [NEW] [AdminController.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/admin/AdminController.java)

`@RestController` Spring MVC na porta de management (9190 — Undertow/Actuator):

```
POST /admin/rules/deploy     — recebe RulesBundle como multipart ou JSON
GET  /admin/rules/version    — retorna versão + timestamp do bundle ativo
```

**Autenticação:** header `X-API-Key` validado contra `admin.apiKey` do adapter.yaml. Sem API Key configurada → endpoints desabilitados (403).

#### [MODIFY] [adapter.yaml](file:///home/g0004218/Projects/n-gate/config/adapter.yaml)

Adicionar bloco `admin:` comentado como exemplo:

```yaml
# --- Admin API ---
# admin:
#   enabled: true
#   apiKey: "my-secure-api-key"
```

---

### Componente: HttpProxyManager (swap GSE)

#### [MODIFY] [HttpProxyManager.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpProxyManager.java)

- Tornar campo `gse` **`volatile`**
- Adicionar método público `swapGroovyEngine(GroovyScriptEngine newGse)`
- O método `evalDynamicRules()` já lê `gse` localmente — com `volatile` o swap é thread-safe

#### [MODIFY] [EndpointWrapper.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/EndpointWrapper.java)

- Expor método `getProxyManager()` ou `swapGroovyEngine()` que delega para o `HttpProxyManager`

#### [MODIFY] [EndpointManager.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/manager/EndpointManager.java)

- Tornar `activeWrappers` acessível (getter) para o `RulesBundleManager` iterar durante swap
- Ou registrar os wrappers no `RulesBundleManager` após criação

---

### Componente: Cluster Integration

#### [MODIFY] [ClusterService.java](file:///home/g0004218/Projects/n-gate/src/main/java/dev/nishisan/ngate/cluster/ClusterService.java)

- O `getDistributedMap()` já é genérico e funcional
- `RulesBundleManager` usa `getDistributedMap("ngate-rules", String.class, RulesBundle.class)`
- Registrar listener de mudança no `DistributedMap` para aplicar bundle recebido de peers

---

## Verification Plan

### Testes Automatizados

**Existentes (devem continuar passando):**
```bash
mvn test -pl . -Dtest="NGridClusterIntegrationTest" -DfailIfNoTests=false
mvn test -pl . -Dtest="NGridClusterOAuthIntegrationTest" -DfailIfNoTests=false
```

> [!NOTE]
> Os testes existentes usam Testcontainers e são pesados (~2min cada). Rodar apenas quando necessário.

**Build verification (rápido):**
```bash
mvn clean compile -DskipTests
```

**Novos testes — candidatos:**

1. **T8: Rules Deploy standalone**: container único, enviar bundle via `POST /admin/rules/deploy` com API Key, verificar `GET /admin/rules/version`, verificar que o novo script está em efeito (e.g., response header customizado)
2. **T9: Rules Deploy cluster replication**: 2 nós, enviar bundle ao nó 1, verificar que nó 2 aplica automaticamente via `DistributedMap`

> [!IMPORTANT]
> Estes testes requerem que o endpoint Admin API esteja na porta de management do Actuator (Undertow). Precisamos confirmar se o `@RestController` Spring funciona na mesma porta do Actuator ou se precisa de configuração adicional.

### Verificação Manual

1. **Subir n-gate local** com `docker-compose up`
2. **Enviar deploy** com `curl -X POST -H "X-API-Key: test-key" -F "scripts=@rules/" http://localhost:9190/admin/rules/deploy`
3. **Verificar versão**: `curl http://localhost:9190/admin/rules/version`
4. **Health check**: `curl http://localhost:9190/actuator/health` — deve incluir info do bundle ativo
