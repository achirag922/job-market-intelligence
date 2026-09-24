import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { AiAssistant } from './pages/AiAssistant';
import { EtlMonitoring } from './pages/EtlMonitoring';
import { CompanyAnalytics } from './pages/CompanyAnalytics';
import { Dashboard } from './pages/Dashboard';
import { JobDetails } from './pages/JobDetails';
import { JobExplorer } from './pages/JobExplorer';
import { JobIntelligence } from './pages/JobIntelligence';
import { LocationAnalytics } from './pages/LocationAnalytics';
import { ResumeIntelligence } from './pages/ResumeIntelligence';
import { SkillAnalytics } from './pages/SkillAnalytics';
import { SkillTrends } from './pages/SkillTrends';

/**
 * Routes, inside the application shell.
 *
 * <p>The shell owns the header, the sidebar and the page frame, so a page is only its own
 * content — no page repeats the chrome, and there is one place to change it.
 */
export default function App() {
  return (
    <AppShell>
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
        <Route path="/etl" element={<EtlMonitoring />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppShell>
  );
}
