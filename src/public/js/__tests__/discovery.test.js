import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearDiscoveryCache,
  loadDiscovery
} from '../auth/discovery.js';

const ISSUER = 'https://idp.example/realms/app';
const DOC = {
  issuer: ISSUER,
  token_endpoint: `${ISSUER}/protocol/openid-connect/token`
};

afterEach(() => {
  clearDiscoveryCache();
  vi.restoreAllMocks();
});

describe('loadDiscovery', () => {
  it('fetches the well-known document once and caches it', async () => {
    const fetchFn = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => DOC
    });

    const first = await loadDiscovery(ISSUER, fetchFn);
    const second = await loadDiscovery(ISSUER, fetchFn);

    expect(first).toEqual(DOC);
    expect(second).toEqual(DOC);
    expect(fetchFn).toHaveBeenCalledTimes(1);
    expect(fetchFn.mock.calls[0][0]).toBe(
      `${ISSUER}/.well-known/openid-configuration`
    );
  });

  it('shares one in-flight lookup between concurrent callers', async () => {
    const fetchFn = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => DOC
    });

    await Promise.all([
      loadDiscovery(ISSUER, fetchFn),
      loadDiscovery(ISSUER, fetchFn)
    ]);

    expect(fetchFn).toHaveBeenCalledTimes(1);
  });

  it('rejects on a non-OK response and does not cache the failure', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValueOnce({ ok: true, json: async () => DOC });

    await expect(loadDiscovery(ISSUER, fetchFn)).rejects.toThrow(
      'OpenID discovery failed: HTTP 503'
    );

    expect(await loadDiscovery(ISSUER, fetchFn)).toEqual(DOC);
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it('rejects on a network error and does not cache the failure', async () => {
    const fetchFn = vi
      .fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ ok: true, json: async () => DOC });

    await expect(loadDiscovery(ISSUER, fetchFn)).rejects.toThrow('network');
    expect(await loadDiscovery(ISSUER, fetchFn)).toEqual(DOC);
  });

  it('rejects and does not cache a discovery document with another issuer', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ ...DOC, issuer: 'https://attacker.example' })
      })
      .mockResolvedValueOnce({ ok: true, json: async () => DOC });

    await expect(loadDiscovery(ISSUER, fetchFn)).rejects.toThrow(
      `OpenID discovery issuer mismatch: expected ${ISSUER}, got https://attacker.example`
    );
    expect(await loadDiscovery(ISSUER, fetchFn)).toEqual(DOC);
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });
});
