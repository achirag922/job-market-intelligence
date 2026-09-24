import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useTheme } from '../hooks/useTheme';
import {
  IconBriefcase,
  IconBuilding,
  IconChat,
  IconDatabase,
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
    items: [{ to: '/', label: 'Dashboard', icon: IconDashboard, end: true }],
  },
  {
    label: 'Job market',
    items: [
      { to: '/jobs', label: 'Job Explorer', icon: IconBriefcase },
      { to: '/analytics/skills', label: 'Skills', icon: IconSpark },
      { to: '/analytics/trends', label: 'Skill Trends', icon: IconTrend },
      { to: '/analytics/companies', label: 'Companies', icon: IconBuilding },
      { to: '/analytics/locations', label: 'Locations', icon: IconMapPin },
      { to: '/analytics/categories', label: 'Job Categories', icon: IconLayers },
    ],
  },
  {
    label: 'Career',
    items: [
      { to: '/resume', label: 'Resume Intelligence', icon: IconFile },
      { to: '/assistant', label: 'AI Assistant', icon: IconChat },
    ],
  },
  {
    label: 'System',
    items: [{ to: '/etl', label: 'ETL Monitoring', icon: IconDatabase }],
  },
];

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
  ['/etl', 'ETL Monitoring'],
  ['/login', 'Log in'],
  ['/signup', 'Sign up'],
  ['/', 'Dashboard'],
];

function titleFor(pathname: string): string {
  return PAGE_TITLES.find(([prefix]) => pathname.startsWith(prefix))?.[1] ?? 'Dashboard';
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
  const location = useLocation();

  // A drawer that survives navigation covers the page you just asked for.
  useEffect(() => {
    setNavOpen(false);
  }, [location.pathname]);

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
            <span className="desktop-only">Job Market Intelligence</span>
            <span className="desktop-only" aria-hidden="true">
              /
            </span>
            <span className="breadcrumb-current">{titleFor(location.pathname)}</span>
          </nav>
        </div>

        <div className="header-actions">
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
          {NAV_GROUPS.map((group) => (
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

      <main className="app-main">
        {children}
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
        <span className="header-user-email" title={user.email}>
          {/* The name when we have it; accounts from before names were collected show the email. */}
          {user.fullName ?? user.email}
        </span>
        <button
          type="button"
          className="ghost small"
          disabled={signingOut}
          onClick={async () => {
            setSigningOut(true);
            try {
              await logout();
            } finally {
              setSigningOut(false);
              navigate('/login');
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
