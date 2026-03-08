# Benchmark UI — Fix de Bugs + SSE Progress em Tempo Real

O benchmark-ui "trava" porque: (1) a fase timed roda sempre por bug de indentação, (2) `subprocess.run` bloqueia o event loop do FastAPI, e (3) não há feedback de progresso na UI. Este plano corrige os 3 problemas.

## Proposed Changes

### benchmark.py — Fix indentação + stdout JSON-lines

#### [MODIFY] [benchmark.py](file:///home/lucas/Projects/inventory-adapter/scripts/benchmark.py)

1. **Fix de indentação**: mover o bloco `timed_results` e o loop timed (linhas 295-309) para dentro do `if benchmark_mode in ["all", "timed"]`
2. **Emitir progresso via stdout**: adicionar `print()` com JSON-lines prefixados com `PROGRESS:` em pontos-chave da execução (início de fase, início de cada endpoint/concorrência, resultado parcial). O `main.py` vai ler essas linhas em tempo real e enviá-las como SSE.
   - Formato: `PROGRESS:{"phase":"warmup","step":"start",...}`
   - Eventos: `warmup_start`, `warmup_done`, `phase_start`, `endpoint_start`, `endpoint_done`, `phase_complete`, `done`
3. Manter compatibilidade: o script continua funcionando standalone no terminal normalmente, os prints PROGRESS: são ignoráveis.

---

### main.py — Endpoint SSE + asyncio

#### [MODIFY] [main.py](file:///home/lucas/Projects/inventory-adapter/compose/benchmark-ui/app/main.py)

1. **Remover endpoint antigo** `/api/run` síncrono
2. **Novo endpoint** `GET /api/run-stream` usando SSE (Server-Sent Events):
   - Receber params via query string (mode, requests, etc.)
   - Usar `asyncio.create_subprocess_exec` para rodar `benchmark.py` de forma totalmente async
   - Ler stdout linha a linha, filtrar prefixo `PROGRESS:`, enviar como SSE events
   - No final, ler o JSON de resultados e enviar como evento `result`
3. Adicionar `sse-starlette` como dependência

---

### index.html — EventSource para SSE

#### [MODIFY] [index.html](file:///home/lucas/Projects/inventory-adapter/compose/benchmark-ui/app/templates/index.html)

1. **Substituir `fetch()`** por `EventSource` conectando ao `/api/run-stream`
2. **Adicionar painel de log** abaixo do spinner mostrando o progresso em tempo real (qual fase, qual endpoint, qual concorrência)
3. **Barra de progresso** visual baseada nos eventos recebidos (sabemos quantos passos no total)
4. Quando o evento `result` chegar, renderizar os cards como antes

---

### requirements.txt

#### [MODIFY] [requirements.txt](file:///home/lucas/Projects/inventory-adapter/compose/benchmark-ui/requirements.txt)

Adicionar `sse-starlette>=1.6.0`

---

## Verification Plan

### Manual — via curl no SSE

```bash
# Subir o ambiente
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build

# Testar o stream SSE (deve mostrar eventos em tempo real)
curl -N http://localhost:8000/api/run-stream?mode=requests&requests=100&concurrencies=1,10
```

O curl deve imprimir eventos SSE progressivamente (não esperar tudo de uma vez).

### Manual — via Browser

Abrir `http://localhost:8000` no browser, clicar em "INICIAR BENCHMARK" e verificar:
1. O log de progresso aparece em tempo real
2. A barra de progresso avança
3. Ao final, os cards de resultado são renderizados
