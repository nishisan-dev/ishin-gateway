# Desenvolvimento

Guia para desenvolvedores que desejam compilar, testar e contribuir com o ishin-gateway.

## Pré-requisitos

| Ferramenta | Versão mínima | Uso |
|------------|:-------------:|-----|
| **Java (OpenJDK)** | 21 | Compilação e runtime |
| **Maven** | 3.9+ | Build e gestão de dependências |
| **Docker** | 24+ | Testes de integração (Testcontainers) |

> [!NOTE]
> O projeto utiliza dependências publicadas no **GitHub Packages** (`dev.nishisan:nishi-utils`, `nishi-utils-spring`).
> Configure seu `~/.m2/settings.xml` com um token GitHub que tenha permissão `read:packages`.

## Build

```bash
# Compilação
mvn clean compile

# Empacotamento (JAR executável, sem rodar testes)
mvn clean package -DskipTests

# Instalação no repositório local
mvn clean install -DskipTests
```

## Testes

### Testes Unitários

Testes rápidos que não requerem Docker nem infraestrutura externa:

```bash
# Rodar todos os testes unitários (exclui integração)
mvn test -Dtest='!*IntegrationTest' -DfailIfNoTests=false

# Rodar um teste específico
mvn test -Dtest='ConfigurationManagerTest'

# Rodar múltiplos testes
mvn test -Dtest='ConfigurationManagerTest,RulesBundleManagerTest,RateLimitManagerTest'
```

### Testes de Integração

Usam **Testcontainers** (requerem Docker rodando):

```bash
# Rodar apenas testes de integração
mvn test -Dtest='*IntegrationTest'

# Exemplo: Circuit Breaker
mvn test -Dtest='CircuitBreakerIntegrationTest'
```

> [!IMPORTANT]
> Testes de integração constroem a imagem Docker do ishin-gateway e levantam containers de mock backends.
> A primeira execução baixa imagens base e pode demorar vários minutos.

### Suíte Completa

```bash
# Unitários + Integração
mvn clean verify
```

## Classes de Teste

| Classe | Tipo | O que testa |
|--------|------|-------------|
| `ConfigurationManagerTest` | Unitário | Parsing/serialização YAML da configuração |
| `RulesBundleManagerTest` | Unitário | Serialização de bundles, limpeza de diretórios, materialização de scripts |
| `RateLimitManagerTest` | Unitário | Modos nowait/stall, isolamento de zonas, estado disabled |
| `UpstreamPoolTest` | Unitário | Estratégias de load balancing (round-robin, weighted, failover, random) |
| `UpstreamHealthCheckerTest` | Unitário | Health checks ativos, recuperação de membros |
| `CircuitBreakerIntegrationTest` | Integração | Transições CLOSED → OPEN → HALF_OPEN com Testcontainers |
| `ClusterIntegrationTest` | Integração | Cluster NGrid 3 nós com replicação de tokens/rules |
| `ClusterOAuthIntegrationTest` | Integração | Compartilhamento OAuth entre nós do cluster |
| `ClusterRulesIntegrationTest` | Integração | Deploy e propagação de rules via cluster |

## Convenções

- **Testes unitários**: nomes terminam em `Test` (ex: `FooBarTest.java`)
- **Testes de integração**: nomes terminam em `IntegrationTest` (ex: `FooBarIntegrationTest.java`)
- **Framework**: JUnit 5 com `@DisplayName` descritivo em português
- **Ordenação**: `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` quando a ordem importa
- **Diretórios temporários**: `@TempDir` do JUnit 5 para testes de I/O

## CI/CD

O pipeline de release (`.github/workflows/release.yml`) executa automaticamente:

1. **Unit tests** — `mvn test` excluindo `*IntegrationTest`
2. **Build** — `mvn package -DskipTests`
3. **Package .deb** — Empacotamento Debian
4. **Upload** — Publica o `.deb` como asset da release

> [!TIP]
> Para simular o CI localmente:
> ```bash
> mvn -B test -Dtest='!*IntegrationTest' -DfailIfNoTests=false && mvn -B -DskipTests package
> ```
