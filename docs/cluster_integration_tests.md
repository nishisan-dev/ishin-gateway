# Testes de Integração: Cluster Mode NGrid

## Visão Geral

O ishin-gateway suporta um modo cluster opcional via [NGrid](https://github.com/nishisan-dev/nishi-utils), permitindo múltiplas instâncias coordenarem estado distribuído (tokens OAuth, leader election, health). Os testes de integração validam este comportamento com **2 ou 3 nós reais** rodando em containers Docker efêmeros.

## Stack de Teste

| Componente | Tecnologia | Versão |
|---|---|---|
| Orquestração de containers | [Testcontainers](https://testcontainers.org) | 2.0.3 |
| Framework de teste | JUnit 5 | via Spring Boot |
| Polling assíncrono | [Awaitility](https://github.com/awaitility/awaitility) | 4.2.2 |
| HTTP Client | OkHttp | 4.12.0 |
| Backend mock | Nginx Alpine | latest |
| IdP (OAuth) | Keycloak | 26.0 |

## Topologia dos Testes

### Cluster Básico (`NGridClusterIntegrationTest`)

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

### Cluster com OAuth (`NGridClusterOAuthIntegrationTest`)

```
              ┌──────────────────────────────┐
              │  Docker Network (isolada)     │
              │                              │
              │  ┌──────────┐ ┌───────────┐  │
              │  │ Keycloak │ │ mock-bknd │  │
              │  │  :8080   │ │   :8080   │  │
              │  └────┬─────┘ └─────┬─────┘  │
              │       │ OAuth       │ HTTP   │
              │   ┌───┴─────────────┴───┐    │
              │   │                     │    │
              │ ┌─┴──┐              ┌──┴─┐  │
              │ │ N1 │    NGrid     │ N2 │  │
              │ │9091│◄────────────►│9091│  │
              │ │7100│  DistMap     │7100│  │
              │ └────┘  (tokens)    └────┘  │
              └──────────────────────────────┘
```

- **N1/N2**: Instâncias ishin-gateway com cluster mode + OAuth habilitados
- **Keycloak**: IdP com realm `inventory-dev`, client `ishin-client`
- **DistMap (tokens)**: `DistributedMap<String, SerializableTokenData>` do NGrid

## Cenários de Teste

### Cluster Básico (sem OAuth) — `NGridClusterIntegrationTest`

| # | Teste | Descrição |
|---|-------|-----------|
| T1 | Mesh Formation | 2 nós formam mesh NGrid (`activeMembers: 2`) |
| T2 | Leader Election | Exatamente 1 líder (XOR validation) |
| T3 | Proxy Funcional | HTTP 200 em ambos os nós via mock backend |
| T4 | Instance ID | `instanceId` distinto por nó no health |
| T5 | Graceful Shutdown | Nó 2 para, Nó 1 continua com `activeMembers ≤ 1` |

### Token Sharing OAuth (com Keycloak) — `NGridClusterOAuthIntegrationTest`

| # | Teste | Descrição |
|---|-------|-----------|
| T6 | Token Sharing POW-RBL | Nó 1 obtém token do Keycloak → publica no `DistributedMap` → Nó 2 lê do mapa sem ir ao IdP |
| T7 | Resiliência na Queda | Nó 1 cai → Nó 2 continua servindo com token válido (cache local + DistributedMap) |

> **POW-RBL** = Publish-on-write + Read-before-login. Qualquer nó que obtém um token publica no mapa distribuído. Antes de ir ao IdP, cada nó verifica se existe token válido no mapa.

---

## Comutação e retorno do líder — `NGridClusterFailoverIntegrationTest`

Este teste inicia três gateways simultaneamente, com quórum de dois, um Keycloak
real e um backend Nginx em rede Docker isolada. Executa dois cenários independentes: desligamento
normal do líder e queda abrupta por `SIGKILL`.

Em cada cenário, verifica:

- Formação do cluster com três membros e exatamente um líder.
- Replicação de uma regra inicial e compartilhamento do mesmo token OAuth,
  com apenas um login no IdP.
- Eleição de um novo líder entre os dois sobreviventes e continuidade HTTP
  durante a comutação e a reintegração.
- Publicação de outra versão das regras e aquisição de um token para uma segunda
  configuração SSO enquanto o antigo líder permanece desligado.
- Reinício do mesmo container, preservando identidade e disco, e convergência
  para três membros com exatamente um líder estável durante dez segundos.
  A afinidade do NGrid pode devolver a liderança ao nó que retornou após sincronizar.
- Aplicação da regra atualizada e recuperação dos dois tokens, incluindo o criado
  durante a ausência, sem novos logins no IdP.

As respostas do backend confirmam o Bearer e a execução da regra. Os tokens são
comparados por SHA-256, e as chamadas ao endpoint OAuth são contadas no Nginx;
a validação não depende de mensagens de log do gateway.

```bash
mvn package -DskipTests
mvn test -Dtest=NGridClusterFailoverIntegrationTest
```

O teste é incluído automaticamente em `mvn clean verify`. Não cobre partições de
rede, expiração de tokens, escritas concorrentes no instante do desligamento
ou compatibilidade entre versões diferentes do gateway.

### Reprodução antes da correção em 15/10/2026

Execução com `nishi-utils-core` 8.14.1, Java 25 e Docker remoto em `192.168.5.92`:

| Cenário | Amostras HTTP | Erros HTTP | Logins OAuth | Resultado |
|---|---:|---:|---:|---|
| Desligamento normal + retorno | 246 | 0 | 3 (esperado: 2) | Falha no reaproveitamento do token inicial pelo nó reintegrado |
| Queda abrupta + retorno | 242 | 0 | 2 | Aprovado |

Nos dois casos houve nova eleição, retorno dos três membros, liderança estável
após a reintegração e aplicação da regra v2. O token criado durante a ausência
foi recuperado nos dois cenários. No desligamento normal, o token inicial mudou
no nó reintegrado e houve um login adicional no IdP. Essa falha ocorreu também
na execução anterior e fundamentou a regressão de recuperação de tokens.

Essa execução terminou com dois cenários executados, uma falha e nenhum erro
de infraestrutura. A reprodução isolada descrita abaixo confirmou a causa na
biblioteca NGrid.

### Resultado com a mitigação temporária do gateway (8.14.1)

O mapa OAuth passou a ser declarado no bootstrap com persistência
`ASYNC_WITH_FSYNC` e recuperação antes da liberação dos handlers do NGrid.
No mesmo Docker remoto, os dois cenários passaram:

| Cenário | Amostras HTTP | Erros HTTP | Logins OAuth | Resultado |
|---|---:|---:|---:|---|
| Desligamento normal + retorno | 254 | 0 | 2 | Aprovado |
| Queda abrupta + retorno | 258 | 0 | 2 | Aprovado |

A validação da correção também passou nos 111 testes unitários e nos outros
19 testes de integração: 132 testes aprovados no total, sendo 21 integrações
executadas no Docker remoto.

O caso da biblioteca foi isolado em dois `NGridNode` TCP, sem gateway: após o
restart normal do líder, ele retornou `null` para uma entrada ainda presente na
réplica local do sobrevivente. A correção geral para mapas voláteis foi solicitada
na [issue nishi-utils #212](https://github.com/nishisan-dev/nishi-utils/issues/212).
Essa mitigação foi validada com diretórios inicialmente criados com persistência.

### Correção na biblioteca — 8.14.2

A [release 8.14.2](https://github.com/nishisan-dev/nishi-utils/releases/tag/v8.14.2)
resolve a recuperação de mapas voláteis após shutdown limpo, exigindo snapshot
antes de liberar o progresso restaurado e a elegibilidade à liderança.
O gateway foi atualizado para essa versão e a mitigação temporária que persistia
tokens em disco foi removida. O teste de comutação usa novamente o modo volátil
padrão, para exercitar diretamente a correção da biblioteca.

Validação em 15/10/2026, Java 25, no Docker remoto em `192.168.5.92`:

| Cenário | Amostras HTTP | Erros HTTP | Logins OAuth | Resultado |
|---|---:|---:|---:|---|
| Desligamento normal + retorno | 228 | 0 | 2 | Aprovado |
| Queda abrupta + retorno | 258 | 0 | 2 | Aprovado |

Os três nós preservaram o token inicial e recuperaram o token criado durante a
ausência, aplicaram a regra v2 e convergiram para uma liderança estável.
A suíte completa (`mvn clean verify`) passou: 111 testes unitários e 21 testes
de integração, sem falhas, erros ou testes ignorados.

A reprodução TCP isolada da issue também passou com o JAR oficial da release:
o líder voltou com a entrada original tanto no modo volátil quanto ao habilitar
`ASYNC_WITH_FSYNC` no retorno usando o mesmo diretório de cursores.

O GitHub Packages respondeu HTTP 401 à credencial local durante esta validação.
Foi usado o JAR publicado na release oficial, instalado no repositório Maven
local com o POM embutido e o POM pai da tag `v8.14.2`.

## Como Executar

### Pré-requisitos

1. **Docker** rodando (versão ≥ 20.10, testado com 29.3.0)
2. **`~/.m2/settings.xml`** com credenciais do GitHub Packages (para resolver `nishi-utils`)
3. **Portas livres**: nenhuma porta fixa necessária (Testcontainers usa portas dinâmicas)
4. **Espaço em disco**: ~500MB para imagens Docker (ishin-gateway, Nginx, Keycloak, Ryuk)

### Comandos

As imagens usam o JAR empacotado localmente, sem enviar credenciais Maven ao Docker.

```bash
# Empacotar o gateway antes dos testes (repetir após alterar código)
mvn -s ~/.m2/settings.xml package -DskipTests

# ─── Executar TODOS os testes de cluster ───────────────────────
mvn -s ~/.m2/settings.xml test -Dtest="NGridCluster*IntegrationTest"

# ─── Executar apenas testes básicos (sem OAuth, mais rápido) ───
mvn -s ~/.m2/settings.xml test -Dtest="NGridClusterIntegrationTest"
# Tempo esperado: ~80s

# ─── Executar apenas testes OAuth (com Keycloak) ──────────────
mvn -s ~/.m2/settings.xml test -Dtest="NGridClusterOAuthIntegrationTest"
# Tempo esperado: ~120s (Keycloak leva ~30s para subir)

# ─── Executar com logs verbose (debug) ────────────────────────
mvn -s ~/.m2/settings.xml test -Dtest="NGridCluster*IntegrationTest" \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

> **Nota:** As imagens de teste copiam o JAR de `target/` e as regras padrão para uma imagem Java 25. Não há compilação Maven dentro do container.

### Interpretando os Logs

Os logs dos containers são prefixados para fácil identificação:

| Prefixo | Container |
|---------|-----------|
| `[node-1]` | ishin-gateway Nó 1 (cluster básico) |
| `[node-2]` | ishin-gateway Nó 2 (cluster básico) |
| `[oauth-node-1]` | ishin-gateway Nó 1 (cluster OAuth) |
| `[oauth-node-2]` | ishin-gateway Nó 2 (cluster OAuth) |
| `[keycloak]` | Keycloak IdP |

**Mensagens-chave nos logs:**

```
# Mesh NGrid formado
"NGrid cluster started — nodeId: [xxx]"
"Leadership change — isLeader: [true/false]"

# Token Sharing POW-RBL
"Token [test-keycloak] published to cluster DistributedMap"    ← publicou
"Token [test-keycloak] loaded from cluster DistributedMap"     ← leu do mapa
"Sent New Token: [xxx***]"                                      ← login no IdP
```

---

## Troubleshooting

### Docker API incompatível

```
client version 1.32 is too old. Minimum supported API version is 1.40
```

**Causa:** Testcontainers 1.x usa API Docker antiga. **Solução:** Usar Testcontainers ≥ 2.0.x (já configurado).

### Permissões no `target/`

```
AccessDeniedException: target/test-classes/...
```

**Causa:** Docker compose anterior criou arquivos como `root` no `target/`.
**Solução:**
```bash
docker run --rm -v $(pwd)/target:/target alpine chown -R $(id -u):$(id -g) /target
```

### Timeout na formação do mesh

Se os testes falham com `ConditionTimeout` no mesh formation:
- Verifique se o Docker tem ≥ 2GB RAM disponível
- Em CI, aumente os timeouts no teste (de 90s para 120s)
- Confirme que a rede Docker não está bloqueando tráfego inter-container

### Keycloak lento para subir

Se os testes OAuth falham no startup do Keycloak:
- Keycloak 26.0 pode levar até 60s no primeiro start
- O `startupTimeout` já está em 120s — suficiente para CI

---

## Arquivos Envolvidos

| Arquivo | Propósito |
|---|---|
| [NGridClusterIntegrationTest.java](../src/test/java/dev/nishisan/ishin/cluster/NGridClusterIntegrationTest.java) | Testes T1-T5 (cluster básico) |
| [NGridClusterOAuthIntegrationTest.java](../src/test/java/dev/nishisan/ishin/cluster/NGridClusterOAuthIntegrationTest.java) | Testes T6-T7 (OAuth token sharing) |
| [Dockerfile](../Dockerfile) | Build multi-stage da imagem ishin-gateway |
| [adapter-test-cluster.yaml](../src/test/resources/adapter-test-cluster.yaml) | Config cluster sem OAuth |
| [adapter-test-cluster-oauth.yaml](../src/test/resources/adapter-test-cluster-oauth.yaml) | Config cluster com OAuth + Keycloak |
| [application-test.properties](../src/test/resources/application-test.properties) | Profile Spring Boot para testes |
| [mock-backend.conf](../src/test/resources/testcontainers/mock-backend.conf) | Config Nginx do backend mock |
| [realm-inventory-dev.json](../src/test/resources/testcontainers/realm-inventory-dev.json) | Realm Keycloak para import |

## Decisões de Design

1. **`GenericContainer` sobre `DockerComposeContainer`**: Controle individual sobre lifecycle de cada nó
2. **Sem OAuth nos testes básicos**: Os cenários T1-T5 focam no cluster NGrid — rápido e sem dependência do Keycloak
3. **Awaitility sobre `Thread.sleep`**: Polling com backoff evita flakiness em CI
4. **`ImageFromDockerfile`**: Build a partir do Dockerfile real, garantindo paridade com produção
5. **Race condition no T6 é aceitável**: O POW-RBL não garante que apenas 1 nó fale com o IdP — ambos podem fazer login quase simultaneamente. O teste aceita ambos os cenários (ideal: leu do mapa; aceitável: fez login próprio)
