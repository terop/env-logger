// IdPs without an end_session_endpoint (Authelia) keep their own session
// cookie, so a new login would skip the credentials. Ending it needs an
// explicit sign-out request. Authelia parses the body as JSON even though the
// content type is text/plain; that content type avoids a CORS preflight, so
// the request is simple and the response is opaque (success is not verifiable).
const defaultFetch = (...args) => globalThis.fetch(...args);

export const endIdpSession = async (
  sessionLogoutUrl,
  fetchFn = defaultFetch
) => {
  if (!sessionLogoutUrl) {
    return;
  }
  try {
    await fetchFn(sessionLogoutUrl, {
      method: 'POST',
      mode: 'no-cors',
      credentials: 'include',
      keepalive: true,
      headers: { 'Content-Type': 'text/plain' },
      body: '{}'
    });
  } catch (error) {
    console.error(`Error ending IdP session: ${error}`);
  }
};
