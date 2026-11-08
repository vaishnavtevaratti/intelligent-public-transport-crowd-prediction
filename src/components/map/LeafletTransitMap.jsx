import { useEffect, useMemo } from 'react';
import { CircleMarker, MapContainer, Marker, Polyline, TileLayer, Tooltip, useMap, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { CROWD_META, KOLHAPUR_CENTER, KOLHAPUR_ZOOM, isStale, routeLine, routeLineLabel } from '../../utils/format';

const DEFAULT_CENTER = [KOLHAPUR_CENTER.lat, KOLHAPUR_CENTER.lng];
const iconCache = new Map();

/** Bus marker icon; cached so live position updates only move the marker. */
function busIcon(bus, selected, stale) {
  const color = bus.latestCrowdLevel ? CROWD_META[bus.latestCrowdLevel].hex : '#475569';
  const label = (bus.busNumber || '').replace(/^[A-Za-z]+-?/, '').slice(0, 4) || 'BUS';
  const key = `${color}|${label}|${selected}|${stale}`;
  if (!iconCache.has(key)) {
    iconCache.set(key, L.divIcon({
      className: 'bus-marker',
      html: `<div class="bus-pin${selected ? ' selected' : ''}${stale ? ' stale' : ''}" style="background:${color}">${label}</div>`,
      iconSize: [30, 30],
      iconAnchor: [15, 15],
    }));
  }
  return iconCache.get(key);
}

function FitToContent({ points, fitKey }) {
  const map = useMap();
  // Maps inside modals/hidden panels are measured before layout settles; re-measure once.
  useEffect(() => {
    const id = setTimeout(() => map.invalidateSize(), 250);
    return () => clearTimeout(id);
  }, [map]);
  useEffect(() => {
    if (points.length === 1) map.setView(points[0], 15);
    else if (points.length > 1) map.fitBounds(points, { padding: [40, 40], maxZoom: 15 });
    // Only refit when the caller asks (fitKey), not on every live position update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey, map]);
  return null;
}

function ClickHandler({ onMapClick }) {
  useMapEvents({ click: (e) => onMapClick?.(e.latlng.lat, e.latlng.lng) });
  return null;
}

export default function LeafletTransitMap({
  routes = [], buses = [], selectedBusId, onSelectBus, highlightRouteId, showStops = true,
  onMapClick, draftStops, fitKey, now, places = [],
}) {
  const fitPoints = useMemo(() => {
    if (draftStops?.length) return draftStops.map((s) => [s.latitude, s.longitude]);
    const pool = highlightRouteId ? routes.filter((r) => r.id === highlightRouteId) : routes;
    const points = pool.flatMap((r) => (r.stops || []).map((s) => [s.latitude, s.longitude]));
    if (points.length) return points;
    return buses.filter((b) => b.currentLatitude != null).map((b) => [b.currentLatitude, b.currentLongitude]);
  }, [routes, buses, highlightRouteId, draftStops]);

  return (
    <MapContainer center={DEFAULT_CENTER} zoom={KOLHAPUR_ZOOM} scrollWheelZoom style={{ height: '100%', width: '100%' }}>
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <FitToContent points={fitPoints} fitKey={fitKey} />
      {onMapClick && <ClickHandler onMapClick={onMapClick} />}

      {routes.map((route) => {
        const line = routeLine(route);
        if (line.points.length < 2) return null;
        const active = !highlightRouteId || highlightRouteId === route.id;
        return (
          <Polyline key={route.id} positions={line.points}
            pathOptions={{ color: active ? '#1d4ed8' : '#94a3b8', weight: active ? 4 : 2, opacity: active ? 0.75 : 0.35,
              dashArray: line.roads ? undefined : '6 8' }}>
            <Tooltip sticky>{routeLineLabel(route)}</Tooltip>
          </Polyline>
        );
      })}

      {showStops && routes
        .filter((r) => !highlightRouteId || r.id === highlightRouteId)
        .flatMap((r) => (r.stops || []).map((s) => (
          <CircleMarker key={`${r.id}-${s.stopOrder}-${s.name}`} center={[s.latitude, s.longitude]} radius={5}
            pathOptions={{ color: '#1d4ed8', weight: 2, fillColor: '#ffffff', fillOpacity: 1 }}>
            <Tooltip>{s.name}</Tooltip>
          </CircleMarker>
        )))}

      {draftStops?.length > 1 && (
        <Polyline positions={draftStops.map((s) => [s.latitude, s.longitude])} pathOptions={{ color: '#7c3aed', weight: 4, dashArray: '6 6' }} />
      )}
      {draftStops?.map((s, i) => (
        <CircleMarker key={`draft-${i}`} center={[s.latitude, s.longitude]} radius={7}
          pathOptions={{ color: '#7c3aed', weight: 3, fillColor: '#fff', fillOpacity: 1 }}>
          <Tooltip permanent direction="top" offset={[0, -6]}>{i + 1}. {s.name || 'Unnamed stop'}</Tooltip>
        </CircleMarker>
      ))}

      {places.map((p) => (
        <CircleMarker key={`place-${p.label}`} center={[p.latitude, p.longitude]} radius={10}
          pathOptions={{ color: p.kind === 'origin' ? '#0ca30c' : '#d03b3b', weight: 4, fillColor: '#fff', fillOpacity: 1 }}>
          <Tooltip permanent direction="top" offset={[0, -8]}>{p.kind === 'origin' ? 'From' : 'To'}: {p.label}</Tooltip>
        </CircleMarker>
      ))}

      {buses.filter((b) => b.currentLatitude != null && b.currentLongitude != null).map((bus) => (
        <Marker key={bus.id} position={[bus.currentLatitude, bus.currentLongitude]}
          icon={busIcon(bus, bus.id === selectedBusId, isStale(bus.lastUpdated, now))}
          eventHandlers={{ click: () => onSelectBus?.(bus.id) }}
          keyboard title={`Bus ${bus.busNumber}`} zIndexOffset={bus.id === selectedBusId ? 1000 : 0}>
          <Tooltip direction="top" offset={[0, -14]}>
            <strong>{bus.busNumber}</strong>
            {bus.latestCrowdLevel ? ` · ${CROWD_META[bus.latestCrowdLevel].label} crowd` : ''}
            {bus.subtitle && <div>{bus.subtitle}</div>}
          </Tooltip>
        </Marker>
      ))}
    </MapContainer>
  );
}
