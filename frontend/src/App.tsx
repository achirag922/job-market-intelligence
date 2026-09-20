import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { AiAssistant } from './pages/AiAssistant';
import { CompanyAnalytics } from './pages/CompanyAnalytics';
import { Dashboard } from './pages/Dashboard';
import { JobDetails } from './pages/JobDetails';
import { JobExplorer } from './pages/JobExplorer';
import { JobIntelligence } from './pages/JobIntelligence';
import { LocationAnalytics } from './pages/LocationAnalytics';
import { SkillAnalytics } from './pages/SkillAnalytics';
import { ResumeIntelligence } from './pages/ResumeIntelligence';
import { SkillTrends } from './pages/SkillTrends';

const NAV_ITEMS = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/jobs', label: 'Job Explorer', end: false },
  { to: '/analytics/categories', label: 'Job Intelligence', end: false },
  { to: '/analytics/skills', label: 'Skills', end: false },
  { to: '/analytics/trends', label: 'Trends', end: false },
  { to: '/analytics/companies', label: 'Companies', end: false },
  { to: '/analytics/locations', label: 'Locations', end: false },
  { to: '/resume', label: 'Resume', end: false },
  { to: '/assistant', label: 'Ask the Data', end: false },
];

export default function App() {
  return (
    <div className="app">
      <header>
        <h1 className="brand">Job Market Intelligence</h1>
        <nav>
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </header>

      <main>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/jobs" element={<JobExplorer />} />
          <Route path="/jobs/:id" element={<JobDetails />} />
          <Route path="/analytics/categories" element={<JobIntelligence />} />
          <Route path="/analytics/skills" element={<SkillAnalytics />} />
          <Route path="/analytics/trends" element={<SkillTrends />} />
          <Route path="/analytics/companies" element={<CompanyAnalytics />} />
          <Route path="/analytics/locations" element={<LocationAnalytics />} />
          <Route path="/resume" element={<ResumeIntelligence />} />
          <Route path="/assistant" element={<AiAssistant />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>

      <footer>
        Data is synthetic and for development only. It does not describe the real job market.
      </footer>
    </div>
  );
}
