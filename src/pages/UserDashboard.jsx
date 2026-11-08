import { Link } from 'react-router-dom';
import { AlertTriangle, ArrowRight, Brain, Bus, CircleCheck, CircleOff, MapPin, MapPinned, Navigation, Route as RouteIcon } from 'lucide-react';
import { Alert, Card, CrowdBadge, EmptyState, ErrorState, PageHeader, SkeletonRows, StatCard } from '../components/ui/ui';
import { CrowdDistribution } from '../components/charts/charts';
import { useAuth } from '../context/AuthContext';
import { useApi } from '../hooks/useApi';
import { useOccupancy } from '../hooks/useOccupancy';
import { DataLabel } from '../components/OccupancyPanel';
import { dashboardService } from '../services/dashboardService';
import { formatDateTime, routeLabel, occupancySummary } from '../utils/format';

export default function UserDashboard() {
  const { user } = useAuth();
  const { data, loading, error, reload } = useApi(() => dashboardService.getDashboardStats());
  const { byBus } = useOccupancy();
  const liveOccupancy = Object.values(byBus).filter((o) => o.estimatedOnboard != null)
    .sort((a, b) => (b.occupancyPercent ?? 0) - (a.occupancyPercent ?? 0));
  const fullBuses = liveOccupancy.filter((o) => o.dataStatus === 'LIVE' && (o.crowdCategory === 'FULL' || o.overCapacity));

  const firstName = (user?.name || '').split(' ')[0];

  return (
    <>
      <PageHeader
        title={`Hello, ${firstName}`}
        description="Live overview of the Kolhapur demo bus network (academic demo routes, simulated GPS) and crowd predictions."
        actions={<Link className="btn btn-primary" to="/travel"><Navigation size={16} /> Plan a trip</Link>}
      />

      {error && <div className="card"><ErrorState message={error} onRetry={reload} /></div>}

      {!error && (
        <>
          {data && !data.mlServiceAvailable && (
            <Alert type="warning">
              The crowd prediction (ML) service is offline, so new predictions cannot be made right now. Stored predictions are still shown.
            </Alert>
          )}
          <div className="grid grid-4">
            {loading || !data ? Array.from({ length: 4 }, (_, i) => <div key={i} className="card stat-card"><SkeletonRows rows={2} /></div>) : (
              <>
                <StatCard icon={Bus} label="Total buses" value={data.totalBuses} hint={`${data.activeBuses} active now`} />
                <StatCard icon={CircleCheck} label="Active buses" value={data.activeBuses}
                  hint={data.simulationEnabled ? 'Positions from GPS simulator' : 'Simulator off'} />
                <StatCard icon={RouteIcon} label="Routes" value={data.totalRoutes} hint={`${data.totalStops} stops in total`} />
                <StatCard icon={AlertTriangle} label="High-crowd alerts" value={data.highCrowdAlerts.length} hint="Buses predicted High in the last hour"
                  tone={data.highCrowdAlerts.length ? 'danger' : undefined} />
              </>
            )}
          </div>

          {fullBuses.length > 0 && (
            <Alert type="error" icon={AlertTriangle}>
              Full buses right now: {fullBuses.map((o) => o.busNumber).join(', ')}. Consider another bus or a later time.
            </Alert>
          )}

          <Card title="Live passenger occupancy" subtitle="Buses with a passenger-counting camera or a recent manual count" flush>
            {liveOccupancy.length === 0 ? (
              <EmptyState icon={Bus} title="No live occupancy data"
                message="No bus is currently reporting passenger counts. Crowd levels below are ML predictions." />
            ) : (
              <div className="list">
                {liveOccupancy.map((o) => (
                  <div key={o.busId} className="list-item" style={{ cursor: 'default' }}>
                    <Bus size={16} className="muted" />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <strong>{o.busNumber}</strong> <DataLabel kind={{ TEST_VIDEO: 'test', WEBCAM_DEMO: 'webcam', MANUAL: 'manual' }[o.dataMode] || 'estimated'} />
                      {o.dataStatus === 'STALE' && <span className="badge badge-medium" style={{ marginLeft: 4 }}>stale</span>}
                      <div className="muted small">{occupancySummary(o)}
                        {' · '}{o.availableSeats != null ? `${o.availableSeats} seats free` : 'seat availability unknown'}</div>
                    </div>
                    <CrowdBadge level={o.crowdCategory} />
                  </div>
                ))}
              </div>
            )}
          </Card>

          <div className="grid grid-main-side">
            <Card title="Predicted crowd levels" subtitle="Latest ML prediction for each bus">
              {loading || !data ? <SkeletonRows rows={3} /> : (
                Object.values(data.crowdSummary).every((v) => v === 0)
                  ? <EmptyState icon={Brain} title="No predictions yet" message="Predictions appear here as soon as the model has run for the active buses." />
                  : <CrowdDistribution counts={data.crowdSummary} />
              )}
            </Card>

            <Card title="Quick actions">
              <div className="stack-sm">
                {[
                  { to: '/tracking', icon: MapPinned, label: 'Track buses live', text: 'See every bus moving on the map' },
                  { to: '/crowd', icon: Brain, label: 'Predict crowd level', text: 'Check how busy a bus will be' },
                  { to: '/travel', icon: MapPin, label: 'Find a route', text: 'Arrival estimates and best time to travel' },
                ].map(({ to, icon: Icon, label, text }) => (
                  <Link key={to} to={to} className="list-item" style={{ borderRadius: 8, border: '1px solid var(--border)', textDecoration: 'none' }}>
                    <div className="stat-icon" style={{ width: 36, height: 36 }}><Icon size={18} /></div>
                    <div style={{ flex: 1 }}><strong>{label}</strong><div className="muted small">{text}</div></div>
                    <ArrowRight size={16} className="muted" />
                  </Link>
                ))}
              </div>
            </Card>
          </div>

          <div className="grid grid-2">
            <Card title="Recent predictions" subtitle="Newest crowd predictions stored in the database" flush>
              {loading || !data ? <div className="card-body"><SkeletonRows /></div> : data.recentPredictions.length === 0 ? (
                <EmptyState title="No predictions stored yet" />
              ) : (
                <div className="list">
                  {data.recentPredictions.map((p) => (
                    <div key={p.id} className="list-item" style={{ cursor: 'default' }}>
                      <Bus size={16} className="muted" />
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <strong>{p.busNumber}</strong> <span className="muted small">· {routeLabel(p)}</span>
                        <div className="muted small">{formatDateTime(p.timestamp)} · {p.source ? p.source.replace('_', '-').toLowerCase() : 'legacy record'}</div>
                      </div>
                      <CrowdBadge level={p.crowdLevel} confidence={p.confidence} />
                    </div>
                  ))}
                </div>
              )}
            </Card>

            <Card title="High crowd alerts" subtitle="Buses predicted to be highly crowded in the last hour" flush>
              {loading || !data ? <div className="card-body"><SkeletonRows /></div> : data.highCrowdAlerts.length === 0 ? (
                <EmptyState icon={CircleOff} title="No high-crowd alerts" message="No bus has been predicted as highly crowded in the last hour." />
              ) : (
                <div className="list">
                  {data.highCrowdAlerts.map((p) => (
                    <div key={p.id} className="list-item" style={{ cursor: 'default' }}>
                      <AlertTriangle size={16} color="var(--crowd-high)" />
                      <div style={{ flex: 1 }}>
                        <strong>{p.busNumber}</strong> · {routeLabel(p)}
                        <div className="muted small">{formatDateTime(p.timestamp)}</div>
                      </div>
                      <CrowdBadge level={p.crowdLevel} />
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </div>
        </>
      )}
    </>
  );
}
