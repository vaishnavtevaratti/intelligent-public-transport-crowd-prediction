import api from './api';

export const travelService = {
  getStops: () => api.get('/travel/stops').then((r) => r.data),
  search: (from, to) => api.get('/travel/search', { params: { from, to } }).then((r) => r.data),
};
