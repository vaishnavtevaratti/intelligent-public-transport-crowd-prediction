import { useCallback, useEffect, useState } from 'react';
import { Camera, ClipboardCheck, Copy, KeyRound, Eye, ListOrdered, Play, Plus, Power, RotateCcw, Square, Trash2 } from 'lucide-react';
import {
  Alert, Card, ConfirmDialog, CrowdBadge, EmptyState, ErrorState, Modal, PageHeader, Pagination, SkeletonRows, Spinner,
} from '../../components/ui/ui';
import { DataLabel, SensorHealthBadge } from '../../components/OccupancyPanel';
import { useApi } from '../../hooks/useApi';
import { useOccupancy } from '../../hooks/useOccupancy';
import { useToast } from '../../context/ToastContext';
import { adminService } from '../../services/adminService';
import { busService } from '../../services/busService';
import { occupancyService } from '../../services/occupancyService';
import { getErrorMessage, getFieldErrors } from '../../services/api';
import { formatDateTime, timeAgo } from '../../utils/format';

const TYPES = {
  DOOR_CAMERA: 'Door camera (entries / exits)',
  CABIN_CAMERA: 'Cabin camera (visible people)',
  SEAT_SENSOR: 'Seat occupancy sensor',
};
const MODE_KIND = { TEST_VIDEO: 'test', WEBCAM_DEMO: 'webcam', CAMERA: 'estimated', MANUAL: 'manual', SEAT_SENSOR: 'measured', SIMULATED: 'simulated' };

function SensorForm({ buses, onClose, onCreated }) {
  const [form, setForm] = useState({ sensorCode: '', busId: buses[0]?.id ? String(buses[0].id) : '', type: 'DOOR_CAMERA', description: '' });
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState(null);
  const [saving, setSaving] = useState(false);
  const update = (f) => (e) => setForm((s) => ({ ...s, [f]: e.target.value }));

  const save = async (e) => {
    e.preventDefault();
    const errs = {};
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]{2,39}$/.test(form.sensorCode.trim())) errs.sensorCode = '3-40 letters, digits, dashes or underscores';
    if (!form.busId) errs.busId = 'Choose a bus';
    setErrors(errs);
    if (Object.keys(errs).length) return;
    setSaving(true);
    setServerError(null);
    try {
      onCreated(await adminService.createSensor({ ...form, sensorCode: form.sensorCode.trim(), busId: Number(form.busId) }));
    } catch (err) {
      setErrors(getFieldErrors(err));
      setServerError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title="Register camera / sensor" onClose={onClose}
      footer={<><button className="btn btn-secondary" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" form="sensor-form" type="submit" disabled={saving}>{saving && <Spinner />} Register</button></>}>
      <form id="sensor-form" className="form-grid" onSubmit={save} noValidate>
        {serverError && <div className="full"><Alert type="error">{serverError}</Alert></div>}
        <div className="field">
          <label htmlFor="sensorCode">Sensor code *</label>
          <input id="sensorCode" className={`input ${errors.sensorCode ? 'invalid' : ''}`} value={form.sensorCode} onChange={update('sensorCode')} placeholder="e.g. CAM-BUS103-DOOR" />
          {errors.sensorCode && <span className="field-error">{errors.sensorCode}</span>}
        </div>
        <div className="field">
          <label htmlFor="sensorBus">Bus *</label>
          <select id="sensorBus" className="select" value={form.busId} onChange={update('busId')}>
            {buses.map((b) => <option key={b.id} value={b.id}>{b.busNumber}</option>)}
          </select>
        </div>
        <div className="field full">
          <label htmlFor="sensorType">Type *</label>
          <select id="sensorType" className="select" value={form.type} onChange={update('type')}>
            {Object.entries(TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <span className="field-hint">
            {form.type === 'DOOR_CAMERA' && 'Counts people crossing a line in the doorway. Needs a starting count (manual reconciliation).'}
            {form.type === 'CABIN_CAMERA' && 'Counts people visible inside the bus. People hidden from view are missed, so it is a lower bound.'}
            {form.type === 'SEAT_SENSOR' && 'Reports how many seats are occupied. The only source of seat availability besides a manual count.'}
          </span>
        </div>
        <div className="field full">
          <label htmlFor="sensorDesc">Description</label>
          <input id="sensorDesc" className="input" value={form.description} onChange={update('description')} maxLength={200} placeholder="Position, camera model…" />
        </div>
      </form>
    </Modal>
  );
}

function KeyDialog({ sensor, onClose }) {
  const toast = useToast();
  const command = `python run_counter.py --source <camera index or video> --mode ${sensor.type === 'CABIN_CAMERA' ? 'cabin' : 'door'} --sensor-code ${sensor.sensorCode} --sensor-key <key>`;
  return (
    <Modal title={`Secret key for ${sensor.sensorCode}`} onClose={onClose}
      footer={<button className="btn btn-primary" onClick={onClose}>I have stored the key</button>}>
      <div className="stack">
        <Alert type="warning" icon={KeyRound}>This key is shown <strong>only once</strong>. Only its hash is stored, so it cannot be shown again (rotate it if lost).</Alert>
        <div className="key-box">{sensor.apiKey}</div>
        <button className="btn btn-secondary btn-sm" style={{ alignSelf: 'flex-start' }}
          onClick={() => navigator.clipboard?.writeText(sensor.apiKey).then(() => toast.success('Key copied'), () => toast.error('Copy failed'))}>
          <Copy size={14} /> Copy key
        </button>
        <p className="small text-2">Start the counter on the bus device (from <span className="mono">vision-service/</span>):</p>
        <div className="key-box small">{command}</div>
      </div>
    </Modal>
  );
}

function ReconcileForm({ occ, onClose, onSaved }) {
  const [form, setForm] = useState({ onboard: occ.estimatedOnboard != null ? String(occ.estimatedOnboard) : '', occupiedSeats: '', note: '' });
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const update = (f) => (e) => setForm((s) => ({ ...s, [f]: e.target.value }));

  const save = async (e) => {
    e.preventDefault();
    const onboard = Number(form.onboard);
    const seats = form.occupiedSeats === '' ? null : Number(form.occupiedSeats);
    if (!Number.isInteger(onboard) || onboard < 0 || onboard > 400) return setError('Onboard count must be a whole number between 0 and 400');
    if (seats != null && (!Number.isInteger(seats) || seats < 0)) return setError('Occupied seats must be a whole number');
    if (seats != null && !occ.seatCount) return setError('Configure the seat count of this bus (Manage Buses) before entering occupied seats');
    if (seats != null && seats > occ.seatCount) return setError(`Occupied seats cannot exceed the ${occ.seatCount} seats`);
    if (seats != null && seats > onboard) return setError('Occupied seats cannot exceed passengers onboard');
    if (!form.note.trim()) return setError('Explain the correction in the note (e.g. "conductor head count at depot")');
    setSaving(true);
    try {
      onSaved(await occupancyService.reconcile(occ.busId, { onboard, occupiedSeats: seats, note: form.note.trim() }));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={`Reconcile occupancy of ${occ.busNumber}`} onClose={onClose}
      footer={<><button className="btn btn-secondary" onClick={onClose}>Cancel</button>
        <button className="btn btn-admin" form="reconcile-form" type="submit" disabled={saving}>{saving && <Spinner />} Save count</button></>}>
      <form id="reconcile-form" className="form-grid" onSubmit={save} noValidate>
        {occ.needsReconciliation && <div className="full"><Alert type="warning">{occ.reconciliationReason}</Alert></div>}
        {error && <div className="full"><Alert type="error">{error}</Alert></div>}
        <div className="field">
          <label htmlFor="rcOnboard">Passengers onboard (counted) *</label>
          <input id="rcOnboard" type="number" min="0" className="input" value={form.onboard} onChange={update('onboard')} />
          <span className="field-hint">Capacity {occ.capacity}</span>
        </div>
        <div className="field">
          <label htmlFor="rcSeats">Occupied seats (optional)</label>
          <input id="rcSeats" type="number" min="0" className="input" value={form.occupiedSeats} onChange={update('occupiedSeats')} disabled={!occ.seatCount}
            placeholder={occ.seatCount ? `of ${occ.seatCount} seats` : 'seat count not configured'} />
        </div>
        <div className="field full">
          <label htmlFor="rcNote">Note *</label>
          <input id="rcNote" className="input" value={form.note} onChange={update('note')} maxLength={255} />
        </div>
        <p className="full muted small">Door counting continues from this value. The correction is stored in the audit log with your account.</p>
      </form>
    </Modal>
  );
}

const COUNTER_STATE = { RUNNING: 'badge-low', STOPPING: 'badge-medium', STOPPED: 'badge-neutral' };

function sourceLabel(source) {
  if (source == null) return '—';
  return /^\d$/.test(source) ? `Camera ${source}${source === '0' ? ' (laptop webcam)' : ''}` : source.replace('test_videos/', 'Test video: ');
}

function StartCounterDialog({ code, sensor, videos, onClose, onStarted }) {
  const [form, setForm] = useState({
    source: videos[0] || '0',
    mode: sensor?.type === 'CABIN_CAMERA' ? 'cabin' : 'door',
    consent: false,
    loop: true,
  });
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const isCamera = /^\d$/.test(form.source);

  const start = async (e) => {
    e.preventDefault();
    if (isCamera && !form.consent) return setError('Confirm that everyone in view of the camera has agreed to be counted');
    setSaving(true);
    setError(null);
    try {
      onStarted(await adminService.startCounter({
        sensorCode: code, source: form.source, mode: form.mode, confirmConsent: isCamera && form.consent, loop: isCamera ? false : form.loop,
      }));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={`Start counter ${code}`} onClose={onClose}
      footer={<><button className="btn btn-secondary" onClick={onClose}>Cancel</button>
        <button className="btn btn-admin" form="counter-form" type="submit" disabled={saving || (isCamera && !form.consent)}>{saving && <Spinner />} <Play size={14} /> Start</button></>}>
      <form id="counter-form" className="form-grid" onSubmit={start} noValidate>
        {error && <div className="full"><Alert type="error">{error}</Alert></div>}
        <div className="field">
          <label htmlFor="ctrSource">Input *</label>
          <select id="ctrSource" className="select" value={form.source} onChange={(e) => setForm((s) => ({ ...s, source: e.target.value, consent: false }))}>
            {videos.map((v) => <option key={v} value={v}>{sourceLabel(v)}</option>)}
            <option value="0">Camera 0 (laptop webcam)</option>
            <option value="1">Camera 1 (external USB camera)</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="ctrMode">Counting mode *</label>
          <select id="ctrMode" className="select" value={form.mode} onChange={(e) => setForm((s) => ({ ...s, mode: e.target.value }))}>
            <option value="door">Door: entries / exits across a line</option>
            <option value="cabin">Cabin: people visible</option>
          </select>
        </div>
        {isCamera ? (
          <div className="full stack">
            <Alert type="warning" icon={Camera}>
              The camera turns on and its annotated picture is shown on this page (open <strong>View</strong>). Frames are kept in
              memory only: nothing is recorded, saved or uploaded, and only counts are stored. A laptop webcam is a
              <strong> demonstration</strong>, not a validated bus camera.
            </Alert>
            <label className="small" style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
              <input id="ctrConsent" type="checkbox" checked={form.consent} onChange={(e) => setForm((s) => ({ ...s, consent: e.target.checked }))} />
              <span>Everyone who will be in view of the camera has agreed to be counted.</span>
            </label>
          </div>
        ) : (
          <label className="full small" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input id="ctrLoop" type="checkbox" checked={form.loop} onChange={(e) => setForm((s) => ({ ...s, loop: e.target.checked }))} />
            <span>Replay the video in a loop until stopped</span>
          </label>
        )}
      </form>
    </Modal>
  );
}

/**
 * Live view of a running counter: the annotated picture (polled ~2x per second through the backend; frames stay in
 * memory), people visible now, crossings counted this session, and the backend's onboard estimate.
 */
function LiveCounterView({ code, counter, occupancy, onStop, onClose }) {
  const toast = useToast();
  const [frameUrl, setFrameUrl] = useState(null);
  const [live, setLive] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    let url = null;
    const poll = async () => {
      try {
        const [blob, status] = await Promise.all([adminService.getCounterFrame(code), adminService.getCounterLive(code)]);
        if (cancelled) return;
        setLive(status);
        setError(null);
        if (!blob || !blob.size) return;   // 204: the detector is still starting
        const next = URL.createObjectURL(blob);
        setFrameUrl(next);
        if (url) URL.revokeObjectURL(url);
        url = next;
      } catch (err) {
        if (!cancelled) setError(getErrorMessage(err));
      }
    };
    poll();
    const id = setInterval(poll, 500);
    return () => { cancelled = true; clearInterval(id); if (url) URL.revokeObjectURL(url); };
  }, [code]);

  const reset = async () => {
    try {
      await adminService.resetCounter(code);
      toast.success('Session counts reset to zero');
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  };

  const door = counter.mode === 'door';
  return (
    <div className="card-body stack" style={{ borderTop: '1px solid var(--border)' }}>
      <div className="row-between wrap">
        <strong>Live view · <span className="mono">{code}</span> · {sourceLabel(counter.source)} · {counter.mode} mode</strong>
        <div className="row wrap" style={{ gap: 6 }}>
          {door && <button className="btn btn-secondary btn-sm" onClick={reset}><RotateCcw size={14} /> Reset session counts</button>}
          <button className="btn btn-secondary btn-sm" onClick={onStop}><Square size={14} color="var(--danger)" /> Stop</button>
          <button className="btn btn-ghost btn-sm" onClick={onClose}>Close view</button>
        </div>
      </div>
      {error && <Alert type="info">{error}</Alert>}
      <div className="live-view">
        <div className="live-frame">
          {frameUrl ? <img src={frameUrl} alt={`Annotated live picture of ${code}: boxes around detected people${door ? ' and the counting line' : ''}`} />
            : <div className="state"><Spinner label="Starting" /> {live?.health === 'STARTING' ? 'Loading the detector…' : 'Waiting for the first picture…'}</div>}
        </div>
        <div className="stack-sm" style={{ minWidth: 220 }}>
          <div className="metric"><div className="label">People visible now</div><div className="value">{live?.visible ?? '—'}</div>
            <div className="muted small">in the picture (median of recent frames)</div></div>
          {door && (
            <div className="metric"><div className="label">Crossings this session</div>
              <div className="value">{live ? `${live.sessionEntries} in · ${live.sessionExits} out` : '—'}</div>
              <div className="muted small">people who crossed the red line; each crossing counts once</div></div>
          )}
          <div className="metric"><div className="label">Estimated onboard (backend)</div>
            <div className="value">{occupancy?.estimatedOnboard != null ? `${occupancy.onboardIsLowerBound ? '≥' : '~'}${occupancy.estimatedOnboard}` : '—'}</div>
            <div className="muted small">{door ? 'starting count + entries − exits (reconcile to set the start)' : 'people visible to the cabin camera (lower bound)'}</div></div>
          <div className="muted small">
            {live ? `${live.fps ?? '—'} frames/s · health ${live.health}${live.message ? ` (${live.message})` : ''} · ` : ''}
            Seats are never inferred from the camera.
          </div>
        </div>
      </div>
    </div>
  );
}

/** Start/stop counters through the local counter agent (vision-service/agent.py) via the backend. */
function CounterControl({ sensors, onActivity, occupancyByBus }) {
  const toast = useToast();
  const countersApi = useApi(() => adminService.getCounters());
  const [starting, setStarting] = useState(null);
  const [viewing, setViewing] = useState(null);
  const [stopping, setStopping] = useState(null);
  const { reload } = countersApi;
  const anyRunning = countersApi.data?.counters.some((c) => c.state !== 'STOPPED');

  useEffect(() => {
    const id = setInterval(() => {
      reload({ silent: true });
      if (anyRunning) onActivity();
    }, 3000);
    return () => clearInterval(id);
  }, [reload, anyRunning, onActivity]);

  const stop = async (code) => {
    setStopping(code);
    try {
      await adminService.stopCounter(code);
      toast.success(`Stopping ${code}…`);
      reload({ silent: true });
    } catch (err) {
      toast.error(getErrorMessage(err));
    } finally {
      setStopping(null);
    }
  };

  const bySensor = Object.fromEntries((sensors || []).map((s) => [s.sensorCode, s]));
  return (
    <Card title="Counter control" subtitle="Start and stop run_counter.py on this computer through the local counter agent" flush>
      {countersApi.error && !countersApi.data ? (
        <div className="card-body">
          <Alert type="info">
            {countersApi.error} Run <span className="mono">python agent.py</span> in <span className="mono">vision-service/</span> (it only accepts
            connections from this computer); this panel connects automatically.
          </Alert>
        </div>
      ) : !countersApi.data ? <div className="card-body"><SkeletonRows rows={3} /></div> : countersApi.data.counters.length === 0 ? (
        <EmptyState icon={Camera} title="No sensor keys on this computer" message="Register a sensor and store its key in vision-service/sensor_keys.json." />
      ) : (
        <div className="table-wrap">
          {countersApi.error && <div className="card-body"><Alert type="warning">Agent not responding: {countersApi.error}</Alert></div>}
          <table className="table">
            <thead><tr><th>Sensor</th><th>State</th><th>Input</th><th>Last output</th><th className="actions">Actions</th></tr></thead>
            <tbody>
              {countersApi.data.counters.map((c) => (
                <tr key={c.sensorCode}>
                  <td><strong className="mono">{c.sensorCode}</strong>{bySensor[c.sensorCode] && <div className="muted small">{bySensor[c.sensorCode].busNumber}</div>}</td>
                  <td>
                    <span className={`badge ${COUNTER_STATE[c.state]}`}>{c.state.toLowerCase()}</span>
                    {c.state === 'STOPPED' && c.exitCode != null && c.exitCode !== 0 && <div className="muted small">exit code {c.exitCode}</div>}
                  </td>
                  <td className="small">{sourceLabel(c.source)}{c.mode && <div className="muted">{c.mode} mode</div>}</td>
                  <td className="small mono" style={{ maxWidth: 380, overflowWrap: 'anywhere' }}>{c.lastLog || '—'}</td>
                  <td className="actions nowrap">
                    {c.state === 'STOPPED' ? (
                      <button className="btn btn-ghost btn-sm" onClick={() => setStarting(c.sensorCode)} aria-label={`Start counter ${c.sensorCode}`}><Play size={14} /> Start</button>
                    ) : (<>
                      <button className="btn btn-ghost btn-sm" onClick={() => setViewing(viewing === c.sensorCode ? null : c.sensorCode)}
                        aria-label={`View counter ${c.sensorCode}`} aria-pressed={viewing === c.sensorCode}><Eye size={14} /> View</button>
                      <button className="btn btn-ghost btn-sm" disabled={c.state === 'STOPPING' || stopping === c.sensorCode}
                        onClick={() => stop(c.sensorCode)} aria-label={`Stop counter ${c.sensorCode}`}><Square size={14} color="var(--danger)" /> Stop</button>
                    </>)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {viewing && countersApi.data?.counters.some((c) => c.sensorCode === viewing && c.state === 'RUNNING') && (
        <LiveCounterView code={viewing} counter={countersApi.data.counters.find((c) => c.sensorCode === viewing)}
          occupancy={bySensor[viewing] ? occupancyByBus[bySensor[viewing].busId] : null}
          onStop={() => stop(viewing)} onClose={() => setViewing(null)} />
      )}
      {starting && (
        <StartCounterDialog code={starting} sensor={bySensor[starting]} videos={countersApi.data?.testVideos || []}
          onClose={() => setStarting(null)}
          onStarted={(c) => { toast.success(`${c.sensorCode} started`); setStarting(null); setViewing(c.sensorCode); reload({ silent: true }); }} />
      )}
    </Card>
  );
}

function ObservationLog({ occ, onClose }) {
  const [page, setPage] = useState(0);
  const { data, loading, error, reload } = useApi(() => occupancyService.getObservations(occ.busId, { page, size: 15 }), [page]);
  return (
    <Modal wide title={`Observation log — ${occ.busNumber}`} onClose={onClose}>
      {error ? <ErrorState message={error} onRetry={reload} /> : loading && !data ? <SkeletonRows rows={6} /> : data.content.length === 0 ? (
        <EmptyState icon={ListOrdered} title="No observations yet" />
      ) : (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Observed</th><th>Sensor</th><th>#</th><th>In / out</th><th>Visible</th><th>Seats</th><th>Onboard</th><th>Status</th><th>Source</th></tr></thead>
              <tbody>
                {data.content.map((o) => (
                  <tr key={o.id}>
                    <td className="nowrap">{formatDateTime(o.observedAt)}</td>
                    <td className="mono">{o.sensorCode || o.performedBy || '—'}</td>
                    <td>{o.sequenceNumber ?? '—'}</td>
                    <td>{o.entries != null ? `+${o.entries} / −${o.exits}` : '—'}</td>
                    <td>{o.visibleCount ?? '—'}</td>
                    <td>{o.occupiedSeats ?? '—'}</td>
                    <td className="nowrap">{o.previousOnboard ?? '?'} → {o.updatedOnboard ?? '?'}</td>
                    <td><span className={`badge ${o.status.includes('CLAMP') || o.status === 'HEALTH_ONLY' ? 'badge-medium' : 'badge-neutral'}`} title={o.note || o.health}>{o.status.replaceAll('_', ' ').toLowerCase()}</span></td>
                    <td><DataLabel kind={MODE_KIND[o.sourceMode] || 'estimated'} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} label="observations" />
        </>
      )}
    </Modal>
  );
}

export default function OccupancySensors() {
  const toast = useToast();
  const sensorsApi = useApi(() => adminService.getSensors());
  const busesApi = useApi(() => busService.getAllBuses());
  const { byBus, loading: occLoading, error: occError } = useOccupancy();
  const [dialog, setDialog] = useState(null); // {type, ...}
  const [busy, setBusy] = useState(false);
  const { reload: reloadSensors } = sensorsApi;
  const refreshSensors = useCallback(() => reloadSensors({ silent: true }), [reloadSensors]);
  const occupancy = Object.values(byBus).sort((a, b) => a.busNumber.localeCompare(b.busNumber, undefined, { numeric: true }));

  const sensorAction = async (fn, success) => {
    setBusy(true);
    try {
      const result = await fn();
      if (success) toast.success(success);
      sensorsApi.reload({ silent: true });
      return result;
    } catch (err) {
      toast.error(getErrorMessage(err));
      return null;
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Occupancy & sensors" description="Passenger-counting cameras, their health, live onboard estimates and manual reconciliation."
        actions={<button className="btn btn-admin" onClick={() => setDialog({ type: 'create' })} disabled={!busesApi.data}><Plus size={16} /> Register sensor</button>} />

      <Card title="Live occupancy by bus" subtitle="Onboard values are estimates from cameras or manual counts; seats are shown only when measured" flush>
        {occError ? <ErrorState message={occError} /> : occLoading ? <div className="card-body"><SkeletonRows rows={5} /></div> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Bus</th><th>Capacity / seats</th><th>Onboard (est.)</th><th>Places left</th><th>Seats free</th><th>Crowd</th><th>Data</th><th className="actions">Actions</th></tr></thead>
              <tbody>
                {occupancy.map((o) => (
                  <tr key={o.busId}>
                    <td><strong>{o.busNumber}</strong>{o.needsReconciliation && <div><span className="badge badge-medium" title={o.reconciliationReason}>needs reconciliation</span></div>}</td>
                    <td>{o.capacity} / {o.seatCount ?? <span className="muted">not set</span>}</td>
                    <td>{o.estimatedOnboard != null ? `${o.onboardIsLowerBound ? '≥' : '~'}${o.estimatedOnboard}` : <span className="muted">unknown</span>}</td>
                    <td>{o.availableCapacity ?? '—'}</td>
                    <td>{o.availableSeats ?? <span className="muted" title="No seat sensor or recent manual seat count">unknown</span>}</td>
                    <td>{o.crowdCategory ? <CrowdBadge level={o.crowdCategory} /> : '—'}</td>
                    <td className="nowrap">
                      {o.dataStatus === 'NO_SENSOR' ? <span className="muted">no sensor</span>
                        : o.dataStatus === 'NO_DATA' ? <span className="muted">no data yet</span>
                          : <>{o.dataMode && <DataLabel kind={MODE_KIND[o.dataMode]} />} <span className={`badge ${o.dataStatus === 'LIVE' ? 'badge-low' : 'badge-medium'}`}>{o.dataStatus.toLowerCase()}</span>
                            <div className="muted small">{timeAgo(o.updatedAt)}</div></>}
                    </td>
                    <td className="actions">
                      <button className="btn btn-ghost btn-sm" onClick={() => setDialog({ type: 'reconcile', occ: o })}><ClipboardCheck size={14} /> Reconcile</button>
                      <button className="btn btn-ghost btn-sm btn-icon" onClick={() => setDialog({ type: 'log', occ: o })} aria-label={`Observation log of ${o.busNumber}`}><ListOrdered size={15} /></button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <CounterControl sensors={sensorsApi.data} onActivity={refreshSensors} occupancyByBus={byBus} />

      <Card title="Cameras & sensors" subtitle="A sensor is offline when it has not reported for 60 seconds" flush>
        {sensorsApi.error ? <ErrorState message={sensorsApi.error} onRetry={sensorsApi.reload} /> : sensorsApi.loading ? <div className="card-body"><SkeletonRows rows={3} /></div> : sensorsApi.data.length === 0 ? (
          <EmptyState icon={Camera} title="No sensors registered" message="Register a camera, then run vision-service/run_counter.py on the bus device with its key." />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Sensor</th><th>Bus</th><th>Type</th><th>Health</th><th>Last report</th><th>Input</th><th>Detector</th><th className="actions">Actions</th></tr></thead>
              <tbody>
                {sensorsApi.data.map((s) => (
                  <tr key={s.id}>
                    <td><strong className="mono">{s.sensorCode}</strong>{s.description && <div className="muted small">{s.description}</div>}</td>
                    <td>{s.busNumber}</td>
                    <td className="small">{TYPES[s.type]}</td>
                    <td><SensorHealthBadge sensor={s} />{s.lastMessage && <div className="muted small">{s.lastMessage}</div>}</td>
                    <td className="nowrap">{s.lastSeenAt ? timeAgo(s.lastSeenAt) : 'never'}{s.lastSequence != null && <div className="muted small">report #{s.lastSequence}</div>}</td>
                    <td>{s.lastMode ? <DataLabel kind={MODE_KIND[s.lastMode]} /> : '—'}</td>
                    <td className="mono small">{s.detectorModel || '—'}</td>
                    <td className="actions nowrap">
                      <button className="btn btn-ghost btn-sm btn-icon" disabled={busy} title={s.enabled ? 'Disable' : 'Enable'} aria-label={`${s.enabled ? 'Disable' : 'Enable'} ${s.sensorCode}`}
                        onClick={() => sensorAction(() => adminService.updateSensor(s.id, { sensorCode: s.sensorCode, busId: s.busId, type: s.type, description: s.description, enabled: !s.enabled }),
                          `${s.sensorCode} ${s.enabled ? 'disabled' : 'enabled'}`)}>
                        <Power size={15} color={s.enabled ? 'var(--success-text)' : 'var(--text-3)'} />
                      </button>
                      <button className="btn btn-ghost btn-sm btn-icon" disabled={busy} title="Rotate key" aria-label={`Rotate key of ${s.sensorCode}`}
                        onClick={() => setDialog({ type: 'rotate', sensor: s })}><KeyRound size={15} /></button>
                      <button className="btn btn-ghost btn-sm btn-icon" disabled={busy} aria-label={`Delete ${s.sensorCode}`}
                        onClick={() => setDialog({ type: 'delete', sensor: s })}><Trash2 size={15} color="var(--danger)" /></button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="Privacy">
        <p className="small text-2">
          Counting runs on the device; frames are discarded after detection and no video is stored or sent. The detector finds
          people (not faces) and track IDs are temporary numbers, so no individual is identified. Only counts, timestamps,
          confidence and sensor health are stored (kept 30 days). Display a notice in the bus informing passengers that camera-based counting is in use.
        </p>
      </Card>

      {dialog?.type === 'create' && (
        <SensorForm buses={busesApi.data || []} onClose={() => setDialog(null)}
          onCreated={(s) => { sensorsApi.reload({ silent: true }); setDialog({ type: 'key', sensor: s }); }} />
      )}
      {dialog?.type === 'key' && <KeyDialog sensor={dialog.sensor} onClose={() => setDialog(null)} />}
      {dialog?.type === 'rotate' && (
        <ConfirmDialog title={`Rotate key of ${dialog.sensor.sensorCode}?`} confirmLabel="Rotate key" busy={busy}
          message="The current key stops working immediately; the device must be updated with the new key."
          onCancel={() => setDialog(null)}
          onConfirm={async () => { const s = await sensorAction(() => adminService.rotateSensorKey(dialog.sensor.id), 'Key rotated'); setDialog(s ? { type: 'key', sensor: s } : null); }} />
      )}
      {dialog?.type === 'delete' && (
        <ConfirmDialog title={`Delete ${dialog.sensor.sensorCode}?`} busy={busy}
          message="The sensor is removed and its key stops working. Its past observations are kept in the log."
          onCancel={() => setDialog(null)}
          onConfirm={async () => { await sensorAction(() => adminService.deleteSensor(dialog.sensor.id), 'Sensor deleted'); setDialog(null); }} />
      )}
      {dialog?.type === 'reconcile' && (
        <ReconcileForm occ={dialog.occ} onClose={() => setDialog(null)}
          onSaved={(o) => { toast.success(`${o.busNumber}: onboard set to ${o.estimatedOnboard}`); setDialog(null); }} />
      )}
      {dialog?.type === 'log' && <ObservationLog occ={dialog.occ} onClose={() => setDialog(null)} />}
    </>
  );
}
