# Fix: Memory Leak no Dashboard (Chrome "Aw, Snap!")

O dashboard ishin-gateway-ui provoca crash do Chrome quando deixado aberto por período prolongado. A investigação revelou **3 problemas críticos** de memory leak relacionados ao gerenciamento de WebSocket e polling.

## Diagnóstico

### 🔴 Problema 1 — Reconexão recursiva infinita de WebSocket (`api.ts`)

```typescript
// Cada onclose/onerror cria uma chamada RECURSIVA que abre um NOVO WS
// O WS antigo nunca é referenciado para cleanup
ws.onerror = () => {
  setTimeout(() => api.connectMetricsWs(onMessage), 5000); // cria WS novo, ninguém fecha
};
ws.onclose = () => {
  setTimeout(() => api.connectMetricsWs(onMessage), 5000); // duplica: onerror + onclose ambos disparam
};
```

**Impacto:** Cada ciclo de desconexão cria 2 novos timeouts (onerror + onclose disparam em sequência). Após N desconexões, existe uma árvore exponencial de WebSockets abertos. Cada WS recebe mensagens e dispara `setMetrics()`  — O(2^n) re-renders acumulados.

### 🔴 Problema 2 — Cleanup insuficiente no `useMetrics` (`useDashboard.ts`)

```typescript
wsRef.current = api.connectMetricsWs(onMessage); // salva ref da 1ª conexão

return () => {
  clearInterval(pollId);
  wsRef.current?.close(); // fecha APENAS o 1º WS; reconexões ficam penduradas
};
```

**Impacto:** Quando o componente desmonta, somente o WS original é fechado. Todas as reconexões (Problema 1) continuam ativas, acumulando dados e event listeners na memória.

### 🟡 Problema 3 — Polling HTTP roda em paralelo infinitamente

O `useMetrics` inicia polling HTTP como "fallback" mas **nunca o desliga** quando o WebSocket conecta. WS + HTTP rodam em paralelo 100% do tempo, duplicando tráfego de rede e processamento de dados a cada 5s.

### 🟡 Problema 4 — Nenhuma suspensão quando tab está em background

Quando o usuário troca de aba, todos os timers/polling/WS continuam rodando. O Chrome limita timers de tabs inativas mas não elimina o acúmulo de dados em memória. Com a tab em background por horas, a memória só cresce.

---

## Proposed Changes

### API Layer

#### [MODIFY] [api.ts](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/api.ts)

Reescrever `connectMetricsWs` substituindo a reconexão recursiva por uma classe `ManagedWebSocket` com lifecycle controlado:

- **AbortSignal** para cancelamento limpo: o caller passa um `AbortSignal` e, quando abortado, a reconexão para e o WS é fechado definitivamente.
- **Reconexão linear** via `setTimeout` com backoff incremental (5s, 10s, 15s... max 30s), usando **uma única instância** — o timer de reconexão é cancelado antes de criar um novo.
- **Guard contra double-fire**: `onclose` NÃO agenda reconexão se `onerror` já agendou (flag `reconnecting`).
- Remover `connectMetricsWs` do objeto `api` e exportar uma função dedicada `createMetricsSocket(onMessage, signal)` que retorna `{ close(): void }`.

---

### Hooks Layer

#### [MODIFY] [useDashboard.ts](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/hooks/useDashboard.ts)

Reescrever `useMetrics`:

- Usar `AbortController` no `useEffect` — o cleanup faz `controller.abort()`, que propaga para o `ManagedWebSocket` e garante que todas as reconexões param.
- **Eliminar polling HTTP duplicado**: usar polling como fallback **somente** quando WS falha. O `ManagedWebSocket` expõe callback `onStateChange(state)` para que o hook saiba se precisa ligar/desligar polling.
- **Page Visibility API** em `useMetrics`, `useTopology`, `useHealth`, `useEvents` e `useTunnelRuntime`: quando `document.hidden === true`, pausar intervalos. Quando a tab volta ao foco, resumir imediatamente.

---

### App Layer (enxuto)

#### [MODIFY] [App.tsx](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/App.tsx)

- Alterar o timestamp do header para usar um estado com `setInterval` ao invés de `new Date()` inline (que faz re-render a cada render sem motivo).

---

## Verification Plan

### Build
```bash
cd /home/lucas/Projects/ishin-gateway/ishin-gateway-ui && npx tsc -b && npx vite build
```

### Verificação Manual (Usuário)

O projeto não possui testes de frontend automatizados. A verificação precisa ser manual:

1. **Abrir o dashboard** no Chrome com DevTools → **Performance Monitor** (Ctrl+Shift+P → "Show Performance Monitor").
2. Observar o campo **JS Heap Size** e **DOM Nodes** por ~2 minutos.
3. **Desconectar a rede** (DevTools → Network → Offline) por 30s e reconectar — verificar que:
   - Apenas 1 reconexão acontece (sem duplicação exponencial).
   - O console não mostra múltiplos "reconnecting...".
4. **Trocar de aba** por 1 minuto e voltar — verificar que o JS Heap não cresceu significativamente.
5. Deixar o dashboard aberto por **30+ minutos** e confirmar que o Heap se mantém estável (sem curva crescente).

> [!IMPORTANT]
> Sem testes unitários de frontend no projeto. A verificação será via build + inspeção manual com DevTools do Chrome.
