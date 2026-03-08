# Dev Compose

This repository now uses a single local development stack with:

- `n-gate`
- `keycloak`
- `zipkin`

## Start

```bash
docker compose up --build
```

## Services

- Inventory adapter: `http://localhost:9090`
- Keycloak: `http://localhost:8081` (admin/admin)
- Zipkin UI: `http://localhost:9411`

## Quick Integration Check

Call the adapter and proxy to Keycloak userinfo endpoint:

```bash
curl -i http://localhost:9090/realms/inventory-dev/protocol/openid-connect/userinfo
```

The adapter obtains a token from Keycloak using:

- client id: `n-gate-client`
- client secret: `n-gate-secret`
- username: `inventory-svc`
- password: `inventory-svc-pass`

This configuration is defined in `config/adapter.yaml` and the realm import in `compose/keycloak/realm-inventory-dev.json`.
