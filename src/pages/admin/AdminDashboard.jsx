import { Link } from 'react-router-dom';
import { Activity, AlertTriangle, Brain, Bus, Camera, Database, MapPinned, RefreshCw, Route as RouteIcon, Users } from 'lucide-react';
import { Alert, Card, EmptyState, ErrorState, PageHeader, SkeletonRows, StatCard } from '../../components/ui/ui';
import { CrowdDistribution, CrowdLegend, HourlyStackedChart } from '../../components/charts/charts';
import { useApi } from '../../hooks/useApi';
import { useOccupancy } from '../../hooks/useOccupancy';
import { DataLabel } from '../../components/OccupancyPanel';
import { adminService } from '../../services/adminService';
import { formatDateTime, percent } from '../../utils/format';

export default function AdminDashboard() {
  const { data, loading, error, reload } = useApi(() => adminService.getAnalytics());
  const sensorsApi = useApi(() => adminService.getSensors());
  const modelsApi = useApi(() => adminService.getModels());
  const perfApi = useApi(() => adminService.getForecastPerformance());
  const { byBus } = useOccupancy();
  const occ = Object.values(byBus);
  const live = occ.filter((o) => o.dataStatus === 'LIVE');
  const onboardTotal = live.reduce((s, o) => s + o.estimatedOnboard, 0);
  const capacityTotal = live.reduce((s, o) => s + (o.capacity || 0), 0);
  const seatsKnown = live.filter((o) => o.availableSeats != null);
  const crowdAlerts = live.filter((o) => o.crowdCategory === 'FULL' || o.crowdCategory === 'HIGH' || o.overCapacity);
  const flagged = occ.filter((o) => o.needsReconciliation);
  const sensors = sensorsApi.data || [];
  const sensorCounts = {
    online: sensors.filter((s) => s.enabled && s.online && s.lastHealth === 'OK').length,
    degraded: sensors.filter((s) => s.enabled && s.online && s.lastHealth !== 'OK').length,
    offline: sensors.filter((s) => s.enabled && !s.online).length,
  };

  if (error) return <div className="card"><ErrorState message={error} onRetry={reload} /></div>;

  const model = data?.model;
  return (
    <>
      <PageHeader
        title="Admin overview"
        description="System-wide figures computed from the database · Kolhapur academic demo network."
        actions={<button className="btn btn-secondary btn-sm" onClick={() => reload()}><RefreshCw size={14} /> Refresh</button>}
      />

      <div className="grid grid-4">
        {loading || !data ? Array.from({ length: 4 }, (_, i) => <div key={i} className="card stat-card"><SkeletonRows rows={2} /></div>) : (
          <>
            <StatCard icon={Users} tone="admin" label="Users" value={data.totalUsers} hint={`${data.adminUsers} administrator(s)`} />
            <StatCard icon={Bus} tone="admin" label="Buses" value={data.totalBuses}
              hint={`${data.busesByStatus.ACTIVE} active · ${data.busesByStatus.MAINTENANCE} maintenance · ${data.busesByStatus.INACTIVE} inactive`} />
            <StatCard icon={RouteIcon} tone="admin" label="Routes" value={data.totalRoutes} />
            <StatCard icon={Database} tone="admin" label="Stored predictions" value={data.totalPredictions.toLocaleString()}
              hint={`${data.locationRecords.toLocaleString()} location records (last 24 h kept)`} />
          </>
        )}
      </div>

      <div className="grid grid-main-side">
        <Card title="Live passenger occupancy" subtitle="From cameras/sensors and manual counts — estimates, not exact counts"
          actions={<Link className="btn btn-secondary btn-sm" to="/admin/occupancy"><Camera size={14} /> Sensors &amp; reconciliation</Link>}>
          <div className="stack">
            <div className="grid grid-3" style={{ gap: 10 }}>
              <div className="metric"><div className="label">Buses with live data</div><div className="value">{live.length} / {occ.length}</div></div>
              <div className="metric"><div className="label">Est. passengers onboard</div><div className="value">{live.length ? onboardTotal : '—'}</div></div>
              <div className="metric"><div className="label">Capacity utilisation</div><div className="value">{capacityTotal ? `${Math.round(onboardTotal * 100 / capacityTotal)}%` : '—'}</div></div>
              <div className="metric"><div className="label">Seats available (where measured)</div>
                <div className="value">{seatsKnown.length ? seatsKnown.reduce((s, o) => s + o.availableSeats, 0) : '—'}</div>
                <div className="muted small">{seatsKnown.length} bus(es) with seat data</div></div>
              <div className="metric"><div className="label">Need reconciliation</div><div className="value">{flagged.length}</div></div>
              <div className="metric"><div className="label">Sensors</div><div className="value small" style={{ fontSize: '0.9rem' }}>
                {sensorCounts.online} ok · {sensorCounts.degraded} degraded · {sensorCounts.offline} offline</div></div>
            </div>
            {crowdAlerts.length > 0 ? (
              <div className="alert alert-error"><AlertTriangle size={15} />
                <div>Crowd alerts: {crowdAlerts.map((o) => `${o.busNumber} ${o.crowdCategory}${o.overCapacity ? ' (over capacity)' : ''} ~${o.estimatedOnboard}/${o.capacity}`).join(' · ')}</div></div>
            ) : <p className="muted small">No live high/full crowd alerts.</p>}
            {live.some((o) => o.dataMode === 'TEST_VIDEO') && (
              <p className="small"><DataLabel kind="test" /> Some live counts come from a recorded test video, not a camera installed in a bus.</p>
            )}
            {live.some((o) => o.dataMode === 'WEBCAM_DEMO') && (
              <p className="small"><DataLabel kind="webcam" /> Some live counts come from a laptop webcam demonstration, not a camera installed in a bus.</p>
            )}
          </div>
        </Card>
        <Card title="ML models in use" subtitle="Evaluation on held-out test days (synthetic data)">
          {modelsApi.loading ? <SkeletonRows rows={3} /> : modelsApi.error ? <ErrorState message={modelsApi.error} onRetry={modelsApi.reload} /> : (
            <div className="stack">
              {(modelsApi.data || []).slice(0, 2).map((m) => {
                const t = m.metrics?.test || {};
                const b = m.metrics?.baseline_test || {};
                return (
                  <div key={m.modelVersion} className="stack-sm" style={{ borderBottom: '1px solid var(--border)', paddingBottom: 8 }}>
                    <div className="row wrap"><strong>{m.modelKind === 'LIVE_FORECAST' ? `Live ${m.horizonMinutes}-min forecast` : 'Time & route model'}</strong>
                      <span className="mono muted small">{m.algorithm}</span></div>
                    {m.modelKind === 'LIVE_FORECAST' && t.regression ? (
                      <span className="small">Passengers MAE {t.regression.mae.toFixed(2)} (baseline {b.persistence_regression?.mae.toFixed(2)}) ·
                        level macro-F1 {t.classification.macro_f1.toFixed(3)} (baseline {b.persistence_classification?.macro_f1.toFixed(3)})</span>
                    ) : t.accuracy != null && <span className="small">Accuracy {percent(t.accuracy, 1)} · macro-F1 {t.macro_f1.toFixed(3)}</span>}
                    <span className="muted small">Trained {formatDateTime(m.trainedAt)} · {m.datasetSource?.startsWith('SYNTHETIC') ? 'synthetic dataset' : m.datasetSource}</span>
                  </div>
                );
              })}
              {perfApi.data && (
                <div className="small">
                  <strong>Live forecast vs. later recorded counts:</strong>{' '}
                  {perfApi.data.forecastsChecked > 0
                    ? `${perfApi.data.forecastsChecked} checked · MAE ${perfApi.data.meanAbsoluteError} (persistence ${perfApi.data.persistenceMeanAbsoluteError}) · level accuracy ${percent(perfApi.data.levelAccuracy)}`
                    : 'not enough data yet'}
                  <div className="muted">{perfApi.data.note}</div>
                </div>
              )}
            </div>
          )}
        </Card>
      </div>

      <div className="grid grid-main-side">
        <Card title="Predictions by hour of day" subtitle="Last 7 days, stacked by predicted crowd level" actions={<CrowdLegend />}>
          {loading || !data ? <SkeletonRows rows={6} /> : data.predictionsByHour.every((r) => r.LOW + r.MEDIUM + r.HIGH === 0)
            ? <EmptyState icon={Brain} title="No predictions in the last 7 days" />
            : <HourlyStackedChart rows={data.predictionsByHour} />}
        </Card>

        <div className="stack">
          <Card title="System status">
            {loading || !data ? <SkeletonRows rows={3} /> : (
              <div className="stack">
                <div className="row-between">
                  <span className="row"><MapPinned size={16} /> GPS simulator</span>
                  <span className={`badge ${data.simulationEnabled ? 'badge-low' : 'badge-neutral'}`}>{data.simulationEnabled ? 'Running' : 'Disabled'}</span>
                </div>
                <div className="row-between">
                  <span className="row"><Brain size={16} /> ML prediction service</span>
                  <span className={`badge ${model.available ? 'badge-low' : 'badge-danger'}`}>{model.available ? 'Online' : 'Offline'}</span>
                </div>
                {!model.available && <Alert type="error">{model.message}</Alert>}
                {model.available && (
                  <dl className="kv">
                    <dt>Model</dt><dd>{model.details.model_type.replace(/_/g, ' ')}</dd>
                    <dt>Test accuracy</dt><dd>{percent(model.details.test.accuracy, 1)}</dd>
                    <dt>Test macro-F1</dt><dd>{model.details.test.macro_f1.toFixed(3)}</dd>
                    <dt>Trained</dt><dd>{formatDateTime(model.details.trained_at)}</dd>
                    <dt>Dataset</dt><dd>Synthetic ({model.details.dataset.rows.toLocaleString()} rows)</dd>
                  </dl>
                )}
              </div>
            )}
          </Card>
          <Card title="Predictions in the last 24 hours">
            {loading || !data ? <SkeletonRows rows={2} /> : <CrowdDistribution counts={data.predictionsLast24h} />}
          </Card>
        </div>
      </div>

      <Card title="Current crowd by route" subtitle="Latest prediction of each bus, grouped by route" flush
        actions={<Link className="btn btn-secondary btn-sm" to="/admin/predictions"><Activity size={14} /> Full history</Link>}>
        {loading || !data ? <div className="card-body"><SkeletonRows /></div> : data.routeCrowd.length === 0 ? (
          <EmptyState title="No predictions yet" />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Route</th><th>Low</th><th>Medium</th><th>High</th><th style={{ width: '35%' }}>Distribution</th></tr></thead>
              <tbody>
                {data.routeCrowd.map((r) => {
                  const total = r.LOW + r.MEDIUM + r.HIGH;
                  return (
                    <tr key={r.routeId}>
                      <td><strong>{r.label}</strong></td>
                      <td>{r.LOW}</td><td>{r.MEDIUM}</td><td>{r.HIGH}</td>
                      <td>
                        <div className="stacked-bar" aria-label={`Low ${r.LOW}, Medium ${r.MEDIUM}, High ${r.HIGH}`}>
                          {['LOW', 'MEDIUM', 'HIGH'].map((l) => r[l] > 0 && (
                            <div key={l} style={{ width: `${(r[l] / total) * 100}%`, background: `var(--crowd-${l.toLowerCase()})` }} />
                          ))}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
