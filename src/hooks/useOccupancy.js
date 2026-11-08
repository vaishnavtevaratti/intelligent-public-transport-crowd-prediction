import { useEffect, useState } from 'react';
import { occupancyService } from '../services/occupancyService';
import { getErrorMessage } from '../services/api';
import websocketService, { OCCUPANCY_TOPIC } from '../services/websocketService';

/** Occupancy of all buses, keyed by bus id, kept live from WebSocket /topic/occupancy. */
export function useOccupancy() {
  const [byBus, setByBus] = useState({});
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const load = () => occupancyService.getAll()
      .then((list) => {
        if (cancelled) return;
        setByBus(Object.fromEntries(list.map((o) => [o.busId, o])));
        setError(null);
      })
      .catch((err) => !cancelled && setError(getErrorMessage(err)))
      .finally(() => !cancelled && setLoading(false));
    load();
    // Re-read every 30 s so STALE / offline states appear even when no messages arrive.
    const timer = setInterval(load, 30000);
    const off = websocketService.subscribe(OCCUPANCY_TOPIC, (o) => setByBus((m) => ({ ...m, [o.busId]: o })));
    return () => { cancelled = true; clearInterval(timer); off(); };
  }, []);

  return { byBus, error, loading };
}
