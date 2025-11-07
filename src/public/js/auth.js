/* global luxon */

const doLogout = () => {
  document.cookie = 'X-Authorization-Token=; expires=Thu, 01 Jan 1970 00:00:00 UTC;';

  if (sessionStorage.getItem('refreshToken')) {
    sessionStorage.removeItem('refreshToken');
  }
};

const updateTokens = async () => {
  await fetch(`${globalThis.authSettings['oidBaseUrl']}/protocol/openid-connect/token`,
              {
                method: 'POST',
                headers: {
                  'Content-Type': 'application/x-www-form-urlencoded',
                },
                body: new URLSearchParams({
                  grant_type: 'refresh_token',
                  client_id: globalThis.authSettings['clientId'],
                  refresh_token: sessionStorage.getItem('refreshToken')
                })
              }
             )
    .then((response) => {
      if (!response.ok) {
        throw new Error('Failed to get new access token');
      }
      return response.json();
    })
    .then((tokens) => {
      storeTokens(tokens);

      return tokens;
    })
    .then((tokens) => {
      storeIdToken(tokens, true);
    })
    .catch((error) => {
      console.error(`Error storing new tokens: ${error}`);
    });
};

/* exported refreshTokensIfNeeded */
const refreshTokensIfNeeded = () => {
  if (!sessionStorage.getItem('accessTokenExpiresAt') ||
      luxon.DateTime.fromISO(sessionStorage.getItem('accessTokenExpiresAt')) <= luxon.DateTime.now()) {
    updateTokens();
  }
};

/* exported doLogin */
const doLogin = () => {
  fetch(`${globalThis.authSettings['oidBaseUrl']}/.well-known/openid-configuration`)
    .then((response) => {
      if (!response.ok) {
        throw new Error('Network response was not ok');
      }
      return response.json();
    })
    .then((config) => {
      const loginUrl = `${config.authorization_endpoint}?client_id=${globalThis.authSettings['clientId']}&` +
            `redirect_uri=${globalThis.authSettings['applicationUrl']}login&response_type=code&scope=openid`;

      window.location.href = loginUrl;
    })
    .catch((error) => {
      console.error(`Error fetching OpenID Connect server configuration: ${error}`);
    });
};

const storeTokens = (tokens) => {
  if (tokens.refresh_token) {
    sessionStorage.setItem('refreshToken', tokens.refresh_token);
  }
  sessionStorage.setItem('accessTokenExpiresAt',
                         luxon.DateTime.now().plus({ seconds: tokens.expires_in }).toISO());

  document.cookie = `X-Authorization-Token=${tokens.access_token};`;
};

const storeIdToken = async (tokens, skipReload = false) => {
  try {
    const response = await fetch(`${globalThis.authSettings['applicationUrl']}store-id-token` +
                                 `?id-token=${tokens.id_token}`);
    if (!response.ok) {
      throw new Error('Failed to store ID token');
    }

    const storeResp = await response.text();
    if (storeResp !== 'OK') {
      console.error('ID token validation failed');

      doLogout();
    }
    if (!skipReload) {
      window.location.href = `${globalThis.authSettings['applicationUrl']}`;
    }
  } catch (error) {
    console.error(`ID token store failed: ${error}`);
  }
};

const getTokens = async () => {
  const urlParams = new URLSearchParams(window.location.search);

  if (urlParams.has('code')) {
    fetch(`${globalThis.authSettings['oidBaseUrl']}/protocol/openid-connect/token`,
          {
            method: 'POST',
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

if (document.location.search && document.location.search.includes('logout')) {
  doLogout();
} else {
  getTokens();
}
