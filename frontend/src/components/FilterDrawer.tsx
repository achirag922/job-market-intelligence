import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { IconClose } from './icons';

interface Props {
  open: boolean;
  title: string;
  onClose: () => void;
  onApply: () => void;
  onClearAll: () => void;
  children: ReactNode;
}

/**
 * The filters, as a panel over the page on a narrow screen.
 *
 * <p>A native {@code <dialog>} opened with {@code showModal}. That single choice supplies
 * what an accessible drawer needs and what hand-built overlays usually get wrong: focus
 * moves into it and cannot tab out behind it, Escape closes it, the page behind is inert
 * to assistive technology, and focus returns to the button that opened it.
 *
 * <p>Changes inside are a draft until Apply. On a phone every filter change re-running the
 * search would reflow the results behind the drawer on every tap, for a page nobody can see.
 */
export function FilterDrawer({ open, title, onClose, onApply, onClearAll, children }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const element = dialog.current;
    if (!element) {
      return;
    }
    if (open && !element.open) {
      // jsdom and a few older browsers lack showModal; opening non-modally keeps the
      // drawer usable there rather than throwing.
      if (typeof element.showModal === 'function') {
        element.showModal();
      } else {
        element.setAttribute('open', '');
      }
    } else if (!open && element.open) {
      if (typeof element.close === 'function') {
        element.close();
      } else {
        element.removeAttribute('open');
      }
    }
  }, [open]);

  return (
    <dialog
      ref={dialog}
      className="filter-drawer"
      aria-labelledby="filter-drawer-title"
      // Escape fires "cancel"; routing it through onClose keeps React's state in step.
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      // A click on the backdrop lands on the dialog element itself, outside its content.
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="filter-drawer-body">
        <header className="filter-drawer-header">
          <h2 id="filter-drawer-title" className="card-title">
            {title}
          </h2>
          <button type="button" className="icon-button ghost" aria-label="Close filters" onClick={onClose}>
            <IconClose />
          </button>
        </header>

        <div className="filter-drawer-content">{children}</div>

        <footer className="filter-drawer-footer">
          <button type="button" className="ghost" onClick={onClearAll}>
            Clear all
          </button>
          <button type="button" className="primary" onClick={onApply}>
            Apply filters
          </button>
        </footer>
      </div>
    </dialog>
  );
}
