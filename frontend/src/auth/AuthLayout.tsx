import type { ReactNode } from 'react';
import { IconDashboard } from '../components/icons';

/**
 * The full-screen dark frame for Sign up, Login and Verify: brand, one centered card,
 * nothing else. Dark regardless of the app theme, like the reference; contrast is kept at
 * WCAG AA for all text.
 */
export function AuthLayout({ title, subtitle, tone, children }: {
  title: string;
  subtitle?: ReactNode;
  /** Colours the heading for a verdict, as the success screen does. */
  tone?: 'success';
  children: ReactNode;
}) {
  return (
    <main className="auth-screen">
      <div className="auth-glow" aria-hidden="true" />
      <section className="auth-panel" aria-labelledby="auth-title">
        <p className="auth-brand">
          <span className="auth-brand-mark" aria-hidden="true">
            <IconDashboard size={18} />
          </span>
          JMIP
          <span className="auth-brand-name">Job Market Intelligence</span>
        </p>
        <h1 id="auth-title" className={tone === 'success' ? 'auth-title is-success' : 'auth-title'}>
          {title}
        </h1>
        {subtitle && <p className="auth-subtitle">{subtitle}</p>}
        {children}
      </section>
    </main>
  );
}

/** "c****@gmail.com": enough to recognise the address, not enough to read it off a screen. */
// eslint-disable-next-line react-refresh/only-export-components
export function maskEmail(email: string): string {
  const [local, domain] = email.trim().split('@');
  if (!local || !domain) {
    return email;
  }
  return `${local[0]}****@${domain}`;
}
