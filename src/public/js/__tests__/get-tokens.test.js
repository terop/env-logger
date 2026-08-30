import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  buildCodeExchangeRequest,
  getTokens,
  hasAuthorizationCode
} from '../auth/get-tokens.js';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('hasAuthorizationCode', () => {
  it('is true only when code is present', () => {
    expect(hasAuthorizationCode('?code=abc')).toBe(true);
    expect(hasAuthorizationCode('?code=')).toBe(true);
    expect(hasAuthorizationCode('?state=xyz')).toBe(false);
    expect(hasAuthorizationCode('')).toBe(false);
  });
});

describe('buildCodeExchangeRequest', () => {
  it('sets redirect_uri to applicationUrl + login', () => {
    const { url, init } = buildCodeExchangeRequest({
      oidBaseUrl: 'https://idp.example/realms/app',
      clientId: 'env-logger',
      applicationUrl: 'http://example.com/env-logger/',
      code: 'auth-code'
    });
    const body = new URLSearchParams(init.body);

    expect(url).toBe(
      'https://idp.example/realms/app/protocol/openid-connect/token'
    );
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('omit');
    expect(body.get('grant_type')).toBe('authorization_code');
    expect(body.get('client_id')).toBe('env-logger');
    expect(body.get('redirect_uri')).toBe(
      'http://example.com/env-logger/login'
    );
    expect(body.get('code')).toBe('auth-code');
    expect(body.get('redirect_uri')).not.toContain('env-logger//login');
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

  it('does not exchange when code is absent', async () => {
    const fetchFn = vi.fn();
    const storeTokens = vi.fn();
    const storeIdToken = vi.fn();

    await getTokens({
      search: '',
      authSettings,
      fetchFn,
      storeTokens,
      storeIdToken
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
      search: '?code=auth-code',
      authSettings,
      fetchFn,
      storeTokens,
      storeIdToken
    });

    expect(fetchFn).toHaveBeenCalledTimes(1);
    const [url, init] = fetchFn.mock.calls[0];
    expect(url).toBe(
      'https://idp.example/realms/app/protocol/openid-connect/token'
    );
    expect(new URLSearchParams(init.body).get('code')).toBe('auth-code');
    expect(storeTokens).toHaveBeenCalledWith(tokens);
    expect(storeIdToken).toHaveBeenCalledWith(tokens);
    expect(storeTokens.mock.invocationCallOrder[0]).toBeLessThan(
      storeIdToken.mock.invocationCallOrder[0]
    );
  });

  it('exchanges an empty code parameter (same as URLSearchParams.has)', async () => {
    const fetchFn = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => tokens
    });

    await getTokens({
      search: '?code=',
      authSettings,
      fetchFn,
      storeTokens: vi.fn(),
      storeIdToken: vi.fn().mockResolvedValue(true)
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
      search: '?code=auth-code',
      authSettings,
      fetchFn,
      storeTokens,
      storeIdToken
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
      search: '?code=auth-code',
      authSettings,
      fetchFn: vi.fn().mockRejectedValue(new Error('network')),
      storeTokens,
      storeIdToken
    });

    expect(storeTokens).not.toHaveBeenCalled();
    expect(storeIdToken).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith(
      'Error exchanging code for tokens: Error: network'
    );
  });
});
