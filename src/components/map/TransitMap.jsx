import { lazy, Suspense, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Spinner } from '../ui/ui';

const LeafletTransitMap = lazy(() => import('./LeafletTransitMap'));
const GoogleTransitMap = lazy(() => import('./GoogleTransitMap'));

const PROVIDER = (import.meta.env.VITE_MAP_PROVIDER || 'osm').toLowerCase();
const GOOGLE_KEY = import.meta.env.VITE_GOOGLE_MAPS_API_KEY || '';

/**
 * Map with routes, stops and live bus markers.
 * Provider: OpenStreetMap + Leaflet by default (free, no key). Set VITE_MAP_PROVIDER=google and
 * VITE_GOOGLE_MAPS_API_KEY to use the Google Maps JavaScript API instead. If Google is requested
 * but not configured or the key is rejected, the map falls back to OpenStreetMap and says so.
 */
export default function TransitMap({ height, className = '', overlay, ...props }) {
  const [googleError, setGoogleError] = useState(null);
  const wantsGoogle = PROVIDER === 'google';
  const useGoogle = wantsGoogle && GOOGLE_KEY && !googleError;

  let notice = null;
  if (wantsGoogle && !GOOGLE_KEY) notice = 'Google Maps selected but VITE_GOOGLE_MAPS_API_KEY is not set - showing OpenStreetMap.';
  else if (googleError) notice = `${googleError} Showing OpenStreetMap instead.`;

  return (
    <div className={`map-frame ${className}`} style={height ? { height } : undefined}>
      <Suspense fallback={<div className="state"><Spinner label="Loading map" /> Loading map…</div>}>
        {useGoogle
          ? <GoogleTransitMap apiKey={GOOGLE_KEY} onFailure={setGoogleError} {...props} />
          : <LeafletTransitMap {...props} />}
      </Suspense>
      {overlay && <div className="map-overlay top-left">{overlay}</div>}
      <div className="map-overlay top-right">
        <span className="map-chip" title={useGoogle ? 'Google Maps JavaScript API' : 'OpenStreetMap tiles with Leaflet (no API key needed)'}>
          Map: {useGoogle ? 'Google Maps' : 'OpenStreetMap'}
        </span>
      </div>
      {notice && (
        <div className="map-overlay bottom-left" style={{ right: 10 }}>
          <span className="map-chip" role="status"><AlertTriangle size={13} color="var(--warning-text)" /> {notice}</span>
        </div>
      )}
    </div>
  );
}
