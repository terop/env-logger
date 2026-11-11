import { afterEach, describe, expect, it, vi } from 'vitest';
import { endIdpSession } from '../auth/idp-session.js';

const LOGOUT_URL = 'https://auth.example.com/api/logout';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('endIdpSession', () => {
  it('does nothing when no session logout URL is configured', async () => {
    const fetchFn = vi.fn();

    await endIdpSession(undefined, fetchFn);
    await endIdpSession(null, fetchFn);
    await endIdpSession('', fetchFn);

    expect(fetchFn).not.toHaveBeenCalled();
  });

  it('sends a simple, credentialed, keepalive POST to the logout URL', async () => {
    const fetchFn = vi.fn().mockResolvedValue({});

    await endIdpSession(LOGOUT_URL, fetchFn);

    expect(fetchFn).toHaveBeenCalledTimes(1);
    const [url, init] = fetchFn.mock.calls[0];
    expect(url).toBe(LOGOUT_URL);
    expect(init.method).toBe('POST');
    expect(init.mode).toBe('no-cors');
    expect(init.credentials).toBe('include');
    expect(init.keepalive).toBe(true);
    // text/plain keeps the request CORS-simple (no preflight)
    expect(init.headers).toEqual({ 'Content-Type': 'text/plain' });
    expect(init.body).toBe('{}');
  });

  it('logs and swallows network errors', async () => {
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});
    const fetchFn = vi.fn().mockRejectedValue(new Error('network'));

    await expect(endIdpSession(LOGOUT_URL, fetchFn)).resolves.toBeUndefined();

    expect(consoleError).toHaveBeenCalledTimes(1);
    expect(consoleError.mock.calls[0][0]).toContain('network');
  });
});
