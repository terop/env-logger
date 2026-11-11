import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  buildCodeExchangeRequest,
  getTokens,
  hasAuthorizationCode
} from '../auth/get-tokens.js';

afterEach(() => {
  vi.restoreAllMocks();
});

const TOKEN_ENDPOINT = 'https://idp.example/realms/app/protocol/openid-connect/token';

const fakeSession = (entries = {}) => {
  const store = new Map(Object.entries(entries));
  return {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => store.set(key, String(value)),
    removeItem: (key) => store.delete(key),
    has: (key) => store.has(key)
  };
};

const discoveryOk = vi.fn(async () => ({ token_endpoint: TOKEN_ENDPOINT }));

describe('hasAuthorizationCode', () => {
  it('is true only when code is present', () => {
    expect(hasAuthorizationCode('?code=abc')).toBe(true);
    expect(hasAuthorizationCode('?code=')).toBe(true);
    expect(hasAuthorizationCode('?state=xyz')).toBe(false);
    expect(hasAuthorizationCode('')).toBe(false);
  });
});

describe('buildCodeExchangeRequest', () => {
  it('uses the discovery token endpoint and sends the PKCE verifier', () => {
    const { url, init } = buildCodeExchangeRequest({
      tokenEndpoint: TOKEN_ENDPOINT,
      clientId: 'env-logger',
      applicationUrl: 'http://example.com/env-logger/',
      code: 'auth-code',
      codeVerifier: 'verifier-123'
    });
    const body = new URLSearchParams(init.body);

    expect(url).toBe(TOKEN_ENDPOINT);
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('omit');
    expect(body.get('grant_type')).toBe('authorization_code');
    expect(body.get('client_id')).toBe('env-logger');
    expect(body.get('redirect_uri')).toBe(
      'http://example.com/env-logger/login'
    );
    expect(body.get('redirect_uri')).not.toContain('env-logger//login');
    expect(body.get('code')).toBe('auth-code');
    expect(body.get('code_verifier')).toBe('verifier-123');
  });
});

describe('getTokens', () => {
  const authSettings = {
    oidBaseUrl: 'https://idp.example/realms/app',
    clientId: 'env-logger',
    applicationUrl: 'http://example.com/env-logger/'
  };

  const tokens = {
    access_token: 'access',
    id_token: 'id',
    refresh_token: 'refresh'
  };

  const validSession = () =>
    fakeSession({ oidcState: 'state-abc', oidcCodeVerifier: 'verifier-123' });

  it('does not exchange when code is absent', async () => {
    const fetchFn = vi.fn();
    const storeTokens = vi.fn();
    const storeIdToken = vi.fn();

    await getTokens({
      search: '',
      authSettings,
      fetchFn,
      storeTokens,
      storeIdToken,
      loadDiscoveryFn: discoveryOk,
      sessionStore: validSession()
    });

    expect(fetchFn).not.toHaveBeenCalled();
    expect(storeTokens).not.toHaveBeenCalled();
    expect(storeIdToken).not.toHaveBeenCalled();
  });

  it('stores tokens then the ID token after a successful exchange', async () => {
    const fetchFn = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => tokens
    });
    const storeTokens = vi.fn();
    const storeIdToken = vi.fn().mockResolvedValue(true);

    await getTokens({
      search: '?code=auth-code&state=state-abc',
      authSettings,
      fetchFn,
      storeTokens,
      storeIdToken,
      loadDiscoveryFn: discoveryOk,
      sessionStore: validSession()
    });

    expect(fetchFn).toHaveBeenCalledTimes(1);
    const [url, init] = fetchFn.mock.calls[0];
    expect(url).toBe(TOKEN_ENDPOINT);
    const body = new URLSearchParams(init.body);
    expect(body.get('code')).toBe('auth-code');
    expect(body.get('code_verifier')).toBe('verifier-123');
    expect(storeTokens).toHaveBeenCalledWith(tokens);
    expect(storeIdToken).toHaveBeenCalledWith(tokens);
    expect(storeTokens.mock.invocationCallOrder[0]).toBeLessThan(
      storeIdToken.mock.invocationCallOrder[0]
    );
  });

  it('clears state and verifier after use', async () => {
    const session = validSession();
    await getTokens({
      search: '?code=auth-code&state=state-abc',
      authSettings,
      fetchFn: vi.fn().mockResolvedValue({ ok: true, json: async () => tokens }),
      storeTokens: vi.fn(),
      storeIdToken: vi.fn().mockResolvedValue(true),
      loadDiscoveryFn: discoveryOk,
      sessionStore: session
    });

    expect(session.has('oidcState')).toBe(false);
    expect(session.has('oidcCodeVerifier')).toBe(false);
  });

  it('rejects a mismatched state before any exchange', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const fetchFn = vi.fn();
    const storeTokens = vi.fn();
    const session = validSession();

    await getTokens({
      search: '?code=auth-code&state=attacker-state',
      authSettings,
      fetchFn,
      storeTokens,
      storeIdToken: vi.fn(),
      loadDiscoveryFn: discoveryOk,
      sessionStore: session
    });

    expect(fetchFn).not.toHaveBeenCalled();
    expect(storeTokens).not.toHaveBeenCalled();
    expect(session.has('oidcState')).toBe(false);
    expect(error).toHaveBeenCalledWith(
      'Error exchanging code for tokens: Error: OpenID state mismatch or missing PKCE verifier'
    );
  });

  it('rejects a callback when no state was stored', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const fetchFn = vi.fn();

    await getTokens({
      search: '?code=auth-code&state=state-abc',
      authSettings,
      fetchFn,
      storeTokens: vi.fn(),
      storeIdToken: vi.fn(),
      loadDiscoveryFn: discoveryOk,
      sessionStore: fakeSession()
    });

    expect(fetchFn).not.toHaveBeenCalled();
  });

  it('exchanges an empty code parameter (same as URLSearchParams.has)', async () => {
    const fetchFn = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => tokens
    });

    await getTokens({
      search: '?code=&state=state-abc',
      authSettings,
      fetchFn,
      storeTokens: vi.fn(),
      storeIdToken: vi.fn().mockResolvedValue(true),
      loadDiscoveryFn: discoveryOk,
      sessionStore: validSession()
    });

    expect(new URLSearchParams(fetchFn.mock.calls[0][1].body).get('code'))
      .toBe('');
  });

  it('does not store tokens when the exchange fails', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const fetchFn = vi.fn().mockResolvedValue({ ok: false, status: 400 });
    const storeTokens = vi.fn();
    const storeIdToken = vi.fn();

    await getTokens({
      search: '?code=auth-code&state=state-abc',
      authSettings,
      fetchFn,
      storeTokens,
      storeIdToken,
      loadDiscoveryFn: discoveryOk,
      sessionStore: validSession()
    });

    expect(storeTokens).not.toHaveBeenCalled();
    expect(storeIdToken).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith(
      'Error exchanging code for tokens: Error: Failed to exchange code for tokens'
    );
  });

  it('does not store tokens when fetch throws', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const storeTokens = vi.fn();
    const storeIdToken = vi.fn();

    await getTokens({
      search: '?code=auth-code&state=state-abc',
      authSettings,
      fetchFn: vi.fn().mockRejectedValue(new Error('network')),
      storeTokens,
      storeIdToken,
      loadDiscoveryFn: discoveryOk,
      sessionStore: validSession()
    });

    expect(storeTokens).not.toHaveBeenCalled();
    expect(storeIdToken).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith(
      'Error exchanging code for tokens: Error: network'
    );
  });
});
