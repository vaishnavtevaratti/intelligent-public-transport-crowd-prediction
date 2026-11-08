import { useEffect, useState } from 'react';

function readSaved() {
  try {
    return localStorage.getItem('theme');
  } catch {
    return null;
  }
}

/** 'light' | 'dark', remembered per browser. Defaults to light. */
export function useTheme() {
  const [theme, setTheme] = useState(() => readSaved() || 'light');
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    try {
      localStorage.setItem('theme', theme);
    } catch {
      /* storage unavailable: the theme still applies for this visit */
    }
  }, [theme]);
  return [theme, () => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))];
}
