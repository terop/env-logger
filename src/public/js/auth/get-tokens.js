export const hasAuthorizationCode = (search) =>
  new URLSearchParams(search).has('code');

export const buildCodeExchangeRequest = ({
  oidBaseUrl,
  clientId,
  applicationUrl,
  code
}) => ({
  url: `${oidBaseUrl}/protocol/openid-connect/token`,
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
      code
    })
  }
});

export const getTokens = async ({
  search,
  authSettings,
  fetchFn,
  storeTokens,
  storeIdToken
}) => {
  if (!hasAuthorizationCode(search)) {
    return;
  }

  try {
    const { url, init } = buildCodeExchangeRequest({
      oidBaseUrl: authSettings['oidBaseUrl'],
      clientId: authSettings['clientId'],
      applicationUrl: authSettings['applicationUrl'],
      code: new URLSearchParams(search).get('code')
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
