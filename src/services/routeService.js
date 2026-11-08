import api from './api';

export const routeService = {
  getAllRoutes: () => api.get('/routes').then((r) => r.data),
  getRouteById: (id) => api.get(`/routes/${id}`).then((r) => r.data),
  createRoute: (route) => api.post('/routes', route).then((r) => r.data),
  updateRoute: (id, route) => api.put(`/routes/${id}`, route).then((r) => r.data),
  deleteRoute: (id) => api.delete(`/routes/${id}`),
};
