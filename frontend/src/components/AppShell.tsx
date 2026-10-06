import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useTheme } from '../hooks/useTheme';
import {
  IconBell,
  IconBookmark,
  IconBriefcase,
  IconBuilding,
  IconChat,
  IconDatabase,
  IconFlag,
  IconClose,
  IconDashboard,
  IconFile,
  IconLayers,
  IconMapPin,
  IconMenu,
  IconMoon,
  IconSpark,
  IconSun,
  IconTrend,
} from './icons';

interface NavItem {
  to: string;
  label: string;
  icon: (props: { size?: number }) => ReactNode;
  end?: boolean;
}

/**
 * Navigation, grouped by what the reader is trying to do rather than by which phase
 * built it. "Overview" is where you land, "Job market" is the data, "Career" is the two
 * tools that are about you specifically.
 */
const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: 'Overview',
    items: [
      { to: '/', label: 'Dashboard', icon: IconDashboard, end: true },
      { to: '/my-career', label: 'My Career', icon: IconFlag },
      { to: '/progress', label: 'Career Progress', icon: IconFlag },
      { to: '/my-analytics', label: 'My Analytics', icon: IconTrend },
      { to: '/notifications', label: 'Notifications', icon: IconBell },
    ],
  },
  {
    label: 'Jobs',
    items: [
      { to: '/jobs', label: 'Job Explorer', icon: IconBriefcase },
      { to: '/for-you', label: 'Recommended for You', icon: IconSpark },
      { to: '/workspace', label: 'Job Workspace', icon: IconBookmark },
      { to: '/saved-jobs', label: 'Applications', icon: IconBookmark },
      { to: '/alerts', label: 'Job Alerts', icon: IconBell },
    ],
  },
  {
    label: 'Resume & profile',
    items: [
      { to: '/resume', label: 'Resume Intelligence', icon: IconFile },
      { to: '/resume-builder', label: 'Resume Builder', icon: IconFile },
      { to: '/portfolio', label: 'Portfolio', icon: IconFile },
      { to: '/onboarding', label: 'Profile & Preferences', icon: IconFlag },
      { to: '/settings', label: 'Settings', icon: IconLayers },
      { to: '/help', label: 'Help & FAQ', icon: IconChat },
    ],
  },
  {
    label: 'Growth',
    items: [
      { to: '/career-goals', label: 'Career Goals', icon: IconFlag },
      { to: '/learning', label: 'Learning & Skills', icon: IconSpark },
      { to: '/interview-prep', label: 'Interview Prep', icon: IconChat },
      { to: '/assistant', label: 'AI Assistant', icon: IconChat },
    ],
  },
  {
    label: 'Market insights',
    items: [
      { to: '/market', label: 'Market Intelligence', icon: IconTrend },
      { to: '/analytics/skills', label: 'Skills', icon: IconSpark },
      { to: '/analytics/trends', label: 'Skill Trends', icon: IconTrend },
      { to: '/analytics/companies', label: 'Companies', icon: IconBuilding },
      { to: '/analytics/locations', label: 'Locations', icon: IconMapPin },
      { to: '/analytics/categories', label: 'Job Categories', icon: IconLayers },
    ],
  },
];

/**
 * V8.9: shown only to ADMIN accounts; the admin APIs refuse everyone else regardless. V9.15: ETL
 * monitoring is an operator's page, so it lives here rather than in everyone's navigation.
 */
const ADMIN_GROUP: { label: string; items: NavItem[] } = {
  label: 'Admin',
  items: [
    { to: '/admin', label: 'Admin Dashboard', icon: IconDatabase },
    { to: '/etl', label: 'ETL Monitoring', icon: IconDatabase },
  ],
};

/** The page title shown in the header, matched longest-prefix-first. */
const PAGE_TITLES: [string, string][] = [
  ['/jobs/', 'Job Details'],
  ['/jobs', 'Job Explorer'],
  ['/analytics/skills', 'Skill Analytics'],
  ['/analytics/trends', 'Skill Trends'],
  ['/analytics/companies', 'Company Analytics'],
  ['/analytics/locations', 'Location Analytics'],
  ['/analytics/categories', 'Job Categories'],
  ['/resume', 'Resume Intelligence'],
  ['/assistant', 'AI Assistant'],
  ['/my-career', 'My Career'],
  ['/market', 'Market Intelligence'],
  ['/alerts', 'Job Alerts'],
  ['/interview-prep', 'Interview Preparation'],
  ['/admin', 'Admin Dashboard'],
  ['/saved-jobs', 'Applications'],
  ['/for-you', 'Recommended for You'],
  ['/my-analytics', 'My Analytics'],
  ['/onboarding', 'Profile & Preferences'],
  ['/progress', 'Career Progress'],
  ['/workspace', 'Job Workspace'],
  ['/notifications', 'Notifications'],
  ['/settings', 'Settings'],
  ['/help', 'Help & FAQ'],
  ['/resume-builder', 'Resume Builder'],
  ['/career-goals', 'Career Goals'],
  ['/learning', 'Learning & Skills'],
  ['/portfolio', 'Professional Portfolio'],
  ['/etl', 'ETL Monitoring'],
  ['/login', 'Log in'],
  ['/signup', 'Sign up'],
  ['/', 'Dashboard'],
];

function titleFor(pathname: string): string {
  return PAGE_TITLES.find(([prefix]) => pathname.startsWith(prefix))?.[1] ?? 'Dashboard';
}

/** V9.11: the sidebar group a page belongs to, for the breadcrumb; the longest matching link wins. */
function sectionFor(pathname: string): string | null {
  let best: { label: string; length: number } | null = null;
  for (const group of [...NAV_GROUPS, ADMIN_GROUP]) {
    for (const item of group.items) {
      const matches = item.to === '/' ? pathname === '/' : pathname === item.to || pathname.startsWith(item.to + '/');
      if (matches && (!best || item.to.length > best.length)) {
        best = { label: group.label, length: item.to.length };
      }
    }
  }
  return best?.label ?? null;
}

/**
 * The application frame: brand, header, sidebar, content.
 *
 * <p>On a wide screen the sidebar is always there. Below 1024px it becomes a drawer over
 * the content, because a fixed 236px column on a phone leaves nothing for the data — and
 * hiding the navigation entirely would leave no way to move between pages.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [navOpen, setNavOpen] = useState(false);
  const { theme, toggle } = useTheme();
  const isAdmin = useAuth().user?.role === 'ADMIN';
  const location = useLocation();

  // A drawer that survives navigation covers the page you just asked for.
  useEffect(() => {
    setNavOpen(false);
  }, [location.pathname]);

  // V9.11: the browser tab and screen readers announce which page this is.
  const pageTitle = titleFor(location.pathname);
  const section = sectionFor(location.pathname);
  useEffect(() => {
    document.title = `${pageTitle} · JMIP`;
  }, [pageTitle]);

  // Escape closes it, which is what every other overlay on the web does.
  useEffect(() => {
    if (!navOpen) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setNavOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [navOpen]);

  return (
    <div className="app">
      <a className="skip-link" href="#main-content">Skip to main content</a>
      <div className="app-brandbar">
        <Brand />
      </div>

      <header className="app-header">
        <div className="row" style={{ minWidth: 0, gap: 12 }}>
          <button
            type="button"
            className="icon-button ghost mobile-only"
            aria-label={navOpen ? 'Close navigation' : 'Open navigation'}
            aria-expanded={navOpen}
            aria-controls="app-sidebar"
            onClick={() => setNavOpen((open) => !open)}
          >
            {navOpen ? <IconClose /> : <IconMenu />}
          </button>
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <span className="desktop-only">{section ?? 'Job Market Intelligence'}</span>
            <span className="desktop-only" aria-hidden="true">
              /
            </span>
            <span className="breadcrumb-current" aria-current="page">{pageTitle}</span>
          </nav>
        </div>

        <div className="header-actions">
          <NotificationBell />
          <UserMenu />
          <button
            type="button"
            className="icon-button ghost"
            onClick={toggle}
            aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          >
            {theme === 'dark' ? <IconSun /> : <IconMoon />}
          </button>
        </div>
      </header>

      <button
        type="button"
        className={navOpen ? 'sidebar-scrim show' : 'sidebar-scrim'}
        aria-label="Close navigation"
        tabIndex={navOpen ? 0 : -1}
        onClick={() => setNavOpen(false)}
      />

      <aside id="app-sidebar" className={navOpen ? 'app-sidebar open' : 'app-sidebar'}>
        <div className="mobile-only" style={{ marginBottom: 16 }}>
          <Brand />
        </div>
        <nav aria-label="Main">
          {(isAdmin ? [...NAV_GROUPS, ADMIN_GROUP] : NAV_GROUPS).map((group) => (
            <div className="nav-group" key={group.label}>
              <p className="nav-group-label">{group.label}</p>
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
                >
                  <item.icon size={17} />
                  {item.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
      </aside>

      <main className="app-main" id="main-content" tabIndex={-1}>
        <div className="page-enter" key={location.pathname}>
          {children}
        </div>
        <footer className="app-footer">
          Data is synthetic and for development only. It does not describe the real job
          market.
        </footer>
      </main>
    </div>
  );
}

function Brand() {
  return (
    <p className="brand">
      <span className="brand-mark" aria-hidden="true">
        <IconDashboard size={16} />
      </span>
      JMIP
    </p>
  );
}

/**
 * V9.16: the bell with the unread count, for signed-in users. Refreshed on navigation, when the
 * notifications page changes something, and every two minutes; a failure simply hides the count.
 */
function NotificationBell() {
  const { status } = useAuth();
  const location = useLocation();
  const [unread, setUnread] = useState(0);
  useEffect(() => {
    if (status !== 'signedIn' || !api.unreadNotifications) return;
    let active = true;
    const load = () => api.unreadNotifications().then((r) => active && setUnread(r.unreadCount), () => undefined);
    void load();
    const timer = window.setInterval(load, 120_000);
    window.addEventListener('jmip:notifications', load);
    return () => {
      active = false;
      window.clearInterval(timer);
      window.removeEventListener('jmip:notifications', load);
    };
  }, [status, location.pathname]);
  if (status !== 'signedIn') return null;
  return (
    <Link to="/notifications" className="icon-button ghost notification-bell"
      aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}>
      <IconBell />
      {unread > 0 && <span className="notification-count" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
    </Link>
  );
}

/** Signed-in email and Log out, or Log in and Sign up links. Nothing while it is still checking. */
function UserMenu() {
  const { status, user, logout } = useAuth();
  const navigate = useNavigate();
  const [signingOut, setSigningOut] = useState(false);

  if (status === 'loading') {
    return null;
  }
  if (status === 'signedIn' && user) {
    return (
      <div className="header-user">
        <Link to="/help" className="header-help" aria-label="Help and FAQ" title="Help & FAQ">?</Link>
        <Link to="/settings" className="header-user-email" title={`${user.email} · Settings`}>
          {/* The name when we have it; accounts from before names were collected show the email. */}
          {user.fullName ?? user.email}
        </Link>
        <button
          type="button"
          className="ghost small"
          disabled={signingOut}
          onClick={async () => {
            setSigningOut(true);
            // V9.15: leave the protected pages first, so signing out lands on Log in rather than
            // the landing page a signed-out visit to the dashboard now shows.
            navigate('/login');
            try {
              await logout();
            } finally {
              setSigningOut(false);
            }
          }}
        >
          {signingOut ? 'Logging out…' : 'Log out'}
        </button>
      </div>
    );
  }
  return (
    <div className="header-user">
      <Link to="/login">Log in</Link>
      <Link className="button-link" to="/signup">
        Sign up
      </Link>
    </div>
  );
}
