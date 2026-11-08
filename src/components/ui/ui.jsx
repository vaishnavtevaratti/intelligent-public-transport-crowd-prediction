// Small reusable UI building blocks used by every page.
import { useEffect, useRef } from 'react';
import { AlertCircle, AlertTriangle, ArrowUpDown, ChevronLeft, ChevronRight, Inbox, RefreshCw, Wifi, WifiOff, X } from 'lucide-react';
import { CROWD_META } from '../../utils/format';

export function Card({ title, subtitle, actions, children, flush = false, className = '' }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="card-header">
          <div>
            {title && <h2>{title}</h2>}
            {subtitle && <p>{subtitle}</p>}
          </div>
          {actions && <div className="row wrap">{actions}</div>}
        </div>
      )}
      <div className={`card-body ${flush ? 'flush' : ''}`}>{children}</div>
    </section>
  );
}

export function PageHeader({ title, description, actions }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="row wrap">{actions}</div>}
    </div>
  );
}

export function StatCard({ icon: Icon, label, value, hint, tone }) {
  const toneStyle = tone === 'admin'
    ? { background: 'var(--admin-soft)', color: 'var(--admin)' }
    : tone === 'danger' ? { background: 'var(--danger-soft)', color: 'var(--danger)' } : undefined;
  return (
    <div className="card stat-card">
      {Icon && <div className="stat-icon" style={toneStyle}><Icon size={20} aria-hidden="true" /></div>}
      <div style={{ minWidth: 0 }}>
        <div className="stat-label">{label}</div>
        <div className="stat-value">{value}</div>
        {hint && <div className="stat-hint">{hint}</div>}
      </div>
    </div>
  );
}

/** Crowd level badge: colour + dot + text, so it never relies on colour alone. */
export function CrowdBadge({ level, confidence }) {
  if (!level) return <span className="badge badge-neutral">No prediction</span>;
  const meta = CROWD_META[level];
  return (
    <span className={`badge ${meta.className}`} title={`${meta.label} crowd: ${meta.advice}`}>
      <span className="badge-dot" aria-hidden="true" />
      {meta.label}
      {confidence != null && <span style={{ fontWeight: 500 }}>· {Math.round(confidence * 100)}%</span>}
    </span>
  );
}

export function BusStatusBadge({ status }) {
  const cls = status === 'ACTIVE' ? 'badge-low' : status === 'MAINTENANCE' ? 'badge-medium' : 'badge-neutral';
  const label = status ? status.charAt(0) + status.slice(1).toLowerCase() : 'Unknown';
  return <span className={`badge ${cls}`}><span className="badge-dot" aria-hidden="true" />{label}</span>;
}

export function Spinner({ label = 'Loading' }) {
  return <span className="spinner" role="status" aria-label={label} />;
}

export function Skeleton({ height = 16, width = '100%', style }) {
  return <div className="skeleton" style={{ height, width, ...style }} aria-hidden="true" />;
}

export function SkeletonRows({ rows = 4, height = 18 }) {
  return (
    <div className="stack" aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} height={height} width={`${90 - i * 8}%`} />)}
    </div>
  );
}

export function EmptyState({ icon: Icon = Inbox, title, message, action }) {
  return (
    <div className="state">
      <div className="state-icon"><Icon size={22} aria-hidden="true" /></div>
      <strong>{title}</strong>
      {message && <p className="muted" style={{ maxWidth: 420 }}>{message}</p>}
      {action}
    </div>
  );
}

export function ErrorState({ message, onRetry }) {
  return (
    <div className="state error" role="alert">
      <div className="state-icon"><AlertCircle size={22} aria-hidden="true" /></div>
      <strong>Could not load data</strong>
      <p className="muted" style={{ maxWidth: 460 }}>{message}</p>
      {onRetry && (
        <button className="btn btn-secondary btn-sm" onClick={onRetry}>
          <RefreshCw size={14} /> Try again
        </button>
      )}
    </div>
  );
}

export function Alert({ type = 'info', icon: Icon = AlertTriangle, children }) {
  return (
    <div className={`alert alert-${type}`} role={type === 'error' ? 'alert' : undefined}>
      <Icon size={16} aria-hidden="true" />
      <div>{children}</div>
    </div>
  );
}

export function Modal({ title, onClose, children, footer, wide = false }) {
  const ref = useRef(null);
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    ref.current?.querySelector('input, select, textarea, button')?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <div className="modal-header">
          <h2>{title}</h2>
          <button className="btn btn-ghost btn-icon btn-sm" onClick={onClose} aria-label="Close"><X size={16} /></button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
}

export function ConfirmDialog({ title, message, confirmLabel = 'Delete', busy = false, onConfirm, onCancel }) {
  return (
    <Modal
      title={title}
      onClose={onCancel}
      footer={(
        <>
          <button className="btn btn-secondary" onClick={onCancel} disabled={busy}>Cancel</button>
          <button className="btn btn-danger" onClick={onConfirm} disabled={busy}>
            {busy && <Spinner label="Working" />} {confirmLabel}
          </button>
        </>
      )}
    >
      <div className="row" style={{ alignItems: 'flex-start', gap: 12 }}>
        <div className="state-icon" style={{ background: 'var(--danger-soft)', color: 'var(--danger)', flexShrink: 0 }}>
          <AlertTriangle size={20} />
        </div>
        <div className="text-2">{message}</div>
      </div>
    </Modal>
  );
}

export function Pagination({ page, totalPages, totalElements, onChange, label = 'records' }) {
  if (totalElements === 0) return null;
  return (
    <div className="pagination">
      <span className="muted small">{totalElements} {label} · page {page + 1} of {Math.max(totalPages, 1)}</span>
      <div className="row">
        <button className="btn btn-secondary btn-sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
          <ChevronLeft size={14} /> Previous
        </button>
        <button className="btn btn-secondary btn-sm" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
          Next <ChevronRight size={14} />
        </button>
      </div>
    </div>
  );
}

const STATUS_TEXT = {
  live: 'Live updates',
  connecting: 'Connecting…',
  reconnecting: 'Reconnecting…',
  unauthorized: 'Live updates unavailable (log in again)',
  idle: 'Not connected',
};

export function ConnectionIndicator({ status }) {
  const live = status === 'live';
  return (
    <span className={`badge ${live ? 'badge-low' : status === 'connecting' ? 'badge-neutral' : 'badge-medium'}`} role="status">
      {live ? <span className="live-dot" aria-hidden="true" /> : status === 'connecting' ? <Wifi size={12} /> : <WifiOff size={12} />}
      {STATUS_TEXT[status] || status}
    </span>
  );
}

/** Explicit label so simulated positions are never presented as real GPS. */
export function SimulatedBadge() {
  return (
    <span className="badge badge-sim" title="Positions come from the built-in GPS simulator, not real devices">
      Simulated GPS
    </span>
  );
}

/** Clickable, accessible sort header for tables driven by useTable(). */
export function SortHeader({ table, k, children }) {
  const active = table.sort.key === k;
  return (
    <th aria-sort={active ? (table.sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button className="th-sort" onClick={() => table.toggleSort(k)}>{children} <ArrowUpDown size={12} /></button>
    </th>
  );
}
