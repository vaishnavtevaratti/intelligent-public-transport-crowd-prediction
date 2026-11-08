import api from './api';

function store(data) {
  localStorage.setItem('token', data.token);
  localStorage.setItem('user', JSON.stringify({ id: data.id, name: data.name, email: data.email, role: data.role }));
}

/** Expiry time (ms since epoch) read from the JWT payload, or null if unreadable. */
export function tokenExpiry(token) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.exp ? payload.exp * 1000 : null;
  } catch {
    return null;
  }
}

export const authService = {
  async register(data) {
    const response = await api.post('/auth/register', data);
    store(response.data);
    return response.data;
  },

  async login(email, password) {
    const response = await api.post('/auth/login', { email, password });
    store(response.data);
    return response.data;
  },

  async getProfile() {
    const response = await api.get('/auth/profile');
    return response.data;
  },

  logout() {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
  },

  getToken() {
    return localStorage.getItem('token');
  },

  /** The saved user, or null when there is no unexpired token. */
  getCurrentUser() {
    const token = localStorage.getItem('token');
    const userStr = localStorage.getItem('user');
    if (!token || !userStr) return null;
    const expiry = tokenExpiry(token);
    if (expiry && expiry <= Date.now()) {
      this.logout();
      return null;
    }
    try {
      return JSON.parse(userStr);
    } catch {
      return null;
    }
  },
};
