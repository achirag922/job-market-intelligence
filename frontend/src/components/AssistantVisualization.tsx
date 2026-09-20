import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { Visualization } from '../api/types';

/** Enough hues for the categories this dataset has; it repeats rather than running out. */
const SLICE_COLOURS = [
  '#3b6ea5', '#57a773', '#c8763a', '#8c5ba8', '#c25b6e',
  '#4a9ba5', '#a5a03b', '#6b7a8f', '#a3574d', '#5d8c5a',
];

interface Props {
  visualization: Visualization;
  /** The rows behind the chart; TABLE renders these rather than the chart points. */
  rows: unknown[];
}

/**
 * Draws an assistant answer.
 *
 * <p>The backend sends a type name and numbers, never markup or code, and this maps that
 * name to a component that already exists. An unrecognised type renders nothing rather
 * than falling back to something arbitrary: there is no path by which a response could
 * cause the page to render content it chose.
 */
export function AssistantVisualization({ visualization, rows }: Props) {
  const { type, title, xAxis, yAxis, points } = visualization;

  if (type === 'NONE') {
    return null;
  }

  if (type === 'TABLE') {
    return <RowTable title={title} rows={rows} />;
  }

  if (points.length === 0) {
    return null;
  }

  return (
    <figure className="assistant-chart">
      {title && <figcaption>{title}</figcaption>}
      {type === 'BAR' && <Bars points={points} xAxis={xAxis} yAxis={yAxis} />}
      {type === 'LINE' && <Series points={points} yAxis={yAxis} />}
      {type === 'PIE' && <Slices points={points} />}
    </figure>
  );
}

function Bars({ points, xAxis, yAxis }: { points: Visualization['points']; xAxis?: string; yAxis?: string }) {
  // Horizontal, because the labels are skill, company and place names. Rotated vertical
  // labels are what happens when long names are forced onto an x-axis.
  return (
    <ResponsiveContainer width="100%" height={Math.max(200, points.length * 30 + 60)}>
      <BarChart data={points} layout="vertical" margin={{ top: 8, right: 24, bottom: 8, left: 8 }}>
        <CartesianGrid strokeDasharray="3 3" horizontal={false} />
        <XAxis type="number" allowDecimals={false} name={yAxis} />
        <YAxis type="category" dataKey="label" width={150} tick={{ fontSize: 12 }} name={xAxis} />
        <Tooltip />
        <Bar dataKey="value" name={yAxis ?? 'Value'} fill="#3b6ea5" />
      </BarChart>
    </ResponsiveContainer>
  );
}

function Series({ points, yAxis }: { points: Visualization['points']; yAxis?: string }) {
  return (
    <ResponsiveContainer width="100%" height={280}>
      <LineChart data={points} margin={{ top: 8, right: 24, bottom: 8, left: 8 }}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="label" tick={{ fontSize: 12 }} />
        <YAxis tick={{ fontSize: 12 }} />
        <Tooltip />
        <Line type="monotone" dataKey="value" name={yAxis ?? 'Value'} stroke="#3b6ea5" dot />
      </LineChart>
    </ResponsiveContainer>
  );
}

function Slices({ points }: { points: Visualization['points'] }) {
  return (
    <ResponsiveContainer width="100%" height={300}>
      <PieChart>
        <Pie data={points} dataKey="value" nameKey="label" outerRadius={110} label>
          {points.map((point, index) => (
            <Cell key={point.label} fill={SLICE_COLOURS[index % SLICE_COLOURS.length]} />
          ))}
        </Pie>
        <Tooltip />
      </PieChart>
    </ResponsiveContainer>
  );
}

/**
 * Rows as a table, with columns discovered from the first row.
 *
 * <p>Values are rendered as text, never as markup. Nested objects are flattened to a short
 * label rather than stringified, because `[object Object]` in a cell is worse than nothing.
 */
function RowTable({ title, rows }: { title?: string; rows: unknown[] }) {
  if (rows.length === 0) {
    return null;
  }

  const first = rows[0];
  if (typeof first !== 'object' || first === null) {
    return null;
  }

  const columns = Object.keys(first as Record<string, unknown>)
    .filter((key) => !Array.isArray((first as Record<string, unknown>)[key]))
    .slice(0, 6);

  return (
    <div className="assistant-chart">
      {title && <p className="subtitle">{title}</p>}
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column}>{humanise(column)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={index}>
              {columns.map((column) => (
                <td key={column}>{cellText((row as Record<string, unknown>)[column])}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** "percentageOfJobs" reads as "Percentage of jobs" rather than as a field name. */
function humanise(key: string): string {
  const spaced = key.replace(/([A-Z])/g, ' $1').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function cellText(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (typeof value === 'object') {
    // Nested DTOs — a company, a location — carry a display name worth showing.
    const nested = value as Record<string, unknown>;
    const label = nested.displayName ?? nested.name ?? nested.city ?? nested.title;
    return typeof label === 'string' ? label : '—';
  }
  return String(value);
}
