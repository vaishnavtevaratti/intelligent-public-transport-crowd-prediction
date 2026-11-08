import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { authService, tokenExpiry } from '../services/authService';
import { AUTH_EXPIRED_EVENT } from '../services/api';
import websocketService from '../services/websocketService';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => authService.getCurrentUser());
  const [sessionMessage, setSessionMessage] = useState(null);

  const logout = useCallback((message = null) => {
    authService.logout();
    websocketService.reset();
    setUser(null);
    setSessionMessage(message);
  }, []);

  // Log out when the backend rejects the token, and when the token expiry time passes.
  useEffect(() => {
    const onExpired = () => logout('Your session has expired. Please log in again.');
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired);
    let timer;
    const expiry = user ? tokenExpiry(authService.getToken()) : null;
    if (expiry) timer = setTimeout(onExpired, Math.max(0, expiry - Date.now()));
    return () => {
      window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired);
      clearTimeout(timer);
    };
  }, [user, logout]);

  // Confirm the stored session with the backend once on load (also refreshes name and role).
  useEffect(() => {
    if (!authService.getToken()) return;
    authService.getProfile()
      .then((profile) => setUser((u) => (u ? { ...u, name: profile.name, role: profile.role } : u)))
      .catch(() => { /* a 401 triggers AUTH_EXPIRED_EVENT; network errors keep the cached user */ });
  }, []);

  const login = useCallback(async (email, password) => {
    const data = await authService.login(email, password);
    setSessionMessage(null);
    setUser({ id: data.id, name: data.name, email: data.email, role: data.role });
    return data;
  }, []);

  const register = useCallback(async (payload) => {
    const data = await authService.register(payload);
    setSessionMessage(null);
    setUser({ id: data.id, name: data.name, email: data.email, role: data.role });
    return data;
  }, []);

  const value = useMemo(() => ({
    user,
    isAdmin: user?.role === 'ADMIN',
    login,
    register,
    logout,
    sessionMessage,
  }), [user, login, register, logout, sessionMessage]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
