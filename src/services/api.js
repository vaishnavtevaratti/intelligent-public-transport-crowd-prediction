import axios from 'axios';

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';
export const AUTH_EXPIRED_EVENT = 'auth:expired';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15000,
});

// Attach the JWT to every request.
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// A 401 on an authenticated request means the session expired: tell the app to log out.
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const isLoginCall = error.config?.url?.includes('/auth/login');
    if (error.response?.status === 401 && !isLoginCall && localStorage.getItem('token')) {
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
    }
    return Promise.reject(error);
  }
);

/** Human-readable message for any API error (uses the backend's JSON "message" when present). */
export function getErrorMessage(error, fallback = 'Something went wrong. Please try again.') {
  if (!error) return fallback;
  if (error.response) {
    const data = error.response.data;
    if (data && typeof data === 'object' && data.message) return data.message;
    if (error.response.status === 403) return 'You do not have permission to perform this action.';
    if (error.response.status === 404) return 'The requested item was not found.';
    if (error.response.status >= 500) return 'The server reported an error. Please try again.';
    return fallback;
  }
  if (error.code === 'ECONNABORTED') return 'The server took too long to respond.';
  if (error.request) return `Cannot reach the backend at ${API_BASE_URL}. Is the Spring Boot server running?`;
  return error.message || fallback;
}

/** Field-level validation errors returned by the backend, e.g. { email: 'Email must be valid' }. */
export function getFieldErrors(error) {
  return error?.response?.data?.validationErrors || {};
}

export default api;
