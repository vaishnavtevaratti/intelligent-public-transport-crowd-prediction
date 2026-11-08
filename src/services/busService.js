import api from './api';

export const busService = {
  getAllBuses: () => api.get('/buses').then((r) => r.data),
  getBusById: (id) => api.get(`/buses/${id}`).then((r) => r.data),
  createBus: (bus) => api.post('/buses', bus).then((r) => r.data),
  updateBus: (id, bus) => api.put(`/buses/${id}`, bus).then((r) => r.data),
  deleteBus: (id) => api.delete(`/buses/${id}`),
};
