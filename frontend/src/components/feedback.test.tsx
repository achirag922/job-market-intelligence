import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FeedbackProvider, useConfirm, useToast } from './feedback';

function Demo() {
  const confirm = useConfirm();
  const toast = useToast();
  const [answer, setAnswer] = useState('none');
  return (
    <>
      <button type="button" onClick={async () => setAnswer(String(await confirm({
        title: 'Delete the alert?', message: 'This cannot be undone.', confirmLabel: 'Delete', tone: 'danger',
      })))}>
        Open
      </button>
      <button type="button" onClick={() => toast('Alert deleted.')}>Notify</button>
      <p>Answer: {answer}</p>
    </>
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('feedback (V9.11)', () => {
  it('asks in an accessible dialog: focus on Cancel, Tab stays inside, Escape cancels, focus returns', async () => {
    render(<FeedbackProvider><Demo /></FeedbackProvider>);
    const opener = screen.getByRole('button', { name: 'Open' });
    opener.focus();
    fireEvent.click(opener);

    const dialog = await screen.findByRole('alertdialog', { name: 'Delete the alert?' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(screen.getByText('This cannot be undone.')).toBeTruthy();
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    const confirm = screen.getByRole('button', { name: 'Delete' });
    expect(document.activeElement).toBe(cancel);
    expect(confirm.className).toBe('danger');

    // Tab from the last button wraps to the first, and Shift+Tab from the first to the last.
    confirm.focus();
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(cancel);
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(confirm);

    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(await screen.findByText('Answer: false')).toBeTruthy();
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it('resolves true when confirmed', async () => {
    render(<FeedbackProvider><Demo /></FeedbackProvider>);
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Answer: true')).toBeTruthy();
  });

  it('moves focus to the main content when the opener was removed by the action', async () => {
    function Removable() {
      const confirm = useConfirm();
      const [gone, setGone] = useState(false);
      return (
        <main id="main-content" tabIndex={-1}>
          {!gone && (
            <button type="button" onClick={async () => { if (await confirm({ title: 'Delete it?' })) setGone(true); }}>
              Remove row
            </button>
          )}
        </main>
      );
    }
    render(<FeedbackProvider><Removable /></FeedbackProvider>);
    const opener = screen.getByRole('button', { name: 'Remove row' });
    opener.focus();
    fireEvent.click(opener);
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove row' })).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(document.getElementById('main-content')));
  });

  it('shows a toast that can be dismissed and goes away by itself', () => {
    vi.useFakeTimers();
    render(<FeedbackProvider><Demo /></FeedbackProvider>);
    fireEvent.click(screen.getByRole('button', { name: 'Notify' }));
    expect(screen.getByRole('status').textContent).toContain('Alert deleted.');
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss notification' }));
    expect(screen.queryByText('Alert deleted.')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Notify' }));
    act(() => { vi.advanceTimersByTime(4100); });
    expect(screen.queryByText('Alert deleted.')).toBeNull();
  });

  it('without the provider, confirm falls back to the browser dialog and toasts are dropped', async () => {
    const native = vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<Demo />);
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    expect(await screen.findByText('Answer: true')).toBeTruthy();
    expect(native).toHaveBeenCalledWith('Delete the alert?\n\nThis cannot be undone.');
    fireEvent.click(screen.getByRole('button', { name: 'Notify' }));
    expect(screen.queryByText('Alert deleted.')).toBeNull();
  });
});
