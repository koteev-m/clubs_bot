import { afterEach, describe, expect, it, vi } from 'vitest';
import { checkPlatformOwnerAccess } from './platformOwner.api';
import { http, setInitData } from '../../shared/api/http';

afterEach(() => {
  vi.restoreAllMocks();
  setInitData('');
});

describe('platform access transport', () => {
  it.each([
    null,
    {},
    { platformOwner: false },
    { platformOwner: 'true' },
    { role: 'OWNER' },
  ])('rejects non-authoritative success %j', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data });
    await expect(
      checkPlatformOwnerAccess(new AbortController().signal),
    ).rejects.toThrow('Invalid platform access response');
  });

  it('uses the existing initData header contract without identity in URL or body', async () => {
    const originalAdapter = http.defaults.adapter;
    let requestSeen = false;
    http.defaults.adapter = async (config) => {
      requestSeen = true;
      expect(config.url).toBe('/api/platform/access');
      expect(config.method).toBe('get');
      expect(config.params).toBeUndefined();
      expect(config.data).toBeUndefined();
      expect(config.headers.get('X-Telegram-Init-Data')).toBe(
        'synthetic-init-data',
      );
      return {
        data: { platformOwner: true },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    };
    try {
      setInitData('synthetic-init-data');
      await expect(
        checkPlatformOwnerAccess(new AbortController().signal),
      ).resolves.toBe(true);
      expect(requestSeen).toBe(true);
    } finally {
      http.defaults.adapter = originalAdapter;
    }
  });
});
