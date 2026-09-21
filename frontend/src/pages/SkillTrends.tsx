import { useMemo, useState } from 'react';
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
import { Card, EmptyState, PageHeader, StatCard } from '../components/ui';
import { IconArrowDown, IconArrowUp, IconMinus } from '../components/icons';
import { useApi } from '../hooks/useApi';

/**
 * Series colours, taken from the validated categorical slots in fixed order.
 *
 * <p>Five is the cap. The palette has eight validated slots and the order is what makes
 * the set safe for colour-vision deficiency; past five lines a chart stops being readable
 * anyway, and the table below carries every row regardless.
 */
const SERIES_SLOTS = [
  'var(--series-1)',
  'var(--series-2)',
  'var(--series-3)',
  'var(--series-4)',
  'var(--series-5)',
];
const CHARTED_SERIES = 5;

const DIRECTIONS: { value: TrendDirection | ''; label: string }[] = [
  { value: '', label: 'Biggest movers' },
  { value: 'RISING', label: 'Rising only' },
  { value: 'FALLING', label: 'Falling only' },
  { value: 'STABLE', label: 'Holding steady' },
];

/**
 * How skill demand has moved.
 *
 * <p>Every figure here — the direction, the change in points, the shares — is computed by
 * the backend from stored monthly snapshots. Nothing on this page derives a trend from
 * the data it was given; the page only draws what it was told.
 */
export function SkillTrends() {
  const [months, setMonths] = useState(6);
  const [direction, setDirection] = useState<TrendDirection | ''>('');
  const [selected, setSelected] = useState<number[]>([]);

  const data = useApi<SkillTrendsData>(
    () => api.skillTrends(months, direction === '' ? undefined : direction, 20),
    [months, direction],
  );

  // Nothing chosen means the biggest movers, which is what someone arriving wants.
  //
  // Memoised against the response rather than against a `?? []` fallback: that fallback
  // is a fresh array on every render, so depending on it would recompute every time and
  // the memo would do nothing.
  const loadedTrends = data.data?.trends;
  const charted = useMemo(() => {
    const rows = loadedTrends ?? [];
    if (selected.length === 0) {
      return rows.slice(0, CHARTED_SERIES);
    }
    return rows.filter((trend) => selected.includes(trend.skillId)).slice(0, CHARTED_SERIES);
  }, [loadedTrends, selected]);

  const toggle = (skillId: number) => {
    setSelected((current) => {
      if (current.includes(skillId)) {
        return current.filter((id) => id !== skillId);
      }
      // Silently dropping a sixth pick would look broken, so the cap replaces the oldest.
      return current.length >= CHARTED_SERIES
        ? [...current.slice(1), skillId]
        : [...current, skillId];
    });
  };

  return (
    <>
      <PageHeader
        title="Skill Trends"
        description="Which skills are gaining or losing ground. Demand is a skill's share of postings, not its raw count — otherwise every skill would look like it was growing whenever the number of postings grew."
      />

      <form className="filters" onSubmit={(event) => event.preventDefault()}>
        <label>
          Time range
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
        <div className="filter-actions">
          <button type="button" className="ghost" disabled={selected.length === 0} onClick={() => setSelected([])}>
            Reset selection
          </button>
        </div>
      </form>

      <AsyncPanel
        state={data}
        skeleton="chart"
        isEmpty={(loaded) => loaded.trends.length === 0}
        emptyTitle="Not enough history"
        empty="Trends need at least two months of dated postings. Ingest more data, or widen the time range."
      >
        {(loaded) => {
          const rising = loaded.trends.filter((t) => t.direction === 'RISING').length;
          const falling = loaded.trends.filter((t) => t.direction === 'FALLING').length;
          return (
            <>
              <div className="stat-grid">
                <StatCard label="Skills tracked" value={loaded.trends.length} hint="In this window" />
                <StatCard label="Rising" value={rising} hint="Gaining share" />
                <StatCard label="Falling" value={falling} hint="Losing share" />
                <StatCard
                  label="Postings in window"
                  value={loaded.window.totalJobsInWindow}
                  hint={`${loaded.window.earlierPeriods.length} earlier vs ${loaded.window.recentPeriods.length} recent months`}
                />
              </div>

              <Card
                title="Share of postings over time"
                description={`Skills with fewer than ${loaded.window.minJobsThreshold} postings in the window are left out, because one posting becoming two is noise rather than a trend.`}
              >
                {charted.length === 0 ? (
                  <EmptyState
                    title="No skills selected"
                    message="Pick a skill from the table below to chart it."
                  />
                ) : (
                  <TrendLines trends={charted} />
                )}

                {/* The selector doubles as the legend's counterpart: chosen skills are
                    pressed, so identity never rests on the line colour alone. */}
                <div className="skill-list" style={{ marginTop: 16, marginBottom: 0 }}>
                  {loaded.trends.slice(0, 12).map((trend) => {
                    const isOn = charted.some((row) => row.skillId === trend.skillId);
                    return (
                      <li key={trend.skillId}>
                        <button
                          type="button"
                          className="skill-tag"
                          aria-pressed={isOn}
                          onClick={() => toggle(trend.skillId)}
                        >
                          {isOn && <span aria-hidden="true">✓</span>}
                          {trend.skill}
                        </button>
                      </li>
                    );
                  })}
                </div>
              </Card>

              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Skills by change in demand</caption>
                  <thead>
                    <tr>
                      <th scope="col">Skill</th>
                      <th scope="col">Direction</th>
                      <th scope="col" className="numeric">
                        Change
                      </th>
                      <th scope="col" className="numeric">
                        Earlier share
                      </th>
                      <th scope="col" className="numeric">
                        Recent share
                      </th>
                      <th scope="col" className="numeric">
                        Postings
                      </th>
                      <th scope="col">
                        <span className="visually-hidden">Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {loaded.trends.map((trend) => (
                      <tr key={trend.skillId}>
                        <td className="cell-strong">{trend.skill}</td>
                        <td>
                          <DirectionTag direction={trend.direction} />
                        </td>
                        <td className="numeric">{formatPoints(trend.changeInPercentagePoints)}</td>
                        <td className="numeric">{trend.earlierSharePercentage.toFixed(1)}%</td>
                        <td className="numeric">{trend.recentSharePercentage.toFixed(1)}%</td>
                        <td className="numeric">{trend.jobCountInWindow}</td>
                        <td>
                          <Link to={`/jobs?skill=${encodeURIComponent(trend.skill)}`}>View jobs</Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          );
        }}
      </AsyncPanel>
    </>
  );
}

/**
 * Recharts wants one row per x-value with a column per series, so the per-skill series
 * are pivoted into rows keyed by period.
 */
function TrendLines({ trends }: { trends: SkillTrend[] }) {
  // Rows are keyed by skill id, not skill name. Recharts reads a dot in dataKey as a
  // nested path, so a skill called "Node.js" would be looked up as row.Node.js and plot
  // as an empty line. The display name goes through `name` instead.
  const periods = trends[0].series.map((point) => point.period);
  const rows = periods.map((period, index) => {
    const row: Record<string, string | number> = { period: period.slice(0, 7) };
    trends.forEach((trend) => {
      row[seriesKey(trend)] = trend.series[index]?.sharePercentage ?? 0;
    });
    return row;
  });

  return (
    <ResponsiveContainer width="100%" height={320}>
      <LineChart data={rows} margin={{ top: 8, right: 24, bottom: 8, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis dataKey="period" tick={{ fontSize: 12 }} tickLine={false} axisLine={false} />
        <YAxis unit="%" tick={{ fontSize: 12 }} tickLine={false} axisLine={false} width={48} />
        <Tooltip
          contentStyle={{
            background: 'var(--surface-1)',
            border: '1px solid var(--border)',
            borderRadius: 6,
            fontSize: 13,
          }}
          formatter={(value) => `${Number(value).toFixed(1)}%`}
        />
        <Legend wrapperStyle={{ fontSize: 13, paddingTop: 8 }} />
        {trends.map((trend, index) => (
          <Line
            key={trend.skillId}
            type="monotone"
            dataKey={seriesKey(trend)}
            name={trend.skill}
            stroke={SERIES_SLOTS[index % SERIES_SLOTS.length]}
            strokeWidth={2}
            dot={{ r: 3, strokeWidth: 0 }}
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

/** Direction carried by an arrow and a word, so colour is never the only signal. */
function DirectionTag({ direction }: { direction: TrendDirection }) {
  const Icon =
    direction === 'RISING' ? IconArrowUp : direction === 'FALLING' ? IconArrowDown : IconMinus;
  return (
    <span className={`trend trend-${direction.toLowerCase()}`}>
      <Icon size={14} />
      {direction.charAt(0) + direction.slice(1).toLowerCase()}
    </span>
  );
}

/** Points, not percent, and signed so the direction is readable without the tag. */
function formatPoints(change: number): string {
  const sign = change > 0 ? '+' : '';
  return `${sign}${change.toFixed(1)} pts`;
}
