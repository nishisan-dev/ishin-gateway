# Migração n-gate → ishin-gateway

Renomear completamente o projeto de `n-gate` para `ishin-gateway`, incluindo coordenadas Maven, pacote Java, métricas, packaging, infra e documentação. Produto ainda não lançado — rename limpo, sem fallbacks.

## Decisões Aprovadas

| Item | Valor definitivo |
|---|---|
| groupId | `dev.nishisan.ishin` |
| artifactId | `ishin-gateway` |
| Package Java | `dev.nishisan.ishin.gateway` |
| Classe principal | `IshinGatewayApplication` |
| Métricas Micrometer | prefixo `ishin.*` |
| Frontend | `ishin-gateway-ui` |
| Submodule test-case | `ishin-gateway-test-case` |
| CLI | `ishin-cli` |
| Repositório GitHub | `nishisan-dev/ishin-gateway` |

## Proposed Changes

### Fase 1 — Maven & Java Core

#### [MODIFY] [pom.xml](file:///home/lucas/Projects/n-gate/pom.xml)
- `groupId` → `dev.nishisan.ishin`
- `artifactId` → `ishin-gateway`
- `mainClass` → `dev.nishisan.ishin.gateway.IshinGatewayApplication`
- `exec.mainClass` → idem

#### Rename pacote Java (64+ classes)
- Mover `src/main/java/dev/nishisan/ngate/` → `src/main/java/dev/nishisan/ishin/gateway/`
- Mover `src/test/java/dev/nishisan/ngate/` → `src/test/java/dev/nishisan/ishin/gateway/`
- Atualizar `package` declarations e `import` statements em todos os arquivos
- Renomear: `NGateApplication` → `IshinGatewayApplication`, `NGateHealthIndicator` → `IshinHealthIndicator`

---

### Fase 2 — Métricas & Referências Internas

#### [MODIFY] [ProxyMetrics.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/observability/ProxyMetrics.java)
- Todas as métricas `ngate.*` → `ishin.*` (12 métricas: `ngate.requests.total`, `ngate.request.duration`, `ngate.request.errors`, `ngate.upstream.requests`, `ngate.upstream.duration`, `ngate.upstream.errors`, `ngate.ratelimit.total`, `ngate.context.requests.total`, `ngate.context.duration`, `ngate.context.errors`, `ngate.script.executions.total`, `ngate.script.duration`, `ngate.script.errors`)

#### [MODIFY] [ConfigurationManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/manager/ConfigurationManager.java)
- Paths hardcoded: `/app/n-gate/config/` → `/app/ishin-gateway/config/`, `/etc/n-gate/` → `/etc/ishin-gateway/`

#### [MODIFY] [DashboardConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/DashboardConfiguration.java)
- Default path: `/var/lib/n-gate/dashboard` → `/var/lib/ishin-gateway/dashboard`

#### [MODIFY] [DashboardApiRoutes.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/dashboard/api/DashboardApiRoutes.java)
- Label do nó central: `"n-gate"` → `"ishin-gateway"`

#### [MODIFY] [TracerService.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/observability/service/TracerService.java)
- Log messages: `"n-gate instance ID"` → `"Ishin Gateway instance ID"`

#### [MODIFY] [TunnelService.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/tunnel/TunnelService.java)
- Banner: `"n-gate TUNNEL MODE"` → `"Ishin Gateway TUNNEL MODE"`

#### [MODIFY] [EndpointManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/manager/EndpointManager.java)
- Log: `"n-gate graceful shutdown"` → `"Ishin Gateway graceful shutdown"`

#### [MODIFY] [MetricsCollectorService.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/dashboard/collector/MetricsCollectorService.java)
- Referência ao prefixo de métricas `ngate.*` → `ishin.*`

#### [MODIFY] [application-dev.properties](file:///home/lucas/Projects/n-gate/src/main/resources/application-dev.properties)
- `logging.group.system=dev.nishisan.ngate` → `dev.nishisan.ishin.gateway`

#### Configs de teste
- [MODIFY] `adapter-test-cluster.yaml` — clusterName/nodeIds `ngate-*` → `ishin-*`
- [MODIFY] `adapter-test-cluster-oauth.yaml` — clientId/clusterName/nodeIds
- [MODIFY] `adapter-test-cluster-rules.yaml` — clusterName/nodeIds

#### Testes de integração
- [MODIFY] `PrometheusMetricsIntegrationTest.java` — assertions com `ngate_*` → `ishin_*`

---

### Fase 3 — Debian Packaging

#### [MODIFY] [control](file:///home/lucas/Projects/n-gate/debian/control)
- `Package: n-gate` → `ishin-gateway`, `Description: n-gate` → `Ishin Gateway`

#### [RENAME] `debian/n-gate.service` → `debian/ishin-gateway.service`
- User/Group: `n-gate` → `ishin-gateway`
- Paths: `/opt/n-gate` → `/opt/ishin-gateway`, `/etc/n-gate` → `/etc/ishin-gateway`, `/var/log/n-gate` → `/var/log/ishin-gateway`, `/var/lib/n-gate` → `/var/lib/ishin-gateway`
- JAR: `n-gate.jar` → `ishin-gateway.jar`

#### [MODIFY] [postinst](file:///home/lucas/Projects/n-gate/debian/postinst)
- User/group `n-gate` → `ishin-gateway`, paths, service name

#### [MODIFY] [prerm](file:///home/lucas/Projects/n-gate/debian/prerm)
- Service name `n-gate.service` → `ishin-gateway.service`

#### [MODIFY] [postrm](file:///home/lucas/Projects/n-gate/debian/postrm)
- Paths, user `n-gate` → `ishin-gateway`

#### [MODIFY] [conffiles](file:///home/lucas/Projects/n-gate/debian/conffiles)
- Paths `/etc/n-gate/` → `/etc/ishin-gateway/`

#### [RENAME] `debian/n-gate.logrotate` → `debian/ishin-gateway.logrotate`
- Paths e user/group

#### [RENAME] `debian/ngate-cli` → `debian/ishin-cli`
- Referências internas a paths e nomes

---

### Fase 4 — Docker & CI/CD

#### [MODIFY] [Dockerfile](file:///home/lucas/Projects/n-gate/Dockerfile)
- Comentários, `COPY` do JAR: `n-gate-1.0-SNAPSHOT.jar` → `ishin-gateway-1.0-SNAPSHOT.jar`

#### [MODIFY] [docker-compose.yml](file:///home/lucas/Projects/n-gate/docker-compose.yml)
- Service name `n-gate:` → `ishin-gateway:`
- Env vars `NGATE_CONFIG` → `ISHIN_CONFIG`
- JAR reference, comentários

#### [MODIFY] [docker-compose.cluster.yml](file:///home/lucas/Projects/n-gate/docker-compose.cluster.yml)
- Service names `n-gate:`, `ngate-node*` → `ishin-gateway:`, `ishin-node*`
- Env vars `NGATE_*` → `ISHIN_*`
- Comentários, URLs, JAR references

#### [MODIFY] [docker-compose.bench.yml](file:///home/lucas/Projects/n-gate/docker-compose.bench.yml)
- Service name `n-gate:` → `ishin-gateway:`
- JAR reference

#### [MODIFY] [release.yml](file:///home/lucas/Projects/n-gate/.github/workflows/release.yml)
- Frontend dir `n-gate-ui` → `ishin-gateway-ui`
- PKG name, paths FHS (`/opt/n-gate` → `/opt/ishin-gateway`, `/etc/n-gate` → `/etc/ishin-gateway`)
- Service file, logrotate file, CLI binary names

---

### Fase 5 — Frontend & Submodule

#### [MODIFY] [package.json](file:///home/lucas/Projects/n-gate/n-gate-ui/package.json)
- `"name": "n-gate-ui"` → `"ishin-gateway-ui"`

#### [RENAME] diretório `n-gate-ui/` → `ishin-gateway-ui/`

#### [MODIFY] [.gitmodules](file:///home/lucas/Projects/n-gate/.gitmodules)
- Submodule path e URL para `ishin-gateway-test-case`

> [!IMPORTANT]
> O rename do submodule requer que o repositório `nishisan-dev/ishin-gateway-test-case` já exista no GitHub. Isso deve ser feito manualmente pelo usuário antes da execução desta fase.

---

### Fase 6 — Documentação & Scripts

#### Documentação (`docs/`)
- Search & replace `n-gate` → `ishin-gateway` e `ngate` → `ishin` em todos os 15+ arquivos markdown

#### Planning (`planning/`)
- Search & replace nas referências (menor prioridade, contexto histórico)

#### [MODIFY] [README.md](file:///home/lucas/Projects/n-gate/README.md)
- Título, descrições, referências

#### Scripts
- [MODIFY] `scripts/deploy_adhoc.sh`
- [MODIFY] `n-gate-test-case/scripts/install_ngate.sh` (→ renomear para `install_ishin.sh`)
- [MODIFY] `n-gate-test-case/scripts/common.sh`
- [MODIFY] `n-gate-test-case/Vagrantfile`
- [MODIFY] `n-gate-test-case/start.sh`

---

## Verification Plan

### Build automatizado

```bash
# Após todas as mudanças, validar build completo
cd /home/lucas/Projects/n-gate
mvn clean package -DskipTests

# Testes unitários (excluindo integration tests que precisam de Docker)
mvn test -Dtest='!*IntegrationTest' -DfailIfNoTests=false
```

### Grep exaustivo

```bash
# Verificar zero referências remanescentes (ignorando .git, target, node_modules)
grep -rn --include='*.java' --include='*.xml' --include='*.yml' --include='*.yaml' \
  --include='*.sh' --include='*.md' --include='*.json' --include='*.properties' \
  --include='*.service' --include='*.ts' --include='*.tsx' \
  -E 'n-gate|ngate|NGate|n_gate|NGATE' \
  --exclude-dir=.git --exclude-dir=target --exclude-dir=node_modules \
  /home/lucas/Projects/n-gate/
```

> [!NOTE]
> O diretório raiz do projeto local ainda se chamará `n-gate` até o repositório ser renomeado no GitHub. Esse rename do diretório local e do remote será feito pelo usuário como último passo.
