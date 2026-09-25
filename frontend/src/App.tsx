import { Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { AiAssistant } from './pages/AiAssistant';
import { EtlMonitoring } from './pages/EtlMonitoring';
import { Login } from './pages/Login';
import { RequireAuth } from './auth/RequireAuth';
import { Signup } from './pages/Signup';
import { VerifyEmail } from './pages/VerifyEmail';
import { CompanyAnalytics } from './pages/CompanyAnalytics';
import { Dashboard } from './pages/Dashboard';
import { JobDetails } from './pages/JobDetails';
import { CareerGoals } from './pages/CareerGoals';
import { JobAlerts } from './pages/JobAlerts';
import { MarketIntelligence } from './pages/MarketIntelligence';
import { SavedJobs } from './pages/SavedJobs';
import { SavedJobsProvider } from './saved/SavedJobs';
import { JobExplorer } from './pages/JobExplorer';
import { JobIntelligence } from './pages/JobIntelligence';
import { LocationAnalytics } from './pages/LocationAnalytics';
import { ResumeIntelligence } from './pages/ResumeIntelligence';
import { SkillAnalytics } from './pages/SkillAnalytics';
import { SkillTrends } from './pages/SkillTrends';

/**
 * Routes. The authentication screens stand alone; everything else sits in the application shell.
 *
 * <p>The shell owns the header, the sidebar and the page frame, so a page is only its own
 * content — no page repeats the chrome, and there is one place to change it.
 */
export default function App() {
  return (
    <Routes>
      {/* Sign up, Login and Verify are full-screen, outside the application shell. */}
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<Signup />} />
      <Route path="/verify-email" element={<VerifyEmail />} />

      <Route element={<ShellLayout />}>
        {/* V6.10.3: everything inside the shell needs a signed-in user. */}
        <Route element={<RequireAuth />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/jobs" element={<JobExplorer />} />
          <Route path="/jobs/:id" element={<JobDetails />} />
          <Route path="/analytics/categories" element={<JobIntelligence />} />
          <Route path="/analytics/skills" element={<SkillAnalytics />} />
          <Route path="/analytics/trends" element={<SkillTrends />} />
          <Route path="/analytics/companies" element={<CompanyAnalytics />} />
          <Route path="/analytics/locations" element={<LocationAnalytics />} />
          <Route path="/market" element={<MarketIntelligence />} />
          <Route path="/resume" element={<ResumeIntelligence />} />
          <Route path="/alerts" element={<JobAlerts />} />
          <Route path="/career-goals" element={<CareerGoals />} />
          <Route path="/saved-jobs" element={<SavedJobs />} />
          <Route path="/assistant" element={<AiAssistant />} />
          <Route path="/etl" element={<EtlMonitoring />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

function ShellLayout() {
  // Saved jobs are shared by every page in the shell; signing out leaves the shell, which
  // drops them, so the next account never sees the last one's.
  return (
    <SavedJobsProvider>
      <AppShell>
        <Outlet />
      </AppShell>
    </SavedJobsProvider>
  );
}
