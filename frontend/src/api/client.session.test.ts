import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, EmailNotVerifiedError, UNAUTHORIZED_EVENT, api } from './client';

/** V10.9: the security-relevant behaviour of the API client: session, CSRF token, 401 signal, request shapes. */

function respond(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

const session = (token: string) => ({ user: { id: 'u1', email: 'alex@example.test', fullName: 'Alex' }, csrfToken: token });

function headersOf(fetchMock: ReturnType<typeof vi.fn>, call: number): Record<string, string> {
  return (fetchMock.mock.calls[call][1] as RequestInit).headers as Record<string, string>;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('session and CSRF token', () => {
  it('keeps the CSRF token from login in memory, sends it on writes, and forgets it on logout', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respond(200, session('token-1')))
      .mockResolvedValueOnce(respond(204))
      .mockResolvedValueOnce(respond(204))
      .mockResolvedValueOnce(respond(204));
    vi.stubGlobal('fetch', fetchMock);

    await api.login('alex@example.test', 'a-long-passphrase');
    await api.deleteSavedJob('saved-1');
    await api.logout();
    await api.deleteSavedJob('saved-2');

    expect(headersOf(fetchMock, 1)['X-CSRF-TOKEN']).toBe('token-1');
    expect(headersOf(fetchMock, 2)['X-CSRF-TOKEN']).toBe('token-1');
    expect(headersOf(fetchMock, 3)['X-CSRF-TOKEN']).toBeUndefined();
    // Never persisted where scripts could read it later.
    expect(JSON.stringify({ ...localStorage })).not.toContain('token-1');
    // The session cookie travels with every call.
    fetchMock.mock.calls.forEach(([, init]) => expect((init as RequestInit).credentials).toBe('include'));
  });

  it('forgets the token even when the logout request fails', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respond(200, session('token-2')))
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(respond(204));
    vi.stubGlobal('fetch', fetchMock);

    await api.login('alex@example.test', 'a-long-passphrase');
    await expect(api.logout()).rejects.toBeInstanceOf(ApiError);
    await api.deleteSavedJob('saved-3');

    expect(headersOf(fetchMock, 2)['X-CSRF-TOKEN']).toBeUndefined();
  });

  it('a refused login for an unconfirmed email becomes EmailNotVerifiedError; wrong credentials stay a 401', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(respond(403, { status: 403, message: 'Confirm your email address first' }))
      .mockResolvedValueOnce(respond(401, { status: 401, message: 'Invalid email or password' })));

    await expect(api.login('new@example.test', 'a-long-passphrase')).rejects.toBeInstanceOf(EmailNotVerifiedError);
    const wrong = await api.login('alex@example.test', 'wrong').catch((error: ApiError) => error);
    expect(wrong).not.toBeInstanceOf(EmailNotVerifiedError);
    expect((wrong as ApiError).status).toBe(401);
  });

  it('currentSession restores the token after a reload, and reports signed-out as null', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respond(200, session('token-3')))
      .mockResolvedValueOnce(respond(204))
      .mockResolvedValueOnce(respond(401, { status: 401, message: 'Not signed in' }))
      .mockResolvedValueOnce(respond(204));
    vi.stubGlobal('fetch', fetchMock);

    expect((await api.currentSession())?.csrfToken).toBe('token-3');
    await api.deleteSavedJob('saved-4');
    expect(await api.currentSession()).toBeNull();
    await api.deleteSavedJob('saved-5');

    expect(headersOf(fetchMock, 1)['X-CSRF-TOKEN']).toBe('token-3');
    expect(headersOf(fetchMock, 3)['X-CSRF-TOKEN']).toBeUndefined();
  });
});

describe('expired sessions', () => {
  it('a 401 from a private endpoint signals the app to return to sign-in; auth endpoints do not', async () => {
    const listener = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, listener);
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(respond(401, { status: 401, message: 'Not signed in' }))
      .mockResolvedValueOnce(respond(401, { status: 401, message: 'Not signed in' }))
      .mockResolvedValueOnce(respond(401, { status: 401, message: 'Not signed in' })));

    await expect(api.overview()).rejects.toMatchObject({ status: 401 });
    await expect(api.deleteSavedJob('saved-6')).rejects.toMatchObject({ status: 401 });
    expect(await api.currentSession()).toBeNull();

    expect(listener).toHaveBeenCalledTimes(2);
    window.removeEventListener(UNAUTHORIZED_EVENT, listener);
  });
});

describe('request shapes', () => {
  it('leaves empty filters off the query string', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(respond(200, [])));
    vi.stubGlobal('fetch', fetchMock);

    await api.savedJobs(undefined);
    await api.savedJobs('APPLIED');

    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/api\/saved-jobs$/);
    expect(String(fetchMock.mock.calls[1][0])).toContain('status=APPLIED');
  });

  it('a write without a body sends no Content-Type and resolves to undefined on 204', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(204));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.hideJob(42)).resolves.toBeUndefined();
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
    expect(init.body).toBeUndefined();
  });

  it('uploads a resume as multipart form data, letting the browser set the boundary', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(201, { id: 'r1', status: 'COMPLETED' }));
    vi.stubGlobal('fetch', fetchMock);

    await api.uploadResume(new File(['%PDF-1.7'], 'resume.pdf', { type: 'application/pdf' }));

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('file')).toBeInstanceOf(File);
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });
});
