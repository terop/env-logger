export const authCookiePath = (baseUrl, locationHref) => {
  try {
    const pathname = new URL(baseUrl, locationHref).pathname;
    return pathname || '/';
  } catch {
    return '/';
  }
};

export const authCookiePaths = (baseUrl, locationHref) => {
  const path = authCookiePath(baseUrl, locationHref);
  const paths = new Set([path, '/']);
  if (path.endsWith('/') && path.length > 1) {
    paths.add(path.slice(0, -1));
  }
  return Array.from(paths);
};

export const accessTokenExpiry = (tokens, now, DateTime) => {
  if (tokens.expires_in != null && tokens.expires_in !== '') {
    return now.plus({ seconds: Number(tokens.expires_in) });
  }
  try {
    const payload = JSON.parse(atob(tokens.access_token.split('.')[1]));
    if (payload.exp) {
      return DateTime.fromSeconds(payload.exp);
    }
  } catch {
    // ignore malformed token
  }
  return now.plus({ seconds: 300 });
};

export const shouldRefreshAccessToken = ({
  refreshToken,
  expiresAt,
  force = false,
  now,
  DateTime
}) => {
  if (!refreshToken) {
    return false;
  }
  const expiry = expiresAt ? DateTime.fromISO(expiresAt) : null;
  if (!force && expiry?.isValid && expiry > now.plus({ seconds: 60 })) {
    return false;
  }
  return true;
};

export const isIdTokenPostSuccess = (response, body) => {
  if (response.ok && body.trim() === 'Not valid') {
    return false;
  }
  return Boolean(response.ok);
};

export const isRefreshSessionOver = (status) =>
  status === 400 || status === 401;

const base64UrlEncode = (bytes) => {
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
};

// Random value for OAuth state and PKCE code_verifier (RFC 7636 allows 43-128 chars)
export const randomBase64Url = (byteLength = 32) => {
  const bytes = new Uint8Array(byteLength);
  globalThis.crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
};

// PKCE S256 code_challenge for a code_verifier (requires a secure context)
export const pkceChallenge = async (codeVerifier) => {
  if (!globalThis.crypto?.subtle) {
    throw new Error(
      'OpenID login requires a secure context (HTTPS or localhost): ' +
        'the Web Crypto API needed for PKCE is unavailable over plain HTTP'
    );
  }
  const digest = await globalThis.crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(codeVerifier)
  );
  return base64UrlEncode(new Uint8Array(digest));
};

export const isCallbackStateValid = (search, expectedState) =>
  Boolean(expectedState) &&
  new URLSearchParams(search).get('state') === expectedState;

export const buildLoginUrl = ({
  authorizationEndpoint,
  clientId,
  applicationUrl,
  scope = 'openid',
  state,
  codeChallenge
}) => {
  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: `${applicationUrl}login`,
    response_type: 'code',
    scope,
    state,
    code_challenge: codeChallenge,
    code_challenge_method: 'S256'
  });
  return `${authorizationEndpoint}?${params.toString()}`;
};
