import { describe, expect, it } from 'vitest';
import {
  PLATFORM_INVITE_PATH,
  PLATFORM_PREVIEW_PATH,
  resolvePlatformOwnerEntry,
} from './previewAccess';

describe('platform owner preview boundary', () => {
  it('rejects preview routes under the production asset base', () => {
    expect(
      resolvePlatformOwnerEntry({
        development: false,
        hostname: 'localhost',
        pathname: '/app/react/__preview/platform/invite',
        search: '?role=OWNER',
        baseUrl: '/app/react/',
      }),
    ).toBe('unavailable');
  });
  it.each([PLATFORM_PREVIEW_PATH, PLATFORM_INVITE_PATH])(
    'opens %s only in local development',
    (pathname) => {
      for (const hostname of ['localhost', '127.0.0.1', '[::1]', '::1']) {
        expect(
          resolvePlatformOwnerEntry({
            development: true,
            hostname,
            pathname,
            search: '',
          }),
        ).toBe('preview');
        expect(
          resolvePlatformOwnerEntry({
            development: false,
            hostname,
            pathname,
            search: '?role=OWNER&scope=GLOBAL',
          }),
        ).toBe('unavailable');
      }
      for (const hostname of [
        'clubs.example',
        'localhost.example',
        '192.168.1.5',
      ]) {
        expect(
          resolvePlatformOwnerEntry({
            development: true,
            hostname,
            pathname,
            search: '?role=OWNER',
          }),
        ).toBe('unavailable');
      }
    },
  );

  it('uses platform-owner mode only as an entry to a server check', () => {
    expect(
      resolvePlatformOwnerEntry({
        development: false,
        hostname: 'clubs.example',
        pathname: '/app',
        search: '?mode=platform-owner&role=OWNER&userId=42',
      }),
    ).toBe('protected');
  });

  it.each(['owner', 'platform'])(
    'does not derive authority from mode=%s',
    (mode) => {
      expect(
        resolvePlatformOwnerEntry({
          development: true,
          hostname: 'localhost',
          pathname: '/',
          search: `?mode=${mode}&role=OWNER&scope=GLOBAL`,
        }),
      ).toBe('unavailable');
    },
  );

  it('does not use role, scope or a preview query as an entry or authorize unknown paths', () => {
    expect(
      resolvePlatformOwnerEntry({
        development: true,
        hostname: 'localhost',
        pathname: '/',
        search: '?role=OWNER&scope=GLOBAL&preview=platform',
      }),
    ).toBeNull();
    expect(
      resolvePlatformOwnerEntry({
        development: true,
        hostname: 'localhost',
        pathname: `${PLATFORM_PREVIEW_PATH}/roles`,
        search: '',
      }),
    ).toBe('unavailable');
  });

  it.each([
    'guest',
    'support',
    'guest-support',
    'entry',
    'admin',
    'promoter',
    'my-nights',
  ])('preserves the existing %s entry', (mode) => {
    expect(
      resolvePlatformOwnerEntry({
        development: false,
        hostname: 'clubs.example',
        pathname: '/app',
        search: `?mode=${mode}`,
      }),
    ).toBeNull();
  });
});
