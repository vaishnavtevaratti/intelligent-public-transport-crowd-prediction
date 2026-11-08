import { AlertTriangle, Armchair, Camera, Clock, TrendingUp, Users } from 'lucide-react';
import { CrowdBadge } from './ui/ui';
import { CROWD_META, formatTime, percent, timeAgo } from '../utils/format';

const KIND = {
  measured: { cls: 'badge-measured', text: 'Measured' },
  estimated: { cls: 'badge-estimated', text: 'Estimated' },
  predicted: { cls: 'badge-predicted', text: 'Predicted' },
  simulated: { cls: 'badge-sim', text: 'Simulated' },
  test: { cls: 'badge-test', text: 'Test video' },
  webcam: { cls: 'badge-test', text: 'Laptop webcam demo', title: 'Real detection on a laptop webcam. Not a camera installed in a bus.' },
  manual: { cls: 'badge-neutral', text: 'Manual count' },
  unavailable: { cls: 'badge-neutral', text: 'Unavailable' },
};

/** Small label that says what kind of number is shown next to it. */
export function DataLabel({ kind, title }) {
  const k = KIND[kind] || KIND.unavailable;
  return <span className={`badge ${k.cls}`} title={title || k.title}>{k.text}</span>;
}

const MODE_KIND = { TEST_VIDEO: 'test', WEBCAM_DEMO: 'webcam', CAMERA: 'estimated', MANUAL: 'manual', SEAT_SENSOR: 'measured', SIMULATED: 'simulated' };
const METHOD_TEXT = {
  DOOR_COUNT: 'Door camera entry/exit count',
  CABIN_VISIBLE: 'People visible to cabin camera (lower bound)',
  MANUAL: 'Manual head count by staff',
  SIMULATED: 'Simulated passenger count for the demo (this bus has no counting sensor)',
};

export default function OccupancyPanel({ occupancy: o, compact = false }) {
  if (!o) return <p className="muted small">Occupancy not loaded.</p>;
  if (o.estimatedOnboard == null) {
    return (
      <div className="stack-sm">
        <div className="row"><Users size={15} className="muted" /><strong>Passengers onboard</strong><DataLabel kind="unavailable" /></div>
        <p className="muted small">
          {o.dataStatus === 'NO_SENSOR'
            ? 'This bus has no passenger-counting camera or sensor, so its live occupancy is unknown.'
            : 'A sensor is registered but has not reported a passenger count yet.'}
          {' '}Capacity {o.capacity}{o.seatCount ? ` · ${o.seatCount} seats` : ''}.
        </p>
        {o.occupiedSeats != null && <p className="small">Occupied seats (measured): {o.occupiedSeats}/{o.seatCount}</p>}
      </div>
    );
  }
  const pct = Math.min(100, o.occupancyPercent ?? 0);
  const meta = CROWD_META[o.crowdCategory] || CROWD_META.LOW;
  const stale = o.dataStatus === 'STALE';
  const forecast = o.liveForecast;
  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="row wrap" style={{ gap: 6 }}>
        <Users size={15} className="muted" /><strong>Passengers onboard</strong>
        <DataLabel kind={MODE_KIND[o.dataMode] || 'estimated'} title={METHOD_TEXT[o.method]} />
        {o.dataMode !== 'SIMULATED' && <DataLabel kind="estimated" />}
        <CrowdBadge level={o.crowdCategory} />
        {stale && <span className="badge badge-medium"><Clock size={11} /> Stale</span>}
      </div>
      <div>
        <div className="row-between small" style={{ marginBottom: 4 }}>
          <span><strong style={{ fontSize: '1.2rem' }}>{o.onboardIsLowerBound ? '≥ ' : '~'}{o.estimatedOnboard}</strong> of {o.capacity} total capacity</span>
          <span className="mono">{o.occupancyPercent}%</span>
        </div>
        <div className="capacity-track" role="progressbar" aria-valuenow={o.occupancyPercent} aria-valuemin={0} aria-valuemax={100}
          aria-label="Estimated occupancy of total passenger capacity">
          <div className="capacity-fill" style={{ width: `${pct}%`, background: meta.color }} />
        </div>
      </div>
      {o.overCapacity && <div className="alert alert-error"><AlertTriangle size={15} />Over capacity: the estimate exceeds the allowed {o.capacity} passengers.</div>}
      <div className="metric-grid">
        <div className="metric" title="Total passenger capacity minus the passengers onboard. This is not a count of empty seats.">
          <div className="label">Passenger places left (estimate)</div><div className="value">{o.availableCapacity}</div>
        </div>
        <div className="metric">
          <div className="label"><Armchair size={11} /> Seats available</div>
          {o.availableSeats != null
            ? <div className="value">{o.availableSeats}<span className="muted small"> / {o.seatCount}</span></div>
            : <div className="small" style={{ paddingTop: 3 }}>{o.seatCount ? `Unknown (${o.seatCount} seats; no seat sensor)` : 'Unknown (seat count not configured)'}</div>}
        </div>
        {!compact && o.standingPassengers != null && (
          <div className="metric"><div className="label">Standing (estimated)</div><div className="value">{o.standingPassengers}</div></div>
        )}
        {!compact && (
          <div className="metric"><div className="label">Data confidence</div><div className="value">{o.confidence != null ? percent(o.confidence) : '—'}</div></div>
        )}
      </div>
      <div className="muted small">
        {METHOD_TEXT[o.method]} · updated {formatTime(o.updatedAt)} ({timeAgo(o.updatedAt)})
        {o.seatDataStatus !== 'UNKNOWN' && ` · seats ${o.seatDataStatus === 'MANUAL' ? 'counted manually' : 'from seat sensor'}`}
      </div>
      {o.onboardIsLowerBound && <div className="muted small">A cabin camera cannot see everyone (people can be hidden), so the real number may be higher.</div>}
      {forecast && (
        <div className="row wrap" style={{ gap: 6 }}>
          <TrendingUp size={15} color="var(--admin)" />
          <span className="small">In {forecast.horizonMinutes} min: ~{Math.round(forecast.predictedPassengers)} onboard</span>
          <CrowdBadge level={forecast.crowdLevel} confidence={forecast.confidence} />
          <DataLabel kind="predicted" title={`Model ${forecast.modelVersion}`} />
          {o.dataMode === 'SIMULATED' && <span className="muted small">(from simulated counts)</span>}
        </div>
      )}
      {o.needsReconciliation && (
        <div className="alert alert-warning"><AlertTriangle size={15} />Needs reconciliation: {o.reconciliationReason}</div>
      )}
      {!compact && o.sensors?.length > 0 && (
        <div className="stack-sm">
          {o.sensors.map((s) => (
            <div key={s.id} className="row small" style={{ gap: 6 }}>
              <Camera size={13} className="muted" />
              <span className="mono">{s.sensorCode}</span>
              <SensorHealthBadge sensor={s} />
              <span className="muted">{s.lastSeenAt ? `last report ${timeAgo(s.lastSeenAt)}` : 'never reported'}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function SensorHealthBadge({ sensor }) {
  if (!sensor.enabled) return <span className="badge badge-neutral">Disabled</span>;
  if (!sensor.online) return <span className="badge badge-danger">Offline</span>;
  const h = sensor.lastHealth;
  const cls = h === 'OK' ? 'badge-low' : h === 'DEGRADED' ? 'badge-medium' : h === 'UNKNOWN' ? 'badge-neutral' : 'badge-danger';
  const text = { OK: 'Online', DEGRADED: 'Degraded', CAMERA_UNAVAILABLE: 'Camera unavailable', INFERENCE_ERROR: 'Model error', UNKNOWN: 'No reports' }[h] || h;
  return <span className={`badge ${cls}`}><span className="badge-dot" />{text}</span>;
}
