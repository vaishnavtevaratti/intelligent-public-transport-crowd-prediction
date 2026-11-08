import api from './api';

export const locationService = {
  getLiveLocations: () => api.get('/location/live').then((r) => r.data),
  getLocationHistory: (busId, limit = 50) => api.get(`/location/history/${busId}`, { params: { limit } }).then((r) => r.data),
};
