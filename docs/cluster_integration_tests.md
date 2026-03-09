# Testes de Integração: Cluster Mode NGrid

## Visão Geral

O n-gate suporta um modo cluster opcional via [NGrid](https://github.com/nishisan-dev/nishi-utils), permitindo múltiplas instâncias coordenarem estado distribuído (tokens OAuth, leader election, health). Os testes de integração validam este comportamento com **2 nós reais** rodando em containers Docker efêmeros.

## Stack de Teste

| Componente | Tecnologia | Versão |
|---|---|---|
| Orquestração de containers | [Testcontainers](https://testcontainers.org) | 2.0.3 |
| Framework de teste | JUnit 5 | via Spring Boot |
| Polling assíncrono | [Awaitility](https://github.com/awaitility/awaitility) | 4.2.2 |
| HTTP Client | OkHttp | 4.12.0 |
| Backend mock | Nginx Alpine | latest |

## Topologia dos Testes

```
                    ┌────────────────────┐
                    │  Docker Network    │
                    │  (isolada/efêmera) │
                    │                    │
                    │  ┌──────────────┐  │
                    │  │ mock-backend │  │
                    │  │ (nginx:8080) │  │
                    │  └──────┬───────┘  │
                    │         │          │
                    │    ┌────┴────┐     │
                    │    │         │     │
                    │  ┌─┴──┐  ┌──┴─┐   │
                    │  │ N1 │  │ N2 │   │
                    │  │9091│  │9091│   │
                    │  │9190│  │9190│   │
                    │  │7100│◄─►7100│   │
                    │  └────┘  └────┘   │
                    │    NGrid Mesh     │
                    └────────────────────┘
```

- **N1/N2**: Instâncias n-gate com cluster mode habilitado
- **Porta 9091**: Proxy HTTP (listener `http-noauth`)
- **Porta 9190**: Spring Boot Actuator (health/metrics)
- **Porta 7100**: NGrid TCP mesh (gossip + replicação)
- **mock-backend**: Nginx retornando `{"status":"ok"}` em todas as rotas

## Cenários de Teste

### T1: Mesh Formation

**Classe**: `NGridClusterIntegrationTest#testClusterMeshFormation`

Valida que 2 nós n-gate se descobrem via seeds TCP e formam um mesh NGrid funcional.

**Assertions:**
- Ambos os nós reportam `status: UP` no `/actuator/health`
- Ambos reportam `clusterMode: true` nos detalhes do health
- Ambos reportam `activeMembers: 2`

**Timeout:** 90s (inclui tempo de gossip + handshake + leader election)

---

### T2: Leader Election

**Classe**: `NGridClusterIntegrationTest#testLeaderElection`

Valida que o NGrid elege exatamente 1 líder entre os 2 nós (quorum epoch-based).

**Assertions:**
- Exatamente um nó reporta `isLeader: true` (validado via XOR)
- O outro reporta `isLeader: false`

---

### T3: Proxy Funcional

**Classe**: `NGridClusterIntegrationTest#testProxyFunctional`

Valida que ambos os nós encaminham requests HTTP corretamente para o backend mock, confirmando que o cluster mode **não interfere** no hot path do proxy.

**Assertions:**
- HTTP GET em ambos os nós retorna `200 OK`
- O body contém `{"status":"ok"}` do backend mock
- B3 tracing headers são injetados (confirmado via logs)

> Este teste também valida a **ausência da regressão da Sessão 3** (deadlock de inicialização Spring com Virtual Threads que fazia o proxy aceitar TCP mas nunca processar requests).

---

### T4: Instance ID Distinto

**Classe**: `NGridClusterIntegrationTest#testHealthReportsInstanceId`

Valida que cada nó tem um `instanceId` único, essencial para correlacionar traces e logs em ambiente distribuído.

**Assertions:**
- `instanceId` do Node 1 = `test-node-1` (via `NGATE_INSTANCE_ID` env)
- `instanceId` do Node 2 = `test-node-2`
- Ambos são distintos (`assertNotEquals`)

---

### T5: Graceful Shutdown

**Classe**: `NGridClusterIntegrationTest#testGracefulShutdown`

Valida que ao parar o Nó 2, o Nó 1 continua operando normalmente (sem crash, sem hang) e detecta a saída do peer.

**Assertions:**
- Nó 1 mantém `status: UP` após shutdown do Nó 2
- Proxy do Nó 1 continua retornando `200 OK`
- `activeMembers` cai para ≤ 1 dentro de 60s

---

## Como Executar

```bash
# Pré-requisitos: Docker rodando, ~/.m2/settings.xml com acesso ao GitHub Packages

# Executar apenas os testes de cluster
mvn -s ~/.m2/settings.xml test -Dtest="NGridClusterIntegrationTest"

# Tempo esperado: ~80s (build da imagem Docker + startup dos containers + assertions)
```

> **Nota:** Na primeira execução, o Docker buildará a imagem n-gate via Dockerfile multi-stage (~2-3 min). Execuções subsequentes usam cache (~20s de build).

## Arquivos Envolvidos

| Arquivo | Propósito |
|---|---|
| [NGridClusterIntegrationTest.java](../src/test/java/dev/nishisan/ngate/cluster/NGridClusterIntegrationTest.java) | Classe de teste principal |
| [Dockerfile](../Dockerfile) | Build multi-stage da imagem n-gate |
| [adapter-test-cluster.yaml](../src/test/resources/adapter-test-cluster.yaml) | Config de cluster para testes (sem OAuth) |
| [application-test.properties](../src/test/resources/application-test.properties) | Profile Spring Boot para testes |
| [mock-backend.conf](../src/test/resources/testcontainers/mock-backend.conf) | Config Nginx do backend mock |

## Decisões de Design

1. **`GenericContainer` sobre `DockerComposeContainer`**: Dá controle individual sobre lifecycle de cada nó (parar um, verificar o outro).
2. **Sem OAuth nos testes**: Os cenários focam no cluster NGrid — o fluxo OAuth+Keycloak é ortogonal e será testado separadamente.
3. **Awaitility sobre `Thread.sleep`**: Polling com backoff evita flakiness em CI com recursos limitados.
4. **`ImageFromDockerfile`**: A imagem é buildada a partir do Dockerfile do projeto, garantindo que os testes validam exatamente o que será deployado.
