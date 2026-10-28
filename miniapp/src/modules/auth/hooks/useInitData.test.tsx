import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useInitData } from './useInitData';
import ClientAccess from '../../client/ClientAccess';

const sdk = vi.hoisted(() => ({
  initData: 'synthetic-a',
  platform: 'web',
  version: '7.10',
}));
vi.mock('../../../app/providers/TelegramProvider', () => ({
  useTelegram: () => sdk,
}));
function Identity() {
  return <output>{useInitData().initData || 'hidden'}</output>;
}

describe('fresh Telegram credentials on resume', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });
  it.each(['pageshow', 'focus'])(
    'same-credential %s hides old client data and rereads revoked access',
    async (event) => {
      sdk.initData = 'synthetic-unchanged';
      vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
      vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
      window.history.replaceState(null, '', '/app?mode=client');
      let finish: (response: Response) => void = () => {};
      const fetch = vi
        .spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce({
          ok: true,
          json: async () => ({
            clients: [
              { id: '20', name: 'Previously accessible client', clubs: [] },
            ],
          }),
        } as Response)
        .mockImplementation(
          () =>
            new Promise((resolve) => {
              finish = resolve;
            }),
        );
      render(<ClientAccess initialToken={null} />);
      await screen.findByText('Previously accessible client');
      act(() => window.dispatchEvent(new Event(event)));
      expect(screen.queryByText('Previously accessible client')).toBeNull();
      expect(fetch).toHaveBeenCalledTimes(2);
      await act(async () =>
        finish({ ok: true, json: async () => ({ clients: [] }) } as Response),
      );
      await screen.findByText('Нет доступных клиентских пространств');
      expect(screen.queryByText('Previously accessible client')).toBeNull();
    },
  );
  it('clears private identity while hidden and reads the new account on foreground and focus', () => {
    sdk.initData = 'synthetic-a';
    let hidden = false;
    vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
    render(<Identity />);
    expect(screen.getByText('synthetic-a')).toBeTruthy();
    act(() => {
      hidden = true;
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(screen.queryByText('synthetic-a')).toBeNull();
    expect(screen.getByText('hidden')).toBeTruthy();
    act(() => {
      sdk.initData = 'synthetic-b';
      hidden = false;
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(screen.getByText('synthetic-b')).toBeTruthy();
    act(() => {
      sdk.initData = 'synthetic-c';
      window.dispatchEvent(new Event('focus'));
    });
    expect(screen.getByText('synthetic-c')).toBeTruthy();
    expect(screen.queryByText('synthetic-b')).toBeNull();
  });
});
