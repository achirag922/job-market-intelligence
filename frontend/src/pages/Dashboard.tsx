import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type {
  CategoryDemand,
  CompanyDemand,
  ExperienceDistribution,
  LocationDemand,
  Overview,
  PagedResponse,
  SkillDemand,
  SkillTrends,
} from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { BarChartPanel, LineChartPanel, PieChartPanel } from '../components/charts';
import { Card, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';

const CHART_SIZE = 8;
const TREND_MONTHS = 6;

/**
 * The landing page: what the dataset contains, then the six views that answer the
 * questions people actually arrive with.
 *
 * <p>Every figure here is read from an analytics endpoint. Nothing is derived in the
 * browser and no card shows a change indicator, because the API does not return one for
 * these metrics — a plausible-looking arrow with nothing behind it would be worse than no
 * arrow at all.
 */
export function Dashboard() {
  const overview = useApi<Overview>(() => api.overview(), []);
  const skills = useApi<SkillDemand[]>(() => api.topSkills(CHART_SIZE), []);
  const categories = useApi<CategoryDemand[]>(() => api.jobCategories(), []);
  const locations = useApi<PagedResponse<LocationDemand>>(() => api.locationDemand(0, CHART_SIZE), []);
  const companies = useApi<PagedResponse<CompanyDemand>>(() => api.companyDemand(0, CHART_SIZE), []);
  const experience = useApi<ExperienceDistribution>(() => api.experienceDistribution(), []);
  const trends = useApi<SkillTrends>(() => api.skillTrends(TREND_MONTHS, undefined, 1), []);

  // The leading category and skill come from the same calls the charts below use, so
  // naming them in a card costs no extra request.
  const topCategory = categories.data?.[0];
  const topSkill = skills.data?.[0];

  return (
    <>
      <PageHeader
        title="Job Market Intelligence"
        description="Understand job demand, skills, companies and career trends across the dataset."
      />

      <AsyncPanel state={overview} skeleton="cards">
        {(data) => (
          <div className="stat-grid">
            <StatCard label="Total jobs" value={data.totalJobs} hint="Postings ingested" />
            <StatCard label="Companies" value={data.totalCompanies} hint="Distinct employers" />
            <StatCard label="Skills tracked" value={data.totalSkills} hint="In the dictionary" />
            <StatCard
              label="Top job category"
              value={topCategory?.category ?? null}
              hint={topCategory ? `${topCategory.jobCount} postings` : 'Not classified yet'}
            />
            <StatCard
              label="Most in-demand skill"
              value={topSkill?.skill ?? null}
              hint={topSkill ? `${topSkill.jobCount} postings` : 'No skills recorded'}
            />
          </div>
        )}
      </AsyncPanel>

      <div className="chart-grid">
        <Card
          title="Jobs by category"
          description="Share of classified postings. Each posting belongs to one category."
          actions={<Link to="/analytics/categories">View all</Link>}
        >
          <AsyncPanel
            state={categories}
            skeleton="chart"
            isEmpty={(data) => data.length === 0}
            empty="No postings have been classified yet."
          >
            {(data) => (
              <PieChartPanel
                data={data.map((row) => ({ label: row.category, value: row.jobCount }))}
              />
            )}
          </AsyncPanel>
        </Card>

        <Card
          title="Top skills"
          description="Postings mentioning each skill."
          actions={<Link to="/analytics/skills">View all</Link>}
        >
          <AsyncPanel
            state={skills}
            skeleton="chart"
            isEmpty={(data) => data.length === 0}
            empty="No skills have been extracted yet."
          >
            {(data) => (
              <BarChartPanel
                data={data.map((row) => ({ label: row.skill, value: row.jobCount }))}
                valueLabel="Postings"
              />
            )}
          </AsyncPanel>
        </Card>

        <Card
          title="Skill trend"
          description={`Share of postings over the last ${TREND_MONTHS} months, for the biggest mover.`}
          actions={<Link to="/analytics/trends">View all</Link>}
        >
          <AsyncPanel
            state={trends}
            skeleton="chart"
            isEmpty={(data) => data.trends.length === 0}
            empty="Not enough history yet to show a trend."
          >
            {(data) => {
              const trend = data.trends[0];
              return (
                <>
                  <p className="card-description" style={{ marginBottom: 12 }}>
                    {trend.skill} · {trend.direction.toLowerCase()}
                  </p>
                  <LineChartPanel
                    data={trend.series.map((point) => ({
                      label: point.period.slice(0, 7),
                      value: point.sharePercentage,
                    }))}
                    valueLabel="Share of postings"
                    suffix="%"
                  />
                </>
              );
            }}
          </AsyncPanel>
        </Card>

        <Card
          title="Jobs by location"
          description="Remote postings have no location and are not counted."
          actions={<Link to="/analytics/locations">View all</Link>}
        >
          <AsyncPanel
            state={locations}
            skeleton="chart"
            isEmpty={(data) => data.content.length === 0}
            empty="No postings carry a location yet."
          >
            {(data) => (
              <BarChartPanel
                data={data.content.map((row) => ({
                  label: row.location.displayName,
                  value: row.jobCount,
                }))}
                valueLabel="Postings"
              />
            )}
          </AsyncPanel>
        </Card>

        <Card
          title="Top companies"
          description="Employers ranked by posting count."
          actions={<Link to="/analytics/companies">View all</Link>}
        >
          <AsyncPanel
            state={companies}
            skeleton="chart"
            isEmpty={(data) => data.content.length === 0}
            empty="No companies have been ingested yet."
          >
            {(data) => (
              <BarChartPanel
                data={data.content.map((row) => ({
                  label: row.company.name,
                  value: row.jobCount,
                }))}
                valueLabel="Postings"
              />
            )}
          </AsyncPanel>
        </Card>

        <Card
          title="Experience required"
          description="Bands are half open. Postings stating no requirement form their own band."
        >
          <AsyncPanel
            state={experience}
            skeleton="chart"
            isEmpty={(data) => data.buckets.length === 0}
            empty="No experience data available."
          >
            {(data) => (
              <BarChartPanel
                data={data.buckets.map((bucket) => ({
                  label: bucket.label,
                  value: bucket.jobCount,
                }))}
                valueLabel="Postings"
              />
            )}
          </AsyncPanel>
        </Card>
      </div>
    </>
  );
}
