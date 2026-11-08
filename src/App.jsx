import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout, { RequireAdmin, RequireAuth } from './components/layout/AppLayout';
import { Spinner } from './components/ui/ui';
import { useAuth } from './context/AuthContext';
import { useTheme } from './hooks/useTheme';
import Login from './pages/Login';
import Register from './pages/Register';

// Pages are loaded on demand so the login screen stays small and fast.
const UserDashboard = lazy(() => import('./pages/UserDashboard'));
const BusTracking = lazy(() => import('./pages/BusTracking'));
const CrowdPrediction = lazy(() => import('./pages/CrowdPrediction'));
const TravelInformation = lazy(() => import('./pages/TravelInformation'));
const AdminDashboard = lazy(() => import('./pages/admin/AdminDashboard'));
const ManageBuses = lazy(() => import('./pages/admin/ManageBuses'));
const ManageRoutes = lazy(() => import('./pages/admin/ManageRoutes'));
const ManageUsers = lazy(() => import('./pages/admin/ManageUsers'));
const PredictionHistory = lazy(() => import('./pages/admin/PredictionHistory'));
const OccupancySensors = lazy(() => import('./pages/admin/OccupancySensors'));
const NotFound = lazy(() => import('./pages/NotFound'));

function PageLoader() {
  return <div className="state"><Spinner /> Loading…</div>;
}

export default function App() {
  const { user } = useAuth();
  useTheme(); // applies the saved theme on every page, including login

  return (
    <Suspense fallback={<PageLoader />}>
      <Routes>
        <Route path="/login" element={user ? <Navigate to="/dashboard" replace /> : <Login />} />
        <Route path="/register" element={user ? <Navigate to="/dashboard" replace /> : <Register />} />
        <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
          <Route path="/dashboard" element={<UserDashboard />} />
          <Route path="/tracking" element={<BusTracking />} />
          <Route path="/crowd" element={<CrowdPrediction />} />
          <Route path="/travel" element={<TravelInformation />} />
          <Route path="/admin" element={<RequireAdmin><AdminDashboard /></RequireAdmin>} />
          <Route path="/admin/buses" element={<RequireAdmin><ManageBuses /></RequireAdmin>} />
          <Route path="/admin/routes" element={<RequireAdmin><ManageRoutes /></RequireAdmin>} />
          <Route path="/admin/users" element={<RequireAdmin><ManageUsers /></RequireAdmin>} />
          <Route path="/admin/predictions" element={<RequireAdmin><PredictionHistory /></RequireAdmin>} />
          <Route path="/admin/occupancy" element={<RequireAdmin><OccupancySensors /></RequireAdmin>} />
          <Route path="*" element={<NotFound />} />
        </Route>
        <Route path="/" element={<Navigate to={user ? '/dashboard' : '/login'} replace />} />
      </Routes>
    </Suspense>
  );
}
