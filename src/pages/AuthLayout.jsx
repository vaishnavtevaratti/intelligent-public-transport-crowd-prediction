import { Brain, Bus, MapPinned, ShieldCheck } from 'lucide-react';

const FEATURES = [
  { icon: MapPinned, title: 'Live bus tracking', text: 'Bus positions update on the map every few seconds over WebSocket.' },
  { icon: Brain, title: 'Crowd prediction', text: 'A machine learning model predicts Low, Medium or High crowding by time and route.' },
  { icon: ShieldCheck, title: 'Admin control', text: 'Administrators manage buses, routes and users securely.' },
];

/** Two-column frame shared by the login and registration pages. */
export default function AuthLayout({ children }) {
  return (
    <div className="auth-page">
      <section className="auth-hero">
        <div className="row" style={{ gap: 10, color: '#fff', fontWeight: 700, fontSize: '1.15rem' }}>
          <div className="brand-mark"><Bus size={18} /></div> OptiTransit
        </div>
        <div className="stack" style={{ gap: 28 }}>
          <h1>Plan smarter trips with live buses and crowd forecasts.</h1>
          {FEATURES.map(({ icon: Icon, title, text }) => (
            <div key={title} className="auth-feature">
              <div className="stat-icon"><Icon size={20} /></div>
              <div><strong style={{ color: '#fff' }}>{title}</strong><p style={{ color: '#cbd5e1' }}>{text}</p></div>
            </div>
          ))}
        </div>
        <p style={{ color: '#94a3b8', fontSize: '0.8rem' }}>Intelligent Public Transport &amp; Crowd Prediction System · B.Tech CSE Mega Project</p>
      </section>
      <section className="auth-panel">
        <div className="auth-card">{children}</div>
      </section>
    </div>
  );
}
