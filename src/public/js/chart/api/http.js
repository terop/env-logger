import { isAuthRedirectPending } from '../ui/dom.js';

export class HttpError extends Error {
  constructor(status, body) {
    super(`HTTP ${status}`);
    this.name = 'HttpError';
    this.status = status;
    this.body = body;
  }
}

export class AuthLoadError extends Error {
  constructor(message = 'Authentication setup failed') {
    super(message);
    this.name = 'AuthLoadError';
  }
}

const waitForAuth = async () => {
  if (!globalThis.authReady) {
    return;
  }
  try {
    await globalThis.authReady;
  } catch (error) {
    throw new AuthLoadError(error?.message ?? 'Authentication setup failed');
  }
};

const ensureFreshAccessToken = async (force = false) => {
  if (typeof globalThis.refreshTokensIfNeeded !== 'function') {
    return false;
  }
  return globalThis.refreshTokensIfNeeded(force);
};

const restoreServerSession = async () => {
  if (typeof globalThis.restoreIdTokenSession !== 'function') {
    return false;
  }
  return globalThis.restoreIdTokenSession();
};

const fetchJson = async (url) => {
  const response = await fetch(url, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' }
  });
  const body = await response.json().catch(() => null);
  return { response, body };
};

export async function getJson(url, params = {}) {
  if (isAuthRedirectPending()) {
    throw new HttpError(401, null);
  }

  const qs = new URLSearchParams(
    Object.entries(params).filter(([, v]) => v != null && v !== '')
  ).toString();
  const requestUrl = qs ? `${url}?${qs}` : url;

  await waitForAuth();

  // Background tabs throttle the refresh timer; wait for a fresh cookie first
  await ensureFreshAccessToken();

  let { response, body } = await fetchJson(requestUrl);

  if (response.status === 401) {
    // Access token may be stale (throttled timer / clock skew).
    // If we refresh, proactively re-establish server session state before retry
    // so we avoid an extra 401 round-trip.
    const refreshed = await ensureFreshAccessToken(true);
    let restored = false;
    if (refreshed) {
      restored = await restoreServerSession();
      ({ response, body } = await fetchJson(requestUrl));
    }
    // Backend also requires in-memory id-token-valid; if refresh did not restore
    // it (or refresh was not possible), try once and retry
    if (response.status === 401 && !restored && await restoreServerSession()) {
      ({ response, body } = await fetchJson(requestUrl));
    }
  }

  if (!response.ok) {
    throw new HttpError(response.status, body);
  }
  return body;
}
