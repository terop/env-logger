/* global applicationUrl,staticAssetPath */

const loadAuthJs = async (applicationUrl, staticAssetPath) => {
  try {
    const response = await fetch(`${applicationUrl}data/auth`);

    if (!response.ok) {
      throw new Error('Failed to fetch authentication parameters');
    }
    const params = await response.json();
    globalThis.authSettings = {
      oidBaseUrl: params['oid-base-url'],
      applicationUrl: applicationUrl,
      clientId: params['client-id']
    };

    const script = document.createElement('script');
    script.src = `${staticAssetPath}/js/auth.js`;
    document.head.appendChild(script);
  } catch (error) {
    console.error(`Error fetching authentication parameters: ${error}`);
  }
};

loadAuthJs(applicationUrl, staticAssetPath);
