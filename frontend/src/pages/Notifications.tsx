import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { AppNotification, NotificationPreferences } from '../api/types';
import { useToast } from '../components/feedback';
import { Badge, Card, EmptyState, ErrorState, PageHeader, SkeletonCards } from '../components/ui';
import { useApi } from '../hooks/useApi';

const TYPE_LABEL: Record<string, string> = {
  JOB_MATCH: 'Job match', FOLLOW_UP: 'Follow-up', INTERVIEW: 'Interview', LEARNING: 'Learning', CAREER: 'Career',
};

const PREFERENCE_LABEL: { key: keyof NotificationPreferences; label: string }[] = [
  { key: 'jobMatches', label: 'New job matches from your alerts' },
  { key: 'followUps', label: 'Application follow-up reminders' },
  { key: 'interviews', label: 'Interview reminders' },
  { key: 'learning', label: 'Learning target reminders' },
  { key: 'career', label: 'Career updates and achievements' },
];

/** "5 min ago" for recent ones; the exact time is always in the tooltip and the datetime attribute. */
export function relativeTime(iso: string, now = Date.now()): string {
  const minutes = Math.round((now - new Date(iso).getTime()) / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return days < 7 ? `${days} d ago` : new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' });
}

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

/** V9.16: the notification center; everything shown is the signed-in user's own. */
export function NotificationsPage() {
  const [attempt, setAttempt] = useState(0);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const inbox = useApi(() => api.notifications(unreadOnly), [attempt, unreadOnly]);
  const toast = useToast();
  const reload = () => {
    setAttempt((count) => count + 1);
    window.dispatchEvent(new Event('jmip:notifications'));
  };

  const open = async (item: AppNotification) => {
    if (!item.readAt) {
      try {
        await api.markNotificationRead(item.id);
        reload();
      } catch (cause) {
        toast(messageOf(cause), 'error');
      }
    }
  };
  const readAll = async () => {
    try {
      await api.markAllNotificationsRead();
      toast('All notifications marked as read.');
      reload();
    } catch (cause) {
      toast(messageOf(cause), 'error');
    }
  };

  return (
    <>
      <PageHeader title="Notifications"
        description="Job matches, follow-ups, interviews, learning and career updates, from your own activity."
        actions={inbox.data && inbox.data.unreadCount > 0 && (
          <button type="button" className="small" onClick={readAll}>Mark all as read</button>
        )} />
      <label className="row small" style={{ gap: 6, marginBottom: 12 }}>
        <input type="checkbox" checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} /> Unread only
      </label>
      {inbox.error ? (
        <ErrorState message={inbox.error} onRetry={reload} />
      ) : !inbox.data ? (
        <SkeletonCards count={2} />
      ) : inbox.data.items.length === 0 ? (
        <Card><EmptyState title={unreadOnly ? 'No unread notifications' : 'No notifications yet'}
          message="Reminders appear here when a follow-up, interview or learning target is near, or a job alert finds matches." /></Card>
      ) : (
        <Card title={`${inbox.data.unreadCount} unread`}>
          <ul className="notification-list" aria-label="Notifications">
            {inbox.data.items.map((item) => (
              <li key={item.id} className={item.readAt ? 'notification' : 'notification is-unread'}>
                <span className="notification-dot" aria-hidden="true" />
                <div className="notification-main">
                  <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                    <Badge tone={item.readAt ? 'neutral' : 'brand'}>{TYPE_LABEL[item.type] ?? item.type}</Badge>
                    <time className="muted small" dateTime={item.createdAt} title={new Date(item.createdAt).toLocaleString()}>
                      {relativeTime(item.createdAt)}
                    </time>
                    {!item.readAt && <span className="visually-hidden">Unread</span>}
                  </span>
                  {item.link ? (
                    <Link to={item.link} onClick={() => void open(item)}><strong>{item.title}</strong></Link>
                  ) : <strong>{item.title}</strong>}
                  {item.body && <span className="small">{item.body}</span>}
                </div>
                {!item.readAt && (
                  <button type="button" className="small ghost" onClick={() => open(item)} aria-label={`Mark “${item.title}” as read`}>
                    Mark read
                  </button>
                )}
              </li>
            ))}
          </ul>
        </Card>
      )}
      <NotificationPreferencesCard onSaved={reload} />
    </>
  );
}

/** Also shown in Settings (V9.17). */
export function NotificationPreferencesCard({ onSaved }: { onSaved: () => void }) {
  const prefs = useApi(() => api.notificationPreferences(), []);
  const [draft, setDraft] = useState<NotificationPreferences | null>(null);
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const current = draft ?? prefs.data;
  if (!current) return null;

  const save = async () => {
    setBusy(true);
    try {
      setDraft(await api.saveNotificationPreferences(current));
      toast('Notification preferences saved.');
      onSaved();
    } catch (cause) {
      toast(messageOf(cause), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Notification preferences" description="Choose which reminders you want. Turned-off kinds are not created or shown.">
      <fieldset className="builder-entry">
        <legend className="visually-hidden">Notify me about</legend>
        <div className="stack" style={{ gap: 8 }}>
          {PREFERENCE_LABEL.map(({ key, label }) => (
            <label key={key} className="row small" style={{ gap: 8 }}>
              <input type="checkbox" checked={current[key]} disabled={busy}
                onChange={(e) => setDraft({ ...current, [key]: e.target.checked })} /> {label}
            </label>
          ))}
        </div>
      </fieldset>
      <button type="button" className="small" style={{ marginTop: 12 }} disabled={busy || !draft} onClick={save}>
        {busy ? 'Saving…' : 'Save preferences'}
      </button>
    </Card>
  );
}
