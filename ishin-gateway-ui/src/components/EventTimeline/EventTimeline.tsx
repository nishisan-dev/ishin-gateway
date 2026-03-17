import { AlertTriangle, CircleDot, Clock, Shield } from 'lucide-react';
import type { EventLog } from '../../types';
import './EventTimeline.css';

interface Props {
  events: EventLog[];
}

const EVENT_ICONS: Record<string, React.ReactNode> = {
  CIRCUIT_BREAKER: <Shield size={12} />,
  RATE_LIMIT: <AlertTriangle size={12} />,
  HEALTH_CHECK: <CircleDot size={12} />,
};

const EVENT_COLORS: Record<string, string> = {
  CIRCUIT_BREAKER: 'warning',
  RATE_LIMIT: 'error',
  HEALTH_CHECK: 'success',
};

export function EventTimeline({ events }: Props) {
  return (
    <div className="timeline-container">
      <div className="timeline-header">
        <Clock size={14} />
        <h3>Eventos</h3>
        <span className="timeline-count">{events.length}</span>
      </div>
      <div className="timeline-list">
        {events.length === 0 ? (
          <div className="timeline-empty">Nenhum evento recente</div>
        ) : (
          events.map((event, i) => (
            <div key={i} className="timeline-item" style={{ animationDelay: `${i * 50}ms` }}>
              <div className={`timeline-dot timeline-dot-${EVENT_COLORS[event.eventType] || 'accent'}`} />
              <div className="timeline-content">
                <div className="timeline-item-header">
                  <span className="timeline-icon">{EVENT_ICONS[event.eventType] || <CircleDot size={12} />}</span>
                  <span className="timeline-type">{event.eventType}</span>
                </div>
                <span className="timeline-source">{event.source}</span>
                <span className="timeline-time">
                  {formatTimeAgo(event.timestamp)}
                </span>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function formatTimeAgo(timestamp: string): string {
  const now = Date.now();
  const then = new Date(timestamp).getTime();
  const diff = Math.floor((now - then) / 1000);

  if (diff < 60) return `${diff}s atrás`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m atrás`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h atrás`;
  return `${Math.floor(diff / 86400)}d atrás`;
}
