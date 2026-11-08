import api from './api';

export const adminService = {
  getAnalytics: () => api.get('/admin/analytics').then((r) => r.data),
  getUsers: () => api.get('/admin/users').then((r) => r.data),
  changeRole: (id, role) => api.put(`/admin/users/${id}/role`, { role }).then((r) => r.data),
  deleteUser: (id) => api.delete(`/admin/users/${id}`),
  getSensors: () => api.get('/admin/sensors').then((r) => r.data),
  createSensor: (body) => api.post('/admin/sensors', body).then((r) => r.data),
  updateSensor: (id, body) => api.put(`/admin/sensors/${id}`, body).then((r) => r.data),
  rotateSensorKey: (id) => api.post(`/admin/sensors/${id}/rotate-key`).then((r) => r.data),
  deleteSensor: (id) => api.delete(`/admin/sensors/${id}`),
  getCounters: () => api.get('/admin/counters').then((r) => r.data),
  startCounter: (body) => api.post('/admin/counters/start', body).then((r) => r.data),
  stopCounter: (sensorCode) => api.post(`/admin/counters/${encodeURIComponent(sensorCode)}/stop`).then((r) => r.data),
  getCounterLive: (sensorCode) => api.get(`/admin/counters/${encodeURIComponent(sensorCode)}/live`).then((r) => r.data),
  /** Latest annotated frame as a Blob (kept in memory only). */
  getCounterFrame: (sensorCode) => api.get(`/admin/counters/${encodeURIComponent(sensorCode)}/frame`, { responseType: 'blob' }).then((r) => r.data),
  resetCounter: (sensorCode) => api.post(`/admin/counters/${encodeURIComponent(sensorCode)}/reset`).then((r) => r.data),
  getModels: () => api.get('/admin/models').then((r) => r.data),
  getForecastPerformance: () => api.get('/admin/forecast-performance').then((r) => r.data),
};
