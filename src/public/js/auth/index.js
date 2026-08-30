/* global luxon */

import {
  accessTokenExpiry,
  authCookiePaths,
  buildLoginUrl,
  isIdTokenPostSuccess,
  isRefreshSessionOver,
  shouldRefreshAccessToken
} from '../auth-logic.js';
import { getTokens } from './get-tokens.js';

let refreshPromise = null;

const appBaseUrl = () =>
  globalThis.authSettings?.applicationUrl ?? globalThis.applicationUrl ?? '/';

const cookiePaths = () =>
  authCookiePaths(appBaseUrl(), window.location.href);

const clearAuthCookie = () => {
  for (const path of cookiePaths()) {
    document.cookie =
      `X-Authorization-Token=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=${path}`;
  }
};

const doLogout = () => {
  clearAuthCookie();

  sessionStorage.removeItem('refreshToken');
  sessionStorage.removeItem('accessTokenExpiresAt');
  sessionStorage.removeItem('idToken');

  window.location.href = appBaseUrl();
};

const storeTokens = (tokens) => {
  if (!tokens?.access_token) {
    throw new Error('Token response missing access_token');
  }
  if (tokens.refresh_token) {
    sessionStorage.setItem('refreshToken', tokens.refresh_token);
  }
  sessionStorage.setItem(
    'accessTokenExpiresAt',
    accessTokenExpiry(tokens, luxon.DateTime.now(), luxon.DateTime).toISO()
  );

  const tokenCookie = `X-Authorization-Token=${tokens.access_token}; SameSite=Lax`;
  // Write token for root and app-path variants so auth survives deployments
  // behind subpaths and proxy rewrites
  for (const path of cookiePaths()) {
    document.cookie = `${tokenCookie}; path=${path}`;
  }
};

const postIdToken = async (idToken) => {
  const encoded = encodeURIComponent(idToken);
  const postBody = new URLSearchParams({ 'id-token': idToken });
  const attempts = [
    {
      url: `store-id-token?id-token=${encoded}`,
      init: {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: postBody
      }
    },
    {
      url: `${appBaseUrl()}store-id-token?id-token=${encoded}`,
      init: {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: postBody
      }
    },
    {
      url: `store-id-token?id-token=${encoded}`,
      init: { credentials: 'same-origin' }
    },
    {
      url: `${appBaseUrl()}store-id-token?id-token=${encoded}`,
      init: { credentials: 'same-origin' }
    }
  ];

  let lastStatus = 'unknown';
  for (const { url, init } of attempts) {
    try {
      const response = await fetch(url, init);
      const body = await response.text();
      if (isIdTokenPostSuccess(response, body)) {
        // Some local/proxied setups strip tiny text bodies from 2xx responses.
        // Treat generic 2xx as success unless backend explicitly says Not valid.
        return true;
      }
      if (response.ok) {
        console.error('ID token validation returned Not valid');
        return false;
      }
      lastStatus = `${response.status} body='${body.slice(0, 120)}'`;
    } catch (error) {
      lastStatus = `network-error: ${error}`;
    }
  }

  console.error(`ID token post failed for all URLs, last result: ${lastStatus}`);
  return false;
};

const storeIdToken = async (tokens, skipReload = false) => {
  if (tokens.id_token) {
    sessionStorage.setItem('idToken', tokens.id_token);
  }
  try {
    if (!tokens.id_token) {
      throw new Error('Token response missing id_token');
    }
    if (!(await postIdToken(tokens.id_token))) {
      console.error('ID token validation failed');
      doLogout();
      return false;
    }
    if (!skipReload) {
      window.location.href = appBaseUrl();
    }
    return true;
  } catch (error) {
    console.error(`ID token store failed: ${error}`);
    return false;
  }
};

// Re-establish server-side id-token-valid after restart without a full login
const restoreIdTokenSession = async () => {
  const idToken = sessionStorage.getItem('idToken');
  if (!idToken) {
    return false;
  }
  try {
    return await postIdToken(idToken);
  } catch (error) {
    console.error(`ID token restore failed: ${error}`);
    return false;
  }
};

// Refresh only updates the access-token cookie. Re-posting the ID token is
// harmful on every refresh: the backend clears id-token-valid before validating,
// so a missing or rejected id_token on refresh locks the session out.
const updateTokens = () => {
  const refreshToken = sessionStorage.getItem('refreshToken');
  if (!refreshToken) {
    return Promise.resolve(false);
  }
  // Coalesce concurrent refresh attempts onto one in-flight request
  if (refreshPromise) {
    return refreshPromise;
  }

  refreshPromise = (async () => {
    try {
      const response = await fetch(
        `${globalThis.authSettings['oidBaseUrl']}/protocol/openid-connect/token`,
        {
          method: 'POST',
          credentials: 'omit',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
          },
          body: new URLSearchParams({
            grant_type: 'refresh_token',
            client_id: globalThis.authSettings['clientId'],
            refresh_token: refreshToken
          })
        }
      );
      if (!response.ok) {
        const responseBody = await response.text().catch(() => '');
        console.error(
          `Token refresh failed: HTTP ${response.status} ${responseBody}`
        );
        // Expired or revoked refresh token — session is over
        if (isRefreshSessionOver(response.status)) {
          doLogout();
        }
        return false;
      }
      storeTokens(await response.json());
      return true;
    } catch (error) {
      // Transient network errors must not wipe a still-recoverable session
      console.error(`Error refreshing tokens: ${error}`);
      return false;
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
};

const refreshTokensIfNeeded = (force = false) => {
  if (!sessionStorage.getItem('refreshToken')) {
    return Promise.resolve(false);
  }

  if (!shouldRefreshAccessToken({
    refreshToken: sessionStorage.getItem('refreshToken'),
    expiresAt: sessionStorage.getItem('accessTokenExpiresAt'),
    force,
    now: luxon.DateTime.now(),
    DateTime: luxon.DateTime
  })) {
    return Promise.resolve(true);
  }
  return updateTokens();
};

// Chart bundle is an IIFE and can only reach auth helpers via globalThis
globalThis.refreshTokensIfNeeded = refreshTokensIfNeeded;
globalThis.restoreIdTokenSession = restoreIdTokenSession;

// Background tabs throttle timers; refresh as soon as the user returns
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') {
    refreshTokensIfNeeded();
  }
});

const doLogin = async () => {
  try {
    if (!globalThis.authSettings && globalThis.authReady) {
      await globalThis.authReady;
    }
    if (!globalThis.authSettings) {
      throw new Error('Authentication settings are not loaded');
    }

    const response = await fetch(
      `${globalThis.authSettings['oidBaseUrl']}/.well-known/openid-configuration`,
      { credentials: 'omit' }
    );
    if (!response.ok) {
      throw new Error('Network response was not ok');
    }
    const config = await response.json();
    const loginUrl = buildLoginUrl({
      authorizationEndpoint: config.authorization_endpoint,
      clientId: globalThis.authSettings['clientId'],
      applicationUrl: globalThis.authSettings['applicationUrl']
    });

    window.location.href = loginUrl;
  } catch (error) {
    console.error(`Error fetching OpenID Connect server configuration: ${error}`);
  }
};

globalThis.doLogin = doLogin;

if (window.location.pathname.includes('logout')) {
  doLogout();
} else {
  getTokens({
    search: window.location.search,
    authSettings: globalThis.authSettings,
    fetchFn: fetch,
    storeTokens,
    storeIdToken
  });
}
