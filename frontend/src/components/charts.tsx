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
import { EmptyState } from './ui';

export interface Datum {
  label: string;
  value: number;
}

/**
 * The categorical slots, in fixed order.
 *
 * <p>Read from CSS variables so a chart follows the theme without re-rendering, and so
 * the palette has exactly one definition. The order is the accessibility mechanism — the
 * set was validated for colour-vision deficiency as an ordered sequence — so slots are
 * taken in order and never shuffled or cycled past the end.
 */
const SERIES_SLOTS = Array.from({ length: 8 }, (_, index) => `var(--series-${index + 1})`);

/** Beyond this many slices a pie is unreadable and the tail becomes "Other". */
const MAX_SLICES = 7;

/* --------------------------------------------------------------------- tooltip */

/**
 * What Recharts hands a custom tooltip.
 *
 * <p>Declared here rather than imported: the library's own `TooltipProps` has changed
 * shape across versions and narrows `payload` away in this one. Naming the three fields
 * actually read keeps this compiling across upgrades and documents the contract.
 */
interface TooltipRenderProps {
  active?: boolean;
  payload?: { value?: number | string; payload?: Datum }[];
  valueLabel?: string;
  suffix?: string;
}

function ChartTooltip({ active, payload, valueLabel, suffix }: TooltipRenderProps) {
  if (!active || !payload?.length) {
    return null;
  }
  const point = payload[0];
  const label = point.payload?.label;
  return (
    <div className="chart-tooltip">
      {label && <div className="chart-tooltip-label">{label}</div>}
      <div className="chart-tooltip-value">
        {valueLabel ? `${valueLabel}: ` : ''}
        {typeof point.value === 'number' ? point.value.toLocaleString('en-US') : point.value}
        {suffix}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ bar chart */

interface BarProps {
  data: Datum[];
  /** What the numbers measure — used by the tooltip and the accessible description. */
  valueLabel?: string;
  suffix?: string;
  height?: number;
  emptyMessage?: string;
}

/**
 * Ranked values as horizontal bars.
 *
 * <p>Horizontal because every label here is a name — a skill, a company, a city — and
 * names forced onto an x-axis end up rotated and unreadable. Height grows with the row
 * count so bars keep a constant thickness instead of stretching.
 */
export function BarChartPanel({
  data,
  valueLabel = 'Jobs',
  suffix,
  height,
  emptyMessage = 'There is no data to chart yet.',
}: BarProps) {
  if (data.length === 0) {
    return <EmptyState title="No data" message={emptyMessage} />;
  }

  const computedHeight = height ?? Math.max(200, data.length * 34 + 40);

  // Sized to the longest label rather than fixed: at a fixed width "Machine Learning
  // Engineer" wraps to two lines and the rows stop aligning with their bars. Capped so
  // one long outlier cannot squeeze the plot area.
  const longest = data.reduce((max, row) => Math.max(max, row.label.length), 0);
  const axisWidth = Math.min(210, Math.max(90, longest * 6.6));

  return (
    <div className="chart-frame">
      <ResponsiveContainer width="100%" height={computedHeight}>
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 32, bottom: 4, left: 4 }}>
          <CartesianGrid strokeDasharray="3 3" horizontal={false} />
          <XAxis type="number" allowDecimals={false} tickLine={false} axisLine={false} />
          <YAxis
            type="category"
            dataKey="label"
            width={axisWidth}
            tickLine={false}
            axisLine={false}
            tick={{ fontSize: 12 }}
          />
          <Tooltip
            cursor={{ fill: 'var(--surface-2)' }}
            content={<ChartTooltip valueLabel={valueLabel} suffix={suffix} />}
          />
          {/* Rounded data-end, square against the baseline. */}
          <Bar dataKey="value" fill="var(--series-1)" radius={[0, 4, 4, 0]} maxBarSize={22} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/* ----------------------------------------------------------------- line chart */

interface LineProps {
  data: Datum[];
  valueLabel?: string;
  suffix?: string;
  height?: number;
  emptyMessage?: string;
}

/** A value over time. One series, so the title names it and no legend is needed. */
export function LineChartPanel({
  data,
  valueLabel = 'Value',
  suffix,
  height = 260,
  emptyMessage = 'There is no history to chart yet.',
}: LineProps) {
  if (data.length === 0) {
    return <EmptyState title="No history" message={emptyMessage} />;
  }

  return (
    <div className="chart-frame">
      <ResponsiveContainer width="100%" height={height}>
        <LineChart data={data} margin={{ top: 8, right: 24, bottom: 4, left: 4 }}>
          <CartesianGrid strokeDasharray="3 3" vertical={false} />
          <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fontSize: 12 }} />
          <YAxis tickLine={false} axisLine={false} tick={{ fontSize: 12 }} width={44} />
          <Tooltip content={<ChartTooltip valueLabel={valueLabel} suffix={suffix} />} />
          <Line
            type="monotone"
            dataKey="value"
            stroke="var(--series-1)"
            strokeWidth={2}
            dot={{ r: 3, strokeWidth: 0, fill: 'var(--series-1)' }}
            activeDot={{ r: 5 }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

/* ------------------------------------------------------------------ pie chart */

interface PieProps {
  data: Datum[];
  height?: number;
  emptyMessage?: string;
}

/**
 * Parts of a whole.
 *
 * <p>Only used where the parts genuinely make up one — each posting belongs to exactly
 * one category, so the slices sum honestly.
 *
 * <p>The tail is folded into a single "Other" slice. The palette has eight validated
 * slots and generating a ninth hue would break the guarantee the set was checked for;
 * beyond that, fourteen slices is not readable at any size. The full list is always in
 * the table beside the chart, so nothing is hidden — only summarised.
 */
export function PieChartPanel({ data, height = 300, emptyMessage = 'No distribution to show.' }: PieProps) {
  if (data.length === 0) {
    return <EmptyState title="No data" message={emptyMessage} />;
  }

  const slices = foldTail(data);

  return (
    <div className="chart-frame">
      <ResponsiveContainer width="100%" height={height}>
        <PieChart>
          <Pie
            data={slices}
            dataKey="value"
            nameKey="label"
            outerRadius="78%"
            /* A 2px surface gap keeps adjacent fills from reading as one shape. */
            paddingAngle={1}
            stroke="var(--surface-1)"
            strokeWidth={2}
          >
            {slices.map((slice, index) => (
              <Cell key={slice.label} fill={SERIES_SLOTS[index % SERIES_SLOTS.length]} />
            ))}
          </Pie>
          <Tooltip content={<ChartTooltip valueLabel="Jobs" />} />
        </PieChart>
      </ResponsiveContainer>

      {/* Identity never rests on colour alone: every slice is named here too. */}
      <ul className="chart-legend">
        {slices.map((slice, index) => (
          <li className="chart-legend-item" key={slice.label}>
            <span
              className="chart-legend-swatch"
              style={{ background: SERIES_SLOTS[index % SERIES_SLOTS.length] }}
              aria-hidden="true"
            />
            {slice.label} · {slice.value.toLocaleString('en-US')}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Keeps the largest slices and sums the rest into one labelled remainder. */
function foldTail(data: Datum[]): Datum[] {
  if (data.length <= MAX_SLICES + 1) {
    return data;
  }
  const ranked = [...data].sort((a, b) => b.value - a.value);
  const head = ranked.slice(0, MAX_SLICES);
  const tail = ranked.slice(MAX_SLICES);
  const remainder = tail.reduce((sum, row) => sum + row.value, 0);
  return [...head, { label: `Other (${tail.length} categories)`, value: remainder }];
}
