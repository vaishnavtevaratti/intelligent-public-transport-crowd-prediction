// Shared display helpers.

/** Levels of the time-and-route model; live occupancy and the 15-minute forecast add FULL. */
export const CROWD_LEVELS = ['LOW', 'MEDIUM', 'HIGH'];
export const OCCUPANCY_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'FULL'];

export const CROWD_META = {
  LOW: { label: 'Low', className: 'badge-low', color: 'var(--crowd-low)', hex: '#0ca30c', advice: 'Plenty of room expected.' },
  MEDIUM: { label: 'Medium', className: 'badge-medium', color: 'var(--crowd-medium)', hex: '#fab219', advice: 'Moderately busy; standing room likely.' },
  HIGH: { label: 'High', className: 'badge-high', color: 'var(--crowd-high)', hex: '#ec835a', advice: 'Very crowded; consider another time or bus.' },
  FULL: { label: 'Full', className: 'badge-full', color: 'var(--crowd-full)', hex: '#d03b3b', advice: 'At or above total passenger capacity.' },
};

export const BUS_STATUSES = ['ACTIVE', 'INACTIVE', 'MAINTENANCE'];

/** A position older than this is shown as stale on the map. */
export const STALE_AFTER_MS = 30000;

export function parseDate(value) {
  if (!value) return null;
  const d = value instanceof Date ? value : new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function formatDateTime(value) {
  const d = parseDate(value);
  return d ? d.toLocaleString(undefined, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—';
}

export function formatTime(value) {
  const d = parseDate(value);
  return d ? d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—';
}

export function timeAgo(value, now = Date.now()) {
  const d = parseDate(value);
  if (!d) return 'never';
  const seconds = Math.max(0, Math.round((now - d.getTime()) / 1000));
  if (seconds < 5) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return `${Math.round(hours / 24)} d ago`;
}

export function isStale(value, now = Date.now()) {
  const d = parseDate(value);
  return !d || now - d.getTime() > STALE_AFTER_MS;
}

export function hourLabel(hour) {
  const h = Number(hour);
  const suffix = h < 12 ? 'AM' : 'PM';
  const display = h % 12 === 0 ? 12 : h % 12;
  return `${display} ${suffix}`;
}

export function percent(value, digits = 0) {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toFixed(digits)}%`;
}

/** Default map view: Kolhapur Central Bus Stand area (the demo network is in Kolhapur, Maharashtra). */
export const KOLHAPUR_CENTER = { lat: 16.7033, lng: 74.2433 };
export const KOLHAPUR_ZOOM = 13;

/**
 * Line to draw for a route: its stored road geometry (OpenStreetMap roads) when available, otherwise straight
 * lines between the stops — which the maps draw dashed and label, so they are never mistaken for roads.
 */
export function routeLine(route) {
  if (route?.pathGeometry?.length >= 2) return { points: route.pathGeometry, roads: true };
  const stops = route?.stops || [];
  return { points: stops.map((s) => [s.latitude, s.longitude]), roads: false };
}

export function routeLineLabel(route) {
  const name = `${route.routeCode ? `${route.routeCode} · ` : ''}${route.source} → ${route.destination}`;
  const kind = routeLine(route).roads ? `roads: ${route.pathSource || 'road geometry'}` : 'straight lines between stops (no road geometry)';
  return `${name}${route.demoRoute ? ' · demo route' : ''} · ${kind}`;
}

export function routeLabel(route) {
  if (!route) return 'Unassigned';
  // Bus and prediction DTOs carry routeSource/routeDestination (a prediction's own
  // "source" field means ON_DEMAND/SCHEDULED, not the route's first stop).
  const source = route.routeSource !== undefined ? route.routeSource : route.source;
  const destination = route.routeDestination !== undefined ? route.routeDestination : route.destination;
  const code = route.routeCode ? `${route.routeCode} · ` : '';
  return source && destination ? `${code}${source} → ${destination}` : 'Unassigned';
}

/** Local 'YYYY-MM-DDTHH:mm' string for <input type="datetime-local"> and the API. */
export function toLocalInputValue(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** One-line summary for lists and map tooltips. */
export function occupancySummary(o) {
  if (!o || o.estimatedOnboard == null) return o?.dataStatus === 'NO_SENSOR' ? 'No passenger sensor' : 'No passenger data yet';
  const prefix = o.onboardIsLowerBound ? '≥' : '~';
  return `${prefix}${o.estimatedOnboard}/${o.capacity} onboard · ${o.availableCapacity} places left${o.dataStatus === 'STALE' ? ' (stale)' : ''}`;
}
