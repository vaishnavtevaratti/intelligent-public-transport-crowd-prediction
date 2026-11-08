import { useState } from 'react';
import { ArrowDownUp, ArrowRight, Bus, Clock, MapPin, Route as RouteIcon, Search, Star } from 'lucide-react';
import TransitMap from '../components/map/TransitMap';
import { Alert, BusStatusBadge, Card, CrowdBadge, EmptyState, ErrorState, PageHeader, SimulatedBadge, SkeletonRows, Spinner } from '../components/ui/ui';
import { useApi } from '../hooks/useApi';
import { useOccupancy } from '../hooks/useOccupancy';
import { DataLabel } from '../components/OccupancyPanel';
import { travelService } from '../services/travelService';
import { routeService } from '../services/routeService';
import { getErrorMessage } from '../services/api';
import { hourLabel, routeLabel, timeAgo, occupancySummary } from '../utils/format';

export default function TravelInformation() {
  const stopsApi = useApi(() => travelService.getStops());
  const routesApi = useApi(() => routeService.getAllRoutes());
  const { byBus: occupancy } = useOccupancy();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [options, setOptions] = useState(null);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState(null);

  const search = async (event) => {
    event?.preventDefault();
    setSearchError(null);
    if (!from || !to) { setSearchError('Choose both a starting stop and a destination.'); return; }
    if (from === to) { setSearchError('Starting stop and destination must be different.'); return; }
    setSearching(true);
    try {
      const result = await travelService.search(from, to);
      setOptions(result);
      setSelectedIndex(0);
    } catch (err) {
      setSearchError(getErrorMessage(err));
    } finally {
      setSearching(false);
    }
  };

  const swap = () => { setFrom(to); setTo(from); setOptions(null); };
  const option = options?.[selectedIndex];
  const stops = stopsApi.data || [];

  return (
    <>
      <PageHeader title="Travel planner" description="Find the route between two stops, see arriving buses and the least crowded time to travel." />

      <Card>
        {stopsApi.error ? <ErrorState message={stopsApi.error} onRetry={stopsApi.reload} /> : (
          <form onSubmit={search} className="row wrap" style={{ alignItems: 'flex-end', gap: 12 }}>
            <div className="field" style={{ flex: '1 1 220px' }}>
              <label htmlFor="from">From</label>
              <select id="from" className="select" value={from} onChange={(e) => { setFrom(e.target.value); setOptions(null); }} disabled={stopsApi.loading}>
                <option value="">{stopsApi.loading ? 'Loading stops…' : 'Choose starting stop'}</option>
                {stops.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <button type="button" className="btn btn-secondary btn-icon" onClick={swap} aria-label="Swap from and to"><ArrowDownUp size={16} /></button>
            <div className="field" style={{ flex: '1 1 220px' }}>
              <label htmlFor="to">To</label>
              <select id="to" className="select" value={to} onChange={(e) => { setTo(e.target.value); setOptions(null); }} disabled={stopsApi.loading}>
                <option value="">{stopsApi.loading ? 'Loading stops…' : 'Choose destination'}</option>
                {stops.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <button className="btn btn-primary" type="submit" disabled={searching} style={{ height: 38 }}>
              {searching ? <Spinner label="Searching" /> : <Search size={16} />} Find buses
            </button>
          </form>
        )}
        {searchError && <div style={{ marginTop: 12 }}><Alert type="error">{searchError}</Alert></div>}
      </Card>

      {options && options.length === 0 && (
        <div className="card">
          <EmptyState icon={RouteIcon} title="No direct route found"
            message={`No single route currently serves both ${from} and ${to}. Try a nearby stop or check the route list below.`} />
        </div>
      )}

      {option && (
        <div className="grid grid-main-side">
          <div className="stack">
            <TransitMap
              routes={[option.route]}
              buses={option.buses.map((b) => ({
                id: b.busId, busNumber: b.busNumber, currentLatitude: b.latitude, currentLongitude: b.longitude,
                latestCrowdLevel: b.latestCrowdLevel, lastUpdated: b.lastUpdated, routeId: option.route.id,
              }))}
              highlightRouteId={option.route.id}
              places={[option.fromStop, option.toStop].map((name, i) => {
                const stop = option.route.stops.find((s) => s.name === name);
                return stop && { label: name, latitude: stop.latitude, longitude: stop.longitude, kind: i === 0 ? 'origin' : 'destination' };
              }).filter(Boolean)}
              fitKey={`${option.route.id}-${from}-${to}`}
              className="short"
              overlay={<SimulatedBadge />}
            />
            {option.note && <Alert type="warning">{option.note}</Alert>}
            <Card title="Stops on this trip">
              <ol className="stack-sm" style={{ margin: 0, paddingLeft: 18 }}>
                {(option.forward ? option.route.stops : [...option.route.stops].reverse())
                  .filter((s, i, arr) => {
                    const a = arr.findIndex((x) => x.name === option.fromStop);
                    const b = arr.findIndex((x) => x.name === option.toStop);
                    return i >= a && i <= b;
                  })
                  .map((s) => (
                    <li key={s.name} style={{ fontWeight: s.name === option.fromStop || s.name === option.toStop ? 700 : 400 }}>{s.name}</li>
                  ))}
              </ol>
              {option.route.stops.length === 0 && <p className="muted">This route has no stop list yet.</p>}
            </Card>
          </div>

          <div className="stack">
            {options.length > 1 && (
              <Card title={`${options.length} route options`} flush>
                <div className="list">
                  {options.map((o, i) => (
                    <button key={o.route.id} className={`list-item ${i === selectedIndex ? 'active' : ''}`} onClick={() => setSelectedIndex(i)}>
                      <RouteIcon size={16} className="muted" />
                      <div style={{ flex: 1 }}><strong>{routeLabel(o.route)}</strong>
                        <div className="muted small">{o.distanceKm != null ? `${o.distanceKm} km · ~${o.estimatedMinutes} min` : 'Distance unknown'}</div></div>
                    </button>
                  ))}
                </div>
              </Card>
            )}

            <Card title={routeLabel(option.route)} subtitle={`${option.fromStop} → ${option.toStop} · ${option.forward ? 'towards ' + option.route.destination : 'towards ' + option.route.source}`}>
              <div className="stack">
                <dl className="kv">
                  <dt><MapPin size={13} /> Stops in between</dt><dd>{option.stopsBetween}</dd>
                  <dt><RouteIcon size={13} /> Trip distance</dt><dd>{option.distanceKm != null ? `${option.distanceKm} km` : '—'}</dd>
                  <dt><Clock size={13} /> Estimated ride time</dt><dd>{option.estimatedMinutes != null ? `~${option.estimatedMinutes} min` : '—'}</dd>
                  <dt>Distance source</dt>
                  <dd>{option.routingSource === 'GOOGLE_ROUTES'
                    ? <span className="badge badge-measured" title="Road network distance and drive time from the Google Routes API">Road · Google Routes</span>
                    : option.routingSource === 'ROAD_GEOMETRY_ESTIMATE'
                      ? <span className="badge badge-neutral" title="Distance along the route's OpenStreetMap road geometry; time at the average bus speed">Road distance · OpenStreetMap</span>
                      : option.routingSource ? <span className="badge badge-neutral" title="Straight lines between stop coordinates at the average bus speed">Straight-line estimate</span> : '—'}</dd>
                  <dt>Crowd now (ML)</dt><dd>{option.crowdNow ? <CrowdBadge level={option.crowdNow.crowdLevel} confidence={option.crowdNow.confidence} /> : 'Unavailable'}</dd>
                </dl>
                {option.bestHoursToday.length > 0 && (
                  <div className="row wrap" style={{ gap: 6 }}>
                    <Star size={15} color="var(--success-text)" /><span className="small">Least crowded later today:</span>
                    {option.bestHoursToday.map((h) => <span key={h} className="badge badge-low">{hourLabel(h)}</span>)}
                  </div>
                )}
                <p className="muted small">
                  {option.routingSource === 'GOOGLE_ROUTES'
                    ? 'Ride time = Google road drive time between the stops plus ~30 s per intermediate stop (no live traffic for buses). '
                    : option.routingSource === 'ROAD_GEOMETRY_ESTIMATE'
                      ? 'Distance follows the roads of this demo route (OpenStreetMap); ride time assumes an average bus speed, without traffic. '
                      : 'Ride time uses straight lines between stops at an average speed. '}
                  Bus arrival times come from the GPS simulator and are approximations.
                </p>
              </div>
            </Card>

            <Card title="Buses on this route" flush>
              {option.buses.length === 0 ? <EmptyState icon={Bus} title="No buses assigned to this route" /> : (
                <div className="list">
                  {option.buses.map((b) => (
                    <div key={b.busId} className="list-item" style={{ cursor: 'default' }}>
                      <Bus size={16} className="muted" />
                      <div style={{ flex: 1 }}>
                        <strong>{b.busNumber}</strong> <BusStatusBadge status={b.status} />
                        <div className="muted small">Position updated {timeAgo(b.lastUpdated)}</div>
                        <div className="small">{occupancySummary(occupancy[b.busId])}
                          {occupancy[b.busId]?.estimatedOnboard != null && <> <DataLabel kind="estimated" /></>}</div>
                      </div>
                      <div className="stack-sm" style={{ alignItems: 'flex-end', gap: 4 }}>
                        <span className="small" style={{ fontWeight: 600 }}>
                          {b.etaMinutes != null ? `Arrives in ~${b.etaMinutes} min` : b.status === 'ACTIVE' ? 'ETA unknown' : 'Not in service'}
                        </span>
                        <CrowdBadge level={b.latestCrowdLevel} />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </div>
        </div>
      )}

      <Card title="All routes" subtitle="Route directory from the database" flush>
        {routesApi.error ? <ErrorState message={routesApi.error} onRetry={routesApi.reload} /> : routesApi.loading ? <div className="card-body"><SkeletonRows /></div> : (routesApi.data || []).length === 0 ? (
          <EmptyState icon={RouteIcon} title="No routes yet" message="An administrator can add routes from Manage Routes." />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Route</th><th>Distance</th><th>Stops</th><th>Buses</th><th>Description</th><th /></tr></thead>
              <tbody>
                {routesApi.data.map((r) => (
                  <tr key={r.id}>
                    <td><strong>{routeLabel(r)}</strong></td>
                    <td>{r.distance} km</td>
                    <td>{r.stops.length}</td>
                    <td>{r.busCount}</td>
                    <td className="muted">{r.description || '—'}</td>
                    <td className="actions">
                      <button className="btn btn-ghost btn-sm" onClick={() => { setFrom(r.stops[0]?.name || r.source); setTo(r.stops[r.stops.length - 1]?.name || r.destination); setOptions(null); window.scrollTo({ top: 0, behavior: 'smooth' }); }}>
                        Plan <ArrowRight size={14} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
