import { useMemo } from 'react';
import { Activity, Gauge, AlertTriangle, Zap } from 'lucide-react';
import type { MetricData } from '../../types';
import './MetricsCards.css';

interface Props {
  metrics: Record<string, MetricData>;
}

interface CardData {
  label: string;
  value: string;
  unit: string;
  icon: React.ReactNode;
  trend?: 'up' | 'down' | 'stable';
  color: 'accent' | 'success' | 'warning' | 'error';
}

export function MetricsCards({ metrics }: Props) {
  const cards = useMemo<CardData[]>(() => {
    // Total inbound requests
    const totalRequests = findMetricValue(metrics, 'ngate.requests.total', 'count') ?? 0;
    
    // Mean latency
    const meanLatency = findMetricValue(metrics, 'ngate.request.duration', 'mean') ?? 0;
    
    // Max latency
    const maxLatency = findMetricValue(metrics, 'ngate.request.duration', 'max') ?? 0;
    
    // Error count (status >= 500)
    const errorCount = findMetricValue(metrics, 'ngate.requests.total', 'count', { status: '5' }) ?? 0;
    
    // Rate limit rejections
    const rateLimitRejects = findMetricValue(metrics, 'ngate.ratelimit.total', 'count', { result: 'REJECTED' }) ?? 0;

    // Active threads
    const activeThreads = findMetricValue(metrics, 'jvm.threads.live', 'value') ?? 0;

    return [
      {
        label: 'Requests',
        value: formatNumber(totalRequests),
        unit: 'total',
        icon: <Activity size={16} />,
        color: 'accent' as const,
      },
      {
        label: 'Latência Média',
        value: meanLatency < 1 ? '<1' : formatNumber(meanLatency),
        unit: 'ms',
        icon: <Gauge size={16} />,
        color: meanLatency > 500 ? 'warning' as const : 'success' as const,
      },
      {
        label: 'Latência Max',
        value: formatNumber(maxLatency),
        unit: 'ms',
        icon: <Zap size={16} />,
        color: maxLatency > 2000 ? 'error' as const : 'accent' as const,
      },
      {
        label: 'Erros 5xx',
        value: formatNumber(errorCount),
        unit: 'total',
        icon: <AlertTriangle size={16} />,
        color: errorCount > 0 ? 'error' as const : 'success' as const,
      },
      {
        label: 'Rate Limited',
        value: formatNumber(rateLimitRejects),
        unit: 'rejected',
        icon: <AlertTriangle size={16} />,
        color: rateLimitRejects > 0 ? 'warning' as const : 'success' as const,
      },
      {
        label: 'Threads',
        value: formatNumber(activeThreads),
        unit: 'ativos',
        icon: <Activity size={16} />,
        color: 'accent' as const,
      },
    ];
  }, [metrics]);

  return (
    <div className="metrics-cards">
      {cards.map((card) => (
        <div key={card.label} className={`metric-card metric-card-${card.color}`}>
          <div className="metric-card-header">
            <span className="metric-card-icon">{card.icon}</span>
            <span className="metric-card-label">{card.label}</span>
          </div>
          <div className="metric-card-body">
            <span className="metric-card-value">{card.value}</span>
            <span className="metric-card-unit">{card.unit}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Helpers ────────────────────────────────────────────────────

function findMetricValue(
  metrics: Record<string, MetricData>,
  prefix: string,
  field: 'value' | 'count' | 'mean' | 'max',
  tagFilter?: Record<string, string>
): number | undefined {
  for (const [key, data] of Object.entries(metrics)) {
    if (!key.startsWith(prefix)) continue;
    if (tagFilter && data.tags) {
      const matches = Object.entries(tagFilter).every(
        ([k, v]) => data.tags?.[k]?.startsWith(v)
      );
      if (!matches) continue;
    }
    if (field === 'value' && data.value !== undefined) return data.value;
    if (field === 'count' && data.count !== undefined) return data.count;
    if (field === 'mean' && data.mean !== undefined) return data.mean;
    if (field === 'max' && data.max !== undefined) return data.max;
  }
  return undefined;
}

function formatNumber(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return Math.round(n).toLocaleString();
}
