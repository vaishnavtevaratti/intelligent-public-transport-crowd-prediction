import api from './api';

export const occupancyService = {
  getAll: () => api.get('/occupancy').then((r) => r.data),
  get: (busId) => api.get(`/occupancy/${busId}`).then((r) => r.data),
  getObservations: (busId, params = {}) => api.get(`/occupancy/${busId}/observations`, { params }).then((r) => r.data),
  reconcile: (busId, body) => api.put(`/occupancy/${busId}/reconcile`, body).then((r) => r.data),
};
