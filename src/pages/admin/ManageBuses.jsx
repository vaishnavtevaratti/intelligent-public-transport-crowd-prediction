import { useMemo, useState } from 'react';
import { Bus, Pencil, Plus, Search, Trash2 } from 'lucide-react';
import {
  Alert, BusStatusBadge, Card, ConfirmDialog, CrowdBadge, EmptyState, ErrorState, Modal, PageHeader, Pagination, SkeletonRows, SortHeader, Spinner,
} from '../../components/ui/ui';
import { useApi } from '../../hooks/useApi';
import { useTable } from '../../hooks/useTable';
import { useToast } from '../../context/ToastContext';
import { busService } from '../../services/busService';
import { routeService } from '../../services/routeService';
import { getErrorMessage, getFieldErrors } from '../../services/api';
import { BUS_STATUSES, routeLabel, timeAgo } from '../../utils/format';

const EMPTY_FORM = { busNumber: '', capacity: '50', seatCount: '', status: 'ACTIVE', routeId: '' };
const COLUMNS = {
  busNumber: (b) => b.busNumber,
  route: (b) => routeLabel(b),
  capacity: (b) => b.capacity,
  status: (b) => b.status,
  updated: (b) => b.lastUpdated || '',
};

function BusForm({ initial, routes, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState(initial
    ? { busNumber: initial.busNumber, capacity: String(initial.capacity ?? ''), seatCount: initial.seatCount != null ? String(initial.seatCount) : '', status: initial.status || 'ACTIVE', routeId: initial.routeId ? String(initial.routeId) : '' }
    : EMPTY_FORM);
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState(null);
  const [saving, setSaving] = useState(false);

  const update = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  const validate = () => {
    const e = {};
    if (!/^[A-Za-z0-9][A-Za-z0-9 -]{1,19}$/.test(form.busNumber.trim())) e.busNumber = '2-20 letters, digits, spaces or dashes';
    const cap = Number(form.capacity);
    if (!Number.isInteger(cap) || cap < 1 || cap > 200) e.capacity = 'Whole number between 1 and 200';
    if (form.seatCount !== '') {
      const seats = Number(form.seatCount);
      if (!Number.isInteger(seats) || seats < 1 || seats > 200) e.seatCount = 'Whole number between 1 and 200';
      else if (seats > cap) e.seatCount = 'Cannot exceed total passenger capacity';
    }
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const save = async (event) => {
    event.preventDefault();
    setServerError(null);
    if (!validate()) return;
    setSaving(true);
    const payload = {
      busNumber: form.busNumber.trim(),
      capacity: Number(form.capacity),
      seatCount: form.seatCount === '' ? null : Number(form.seatCount),
      status: form.status,
      routeId: form.routeId ? Number(form.routeId) : null,
    };
    try {
      const saved = initial ? await busService.updateBus(initial.id, payload) : await busService.createBus(payload);
      toast.success(`Bus ${saved.busNumber} ${initial ? 'updated' : 'created'}`);
      onSaved();
    } catch (err) {
      setErrors(getFieldErrors(err));
      setServerError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={initial ? `Edit bus ${initial.busNumber}` : 'Add bus'} onClose={onClose}
      footer={(
        <>
          <button className="btn btn-secondary" onClick={onClose} disabled={saving}>Cancel</button>
          <button className="btn btn-primary" form="bus-form" type="submit" disabled={saving}>{saving && <Spinner />} Save bus</button>
        </>
      )}>
      <form id="bus-form" className="form-grid" onSubmit={save} noValidate>
        {serverError && <div className="full"><Alert type="error">{serverError}</Alert></div>}
        <div className="field">
          <label htmlFor="busNumber">Bus number *</label>
          <input id="busNumber" className={`input ${errors.busNumber ? 'invalid' : ''}`} value={form.busNumber} onChange={update('busNumber')} placeholder="e.g. BUS-111" />
          {errors.busNumber && <span className="field-error">{errors.busNumber}</span>}
        </div>
        <div className="field">
          <label htmlFor="capacity">Total passenger capacity *</label>
          <input id="capacity" type="number" min="1" max="200" className={`input ${errors.capacity ? 'invalid' : ''}`} value={form.capacity} onChange={update('capacity')} />
          {errors.capacity ? <span className="field-error">{errors.capacity}</span> : <span className="field-hint">Seated + permitted standing passengers.</span>}
        </div>
        <div className="field">
          <label htmlFor="seatCount">Number of seats</label>
          <input id="seatCount" type="number" min="1" max="200" className={`input ${errors.seatCount ? 'invalid' : ''}`} value={form.seatCount} onChange={update('seatCount')} placeholder="optional" />
          {errors.seatCount ? <span className="field-error">{errors.seatCount}</span> : <span className="field-hint">Separate from capacity; needed for seat availability.</span>}
        </div>
        <div className="field">
          <label htmlFor="status">Status</label>
          <select id="status" className="select" value={form.status} onChange={update('status')}>
            {BUS_STATUSES.map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
          </select>
          <span className="field-hint">Only active buses move and get scheduled predictions.</span>
        </div>
        <div className="field">
          <label htmlFor="routeId">Route</label>
          <select id="routeId" className="select" value={form.routeId} onChange={update('routeId')}>
            <option value="">Not assigned</option>
            {routes.map((r) => <option key={r.id} value={r.id}>{routeLabel(r)}{r.stops.length < 2 ? ' (no stops)' : ''}</option>)}
          </select>
          <span className="field-hint">A new bus starts at the first stop of its route.</span>
        </div>
      </form>
    </Modal>
  );
}

export default function ManageBuses() {
  const toast = useToast();
  const busesApi = useApi(() => busService.getAllBuses());
  const routesApi = useApi(() => routeService.getAllRoutes());
  const [statusFilter, setStatusFilter] = useState('');
  const [routeFilter, setRouteFilter] = useState('');
  const [editing, setEditing] = useState(null); // null | 'new' | bus
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);

  const filtered = useMemo(() => (busesApi.data || []).filter((b) =>
    (!statusFilter || b.status === statusFilter) && (!routeFilter || String(b.routeId ?? 'none') === routeFilter)),
  [busesApi.data, statusFilter, routeFilter]);

  const table = useTable(filtered, {
    columns: COLUMNS,
    searchText: (b) => `${b.busNumber} ${routeLabel(b)}`,
    initialSort: { key: 'busNumber', dir: 'asc' },
  });

  const confirmDelete = async () => {
    setBusy(true);
    try {
      await busService.deleteBus(deleting.id);
      toast.success(`Bus ${deleting.busNumber} deleted`);
      setDeleting(null);
      busesApi.reload({ silent: true });
    } catch (err) {
      toast.error(getErrorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Manage buses" description="Add buses, assign them to routes and change their status."
        actions={<button className="btn btn-admin" onClick={() => setEditing('new')} disabled={routesApi.loading}><Plus size={16} /> Add bus</button>} />

      <Card flush>
        <div className="card-body toolbar">
          <div className="input-icon" style={{ flex: '1 1 220px' }}>
            <Search size={15} />
            <input className="input" style={{ width: '100%' }} placeholder="Search by bus number or route…" value={table.query} onChange={(e) => table.setQuery(e.target.value)} aria-label="Search buses" />
          </div>
          <select className="select" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} aria-label="Filter by status">
            <option value="">All statuses</option>
            {BUS_STATUSES.map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
          </select>
          <select className="select" value={routeFilter} onChange={(e) => setRouteFilter(e.target.value)} aria-label="Filter by route">
            <option value="">All routes</option>
            <option value="none">Not assigned</option>
            {(routesApi.data || []).map((r) => <option key={r.id} value={r.id}>{routeLabel(r)}</option>)}
          </select>
        </div>

        {busesApi.error ? <ErrorState message={busesApi.error} onRetry={busesApi.reload} /> : busesApi.loading ? (
          <div className="card-body"><SkeletonRows rows={6} /></div>
        ) : table.total === 0 ? (
          <EmptyState icon={Bus} title={busesApi.data.length ? 'No buses match' : 'No buses yet'}
            message={busesApi.data.length ? 'Change the search or filters.' : 'Add the first bus to start tracking.'}
            action={!busesApi.data.length && <button className="btn btn-admin" onClick={() => setEditing('new')}><Plus size={16} /> Add bus</button>} />
        ) : (
          <>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <SortHeader table={table} k="busNumber">Bus</SortHeader>
                    <SortHeader table={table} k="route">Route</SortHeader>
                    <SortHeader table={table} k="capacity">Capacity / seats</SortHeader>
                    <SortHeader table={table} k="status">Status</SortHeader>
                    <th>Crowd</th>
                    <SortHeader table={table} k="updated">Last position</SortHeader>
                    <th className="actions">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {table.rows.map((b) => (
                    <tr key={b.id}>
                      <td><strong>{b.busNumber}</strong></td>
                      <td>{b.routeId ? routeLabel(b) : <span className="muted">Not assigned</span>}</td>
                      <td>{b.capacity} / {b.seatCount ?? <span className="muted">—</span>}</td>
                      <td><BusStatusBadge status={b.status} /></td>
                      <td><CrowdBadge level={b.latestCrowdLevel} /></td>
                      <td className="muted nowrap">{timeAgo(b.lastUpdated)}</td>
                      <td className="actions">
                        <button className="btn btn-ghost btn-sm btn-icon" onClick={() => setEditing(b)} aria-label={`Edit ${b.busNumber}`}><Pencil size={15} /></button>
                        <button className="btn btn-ghost btn-sm btn-icon" onClick={() => setDeleting(b)} aria-label={`Delete ${b.busNumber}`}><Trash2 size={15} color="var(--danger)" /></button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={table.page} totalPages={table.totalPages} totalElements={table.total} onChange={table.setPage} label="buses" />
          </>
        )}
      </Card>

      {editing && (
        <BusForm initial={editing === 'new' ? null : editing} routes={routesApi.data || []}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); busesApi.reload({ silent: true }); }} />
      )}
      {deleting && (
        <ConfirmDialog title={`Delete bus ${deleting.busNumber}?`} busy={busy} onCancel={() => setDeleting(null)} onConfirm={confirmDelete}
          message="This permanently removes the bus together with its location history and crowd predictions. This cannot be undone." />
      )}
    </>
  );
}
