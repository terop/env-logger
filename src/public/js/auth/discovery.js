// OpenID Connect discovery document, shared by login, code exchange and refresh.
// Successful lookups are cached per issuer; failures are not cached so the next
// call can retry.
const discoveryCache = new Map();

const defaultFetch = (...args) => globalThis.fetch(...args);

export const loadDiscovery = (oidBaseUrl, fetchFn = defaultFetch) => {
  if (!discoveryCache.has(oidBaseUrl)) {
    const lookup = (async () => {
      const response = await fetchFn(
        `${oidBaseUrl}/.well-known/openid-configuration`,
        { credentials: 'omit' }
      );
      if (!response.ok) {
        throw new Error(`OpenID discovery failed: HTTP ${response.status}`);
      }
      const document = await response.json();
      if (document.issuer !== oidBaseUrl) {
        throw new Error(
          `OpenID discovery issuer mismatch: expected ${oidBaseUrl}, got ${document.issuer}`
        );
      }
      return document;
    })();
    discoveryCache.set(oidBaseUrl, lookup);
    lookup.catch(() => discoveryCache.delete(oidBaseUrl));
  }
  return discoveryCache.get(oidBaseUrl);
};

export const clearDiscoveryCache = () => discoveryCache.clear();
