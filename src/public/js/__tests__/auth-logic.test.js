import { describe, expect, it } from 'vitest';
import { DateTime } from 'luxon';
import {
  accessTokenExpiry,
  authCookiePath,
  authCookiePaths,
  buildLoginUrl,
  isIdTokenPostSuccess,
  isRefreshSessionOver,
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
  it('sets redirect_uri to applicationUrl + login without an extra slash', () => {
    const loginUrl = buildLoginUrl({
      authorizationEndpoint: 'https://idp.example/auth',
      clientId: 'env-logger',
      applicationUrl: 'http://example.com/env-logger/'
    });
    const params = new URL(loginUrl).searchParams;

    expect(params.get('client_id')).toBe('env-logger');
    expect(params.get('redirect_uri')).toBe(
      'http://example.com/env-logger/login'
    );
    expect(params.get('scope')).toBe('openid');
    expect(loginUrl).not.toContain('env-logger//login');
  });
});
