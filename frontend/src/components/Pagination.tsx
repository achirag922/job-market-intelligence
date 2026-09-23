import { pageWindow } from './pageWindow';

interface Props {
  page: number;
  totalPages: number;
  totalElements: number;
  first: boolean;
  last: boolean;
  onChange: (page: number) => void;
  /** Numbered page buttons between Previous and Next. Off by default. */
  showPageNumbers?: boolean;
  /** With both of these, a page-size control appears. */
  pageSize?: number;
  pageSizeOptions?: readonly number[];
  onPageSizeChange?: (size: number) => void;
}

/**
 * Page numbers are zero based in the API and shown one based here.
 *
 * <p>The numbered buttons and the size control are opt-in, so the pages that used this
 * before render exactly as they did.
 */
export function Pagination({
  page,
  totalPages,
  totalElements,
  first,
  last,
  onChange,
  showPageNumbers = false,
  pageSize,
  pageSizeOptions,
  onPageSizeChange,
}: Props) {
  if (totalElements === 0) {
    return null;
  }
  const pages = Math.max(totalPages, 1);

  return (
    <nav className="pagination" aria-label="Pagination">
      <span>
        Page {page + 1} of {pages} ·{' '}
        <strong>{totalElements.toLocaleString('en-US')}</strong> results
      </span>

      <div className="pagination-controls">
        {pageSize !== undefined && pageSizeOptions && onPageSizeChange && (
          <label className="pagination-size">
            <span>Per page</span>
            <select value={pageSize} onChange={(event) => onPageSizeChange(Number(event.target.value))}>
              {pageSizeOptions.map((size) => (
                <option key={size} value={size}>
                  {size}
                </option>
              ))}
            </select>
          </label>
        )}

        <button type="button" className="small" onClick={() => onChange(page - 1)} disabled={first}>
          Previous
        </button>

        {showPageNumbers &&
          pageWindow(page, pages).map((entry, index) =>
            entry === null ? (
              <span key={`gap-${index}`} className="pagination-gap" aria-hidden="true">
                …
              </span>
            ) : (
              <button
                key={entry}
                type="button"
                className={entry === page ? 'small primary' : 'small'}
                aria-label={`Page ${entry + 1}`}
                aria-current={entry === page ? 'page' : undefined}
                onClick={() => onChange(entry)}
              >
                {entry + 1}
              </button>
            ),
          )}

        <button type="button" className="small" onClick={() => onChange(page + 1)} disabled={last}>
          Next
        </button>
      </div>
    </nav>
  );
}
