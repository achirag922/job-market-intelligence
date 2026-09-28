import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, CANNOT_REACH, api, friendlyMessage } from './client';

function respond(status: number, body: unknown, headers: Record<string, string> = {}) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  return new Response(text, { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

async function failure(call: () => Promise<unknown>): Promise<ApiError> {
  try {
    await call();
  } catch (error) {
    return error as ApiError;
  }
  throw new Error('expected the call to fail');
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API error handling (V7.8)', () => {
  it('turns a server error into a plain sentence with the request id, never the server text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(500,
      { status: 500, message: 'org.postgresql.util.PSQLException: relation "jobs" does not exist' },
      { 'X-Request-Id': 'abc-123-request' })));

    const error = await failure(() => api.overview());

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(500);
    expect(error.message).toBe('Something went wrong on our side. Please try again in a moment. (Reference: abc-123-request)');
    expect(error.message).not.toContain('PSQLException');
  });

  it('keeps the message the server wrote for a request it rejected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(404, { status: 404, message: 'Job not found: 99' })));
    expect((await failure(() => api.job(99))).message).toBe('Job not found: 99');
  });

  it('explains a failure without a readable body, such as a proxy page', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 })));
    expect((await failure(() => api.overview())).message).toMatch(/^Something went wrong on our side/);

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 429 })));
    expect((await failure(() => api.overview())).message).toBe('Too many requests. Please wait a minute and try again.');
  });

  it('says the server cannot be reached, without naming addresses, when the request never arrives', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const error = await failure(() => api.overview());
    expect(error.status).toBe(0);
    expect(error.message).toBe(CANNOT_REACH);
    expect(error.message).not.toContain('localhost');
  });

  it('shows validation messages from the server for form submissions', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(400, {
      status: 400, message: 'Request validation failed',
      fieldErrors: [{ field: 'name', message: 'name is required' }, { field: 'frequency', message: 'frequency is required' }],
    })));
    expect((await failure(() => api.createJobAlert({ name: '', frequency: 'DAILY' }))).message)
      .toBe('name is required; frequency is required');
  });

  it('falls back to a status sentence when the server gives no message', () => {
    const headers = new Headers();
    expect(friendlyMessage({ status: 403, headers }, '')).toBe('You do not have access to that.');
    expect(friendlyMessage({ status: 418, headers }, '')).toBe('The request could not be completed (418).');
    expect(friendlyMessage({ status: 503, headers }, 'maintenance')).toBe('Something went wrong on our side. Please try again in a moment.');
  });
});
