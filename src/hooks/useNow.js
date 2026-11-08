import { useEffect, useState } from 'react';

/** Current time, refreshed every `intervalMs` (for "updated 5s ago" labels and staleness). */
export function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}
