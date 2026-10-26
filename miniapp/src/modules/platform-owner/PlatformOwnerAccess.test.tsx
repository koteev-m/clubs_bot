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

const auth = vi.hoisted(() => ({ initData: 'synthetic-signed-init-data' }));
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
    window.history.replaceState({}, '', '/app?mode=platform-owner');
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  });
  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.restoreAllMocks();
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
      const get = vi
        .spyOn(http, 'get')
        .mockRejectedValue({
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
