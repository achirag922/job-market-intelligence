interface Props {
  page: number;
  totalPages: number;
  totalElements: number;
  first: boolean;
  last: boolean;
  onChange: (page: number) => void;
}

/** Page numbers are zero based in the API and shown one based here. */
export function Pagination({ page, totalPages, totalElements, first, last, onChange }: Props) {
  if (totalElements === 0) {
    return null;
  }
  return (
    <div className="pagination">
      <button type="button" onClick={() => onChange(page - 1)} disabled={first}>
        Previous
      </button>
      <span>
        Page {page + 1} of {Math.max(totalPages, 1)} · {totalElements} results
      </span>
      <button type="button" onClick={() => onChange(page + 1)} disabled={last}>
        Next
      </button>
    </div>
  );
}
