import { useState, useMemo } from 'react';
import { ArrowLeft, AlertTriangle, ChevronDown, ChevronRight, Copy, Check } from 'lucide-react';
import './TraceWaterfall.css';

interface Span {
  traceId: string;
  id: string;
  parentId?: string;
  name: string;
  timestamp: number;
  duration: number;
  localEndpoint?: { serviceName: string };
  tags?: Record<string, string>;
}

interface SpanNode {
  span: Span;
  children: SpanNode[];
  depth: number;
}

interface Props {
  spans: Span[];
  onBack: () => void;
}

// ─── Color palette for services ─────────────────────────────────
const SERVICE_COLORS = [
  '#748FFC', // indigo
  '#A5D8FF', // sky
  '#51CF66', // green
  '#FCC419', // yellow
  '#F783AC', // pink
  '#B197FC', // violet
  '#63E6BE', // teal
  '#FF922B', // orange
];

function getServiceColor(serviceName: string, serviceMap: Map<string, number>): string {
  if (!serviceMap.has(serviceName)) {
    serviceMap.set(serviceName, serviceMap.size);
  }
  return SERVICE_COLORS[serviceMap.get(serviceName)! % SERVICE_COLORS.length];
}

// ─── Tree builder ───────────────────────────────────────────────

function buildSpanTree(spans: Span[]): SpanNode[] {
  const byId = new Map<string, SpanNode>();
  const roots: SpanNode[] = [];

  // Create nodes
  spans.forEach(span => {
    byId.set(span.id, { span, children: [], depth: 0 });
  });

  // Link children
  spans.forEach(span => {
    const node = byId.get(span.id)!;
    if (span.parentId && byId.has(span.parentId)) {
      byId.get(span.parentId)!.children.push(node);
    } else {
      roots.push(node);
    }
  });

  // Sort roots by timestamp (earliest first)
  roots.sort((a, b) => a.span.timestamp - b.span.timestamp);

  // Assign depth and sort children
  function assignDepth(node: SpanNode, depth: number) {
    node.depth = depth;
    node.children.sort((a, b) => a.span.timestamp - b.span.timestamp);
    node.children.forEach(child => assignDepth(child, depth + 1));
  }
  roots.forEach(root => assignDepth(root, 0));

  return roots;
}

function flattenTree(nodes: SpanNode[]): SpanNode[] {
  const result: SpanNode[] = [];
  function walk(node: SpanNode) {
    result.push(node);
    node.children.forEach(walk);
  }
  nodes.forEach(walk);
  return result;
}

// ─── Component ──────────────────────────────────────────────────

export function TraceWaterfall({ spans, onBack }: Props) {
  const [expandedSpan, setExpandedSpan] = useState<string | null>(null);
  const [copiedTraceId, setCopiedTraceId] = useState(false);

  const { flatSpans, traceStart, traceDuration, rootSpan, serviceMap, hasErrors } = useMemo(() => {
    const spanTree = buildSpanTree(spans);
    const flatSpans = flattenTree(spanTree);
    const rootSpan = flatSpans[0]?.span ?? spans[0];

    const traceStart = Math.min(...spans.map(s => s.timestamp));
    const traceEnd = Math.max(...spans.map(s => s.timestamp + s.duration));
    const traceDuration = traceEnd - traceStart;

    const serviceMap = new Map<string, number>();
    spans.forEach(s => {
      const name = s.localEndpoint?.serviceName || 'unknown';
      getServiceColor(name, serviceMap);
    });

    const hasErrors = spans.some(s => s.tags?.error || s.tags?.['error'] === 'true' || s.tags?.['otel.status_code'] === 'ERROR');

    return { flatSpans, traceStart, traceDuration, rootSpan, serviceMap, hasErrors };
  }, [spans]);

  const copyTraceId = () => {
    navigator.clipboard.writeText(rootSpan.traceId);
    setCopiedTraceId(true);
    setTimeout(() => setCopiedTraceId(false), 2000);
  };

  const toggleSpan = (spanId: string) => {
    setExpandedSpan(prev => prev === spanId ? null : spanId);
  };

  return (
    <div className="waterfall">
      {/* ─── Header ────────────────────────────────────────── */}
      <div className="waterfall-header">
        <button className="waterfall-back" onClick={onBack}>
          <ArrowLeft size={14} />
          Voltar
        </button>

        <div className="waterfall-summary">
          <div className="waterfall-title">
            <span className="waterfall-root-name">{rootSpan.name}</span>
            {hasErrors && (
              <span className="waterfall-error-badge">
                <AlertTriangle size={11} />
                ERRO
              </span>
            )}
          </div>
          <div className="waterfall-meta">
            <button className="waterfall-trace-id mono" onClick={copyTraceId} title="Copiar Trace ID">
              {copiedTraceId ? <Check size={10} /> : <Copy size={10} />}
              {rootSpan.traceId.substring(0, 16)}…
            </button>
            <span className="waterfall-stat">
              <strong>{spans.length}</strong> spans
            </span>
            <span className="waterfall-stat">
              <strong>{formatDuration(traceDuration)}</strong>
            </span>
            <span className="waterfall-stat text-muted">
              {new Date(rootSpan.timestamp / 1000).toLocaleString('pt-BR')}
            </span>
          </div>
        </div>
      </div>

      {/* ─── Timeline ruler ────────────────────────────────── */}
      <div className="waterfall-ruler">
        <div className="waterfall-ruler-label-col" />
        <div className="waterfall-ruler-bar-col">
          {[0, 25, 50, 75, 100].map(pct => (
            <span key={pct} className="ruler-mark mono" style={{ left: `${pct}%` }}>
              {formatDuration(traceDuration * pct / 100)}
            </span>
          ))}
        </div>
      </div>

      {/* ─── Span rows ────────────────────────────────────── */}
      <div className="waterfall-body">
        {flatSpans.map((node, i) => {
          const { span, depth } = node;
          const offsetPct = traceDuration > 0
            ? ((span.timestamp - traceStart) / traceDuration) * 100
            : 0;
          const widthPct = traceDuration > 0
            ? Math.max(0.5, (span.duration / traceDuration) * 100)
            : 100;
          const serviceName = span.localEndpoint?.serviceName || 'unknown';
          const color = getServiceColor(serviceName, serviceMap);
          const isError = !!(span.tags?.error || span.tags?.['error'] === 'true' || span.tags?.['otel.status_code'] === 'ERROR');
          const isExpanded = expandedSpan === span.id;
          const hasTags = span.tags && Object.keys(span.tags).length > 0;

          return (
            <div
              key={span.id}
              className={`waterfall-row ${isError ? 'waterfall-row-error' : ''} ${isExpanded ? 'waterfall-row-expanded' : ''}`}
              style={{ animationDelay: `${i * 20}ms` }}
            >
              {/* Label column */}
              <div
                className="waterfall-label-col"
                style={{ paddingLeft: `${12 + depth * 16}px` }}
                onClick={() => hasTags && toggleSpan(span.id)}
              >
                {hasTags && (
                  <span className="waterfall-expand-icon">
                    {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                  </span>
                )}
                <span
                  className="waterfall-service-dot"
                  style={{ backgroundColor: color }}
                />
                <span className="waterfall-span-name">{span.name}</span>
                <span className="waterfall-span-service" style={{ color }}>
                  {serviceName}
                </span>
              </div>

              {/* Bar column */}
              <div className="waterfall-bar-col">
                <div
                  className={`waterfall-bar ${isError ? 'waterfall-bar-error' : ''}`}
                  style={{
                    left: `${offsetPct}%`,
                    width: `${widthPct}%`,
                    backgroundColor: isError ? undefined : color,
                  }}
                >
                  {widthPct > 8 && (
                    <span className="waterfall-bar-label mono">
                      {formatDuration(span.duration)}
                    </span>
                  )}
                </div>
                {widthPct <= 8 && (
                  <span
                    className="waterfall-bar-label-outside mono"
                    style={{ left: `${offsetPct + widthPct + 0.5}%` }}
                  >
                    {formatDuration(span.duration)}
                  </span>
                )}
              </div>

              {/* Expanded tags */}
              {isExpanded && span.tags && (
                <div className="waterfall-tags">
                  {Object.entries(span.tags).map(([k, v]) => (
                    <div key={k} className="waterfall-tag-row">
                      <span className="waterfall-tag-key mono">{k}</span>
                      <span className="waterfall-tag-value mono">{v}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function formatDuration(micros: number): string {
  if (micros < 1000) return `${Math.round(micros)}µs`;
  if (micros < 1_000_000) return `${(micros / 1000).toFixed(1)}ms`;
  return `${(micros / 1_000_000).toFixed(2)}s`;
}
