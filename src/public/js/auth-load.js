/* global applicationUrl,staticAssetPath,refreshTokensIfNeeded,doLogin */

globalThis.authLoadOk = false;

const fetchFirstOk = async (urls) => {
  let lastStatus = 'unknown';
  for (const url of urls) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        return response;
      }
      lastStatus = response.status;
    } catch {
      lastStatus = 'network-error';
    }
  }
  throw new Error(`All auth config URLs failed, last status: ${lastStatus}`);
};

const loadScript = async (url) => {
  await new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = url;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Failed to load script: ${url}`));
    document.head.appendChild(script);
  });
  globalThis.authScriptUrl = url;
};

const exposeLegacyAuthGlobals = () => {
  // Backward compatibility: older shared auth.js defines top-level functions
  // without attaching them to globalThis. The chart bundle expects globalThis.
  if (typeof globalThis.refreshTokensIfNeeded !== 'function' &&
      typeof refreshTokensIfNeeded === 'function') {
    globalThis.refreshTokensIfNeeded = refreshTokensIfNeeded;
  }
  if (typeof globalThis.doLogin !== 'function' &&
      typeof doLogin === 'function') {
    globalThis.doLogin = doLogin;
  }
};

const loadAuthJs = async (applicationUrl, staticAssetPath) => {
  // Expose before any await so chart.bundle can redirect on 401 without
  // waiting for authSettings (lexical const is not on globalThis)
  globalThis.applicationUrl = applicationUrl;

  const response = await fetchFirstOk([
    'data/auth',
    `${applicationUrl}data/auth`
  ]);
  const params = await response.json();
  globalThis.authSettings = {
    oidBaseUrl: params['oid-base-url'],
    applicationUrl: applicationUrl,
    clientId: params['client-id']
  };

  // Use exactly one auth asset source to avoid mixed local / CDN versions
  const authScriptUrl = staticAssetPath
    ? `${staticAssetPath}/auth.js`
    : `${applicationUrl}/auth.js`;
  await loadScript(authScriptUrl);
  exposeLegacyAuthGlobals();

  globalThis.authLoadOk = true;
};

// Chart bundle awaits this before API calls so refresh helpers exist
const configuredStaticAssetPath =
  typeof staticAssetPath !== 'undefined' ? staticAssetPath : globalThis.staticAssetPath;

globalThis.authReady = loadAuthJs(applicationUrl, configuredStaticAssetPath).catch((error) => {
  console.error(`Error fetching authentication parameters: ${error}`);
  throw error;
});
