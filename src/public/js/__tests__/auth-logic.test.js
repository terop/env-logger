import { afterEach, describe, expect, it, vi } from 'vitest';
import { DateTime } from 'luxon';
import {
  accessTokenExpiry,
  authCookiePath,
  authCookiePaths,
  buildLoginUrl,
  isCallbackStateValid,
  isIdTokenPostSuccess,
  isRefreshSessionOver,
  pkceChallenge,
  randomBase64Url,
  shouldRefreshAccessToken
} from '../auth-logic.js';

const LOCATION = 'http://localhost/';
const NOW = DateTime.fromISO('2024-06-15T12:00:00.000Z');

const jwtWithExp = (exp) => {
  const payload = Buffer.from(JSON.stringify({ exp })).toString('base64');
  return `hdr.${payload}.sig`;
};

describe('authCookiePath', () => {
  it('uses / for a root base URL', () => {
    expect(authCookiePath('/', LOCATION)).toBe('/');
    expect(authCookiePath('http://example.com/', LOCATION)).toBe('/');
  });

  it('keeps a subpath without a trailing slash', () => {
    expect(authCookiePath('/env-logger', LOCATION)).toBe('/env-logger');
  });

  it('keeps a trailing slash on a subpath', () => {
    expect(authCookiePath('/env-logger/', LOCATION)).toBe('/env-logger/');
  });

  it('falls back to / for a bad base URL', () => {
    expect(authCookiePath('http://', LOCATION)).toBe('/');
  });
});

describe('authCookiePaths', () => {
  it('writes only / for a root path', () => {
    expect(authCookiePaths('/', LOCATION)).toEqual(['/']);
  });

  it('writes the subpath and / when there is no trailing slash', () => {
    expect(authCookiePaths('/app', LOCATION)).toEqual(['/app', '/']);
    expect(authCookiePaths('/env-logger', LOCATION)).toEqual([
      '/env-logger',
      '/'
    ]);
  });

  it('writes slash and non-slash subpath variants', () => {
    expect(authCookiePaths('/app/', LOCATION)).toEqual(['/app/', '/', '/app']);
    expect(authCookiePaths('/env-logger/', LOCATION)).toEqual([
      '/env-logger/',
      '/',
      '/env-logger'
    ]);
  });

  it('falls back to / for a bad base URL', () => {
    expect(authCookiePaths('http://', LOCATION)).toEqual(['/']);
  });
});

describe('accessTokenExpiry', () => {
  it('prefers expires_in over a JWT exp claim', () => {
    const tokens = {
      expires_in: 3600,
      access_token: jwtWithExp(NOW.toSeconds() + 60)
    };
    expect(accessTokenExpiry(tokens, NOW, DateTime).toISO()).toBe(
      NOW.plus({ seconds: 3600 }).toISO()
    );
  });

  it('reads exp from the access token JWT when expires_in is absent', () => {
    const exp = NOW.toSeconds() + 1200;
    const tokens = { access_token: jwtWithExp(exp) };
    expect(accessTokenExpiry(tokens, NOW, DateTime).toISO()).toBe(
      DateTime.fromSeconds(exp).toISO()
    );
  });

  it('uses now + 300s for a garbage token', () => {
    const tokens = { access_token: 'not-a-jwt' };
    expect(accessTokenExpiry(tokens, NOW, DateTime).toISO()).toBe(
      NOW.plus({ seconds: 300 }).toISO()
    );
  });
});

describe('shouldRefreshAccessToken', () => {
  const refreshArgs = (overrides = {}) => ({
    refreshToken: 'refresh-token',
    expiresAt: NOW.plus({ seconds: 90 }).toISO(),
    force: false,
    now: NOW,
    DateTime,
    ...overrides
  });

  it('skips refresh when more than 60 seconds remain', () => {
    expect(shouldRefreshAccessToken(refreshArgs())).toBe(false);
  });

  it('refreshes when 30 seconds remain', () => {
    expect(
      shouldRefreshAccessToken(
        refreshArgs({ expiresAt: NOW.plus({ seconds: 30 }).toISO() })
      )
    ).toBe(true);
  });

  it('refreshes when force is true', () => {
    expect(shouldRefreshAccessToken(refreshArgs({ force: true }))).toBe(true);
  });

  it('does not refresh without a refresh token', () => {
    expect(shouldRefreshAccessToken(refreshArgs({ refreshToken: null }))).toBe(
      false
    );
    expect(shouldRefreshAccessToken(refreshArgs({ refreshToken: '' }))).toBe(
      false
    );
  });
});

describe('isIdTokenPostSuccess', () => {
  it('treats 200 + Not valid as failure', () => {
    expect(isIdTokenPostSuccess({ ok: true }, 'Not valid')).toBe(false);
    expect(isIdTokenPostSuccess({ ok: true }, '  Not valid  ')).toBe(false);
  });

  it('treats 200 + empty or OK as success', () => {
    expect(isIdTokenPostSuccess({ ok: true }, '')).toBe(true);
    expect(isIdTokenPostSuccess({ ok: true }, 'OK')).toBe(true);
  });

  it('treats non-2xx as failure', () => {
    expect(isIdTokenPostSuccess({ ok: false }, 'OK')).toBe(false);
  });
});

describe('isRefreshSessionOver', () => {
  it('ends the session on HTTP 400 or 401', () => {
    expect(isRefreshSessionOver(400)).toBe(true);
    expect(isRefreshSessionOver(401)).toBe(true);
  });

  it('keeps the session on other HTTP statuses', () => {
    expect(isRefreshSessionOver(503)).toBe(false);
    expect(isRefreshSessionOver(500)).toBe(false);
  });

  it('keeps the session on a thrown fetch / network error', () => {
    expect(isRefreshSessionOver(undefined)).toBe(false);
    expect(isRefreshSessionOver(new Error('Failed to fetch'))).toBe(false);
  });
});

describe('buildLoginUrl', () => {
  const baseArgs = {
    authorizationEndpoint: 'https://idp.example/auth',
    clientId: 'env-logger',
    applicationUrl: 'http://example.com/env-logger/',
    state: 'state-abc',
    codeChallenge: 'challenge-xyz'
  };

  it('sets redirect_uri to applicationUrl + login without an extra slash', () => {
    const loginUrl = buildLoginUrl(baseArgs);
    const params = new URL(loginUrl).searchParams;

    expect(params.get('client_id')).toBe('env-logger');
    expect(params.get('redirect_uri')).toBe(
      'http://example.com/env-logger/login'
    );
    expect(loginUrl).not.toContain('env-logger//login');
  });

  it('includes state and PKCE S256 parameters', () => {
    const params = new URL(buildLoginUrl(baseArgs)).searchParams;

    expect(params.get('response_type')).toBe('code');
    expect(params.get('state')).toBe('state-abc');
    expect(params.get('code_challenge')).toBe('challenge-xyz');
    expect(params.get('code_challenge_method')).toBe('S256');
  });

  it('defaults scope to openid', () => {
    expect(new URL(buildLoginUrl(baseArgs)).searchParams.get('scope')).toBe(
      'openid'
    );
  });

  it('uses the configured scope, including offline_access for Authelia', () => {
    const params = new URL(
      buildLoginUrl({ ...baseArgs, scope: 'openid offline_access' })
    ).searchParams;

    expect(params.get('scope')).toBe('openid offline_access');
  });
});

describe('PKCE and state helpers', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('computes the RFC 7636 appendix B challenge', async () => {
    expect(
      await pkceChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')
    ).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('fails with a clear message when Web Crypto is unavailable (plain HTTP)', async () => {
    vi.stubGlobal('crypto', { getRandomValues: () => {} });

    await expect(pkceChallenge('verifier')).rejects.toThrow(
      /secure context \(HTTPS or localhost\)/
    );
  });

  it('generates URL-safe random values of the expected length', () => {
    const value = randomBase64Url();
    expect(value).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(value).toHaveLength(43);
    expect(randomBase64Url()).not.toBe(value);
  });

  it('accepts only the state that was stored for this login', () => {
    expect(isCallbackStateValid('?code=c&state=abc', 'abc')).toBe(true);
    expect(isCallbackStateValid('?code=c&state=other', 'abc')).toBe(false);
    expect(isCallbackStateValid('?code=c', 'abc')).toBe(false);
    expect(isCallbackStateValid('?code=c&state=', '')).toBe(false);
    expect(isCallbackStateValid('?code=c&state=abc', null)).toBe(false);
  });
});
