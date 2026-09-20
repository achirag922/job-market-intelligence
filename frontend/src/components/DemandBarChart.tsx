import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

export interface DemandDatum {
  label: string;
  value: number;
}

interface Props {
  data: DemandDatum[];
  /** Tooltip and axis label for the measured quantity. */
  valueName?: string;
  height?: number;
}

/**
 * Horizontal bars, because the labels are skill, company and place names. Rotated
 * vertical labels are the usual result of forcing long names onto an x-axis, and they
 * are hard to read.
 */
export function DemandBarChart({ data, valueName = 'Jobs', height = 320 }: Props) {
  if (data.length === 0) {
    return <p className="status">No data to chart.</p>;
  }
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} layout="vertical" margin={{ top: 8, right: 24, bottom: 8, left: 8 }}>
        <CartesianGrid strokeDasharray="3 3" horizontal={false} />
        <XAxis type="number" allowDecimals={false} />
        <YAxis type="category" dataKey="label" width={150} tick={{ fontSize: 12 }} />
        {/* The series name supplies the tooltip label, so no formatter is needed. */}
        <Tooltip />
        <Bar dataKey="value" name={valueName} fill="#3b6ea5" />
      </BarChart>
    </ResponsiveContainer>
  );
}
