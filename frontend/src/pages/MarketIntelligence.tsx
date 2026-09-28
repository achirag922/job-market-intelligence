import { useMemo, useState } from 'react';
import { api } from '../api/client';
import type {
  CategoryDemand,
  MarketCompanies,
  MarketFilters,
  MarketLocations,
  MarketRemote,
  MarketSalary,
  MarketScope,
  MarketSkills,
  WorkMode,
} from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { BarChartPanel, MultiLineChartPanel, PieChartPanel } from '../components/charts';
import { DebouncedInput } from '../components/DebouncedInput';
import { Badge, Card, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { EXPERIENCE_OPTIONS } from './jobSearchState';

const MODE_LABEL: Record<WorkMode, string> = {
  REMOTE: 'Remote',
  HYBRID: 'Hybrid',
  ON_SITE: 'On-site',
  NOT_STATED: 'Not stated',
};

/** "Sep 2026" from "2026-09-01". */
export function monthLabel(iso: string): string {
  const [year, month] = iso.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, 1)).toLocaleDateString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' });
}

function money(value?: number): string {
  return value === undefined || value === null ? '—' : value.toLocaleString('en-US');
}

/** What postings and months a section covers, so older data is never read as current. */
export function ScopeLine({ scope }: { scope: MarketScope }) {
  const months =
    scope.earliestMonth && scope.latestMonth
      ? scope.earliestMonth === scope.latestMonth
        ? monthLabel(scope.earliestMonth)
        : `${monthLabel(scope.earliestMonth)} – ${monthLabel(scope.latestMonth)}`
      : 'no dated postings';
  return (
    <p className="muted small market-scope">
      {scope.postings.toLocaleString('en-US')} postings · posted {months}
      {scope.latestPostingInData ? ` · newest posting in the data: ${scope.latestPostingInData}` : ''}
    </p>
  );
}

function Notes({ notes }: { notes: string[] }) {
  if (notes.length === 0) {
    return null;
  }
  return (
    <ul className="market-notes">
      {notes.map((note) => (
        <li key={note} className="muted small">
          {note}
        </li>
      ))}
    </ul>
  );
}

/**
 * V7.5: salary, location, work-mode, company and skill views of the job market. Every
 * figure comes from the backend, counted from JMIP's postings; nothing here forecasts.
 */
export function MarketIntelligence() {
  const [filters, setFilters] = useState<MarketFilters>({});
  const set = (key: keyof MarketFilters, value: string) =>
    setFilters((previous) => ({ ...previous, [key]: key === 'months' ? (value ? Number(value) : undefined) : value || undefined }));
  const key = JSON.stringify(filters);

  const categories = useApi<CategoryDemand[]>(() => api.jobCategories(), []);
  const salary = useApi<MarketSalary>(() => api.marketSalary(filters), [key]);
  const locations = useApi<MarketLocations>(() => api.marketLocations(filters), [key]);
  const remote = useApi<MarketRemote>(() => api.marketRemote(filters), [key]);
  const companies = useApi<MarketCompanies>(() => api.marketCompanies(filters), [key]);
  const skills = useApi<MarketSkills>(() => api.marketSkills(filters), [key]);

  return (
    <>
      <PageHeader
        title="Market Intelligence"
        description="Salaries, locations, work modes, hiring companies and skill demand, counted from the postings in JMIP. Trends describe past posting months; they are not forecasts."
      />

      <div className="filters" role="group" aria-label="Market filters">
        <label>
          Job category
          <select value={filters.category ?? ''} onChange={(e) => set('category', e.target.value)}>
            <option value="">All categories</option>
            {(categories.data ?? []).map((option) => (
              <option key={option.category} value={option.category}>
                {option.category}
              </option>
            ))}
          </select>
        </label>
        <label>
          Location
          <DebouncedInput value={filters.location ?? ''} onCommit={(value) => set('location', value)} placeholder="City, state or country" aria-label="Location" />
        </label>
        <label>
          Experience
          <select value={filters.experience ?? ''} onChange={(e) => set('experience', e.target.value)}>
            <option value="">Any experience</option>
            {EXPERIENCE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Period
          <select value={filters.months ?? ''} onChange={(e) => set('months', e.target.value)}>
            <option value="">All posting months</option>
            <option value="3">Last 3 months of data</option>
            <option value="6">Last 6 months of data</option>
            <option value="12">Last 12 months of data</option>
          </select>
        </label>
      </div>

      <Card title="Salary" description="Averages of the stated minimum and maximum, per currency. Currencies are never converted or combined.">
        <AsyncPanel state={salary} skeleton="table">
          {(data) => <SalarySection data={data} />}
        </AsyncPanel>
      </Card>

      <Card title="Location demand" description="Where the postings are, and how many state no location at all.">
        <AsyncPanel state={locations} skeleton="chart">
          {(data) => (
            <>
              <ScopeLine scope={data.scope} />
              <div className="stat-grid" style={{ marginBottom: 12 }}>
                <StatCard label="With a location" value={data.locationStated} />
                <StatCard label="No location stated" value={data.locationNotStated} />
              </div>
              <BarChartPanel
                data={data.topLocations.map((row) => ({ label: row.location, value: row.postings }))}
                valueLabel="Postings"
                emptyMessage="No postings with a location match these filters."
              />
              <Notes notes={data.notes} />
            </>
          )}
        </AsyncPanel>
      </Card>

      <Card title="Remote, hybrid and on-site" description="Work mode as the postings describe it.">
        <AsyncPanel state={remote} skeleton="chart">
          {(data) => <RemoteSection data={data} />}
        </AsyncPanel>
      </Card>

      <Card title="Hiring companies" description="Postings per company in JMIP's dataset, and per posting month for the top five.">
        <AsyncPanel state={companies} skeleton="chart">
          {(data) => (
            <>
              <ScopeLine scope={data.scope} />
              <BarChartPanel
                data={data.topCompanies.map((row) => ({ label: row.company, value: row.postings }))}
                valueLabel="Postings"
                emptyMessage="No postings match these filters."
              />
              <h3 className="resume-compare-title">Postings per month</h3>
              <MultiLineChartPanel
                rows={monthRows(data.trend[0]?.points.map((point) => point.month) ?? [], (month) =>
                  Object.fromEntries(data.trend.map((series) => [
                    `c${series.companyId}`,
                    series.points.find((point) => point.month === month)?.postings ?? 0,
                  ])))}
                series={data.trend.map((series) => ({ key: `c${series.companyId}`, label: series.company }))}
                emptyMessage="No dated postings to chart."
              />
              <Notes notes={data.notes} />
            </>
          )}
        </AsyncPanel>
      </Card>

      <Card title="Skill demand" description="The skills most often asked for, and how their share has moved between earlier and recent posting months.">
        <AsyncPanel state={skills} skeleton="chart">
          {(data) => <SkillSection data={data} />}
        </AsyncPanel>
      </Card>
    </>
  );
}

/** One chart row per month, labelled for the axis. */
function monthRows(months: string[], values: (month: string) => Record<string, number | null>) {
  return months.map((month) => ({ label: monthLabel(month), ...values(month) }));
}

function SalarySection({ data }: { data: MarketSalary }) {
  const currencies = data.byCurrency.map((row) => row.currency);
  const [chosen, setChosen] = useState<string | null>(null);
  const currency = chosen && currencies.includes(chosen) ? chosen : currencies[0];
  const months = useMemo(() => [...new Set(data.trend.map((point) => point.month))].sort(), [data.trend]);

  return (
    <>
      <ScopeLine scope={data.scope} />
      <p className="small">
        {data.postingsWithSalary} of {data.scope.postings} postings state a salary.
      </p>
      {data.byCurrency.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Salary by currency</caption>
            <thead>
              <tr>
                <th scope="col">Currency</th>
                <th scope="col" className="tabular">Postings</th>
                <th scope="col" className="tabular">Average range</th>
                <th scope="col" className="tabular">Lowest – highest</th>
                <th scope="col">Sample</th>
              </tr>
            </thead>
            <tbody>
              {data.byCurrency.map((row) => (
                <tr key={row.currency}>
                  <td>{row.currency}</td>
                  <td className="tabular">{row.postings}</td>
                  <td className="tabular">{money(row.averageMin)} – {money(row.averageMax)}</td>
                  <td className="tabular">{money(row.lowestMin)} – {money(row.highestMax)}</td>
                  <td>{row.reliable ? <Badge tone="success">Enough postings</Badge> : <Badge tone="warning">Too few postings</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data.byCategory.length > 0 && (
        <details className="market-details">
          <summary>By job category</summary>
          <div className="table-wrap">
            <table>
              <caption className="visually-hidden">Salary by category</caption>
              <thead>
                <tr>
                  <th scope="col">Category</th>
                  <th scope="col">Currency</th>
                  <th scope="col" className="tabular">Postings</th>
                  <th scope="col" className="tabular">Average range</th>
                </tr>
              </thead>
              <tbody>
                {data.byCategory.map((row) => (
                  <tr key={`${row.category}-${row.currency}`}>
                    <td>{row.category}</td>
                    <td>{row.currency}</td>
                    <td className="tabular">
                      {row.postings}
                      {!row.reliable && ' *'}
                    </td>
                    <td className="tabular">{money(row.averageMin)} – {money(row.averageMax)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted small">* fewer postings than the minimum sample.</p>
        </details>
      )}
      {currency && (
        <>
          <div className="row" style={{ gap: 8, alignItems: 'center', marginTop: 12 }}>
            <h3 className="resume-compare-title" style={{ margin: 0 }}>Average by posting month</h3>
            <label className="field">
              <span className="visually-hidden">Currency</span>
              <select aria-label="Salary trend currency" value={currency} onChange={(e) => setChosen(e.target.value)}>
                {currencies.map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <MultiLineChartPanel
            rows={monthRows(months, (month) => {
              const point = data.trend.find((item) => item.month === month && item.currency === currency);
              return { min: point?.averageMin ?? null, max: point?.averageMax ?? null };
            })}
            series={[{ key: 'min', label: `Average minimum (${currency})` }, { key: 'max', label: `Average maximum (${currency})` }]}
            emptyMessage="No dated salaries to chart."
          />
        </>
      )}
      <Notes notes={data.notes} />
    </>
  );
}

function RemoteSection({ data }: { data: MarketRemote }) {
  return (
    <>
      <ScopeLine scope={data.scope} />
      <div className="market-split">
        <PieChartPanel
          data={data.distribution.filter((row) => row.postings > 0).map((row) => ({ label: MODE_LABEL[row.mode], value: row.postings }))}
          emptyMessage="No postings match these filters."
        />
        <ul className="market-mode-list" aria-label="Work mode distribution">
          {data.distribution.map((row) => (
            <li key={row.mode}>
              <strong>{MODE_LABEL[row.mode]}</strong> {row.postings} ({row.percentageOfPostings}%)
            </li>
          ))}
        </ul>
      </div>
      <h3 className="resume-compare-title">Postings per month</h3>
      <MultiLineChartPanel
        rows={data.trend.map((point) => ({
          label: monthLabel(point.month), remote: point.remote, hybrid: point.hybrid, onSite: point.onSite, notStated: point.notStated,
        }))}
        series={[
          { key: 'remote', label: 'Remote' },
          { key: 'hybrid', label: 'Hybrid' },
          { key: 'onSite', label: 'On-site' },
          { key: 'notStated', label: 'Not stated' },
        ]}
        emptyMessage="No dated postings to chart."
      />
      <p className="muted small">{data.method}</p>
      <Notes notes={data.notes} />
    </>
  );
}

function SkillSection({ data }: { data: MarketSkills }) {
  const trends = data.trend?.trends ?? [];
  return (
    <>
      <ScopeLine scope={data.scope} />
      <BarChartPanel
        data={data.topSkills.map((row) => ({ label: row.skill, value: row.percentageOfPostings }))}
        valueLabel="Share of postings"
        suffix="%"
        emptyMessage="No postings with skills match these filters."
      />
      {data.trend && (
        <>
          <h3 className="resume-compare-title">Earlier vs recent posting months</h3>
          {data.trend.window.fromPeriod && data.trend.window.toPeriod && (
            <p className="muted small">
              Stored skill history, {monthLabel(data.trend.window.fromPeriod)} – {monthLabel(data.trend.window.toPeriod)}
            </p>
          )}
          {trends.length > 0 && (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Skill demand change</caption>
                <thead>
                  <tr>
                    <th scope="col">Skill</th>
                    <th scope="col" className="tabular">Earlier share</th>
                    <th scope="col" className="tabular">Recent share</th>
                    <th scope="col" className="tabular">Change (points)</th>
                    <th scope="col">Direction</th>
                  </tr>
                </thead>
                <tbody>
                  {trends.map((trend) => (
                    <tr key={trend.skillId}>
                      <td>{trend.skill}</td>
                      <td className="tabular">{trend.earlierSharePercentage}%</td>
                      <td className="tabular">{trend.recentSharePercentage}%</td>
                      <td className="tabular">
                        {trend.changeInPercentagePoints > 0 ? '+' : ''}
                        {trend.changeInPercentagePoints}
                      </td>
                      <td>
                        <Badge tone={trend.direction === 'RISING' ? 'success' : trend.direction === 'FALLING' ? 'danger' : 'neutral'}>
                          {trend.direction.toLowerCase()}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      <Notes notes={data.notes} />
    </>
  );
}
