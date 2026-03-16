import { ReactFlowProvider } from '@xyflow/react';
import { MetricsCards } from './components/MetricsCards/MetricsCards';
import { TopologyView } from './components/TopologyView/TopologyView';
import { EventTimeline } from './components/EventTimeline/EventTimeline';
import { useMetrics, useTopology, useHealth, useEvents } from './hooks/useDashboard';
import { Activity, Server, Shield } from 'lucide-react';
import './App.css';

function App() {
  const metrics = useMetrics();
  const { topology, loading: topoLoading } = useTopology();
  const health = useHealth();
  const events = useEvents();

  return (
    <div className="dashboard">
      {/* ─── Sidebar ──────────────────────────────────────── */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <Shield size={20} className="brand-icon" />
          <div className="brand-text">
            <span className="brand-name">n-gate</span>
            <span className="brand-sub">observability</span>
          </div>
        </div>

        <div className="sidebar-status">
          <div className="status-item">
            <span className={`status-dot ${health?.status === 'UP' ? 'status-dot-success' : 'status-dot-error'}`} />
            <span className="status-label">{health?.status ?? '...'}</span>
          </div>
          <div className="status-item">
            <Server size={12} />
            <span className="status-label">{health?.mode ?? '...'}</span>
          </div>
          <div className="status-item">
            <Activity size={12} />
            <span className="status-label">{health?.listeners ?? 0} listeners</span>
          </div>
        </div>

        <EventTimeline events={events} />
      </aside>

      {/* ─── Main Content ─────────────────────────────────── */}
      <main className="main">
        <header className="main-header">
          <h1>Dashboard</h1>
          <span className="header-timestamp mono">
            {new Date().toLocaleTimeString('pt-BR')}
          </span>
        </header>

        <section className="main-metrics">
          <MetricsCards metrics={metrics} />
        </section>

        <section className="main-topology">
          <ReactFlowProvider>
            <TopologyView data={topology} loading={topoLoading} />
          </ReactFlowProvider>
        </section>
      </main>
    </div>
  );
}

export default App;
