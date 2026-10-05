import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { NotificationInbox } from '../api/types';
import { NotificationsPage, relativeTime } from './Notifications';

const notifications = vi.fn();
const markNotificationRead = vi.fn();
const markAllNotificationsRead = vi.fn();
const notificationPreferences = vi.fn();
const saveNotificationPreferences = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      notifications: (...a: unknown[]) => notifications(...a),
      markNotificationRead: (...a: unknown[]) => markNotificationRead(...a),
      markAllNotificationsRead: (...a: unknown[]) => markAllNotificationsRead(...a),
      notificationPreferences: (...a: unknown[]) => notificationPreferences(...a),
      saveNotificationPreferences: (...a: unknown[]) => saveNotificationPreferences(...a),
    },
  };
});

const inbox: NotificationInbox = {
  unreadCount: 1,
  items: [
    { id: 'n1', type: 'FOLLOW_UP', title: 'Follow-up overdue: Engineer 1', body: 'Acme · due 2026-10-04', link: '/workspace',
      createdAt: new Date(Date.now() - 5 * 60_000).toISOString() },
    { id: 'n2', type: 'JOB_MATCH', title: '2 new jobs match “Java roles”', link: '/alerts',
      createdAt: new Date(Date.now() - 3 * 3_600_000).toISOString(), readAt: new Date().toISOString() },
  ],
};

beforeEach(() => {
  notifications.mockResolvedValue(inbox);
  markNotificationRead.mockResolvedValue(undefined);
  markAllNotificationsRead.mockResolvedValue({ unreadCount: 0 });
  notificationPreferences.mockResolvedValue({ jobMatches: true, followUps: true, interviews: true, learning: true, career: true });
  saveNotificationPreferences.mockImplementation((p) => Promise.resolve(p));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Notifications (V9.16)', () => {
  it('lists notifications with type, unread state and timestamps, and marks them read', async () => {
    render(<MemoryRouter><NotificationsPage /></MemoryRouter>);
    const list = await screen.findByRole('list', { name: 'Notifications' });
    const items = within(list).getAllByRole('listitem');
    expect(items[0].className).toContain('is-unread');
    expect(items[1].className).not.toContain('is-unread');
    expect(within(items[0]).getByText('5 min ago').getAttribute('datetime')).toBe(inbox.items[0].createdAt);
    expect(within(items[1]).getByText('3 h ago')).toBeTruthy();
    expect(screen.getByText('1 unread')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Mark “Follow-up overdue: Engineer 1” as read' }));
    await waitFor(() => expect(markNotificationRead).toHaveBeenCalledWith('n1'));
    fireEvent.click(screen.getByRole('button', { name: 'Mark all as read' }));
    await waitFor(() => expect(markAllNotificationsRead).toHaveBeenCalled());
  });

  it('filters to unread and saves preferences', async () => {
    render(<MemoryRouter><NotificationsPage /></MemoryRouter>);
    await screen.findByRole('list', { name: 'Notifications' });
    fireEvent.click(screen.getByLabelText('Unread only'));
    await waitFor(() => expect(notifications).toHaveBeenLastCalledWith(true));

    fireEvent.click(await screen.findByLabelText('Learning target reminders'));
    fireEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await waitFor(() => expect(saveNotificationPreferences).toHaveBeenCalledWith(
      { jobMatches: true, followUps: true, interviews: true, learning: false, career: true }));
  });

  it('explains an empty inbox', async () => {
    notifications.mockResolvedValue({ items: [], unreadCount: 0 });
    render(<MemoryRouter><NotificationsPage /></MemoryRouter>);
    expect(await screen.findByText('No notifications yet')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Mark all as read' })).toBeNull();
  });

  it('formats relative times', () => {
    const now = Date.parse('2026-10-05T12:00:00Z');
    expect(relativeTime('2026-10-05T11:59:40Z', now)).toBe('just now');
    expect(relativeTime('2026-10-05T10:00:00Z', now)).toBe('2 h ago');
    expect(relativeTime('2026-10-03T12:00:00Z', now)).toBe('2 d ago');
  });
});
