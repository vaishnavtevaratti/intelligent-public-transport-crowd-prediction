import { useEffect, useMemo, useRef, useState } from 'react';
import { CROWD_META, KOLHAPUR_CENTER, KOLHAPUR_ZOOM, isStale, routeLine, routeLineLabel } from '../../utils/format';

// Google Maps JavaScript API provider. Used only when VITE_MAP_PROVIDER=google and
// VITE_GOOGLE_MAPS_API_KEY are set. Same props as LeafletTransitMap.

let loaderPromise = null;
const AUTH_ERROR = 'Google Maps rejected the API key (check the key, billing and HTTP referrer restrictions).';
// Google reports a bad key by calling window.gm_authFailure AFTER the script has loaded,
// so every mounted map is told about it (and maps mounted later learn it immediately).
let authFailed = false;
const authListeners = new Set();
window.gm_authFailure = () => {
  authFailed = true;
  authListeners.forEach((fn) => fn(AUTH_ERROR));
};

function loadGoogleMaps(apiKey) {
  if (window.google?.maps) return Promise.resolve(window.google.maps);
  if (loaderPromise) return loaderPromise;
  loaderPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(apiKey)}&v=weekly`;
    script.async = true;
    script.onload = () => (window.google?.maps ? resolve(window.google.maps) : reject(new Error('Google Maps failed to initialise.')));
    script.onerror = () => {
      loaderPromise = null;   // allow a later retry instead of caching the failure
      script.remove();
      reject(new Error('Could not download the Google Maps script (network blocked?).'));
    };
    document.head.appendChild(script);
  });
  return loaderPromise;
}

export default function GoogleTransitMap({
  apiKey, onFailure, routes = [], buses = [], selectedBusId, onSelectBus, highlightRouteId,
  showStops = true, onMapClick, draftStops, fitKey, places, now,
}) {
  // Redraw routes/stops only when their CONTENT changes. Parent components pass new array objects
  // on every render (every live update), which would otherwise recreate all overlays each time.
  const overlayKey = useMemo(() => JSON.stringify({
    // geometry is summarised (point count + ends) to keep this cheap on every live update
    routes: routes.map((r) => [r.id, (r.stops || []).map((st) => [st.latitude, st.longitude, st.name]),
      r.pathGeometry ? [r.pathGeometry.length, r.pathGeometry[0], r.pathGeometry[r.pathGeometry.length - 1]] : null]),
    highlightRouteId, showStops, draftStops, places,
  }), [routes, highlightRouteId, showStops, draftStops, places]);
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const overlaysRef = useRef([]);
  const markersRef = useRef(new Map());
  const [maps, setMaps] = useState(null);
  const onFailureRef = useRef(onFailure);
  const onSelectRef = useRef(onSelectBus);
  const onClickRef = useRef(onMapClick);
  useEffect(() => {
    onFailureRef.current = onFailure;
    onSelectRef.current = onSelectBus;
    onClickRef.current = onMapClick;
  });

  useEffect(() => {
    const notify = (message) => onFailureRef.current?.(message);
    if (authFailed) {
      notify(AUTH_ERROR);
      return undefined;
    }
    authListeners.add(notify);
    return () => authListeners.delete(notify);
  }, []);

  useEffect(() => {
    let cancelled = false;
    loadGoogleMaps(apiKey)
      .then((g) => {
        if (cancelled || !containerRef.current) return;
        mapRef.current = new g.Map(containerRef.current, { center: KOLHAPUR_CENTER, zoom: KOLHAPUR_ZOOM, streetViewControl: false, mapTypeControl: false });
        mapRef.current.addListener('click', (e) => onClickRef.current?.(e.latLng.lat(), e.latLng.lng()));
        setMaps(g);
      })
      .catch((err) => onFailureRef.current?.(err.message));
    return () => { cancelled = true; };
  }, [apiKey]);

  // Release everything this map created when it unmounts (no leaked markers or listeners).
  useEffect(() => () => {
    overlaysRef.current.forEach((o) => o.setMap(null));
    overlaysRef.current = [];
    markersRef.current.forEach((m) => m.setMap(null));
    markersRef.current.clear();
    if (mapRef.current && window.google?.maps?.event) window.google.maps.event.clearInstanceListeners(mapRef.current);
    mapRef.current = null;
  }, []);

  // Routes, stops and draft stops (redrawn when they change).
  useEffect(() => {
    if (!maps || !mapRef.current) return;
    overlaysRef.current.forEach((o) => o.setMap(null));
    overlaysRef.current = [];
    const map = mapRef.current;
    routes.forEach((route) => {
      const stops = route.stops || [];
      const line = routeLine(route);
      if (line.points.length < 2) return;
      const active = !highlightRouteId || highlightRouteId === route.id;
      const opacity = active ? 0.75 : 0.35;
      // road geometry is drawn solid; straight lines between stops are dashed (they are not roads)
      overlaysRef.current.push(new maps.Polyline({
        map, path: line.points.map(([lat, lng]) => ({ lat, lng })),
        strokeColor: active ? '#1d4ed8' : '#94a3b8', strokeWeight: active ? 4 : 2,
        strokeOpacity: line.roads ? opacity : 0,
        icons: line.roads ? undefined : [{ icon: { path: 'M 0,-1 0,1', strokeOpacity: opacity, scale: 3 }, offset: '0', repeat: '14px' }],
        title: routeLineLabel(route),
      }));
      if (showStops && active) {
        stops.forEach((s) => overlaysRef.current.push(new maps.Marker({
          map, position: { lat: s.latitude, lng: s.longitude }, title: s.name,
          icon: { path: maps.SymbolPath.CIRCLE, scale: 5, fillColor: '#fff', fillOpacity: 1, strokeColor: '#1d4ed8', strokeWeight: 2 },
        })));
      }
    });
    (places || []).forEach((p) => overlaysRef.current.push(new maps.Marker({
      map, position: { lat: p.latitude, lng: p.longitude }, title: `${p.kind === 'origin' ? 'From' : 'To'}: ${p.label}`,
      label: p.kind === 'origin' ? 'A' : 'B',
    })));
    (draftStops || []).forEach((s, i) => overlaysRef.current.push(new maps.Marker({
      map, position: { lat: s.latitude, lng: s.longitude }, label: String(i + 1), title: s.name,
    })));
    if (draftStops?.length > 1) {
      overlaysRef.current.push(new maps.Polyline({ map, path: draftStops.map((s) => ({ lat: s.latitude, lng: s.longitude })), strokeColor: '#7c3aed', strokeWeight: 4 }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [maps, overlayKey]);

  // Bus markers: created once per bus, then moved in place.
  useEffect(() => {
    if (!maps || !mapRef.current) return;
    const seen = new Set();
    buses.filter((b) => b.currentLatitude != null).forEach((bus) => {
      seen.add(bus.id);
      const color = bus.latestCrowdLevel ? CROWD_META[bus.latestCrowdLevel].hex : '#475569';
      // Same meaning as the OpenStreetMap markers: colour = crowd level, faded = no update for 30 s.
      const stale = isStale(bus.lastUpdated, now);
      const icon = { path: maps.SymbolPath.CIRCLE, scale: bus.id === selectedBusId ? 11 : 9, fillColor: color,
        fillOpacity: stale ? 0.45 : 1, strokeColor: '#fff', strokeWeight: 2 };
      const position = { lat: bus.currentLatitude, lng: bus.currentLongitude };
      const title = [bus.busNumber, bus.latestCrowdLevel && `${CROWD_META[bus.latestCrowdLevel].label} crowd`, bus.subtitle]
        .filter(Boolean).join(' · ');
      let marker = markersRef.current.get(bus.id);
      if (!marker) {
        marker = new maps.Marker({ map: mapRef.current, position, icon, title });
        marker.addListener('click', () => onSelectRef.current?.(bus.id));
        markersRef.current.set(bus.id, marker);
      } else {
        marker.setPosition(position);
        marker.setIcon(icon);
        marker.setTitle(title);
      }
    });
    markersRef.current.forEach((marker, id) => {
      if (!seen.has(id)) { marker.setMap(null); markersRef.current.delete(id); }
    });
  }, [maps, buses, selectedBusId, now]);

  // Fit to content when asked.
  useEffect(() => {
    if (!maps || !mapRef.current) return;
    const bounds = new maps.LatLngBounds();
    let count = 0;
    const pool = draftStops?.length ? [{ stops: draftStops }] : routes.filter((r) => !highlightRouteId || r.id === highlightRouteId);
    pool.forEach((r) => (r.stops || []).forEach((s) => { bounds.extend({ lat: s.latitude, lng: s.longitude }); count++; }));
    if (count > 0) mapRef.current.fitBounds(bounds, 40);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [maps, fitKey]);

  return <div ref={containerRef} style={{ width: '100%', height: '100%' }} />;
}
