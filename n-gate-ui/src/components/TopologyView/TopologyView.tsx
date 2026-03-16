import { useMemo, useCallback } from 'react';
import {
  ReactFlow,
  Background,
  type Node,
  type Edge,
  Position,
  MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Globe, Server, Database, Shield } from 'lucide-react';
import type { TopologyData } from '../../types';
import './TopologyView.css';

interface Props {
  data: TopologyData | null;
  loading: boolean;
}

export function TopologyView({ data, loading }: Props) {

  const { nodes, edges } = useMemo(() => {
    if (!data) return { nodes: [] as Node[], edges: [] as Edge[] };

    const flowNodes: Node[] = [];
    const flowEdges: Edge[] = [];

    // Separar nós por tipo para posicionamento
    const listeners = data.nodes.filter(n => n.type === 'listener');
    const gateways = data.nodes.filter(n => n.type === 'gateway');
    const backends = data.nodes.filter(n => n.type === 'backend');

    // Posicionar listeners à esquerda
    listeners.forEach((node, i) => {
      flowNodes.push({
        id: node.id,
        position: { x: 50, y: 80 + i * 120 },
        type: 'default',
        data: {
          label: (
            <div className="topo-node topo-listener">
              <Globe size={14} className="topo-node-icon" />
              <div className="topo-node-content">
                <span className="topo-node-label">{node.label}</span>
                <span className="topo-node-meta">
                  :{node.port} {node.ssl ? '🔒' : ''} {node.secured ? '🛡' : ''}
                </span>
              </div>
            </div>
          ),
        },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
      });
    });

    // Posicionar gateway no centro
    gateways.forEach((node) => {
      flowNodes.push({
        id: node.id,
        position: { x: 350, y: 80 + (Math.max(listeners.length, backends.length) - 1) * 60 },
        type: 'default',
        data: {
          label: (
            <div className="topo-node topo-gateway">
              <Shield size={18} className="topo-node-icon" />
              <div className="topo-node-content">
                <span className="topo-node-label">{node.label}</span>
                <span className="topo-node-meta">{node.mode}</span>
              </div>
            </div>
          ),
        },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
      });
    });

    // Posicionar backends à direita
    backends.forEach((node, i) => {
      flowNodes.push({
        id: node.id,
        position: { x: 650, y: 80 + i * 120 },
        type: 'default',
        data: {
          label: (
            <div className="topo-node topo-backend">
              {node.hasOauth ? <Server size={14} className="topo-node-icon" /> : <Database size={14} className="topo-node-icon" />}
              <div className="topo-node-content">
                <span className="topo-node-label">{node.label}</span>
                <span className="topo-node-meta">
                  {node.members} member{node.members !== 1 ? 's' : ''}
                  {node.hasOauth ? ' • OAuth' : ''}
                </span>
              </div>
            </div>
          ),
        },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
      });
    });

    // Edges
    data.edges.forEach((edge, i) => {
      flowEdges.push({
        id: `edge-${i}`,
        source: edge.source,
        target: edge.target,
        animated: true,
        style: {
          stroke: edge.type === 'inbound' ? '#748FFC' : '#A5D8FF',
          strokeWidth: 2,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: edge.type === 'inbound' ? '#748FFC' : '#A5D8FF',
          width: 16,
          height: 16,
        },
      });
    });

    return { nodes: flowNodes, edges: flowEdges };
  }, [data]);

  const onInit = useCallback(() => {
    // React Flow initialized
  }, []);

  if (loading) {
    return (
      <div className="topology-loading">
        <div className="topology-spinner" />
        <span>Carregando topologia...</span>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="topology-empty">
        <span>Sem dados de topologia</span>
      </div>
    );
  }

  return (
    <div className="topology-container">
      <div className="topology-header">
        <h3>Topologia</h3>
        <div className="topology-badges">
          {data.circuitBreaker?.enabled && <span className="badge badge-accent">CB</span>}
          {data.rateLimiting?.enabled && <span className="badge badge-warning">RL</span>}
          {data.cluster?.enabled && <span className="badge badge-success">Cluster: {data.cluster.clusterName}</span>}
        </div>
      </div>
      <div className="topology-flow">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onInit={onInit}
          fitView
          panOnDrag
          zoomOnScroll
          minZoom={0.5}
          maxZoom={2}
          proOptions={{ hideAttribution: true }}
        >
          <Background color="#2C2E33" gap={20} />
        </ReactFlow>
      </div>
    </div>
  );
}
