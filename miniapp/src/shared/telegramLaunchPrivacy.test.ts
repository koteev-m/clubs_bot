import { describe, expect, it } from 'vitest';
import { withoutLaunchCredentials } from './telegramLaunchPrivacy';

describe('optional Telegram SDK cache privacy', () => {
  it('retains display settings while dropping signed credentials and invitation tokens', () => {
    const value = JSON.stringify({
      tgWebAppData: 'start_param=ci_secret',
      tgWebAppStartParam: 'ci_secret',
      tgWebAppVersion: '7.0',
    });
    expect(JSON.parse(withoutLaunchCredentials(value))).toEqual({
      tgWebAppVersion: '7.0',
    });
    sessionStorage.setItem('__telegram__initParams', value);
    expect(sessionStorage.getItem('__telegram__initParams')).not.toContain(
      'secret',
    );
    expect(sessionStorage.getItem('__telegram__initParams')).not.toContain(
      'tgWebAppData',
    );
    expect(
      JSON.parse(sessionStorage.getItem('__telegram__initParams') || '{}')
        .tgWebAppVersion,
    ).toBe('7.0');
    sessionStorage.setItem('theme', 'dark');
    expect(sessionStorage.getItem('theme')).toBe('dark');
  });
});
