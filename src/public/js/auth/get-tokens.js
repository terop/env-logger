import { isCallbackStateValid } from '../auth-logic.js';
import { loadDiscovery } from './discovery.js';

export const hasAuthorizationCode = (search) =>
  new URLSearchParams(search).has('code');

export const buildCodeExchangeRequest = ({
  tokenEndpoint,
  clientId,
  applicationUrl,
  code,
  codeVerifier
}) => ({
  url: tokenEndpoint,
  init: {
    method: 'POST',
    credentials: 'omit',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: clientId,
      redirect_uri: `${applicationUrl}login`,
      code,
      code_verifier: codeVerifier
    })
  }
});

export const getTokens = async ({
  search,
  authSettings,
  fetchFn,
  storeTokens,
  storeIdToken,
  loadDiscoveryFn = loadDiscovery,
  sessionStore = globalThis.sessionStorage
}) => {
  if (!hasAuthorizationCode(search)) {
    return;
  }

  // State and verifier are single use: read and clear them before anything else
  const expectedState = sessionStore.getItem('oidcState');
  const codeVerifier = sessionStore.getItem('oidcCodeVerifier');
  sessionStore.removeItem('oidcState');
  sessionStore.removeItem('oidcCodeVerifier');

  try {
    if (!isCallbackStateValid(search, expectedState) || !codeVerifier) {
      throw new Error('OpenID state mismatch or missing PKCE verifier');
    }
    const config = await loadDiscoveryFn(authSettings['oidBaseUrl']);
    const { url, init } = buildCodeExchangeRequest({
      tokenEndpoint: config.token_endpoint,
      clientId: authSettings['clientId'],
      applicationUrl: authSettings['applicationUrl'],
      code: new URLSearchParams(search).get('code'),
      codeVerifier
    });
    const response = await fetchFn(url, init);
    if (!response.ok) {
      throw new Error('Failed to exchange code for tokens');
    }
    const tokens = await response.json();
    storeTokens(tokens);
    await storeIdToken(tokens);
  } catch (error) {
    console.error(`Error exchanging code for tokens: ${error}`);
  }
};
