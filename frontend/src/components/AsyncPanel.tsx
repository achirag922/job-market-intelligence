import type { ReactNode } from 'react';
import type { AsyncState } from '../hooks/useApi';
import { EmptyState, ErrorState, SkeletonCards, SkeletonChart, SkeletonTable } from './ui';

/** Which placeholder to show while loading, so it is shaped like what is coming. */
type SkeletonKind = 'table' | 'chart' | 'cards' | 'text' | 'none';

interface Props<T> {
  state: AsyncState<T>;
  children: (data: T) => ReactNode;
  /** Shown when the call succeeded but returned nothing. */
  empty?: string;
  emptyTitle?: string;
  isEmpty?: (data: T) => boolean;
  skeleton?: SkeletonKind;
  /** Rows or cards to mimic while loading. */
  skeletonCount?: number;
  onRetry?: () => void;
}

/**
 * Renders loading, error and empty states in one place, so every page handles a failed
 * API call the same way rather than each inventing its own.
 *
 * <p>The loading state is a skeleton shaped like the content rather than a spinner: the
 * layout stays still when the data lands, and the reader already knows what is arriving.
 */
export function AsyncPanel<T>({
  state,
  children,
  empty,
  emptyTitle,
  isEmpty,
  skeleton = 'text',
  skeletonCount,
  onRetry,
}: Props<T>) {
  if (state.loading) {
    return <LoadingPlaceholder kind={skeleton} count={skeletonCount} />;
  }
  if (state.error) {
    return <ErrorState message={state.error} onRetry={onRetry} />;
  }
  if (state.data === null) {
    return <EmptyState message="No data was returned for this view." />;
  }
  if (isEmpty?.(state.data)) {
    return <EmptyState title={emptyTitle} message={empty ?? 'There is nothing to show here yet.'} />;
  }
  return <>{children(state.data)}</>;
}

function LoadingPlaceholder({ kind, count }: { kind: SkeletonKind; count?: number }) {
  switch (kind) {
    case 'table':
      return <SkeletonTable rows={count ?? 6} />;
    case 'chart':
      return <SkeletonChart />;
    case 'cards':
      return <SkeletonCards count={count ?? 4} />;
    case 'none':
      return null;
    default:
      return (
        <div className="skeleton-stack" aria-busy="true" aria-label="Loading">
          <div className="skeleton skeleton-line" style={{ width: '60%' }} />
          <div className="skeleton skeleton-line" style={{ width: '85%' }} />
          <div className="skeleton skeleton-line" style={{ width: '40%' }} />
        </div>
      );
  }
}
