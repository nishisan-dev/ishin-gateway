# Referência de Configuração — adapter.yaml

O n-gate é configurado através do arquivo `adapter.yaml`, cujo caminho é definido pela variável de ambiente `NGATE_CONFIG` (padrão: `config/adapter.yaml`).

---

## Estrutura Geral

```yaml
endpoints:
  default:
    listeners:     # Listeners HTTP/HTTPS
    backends:      # Backends de destino
    ruleMapping:   # Script Groovy global
    ruleMappingThreads: 1
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

## Listeners

Cada listener é um servidor HTTP independente com porta, regras de segurança e backend default próprios.

```yaml
listeners:
  <nome-do-listener>:
    listenAddress: "0.0.0.0"       # Endereço de bind
    listenPort: 9090               # Porta TCP
    ssl: false                     # Habilita HTTPS
    scriptOnly: false              # Se true, apenas executa scripts (sem proxy)
    defaultScript: "Default.groovy" # Script padrão (legacy)
    defaultBackend: "keycloak"     # Backend de destino padrão
    secured: false                 # Habilita autenticação JWT
    secureProvider:                # Configuração do decoder JWT (se secured: true)
      providerClass: "..."
      name: "..."
      options: {}
    urlContexts:                   # Mapeamento de rotas
      <nome-contexto>:
        context: "/*"              # Path pattern
        method: "ANY"              # GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE, ANY
        ruleMapping: "..."         # Script Groovy para este contexto (sobrescreve global)
        secured: true              # Override de segurança por contexto
```

### Campos do Listener

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `listenAddress` | String | `"0.0.0.0"` | Endereço de bind da interface de rede |
| `listenPort` | Integer | — | Porta TCP do listener |
| `ssl` | Boolean | `false` | Habilita HTTPS (requer keystore em `ssl/`) |
| `scriptOnly` | Boolean | `false` | Se `true`, não faz proxy — apenas executa scripts e retorna respostas sintéticas |
| `defaultScript` | String | `"Default.groovy"` | Script Groovy executado por padrão (legacy) |
| `defaultBackend` | String | — | Nome do backend padrão (chave em `backends`) |
| `secured` | Boolean | `false` | Habilita validação JWT nos requests de entrada |
| `secureProvider` | Object | — | Configuração do provider de decodificação de token |

### URL Contexts

Cada URL Context define um padrão de rota e seu comportamento:

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `context` | String | — | Path pattern (ex: `/*`, `/api/*`, `/health`) |
| `method` | String | — | Método HTTP ou `ANY` para todos |
| `ruleMapping` | String | — | Script Groovy específico (sobrescreve o global) |
| `secured` | Boolean | herda do listener | Override de segurança por rota |

---

## Backends

Cada backend representa um serviço de destino para onde o n-gate encaminha requests.

```yaml
backends:
  <nome-do-backend>:
    backendName: "keycloak"
    xOriginalHost: null
    endPointUrl: "http://keycloak:8080"
    oauthClientConfig:             # Opcional: credenciais OAuth2
      ssoName: "inventory-keycloak"
      clientId: "ngate-client"
      clientSecret: "ngate-secret"
      userName: "inventory-svc"
      password: "inventory-svc-pass"
      tokenServerUrl: "http://keycloak:8080/realms/.../token"
      useRefreshToken: true
      renewBeforeSecs: 30
      authorizationServerUrl: null
      authScopes:
        - "openid"
        - "profile"
        - "email"
```

### Campos do Backend

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `backendName` | String | — | Identificador do backend |
| `xOriginalHost` | String | `null` | Header `X-Original-Host` a ser injetado |
| `endPointUrl` | String | — | URL base do backend (incluindo scheme e porta) |
| `oauthClientConfig` | Object | `null` | Se presente, habilita autenticação OAuth2 automática |

### OAuth Client Config

Quando configurado, o n-gate obtém e injeta automaticamente um `Bearer` token no header `Authorization` de cada request ao backend.

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `ssoName` | String | — | Identificador único do SSO client |
| `clientId` | String | — | Client ID OAuth2 |
| `clientSecret` | String | — | Client Secret OAuth2 |
| `userName` | String | — | Usuário para grant `password` |
| `password` | String | — | Senha do usuário |
| `tokenServerUrl` | String | — | URL do endpoint de token |
| `useRefreshToken` | Boolean | `false` | Usa refresh token para renovação |
| `renewBeforeSecs` | Integer | `30` | Segundos antes da expiração para renovar |
| `authorizationServerUrl` | String | `null` | URL do authorization server (se diferente) |
| `authScopes` | List\<String\> | — | Scopes solicitados no token |

---

## Secure Provider

Configuração do decoder JWT para validação de tokens de entrada (quando `secured: true`).

```yaml
secureProvider:
  providerClass: "dev.nishisan.ngate.auth.jwt.JWTTokenDecoder"
  name: "local-keycloak-jwt"
  options:
    issuerUri: http://keycloak:8080/realms/inventory-dev
    jwkSetUri: http://keycloak:8080/realms/inventory-dev/protocol/openid-connect/certs
```

### Campos do Secure Provider

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `providerClass` | String | Classe Java do decoder (`JWTTokenDecoder`) ou path de script Groovy |
| `name` | String | Nome do provider (usado como cache key) |
| `options` | Map | Opções passadas ao decoder (ex: `issuerUri`, `jwkSetUri`) |

O `providerClass` pode ser:
- `dev.nishisan.ngate.auth.jwt.JWTTokenDecoder` — Decoder built-in
- Caminho de um script Groovy em `custom/` — Decoder customizado via closures

---

## Tuning de Performance

### Jetty Thread Pool

Controla o pool de threads do servidor HTTP (Jetty 12):

| Parâmetro | Tipo | Default | Descrição |
|-----------|------|---------|-----------|
| `jettyMinThreads` | Integer | `16` | Threads mínimas no pool |
| `jettyMaxThreads` | Integer | `500` | Threads máximas no pool |
| `jettyIdleTimeout` | Integer | `120000` | Timeout de idle (ms) para threads |

### OkHttp Connection Pool

Pool de conexões TCP compartilhado entre todos os backends:

| Parâmetro | Tipo | Default | Descrição |
|-----------|------|---------|-----------|
| `connectionPoolSize` | Integer | `256` | Conexões máximas no pool |
| `connectionPoolKeepAliveMinutes` | Integer | `5` | Tempo de keep-alive (minutos) |

### OkHttp Dispatcher

Controla concorrência de requests upstream (usa Virtual Threads):

| Parâmetro | Tipo | Default | Descrição |
|-----------|------|---------|-----------|
| `dispatcherMaxRequests` | Integer | `512` | Requests simultâneos máximos |
| `dispatcherMaxRequestsPerHost` | Integer | `256` | Requests simultâneos por host |

### Outros

| Parâmetro | Tipo | Default | Descrição |
|-----------|------|---------|-----------|
| `socketTimeout` | Integer | `30` | Timeout de socket (segundos) — aplica-se a connect, read, write e call |
| `ruleMapping` | String | `"default/Rules.groovy"` | Script Groovy global |
| `ruleMappingThreads` | Integer | `1` | Threads para execução de regras (ThreadPool) |

---

## Variáveis de Ambiente

| Variável | Default | Descrição |
|----------|---------|-----------|
| `NGATE_CONFIG` | `config/adapter.yaml` | Caminho do arquivo de configuração |
| `ZIPKIN_ENDPOINT` | — | URL do Zipkin collector (ex: `http://zipkin:9411/api/v2/spans`) |
| `TRACING_ENABLED` | `true` | Habilita/desabilita tracing |
| `SPRING_PROFILES_DEFAULT` | `dev` | Profile Spring Boot ativo (`dev`, `bench`) |

---

## Exemplos

### Mínimo — Proxy simples sem autenticação

```yaml
---
endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        ssl: false
        scriptOnly: false
        defaultBackend: "my-api"
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

    backends:
      my-api:
        backendName: "my-api"
        endPointUrl: "http://api-server:3000"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 8
    jettyMaxThreads: 200
    jettyIdleTimeout: 60000
    connectionPoolSize: 64
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 128
    dispatcherMaxRequestsPerHost: 64
```

### Avançado — Múltiplos listeners com autenticação

```yaml
---
endpoints:
  default:
    listeners:
      # Listener público (sem auth)
      public:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        ssl: false
        scriptOnly: false
        defaultBackend: "public-api"
        secured: false
        urlContexts:
          all:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

      # Listener privado (com auth JWT)
      private:
        listenAddress: "0.0.0.0"
        listenPort: 8443
        ssl: false
        scriptOnly: false
        defaultBackend: "private-api"
        secured: true
        secureProvider:
          providerClass: "dev.nishisan.ngate.auth.jwt.JWTTokenDecoder"
          name: "keycloak-jwt"
          options:
            issuerUri: http://keycloak:8080/realms/my-realm
            jwkSetUri: http://keycloak:8080/realms/my-realm/protocol/openid-connect/certs
        urlContexts:
          api:
            context: "/api/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"
            secured: true
          health:
            context: "/health"
            method: "GET"
            secured: false

    backends:
      public-api:
        backendName: "public-api"
        endPointUrl: "http://public-service:3000"

      private-api:
        backendName: "private-api"
        endPointUrl: "https://private-service:8443"
        oauthClientConfig:
          ssoName: "private-sso"
          clientId: "gateway-client"
          clientSecret: "my-secret"
          userName: "svc-user"
          password: "svc-pass"
          tokenServerUrl: "http://keycloak:8080/realms/my-realm/protocol/openid-connect/token"
          useRefreshToken: true
          renewBeforeSecs: 30
          authScopes:
            - "openid"
            - "profile"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 16
    jettyMaxThreads: 500
    jettyIdleTimeout: 120000
    connectionPoolSize: 256
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 512
    dispatcherMaxRequestsPerHost: 256
```
