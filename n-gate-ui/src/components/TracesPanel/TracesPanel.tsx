import { useState, useEffect } from 'react';
import { Search, ExternalLink, Clock, Layers } from 'lucide-react';
import { api } from '../../api';
import './TracesPanel.css';

interface Span {
  traceId: string;
  id: string;
  name: string;
  timestamp: number;
  duration: number;
  localEndpoint?: { serviceName: string };
  tags?: Record<string, string>;
}

interface Props {
  enabled: boolean;
}

export function TracesPanel({ enabled }: Props) {
  const [traces, setTraces] = useState<Span[][]>([]);
  const [loading, setLoading] = useState(false);
  const [searchService, setSearchService] = useState('');
  const [selectedTrace, setSelectedTrace] = useState<Span[] | null>(null);

  useEffect(() => {
    if (!enabled) return;
    fetchTraces();
  }, [enabled]);

  const fetchTraces = async () => {
    setLoading(true);
    try {
      const params: Record<string, string> = { limit: '20' };
      if (searchService) params.serviceName = searchService;
      const data = await api.getTraces(params);
      setTraces(data || []);
    } catch {
      setTraces([]);
    } finally {
      setLoading(false);
    }
  };

  if (!enabled) {
    return (
      <div className="traces-disabled">
        <Layers size={24} />
        <p>Integração Zipkin desabilitada</p>
        <span className="text-muted">
          Configure <code>dashboard.zipkin.enabled: true</code> no adapter.yaml
        </span>
      </div>
    );
  }

  return (
    <div className="traces-panel">
      <div className="traces-header">
        <div className="traces-title-group">
          <Layers size={14} />
          <h3>Traces</h3>
        </div>
        <div className="traces-search">
          <Search size={12} />
          <input
            type="text"
            placeholder="Filtrar por serviço..."
            value={searchService}
            onChange={(e) => setSearchService(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && fetchTraces()}
          />
        </div>
      </div>

      <div className="traces-body">
        {selectedTrace ? (
          <div className="trace-detail">
            <button className="trace-back" onClick={() => setSelectedTrace(null)}>
              ← Voltar
            </button>
            <div className="trace-spans">
              {selectedTrace.map((span) => (
                <div key={span.id} className="trace-span">
                  <div className="span-bar-container">
                    <div
                      className="span-bar"
                      style={{
                        width: `${Math.max(5, (span.duration / (selectedTrace[0]?.duration || 1)) * 100)}%`,
                      }}
                    />
                  </div>
                  <div className="span-info">
                    <span className="span-name">{span.name}</span>
                    <span className="span-service">
                      {span.localEndpoint?.serviceName || 'unknown'}
                    </span>
                    <span className="span-duration mono">
                      {formatDuration(span.duration)}
                    </span>
                  </div>
                  {span.tags && Object.keys(span.tags).length > 0 && (
                    <div className="span-tags">
                      {Object.entries(span.tags).slice(0, 5).map(([k, v]) => (
                        <span key={k} className="span-tag">
                          {k}: {v}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        ) : loading ? (
          <div className="traces-loading">
            <Clock size={16} className="subtle-pulse" />
            <span>Buscando traces...</span>
          </div>
        ) : traces.length === 0 ? (
          <div className="traces-empty">
            <span>Nenhum trace encontrado</span>
          </div>
        ) : (
          <div className="traces-list">
            {traces.map((trace, i) => {
              const rootSpan = trace[0];
              if (!rootSpan) return null;
              const totalDuration = Math.max(...trace.map((s) => s.duration));
              return (
                <div
                  key={i}
                  className="trace-row"
                  onClick={() => setSelectedTrace(trace)}
                >
                  <div className="trace-row-header">
                    <span className="trace-name">{rootSpan.name}</span>
                    <ExternalLink size={10} className="trace-expand" />
                  </div>
                  <div className="trace-row-meta">
                    <span className="trace-service">
                      {rootSpan.localEndpoint?.serviceName || 'unknown'}
                    </span>
                    <span className="trace-spans-count mono">
                      {trace.length} span{trace.length !== 1 ? 's' : ''}
                    </span>
                    <span className="trace-duration mono">
                      {formatDuration(totalDuration)}
                    </span>
                    <span className="trace-time text-muted">
                      {new Date(rootSpan.timestamp / 1000).toLocaleTimeString('pt-BR')}
                    </span>
                  </div>
                  <div className="trace-minibar">
                    <div
                      className="trace-minibar-fill"
                      style={{
                        width: `${Math.min(100, Math.max(5, totalDuration / 10))}%`,
                      }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

function formatDuration(micros: number): string {
  if (micros < 1000) return `${micros}µs`;
  if (micros < 1_000_000) return `${(micros / 1000).toFixed(1)}ms`;
  return `${(micros / 1_000_000).toFixed(2)}s`;
}
