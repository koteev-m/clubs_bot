import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  errorMessage,
  OnboardingError,
  onboardingRequest,
} from './onboarding.api';
import { readClientEntry, scrubClientLaunchUrl } from './clientEntry';

describe('onboarding transport and launch privacy', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });
  it('keeps the request deadline active while a mutation response body is stalled', async () => {
    vi.useFakeTimers();
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      async (_path, options) =>
        ({
          ok: true,
          json: () =>
            new Promise((_resolve, reject) => {
              options?.signal?.addEventListener('abort', () =>
                reject(new Error('aborted')),
              );
            }),
        }) as Response,
    );
    const result = onboardingRequest('synthetic', '/api/client/applications', {
      body: {},
      key: 'synthetic-command',
    }).catch((error) => error);
    await vi.advanceTimersByTimeAsync(15000);
    expect(await result).toMatchObject({ status: 0, code: 'unknown_outcome' });
  });
  it('binds credentials to one request without query, caching or raw token storage', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      json: async () => ({ id: '1' }),
    } as Response);
    await onboardingRequest(
      'signed-synthetic-identity',
      '/api/client/invitations/resolve',
      { body: { token: 'a'.repeat(43) } },
    );
    expect(fetch.mock.calls[0][0]).toBe('/api/client/invitations/resolve');
    expect(fetch.mock.calls[0][1]).toMatchObject({
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'X-Telegram-Init-Data': 'signed-synthetic-identity' },
    });
    expect(Object.keys(localStorage)).toHaveLength(0);
  });
  it('does not expose server bodies in error messages', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({
        code: 'forbidden',
        message: 'private note or credential',
      }),
    } as Response);
    let caught: unknown;
    try {
      await onboardingRequest('signed', '/api/client/context');
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(OnboardingError);
    expect(errorMessage(caught)).not.toContain('private note');
  });
  it('recognizes exact launch prefix and removes raw token and initData from address history', () => {
    const token = 'a'.repeat(43);
    const data = new URLSearchParams({
      start_param: 'ci_' + token,
      user: '{"id":880062001}',
      hash: 'synthetic',
    }).toString();
    const hash = '#tgWebAppData=' + encodeURIComponent(data);
    expect(readClientEntry('', hash)).toEqual({ client: true, token });
    expect(readClientEntry('?startapp=ci_short', '')).toEqual({
      client: false,
      token: null,
    });
    window.history.replaceState(null, '', '/app?startapp=ci_' + token + hash);
    scrubClientLaunchUrl();
    expect(window.location.search).toBe('?mode=client');
    expect(window.location.hash).toBe('');
  });
});
