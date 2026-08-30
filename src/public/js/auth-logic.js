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

export const buildLoginUrl = ({
  authorizationEndpoint,
  clientId,
  applicationUrl
}) =>
  `${authorizationEndpoint}?client_id=${clientId}&` +
  `redirect_uri=${applicationUrl}login&response_type=code&scope=openid`;
