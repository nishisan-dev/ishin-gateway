# Planejamento: Criação do Container Benchmark UI (In-Cluster)

## Visão Geral

O objetivo deste plano é projetar um container dedicado (`benchmark-ui`) equipado com uma interface web esteticamente moderna. Este container fará parte da rede interna do `docker-compose`, eliminando as saídas e entradas pela porta exposta no host (NAT / Docker Proxy). Dessa forma, a interface Web fará requisições para o próprio backend do container, e este executará a ferramenta de estresse (`ab` - Apache Bench) realizando chamadas **diretamente nos IPs internos da bridge**, aferindo o benchmark puramente em nível de rede interna.

## User Review Required

Por favor revise as decisões arquiteturais:
- **Tecnologia do Backend do Benchmark:** Para o container de interface, sugiro utilizarmos **Python com FastAPI**, pois já temos a lógica mapeada em `scripts/benchmark.py`. O backend FastAPI usará `subprocess` para engatilhar o `ab` ou orquestrar o benchmark.
- **Portas e Serviços:** O novo container `benchmark-ui` precisará expor uma porta para seu acesso no browser (ex: `8000` no host). O benchmark *dentro* dele não sai para o host.

## Proposed Changes

### Componente: Infraestrutura Docker Compose

#### [MODIFY] docker-compose.yml
Adição do serviço de benchmark na infraestrutura, apontando para um Dockerfile próprio.
```yaml
  benchmark-ui:
    build: 
      context: .
      dockerfile: compose/benchmark-ui/Dockerfile
    ports:
      - "8000:8000"
    depends_on:
      - static-backend
      - nginx-proxy
      - inventory-adapter
```

### Componente: Container Benchmark UI

#### [NEW] compose/benchmark-ui/Dockerfile
Um sistema base limpo (Python 3.11), no qual instalaremos a ferramenta `ab` e as bibliotecas do servidor web.
```dockerfile
FROM python:3.11-slim

# Instalação do Apache Bench
RUN apt-get update && apt-get install -y apache2-utils && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY compose/benchmark-ui/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# O código em scripts e assets
COPY compose/benchmark-ui/app/ ./app/
COPY scripts/benchmark.py ./scripts/benchmark.py

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

#### [NEW] compose/benchmark-ui/requirements.txt
```text
fastapi==0.110.0
uvicorn==0.28.0
```

### Componente: Backend (Orquestrador do Benchmark)

#### [NEW] compose/benchmark-ui/app/main.py
Um pequeno servidor FastAPI encarregado de:
1. Servir o HTML/CSS contendo a interface.
2. Desponibilizar um Endpoint `POST /api/run` que importe o nosso `scripts/benchmark.py` (ou execute-o via `subprocess`), substituindo dinamicamente as URLs para usarem os domínios internos do Docker:
   - `http://static-backend:8080/`
   - `http://nginx-proxy:8080/`
   - `http://inventory-adapter:9091/`
3. Capturar o JSON com os resultados e retornar diretamente para exibição na UI.

### Componente: Frontend Web UI (Design Premium)

#### [NEW] compose/benchmark-ui/app/static/index.html
Uma interface rica (HTML/Vanilla JS/CSS) construída com responsividade, animações de progresso e *Dark Mode*, seguindo as mais altas diretrizes de UX e UI Premium (gradientes suaves, micro-animações em botões, cartões com glassmorphism).
- **Paridade total com CLI**: Replicará a matriz exata de testes vista via terminal (Fase 1: Requests Fixas vs Fase 2: Timed Tests).
- Controle e inputs para modificar Concorrências (ex: `1, 10, 50, 100, 500`), Quantidade e Duração em segundos.
- Um botão de ação dinâmico que fica em estado *Loading* enquanto o backend roda o benchmark.
- Gráficos/Cards elegantes renderizados dinamicamente do JSON de retorno para comparar throughput, "tail latency" (p99/p100), médias de latência e overhead.

## Verification Plan

### Automated Tests
1. Realizar o rebuild do docker-compose: `docker compose up --build -d` e checar se o `benchmark-ui` estabiliza (HTTP 200).

### Manual Verification
1. Abrir o navegador em `http://localhost:8000`.
2. Utilizar a interface gráfica para engatilhar um teste rápido (ex: 50 requests, c=10).
3. Validar se o teste retornou métricas válidas e observar que os tempos de "overhead" em latência deverão ser consistentemente menores do que os rodados a partir do host (garantindo que o Docker NAT Proxy foi devidamente anulado).
