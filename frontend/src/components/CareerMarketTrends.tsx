import { useState } from 'react';
import { api } from '../api/client';
import type { CategoryDemand, MarketTrend, MarketTrends } from '../api/types';
import { useApi } from '../hooks/useApi';
import { AsyncPanel } from './AsyncPanel';
import { MultiLineChartPanel } from './charts';
import { Badge, Card, StatCard } from './ui';

const RANGES = [3, 6, 12, 24, 36];

const DIRECTION: Record<string, { label: string; tone: 'success' | 'danger' | 'neutral' | 'warning' }> = {
  INCREASING: { label: 'Increasing', tone: 'success' },
  DECREASING: { label: 'Decreasing', tone: 'danger' },
  STABLE: { label: 'Stable', tone: 'neutral' },
  INSUFFICIENT_DATA: { label: 'Insufficient data', tone: 'warning' },
};

const MODE_LABEL: Record<string, string> = { REMOTE: 'Remote', HYBRID: 'Hybrid', ON_SITE: 'On-site' };

/** "Jul 2026" from "2026-07-01". */
function month(iso?: string): string {
  if (!iso) {
    return '—';
  }
  const [year, m] = iso.split('-').map(Number);
  return new Date(Date.UTC(year, m - 1, 1)).toLocaleDateString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' });
}

function range(from?: string, to?: string): string {
  return from === to ? month(from) : `${month(from)}–${month(to)}`;
}

/** The direction, the change and the two periods it compares, or why there is none. */
export function TrendLabel({ trend }: { trend?: MarketTrend }) {
  if (!trend) {
    return null;
  }
  const direction = DIRECTION[trend.direction] ?? DIRECTION.INSUFFICIENT_DATA;
  const unit = trend.unit === 'PERCENTAGE_POINTS' ? ' pts' : '%';
  return (
    <span className="small">
      <Badge tone={direction.tone}>{direction.label}</Badge>{' '}
      {trend.change !== undefined && `${trend.change > 0 ? '+' : ''}${trend.change}${unit} `}
      {trend.earlierFrom ? (
        <span className="muted">({range(trend.earlierFrom, trend.earlierTo)} vs {range(trend.recentFrom, trend.recentTo)})</span>
      ) : (
        trend.note && <span className="muted">{trend.note}</span>
      )}
    </span>
  );
}

/**
 * V8.8: how demand has moved over past posting months, for one role or all of them. Historical
 * figures and the estimate are labelled separately; the estimate is never shown as a prediction.
 */
export function CareerMarketTrends({ categories }: { categories: CategoryDemand[] }) {
  const [category, setCategory] = useState('');
  const [months, setMonths] = useState(12);
  const trends = useApi<MarketTrends>(() => api.marketTrends({ category: category || undefined, months }), [category, months]);

  return (
    <Card
      title="Career market trends"
      description="Historical demand by posting month from JMIP's postings, compared between earlier and recent months. The estimate is separate and only shown when there is enough history."
    >
      <div className="row" style={{ gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        <label className="field">
          Role
          <select value={category} onChange={(event) => setCategory(event.target.value)}>
            <option value="">All roles</option>
            {categories.map((option) => (
              <option key={option.category} value={option.category}>
                {option.category}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          Time range
          <select value={months} onChange={(event) => setMonths(Number(event.target.value))}>
            {RANGES.map((value) => (
              <option key={value} value={value}>
                Last {value} months of data
              </option>
            ))}
          </select>
        </label>
      </div>
      <AsyncPanel state={trends} skeleton="chart">
        {(data) => <TrendsBody data={data} />}
      </AsyncPanel>
    </Card>
  );
}

function TrendsBody({ data }: { data: MarketTrends }) {
  const remote = data.workModeTrends.find((mode) => mode.mode === 'REMOTE');
  const historyRows = data.volume.map((point) => ({ label: month(point.month), historical: point.postings ?? null, estimate: null }));
  const estimateRows = data.forecast.estimates.map((point) => ({ label: month(point.month), historical: null, estimate: point.estimatedPostings }));

  return (
    <div className="stack" style={{ gap: 16 }}>
      <p className="muted small" style={{ margin: 0 }}>
        Historical data: {range(data.period.fromMonth, data.period.toMonth)} · {data.period.coveredMonths} month(s) with data
        {data.period.latestPostedDate && ` · newest posting ${data.period.latestPostedDate}`} · {data.period.source}
      </p>
      {data.notes.length > 0 && (
        <ul className="muted small" style={{ margin: 0 }}>
          {data.notes.map((note) => <li key={note}>{note}</li>)}
        </ul>
      )}

      <div className="stat-grid">
        <StatCard label="Job volume (historical)" value={DIRECTION[data.volumeTrend.direction]?.label} hint={data.volumeTrend.basis} />
        {data.shareTrend && <StatCard label="Share of all postings" value={DIRECTION[data.shareTrend.direction]?.label} hint="Percentage points" />}
        {data.salary && <StatCard label={`Salary (${data.salary.currency})`} value={DIRECTION[data.salary.trend.direction]?.label} hint="Stated salary midpoint" />}
        {remote && <StatCard label="Remote share" value={DIRECTION[remote.trend.direction]?.label} hint="Percentage points" />}
      </div>

      <section aria-label="Job volume">
        <h3 className="small">Postings per month</h3>
        <p className="small" style={{ margin: '0 0 6px' }}>Volume: <TrendLabel trend={data.volumeTrend} /></p>
        {data.shareTrend && <p className="small" style={{ margin: '0 0 6px' }}>Share of all postings: <TrendLabel trend={data.shareTrend} /></p>}
        <MultiLineChartPanel
          height={240}
          rows={[...historyRows, ...estimateRows]}
          series={[
            { key: 'historical', label: 'Postings (historical)' },
            { key: 'estimate', label: 'Estimate (not a prediction)' },
          ]}
          emptyMessage="No dated postings in this period."
        />
        <Forecast data={data} />
      </section>

      <section aria-label="Skill trends" className="gap-columns">
        <SkillList title="Growing skills" moves={data.skills.growing} />
        <SkillList title="Declining skills" moves={data.skills.declining} />
        <div className="gap-column">
          <p className="muted small" style={{ margin: 0 }}>
            {data.skills.source ?? 'Skill trends'}
            {data.skills.earlierFrom && ` · ${range(data.skills.earlierFrom, data.skills.earlierTo)} vs ${range(data.skills.recentFrom, data.skills.recentTo)}`}
          </p>
          {data.skills.note && <p className="muted small">{data.skills.note}</p>}
        </div>
      </section>

      {data.salary ? (
        <section aria-label="Salary trend">
          <h3 className="small">Salary in {data.salary.currency} (historical)</h3>
          <p className="small" style={{ margin: '0 0 6px' }}><TrendLabel trend={data.salary.trend} /></p>
          <MultiLineChartPanel
            height={200}
            rows={data.salary.series.map((point) => ({ label: month(point.month), min: point.averageMin ?? null, max: point.averageMax ?? null }))}
            series={[{ key: 'min', label: 'Average minimum' }, { key: 'max', label: 'Average maximum' }]}
          />
          {data.salary.note && <p className="muted small">{data.salary.note}</p>}
        </section>
      ) : (
        <p className="muted small">No posting in this selection states a salary, so there is no salary trend.</p>
      )}

      <div className="gap-columns">
        <section aria-label="Location trends" className="gap-column">
          <h3 className="small">Locations</h3>
          {data.locations.length === 0 ? (
            <p className="muted small">No posting in this selection states a location.</p>
          ) : (
            <ul className="stack small" style={{ gap: 4, listStyle: 'none', padding: 0, margin: 0 }}>
              {data.locations.map((location) => (
                <li key={location.locationId}>
                  <strong>{location.location}</strong> <span className="muted">({location.postings})</span> <TrendLabel trend={location.trend} />
                </li>
              ))}
            </ul>
          )}
        </section>
        <section aria-label="Work mode trends" className="gap-column">
          <h3 className="small">Work-mode shares</h3>
          <ul className="stack small" style={{ gap: 4, listStyle: 'none', padding: 0, margin: 0 }}>
            {data.workModeTrends.map((mode) => (
              <li key={mode.mode}>
                <strong>{MODE_LABEL[mode.mode] ?? mode.mode}</strong> <TrendLabel trend={mode.trend} />
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}

function Forecast({ data }: { data: MarketTrends }) {
  const forecast = data.forecast;
  if (forecast.status !== 'ESTIMATE') {
    return (
      <p className="small" role="note">
        <Badge tone="warning">Insufficient data</Badge> <span className="muted">{forecast.note ?? 'Not enough history for an estimate.'}</span>
      </p>
    );
  }
  return (
    <div className="stack small" role="note" style={{ gap: 4 }}>
      <p style={{ margin: 0 }}>
        <Badge tone="warning">Estimate</Badge> {forecast.label}
      </p>
      <p className="muted" style={{ margin: 0 }}>
        Based on {forecast.basedOnMonths} months with data ({range(forecast.fromMonth, forecast.toMonth)}): about{' '}
        {(forecast.slopePerMonth ?? 0) >= 0
          ? `${forecast.slopePerMonth} posting(s) more per month`
          : `${-(forecast.slopePerMonth ?? 0)} posting(s) fewer per month`}
        {forecast.rSquared !== undefined && `; the line explains ${Math.round(forecast.rSquared * 100)}% of the month-to-month variation (R²)`}.
      </p>
      <p style={{ margin: 0 }}>
        {forecast.estimates.map((point) => `${month(point.month)}: ~${point.estimatedPostings}`).join(' · ')}{' '}
        <span className="muted">(estimated postings)</span>
      </p>
      {forecast.note && <p className="muted" style={{ margin: 0 }}>{forecast.note}</p>}
    </div>
  );
}

function SkillList({ title, moves }: { title: string; moves: MarketTrends['skills']['growing'] }) {
  return (
    <div className="gap-column">
      <h3 className="small">{title}</h3>
      {moves.length === 0 ? (
        <p className="muted small">None in this period.</p>
      ) : (
        <ol className="small" style={{ margin: 0, paddingLeft: 18 }}>
          {moves.map((move) => (
            <li key={move.skill}>
              {move.skill}{' '}
              <span className="muted">
                {move.earlierSharePercentage}% → {move.recentSharePercentage}% ({move.changeInPercentagePoints > 0 ? '+' : ''}
                {move.changeInPercentagePoints} pts)
              </span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
