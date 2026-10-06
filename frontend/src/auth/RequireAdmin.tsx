import type { ReactNode } from 'react';
import { EmptyState } from '../components/ui';
import { useAuth } from './AuthContext';

/**
 * V8.9: pages for ADMIN accounts. The server refuses a USER anyway (403); this only spares a
 * user a page of errors. It never grants anything: the role shown here comes from the session.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  if (user?.role !== 'ADMIN') {
    return <EmptyState title="Admins only" message="This area is only available to platform administrators." />;
  }
  return <>{children}</>;
}
