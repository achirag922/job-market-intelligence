import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { SkeletonCards } from '../components/ui';
import { useAuth } from './AuthContext';

/**
 * Routes that need a signed-in user. The API refuses them anyway (401); this only saves a
 * signed-out visitor from a page of errors, sending them to Log in and back afterwards.
 */
export function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return <SkeletonCards count={3} />;
  }
  if (status === 'signedOut') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}
