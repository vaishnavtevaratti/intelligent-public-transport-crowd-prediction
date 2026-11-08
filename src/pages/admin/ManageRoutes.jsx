import { useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, Map as MapIcon, MousePointerClick, Pencil, Plus, Route as RouteIcon, Search, Trash2, X } from 'lucide-react';
import TransitMap from '../../components/map/TransitMap';
import {
  Alert, Card, ConfirmDialog, EmptyState, ErrorState, Modal, PageHeader, Pagination, SkeletonRows, SortHeader, Spinner,
} from '../../components/ui/ui';
import { useApi } from '../../hooks/useApi';
import { useTable } from '../../hooks/useTable';
import { useToast } from '../../context/ToastContext';
import { routeService } from '../../services/routeService';
import { getErrorMessage } from '../../services/api';
import { routeLabel } from '../../utils/format';

const COLUMNS = {
  route: (r) => routeLabel(r),
  distance: (r) => r.distance,
  stops: (r) => r.stops.length,
  buses: (r) => r.busCount,
};

function RouteForm({ initial, otherRoutes, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({
    source: initial?.source || '',
    destination: initial?.destination || '',
    distance: initial?.distance != null ? String(initial.distance) : '',
    description: initial?.description || '',
  });
  const [stops, setStops] = useState(() => (initial?.stops || []).map((s) => ({ name: s.name, latitude: s.latitude, longitude: s.longitude })));
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState(null);
  const [saving, setSaving] = useState(false);
  const [fitKey, setFitKey] = useState(0);

  const update = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));
  const updateStop = (i, field, value) => setStops((list) => list.map((s, idx) => (idx === i ? { ...s, [field]: value } : s)));
  const move = (i, dir) => setStops((list) => {
    const next = [...list];
    [next[i], next[i + dir]] = [next[i + dir], next[i]];
    return next;
  });
  const addStopAt = (lat, lng) => setStops((list) => [...list, { name: `Stop ${list.length + 1}`, latitude: Number(lat.toFixed(6)), longitude: Number(lng.toFixed(6)) }]);

  const validate = () => {
    const e = {};
    if (!form.source.trim()) e.source = 'Source is required';
    if (!form.destination.trim()) e.destination = 'Destination is required';
    if (form.source.trim() && form.source.trim().toLowerCase() === form.destination.trim().toLowerCase()) e.destination = 'Must differ from source';
    if (form.distance !== '' && !(Number(form.distance) > 0 && Number(form.distance) <= 500)) e.distance = 'Between 0 and 500 km';
    if (form.distance === '' && stops.length < 2) e.distance = 'Required when the route has fewer than 2 stops';
    if (stops.length === 1) e.stops = 'Add at least two stops, or remove the single stop';
    const names = new Set();
    stops.forEach((s, i) => {
      const lat = Number(s.latitude);
      const lng = Number(s.longitude);
      if (!String(s.name).trim()) e.stops = `Stop ${i + 1} needs a name`;
      else if (names.has(s.name.trim().toLowerCase())) e.stops = `Duplicate stop name: ${s.name}`;
      else if (Number.isNaN(lat) || lat < -90 || lat > 90 || Number.isNaN(lng) || lng < -180 || lng > 180) e.stops = `Stop ${i + 1} has invalid coordinates`;
      names.add(String(s.name).trim().toLowerCase());
    });
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const save = async (event) => {
    event.preventDefault();
    setServerError(null);
    if (!validate()) return;
    setSaving(true);
    const payload = {
      source: form.source.trim(),
      destination: form.destination.trim(),
      distance: form.distance === '' ? null : Number(form.distance),
      description: form.description.trim() || null,
      stops: stops.map((s) => ({ name: s.name.trim(), latitude: Number(s.latitude), longitude: Number(s.longitude) })),
    };
    try {
      const saved = initial ? await routeService.updateRoute(initial.id, payload) : await routeService.createRoute(payload);
      toast.success(`Route ${routeLabel(saved)} ${initial ? 'updated' : 'created'} (${saved.distance} km)`);
      onSaved();
    } catch (err) {
      setServerError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const draft = stops.filter((s) => !Number.isNaN(Number(s.latitude)) && !Number.isNaN(Number(s.longitude)) && s.latitude !== '' && s.longitude !== '')
    .map((s) => ({ ...s, latitude: Number(s.latitude), longitude: Number(s.longitude) }));

  return (
    <Modal wide title={initial ? `Edit route ${routeLabel(initial)}` : 'Add route'} onClose={onClose}
      footer={(
        <>
          <button className="btn btn-secondary" onClick={onClose} disabled={saving}>Cancel</button>
          <button className="btn btn-primary" form="route-form" type="submit" disabled={saving}>{saving && <Spinner />} Save route</button>
        </>
      )}>
      <form id="route-form" onSubmit={save} noValidate className="grid grid-2" style={{ alignItems: 'start' }}>
        <div className="stack">
          {serverError && <Alert type="error">{serverError}</Alert>}
          <div className="form-grid">
            <div className="field">
              <label htmlFor="source">Source *</label>
              <input id="source" className={`input ${errors.source ? 'invalid' : ''}`} value={form.source} onChange={update('source')} />
              {errors.source && <span className="field-error">{errors.source}</span>}
            </div>
            <div className="field">
              <label htmlFor="destination">Destination *</label>
              <input id="destination" className={`input ${errors.destination ? 'invalid' : ''}`} value={form.destination} onChange={update('destination')} />
              {errors.destination && <span className="field-error">{errors.destination}</span>}
            </div>
            <div className="field">
              <label htmlFor="distance">Distance (km)</label>
              <input id="distance" type="number" step="0.1" className={`input ${errors.distance ? 'invalid' : ''}`} value={form.distance} onChange={update('distance')} placeholder="Auto from stops" />
              {errors.distance ? <span className="field-error">{errors.distance}</span> : <span className="field-hint">Leave empty to calculate from stops.</span>}
            </div>
            <div className="field">
              <label htmlFor="description">Description</label>
              <input id="description" className="input" value={form.description} onChange={update('description')} maxLength={255} />
            </div>
          </div>

          <div className="row-between">
            <strong>Stops ({stops.length})</strong>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => setStops((l) => [...l, { name: `Stop ${l.length + 1}`, latitude: '', longitude: '' }])}>
              <Plus size={14} /> Add stop manually
            </button>
          </div>
          {errors.stops && <span className="field-error">{errors.stops}</span>}
          {stops.length === 0 && <p className="muted small">No stops yet. Click on the map to add stops in travel order. Stops are needed for the map, simulation and arrival estimates.</p>}
          <div className="stack-sm" style={{ maxHeight: 300, overflowY: 'auto' }}>
            {stops.map((s, i) => (
              <div key={i} className="row" style={{ gap: 6 }}>
                <span className="muted small" style={{ width: 18 }}>{i + 1}.</span>
                <input className="input" style={{ flex: 2 }} value={s.name} onChange={(e) => updateStop(i, 'name', e.target.value)} aria-label={`Stop ${i + 1} name`} />
                <input className="input" style={{ flex: 1 }} value={s.latitude} onChange={(e) => updateStop(i, 'latitude', e.target.value)} aria-label={`Stop ${i + 1} latitude`} placeholder="Lat" />
                <input className="input" style={{ flex: 1 }} value={s.longitude} onChange={(e) => updateStop(i, 'longitude', e.target.value)} aria-label={`Stop ${i + 1} longitude`} placeholder="Lng" />
                <button type="button" className="btn btn-ghost btn-sm btn-icon" disabled={i === 0} onClick={() => move(i, -1)} aria-label="Move up"><ArrowUp size={14} /></button>
                <button type="button" className="btn btn-ghost btn-sm btn-icon" disabled={i === stops.length - 1} onClick={() => move(i, 1)} aria-label="Move down"><ArrowDown size={14} /></button>
                <button type="button" className="btn btn-ghost btn-sm btn-icon" onClick={() => setStops((l) => l.filter((_, idx) => idx !== i))} aria-label="Remove stop"><X size={14} /></button>
              </div>
            ))}
          </div>
        </div>

        <div className="stack-sm">
          <div className="row-between">
            <span className="small text-2"><MousePointerClick size={14} style={{ verticalAlign: -2 }} /> Click the map to add the next stop</span>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setFitKey((k) => k + 1)}><MapIcon size={14} /> Fit</button>
          </div>
          <TransitMap routes={otherRoutes} highlightRouteId={-1} showStops={false} draftStops={draft} onMapClick={addStopAt} fitKey={`edit-${fitKey}`} height={420} />
          <span className="muted small">Grey lines are existing routes, for reference.</span>
        </div>
      </form>
    </Modal>
  );
}

export default function ManageRoutes() {
  const toast = useToast();
  const routesApi = useApi(() => routeService.getAllRoutes());
  const [editing, setEditing] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);
  const routes = useMemo(() => routesApi.data || [], [routesApi.data]);

  const table = useTable(routes, {
    columns: COLUMNS,
    searchText: (r) => `${routeLabel(r)} ${r.description || ''} ${r.stops.map((s) => s.name).join(' ')}`,
    initialSort: { key: 'route', dir: 'asc' },
  });

  const confirmDelete = async () => {
    setBusy(true);
    try {
      await routeService.deleteRoute(deleting.id);
      toast.success(`Route ${routeLabel(deleting)} deleted`);
      setDeleting(null);
      routesApi.reload({ silent: true });
    } catch (err) {
      toast.error(getErrorMessage(err));
      setDeleting(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Manage routes" description="Create routes with ordered stops. Buses follow the stops on the map."
        actions={<button className="btn btn-admin" onClick={() => setEditing('new')}><Plus size={16} /> Add route</button>} />

      <Card flush>
        <div className="card-body toolbar">
          <div className="input-icon" style={{ flex: '1 1 260px' }}>
            <Search size={15} />
            <input className="input" style={{ width: '100%' }} placeholder="Search routes or stops…" value={table.query} onChange={(e) => table.setQuery(e.target.value)} aria-label="Search routes" />
          </div>
        </div>
        {routesApi.error ? <ErrorState message={routesApi.error} onRetry={routesApi.reload} /> : routesApi.loading ? (
          <div className="card-body"><SkeletonRows rows={6} /></div>
        ) : table.total === 0 ? (
          <EmptyState icon={RouteIcon} title={routes.length ? 'No routes match' : 'No routes yet'}
            action={!routes.length && <button className="btn btn-admin" onClick={() => setEditing('new')}><Plus size={16} /> Add route</button>} />
        ) : (
          <>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <SortHeader table={table} k="route">Route</SortHeader>
                    <SortHeader table={table} k="distance">Distance</SortHeader>
                    <SortHeader table={table} k="stops">Stops</SortHeader>
                    <SortHeader table={table} k="buses">Buses</SortHeader>
                    <th>Description</th>
                    <th className="actions">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {table.rows.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <strong>{routeLabel(r)}</strong>
                        <div className="row wrap" style={{ gap: 4, marginTop: 2 }}>
                          {r.demoRoute && <span className="badge badge-sim" title="Fictional academic demo route, not an official KMT route">Demo route</span>}
                          {r.pathGeometry?.length >= 2
                            ? <span className="badge badge-neutral" title={r.pathSource || 'road geometry'}>Road geometry</span>
                            : r.stops.length >= 2 && <span className="badge badge-medium" title="The map draws dashed straight lines between the stops">Straight lines only</span>}
                        </div>
                      </td>
                      <td>{r.distance} km</td>
                      <td>{r.stops.length < 2 ? <span className="badge badge-medium">No map path</span> : r.stops.length}</td>
                      <td>{r.busCount}</td>
                      <td className="muted">{r.description || '—'}</td>
                      <td className="actions">
                        <button className="btn btn-ghost btn-sm btn-icon" onClick={() => setEditing(r)} aria-label={`Edit ${routeLabel(r)}`}><Pencil size={15} /></button>
                        <button className="btn btn-ghost btn-sm btn-icon" onClick={() => setDeleting(r)} aria-label={`Delete ${routeLabel(r)}`}><Trash2 size={15} color="var(--danger)" /></button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={table.page} totalPages={table.totalPages} totalElements={table.total} onChange={table.setPage} label="routes" />
          </>
        )}
      </Card>

      {editing && (
        <RouteForm initial={editing === 'new' ? null : editing}
          otherRoutes={routes.filter((r) => editing === 'new' || r.id !== editing.id)}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); routesApi.reload({ silent: true }); }} />
      )}
      {deleting && (
        <ConfirmDialog title={`Delete route ${routeLabel(deleting)}?`} busy={busy} onCancel={() => setDeleting(null)} onConfirm={confirmDelete}
          message={deleting.busCount > 0
            ? `This route still has ${deleting.busCount} bus(es) assigned, so the server will refuse to delete it. Reassign or delete those buses first.`
            : 'This permanently removes the route, its stops and its stored crowd predictions.'} />
      )}
    </>
  );
}
