# Atualização de Documentação e Exemplos — Cluster Mode, Circuit Breaker e Métricas

## Contexto

O ishin-gateway passou por 5 sessões de implementação de horizontal scaling que adicionaram:

1. **Health Check, Graceful Shutdown e Instance ID** (Sessão 1)
2. **NGrid Cluster Mode** — mesh TCP, leader election, DistributedMap (Sessão 2)
3. **OAuth Token Sharing** — POW-RBL via DistributedMap (Sessão 3)
4. **Rules Deploy** — RulesBundle + Admin API + replicação cluster (Sessão 4)
5. **Métricas Prometheus + Circuit Breaker** (Sessão 5)

Nenhum desses itens está refletido na documentação existente (`README.md`, `docs/*.md`). O objetivo é preencher esse gap sem reorganizar a estrutura de documentação atual.

---

## Proposed Changes

### README.md

#### [MODIFY] [README.md](file:///home/lucas/Projects/ishin-gateway/README.md)

1. **Tabela de Features** — adicionar linhas:
   - **Cluster Mode (NGrid)** — Coordenação de múltiplas instâncias via mesh TCP com leader election e DistributedMap
   - **Token Sharing** — Compartilhamento de tokens OAuth via POW-RBL (Publish-on-write + Read-before-login)
   - **Rules Deploy** — Deploy atômico de scripts Groovy via Admin API com replicação cluster
   - **Circuit Breaker** — Resilience4j por backend com transições CLOSED/OPEN/HALF_OPEN
   - **Métricas Prometheus** — Counters/timers inbound/upstream + endpoint `/actuator/prometheus`
   - **Health Check** — Spring Boot Actuator com status de cluster e circuit breaker

2. **Diagrama de Arquitetura ASCII** — adicionar bloco NGrid cluster abaixo do diagrama existente mostrando topologia com LB

3. **Diagrama PlantUML** — adicionar referência ao diagrama de topologia cluster (novo)

4. **Quick Start** — adicionar seção "Subir como Cluster" com comandos do `docker-compose.cluster.yml`

5. **Tabela de Portas** — adicionar:
   - `9190` — Spring Boot Actuator (health/prometheus/admin API)
   - `7100` — NGrid mesh (inter-nó, interno)
   - `5080` — nginx LB cluster

6. **Profiles** — adicionar instrução para cluster mode

7. **Tabela de Documentação** — adicionar link para `docs/cluster_integration_tests.md`

8. **Tech Stack** — adicionar:
   - NGrid (nishi-utils) 3.1.0 — Distributed structures e cluster coordination
   - Resilience4j 2.2.0 — Circuit breaker
   - Micrometer — Métricas Prometheus

9. **Estrutura do Projeto** — adicionar:
   - `cluster/` no src com `ClusterService`
   - `docker-compose.cluster.yml`
   - `config/adapter-cluster.yaml`

---

### docs/architecture.md

#### [MODIFY] [architecture.md](file:///home/lucas/Projects/ishin-gateway/docs/architecture.md)

1. **Visão Geral** — mencionar cluster mode como capacidade
2. **Nova seção: Cluster Mode (NGrid)** — após "Modelo de Threading":
   - Diagrama de topologia (embed do PlantUML)
   - ClusterService — lifecycle do NGridNode, leadership, DistributedMap
   - Token Sharing (POW-RBL)
   - Rules Deploy e replicação
   - Circuit Breaker (BackendCircuitBreakerManager)
   - Métricas Prometheus (ProxyMetrics)
3. **Tabela de Pacotes** — adicionar:
   - `dev.nishisan.ishin.cluster` — Cluster mode: ClusterService, NGrid lifecycle
   - `dev.nishisan.ishin.http.circuit` — Circuit breaker: BackendCircuitBreakerManager

---

### docs/configuration.md

#### [MODIFY] [configuration.md](file:///home/lucas/Projects/ishin-gateway/docs/configuration.md)

1. **Estrutura Geral** — adicionar blocos `cluster:`, `admin:`, `circuitBreaker:` ao YAML da visão geral
2. **Nova seção: Cluster Mode** — referência completa do bloco `cluster:`:
   - `enabled`, `nodeId`, `host`, `port`, `clusterName`, `seeds`, `replicationFactor`, `dataDirectory`
   - Exemplo YAML com 3 nós
3. **Nova seção: Admin API** — referência do bloco `admin:`:
   - `enabled`, `apiKey`
   - Exemplo de uso com `curl`
4. **Nova seção: Circuit Breaker** — referência do bloco `circuitBreaker:`:
   - `failureRateThreshold`, `waitDurationInOpenState`, `slidingWindowSize`, etc.
   - Exemplo YAML
5. **Variáveis de Ambiente** — adicionar:
   - `ISHIN_CLUSTER_NODE_ID`
   - `ISHIN_CLUSTER_PORT`
   - `MANAGEMENT_PORT` / `SERVER_PORT`
6. **Exemplos** — adicionar exemplo "Cluster Mode — 3 nós com token sharing"

---

### docs/use_cases.md

#### [MODIFY] [use_cases.md](file:///home/lucas/Projects/ishin-gateway/docs/use_cases.md)

1. **Caso #8: Cluster com Token Sharing** — cenário end-to-end completo:
   - Diagrama ASCII simplificado
   - `adapter-cluster.yaml` completo
   - `docker-compose.cluster.yml` (referência)
   - Comandos de validação (health check, leader election, token sharing)
   - Tabela resumo atualizada

---

### docs/observability.md

#### [MODIFY] [observability.md](file:///home/lucas/Projects/ishin-gateway/docs/observability.md)

1. **Stack** — adicionar Micrometer e Resilience4j na tabela
2. **Nova seção: Métricas Prometheus** — após seção de Componentes Internos:
   - Endpoint `/actuator/prometheus`
   - Tabela de métricas inbound (`ishin.requests.total`, `ishin.request.duration`, `ishin.request.errors`)
   - Tabela de métricas upstream (`ishin.upstream.requests`, `ishin.upstream.duration`, `ishin.upstream.errors`)
   - Tabela de métricas cluster (`ishin.cluster.active.members`, `ishin.cluster.is.leader`)
   - Tabela de métricas circuit breaker (Resilience4j auto-registered)
3. **Nova seção: Circuit Breaker** — headers e comportamento:
   - Header `x-circuit-breaker: OPEN` no 503
   - Transições CLOSED → OPEN → HALF_OPEN → CLOSED

---

### Diagramas PlantUML

#### [NEW] [cluster_topology.puml](file:///home/lucas/Projects/ishin-gateway/docs/diagrams/cluster_topology.puml)

Diagrama de topologia do cluster mostrando:
- Load Balancer (nginx) na frente
- 3 nós ishin-gateway com NGrid mesh TCP entre eles
- DistributedMap para tokens e rules bundles
- Backends compartilhados
- Portas de cada componente

---

## Verification Plan

### Validação Visual dos Diagramas
- Acessar `https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/ishin-gateway/main/docs/diagrams/cluster_topology.puml` após merge para validar renderização
- Como os diagramas só renderizam via proxy a partir do GitHub raw URL, a validação local é pela sintaxe PlantUML

### Revisão Manual de Consistência
- Conferir que todos os links internos nos docs apontam para arquivos existentes
- Conferir que os exemplos YAML correspondem aos campos reais em `ClusterConfiguration.java` e `CircuitBreakerConfiguration.java`
- Confirmar que as portas documentadas batem com `docker-compose.cluster.yml`
