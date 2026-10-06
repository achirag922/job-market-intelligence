import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { FeedbackProvider } from '../components/feedback';
import { SettingsPage } from './Settings';

const replaceUser = vi.fn();
const updateAccountName = vi.fn();
const changePassword = vi.fn();
const deleteAccount = vi.fn();
const portfolio = vi.fn();
const publishPortfolio = vi.fn();

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'u1', fullName: 'Alex Rivera', email: 'alex@example.test' }, replaceUser }),
}));
vi.mock('../components/MatchBreakdown', () => ({ MatchPreferencesCard: () => <p>Job preferences card</p> }));
vi.mock('./Notifications', () => ({ NotificationPreferencesCard: () => <p>Notification preferences card</p> }));
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      updateAccountName: (...a: unknown[]) => updateAccountName(...a),
      changePassword: (...a: unknown[]) => changePassword(...a),
      deleteAccount: (...a: unknown[]) => deleteAccount(...a),
      portfolio: (...a: unknown[]) => portfolio(...a),
      publishPortfolio: (...a: unknown[]) => publishPortfolio(...a),
      unpublishPortfolio: vi.fn(),
    },
  };
});

function renderPage() {
  render(<MemoryRouter><FeedbackProvider><SettingsPage /></FeedbackProvider></MemoryRouter>);
}

beforeEach(() => {
  localStorage.clear();
  portfolio.mockResolvedValue({ slug: 'alex', displayName: 'Alex', visibility: 'PRIVATE', publicPath: '/profile/alex' });
  publishPortfolio.mockResolvedValue({ slug: 'alex', displayName: 'Alex', visibility: 'PUBLIC', publicPath: '/profile/alex' });
  updateAccountName.mockResolvedValue({ id: 'u1', fullName: 'Alex R', email: 'alex@example.test' });
  changePassword.mockResolvedValue(undefined);
  deleteAccount.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Settings (V9.17)', () => {
  it('shows every section, reusing the job and notification preference cards', async () => {
    renderPage();
    for (const name of ['Profile', 'Privacy & public profile', 'Appearance', 'Account']) {
      expect(screen.getByRole('heading', { name })).toBeTruthy();
    }
    expect(screen.getByText('Job preferences card')).toBeTruthy();
    expect(screen.getByText('Notification preferences card')).toBeTruthy();
    expect(screen.getByLabelText('Email')).toHaveProperty('readOnly', true);
    expect(await screen.findByText('Private')).toBeTruthy();
  });

  it('saves the name and updates the signed-in user', async () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Alex R' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    await waitFor(() => expect(updateAccountName).toHaveBeenCalledWith('Alex R'));
    expect(replaceUser).toHaveBeenCalledWith(expect.objectContaining({ fullName: 'Alex R' }));
  });

  it('publishes the portfolio and switches the theme', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Publish' }));
    expect(await screen.findByText('Public')).toBeTruthy();
    fireEvent.click(screen.getByLabelText('Dark'));
    expect(screen.getByLabelText('Dark')).toHaveProperty('checked', true);
  });

  it('validates and changes the password, showing server errors', async () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old password here' } });
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'short' } });
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'short' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));
    expect(screen.getByText('The new password must be at least 12 characters.')).toBeTruthy();
    expect(changePassword).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a brand new passphrase' } });
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'a brand new passphrase' } });
    changePassword.mockRejectedValueOnce(new ApiError(400, 'Your current password is not correct'));
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));
    expect(await screen.findByText('Your current password is not correct')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));
    await waitFor(() => expect(changePassword).toHaveBeenLastCalledWith('old password here', 'a brand new passphrase'));
  });

  it('asks for confirmation before deleting the account', async () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Password to confirm deletion'), { target: { value: 'my password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Delete account' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(deleteAccount).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
  });
});
