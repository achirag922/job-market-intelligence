import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ErrorBoundary } from './ErrorBoundary';

let shouldThrow = true;

function Fragile() {
  if (shouldThrow) {
    throw new Error('TypeError: cannot read properties of undefined (reading "skills") at Resume.tsx:42');
  }
  return <p>Page content</p>;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  shouldThrow = true;
});

describe('ErrorBoundary', () => {
  it('replaces a crashed page with a plain message, never the error itself, and recovers on retry', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(
      <ErrorBoundary>
        <Fragile />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('alert').textContent).toContain('Something went wrong on this page');
    expect(screen.queryByText(/TypeError|Resume\.tsx/)).toBeNull();

    shouldThrow = false;
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(screen.getByText('Page content')).toBeTruthy();
  });
});
