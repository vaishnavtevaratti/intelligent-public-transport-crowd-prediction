import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { KeyRound, UserPlus } from 'lucide-react';
import AuthLayout from './AuthLayout';
import { Alert, Spinner } from '../components/ui/ui';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { getErrorMessage, getFieldErrors } from '../services/api';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_RE = /^\+?[0-9 ()-]{7,20}$/;

export default function Register() {
  const { register } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '', confirm: '', role: 'USER', adminCode: '' });
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState(null);

  const update = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  const validate = () => {
    const e = {};
    if (form.name.trim().length < 2) e.name = 'Name must be at least 2 characters';
    if (!EMAIL_RE.test(form.email.trim())) e.email = 'Enter a valid email address';
    if (form.phone.trim() && !PHONE_RE.test(form.phone.trim())) e.phone = 'Enter a valid phone number';
    if (form.password.length < 6) e.password = 'Password must be at least 6 characters';
    if (form.password !== form.confirm) e.confirm = 'Passwords do not match';
    if (form.role === 'ADMIN' && !form.adminCode.trim()) e.adminCode = 'Administrator registration code is required';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    setServerError(null);
    if (!validate()) return;
    setSubmitting(true);
    try {
      const data = await register({
        name: form.name.trim(),
        email: form.email.trim(),
        phone: form.phone.trim() || null,
        password: form.password,
        role: form.role,
        adminCode: form.role === 'ADMIN' ? form.adminCode.trim() : null,
      });
      toast.success('Account created. Welcome!');
      navigate(data.role === 'ADMIN' ? '/admin' : '/dashboard', { replace: true });
    } catch (err) {
      setErrors(getFieldErrors(err));
      setServerError(getErrorMessage(err, 'Registration failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const field = (name, label, props = {}) => (
    <div className={`field ${props.full ? 'full' : ''}`}>
      <label htmlFor={name}>{label}</label>
      <input id={name} className={`input ${errors[name] ? 'invalid' : ''}`} value={form[name]} onChange={update(name)} {...props.input} />
      {errors[name] && <span className="field-error">{errors[name]}</span>}
    </div>
  );

  return (
    <AuthLayout>
      <div className="card" style={{ padding: 28 }}>
        <div className="stack" style={{ gap: 18 }}>
          <div>
            <h1>Create account</h1>
            <p className="muted">Register to track buses and check crowd forecasts.</p>
          </div>
          <div className="segmented" role="tablist" aria-label="Account type">
            {['USER', 'ADMIN'].map((role) => (
              <button key={role} type="button" role="tab" aria-selected={form.role === role}
                className={form.role === role ? 'active' : ''} onClick={() => setForm((f) => ({ ...f, role }))}>
                {role === 'USER' ? 'Passenger' : 'Administrator'}
              </button>
            ))}
          </div>
          {serverError && <Alert type="error">{serverError}</Alert>}
          <form className="form-grid" onSubmit={handleSubmit} noValidate>
            {field('name', 'Full name', { full: true, input: { autoComplete: 'name' } })}
            {field('email', 'Email', { full: true, input: { type: 'email', autoComplete: 'email' } })}
            {field('phone', 'Phone (optional)', { full: true, input: { type: 'tel', autoComplete: 'tel' } })}
            {field('password', 'Password', { input: { type: 'password', autoComplete: 'new-password' } })}
            {field('confirm', 'Confirm password', { input: { type: 'password', autoComplete: 'new-password' } })}
            {form.role === 'ADMIN' && (
              <div className="field full">
                <label htmlFor="adminCode"><KeyRound size={13} style={{ verticalAlign: -2 }} /> Administrator registration code</label>
                <input id="adminCode" type="password" className={`input ${errors.adminCode ? 'invalid' : ''}`} value={form.adminCode} onChange={update('adminCode')} />
                {errors.adminCode
                  ? <span className="field-error">{errors.adminCode}</span>
                  : <span className="field-hint">Provided by the system owner. Without it, register as a passenger and ask an administrator to grant access.</span>}
              </div>
            )}
            <button className="btn btn-primary btn-block full" type="submit" disabled={submitting} style={{ height: 40 }}>
              {submitting ? <Spinner label="Creating account" /> : <UserPlus size={16} />} Create account
            </button>
          </form>
          <p className="muted" style={{ textAlign: 'center' }}>Already registered? <Link to="/login">Sign in</Link></p>
        </div>
      </div>
    </AuthLayout>
  );
}
