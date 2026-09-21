import type { ReactNode } from 'react';
import { IconAlert, IconInbox } from './icons';

/**
 * The shared building blocks every page is assembled from.
 *
 * <p>They live in one file because they are small, they are always used together, and
 * splitting eleven twenty-line components across eleven files makes them harder to keep
 * consistent, not easier.
 */

/* ------------------------------------------------------------------ page header */

interface PageHeaderProps {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}

/** Title, one line of explanation, and any page-level actions. */
export function PageHeader({ title, description, actions }: PageHeaderProps) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {description && <p className="subtitle">{description}</p>}
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
    </header>
  );
}

/* --------------------------------------------------------------------- cards */

interface StatCardProps {
  label: string;
  /** A number is formatted with separators; a string is shown as written. */
  value: number | string | null | undefined;
  hint?: string;
  loading?: boolean;
}

/**
 * One headline figure.
 *
 * <p>A null value renders as an em dash rather than a zero: "we do not have this" and
 * "this is zero" are different facts, and showing the second for the first is a quiet lie.
 */
export function StatCard({ label, value, hint, loading }: StatCardProps) {
  if (loading) {
    return (
      <div className="stat-card" aria-busy="true">
        <Skeleton width="45%" height={13} />
        <Skeleton width="70%" height={28} />
      </div>
    );
  }

  const isNumber = typeof value === 'number';
  const shown =
    value === null || value === undefined ? '—' : isNumber ? value.toLocaleString('en-US') : value;

  return (
    <div className="stat-card">
      <span className="stat-label">{label}</span>
      <span className={isNumber ? 'stat-value' : 'stat-value is-text'}>{shown}</span>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  );
}

interface CardProps {
  title?: string;
  description?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}

/** A titled surface. Used for charts, tables and any grouped content. */
export function Card({ title, description, actions, children, className }: CardProps) {
  return (
    <section className={className ? `card ${className}` : 'card'}>
      {(title || actions) && (
        <div className="card-header">
          <div>
            {title && <h2 className="card-title">{title}</h2>}
            {description && <p className="card-description">{description}</p>}
          </div>
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

/* --------------------------------------------------------------------- states */

interface EmptyStateProps {
  title?: string;
  message: string;
  action?: ReactNode;
}

/**
 * Nothing to show, and why.
 *
 * <p>A blank panel makes a reader wonder whether the page is broken. Saying "no postings
 * match these filters" costs one line and answers that.
 */
export function EmptyState({ title = 'Nothing to show', message, action }: EmptyStateProps) {
  return (
    <div className="state-panel">
      <span className="state-panel-icon">
        <IconInbox size={20} />
      </span>
      <p className="state-panel-title">{title}</p>
      <p className="state-panel-message">{message}</p>
      {action}
    </div>
  );
}

interface ErrorStateProps {
  message: string;
  onRetry?: () => void;
}

/**
 * A failed call.
 *
 * <p>The message shown is the one the API client produced — a short sentence — never an
 * exception, a URL or a status line. `role="alert"` so a screen reader is told without
 * having to find it.
 */
export function ErrorState({ message, onRetry }: ErrorStateProps) {
  return (
    <div className="state-panel error" role="alert">
      <span className="state-panel-icon">
        <IconAlert size={20} />
      </span>
      <p className="state-panel-title">Could not load this</p>
      <p className="state-panel-message">{message}</p>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ skeletons */

interface SkeletonProps {
  width?: number | string;
  height?: number | string;
  radius?: number | string;
}

export function Skeleton({ width = '100%', height = 12, radius }: SkeletonProps) {
  return (
    <span
      className="skeleton"
      style={{ display: 'block', width, height, borderRadius: radius }}
      aria-hidden="true"
    />
  );
}

/**
 * Placeholders shaped like the thing that is loading.
 *
 * <p>A skeleton the size of the eventual content keeps the layout still when it arrives.
 * A centred spinner cannot do that, and the page jumps.
 */
export function SkeletonTable({ rows = 6, columns = 4 }: { rows?: number; columns?: number }) {
  return (
    <div className="table-wrap" aria-busy="true" aria-label="Loading table">
      <table>
        <tbody>
          {Array.from({ length: rows }).map((_, row) => (
            <tr key={row}>
              {Array.from({ length: columns }).map((__, column) => (
                <td key={column}>
                  <Skeleton width={column === 0 ? '70%' : '45%'} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function SkeletonChart({ height = 280 }: { height?: number }) {
  return (
    <div aria-busy="true" aria-label="Loading chart">
      <Skeleton height={height} radius={10} />
    </div>
  );
}

export function SkeletonCards({ count = 4 }: { count?: number }) {
  return (
    <div className="stat-grid">
      {Array.from({ length: count }).map((_, index) => (
        <StatCard key={index} label="" value={null} loading />
      ))}
    </div>
  );
}

/* --------------------------------------------------------------------- badges */

type BadgeTone = 'neutral' | 'brand' | 'success' | 'warning' | 'danger';

const BADGE_CLASS: Record<BadgeTone, string> = {
  neutral: 'badge',
  brand: 'badge badge-brand',
  success: 'badge badge-success',
  warning: 'badge badge-warning',
  danger: 'badge badge-danger',
};

export function Badge({ tone = 'neutral', children }: { tone?: BadgeTone; children: ReactNode }) {
  return <span className={BADGE_CLASS[tone]}>{children}</span>;
}

/**
 * A skill, optionally marked as held or missing.
 *
 * <p>The state is carried by a word and a symbol as well as by colour, so it survives a
 * reader who cannot distinguish the two backgrounds.
 */
export function SkillBadge({
  name,
  state,
}: {
  name: string;
  state?: 'matched' | 'missing';
}) {
  if (!state) {
    return <li className="skill-tag">{name}</li>;
  }
  const matched = state === 'matched';
  return (
    <li className={`skill-tag ${state}`}>
      <span aria-hidden="true">{matched ? '✓' : '✕'}</span>
      {name}
      <span className="visually-hidden">{matched ? '(on your resume)' : '(missing)'}</span>
    </li>
  );
}

/* ----------------------------------------------------------------- table bits */

/**
 * A value with a proportional bar behind it.
 *
 * <p>A column of numbers is precise but shapeless; the bar gives the ranking a form that
 * can be read at a glance. The number stays, so nothing depends on judging a length.
 */
export function BarCell({ value, max, suffix }: { value: number; max: number; suffix?: string }) {
  const share = max > 0 ? Math.max(2, Math.round((value / max) * 100)) : 0;
  return (
    <div>
      <div className="row" style={{ justifyContent: 'space-between', gap: 8, flexWrap: 'nowrap' }}>
        <span>
          {value.toLocaleString('en-US')}
          {suffix}
        </span>
      </div>
      <div className="bar-cell-track" aria-hidden="true">
        <div className="bar-cell-fill" style={{ width: `${share}%` }} />
      </div>
    </div>
  );
}
