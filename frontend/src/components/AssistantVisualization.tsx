import type { Visualization } from '../api/types';
import { BarChartPanel, LineChartPanel, PieChartPanel } from './charts';

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
 *
 * <p>The drawing itself is the same {@code charts} module every other page uses, so an
 * assistant answer looks like the rest of the application rather than like a second
 * charting style bolted on beside it.
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
      {type === 'BAR' && <BarChartPanel data={points} valueLabel={yAxis ?? xAxis ?? 'Value'} />}
      {type === 'LINE' && <LineChartPanel data={points} valueLabel={yAxis ?? 'Value'} />}
      {type === 'PIE' && <PieChartPanel data={points} />}
    </figure>
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
      {title && <p className="card-description">{title}</p>}
      <div className="table-wrap" style={{ marginBottom: 0 }}>
        <table>
          <thead>
            <tr>
              {columns.map((column) => (
                <th scope="col" key={column}>
                  {humanise(column)}
                </th>
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
