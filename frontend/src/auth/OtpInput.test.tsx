import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { OtpInput } from './OtpInput';

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
});
