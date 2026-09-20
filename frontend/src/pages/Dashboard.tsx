import { api } from '../api/client';
import type { CompanyDemand, LocationDemand, Overview, PagedResponse, SkillDemand } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { DemandBarChart } from '../components/DemandBarChart';
import { useApi } from '../hooks/useApi';

const CHART_SIZE = 8;

export function Dashboard() {
  const overview = useApi<Overview>(() => api.overview(), []);
  const skills = useApi<SkillDemand[]>(() => api.topSkills(CHART_SIZE), []);
  const locations = useApi<PagedResponse<LocationDemand>>(
    () => api.locationDemand(0, CHART_SIZE),
    [],
  );
  const companies = useApi<PagedResponse<CompanyDemand>>(
    () => api.companyDemand(0, CHART_SIZE),
    [],
  );

  return (
    <section>
      <h1>Dashboard</h1>

      <AsyncPanel state={overview}>
        {(data) => (
          <div className="stat-grid">
            <StatCard label="Total Jobs" value={data.totalJobs} />
            <StatCard label="Total Companies" value={data.totalCompanies} />
            <StatCard label="Total Skills" value={data.totalSkills} />
            <StatCard label="Total Locations" value={data.totalLocations} />
          </div>
        )}
      </AsyncPanel>

      <div className="card">
        <h2>Top Skills</h2>
        <AsyncPanel state={skills} isEmpty={(data) => data.length === 0} empty="No skills recorded yet.">
          {(data) => (
            <DemandBarChart
              data={data.map((row) => ({ label: row.skill, value: row.jobCount }))}
            />
          )}
        </AsyncPanel>
      </div>

      <div className="card">
        <h2>Jobs by Location</h2>
        <AsyncPanel
          state={locations}
          isEmpty={(data) => data.content.length === 0}
          empty="No located postings yet."
        >
          {(data) => (
            <DemandBarChart
              data={data.content.map((row) => ({
                label: row.location.displayName,
                value: row.jobCount,
              }))}
            />
          )}
        </AsyncPanel>
      </div>

      <div className="card">
        <h2>Jobs by Company</h2>
        <AsyncPanel
          state={companies}
          isEmpty={(data) => data.content.length === 0}
          empty="No companies yet."
        >
          {(data) => (
            <DemandBarChart
              data={data.content.map((row) => ({ label: row.company.name, value: row.jobCount }))}
            />
          )}
        </AsyncPanel>
      </div>
    </section>
  );
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="stat-card">
      <span className="stat-value">{value.toLocaleString('en-US')}</span>
      <span className="stat-label">{label}</span>
    </div>
  );
}
