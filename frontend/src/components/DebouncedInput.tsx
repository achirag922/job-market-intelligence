import { useEffect, useRef, useState } from 'react';
import type { InputHTMLAttributes } from 'react';

interface Props extends Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'> {
  /** The committed value — what the search is actually using. */
  value: string;
  /** Called once typing settles, or at once on Enter or blur. */
  onCommit: (value: string) => void;
  /** Milliseconds of quiet before committing. 0 commits on every keystroke. */
  delay?: number;
  /** Every keystroke, before any debounce — for suggestions that follow the typing. */
  onDraftChange?: (value: string) => void;
}

/**
 * A text input that commits when the typing stops, not on every key.
 *
 * <p>Holding its own value is what keeps it responsive: the characters appear as they are
 * typed, while the search behind it runs once for "java spring" rather than eleven times
 * for every prefix of it.
 *
 * <p>Enter and blur commit immediately, so nobody has to wait for the timer to find out
 * whether their search worked. And when the committed value changes from outside — a
 * filter chip removed, "clear all" pressed — the input follows it rather than keeping a
 * stale draft on screen.
 */
export function DebouncedInput({
  value,
  onCommit,
  delay = 350,
  onDraftChange,
  onKeyDown,
  onBlur,
  ...rest
}: Props) {
  const [draft, setDraft] = useState(value);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const lastCommitted = useRef(value);

  // External changes win. Without this, clearing a filter elsewhere would leave its old
  // text sitting in the box, and the next keystroke would silently bring it back.
  useEffect(() => {
    if (value !== lastCommitted.current) {
      lastCommitted.current = value;
      setDraft(value);
    }
  }, [value]);

  useEffect(() => () => clearTimeout(timer.current), []);

  const commit = (next: string) => {
    clearTimeout(timer.current);
    if (next.trim() === lastCommitted.current.trim()) {
      return;
    }
    lastCommitted.current = next;
    onCommit(next);
  };

  return (
    <input
      {...rest}
      value={draft}
      onChange={(event) => {
        const next = event.target.value;
        setDraft(next);
        onDraftChange?.(next);
        clearTimeout(timer.current);
        if (delay === 0) {
          commit(next);
        } else {
          timer.current = setTimeout(() => commit(next), delay);
        }
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault();
          commit(draft);
        }
        onKeyDown?.(event);
      }}
      onBlur={(event) => {
        commit(draft);
        onBlur?.(event);
      }}
    />
  );
}
