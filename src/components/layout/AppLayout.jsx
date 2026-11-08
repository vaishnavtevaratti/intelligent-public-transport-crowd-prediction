import { useEffect, useRef, useState } from 'react';
import { NavLink, Navigate, Outlet, useLocation } from 'react-router-dom';
import {
  AlertTriangle, Bell, Brain, Bus, Camera, History, LayoutDashboard, LogOut, MapPinned, Menu, Moon,
  Route as RouteIcon, ShieldCheck, Sun, Users, Navigation,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../hooks/useTheme';
import { crowdService } from '../../services/crowdService';
import websocketService, { CROWD_TOPIC } from '../../services/websocketService';
import { formatTime, routeLabel } from '../../utils/format';

const USER_NAV = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/tracking', label: 'Live Tracking', icon: MapPinned },
  { to: '/crowd', label: 'Crowd Prediction', icon: Brain },
  { to: '/travel', label: 'Travel Planner', icon: Navigation },
];

const ADMIN_NAV = [
  { to: '/admin', label: 'Admin Overview', icon: ShieldCheck, end: true },
  { to: '/admin/buses', label: 'Manage Buses', icon: Bus },
  { to: '/admin/routes', label: 'Manage Routes', icon: RouteIcon },
  { to: '/admin/occupancy', label: 'Occupancy & Sensors', icon: Camera },
  { to: '/admin/users', label: 'Users', icon: Users },
  { to: '/admin/predictions', label: 'Prediction History', icon: History },
];

const TITLES = {
  '/dashboard': 'Dashboard', '/tracking': 'Live Bus Tracking', '/crowd': 'Crowd Prediction',
  '/travel': 'Travel Planner', '/admin': 'Admin Overview', '/admin/buses': 'Manage Buses',
  '/admin/routes': 'Manage Routes', '/admin/users': 'User Management', '/admin/occupancy': 'Occupancy & Sensors', '/admin/predictions': 'Prediction History',
};

/** High-crowd alerts from the backend (last hour), refreshed every minute and on new predictions. */
function AlertsMenu() {
  const [open, setOpen] = useState(false);
  const [alerts, setAlerts] = useState([]);
  const [error, setError] = useState(false);
  const ref = useRef(null);

  useEffect(() => {
    const load = () => crowdService.getAlerts().then((a) => { setAlerts(a); setError(false); }).catch(() => setError(true));
    load();
    const id = setInterval(load, 60000);
    const off = websocketService.subscribe(CROWD_TOPIC, (p) => p.crowdLevel === 'HIGH' && load());
    return () => { clearInterval(id); off(); };
  }, []);

  useEffect(() => {
    const close = (e) => ref.current && !ref.current.contains(e.target) && setOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  return (
    <div className="dropdown" ref={ref}>
      <button className="btn btn-ghost btn-icon" style={{ position: 'relative' }} onClick={() => setOpen((o) => !o)}
        aria-label={`Crowd alerts (${alerts.length})`} aria-expanded={open}>
        <Bell size={18} />
        {alerts.length > 0 && <span className="count-dot">{alerts.length}</span>}
      </button>
      {open && (
        <div className="dropdown-menu">
          <div className="dropdown-item" style={{ fontWeight: 600 }}>High crowd alerts · last hour</div>
          {error && <div className="dropdown-item muted">Alerts could not be loaded.</div>}
          {!error && alerts.length === 0 && <div className="dropdown-item muted">No buses are predicted to be highly crowded right now.</div>}
          {alerts.map((a) => (
            <div key={a.id} className="dropdown-item">
              <AlertTriangle size={16} color="var(--crowd-high)" style={{ flexShrink: 0, marginTop: 2 }} />
              <div>
                <strong>{a.busNumber}</strong> predicted <strong>High</strong> crowd
                <div className="muted small">{routeLabel(a)} · {formatTime(a.timestamp)}</div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default function AppLayout() {
  const { user, isAdmin, logout } = useAuth();
  const [theme, toggleTheme] = useTheme();
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();

  const title = TITLES[location.pathname] || 'OptiTransit';
  const initials = (user?.name || '?').split(' ').map((p) => p[0]).join('').slice(0, 2).toUpperCase();

  return (
    <div className="app-shell">
      <aside className={`sidebar ${menuOpen ? 'open' : ''}`} aria-label="Main navigation">
        <div className="sidebar-brand">
          <div className="brand-mark"><Bus size={18} /></div>
          OptiTransit
        </div>
        <nav>
          <div className="nav-section">Passenger</div>
          {USER_NAV.map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} className="nav-link" onClick={() => setMenuOpen(false)}><Icon size={18} />{label}</NavLink>
          ))}
          {isAdmin && (
            <>
              <div className="nav-section" style={{ color: '#a78bfa' }}>Administration</div>
              {ADMIN_NAV.map(({ to, label, icon: Icon, end }) => (
                <NavLink key={to} to={to} end={end} className="nav-link admin" onClick={() => setMenuOpen(false)}><Icon size={18} />{label}</NavLink>
              ))}
            </>
          )}
        </nav>
        <div className="sidebar-footer">Intelligent Public Transport &amp; Crowd Prediction System</div>
      </aside>
      {menuOpen && <div className="backdrop" onClick={() => setMenuOpen(false)} />}

      <div className="main-area">
        <header className="topbar">
          <button className="btn btn-ghost btn-icon mobile-only" onClick={() => setMenuOpen(true)} aria-label="Open menu">
            <Menu size={20} />
          </button>
          <div className="topbar-title">{title}</div>
          <AlertsMenu />
          <button className="btn btn-ghost btn-icon" onClick={toggleTheme} aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}>
            {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
          </button>
          <div className="row" style={{ gap: 10 }}>
            <div className="stat-icon" style={{ width: 34, height: 34, borderRadius: '50%', fontWeight: 700, fontSize: '0.8rem', ...(isAdmin ? { background: 'var(--admin-soft)', color: 'var(--admin)' } : {}) }}>
              {initials}
            </div>
            <div className="stack-sm hide-sm" style={{ gap: 0, lineHeight: 1.2 }}>
              <span style={{ fontWeight: 600, fontSize: '0.85rem' }} className="nowrap">{user?.name}</span>
              <span className={`small ${isAdmin ? '' : 'muted'}`} style={isAdmin ? { color: 'var(--admin)', fontWeight: 600 } : undefined}>
                {isAdmin ? 'Administrator' : 'Passenger'}
              </span>
            </div>
          </div>
          <button className="btn btn-ghost btn-icon" onClick={() => logout()} aria-label="Log out" title="Log out">
            <LogOut size={18} />
          </button>
        </header>
        <main className="page">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

export function RequireAuth({ children }) {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return children;
}

export function RequireAdmin({ children }) {
  const { isAdmin } = useAuth();
  if (!isAdmin) return <Navigate to="/dashboard" replace />;
  return children;
}
