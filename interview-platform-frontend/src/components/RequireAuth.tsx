import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { getAccessToken } from '../auth/storage';

export default function RequireAuth() {
  const location = useLocation();
  if (!getAccessToken()) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}
