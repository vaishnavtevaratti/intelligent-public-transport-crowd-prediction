import api from './api';

export const crowdService = {
  /** Paged prediction history. params: { routeId, busId, level, source, page, size } */
  getHistory: (params = {}) => api.get('/crowd/history', { params }).then((r) => r.data),
  /** Ask the ML model for a prediction. travelTime: 'YYYY-MM-DDTHH:mm' (local) or undefined for now. */
  predict: (busId, travelTime) => api.post('/crowd/predict', { busId, travelTime }).then((r) => r.data),
  getForecast: (routeId, params = {}) => api.get(`/crowd/forecast/${routeId}`, { params }).then((r) => r.data),
  getAlerts: () => api.get('/crowd/alerts').then((r) => r.data),
  getModelStatus: () => api.get('/crowd/model').then((r) => r.data),
};
