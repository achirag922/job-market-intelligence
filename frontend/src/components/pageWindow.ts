/**
 * Which page numbers to show: the first, the last, and the current one with a neighbour
 * either side, with a gap marker ({@code null}) where pages are skipped.
 *
 * <p>Seven buttons at most, however many pages there are, so the control never wraps
 * onto a second line on a phone.
 *
 * <p>In its own module rather than beside {@code Pagination}: a component file that also
 * exports a plain function loses React Fast Refresh, and every edit then reloads the page.
 *
 * @param current zero-based current page
 * @param total   number of pages
 */
export function pageWindow(current: number, total: number): (number | null)[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, index) => index);
  }
  const wanted = new Set([0, total - 1, current - 1, current, current + 1]);
  const sorted = [...wanted].filter((entry) => entry >= 0 && entry < total).sort((a, b) => a - b);

  const window: (number | null)[] = [];
  sorted.forEach((entry, index) => {
    if (index > 0 && entry - sorted[index - 1] > 1) {
      window.push(null);
    }
    window.push(entry);
  });
  return window;
}
