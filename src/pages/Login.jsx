import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Eye, EyeOff, Lock, LogIn, Mail } from 'lucide-react';
import AuthLayout from './AuthLayout';
import { Alert, Spinner } from '../components/ui/ui';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { getErrorMessage } from '../services/api';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function Login() {
  const { login, sessionMessage } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const [form, setForm] = useState({ email: '', password: '' });
  const [errors, setErrors] = useState({});
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState(null);

  const validate = () => {
    const e = {};
    if (!EMAIL_RE.test(form.email.trim())) e.email = 'Enter a valid email address';
    if (!form.password) e.password = 'Enter your password';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    setServerError(null);
    if (!validate()) return;
    setSubmitting(true);
    try {
      const data = await login(form.email.trim(), form.password);
      toast.success(`Welcome back, ${data.name}!`);
      navigate(location.state?.from || (data.role === 'ADMIN' ? '/admin' : '/dashboard'), { replace: true });
    } catch (err) {
      setServerError(getErrorMessage(err, 'Login failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const update = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  return (
    <AuthLayout>
      <div className="card" style={{ padding: 28 }}>
        <div className="stack" style={{ gap: 20 }}>
          <div>
            <h1>Sign in</h1>
            <p className="muted">Passengers and administrators use the same sign-in.</p>
          </div>
          {sessionMessage && <Alert type="warning">{sessionMessage}</Alert>}
          {serverError && <Alert type="error">{serverError}</Alert>}
          <form className="stack" onSubmit={handleSubmit} noValidate>
            <div className="field">
              <label htmlFor="email">Email</label>
              <div className="input-icon">
                <Mail size={16} />
                <input id="email" type="email" autoComplete="email" className={`input ${errors.email ? 'invalid' : ''}`}
                  value={form.email} onChange={update('email')} placeholder="you@example.com" />
              </div>
              {errors.email && <span className="field-error">{errors.email}</span>}
            </div>
            <div className="field">
              <label htmlFor="password">Password</label>
              <div className="input-icon" style={{ position: 'relative' }}>
                <Lock size={16} />
                <input id="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password"
                  className={`input ${errors.password ? 'invalid' : ''}`} value={form.password} onChange={update('password')}
                  style={{ paddingRight: 40 }} />
                <button type="button" className="btn btn-ghost btn-sm btn-icon" onClick={() => setShowPassword((s) => !s)}
                  style={{ position: 'absolute', right: 4, top: 4 }} aria-label={showPassword ? 'Hide password' : 'Show password'}>
                  {showPassword ? <EyeOff size={15} /> : <Eye size={15} />}
                </button>
              </div>
              {errors.password && <span className="field-error">{errors.password}</span>}
            </div>
            <button className="btn btn-primary btn-block" type="submit" disabled={submitting} style={{ height: 40 }}>
              {submitting ? <Spinner label="Signing in" /> : <LogIn size={16} />} Sign in
            </button>
          </form>
          <p className="muted" style={{ textAlign: 'center' }}>
            New passenger? <Link to="/register">Create an account</Link>
          </p>
        </div>
      </div>
    </AuthLayout>
  );
}
