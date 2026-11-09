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
import ClientAccess from './ClientAccess';

const telegram = vi.hoisted(() => ({
  viewportHeight: 844,
  onEvent: vi.fn(),
  offEvent: vi.fn(),
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
vi.mock('../auth/hooks/useInitData', () => ({
  useInitData: () => ({ initData: 'synthetic-client' }),
}));
const reply = (data: unknown, status = 200) =>
  Promise.resolve({
    ok: status < 400,
    status,
    json: async () => data,
  } as Response);
const page = (items: unknown[]) => ({ items, nextCursor: null });
const detail = {
  id: '20',
  name: 'Организация A',
  active: true,
  archived: false,
  version: '3',
  activeMembers: 0,
  archiveBlockers: [],
  availableActions: ['archive'],
  allowedClubs: 1,
  createdClubs: 0,
  availableClubs: 1,
  limitVersion: '1',
  members: [],
  invitations: [],
  clubs: [],
};
const profile = {
  id: '100',
  name: 'Сохранённый клуб',
  clientId: '20',
  clientName: 'Организация A',
  permissions: ['client.club.read'],
  availableActions: [],
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Улица 1',
  unit: '',
  version: '1',
  savedAt: '2026-11-02T12:00:00Z',
};

beforeEach(() => {
  history.replaceState({}, '', '/app?mode=platform-owner');
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('owner UX and client navigation', () => {
  it('keeps overview, clubs and access distinct, with no placeholder settings', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((input) => {
        const path = String(input);
        if (path.endsWith('/summary'))
          return reply({
            clients: 2,
            archivedClients: 1,
            clubs: 1,
            activeMembers: 0,
            openInvitations: 0,
          });
        if (path.endsWith('/clubs/search'))
          return reply(
            page([
              {
                id: '100',
                name: 'Клуб A',
                clientId: '20',
                clientName: 'Организация A',
                published: false,
                clientActive: true,
                clientArchived: false,
              },
            ]),
          );
        return reply(page([detail]));
      });
    render(<PlatformOnboarding credential="signed-owner" />);
    await screen.findByText('Организации вне архива');
    const entries = history.length;
    fireEvent.click(screen.getByRole('button', { name: 'Обзор' }));
    expect(history.length).toBe(entries);
    expect(
      screen.queryByText('История заявок', { selector: 'h2' }),
    ).toBeNull();
    expect(screen.queryByRole('button', { name: /Настройки/ })).toBeNull();
    expect(
      fetch.mock.calls.some(([path]) =>
        String(path).endsWith('/applications/search'),
      ),
    ).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Клубы' }));
    await screen.findByRole('heading', { name: 'Клуб A' });
    expect(location.search).toContain('screen=clubs');
    expect(screen.getByText('Не опубликован')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Доступы' }));
    await screen.findByLabelText('Список организаций');
    expect(location.search).toContain('screen=clients');
    expect(screen.queryByRole('heading', { name: 'Клуб A' })).toBeNull();
    expect(
      screen
        .getByRole('button', { name: 'Доступы' })
        .getAttribute('aria-current'),
    ).toBe('page');
  });

  it('defaults to organizations outside archive and resets pagination when filter changes', async () => {
    history.replaceState({}, '', '/app?mode=platform-owner&screen=clients');
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((_path, options) => {
        const body = JSON.parse(String(options?.body));
        return reply(
          page([
            {
              ...detail,
              archived: body.status === 'archived',
              name:
                body.status === 'archived'
                  ? 'Архивная компания'
                  : detail.name,
            },
          ]),
        );
      });
    render(<PlatformOnboarding credential="signed-owner" />);
    await screen.findByText('Организация активна');
    expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({
      limit: 50,
      status: 'active',
    });
    fireEvent.change(screen.getByLabelText('Список организаций'), {
      target: { value: 'archived' },
    });
    await screen.findByText('Архивная компания');
    expect(screen.getByText('Организация в архиве')).toBeTruthy();
    expect(JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body))).toEqual({
      limit: 50,
      status: 'archived',
    });
  });

  it('explains organization versus revoked user access and labels every saved club permission', async () => {
    history.replaceState(
      {},
      '',
      '/app?mode=platform-owner&screen=client&resource=20',
    );
    vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      reply({
        ...detail,
        archiveBlockers: ['clubs'],
        availableActions: [],
        members: [
          {
            userId: '90',
            telegramUserId: '123',
            displayName: 'Участник',
            username: null,
            active: false,
            version: '2',
            permissions: [],
            restorablePermissions: [],
            clubGrants: [
              'client.club.read',
              'client.club.profile.write',
              'client.club.layout.write',
            ].map((permission) => ({
              clubId: '100',
              clubName: 'Клуб A',
              permission,
              active: false,
            })),
          },
        ],
      }),
    );
    render(<PlatformOnboarding credential="signed-owner" />);
    await screen.findByText('Доступ отозван');
    expect(screen.getByText('Организация активна')).toBeTruthy();
    for (const name of [
      'чтение клуба',
      'редактирование профиля',
      'редактирование залов и столов',
    ])
      expect(screen.getByLabelText('Клуб A: ' + name)).toBeTruthy();
    expect(
      screen.queryByRole('button', { name: 'Архивировать организацию' }),
    ).toBeNull();
    expect(
      screen.getByText(/Пока нельзя архивировать: есть связанные заведения/),
    ).toBeTruthy();
  });

  it('retains the archive command across Back/Forward and retries its original version and key', async () => {
    history.replaceState(
      {},
      '',
      '/app?mode=platform-owner&screen=client&resource=20',
    );
    let attempts = 0;
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((path) => {
      if (String(path).endsWith('/archive'))
        return ++attempts === 1
          ? Promise.reject(new Error('lost'))
          : reply({
              ...detail,
              archived: true,
              version: '4',
              availableActions: ['restore'],
            });
      if (String(path).endsWith('/summary'))
        return reply({
          clients: 1,
          clubs: 0,
          activeMembers: 0,
          openInvitations: 0,
        });
      return reply(
        attempts > 1
          ? {
              ...detail,
              archived: true,
              version: '4',
              availableActions: ['restore'],
            }
          : detail,
      );
    });
    render(<PlatformOnboarding credential="signed-owner" />);
    fireEvent.click(
      await screen.findByRole('button', { name: 'Архивировать организацию' }),
    );
    await screen.findByText('Результат операции пока неизвестен');
    act(() => {
      history.replaceState({}, '', '/app?mode=platform-owner');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    act(() => {
      history.replaceState(
        {},
        '',
        '/app?mode=platform-owner&screen=client&resource=20',
      );
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    fireEvent.click(
      await screen.findByRole('button', { name: 'Повторить ту же операцию' }),
    );
    await screen.findByRole('button', { name: 'Восстановить организацию' });
    const commands = fetch.mock.calls.filter(([path]) =>
      String(path).endsWith('/archive'),
    );
    expect(commands).toHaveLength(2);
    expect(commands[0][1]?.body).toBe(commands[1][1]?.body);
    expect(commands[0][1]?.headers).toEqual(commands[1][1]?.headers);
    expect(JSON.parse(String(commands[0][1]?.body))).toEqual({
      expectedVersion: '3',
    });
  });

  it('opens the only club directly, retains an explicit organization path and responds to viewport events', async () => {
    history.replaceState({}, '', '/app?mode=client');
    vi.spyOn(globalThis, 'fetch').mockImplementation((path) =>
      reply(
        String(path).endsWith('/search')
          ? page([])
          : String(path).endsWith('/context')
            ? { clients: [{ ...detail, clubs: [profile] }] }
            : String(path).endsWith('/profile')
              ? profile
              : {
                  ...detail,
                  permissions: ['client.application.manage'],
                  clubs: [profile],
                },
      ),
    );
    const view = render(<ClientAccess initialToken={null} />);
    await screen.findByRole('heading', { name: 'Сохранённый клуб' });
    expect(location.search).toContain('screen=club&resource=100');
    expect(screen.getByText('Статус: проверка недоступна')).toBeTruthy();
    expect(
      screen.getByText(
        'Подтверждённые отчёты о работе клуба в этом кабинете пока не подключены.',
      ),
    ).toBeTruthy();
    const event = telegram.onEvent.mock.calls.findLast(
      ([name]) => name === 'viewportChanged',
    )!;
    telegram.viewportHeight = 620;
    act(() => (event[1] as () => void)());
    expect(
      (
        view.container.querySelector('.platform-owner') as HTMLElement
      ).style.getPropertyValue('--po-viewport-height'),
    ).toBe('620px');
    telegram.viewportHeight = 844;
    fireEvent.click(
      screen.getByRole('button', { name: 'Организация и другие заведения' }),
    );
    await screen.findByText('Можно добавить: 1');
    expect(
      screen.getByRole('button', { name: 'Создать заведение' }),
    ).toBeTruthy();
  });

  it('keeps selection when there are several clients or several clubs', async () => {
    history.replaceState({}, '', '/app?mode=client');
    vi.spyOn(globalThis, 'fetch').mockImplementation((path) =>
      reply(
        String(path).endsWith('/context')
          ? {
              clients: [
                detail,
                { ...detail, id: '21', name: 'Организация B' },
              ],
            }
          : {
              ...detail,
              permissions: [],
              clubs: [
                profile,
                { ...profile, id: '101', name: 'Второй клуб' },
              ],
            },
      ),
    );
    render(<ClientAccess initialToken={null} />);
    fireEvent.click(
      await screen.findByRole('button', { name: /Организация A/ }),
    );
    await screen.findByRole('button', { name: /Второй клуб/ });
    await waitFor(() =>
      expect(location.search).toContain('screen=client-space'),
    );
    expect(
      screen.queryByRole('heading', { name: 'Сохранённый клуб' }),
    ).toBeNull();
  });
});
