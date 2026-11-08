import api from './api';

export const dashboardService = {
  getDashboardStats: () => api.get('/dashboard').then((r) => r.data),
};
