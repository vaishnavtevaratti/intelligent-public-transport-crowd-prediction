import { useState } from 'react';
import { History, RefreshCw } from 'lucide-react';
import { CrowdBadge, EmptyState, ErrorState, Pagination, SkeletonRows } from './ui/ui';
import { useApi } from '../hooks/useApi';
import { crowdService } from '../services/crowdService';
import { formatDateTime, routeLabel } from '../utils/format';

const SOURCE_LABEL = { ON_DEMAND: 'On demand', SCHEDULED: 'Scheduled' };

/** Paged, filterable list of stored crowd predictions (GET /api/crowd/history). */
export default function PredictionTable({ routes = [], buses = [], refreshKey = 0, pageSize = 10 }) {
  const [filters, setFilters] = useState({ routeId: '', busId: '', level: '', source: '' });
  const [page, setPage] = useState(0);

  const { data, loading, error, reload } = useApi(() => {
    const params = { page, size: pageSize };
    Object.entries(filters).forEach(([k, v]) => { if (v) params[k] = v; });
    return crowdService.getHistory(params);
  }, [page, filters, refreshKey]);

  const setFilter = (key) => (e) => {
    setPage(0);
    setFilters((f) => ({ ...f, [key]: e.target.value }));
  };

  return (
    <>
      <div className="card-body toolbar" style={{ paddingBottom: 12 }}>
        <select className="select" value={filters.routeId} onChange={setFilter('routeId')} aria-label="Filter by route">
          <option value="">All routes</option>
          {routes.map((r) => <option key={r.id} value={r.id}>{routeLabel(r)}</option>)}
        </select>
        {buses.length > 0 && (
          <select className="select" value={filters.busId} onChange={setFilter('busId')} aria-label="Filter by bus">
            <option value="">All buses</option>
            {buses.map((b) => <option key={b.id} value={b.id}>{b.busNumber}</option>)}
          </select>
        )}
        <select className="select" value={filters.level} onChange={setFilter('level')} aria-label="Filter by crowd level">
          <option value="">All levels</option>
          <option value="LOW">Low</option><option value="MEDIUM">Medium</option><option value="HIGH">High</option>
        </select>
        <select className="select" value={filters.source} onChange={setFilter('source')} aria-label="Filter by source">
          <option value="">All sources</option>
          <option value="ON_DEMAND">On demand</option><option value="SCHEDULED">Scheduled</option>
        </select>
        <button className="btn btn-secondary btn-sm" onClick={() => reload()}><RefreshCw size={14} /> Refresh</button>
      </div>
      {error ? <ErrorState message={error} onRetry={reload} /> : loading && !data ? (
        <div className="card-body"><SkeletonRows rows={5} /></div>
      ) : data.content.length === 0 ? (
        <EmptyState icon={History} title="No predictions found" message="Try different filters, or make a prediction above." />
      ) : (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr><th>Made at</th><th>Travel time</th><th>Bus</th><th>Route</th><th>Crowd level</th><th>Source</th><th>Model</th></tr>
              </thead>
              <tbody>
                {data.content.map((p) => (
                  <tr key={p.id}>
                    <td className="nowrap">{formatDateTime(p.timestamp)}</td>
                    <td className="nowrap">{formatDateTime(p.predictedFor || p.timestamp)}</td>
                    <td className="nowrap"><strong>{p.busNumber}</strong></td>
                    <td>{routeLabel(p)}</td>
                    <td><CrowdBadge level={p.crowdLevel} confidence={p.confidence} /></td>
                    <td>{p.source ? SOURCE_LABEL[p.source] : <span className="muted" title="Created before the ML model was integrated">Legacy</span>}</td>
                    <td className="mono muted">{p.modelVersion || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} label="predictions" />
        </>
      )}
    </>
  );
}
