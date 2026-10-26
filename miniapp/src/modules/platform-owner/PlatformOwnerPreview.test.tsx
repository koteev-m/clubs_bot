import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PlatformOwnerPreview from './PlatformOwnerPreview';
import { PLATFORM_INVITE_PATH, PLATFORM_PREVIEW_PATH } from './previewAccess';

const telegram = vi.hoisted(() => ({
  BackButton: {
    show: vi.fn(),
    hide: vi.fn(),
    onClick: vi.fn(),
    offClick: vi.fn(),
  },
}));
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => telegram,
}));

describe('platform owner local screens', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    window.history.replaceState({}, '', PLATFORM_PREVIEW_PATH);
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('shows honest unavailable data, no fabricated clubs/counts and no active unfinished sections', () => {
    render(<PlatformOwnerPreview />);
    expect(
      screen.getByRole('heading', { name: 'Управление платформой' }),
    ).toBeTruthy();
    expect(screen.getByText('Платформа', { exact: true })).toBeTruthy();
    expect(screen.getByText('Клубы в процессе настройки')).toBeTruthy();
    expect(screen.getByText('Ожидающие приглашения')).toBeTruthy();
    expect(screen.getAllByText('Данные не подключены')).toHaveLength(2);
    expect(screen.getByText('Список клубов пока недоступен')).toBeTruthy();
    expect(screen.queryByText(/^\d+$/)).toBeNull();
    for (const name of ['Клубы', 'Доступы', 'Настройки']) {
      expect(
        (
          screen.getByRole('button', {
            name: `${name} Позже`,
          }) as HTMLButtonElement
        ).disabled,
      ).toBe(true);
    }
    expect(
      screen
        .getByRole('button', { name: 'Обзор' })
        .getAttribute('aria-current'),
    ).toBe('page');
  });

  it('pushes once, restores overview via Back, and supports browser Forward plus Telegram Back', async () => {
    render(<PlatformOwnerPreview />);
    const initialLength = window.history.length;
    fireEvent.click(
      screen.getByRole('button', { name: 'Пригласить владельца' }),
    );
    expect(window.location.pathname).toBe(PLATFORM_INVITE_PATH);
    expect(window.history.length).toBe(initialLength + 1);
    expect(screen.getByRole('heading', { name: 'Пригласить владельца' })).toBe(
      document.activeElement,
    );
    expect(screen.getByText('Владелец клуба')).toBeTruthy();
    expect(screen.getByText('Новый клуб')).toBeTruthy();
    expect(screen.getByText('Telegram', { exact: true })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain(
      'Создание приглашений ещё не подключено',
    );
    expect(screen.queryByRole('link')).toBeNull();
    expect(
      screen.queryByRole('button', { name: /создать|отправить/i }),
    ).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Назад к обзору' }));
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Управление платформой' }),
      ).toBeTruthy(),
    );
    expect(window.location.pathname).toBe(PLATFORM_PREVIEW_PATH);
    expect(window.history.length).toBe(initialLength + 1);
    act(() => window.history.forward());
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Пригласить владельца' }),
      ).toBeTruthy(),
    );
    const handler = telegram.BackButton.onClick.mock.calls.at(-1)?.[0];
    act(() => handler());
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Управление платформой' }),
      ).toBeTruthy(),
    );
    expect(telegram.BackButton.offClick).toHaveBeenCalledWith(handler);
    expect(telegram.BackButton.hide).toHaveBeenCalled();
  });

  it('creates a usable back history for a directly opened invitation and cleans up SDK handlers', async () => {
    window.history.replaceState({}, '', PLATFORM_INVITE_PATH);
    const { unmount } = render(<PlatformOwnerPreview />);
    const handler = telegram.BackButton.onClick.mock.calls.at(-1)?.[0];
    expect(window.location.pathname).toBe(PLATFORM_INVITE_PATH);
    fireEvent.click(screen.getByRole('button', { name: 'Назад к обзору' }));
    await waitFor(() =>
      expect(window.location.pathname).toBe(PLATFORM_PREVIEW_PATH),
    );
    expect(
      screen.getByRole('heading', { name: 'Управление платформой' }),
    ).toBeTruthy();
    unmount();
    expect(telegram.BackButton.offClick).toHaveBeenCalledWith(handler);
  });
});
