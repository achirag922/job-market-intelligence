import { BarChartPanel } from './charts';
import type { Datum } from './charts';

export type DemandDatum = Datum;

interface Props {
  data: DemandDatum[];
  /** Tooltip label for the measured quantity. */
  valueName?: string;
  height?: number;
}

/**
 * Ranked demand as horizontal bars.
 *
 * <p>Kept as the name the analytics pages already import; the drawing itself moved to
 * {@link BarChartPanel} so every chart in the application shares one set of marks,
 * colours and tooltips rather than each page styling its own.
 */
export function DemandBarChart({ data, valueName = 'Jobs', height }: Props) {
  return <BarChartPanel data={data} valueLabel={valueName} height={height} />;
}
