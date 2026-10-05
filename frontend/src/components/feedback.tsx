import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';

/**
 * V9.11: app-wide feedback. Toasts confirm that something happened; the confirm dialog asks before
 * something that cannot be undone. Both are provided once at the root. A component rendered without
 * the provider (a unit test, say) still works: toasts are dropped and confirm falls back to the
 * browser's own dialog, so no caller has to care.
 */

type ToastTone = 'success' | 'error' | 'info';

interface Toast {
  id: number;
  tone: ToastTone;
  message: string;
}

interface ConfirmOptions {
  title: string;
  message?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** danger styles the confirm button for deletions and the like. */
  tone?: 'danger' | 'default';
}

interface Feedback {
  toast: (message: string, tone?: ToastTone) => void;
  confirm: (options: ConfirmOptions) => Promise<boolean>;
}

const FALLBACK: Feedback = {
  toast: () => undefined,
  confirm: (options) => Promise.resolve(window.confirm(options.message ? `${options.title}\n\n${options.message}` : options.title)),
};

const FeedbackContext = createContext<Feedback>(FALLBACK);

const TOAST_MILLIS = 4000;

export function FeedbackProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [pending, setPending] = useState<(ConfirmOptions & { resolve: (ok: boolean) => void }) | null>(null);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setToasts((all) => all.filter((toast) => toast.id !== id)), []);

  const toast = useCallback((message: string, tone: ToastTone = 'success') => {
    const id = nextId.current++;
    setToasts((all) => [...all.slice(-3), { id, tone, message }]);
    window.setTimeout(() => dismiss(id), TOAST_MILLIS);
  }, [dismiss]);

  const confirm = useCallback((options: ConfirmOptions) =>
    new Promise<boolean>((resolve) => setPending({ ...options, resolve })), []);

  const value = useMemo(() => ({ toast, confirm }), [toast, confirm]);

  return (
    <FeedbackContext.Provider value={value}>
      {children}
      <div className="toast-region" aria-label="Notifications">
        {toasts.map((item) => (
          <div key={item.id} className={`toast toast-${item.tone}`} role={item.tone === 'error' ? 'alert' : 'status'}>
            <span>{item.message}</span>
            <button type="button" className="toast-close" aria-label="Dismiss notification" onClick={() => dismiss(item.id)}>
              ×
            </button>
          </div>
        ))}
      </div>
      {pending && (
        <ConfirmDialog options={pending} onClose={(ok) => { pending.resolve(ok); setPending(null); }} />
      )}
    </FeedbackContext.Provider>
  );
}

export function useToast() {
  return useContext(FeedbackContext).toast;
}

export function useConfirm() {
  return useContext(FeedbackContext).confirm;
}

/**
 * A confirmed deletion usually removes the button that asked, once the server answers. Focus would
 * then drop to the document body, so a keyboard user loses their place; for a short while after the
 * dialog closes, move it to the main content instead when that happens.
 */
function keepFocusInPage(opener: HTMLElement) {
  if (typeof MutationObserver === 'undefined') {
    return;
  }
  const observer = new MutationObserver(() => {
    if (!document.contains(opener)) {
      stop();
      if (!document.activeElement || document.activeElement === document.body) {
        document.getElementById('main-content')?.focus();
      }
    }
  });
  const timer = window.setTimeout(() => observer.disconnect(), 15000);
  const stop = () => {
    observer.disconnect();
    window.clearTimeout(timer);
  };
  observer.observe(document.body, { childList: true, subtree: true });
}

/**
 * A modal question. Focus starts on Cancel (the safe choice), Tab stays inside, Escape or a click
 * outside cancels, and focus returns to whatever opened it.
 */
function ConfirmDialog({ options, onClose }: { options: ConfirmOptions; onClose: (ok: boolean) => void }) {
  const dialog = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    cancel.current?.focus();
    // Back to the opener; if the action then removes it (a deleted row), to the page's main content.
    return () => {
      if (opener && document.contains(opener)) {
        opener.focus();
        keepFocusInPage(opener);
      } else {
        document.getElementById('main-content')?.focus();
      }
    };
  }, []);

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose(false);
      return;
    }
    if (event.key === 'Tab' && dialog.current) {
      const buttons = Array.from(dialog.current.querySelectorAll<HTMLButtonElement>('button'));
      const first = buttons[0];
      const last = buttons[buttons.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  };

  return (
    <div className="dialog-scrim" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(false); }}>
      <div ref={dialog} className="dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title"
        aria-describedby={options.message ? 'confirm-message' : undefined} onKeyDown={onKeyDown}>
        <h2 id="confirm-title" className="dialog-title">{options.title}</h2>
        {options.message && <p id="confirm-message" className="dialog-message">{options.message}</p>}
        <div className="dialog-actions">
          <button ref={cancel} type="button" className="ghost" onClick={() => onClose(false)}>
            {options.cancelLabel ?? 'Cancel'}
          </button>
          <button type="button" className={options.tone === 'danger' ? 'danger' : undefined} onClick={() => onClose(true)}>
            {options.confirmLabel ?? 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
}
