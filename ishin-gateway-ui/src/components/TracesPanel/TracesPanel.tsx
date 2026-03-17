import { useState, useEffect } from 'react';
import { Search, Clock, Layers, AlertTriangle } from 'lucide-react';
import { TraceWaterfall } from './TraceWaterfall';
import { api } from '../../api';
import './TracesPanel.css';

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

interface Props {
  enabled: boolean;
}

export function TracesPanel({ enabled }: Props) {
  const [traces, setTraces] = useState<Span[][]>([]);
  const [loading, setLoading] = useState(false);
  const [searchService, setSearchService] = useState('');
  const [selectedTrace, setSelectedTrace] = useState<Span[] | null>(null);
  const [zipkinAvailable, setZipkinAvailable] = useState(enabled);
  const [errorMessage, setErrorMessage] = useState('');

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

      if (Array.isArray(data)) {
        setZipkinAvailable(true);
        setErrorMessage('');
        setTraces(data);
        return;
      }

      const message = extractErrorMessage(data);
      setZipkinAvailable(false);
      setErrorMessage(message);
      setSelectedTrace(null);
      setTraces([]);
    } catch {
      setZipkinAvailable(false);
      setErrorMessage('Falha ao buscar traces');
      setTraces([]);
    } finally {
      setLoading(false);
    }
  };

  if (!enabled || !zipkinAvailable) {
    return (
      <div className="traces-disabled">
        <Layers size={24} />
        <p>{!enabled ? 'Integração Zipkin desabilitada' : 'Traces indisponíveis'}</p>
        <span className="text-muted">
          {errorMessage || (
            <>
              Configure <code>dashboard.zipkin.enabled: true</code> no adapter.yaml
            </>
          )}
        </span>
      </div>
    );
  }

  return (
    <div className="traces-panel">
      {selectedTrace ? (
        <TraceWaterfall
          spans={selectedTrace}
          onBack={() => setSelectedTrace(null)}
        />
      ) : (
        <>
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
            {loading ? (
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
                  const hasError = trace.some(s =>
                    s.tags?.error || s.tags?.['error'] === 'true' || s.tags?.['otel.status_code'] === 'ERROR'
                  );
                  const isSlowMs = totalDuration / 1000; // micros → ms
                  const isSlow = isSlowMs > 1000; // > 1s

                  return (
                    <div
                      key={i}
                      className={`trace-row ${hasError ? 'trace-row-error' : ''}`}
                      onClick={() => setSelectedTrace(trace)}
                    >
                      <div className="trace-row-header">
                        <div className="trace-name-group">
                          <span className="trace-name">{rootSpan.name}</span>
                          {hasError && (
                            <span className="trace-error-indicator">
                              <AlertTriangle size={10} />
                            </span>
                          )}
                          {isSlow && (
                            <span className="trace-slow-badge mono">SLOW</span>
                          )}
                        </div>
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
                      <div className={`trace-minibar ${hasError ? 'trace-minibar-error' : ''}`}>
                        <div
                          className={`trace-minibar-fill ${hasError ? 'trace-minibar-fill-error' : ''}`}
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
        </>
      )}
    </div>
  );
}

function formatDuration(micros: number): string {
  if (micros < 1000) return `${micros}µs`;
  if (micros < 1_000_000) return `${(micros / 1000).toFixed(1)}ms`;
  return `${(micros / 1_000_000).toFixed(2)}s`;
}

function extractErrorMessage(value: unknown): string {
  if (value && typeof value === 'object' && 'error' in value) {
    const error = value.error;
    if (typeof error === 'string' && error.trim().length > 0) {
      return error;
    }
  }

  return 'Resposta inesperada da API de traces';
}
