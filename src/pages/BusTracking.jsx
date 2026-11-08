import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Brain, Bus, Clock, Compass, Gauge, MapPin, MapPinOff, Navigation, RefreshCw, Route as RouteIcon, Search } from 'lucide-react';
import TransitMap from '../components/map/TransitMap';
import {
  BusStatusBadge, Card, ConnectionIndicator, CrowdBadge, EmptyState, ErrorState, PageHeader, SimulatedBadge, SkeletonRows,
} from '../components/ui/ui';
import OccupancyPanel, { DataLabel } from '../components/OccupancyPanel';
import { useLiveBuses } from '../hooks/useLiveBuses';
import { useOccupancy } from '../hooks/useOccupancy';
import { useNow } from '../hooks/useNow';
import { CROWD_META, OCCUPANCY_LEVELS, formatTime, isStale, routeLabel, timeAgo, occupancySummary } from '../utils/format';

/** Live occupancy category when fresh sensor data exists, otherwise the latest ML prediction. */
function crowdFor(bus, occ) {
  if (occ?.dataStatus === 'LIVE' && occ.crowdCategory) return { level: occ.crowdCategory, kind: 'live' };
  if (bus.latestCrowdLevel) return { level: bus.latestCrowdLevel, kind: 'predicted' };
  return { level: null, kind: 'none' };
}

export default function BusTracking() {
  const { buses, routes, loading, error, status, lastMessageAt, reload } = useLiveBuses();
  const { byBus: occupancy } = useOccupancy();
  const now = useNow(1000);
  const [selectedId, setSelectedId] = useState(null);
  const [query, setQuery] = useState('');
  const [busFilter, setBusFilter] = useState('');
  const [routeFilter, setRouteFilter] = useState('');
  const [crowdFilter, setCrowdFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('ACTIVE');

  const filtered = useMemo(() => buses.filter((b) => {
    if (busFilter && String(b.id) !== busFilter) return false;
    if (statusFilter && b.status !== statusFilter) return false;
    if (routeFilter && String(b.routeId) !== routeFilter) return false;
    if (crowdFilter && crowdFor(b, occupancy[b.id]).level !== crowdFilter) return false;
    const q = query.trim().toLowerCase();
    return !q || b.busNumber.toLowerCase().includes(q) || routeLabel(b).toLowerCase().includes(q);
  }), [buses, occupancy, query, busFilter, routeFilter, crowdFilter, statusFilter]);

  // Map markers use the same crowd level shown in the list, and a tooltip line with occupancy.
  const mapBuses = filtered.map((b) => {
    const occ = occupancy[b.id];
    const subtitle = [b.routeCode ? `Route ${b.routeCode}` : 'No route', b.status !== 'ACTIVE' ? b.status.toLowerCase() : null,
      occupancySummary(occ)].filter(Boolean).join(' · ');
    return { ...b, latestCrowdLevel: crowdFor(b, occ).level, subtitle };
  });

  const selected = buses.find((b) => b.id === selectedId) || null;
  const selectedOcc = selected ? occupancy[selected.id] : null;
  const selectedRoute = selected ? routes.find((r) => r.id === selected.routeId) : null;
  const highlightRouteId = routeFilter ? Number(routeFilter) : selected?.routeId;
  const mapRoutes = routeFilter ? routes.filter((r) => String(r.id) === routeFilter) : routes;
  const feedStale = status === 'live' && lastMessageAt && now - lastMessageAt > 15000;
  const fullBuses = Object.values(occupancy).filter((o) => o.dataStatus === 'LIVE' && (o.crowdCategory === 'FULL' || o.overCapacity));
  const selectedCrowd = selected ? crowdFor(selected, selectedOcc) : null;

  return (
    <>
      <PageHeader
        title="Live bus tracking"
        description="Kolhapur, Maharashtra · academic demo routes (not official KMT routes or timetables). Positions come from the GPS simulator and update every ~5 seconds. Click a bus for details."
        actions={(
          <>
            <ConnectionIndicator status={status} />
            <SimulatedBadge />
            <button className="btn btn-secondary btn-sm" onClick={reload}><RefreshCw size={14} /> Refresh data</button>
          </>
        )}
      />

      {fullBuses.length > 0 && (
        <div className="alert alert-error" role="alert">
          <AlertTriangle size={16} />
          <div>
            <strong>Full buses:</strong> {fullBuses.map((o) => `${o.busNumber} (~${o.estimatedOnboard}/${o.capacity})`).join(', ')}.
            {' '}Passenger counts are camera estimates.
          </div>
        </div>
      )}

      {error ? <div className="card"><ErrorState message={error} onRetry={reload} /></div> : (
        <div className="grid grid-main-side">
          <div className="stack">
            <TransitMap
              routes={mapRoutes}
              buses={mapBuses}
              selectedBusId={selectedId}
              onSelectBus={setSelectedId}
              highlightRouteId={highlightRouteId}
              fitKey={`${loading}-${routeFilter}`}
              now={now}
              overlay={(
                <span className="map-chip">
                  {lastMessageAt ? <>Last location update {timeAgo(lastMessageAt, now)} ({formatTime(lastMessageAt)})</> : 'Waiting for first live update…'}
                </span>
              )}
            />
            <div className="row-between wrap">
              <div className="legend">
                {OCCUPANCY_LEVELS.map((l) => (
                  <span key={l}><span className="swatch" style={{ background: CROWD_META[l].color }} />{CROWD_META[l].label}</span>
                ))}
              </div>
              <span className="muted small">Colour = live occupancy if a camera reports it, else the ML prediction · grey = no data · faded = no update for 30 s</span>
            </div>
            {feedStale && (
              <div className="alert alert-warning">No position updates received for {Math.round((now - lastMessageAt) / 1000)} s. The simulator may be stopped or the connection interrupted.</div>
            )}
          </div>

          <div className="stack">
            {selected && (
              <Card title={`Bus ${selected.busNumber}`}
                subtitle={selectedRoute ? `Route #${selectedRoute.id} · ${routeLabel(selectedRoute)}` : 'Not assigned to a route'}
                actions={<button className="btn btn-ghost btn-sm" onClick={() => setSelectedId(null)}>Close</button>}>
                <div className="stack">
                  <div className="row wrap">
                    <BusStatusBadge status={selected.status} />
                    <CrowdBadge level={selectedCrowd.level} />
                    {selectedCrowd.kind !== 'none' && <DataLabel kind={selectedCrowd.kind === 'live' ? 'estimated' : 'predicted'} />}
                  </div>
                  {selected.currentLatitude == null && (
                    <div className="alert alert-warning"><MapPinOff size={15} />Location unavailable: this bus has never reported a position.</div>
                  )}
                  <dl className="kv">
                    <dt><Clock size={13} /> Last location</dt>
                    <dd>{selected.lastUpdated ? `${formatTime(selected.lastUpdated)} (${timeAgo(selected.lastUpdated, now)})` : 'No position yet'}</dd>
                    <dt><Compass size={13} /> Direction</dt><dd>{selected.direction ? `towards ${selected.direction}` : '—'}</dd>
                    <dt><Navigation size={13} /> Next stop</dt><dd>{selected.nextStop || '—'}</dd>
                    <dt><Gauge size={13} /> Speed</dt><dd>{selected.speedKmph != null ? `${selected.speedKmph} km/h` : '—'}</dd>
                    <dt><MapPin size={13} /> Position</dt>
                    <dd className="mono">{selected.currentLatitude != null ? `${selected.currentLatitude.toFixed(5)}, ${selected.currentLongitude.toFixed(5)}` : '—'}</dd>
                    <dt>Location source</dt><dd>{selected.locationSource === 'GPS' ? <DataLabel kind="measured" title="Real GPS device" /> : <DataLabel kind="simulated" title="Built-in GPS simulator" />}</dd>
                    <dt><RouteIcon size={13} /> Route stops</dt><dd>{selectedRoute?.stops?.length ?? 0}</dd>
                  </dl>
                  {isStale(selected.lastUpdated, now) && selected.status === 'ACTIVE' && selected.currentLatitude != null && (
                    <div className="alert alert-warning">This position is more than 30 seconds old (stale).</div>
                  )}
                  <OccupancyPanel occupancy={selectedOcc} />
                  <Link className="btn btn-primary btn-sm" to={`/crowd?busId=${selected.id}`}><Brain size={14} /> Predict crowd for this bus</Link>
                </div>
              </Card>
            )}

            <Card title={`Buses (${filtered.length})`} flush>
              <div className="card-body stack" style={{ paddingBottom: 12 }}>
                <div className="input-icon">
                  <Search size={15} />
                  <input className="input" placeholder="Search bus or route…" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search buses" />
                </div>
                <div className="grid grid-2" style={{ gap: 8 }}>
                  <select className="select" value={busFilter} onChange={(e) => setBusFilter(e.target.value)} aria-label="Filter by bus">
                    <option value="">All buses</option>
                    {buses.map((b) => <option key={b.id} value={b.id}>{b.busNumber}</option>)}
                  </select>
                  <select className="select" value={routeFilter} onChange={(e) => setRouteFilter(e.target.value)} aria-label="Filter by route">
                    <option value="">All routes</option>
                    {routes.map((r) => <option key={r.id} value={r.id}>#{r.id} {routeLabel(r)}</option>)}
                  </select>
                  <select className="select" value={crowdFilter} onChange={(e) => setCrowdFilter(e.target.value)} aria-label="Filter by crowd level">
                    <option value="">Any crowd</option>
                    {OCCUPANCY_LEVELS.map((l) => <option key={l} value={l}>{CROWD_META[l].label}</option>)}
                  </select>
                  <select className="select" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} aria-label="Filter by status">
                    <option value="">Any status</option>
                    <option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option><option value="MAINTENANCE">Maintenance</option>
                  </select>
                </div>
              </div>
              {loading ? <div className="card-body"><SkeletonRows rows={6} /></div> : filtered.length === 0 ? (
                <EmptyState icon={Bus} title={buses.length ? 'No buses match the filters' : 'No buses in the system'}
                  message={buses.length ? 'Change or clear the filters above.' : 'An administrator can add buses from Manage Buses.'} />
              ) : (
                <div className="list scroll-list">
                  {filtered.map((b) => {
                    const occ = occupancy[b.id];
                    const crowd = crowdFor(b, occ);
                    return (
                      <button key={b.id} className={`list-item ${b.id === selectedId ? 'active' : ''}`} onClick={() => setSelectedId(b.id)}>
                        <Bus size={16} className="muted" />
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <strong>{b.busNumber}</strong> <span className="muted small">#{b.routeId ?? '—'}</span>
                          <div className="muted small" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{routeLabel(b)}</div>
                          <div className="muted small">{occupancySummary(occ)} · location {timeAgo(b.lastUpdated, now)}</div>
                        </div>
                        <div className="stack-sm" style={{ alignItems: 'flex-end', gap: 3 }}>
                          <CrowdBadge level={crowd.level} />
                          {crowd.kind !== 'none' && <span className="muted" style={{ fontSize: '0.68rem' }}>{crowd.kind === 'live' ? 'live estimate' : 'predicted'}</span>}
                        </div>
                      </button>
                    );
                  })}
                </div>
              )}
            </Card>
          </div>
        </div>
      )}
    </>
  );
}
