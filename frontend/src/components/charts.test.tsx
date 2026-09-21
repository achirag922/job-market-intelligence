import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { BarChartPanel, PieChartPanel } from './charts';

/**
 * The charting rules that are not about looks.
 *
 * <p>Two properties matter enough to pin: a chart with nothing to draw must say so rather
 * than render an empty frame, and a distribution with more categories than the palette has
 * validated slots must fold its tail rather than invent a colour.
 */
describe('charts', () => {
  afterEach(cleanup);

  it('explains an empty bar chart instead of drawing an empty frame', () => {
    const { container } = render(<BarChartPanel data={[]} emptyMessage="No skills extracted yet." />);

    expect(screen.getByText('No skills extracted yet.')).toBeInTheDocument();
    expect(container.querySelector('.chart-frame')).toBeNull();
  });

  it('draws a frame when there is data', () => {
    const { container } = render(
      <BarChartPanel data={[{ label: 'Java', value: 34 }]} valueLabel="Postings" />,
    );

    expect(container.querySelector('.chart-frame')).toBeTruthy();
  });

  it('names every pie slice in the legend, so identity is never colour alone', () => {
    render(
      <PieChartPanel
        data={[
          { label: 'Backend Developer', value: 19 },
          { label: 'Data Engineer', value: 15 },
        ]}
      />,
    );

    expect(screen.getByText(/Backend Developer/)).toBeInTheDocument();
    expect(screen.getByText(/Data Engineer/)).toBeInTheDocument();
  });

  it('folds the tail of a long distribution into one labelled remainder', () => {
    // The palette has eight validated slots and the order is what makes the set safe for
    // colour-vision deficiency. A ninth generated hue would break that guarantee, and
    // fourteen slices would be unreadable regardless.
    const data = Array.from({ length: 14 }, (_, index) => ({
      label: `Category ${index + 1}`,
      value: 20 - index,
    }));

    render(<PieChartPanel data={data} />);

    // The label and the value are separate text nodes, so the whole legend entry is
    // matched against the item's combined text rather than a single node's.
    const entry = screen
      .getAllByRole('listitem')
      .find((item) => item.textContent?.includes('Other (7 categories)'));

    expect(entry).toBeDefined();
    // The folded categories are summed, not dropped: values 13 down to 7 total 70.
    expect(entry?.textContent).toContain('70');
    expect(screen.queryByText('Category 14')).not.toBeInTheDocument();
  });

  it('leaves a short distribution intact', () => {
    const data = Array.from({ length: 5 }, (_, index) => ({
      label: `Category ${index + 1}`,
      value: 10 - index,
    }));

    render(<PieChartPanel data={data} />);

    expect(screen.queryByText(/Other \(/)).not.toBeInTheDocument();
    expect(screen.getByText(/Category 5/)).toBeInTheDocument();
  });
});
