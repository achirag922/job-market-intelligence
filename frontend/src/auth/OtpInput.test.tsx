import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { ORBIT_RADIUS, OtpInput, orbitLayout, polarPath, type SlotGeometry } from './OtpInput';

function Harness({ onComplete }: { onComplete?: (code: string) => void }) {
  const [value, setValue] = useState('');
  return (
    <>
      <OtpInput value={value} onChange={setValue} onComplete={onComplete} autoFocus />
      <output data-testid="value">{value}</output>
    </>
  );
}

const box = (n: number) => screen.getByLabelText(`Digit ${n} of 6`) as HTMLInputElement;
const value = () => screen.getByTestId('value').textContent;

describe('OtpInput', () => {
  afterEach(cleanup);

  it('renders six labelled boxes in a group and focuses the first', () => {
    render(<Harness />);
    expect(screen.getByRole('group', { name: 'Verification code' })).toBeInTheDocument();
    expect(screen.getAllByRole('textbox')).toHaveLength(6);
    expect(box(1)).toHaveFocus();
    expect(box(1)).toHaveAttribute('autocomplete', 'one-time-code');
    expect(box(1)).toHaveAttribute('inputmode', 'numeric');
  });

  it('moves to the next box after each digit and ignores anything that is not a digit', () => {
    render(<Harness />);
    fireEvent.change(box(1), { target: { value: '4' } });
    expect(box(2)).toHaveFocus();
    fireEvent.change(box(2), { target: { value: 'x' } });
    expect(value()).toBe('4');
    expect(box(2)).toHaveFocus();
    fireEvent.change(box(2), { target: { value: '2' } });
    expect(value()).toBe('42');
    expect(box(3)).toHaveFocus();
  });

  it('fills every box from a pasted code and reports it complete', () => {
    const onComplete = vi.fn();
    render(<Harness onComplete={onComplete} />);
    fireEvent.paste(box(1), { clipboardData: { getData: () => ' 123-456 ' } });

    expect(value()).toBe('123456');
    expect([1, 2, 3, 4, 5, 6].map((n) => box(n).value).join('')).toBe('123456');
    expect(onComplete).toHaveBeenCalledWith('123456');
    expect(box(6)).toHaveFocus();
  });

  it('accepts a whole code autofilled into the first box', () => {
    const onComplete = vi.fn();
    render(<Harness onComplete={onComplete} />);
    fireEvent.change(box(1), { target: { value: '987654' } });
    expect(value()).toBe('987654');
    expect(onComplete).toHaveBeenCalledWith('987654');
  });

  it('Backspace clears the current digit, then moves back and clears the previous one', () => {
    render(<Harness />);
    fireEvent.paste(box(1), { clipboardData: { getData: () => '123' } });
    expect(box(4)).toHaveFocus();

    fireEvent.keyDown(box(4), { key: 'Backspace' });
    expect(value()).toBe('12');
    expect(box(3)).toHaveFocus();

    // On a filled box, Backspace clears that digit and stays; the digit after it stays put.
    fireEvent.paste(box(3), { clipboardData: { getData: () => '3' } });
    box(2).focus();
    fireEvent.keyDown(box(2), { key: 'Backspace' });
    expect(box(2).value).toBe('');
    expect(box(2)).toHaveFocus();
    expect(box(3).value).toBe('3');
    expect(value()).toBe('1 3');
  });

  it('arrow keys move between boxes', () => {
    render(<Harness />);
    fireEvent.keyDown(box(1), { key: 'ArrowRight' });
    expect(box(2)).toHaveFocus();
    fireEvent.keyDown(box(2), { key: 'ArrowLeft' });
    expect(box(1)).toHaveFocus();
  });

  it('marks every box invalid for screen readers when told to', () => {
    render(<OtpInput value="111111" onChange={() => {}} invalid />);
    expect(screen.getAllByRole('textbox').every((input) => input.getAttribute('aria-invalid') === 'true')).toBe(true);
  });

  it('plays a light sweep on a box each time a digit lands in it', () => {
    const { container } = render(<Harness />);
    expect(container.querySelectorAll('.otp-sweep')).toHaveLength(0);
    fireEvent.change(box(1), { target: { value: '4' } });
    expect(box(1).parentElement?.querySelectorAll('.otp-sweep')).toHaveLength(1);
    expect(container.querySelectorAll('.otp-sweep')).toHaveLength(1);
  });
});

describe('orbit geometry', () => {
  const size = 50;
  const centres = [-150, -90, -30, 30, 90, 150];
  const radius = size * ORBIT_RADIUS;

  const parse = (frame: Keyframe) => {
    const [, x, y] = /translate\((-?[\d.]+)px, (-?[\d.]+)px\)/.exec(String(frame.transform))!;
    return { x: Number(x), y: Number(y) };
  };

  it('places the first digit on the left and the rest clockwise, evenly spaced', () => {
    const slots = orbitLayout(centres);
    expect(slots.map((slot) => slot.orbitAngle)).toEqual([180, 240, 300, 360, 420, 480]);
    expect(slots.map((slot) => slot.startAngle)).toEqual([180, 180, 180, 0, 0, 0]);
  });

  it('curls each box from its row position onto its exact point on the ring', () => {
    orbitLayout(centres).forEach((slot) => {
      const sweep = (((slot.orbitAngle - slot.startAngle) % 360) + 360) % 360;
      const frames = polarPath(slot, slot.startRadius, slot.startAngle, radius, sweep);
      const start = parse(frames[0]);
      const end = parse(frames[frames.length - 1]);
      expect(start.x).toBeCloseTo(0, 1);
      expect(start.y).toBeCloseTo(0, 1);
      // Where the box centre ends up, relative to the hub: on the ring, at its angle.
      const centre = { x: end.x + slot.cx, y: end.y };
      expect(Math.hypot(centre.x, centre.y)).toBeCloseTo(radius, 1);
      const angle = ((Math.atan2(centre.y, centre.x) * 180) / Math.PI + 360) % 360;
      expect(angle).toBeCloseTo(slot.orbitAngle % 360, 0);
    });
  });

  it('curls the left half upward and the right half downward', () => {
    const [, second, , , fifth] = orbitLayout(centres);
    const midway = (slot: SlotGeometry) => {
      const sweep = (((slot.orbitAngle - slot.startAngle) % 360) + 360) % 360;
      return parse(polarPath(slot, slot.startRadius, slot.startAngle, radius, sweep)[4]);
    };
    expect(midway(second).y).toBeLessThan(0);
    expect(midway(fifth).y).toBeGreaterThan(0);
  });
});

describe('OtpInput motion', () => {
  const originalAnimate = Element.prototype.animate;
  const originalGetAnimations = Element.prototype.getAnimations;

  afterEach(() => {
    Element.prototype.animate = originalAnimate;
    Element.prototype.getAnimations = originalGetAnimations;
  });

  it('without animation support, reports each motion as settled at once and still shows the ring state', () => {
    const onMotionEnd = vi.fn();
    const { container, rerender } = render(<OtpInput value="123456" onChange={() => {}} onMotionEnd={onMotionEnd} />);
    const stage = container.querySelector('.otp-stage')!;
    expect(stage.className).toBe('otp-stage');

    rerender(<OtpInput value="123456" onChange={() => {}} onMotionEnd={onMotionEnd} motion="orbit" />);
    expect(stage.className).toContain('is-orbit');
    return waitFor(() => expect(onMotionEnd).toHaveBeenCalledWith('orbit'));
  });

  it('curls onto the orbit, spins one and a quarter turns about the hub, then collapses', async () => {
    const played: string[] = [];
    Element.prototype.animate = vi.fn(function (frames: Keyframe[] | PropertyIndexedKeyframes | null) {
      played.push(JSON.stringify(frames));
      return { finished: Promise.resolve(), cancel: vi.fn() } as unknown as Animation;
    }) as typeof Element.prototype.animate;
    Element.prototype.getAnimations = () => [];
    const onMotionEnd = vi.fn();
    const { rerender } = render(<OtpInput value="123456" onChange={() => {}} onMotionEnd={onMotionEnd} />);

    rerender(<OtpInput value="123456" onChange={() => {}} onMotionEnd={onMotionEnd} motion="orbit" />);
    await waitFor(() => expect(onMotionEnd).toHaveBeenCalledWith('orbit'));
    expect(played.some((frames) => frames.includes('scale('))).toBe(true);
    expect(played.filter((frames) => frames.includes('rotate(450deg)'))).toHaveLength(7);
    expect(played.filter((frames) => frames.includes('rotate(-90deg)'))).toHaveLength(6);

    rerender(<OtpInput value="123456" onChange={() => {}} onMotionEnd={onMotionEnd} motion="collapse" />);
    await waitFor(() => expect(onMotionEnd).toHaveBeenCalledWith('collapse'));
    expect(played.some((frames) => frames.includes('"opacity":0'))).toBe(true);
  });
});
