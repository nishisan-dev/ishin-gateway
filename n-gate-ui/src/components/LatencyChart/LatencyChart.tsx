import { useState, useEffect, useMemo } from 'react';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { TrendingUp, Clock } from 'lucide-react';
import { api } from '../../api';
import './LatencyChart.css';

interface ChartDataPoint {
  time: string;
  timestamp: number;
  value: number;
}

interface Props {
  metricName?: string;
  title?: string;
  hours?: number;
}

const TIME_RANGES = [
  { label: '1h', hours: 1 },
  { label: '6h', hours: 6 },
  { label: '24h', hours: 24 },
];

export function LatencyChart({
  metricName = 'ngate.request.duration',
  title = 'Latência',
  hours: defaultHours = 1,
}: Props) {
  const [data, setData] = useState<ChartDataPoint[]>([]);
  const [selectedRange, setSelectedRange] = useState(defaultHours);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchHistory = async () => {
      setLoading(true);
      try {
        const now = new Date();
        const from = new Date(now.getTime() - selectedRange * 3600 * 1000);
        const records = await api.getMetricHistory(
          metricName,
          from.toISOString(),
          now.toISOString()
        );

        const chartData: ChartDataPoint[] = (records || []).map(
          (r: { timestamp: string; value: number }) => ({
            time: new Date(r.timestamp).toLocaleTimeString('pt-BR', {
              hour: '2-digit',
              minute: '2-digit',
            }),
            timestamp: new Date(r.timestamp).getTime(),
            value: Math.round(r.value * 100) / 100,
          })
        );

        setData(chartData);
      } catch {
        // Sem dados — fallback
        setData([]);
      } finally {
        setLoading(false);
      }
    };

    fetchHistory();
    const id = setInterval(fetchHistory, 30000);
    return () => clearInterval(id);
  }, [metricName, selectedRange]);

  const stats = useMemo(() => {
    if (data.length === 0) return { avg: 0, max: 0, min: 0, current: 0 };
    const values = data.map((d) => d.value);
    return {
      avg: Math.round(values.reduce((a, b) => a + b, 0) / values.length),
      max: Math.round(Math.max(...values)),
      min: Math.round(Math.min(...values)),
      current: Math.round(values[values.length - 1]),
    };
  }, [data]);

  return (
    <div className="latency-chart">
      <div className="chart-header">
        <div className="chart-title-group">
          <TrendingUp size={14} />
          <h3>{title}</h3>
        </div>
        <div className="chart-controls">
          <div className="chart-stats">
            <span className="chart-stat">
              avg: <strong>{stats.avg}ms</strong>
            </span>
            <span className="chart-stat">
              max: <strong className="text-warning">{stats.max}ms</strong>
            </span>
            <span className="chart-stat">
              cur: <strong className="text-accent">{stats.current}ms</strong>
            </span>
          </div>
          <div className="time-range-selector">
            {TIME_RANGES.map((range) => (
              <button
                key={range.hours}
                className={`time-range-btn ${selectedRange === range.hours ? 'active' : ''}`}
                onClick={() => setSelectedRange(range.hours)}
              >
                {range.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="chart-body">
        {loading && data.length === 0 ? (
          <div className="chart-loading">
            <Clock size={16} className="subtle-pulse" />
            <span>Carregando dados...</span>
          </div>
        ) : data.length === 0 ? (
          <div className="chart-empty">
            <span>Sem dados históricos para "{metricName}"</span>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={200}>
            <AreaChart data={data} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
              <defs>
                <linearGradient id="latencyGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#748FFC" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="#748FFC" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="rgba(255,255,255,0.04)"
                vertical={false}
              />
              <XAxis
                dataKey="time"
                stroke="#5C5F66"
                fontSize={10}
                fontFamily="var(--font-mono)"
                tickLine={false}
                axisLine={false}
              />
              <YAxis
                stroke="#5C5F66"
                fontSize={10}
                fontFamily="var(--font-mono)"
                tickLine={false}
                axisLine={false}
                tickFormatter={(v) => `${v}ms`}
              />
              <Tooltip
                contentStyle={{
                  background: '#25262B',
                  border: '1px solid rgba(255,255,255,0.1)',
                  borderRadius: '8px',
                  fontSize: '12px',
                  fontFamily: 'var(--font-mono)',
                  color: '#C1C2C5',
                }}
                labelStyle={{ color: '#909296' }}
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                formatter={(value: any) => [`${value}ms`, 'Latência']}
              />
              <Area
                type="monotone"
                dataKey="value"
                stroke="#748FFC"
                strokeWidth={2}
                fill="url(#latencyGradient)"
                dot={false}
                activeDot={{
                  r: 4,
                  fill: '#748FFC',
                  stroke: '#1A1B1E',
                  strokeWidth: 2,
                }}
              />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
