import { useEffect, useRef, useState, type ClipboardEvent, type KeyboardEvent } from 'react';

/**
 * Where the boxes are: in their row, on the orbit around the hub (while the code is being
 * checked), or collapsed into the hub (once it is accepted).
 */
export type OtpMotion = 'row' | 'orbit' | 'collapse';

interface Props {
  /** Digits entered so far; an emptied middle box is a space. */
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
  /** Moving between states is animated; {@link onMotionEnd} reports when it has settled. */
  motion?: OtpMotion;
  onMotionEnd?: (motion: OtpMotion) => void;
}

const CURL_MS = 540;
const SPIN_MS = 860;
const COLLAPSE_MS = 460;
const RETURN_MS = 520;
/** One and a quarter turns: the spin ends a quarter along, so every digit visibly moved. */
const SPIN_DEGREES = 450;
const EASE_OUT = 'cubic-bezier(0.25, 0.8, 0.25, 1)';
/** A slight pull back, then a hard brake into place. */
const WIND_UP_BRAKE = 'cubic-bezier(0.6, -0.28, 0.2, 1)';
const COLLAPSE_EASE = 'cubic-bezier(0.55, 0, 0.75, 0.25)';
/** Orbit radius, in box sizes. The stage in CSS is sized to match (4.2 boxes tall). */
export const ORBIT_RADIUS = 1.5;
const PATH_SAMPLES = 8;

export interface SlotGeometry {
  /** Horizontal offset of the box centre from the hub, in its row position. */
  cx: number;
  /** Polar angle of the row position (180 left of the hub, 0 right). */
  startAngle: number;
  startRadius: number;
  /** Its point on the orbit: first digit on the left, then clockwise. */
  orbitAngle: number;
}

interface Orbit {
  radius: number;
  slots: SlotGeometry[];
  /** Degrees the ring has turned since the boxes arrived. */
  turn: number;
}

/** False without the Web Animations API (tests, old browsers) or when motion is reduced. */
// eslint-disable-next-line react-refresh/only-export-components
export function motionAllowed(): boolean {
  if (typeof window === 'undefined' || typeof Element === 'undefined' || typeof Element.prototype.animate !== 'function') {
    return false;
  }
  return !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}

/** Row positions to orbit geometry. Pure, so the path maths can be tested without a browser. */
// eslint-disable-next-line react-refresh/only-export-components
export function orbitLayout(centres: number[]): SlotGeometry[] {
  return centres.map((cx, index) => {
    const orbitAngle = 180 + (index * 360) / centres.length;
    const startAngle = Math.abs(cx) < 0.5 ? orbitAngle : cx < 0 ? 180 : 0;
    return { cx, startAngle, startRadius: Math.abs(cx), orbitAngle };
  });
}

const polar = (radius: number, degrees: number) => {
  const radians = (degrees * Math.PI) / 180;
  return { x: radius * Math.cos(radians), y: radius * Math.sin(radians) };
};
const translate = (x: number, y: number, extra = '') => `translate(${x.toFixed(2)}px, ${y.toFixed(2)}px)${extra}`;
/** Clockwise sweep from a to b, 0–360. */
const clockwise = (a: number, b: number) => (((b - a) % 360) + 360) % 360;
/** Shortest signed sweep from a to b, -180–180. */
const shortest = (a: number, b: number) => ((((b - a + 540) % 360) + 360) % 360) - 180;

/**
 * Keyframes carrying a box centre along a polar path around the hub — radius and angle
 * change together, so the row curls onto the ring instead of sliding across to it.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function polarPath(slot: SlotGeometry, fromRadius: number, fromAngle: number, toRadius: number,
                          sweep: number): Keyframe[] {
  return Array.from({ length: PATH_SAMPLES + 1 }, (_, step) => {
    const t = step / PATH_SAMPLES;
    const point = polar(fromRadius + (toRadius - fromRadius) * t, fromAngle + sweep * t);
    const dip = 1 - 0.08 * Math.sin(Math.PI * t);
    return { transform: translate(point.x - slot.cx, point.y, ` scale(${dip.toFixed(3)})`) };
  });
}

/**
 * One box per digit. Typing moves forward, Backspace moves back, arrows move freely, and a
 * pasted (or autofilled) code fills every box at once. Only digits are accepted.
 *
 * <p>When the parent asks for the orbit, the row curls onto a dotted ring around a hub and
 * the ring spins once, braking into place; the verdict colours the boxes, and success
 * collapses them into the hub. The turn is a single rotate() about the hub, not sampled
 * points, so the path is an exact circle.
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
  motion = 'row',
  onMotionEnd,
}: Props) {
  const boxes = useRef<(HTMLInputElement | null)[]>([]);
  const slots = useRef<(HTMLSpanElement | null)[]>([]);
  const group = useRef<HTMLDivElement>(null);
  const ring = useRef<SVGSVGElement>(null);
  // Bumped each time a box receives a digit, to replay its light sweep.
  const landings = useRef<number[]>([]);
  const orbit = useRef<Orbit | null>(null);
  const running = useRef(new Set<Animation>());
  const shown = useRef<OtpMotion>(motion);
  const reportEnd = useRef(onMotionEnd);
  reportEnd.current = onMotionEnd;
  const [returning, setReturning] = useState(false);

  // Positions are fixed: an emptied middle box is a space in the value, so the digits after
  // it stay where they are instead of sliding left.
  const digits = Array.from({ length }, (_, index) => (value[index] ?? '').trim());
  const emit = (next: string[]) => onChange(next.map((digit) => digit || ' ').join('').trimEnd());

  useEffect(() => {
    if (autoFocus) {
      boxes.current[0]?.focus();
    }
  }, [autoFocus]);

  useEffect(() => {
    const animations = running.current;
    return () => animations.forEach((animation) => animation.cancel());
  }, []);

  const focusBox = (index: number) => {
    const box = boxes.current[Math.max(0, Math.min(length - 1, index))];
    box?.focus();
    box?.select();
  };

  useEffect(() => {
    const from = shown.current;
    if (from === motion) {
      return;
    }
    shown.current = motion;
    let live = true;

    const play = async (element: Element | null, frames: Keyframe[], options: KeyframeAnimationOptions) => {
      if (!element) {
        return;
      }
      const animation = element.animate(frames, { fill: 'forwards', ...options });
      running.current.add(animation);
      await animation.finished;
    };
    /** Leave an element where its animation ended, as plain inline style. */
    const settle = (element: HTMLElement | SVGElement | null, transform: string, origin = '') => {
      if (!element) {
        return;
      }
      element.style.transform = transform;
      element.style.transformOrigin = origin;
      element.getAnimations().forEach((animation) => {
        animation.cancel();
        running.current.delete(animation);
      });
    };
    const slotAt = (index: number) => slots.current[index] ?? null;
    const faceAt = (index: number) => boxes.current[index] ?? null;
    const offset = (current: Orbit, index: number) => {
      const slot = current.slots[index];
      const point = polar(current.radius, slot.orbitAngle + current.turn);
      return { x: point.x - slot.cx, y: point.y };
    };

    const enterOrbit = async () => {
      const row = group.current?.getBoundingClientRect();
      const measured = slots.current.slice(0, length).map((slot) => slot?.getBoundingClientRect());
      if (!row || measured.some((rect) => !rect)) {
        return;
      }
      // The boxes are disabled while they travel; a focused one would carry its caret and
      // selection round the ring.
      if (group.current?.contains(document.activeElement)) {
        (document.activeElement as HTMLElement).blur();
      }
      const hubX = row.left + row.width / 2;
      const size = measured[0]!.width;
      const current: Orbit = {
        radius: size * ORBIT_RADIUS,
        slots: orbitLayout(measured.map((rect) => rect!.left + rect!.width / 2 - hubX)),
        turn: 0,
      };
      orbit.current = current;

      // 1. The row curls onto the ring: left half up, right half down, all clockwise.
      await Promise.all(current.slots.map((slot, index) => play(slotAt(index),
          polarPath(slot, slot.startRadius, slot.startAngle, current.radius, clockwise(slot.startAngle, slot.orbitAngle)),
          { duration: CURL_MS, easing: EASE_OUT, delay: index * 18 })));
      current.slots.forEach((slot, index) => {
        const target = offset(current, index);
        // The hub, in the box's own coordinates: rotate() about it traces the orbit exactly.
        settle(slotAt(index), translate(target.x, target.y), `${size / 2 - slot.cx}px ${size / 2}px`);
      });

      // 2. One and a quarter turns about the hub. The boxes tilt as they travel and come
      //    back upright (-90° on the face cancels the extra quarter turn).
      const spin = { duration: SPIN_MS, easing: WIND_UP_BRAKE };
      await Promise.all([
        ...current.slots.flatMap((_, index) => {
          const target = offset(current, index);
          const place = translate(target.x, target.y);
          return [
            play(slotAt(index), [{ transform: `rotate(0deg) ${place}` }, { transform: `rotate(${SPIN_DEGREES}deg) ${place}` }], spin),
            play(faceAt(index), [{ transform: 'rotate(0deg)' }, { transform: `rotate(${360 - SPIN_DEGREES}deg)` }], spin),
          ];
        }),
        play(ring.current, [{ transform: 'rotate(0deg)' }, { transform: `rotate(${SPIN_DEGREES}deg)` }], spin),
      ]);
      current.turn = SPIN_DEGREES % 360;
      current.slots.forEach((_, index) => {
        const target = offset(current, index);
        settle(slotAt(index), translate(target.x, target.y));
        settle(faceAt(index), '');
      });
      settle(ring.current, '');
    };

    const collapse = async () => {
      const current = orbit.current;
      if (!current) {
        return;
      }
      const timing = (index: number) => ({ duration: COLLAPSE_MS, easing: COLLAPSE_EASE, delay: index * 22 });
      await Promise.all(current.slots.flatMap((slot, index) => {
        const from = offset(current, index);
        return [
          play(slotAt(index), [
            { transform: translate(from.x, from.y), opacity: 1 },
            { transform: translate(-slot.cx, 0, ' scale(0.2)'), opacity: 0 },
          ], timing(index)),
          play(faceAt(index), [{ transform: 'rotate(0deg)' }, { transform: `rotate(${index % 2 ? 150 : -150}deg)` }],
              timing(index)),
        ];
      }));
      // Left collapsed: the parent replaces the boxes with the success badge next.
    };

    const returnToRow = async () => {
      const current = orbit.current;
      if (!current) {
        return;
      }
      setReturning(true);
      await Promise.all(current.slots.map((slot, index) => {
        const from = slot.orbitAngle + current.turn;
        return play(slotAt(index),
            polarPath(slot, current.radius, from, slot.startRadius, shortest(from, slot.startAngle)),
            { duration: RETURN_MS, easing: EASE_OUT });
      }));
      current.slots.forEach((_, index) => settle(slotAt(index), ''));
      orbit.current = null;
      setReturning(false);
      focusBox(length - 1);
    };

    const run = !motionAllowed() ? Promise.resolve()
        : motion === 'orbit' ? enterOrbit()
        : motion === 'collapse' ? collapse()
        : returnToRow();
    run
      .catch(() => {
        // Cancelled by unmount or a newer motion; nothing to report.
      })
      .then(() => {
        if (live) {
          reportEnd.current?.(motion);
        }
      });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [motion]);

  /** Writes digits starting at a box, and moves focus to the next empty one. */
  const fill = (start: number, incoming: string) => {
    const clean = incoming.replace(/\D/g, '');
    if (!clean) {
      return;
    }
    const next = digits.slice();
    for (let offset = 0; offset < clean.length && start + offset < length; offset++) {
      next[start + offset] = clean[offset];
      landings.current[start + offset] = (landings.current[start + offset] ?? 0) + 1;
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

  const verdict = success ? ' otp-success' : invalid ? ' otp-invalid' : '';
  const stage = `otp-stage${motion !== 'row' ? ' is-orbit' : ''}${motion === 'collapse' ? ' is-collapse' : ''}${
    returning ? ' is-returning' : ''}`;
  return (
    <div className={stage}>
      {/* The track, and the point the boxes collapse onto. */}
      <svg ref={ring} className="otp-ring" viewBox="0 0 100 100" aria-hidden="true" focusable="false">
        <circle cx="50" cy="50" r="50" vectorEffect="non-scaling-stroke" />
      </svg>
      <span className="otp-hub" aria-hidden="true" />
      <div ref={group} className={`otp-group${verdict}`} role="group" aria-label="Verification code"
           aria-describedby={describedBy}>
        {digits.map((digit, index) => (
          <span
            key={index}
            ref={(element) => {
              slots.current[index] = element;
            }}
            className="otp-slot"
          >
            <input
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
            {/* A light sweeping once round the border as the digit lands. */}
            {digit && landings.current[index] ? (
              <span key={landings.current[index]} className="otp-sweep" aria-hidden="true" />
            ) : null}
          </span>
        ))}
      </div>
    </div>
  );
}
