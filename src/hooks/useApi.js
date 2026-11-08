import { useCallback, useEffect, useRef, useState } from 'react';
import { getErrorMessage } from '../services/api';

/**
 * Runs an async loader and tracks { data, loading, error }. reload() runs it again.
 * The loader re-runs whenever a value in `deps` changes; `loading` is true until the
 * request for the current deps has finished.
 */
export function useApi(loader, deps = []) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [settledKey, setSettledKey] = useState(null);
  const [reloading, setReloading] = useState(false);
  const loaderRef = useRef(loader);
  const requestId = useRef(0);
  const key = JSON.stringify(deps);

  useEffect(() => {
    loaderRef.current = loader;
  });

  const run = useCallback((runKey) => {
    const id = ++requestId.current;
    return Promise.resolve()
      .then(() => loaderRef.current())
      .then((result) => {
        if (id !== requestId.current) return result;
        setData(result);
        setError(null);
        return result;
      })
      .catch((err) => {
        if (id === requestId.current) setError(getErrorMessage(err));
        return null;
      })
      .finally(() => {
        if (id === requestId.current) {
          setSettledKey(runKey);
          setReloading(false);
        }
      });
  }, []);

  useEffect(() => {
    run(key);
  }, [run, key]);

  /** Re-fetch. With { silent: true } the current data stays on screen without a loading state. */
  const reload = useCallback(({ silent = false } = {}) => {
    if (!silent) setReloading(true);
    return run(key);
  }, [run, key]);

  return { data, setData, loading: settledKey !== key || reloading, error, reload };
}
