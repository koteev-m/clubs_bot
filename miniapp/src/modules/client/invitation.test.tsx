import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PlatformOnboarding from './PlatformOnboarding';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const link =
  'https://t.me/synthetic_platform_bot?startapp=ci_' + 'a'.repeat(43);
const invitation = {
  id: '10',
  clientId: '20',
  clientName: 'MIX Group',
  recipientLabel: 'Иван Петров',
  status: 'issued',
  version: '1',
  expiresAt: '2026-11-04T12:00:00Z',
  availableActions: ['reissue', 'revoke'],
  invitationLink: link,
};
const reply = (body: unknown, status = 200) =>
  Promise.resolve({
    ok: status < 400,
    status,
    json: async () => body,
  } as Response);
function fill() {
  fireEvent.change(screen.getByLabelText('Название клиента или компании'), {
    target: { value: 'MIX Group' },
  });
  fireEvent.change(screen.getByLabelText('Имя получателя'), {
    target: { value: 'Иван Петров' },
  });
}
function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Создать ссылку' }));
}
function go(screenName: string) {
  act(() => {
    window.history.pushState(
      { onboarding: true },
      '',
      '/app?mode=platform-owner&screen=' + screenName,
    );
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
}

describe('owner invitation recovery', () => {
  beforeEach(() => {
    window.history.replaceState(
      { onboarding: true },
      '',
      '/app?mode=platform-owner&screen=invite',
    );
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('shows a valid link, expiry and copy confirmation without persisting private state', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => reply(invitation));
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    render(<PlatformOnboarding credential="signed-owner" />);
    fill();
    submit();
    await screen.findByRole('heading', { name: 'Приглашение создано' });
    expect(screen.getByText(link)).toBeTruthy();
    expect(screen.getByText(/Ссылка действует семь суток — до/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Копировать ссылку' }));
    await screen.findByText('Ссылка скопирована');
    expect(writeText).toHaveBeenCalledWith(link);
    expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({
      clientName: 'MIX Group',
      recipientLabel: 'Иван Петров',
    });
    expect(Object.keys(localStorage)).toHaveLength(0);
    expect(Object.keys(sessionStorage)).toHaveLength(0);
    expect(window.location.href).not.toContain('startapp');
  });

  it.each([
    undefined,
    { writeText: () => Promise.reject(new Error('denied')) },
  ])(
    'handles unavailable or denied clipboard without false copied state',
    async (clipboard) => {
      vi.spyOn(globalThis, 'fetch').mockImplementation(() => reply(invitation));
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: clipboard,
      });
      render(<PlatformOnboarding credential="owner" />);
      fill();
      submit();
      fireEvent.click(
        await screen.findByRole('button', { name: 'Копировать ссылку' }),
      );
      await screen.findByText(/Не удалось скопировать/);
      expect(screen.queryByText('Ссылка скопирована')).toBeNull();
    },
  );

  it.each([
    undefined,
    'https://example.org/secret',
    'https://t.me/fake?startapp=ci_short',
  ])(
    'does not call a missing or malformed link success',
    async (invitationLink) => {
      vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
        reply({ ...invitation, invitationLink }),
      );
      render(<PlatformOnboarding credential="owner" />);
      fill();
      submit();
      await screen.findByRole('heading', { name: 'Ссылка не получена' });
      expect(
        screen.queryByRole('heading', { name: 'Приглашение создано' }),
      ).toBeNull();
      expect(
        screen.queryByRole('button', { name: 'Копировать ссылку' }),
      ).toBeNull();
      expect(
        screen.getByRole('button', { name: 'Открыть приглашение' }),
      ).toBeTruthy();
    },
  );

  it.each([
    [400, 'validation_error'],
    [503, 'invitation_link_unavailable'],
  ])(
    'distinguishes known failure %s from unknown outcome and retains form',
    async (status, code) => {
      vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
        reply({ code }, Number(status)),
      );
      render(<PlatformOnboarding credential="owner" />);
      fill();
      submit();
      await screen.findByText('Не удалось создать приглашение.');
      expect(screen.queryByText(/Ответ сервера не получен/)).toBeNull();
      expect(
        (
          screen.getByRole('button', {
            name: 'Создать ссылку',
          }) as HTMLButtonElement
        ).disabled,
      ).toBe(false);
      expect(
        (screen.getByLabelText('Имя получателя') as HTMLInputElement).value,
      ).toBe('Иван Петров');
    },
  );

  it('keeps original intent through 500, navigation and configuration failure on retry', async () => {
    let attempts = 0;
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((_path, options) => {
        if (options?.method !== 'POST' || String(_path).endsWith('/search'))
          return reply({ items: [], nextCursor: null });
        attempts++;
        return attempts === 1
          ? reply({ code: 'internal_error' }, 500)
          : attempts === 2
            ? reply({ code: 'invitation_link_unavailable' }, 503)
            : reply({ ...invitation, invitationLink: undefined });
      });
    render(<PlatformOnboarding credential="owner" />);
    fill();
    submit();
    await screen.findByText(/Ответ сервера не получен/);
    go('overview');
    go('invite');
    expect(
      (
        screen.getByLabelText(
          'Название клиента или компании',
        ) as HTMLInputElement
      ).value,
    ).toBe('MIX Group');
    fireEvent.click(
      screen.getByRole('button', { name: 'Проверить результат' }),
    );
    await screen.findByText(/Ответ сервера не получен/);
    fireEvent.click(
      screen.getByRole('button', { name: 'Проверить результат' }),
    );
    await screen.findByRole('heading', { name: 'Ссылка не получена' });
    const commands = fetch.mock.calls.filter(
      ([path, options]) =>
        options?.method === 'POST' && String(path).endsWith('/invitations'),
    );
    expect(commands).toHaveLength(3);
    for (const [, options] of commands) {
      expect(options?.body).toBe(commands[0][1]?.body);
      expect(options?.headers).toEqual(commands[0][1]?.headers);
    }
  });

  it('retains draft across screen navigation and drops it synchronously on credential change', () => {
    const view = render(<PlatformOnboarding credential="owner-a" />);
    fill();
    go('overview');
    go('invite');
    expect(
      (screen.getByLabelText('Имя получателя') as HTMLInputElement).value,
    ).toBe('Иван Петров');
    view.rerender(<PlatformOnboarding credential="owner-b" />);
    expect(
      (screen.getByLabelText('Имя получателя') as HTMLInputElement).value,
    ).toBe('');
  });

  it('discards late issue result after account switch', async () => {
    let resolve!: (response: Response) => void;
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const view = render(<PlatformOnboarding credential="owner-a" />);
    fill();
    submit();
    view.rerender(<PlatformOnboarding credential="owner-b" />);
    await act(async () => resolve(await reply(invitation)));
    expect(screen.queryByText(link)).toBeNull();
    expect(
      (screen.getByLabelText('Имя получателя') as HTMLInputElement).value,
    ).toBe('');
  });

  it('forgets the issued link on opening its mutable status and can explicitly invite another client', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((path) =>
      reply(
        String(path).endsWith('/invitations')
          ? { ...invitation, token: 'a'.repeat(43) }
          : { ...invitation, invitationLink: undefined },
      ),
    );
    render(<PlatformOnboarding credential="owner" />);
    fill();
    submit();
    fireEvent.click(
      await screen.findByRole('button', { name: 'Открыть приглашение' }),
    );
    await screen.findByRole('heading', { name: 'Приглашение клиента' });
    go('invite');
    expect(screen.queryByText(link)).toBeNull();
    expect(
      screen.queryByRole('button', { name: 'Копировать ссылку' }),
    ).toBeNull();
    fireEvent.click(
      screen.getByRole('button', { name: 'Пригласить другого клиента' }),
    );
    expect(
      (screen.getByLabelText('Имя получателя') as HTMLInputElement).value,
    ).toBe('');
  });

  it('times out without releasing the command key', async () => {
    vi.useFakeTimers();
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementationOnce(
        (_path, options) =>
          new Promise((_resolve, reject) =>
            options?.signal?.addEventListener('abort', () =>
              reject(new Error('timeout')),
            ),
          ),
      )
      .mockImplementation(() => reply(invitation));
    render(<PlatformOnboarding credential="owner" />);
    fill();
    submit();
    await act(() => vi.advanceTimersByTimeAsync(15000));
    expect(screen.getByText(/Ответ сервера не получен/)).toBeTruthy();
    vi.useRealTimers();
    fireEvent.click(
      screen.getByRole('button', { name: 'Проверить результат' }),
    );
    await waitFor(() => expect(screen.getByText(link)).toBeTruthy());
    expect(fetch.mock.calls[0][1]?.headers).toEqual(
      fetch.mock.calls[1][1]?.headers,
    );
  });
});
