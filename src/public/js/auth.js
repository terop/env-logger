/* global luxon */

let refreshPromise = null;

const appBaseUrl = () =>
  globalThis.authSettings?.applicationUrl ?? globalThis.applicationUrl ?? '/';

const authCookiePath = () => {
  try {
    const pathname = new URL(appBaseUrl(), window.location.href).pathname;
    return pathname || '/';
  } catch {
    return '/';
  }
};

const authCookiePaths = () => {
  const path = authCookiePath();
  const paths = new Set([path, '/']);
  if (path.endsWith('/') && path.length > 1) {
    paths.add(path.slice(0, -1));
  }
  return Array.from(paths);
};

const clearAuthCookie = () => {
  for (const path of authCookiePaths()) {
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

const accessTokenExpiry = (tokens) => {
  if (tokens.expires_in != null && tokens.expires_in !== '') {
    return luxon.DateTime.now().plus({ seconds: Number(tokens.expires_in) });
  }
  try {
    const payload = JSON.parse(atob(tokens.access_token.split('.')[1]));
    if (payload.exp) {
      return luxon.DateTime.fromSeconds(payload.exp);
    }
  } catch {
    // ignore malformed token
  }
  return luxon.DateTime.now().plus({ seconds: 300 });
};

const storeTokens = (tokens) => {
  if (!tokens?.access_token) {
    throw new Error('Token response missing access_token');
  }
  if (tokens.refresh_token) {
    sessionStorage.setItem('refreshToken', tokens.refresh_token);
  }
  sessionStorage.setItem('accessTokenExpiresAt', accessTokenExpiry(tokens).toISO());

  const tokenCookie = `X-Authorization-Token=${tokens.access_token}; SameSite=Lax`;
  // Write token for root and app-path variants so auth survives deployments
  // behind subpaths and proxy rewrites
  for (const path of authCookiePaths()) {
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
      if (response.ok && body.trim() === 'Not valid') {
        console.error('ID token validation returned Not valid');
        return false;
      }
      if (response.ok) {
        // Some local/proxied setups strip tiny text bodies from 2xx responses.
        // Treat generic 2xx as success unless backend explicitly says Not valid.
        return true;
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
        if (response.status === 400 || response.status === 401) {
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

  const expiresAt = sessionStorage.getItem('accessTokenExpiresAt');
  const expiry = expiresAt ? luxon.DateTime.fromISO(expiresAt) : null;
  // Refresh one minute early so API calls do not race an already-expired cookie
  if (!force && expiry?.isValid &&
      expiry > luxon.DateTime.now().plus({ seconds: 60 })) {
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
    const loginUrl = `${config.authorization_endpoint}?client_id=${globalThis.authSettings['clientId']}&` +
          `redirect_uri=${globalThis.authSettings['applicationUrl']}login&response_type=code&scope=openid`;

    window.location.href = loginUrl;
  } catch (error) {
    console.error(`Error fetching OpenID Connect server configuration: ${error}`);
  }
};

globalThis.doLogin = doLogin;

const getTokens = async () => {
  const urlParams = new URLSearchParams(window.location.search);

  if (urlParams.has('code')) {
    fetch(`${globalThis.authSettings['oidBaseUrl']}/protocol/openid-connect/token`,
          {
            method: 'POST',
            credentials: 'omit',
            headers: {
              'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: new URLSearchParams({
              grant_type: 'authorization_code',
              client_id: globalThis.authSettings['clientId'],
              redirect_uri: `${globalThis.authSettings['applicationUrl']}login`,
              code: urlParams.get('code'),
            }),
          }
         )
      .then((response) => {
        if (!response.ok) {
          throw new Error('Failed to exchange code for tokens');
        }
        return response.json();
      })
      .then((tokens) => {
        storeTokens(tokens);

        return tokens;
      })
      .then((tokens) => {
        storeIdToken(tokens);
      })
      .catch((error) => {
        console.error(`Error exchanging code for tokens: ${error}`);
      });
  }
};

if (window.location.pathname.includes('logout')) {
  doLogout();
} else {
  getTokens();
}
