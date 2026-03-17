# Waterfall View para Traces no Dashboard

O detalhe de traces no dashboard atualmente mostra spans em lista flat, sem hierarquia nem posicionamento temporal. Esta implementação adiciona um **waterfall chart** estilo Zipkin/Jaeger, com barras posicionadas por offset temporal e indentação por hierarquia parent/child.

## Proposed Changes

### TracesPanel — Refatoração + Melhorias visuais

#### [MODIFY] [TracesPanel.tsx](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/components/TracesPanel/TracesPanel.tsx)

- Extrair a renderização de detalhe do trace (bloco `selectedTrace`) para usar o novo componente `TraceWaterfall`
- Adicionar campo `parentId` na interface `Span` (o Zipkin retorna esse campo)
- Na listagem de traces, adicionar indicador visual de erro (quando qualquer span tiver tag `error`) e highlight de latência alta (> 1s)

#### [MODIFY] [TracesPanel.css](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/components/TracesPanel/TracesPanel.css)

- Adicionar estilos para indicador de erro na trace row
- Remover estilos antigos do detalhe flat que serão movidos para `TraceWaterfall.css`

---

### TraceWaterfall — Novo componente

#### [NEW] [TraceWaterfall.tsx](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/components/TracesPanel/TraceWaterfall.tsx)

Componente dedicado à visualização waterfall de um trace:

1. **Reconstrução de árvore**: Montar árvore de spans via `parentId`, detectar o root span (sem `parentId` ou com `parentId` ausente no conjunto)
2. **Cálculo de offset**: Para cada span, calcular `offsetFromRoot = span.timestamp - root.timestamp`
3. **Renderização waterfall**:
   - Layout de 2 colunas: **labels** (esquerda) | **barras temporais** (direita)
   - Coluna labels: nome do span + serviço, indentado pela profundidade na árvore
   - Coluna barras: barra horizontal posicionada por `left = (offset / totalDuration) * 100%`, com `width = (span.duration / totalDuration) * 100%`
   - Cores: barras com gradient normal para spans OK, vermelho para spans com erro
4. **Interatividade**: Ao clicar num span, expande mostrando as tags detalhadas
5. **Header**: Mostrar traceId, duração total, contagem de spans, timestamp do root

#### [NEW] [TraceWaterfall.css](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/components/TracesPanel/TraceWaterfall.css)

Estilos do waterfall usando os design tokens existentes:
- Grid 2 colunas com proporção fixa (~200px labels / auto barras)
- Linhas zebra com hover highlight
- Barras com `position: absolute` dentro de container relativo
- Indentação com `padding-left` proporcional à depth
- Micro-animação de fadeIn sequencial nas rows

## Verification Plan

### Verificação via Browser

1. Executar `cd /home/lucas/Projects/ishin-gateway/ishin-gateway-ui && npm run dev`
2. Abrir `http://localhost:3000` no browser
3. Configurar proxy do Vite para apontar para a instância do ishin-gateway com Zipkin habilitado
4. Navegar até a aba **Traces**
5. Verificar que a listagem mostra indicadores de erro (se houver traces com erro)
6. Clicar em um trace e verificar:
   - Waterfall chart renderiza com barras posicionadas temporalmente
   - Hierarquia de spans é visível via indentação
   - Clicar num span expande as tags
   - Botão "Voltar" retorna à listagem

### Build Check

- `cd /home/lucas/Projects/ishin-gateway/ishin-gateway-ui && npx tsc -b --noEmit` — verificar compilação TypeScript sem erros
