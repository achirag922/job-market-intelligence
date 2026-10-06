import { useEffect, useId, useState } from 'react';
import type { ReactNode } from 'react';

/**
 * V9.18: an explanation next to a metric or a complex control. A real button: it opens on hover,
 * keyboard focus or a tap, closes on Escape or when focus leaves, and the text is announced through
 * aria-describedby. Nothing is hidden from people who cannot hover.
 */
export function InfoTip({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  return (
    <span className="info-tip" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
      <button type="button" className="info-tip-button" aria-label={`About ${label}`} aria-describedby={open ? id : undefined}
        aria-expanded={open} onClick={() => setOpen((value) => !value)} onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}
        onKeyDown={(event) => { if (event.key === 'Escape') setOpen(false); }}>
        ?
      </button>
      {open && <span id={id} role="tooltip" className="info-tip-bubble">{children}</span>}
    </span>
  );
}

const GUIDE_PREFIX = 'jmip:guide:';

function seen(key: string): boolean {
  try {
    return localStorage.getItem(GUIDE_PREFIX + key) === 'dismissed';
  } catch {
    return false;
  }
}

/**
 * V9.18: a short first-visit tip for an important page. Dismissed once, it stays dismissed in this
 * browser; it never blocks the page.
 */
export function PageGuide({ id, title, children, helpAnchor }: { id: string; title: string; children: ReactNode; helpAnchor?: string }) {
  const [visible, setVisible] = useState(() => !seen(id));
  useEffect(() => {
    setVisible(!seen(id));
  }, [id]);
  if (!visible) return null;
  const dismiss = () => {
    try {
      localStorage.setItem(GUIDE_PREFIX + id, 'dismissed');
    } catch {
      // Storage unavailable: the tip simply shows again next time.
    }
    setVisible(false);
  };
  return (
    <aside className="page-guide" aria-label={`Tip: ${title}`}>
      <div>
        <strong>{title}</strong>
        <p>{children}</p>
        {helpAnchor && <a href={`/help#${helpAnchor}`}>Learn more in Help</a>}
      </div>
      <button type="button" className="small ghost" onClick={dismiss}>Got it</button>
    </aside>
  );
}
