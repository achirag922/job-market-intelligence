import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { api } from '../api/client';
import type { SkillTrend, SkillTrends as SkillTrendsData, TrendDirection } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { useApi } from '../hooks/useApi';

/** Enough colours for the charted series; beyond that the chart stops being readable. */
const SERIES_COLOURS = ['#3b6ea5', '#b3261e', '#2e7d32', '#8a5a00', '#6a1b9a'];
const CHARTED_SERIES = 5;

const DIRECTIONS: { value: TrendDirection | ''; label: string }[] = [
  { value: '', label: 'Biggest movers' },
  { value: 'RISING', label: 'Rising only' },
  { value: 'FALLING', label: 'Falling only' },
  { value: 'STABLE', label: 'Holding steady' },
];

export function SkillTrends() {
  const [months, setMonths] = useState(6);
  const [direction, setDirection] = useState<TrendDirection | ''>('');

  const data = useApi<SkillTrendsData>(
    () => api.skillTrends(months, direction === '' ? undefined : direction, 20),
    [months, direction],
  );

  return (
    <section>
      <h1>Skill Trends</h1>
      <p className="subtitle">
        Which skills are gaining or losing ground. Demand is measured as a skill's share of
        postings, not its raw count — otherwise every skill would look like it was growing
        whenever the number of postings grew.
      </p>

      <form className="filters" onSubmit={(event) => event.preventDefault()}>
        <label>
          Window
          <select value={months} onChange={(event) => setMonths(Number(event.target.value))}>
            <option value={3}>Last 3 months</option>
            <option value={6}>Last 6 months</option>
            <option value={12}>Last 12 months</option>
          </select>
        </label>
        <label>
          Show
          <select
            value={direction}
            onChange={(event) => setDirection(event.target.value as TrendDirection | '')}
          >
            {DIRECTIONS.map((option) => (
              <option key={option.label} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      </form>

      <AsyncPanel
        state={data}
        isEmpty={(loaded) => loaded.trends.length === 0}
        empty="Not enough history yet. Trends need at least two months of dated postings."
      >
        {(loaded) => (
          <>
            <p className="subtitle">
              Comparing {loaded.window.earlierPeriods.length} earlier month(s) against{' '}
              {loaded.window.recentPeriods.length} recent one(s), across{' '}
              <strong>{loaded.window.totalJobsInWindow}</strong> postings. Skills with fewer
              than {loaded.window.minJobsThreshold} postings in the window are left out.
            </p>

            <div className="card">
              <h2>Share of postings over time</h2>
              <TrendLines trends={loaded.trends.slice(0, CHARTED_SERIES)} />
            </div>

            <table>
              <thead>
                <tr>
                  <th>Skill</th>
                  <th>Direction</th>
                  <th>Change</th>
                  <th>Earlier share</th>
                  <th>Recent share</th>
                  <th>Jobs in window</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {loaded.trends.map((trend) => (
                  <tr key={trend.skillId}>
                    <td>{trend.skill}</td>
                    <td>
                      <DirectionTag direction={trend.direction} />
                    </td>
                    <td>{formatPoints(trend.changeInPercentagePoints)}</td>
                    <td>{trend.earlierSharePercentage.toFixed(1)}%</td>
                    <td>{trend.recentSharePercentage.toFixed(1)}%</td>
                    <td>{trend.jobCountInWindow}</td>
                    <td>
                      <Link to={`/jobs?skill=${encodeURIComponent(trend.skill)}`}>View jobs</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </AsyncPanel>
    </section>
  );
}

/**
 * Recharts wants one row per x-value with a column per series, so the per-skill series
 * are pivoted into rows keyed by period.
 */
function TrendLines({ trends }: { trends: SkillTrend[] }) {
  if (trends.length === 0) {
    return <p className="status">Nothing to chart.</p>;
  }

  // Rows are keyed by skill id, not skill name. Recharts reads a dot in dataKey as a
  // nested path, so a skill called "Node.js" would be looked up as row.Node.js and plot
  // as an empty line. The display name goes through `name` instead.
  const periods = trends[0].series.map((point) => point.period);
  const rows = periods.map((period, index) => {
    const row: Record<string, string | number> = { period };
    trends.forEach((trend) => {
      row[seriesKey(trend)] = trend.series[index]?.sharePercentage ?? 0;
    });
    return row;
  });

  return (
    <ResponsiveContainer width="100%" height={320}>
      <LineChart data={rows} margin={{ top: 8, right: 24, bottom: 8, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="period" tick={{ fontSize: 12 }} />
        <YAxis unit="%" tick={{ fontSize: 12 }} />
        <Tooltip />
        <Legend />
        {trends.map((trend, index) => (
          <Line
            key={trend.skillId}
            type="monotone"
            dataKey={seriesKey(trend)}
            name={trend.skill}
            stroke={SERIES_COLOURS[index % SERIES_COLOURS.length]}
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
        ))}
      </LineChart>
    </ResponsiveContainer>
  );
}

function seriesKey(trend: SkillTrend): string {
  return `skill_${trend.skillId}`;
}

function DirectionTag({ direction }: { direction: TrendDirection }) {
  const symbol = direction === 'RISING' ? '▲' : direction === 'FALLING' ? '▼' : '—';
  return (
    <span className={`trend trend-${direction.toLowerCase()}`}>
      {symbol} {direction.charAt(0) + direction.slice(1).toLowerCase()}
    </span>
  );
}

/** Points, not percent, and signed so the direction is readable without the tag. */
function formatPoints(change: number): string {
  const sign = change > 0 ? '+' : '';
  return `${sign}${change.toFixed(1)} pts`;
}
