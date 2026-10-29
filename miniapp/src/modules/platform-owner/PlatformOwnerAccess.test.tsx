import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PlatformOwnerAccess from './PlatformOwnerAccess';
import { http } from '../../shared/api/http';

const auth = vi.hoisted(() => ({
  initData: 'synthetic-signed-init-data',
  refreshEpoch: 0,
}));
vi.mock('../auth/hooks/useInitData', () => ({ useInitData: () => auth }));
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({
    BackButton: {
      show: vi.fn(),
      hide: vi.fn(),
      onClick: vi.fn(),
      offClick: vi.fn(),
    },
  }),
}));

describe('platform owner server access gate', () => {
  beforeEach(() => {
    auth.initData = 'synthetic-signed-init-data';
    auth.refreshEpoch = 0;
    window.history.replaceState({}, '', '/app?mode=platform-owner');
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  });
  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('preserves a lost invitation command across focus and hidden-page revalidation without exposing the form before authorization', async () => {
    window.history.replaceState(
      { onboarding: true },
      '',
      '/app?mode=platform-owner&screen=invite',
    );
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValue({ data: { platformOwner: true } });
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockRejectedValue(new Error('lost response'));
    const view = render(<PlatformOwnerAccess />);
    fireEvent.change(
      await screen.findByLabelText('Название клиента или компании'),
      { target: { value: 'MIX Group' } },
    );
    fireEvent.change(screen.getByLabelText('Имя получателя'), {
      target: { value: 'Иван Петров' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Создать ссылку' }));
    await screen.findByText(/Ответ сервера не получен/);
    let allow!: (value: { data: { platformOwner: boolean } }) => void;
    get.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          allow = resolve;
        }),
    );
    auth.refreshEpoch++;
    view.rerender(<PlatformOwnerAccess />);
    expect(
      screen.getByLabelText('Имя получателя').closest('[hidden]'),
    ).not.toBeNull();
    await act(async () => allow({ data: { platformOwner: true } }));
    expect(
      ((await screen.findByLabelText('Имя получателя')) as HTMLInputElement)
        .value,
    ).toBe('Иван Петров');
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    auth.initData = '';
    auth.refreshEpoch++;
    view.rerender(<PlatformOwnerAccess />);
    expect(
      screen.getByLabelText('Имя получателя').closest('[hidden]'),
    ).not.toBeNull();
    hidden.mockReturnValue(false);
    auth.initData = 'synthetic-signed-init-data';
    auth.refreshEpoch++;
    view.rerender(<PlatformOwnerAccess />);
    fireEvent.click(
      await screen.findByRole('button', { name: 'Проверить результат' }),
    );
    await screen.findByText(/Ответ сервера не получен/);
    expect(fetch.mock.calls).toHaveLength(2);
    expect(fetch.mock.calls[1][1]?.headers).toEqual(
      fetch.mock.calls[0][1]?.headers,
    );
    expect(fetch.mock.calls[1][1]?.body).toBe(fetch.mock.calls[0][1]?.body);
  });

  it('clears private invitation draft and pending intent after denied revalidation', async () => {
    window.history.replaceState(
      { onboarding: true },
      '',
      '/app?mode=platform-owner&screen=invite',
    );
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValue({ data: { platformOwner: true } });
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('lost response'));
    const view = render(<PlatformOwnerAccess />);
    fireEvent.change(
      await screen.findByLabelText('Название клиента или компании'),
      { target: { value: 'Private old client' } },
    );
    fireEvent.change(screen.getByLabelText('Имя получателя'), {
      target: { value: 'Private old recipient' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Создать ссылку' }));
    await screen.findByText(/Ответ сервера не получен/);
    get.mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 403 },
    });
    auth.refreshEpoch++;
    view.rerender(<PlatformOwnerAccess />);
    await screen.findByText('Нет доступа к управлению платформой');
    fireEvent.click(screen.getByRole('button', { name: 'Повторить проверку' }));
    expect(
      ((await screen.findByLabelText('Имя получателя')) as HTMLInputElement)
        .value,
    ).toBe('');
    expect(
      screen.queryByRole('button', { name: 'Проверить результат' }),
    ).toBeNull();
  });

  it('keeps controls hidden until the server confirms access', async () => {
    let accept: (value: {
      data: { platformOwner: boolean };
    }) => void = () => {};
    vi.spyOn(http, 'get').mockImplementation(
      () =>
        new Promise((resolve) => {
          accept = resolve;
        }),
    );
    render(<PlatformOwnerAccess />);
    expect(screen.getByLabelText('Проверка доступа к платформе')).toBeTruthy();
    expect(
      screen.queryByRole('button', { name: 'Пригласить владельца' }),
    ).toBeNull();
    await act(async () => accept({ data: { platformOwner: true } }));
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Управление платформой' }),
      ).toBeTruthy(),
    );
    expect(
      screen.getByText('Владелец платформы', { exact: true }),
    ).toBeTruthy();
    expect(screen.queryByText('Локальный просмотр')).toBeNull();
  });

  it.each([401, 403])(
    'honors server %s despite URL, role and persisted client claims',
    async (status) => {
      window.history.replaceState(
        { platformOwner: true },
        '',
        '/app?mode=platform-owner&screen=invite&role=OWNER&userId=42',
      );
      localStorage.setItem('platformOwner', 'true');
      const get = vi.spyOn(http, 'get').mockRejectedValue({
        isAxiosError: true,
        response: { status, data: { secret: 'never-display' } },
      });
      render(<PlatformOwnerAccess />);
      await waitFor(() =>
        expect(
          screen.getByRole('heading', {
            name:
              status === 403
                ? 'Нет доступа к управлению платформой'
                : 'Нужна авторизация в Telegram',
          }),
        ).toBeTruthy(),
      );
      expect(
        screen.queryByRole('button', { name: 'Пригласить владельца' }),
      ).toBeNull();
      expect(screen.queryByText('never-display')).toBeNull();
      expect(get.mock.calls[0][0]).toBe('/api/platform/access');
    },
  );

  it('does not send a request without Telegram credentials', () => {
    auth.initData = '';
    const get = vi.spyOn(http, 'get');
    render(<PlatformOwnerAccess />);
    expect(
      screen.getByRole('heading', { name: 'Нужна авторизация в Telegram' }),
    ).toBeTruthy();
    expect(get).not.toHaveBeenCalled();
  });

  it('fails closed on malformed success and retries only through the server', async () => {
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValueOnce({ data: { role: 'OWNER' } })
      .mockResolvedValueOnce({ data: { platformOwner: true } });
    render(<PlatformOwnerAccess />);
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Не удалось проверить доступ' }),
      ).toBeTruthy(),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Повторить проверку' }));
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Управление платформой' }),
      ).toBeTruthy(),
    );
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('clears previously allowed UI and rechecks after page restoration', async () => {
    vi.spyOn(http, 'get')
      .mockResolvedValueOnce({ data: { platformOwner: true } })
      .mockRejectedValueOnce({ isAxiosError: true, response: { status: 403 } });
    render(<PlatformOwnerAccess />);
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Управление платформой' }),
      ).toBeTruthy(),
    );
    act(() => window.dispatchEvent(new Event('pageshow')));
    expect(
      screen.queryByRole('button', { name: 'Пригласить владельца' }),
    ).toBeNull();
    await waitFor(() =>
      expect(
        screen.getByRole('heading', {
          name: 'Нет доступа к управлению платформой',
        }),
      ).toBeTruthy(),
    );
  });

  it('ignores late success after credentials changed and aborts the old request', async () => {
    let accept: (value: {
      data: { platformOwner: boolean };
    }) => void = () => {};
    const get = vi.spyOn(http, 'get').mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          accept = resolve;
        }),
    );
    const { rerender } = render(<PlatformOwnerAccess />);
    const signal = get.mock.calls[0][1]?.signal;
    auth.initData = '';
    rerender(<PlatformOwnerAccess />);
    expect(signal?.aborted).toBe(true);
    await act(async () => accept({ data: { platformOwner: true } }));
    expect(
      screen.getByRole('heading', { name: 'Нужна авторизация в Telegram' }),
    ).toBeTruthy();
    expect(
      screen.queryByRole('button', { name: 'Пригласить владельца' }),
    ).toBeNull();
  });
});
