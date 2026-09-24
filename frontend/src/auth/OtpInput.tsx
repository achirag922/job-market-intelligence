import { useEffect, useRef, type ClipboardEvent, type KeyboardEvent } from 'react';

interface Props {
  /** Digits entered so far, 0–length characters. */
  value: string;
  onChange: (value: string) => void;
  /** Called once all boxes are filled. */
  onComplete?: (value: string) => void;
  length?: number;
  disabled?: boolean;
  /** Error styling (and a shake); cleared by the parent when the user types again. */
  invalid?: boolean;
  success?: boolean;
  autoFocus?: boolean;
  /** Id of the text describing the boxes, for screen readers. */
  describedBy?: string;
}

/**
 * One box per digit. Typing moves forward, Backspace moves back, arrows move freely, and a
 * pasted (or autofilled) code fills every box at once. Only digits are accepted.
 */
export function OtpInput({
  value,
  onChange,
  onComplete,
  length = 6,
  disabled = false,
  invalid = false,
  success = false,
  autoFocus = false,
  describedBy,
}: Props) {
  const boxes = useRef<(HTMLInputElement | null)[]>([]);
  // Positions are fixed: an emptied middle box is a space in the value, so the digits after
  // it stay where they are instead of sliding left.
  const digits = Array.from({ length }, (_, index) => (value[index] ?? '').trim());
  const emit = (next: string[]) => onChange(next.map((digit) => digit || ' ').join('').trimEnd());

  useEffect(() => {
    if (autoFocus) {
      boxes.current[0]?.focus();
    }
  }, [autoFocus]);

  const focusBox = (index: number) => {
    const box = boxes.current[Math.max(0, Math.min(length - 1, index))];
    box?.focus();
    box?.select();
  };

  /** Writes digits starting at a box, and moves focus to the next empty one. */
  const fill = (start: number, incoming: string) => {
    const clean = incoming.replace(/\D/g, '');
    if (!clean) {
      return;
    }
    const next = digits.slice();
    for (let offset = 0; offset < clean.length && start + offset < length; offset++) {
      next[start + offset] = clean[offset];
    }
    const joined = next.join('');
    emit(next);
    const firstEmpty = next.findIndex((digit) => digit === '');
    focusBox(firstEmpty === -1 ? length - 1 : firstEmpty);
    if (firstEmpty === -1 && joined.length === length) {
      onComplete?.(joined);
    }
  };

  const onKeyDown = (index: number, event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Backspace') {
      event.preventDefault();
      const next = digits.slice();
      if (next[index]) {
        next[index] = '';
        emit(next);
      } else if (index > 0) {
        next[index - 1] = '';
        emit(next);
        focusBox(index - 1);
      }
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      focusBox(index - 1);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      focusBox(index + 1);
    }
  };

  const onPaste = (index: number, event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    fill(index, event.clipboardData.getData('text'));
  };

  const state = success ? ' otp-success' : invalid ? ' otp-invalid' : '';
  return (
    <div className={`otp-group${state}`} role="group" aria-label="Verification code" aria-describedby={describedBy}>
      {digits.map((digit, index) => (
        <input
          key={index}
          ref={(element) => {
            boxes.current[index] = element;
          }}
          className={digit ? 'otp-box filled' : 'otp-box'}
          type="text"
          inputMode="numeric"
          pattern="[0-9]*"
          maxLength={length}
          // The first box lets the phone offer the code from the email.
          autoComplete={index === 0 ? 'one-time-code' : 'off'}
          aria-label={`Digit ${index + 1} of ${length}`}
          aria-invalid={invalid || undefined}
          value={digit}
          disabled={disabled}
          onFocus={(event) => event.target.select()}
          onKeyDown={(event) => onKeyDown(index, event)}
          onPaste={(event) => onPaste(index, event)}
          onChange={(event) => {
            const typed = event.target.value.replace(/\D/g, '');
            if (!typed) {
              return;
            }
            // One digit typed, or several at once from autofill: fill from here either way.
            fill(index, typed.length > 1 && digit ? typed.replace(digit, '') || typed : typed);
          }}
        />
      ))}
    </div>
  );
}
