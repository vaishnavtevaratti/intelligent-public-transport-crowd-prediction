import { useEffect, useState } from 'react';
import { busService } from '../services/busService';
import { routeService } from '../services/routeService';
import { getErrorMessage } from '../services/api';
import websocketService, { CROWD_TOPIC, LOCATION_TOPIC } from '../services/websocketService';

/**
 * Loads buses and routes from the REST API, then keeps bus positions and crowd levels
 * up to date from the WebSocket topics. Markers update in place; nothing reloads the page.
 */
export function useLiveBuses() {
  const [buses, setBuses] = useState([]);
  const [routes, setRoutes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [status, setStatus] = useState(websocketService.status);
  const [lastMessageAt, setLastMessageAt] = useState(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    Promise.all([busService.getAllBuses(), routeService.getAllRoutes()])
      .then(([busData, routeData]) => {
        if (cancelled) return;
        setBuses(busData);
        setRoutes(routeData);
        setError(null);
      })
      .catch((err) => !cancelled && setError(getErrorMessage(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, [reloadKey]);

  useEffect(() => {
    const offStatus = websocketService.onStatus(setStatus);
    const offLocation = websocketService.subscribe(LOCATION_TOPIC, (update) => {
      setLastMessageAt(Date.now());
      setBuses((list) => list.map((bus) => (bus.id === update.busId
        ? {
          ...bus,
          currentLatitude: update.latitude,
          currentLongitude: update.longitude,
          lastUpdated: update.timestamp,
          speedKmph: update.speedKmph,
          heading: update.heading,
          nextStop: update.nextStop,
          locationSource: update.source,
        }
        : bus)));
    });
    const offCrowd = websocketService.subscribe(CROWD_TOPIC, (prediction) => {
      setBuses((list) => list.map((bus) => (bus.id === prediction.busId
        ? { ...bus, latestCrowdLevel: prediction.crowdLevel, latestCrowdTime: prediction.timestamp }
        : bus)));
    });
    return () => { offStatus(); offLocation(); offCrowd(); };
  }, []);

  const reload = () => {
    setLoading(true);
    setReloadKey((k) => k + 1);
  };

  return { buses, routes, loading, error, status, lastMessageAt, reload };
}
