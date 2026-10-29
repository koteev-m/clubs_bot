import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ClientAccess from './ClientAccess';
import { ClubApplication, ClubProfile } from './onboarding.types';

const auth = vi.hoisted(() => ({
  initData: 'synthetic-client-a',
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
const approved: ClubApplication = {
  id: '30',
  clientId: '20',
  clientName: 'Synthetic Client',
  authorUserId: '90',
  status: 'approved',
  version: '4',
  venueName: 'Synthetic Venue',
  countryCode: 'RU',
  city: 'Synthetic City',
  address: 'Synthetic Street 1',
  unit: 'Test Unit',
  timezone: 'Europe/Moscow',
  comment: '',
  previousApplicationId: null,
  createdClubId: null,
  submittedAt: '2026-10-28T10:00:00Z',
  decisionReason: null,
  history: [],
  availableActions: ['createClub'],
};
const profile: ClubProfile = {
  id: '100',
  clientId: '20',
  clientName: 'Synthetic Client',
  name: 'Synthetic Venue',
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Synthetic City',
  address: 'Synthetic Street 1',
  unit: 'Test Unit',
  version: '1',
  savedAt: '2026-10-28T12:00:00Z',
  permissions: ['client.club.read', 'client.club.profile.write'],
  availableActions: ['update'],
};
const response = (data: unknown, status = 200) =>
  Promise.resolve({
    ok: status < 400,
    status,
    json: async () => data,
  } as Response);
const go = (screenName: string, id: string) =>
  window.history.replaceState(
    {},
    '',
    '/app?mode=client&screen=' + screenName + '&resource=' + id,
  );
const value = (label: string) =>
  (screen.getByLabelText(label) as HTMLInputElement).value;

describe('private club onboarding B', () => {
  beforeEach(() => {
    auth.initData = 'synthetic-client-a';
    auth.refreshEpoch = 0;
    go('application', approved.id);
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('keeps old approved data as history with explicit reuse and no approval consumption action', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => response(approved));
    render(<ClientAccess initialToken={null} />);
    await screen.findByText('Synthetic Street 1');
    expect(screen.getByText('Test Unit')).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Использовать данные для создания' }),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Создать клуб' })).toBeNull();
  });

  it('saves only mutable profile fields and reopens the saved club through its own client', async () => {
    go('club', '100');
    let stored = { ...profile };
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((path, options) => {
        if (options?.method === 'PATCH') {
          const { name, description, timezone } = JSON.parse(
            String(options.body),
          );
          stored = {
            ...stored,
            name,
            description,
            timezone,
            version: '2',
            savedAt: '2026-10-28T13:00:00Z',
          };
        }
        if (String(path).endsWith('/context'))
          return response({
            clients: [
              { id: '20', name: 'Synthetic Client', clubs: [stored] },
              { id: '21', name: 'Independent Client', clubs: [] },
            ],
          });
        if (String(path).endsWith('/onboarding'))
          return response({
            id: '20',
            name: 'Synthetic Client',
            permissions: ['client.application.manage'],
            clubs: [stored],
          });
        if (String(path).endsWith('/search'))
          return response({ items: [], nextCursor: null });
        return response(stored);
      });
    const view = render(<ClientAccess initialToken={null} />);
    fireEvent.change(await screen.findByLabelText('Отображаемое название'), {
      target: { value: 'Saved synthetic club' },
    });
    fireEvent.change(screen.getByLabelText('Описание клуба'), {
      target: { value: 'Synthetic description' },
    });
    fireEvent.change(screen.getByLabelText('Часовой пояс'), {
      target: { value: 'Europe/Paris' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    await screen.findByText('Сохранено');
    const saved = fetch.mock.calls.find(
      ([, options]) => options?.method === 'PATCH',
    )!;
    expect(JSON.parse(String(saved[1]?.body))).toEqual({
      name: 'Saved synthetic club',
      description: 'Synthetic description',
      timezone: 'Europe/Paris',
      expectedVersion: '1',
    });
    expect(
      screen.getByText(/Последнее успешное сохранение/).querySelector('time')
        ?.dateTime,
    ).toBe('2026-10-28T13:00:00Z');
    view.unmount();
    window.history.replaceState({}, '', '/app?mode=client');
    render(<ClientAccess initialToken={null} />);
    fireEvent.click(
      await screen.findByRole('button', { name: /Synthetic Client/ }),
    );
    expect(screen.queryByText('Independent Client')).toBeNull();
    fireEvent.click(
      await screen.findByRole('button', { name: /Saved synthetic club/ }),
    );
    await screen.findByLabelText('Отображаемое название');
    expect(value('Описание клуба')).toBe('Synthetic description');
    expect(value('Часовой пояс')).toBe('Europe/Paris');
  });

  it('validates the name and timezone and leaves readonly location out of mutation payload', async () => {
    go('club', '100');
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => response(profile));
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(await screen.findByLabelText('Отображаемое название'), {
      target: { value: ' ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    await screen.findByText(/Укажите название клуба/);
    fireEvent.change(screen.getByLabelText('Отображаемое название'), {
      target: { value: 'Valid synthetic name' },
    });
    fireEvent.change(screen.getByLabelText('Часовой пояс'), {
      target: { value: 'Unknown/Synthetic' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    await screen.findByText(/Укажите часовой пояс IANA/);
    expect(
      fetch.mock.calls.filter(([, options]) => options?.method === 'PATCH'),
    ).toHaveLength(0);
  });

  it.each(['client.club.read', 'client.club.profile.write', 'GLOBAL'])(
    'a single %s permission never renders a save action',
    async (permission) => {
      go('club', '100');
      vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
        response({
          ...profile,
          permissions: [permission],
          availableActions: ['update'],
        }),
      );
      render(<ClientAccess initialToken={null} />);
      await screen.findByText('Профиль доступен только для просмотра.');
      expect(
        screen.queryByRole('button', { name: 'Сохранить профиль' }),
      ).toBeNull();
      expect(
        (
          screen.getByLabelText('Отображаемое название') as HTMLInputElement
        ).closest('fieldset')?.disabled,
      ).toBe(true);
    },
  );

  it('a failed profile read can be retried and server validation preserves unsaved input', async () => {
    go('club', '100');
    let reads = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation((_path, options) => {
      if (options?.method === 'PATCH')
        return response({ code: 'validation_error' }, 400);
      return ++reads === 1
        ? Promise.reject(new Error('synthetic network failure'))
        : response(profile);
    });
    render(<ClientAccess initialToken={null} />);
    await screen.findByText('Не удалось загрузить данные. Повторите попытку.');
    expect(screen.queryByText('Synthetic Street 1')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
    fireEvent.change(await screen.findByLabelText('Отображаемое название'), {
      target: { value: 'Preserved synthetic input' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    await screen.findByText('Проверьте заполненные поля и повторите действие.');
    expect(value('Отображаемое название')).toBe('Preserved synthetic input');
    expect(screen.queryByText('Сохранено')).toBeNull();
  });

  it('profile conflict reads current server version, preserves input and requires explicit reload before saving', async () => {
    go('club', '100');
    let reads = 0;
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((_path, options) => {
        if (options?.method === 'PATCH')
          return response({ code: 'version_conflict' }, 409);
        return response(
          ++reads === 1
            ? profile
            : { ...profile, name: 'Concurrent name', version: '2' },
        );
      });
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(await screen.findByLabelText('Отображаемое название'), {
      target: { value: 'Unsaved synthetic input' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    await screen.findByText(/Запись уже изменена/);
    await screen.findByRole('heading', { name: 'Concurrent name', level: 1 });
    expect(value('Отображаемое название')).toBe('Unsaved synthetic input');
    expect(
      (
        screen.getByRole('button', {
          name: 'Сохранить профиль',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    fireEvent.click(
      screen.getByRole('button', { name: 'Загрузить актуальный профиль' }),
    );
    await waitFor(() =>
      expect(value('Отображаемое название')).toBe('Concurrent name'),
    );
    expect(
      fetch.mock.calls.filter(([, options]) => options?.method === 'PATCH'),
    ).toHaveLength(1);
  });

  it('unknown profile save keeps exactly the same command across retry', async () => {
    go('club', '100');
    let patches = 0;
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((_path, options) => {
        if (options?.method === 'PATCH')
          return ++patches === 1
            ? Promise.reject(new Error('lost synthetic response'))
            : response({ ...profile, name: 'Saved once', version: '2' });
        return response(profile);
      });
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(await screen.findByLabelText('Отображаемое название'), {
      target: { value: 'Saved once' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    await screen.findByText('Результат операции пока неизвестен');
    fireEvent.click(
      screen.getByRole('button', { name: 'Повторить ту же операцию' }),
    );
    await screen.findByText('Сохранено');
    const attempts = fetch.mock.calls.filter(
      ([, options]) => options?.method === 'PATCH',
    );
    expect(attempts[0][1]?.body).toEqual(attempts[1][1]?.body);
    expect(attempts[0][1]?.headers).toEqual(attempts[1][1]?.headers);
  });

  it('revoked profile access removes private data and unsaved fields', async () => {
    go('club', '100');
    vi.spyOn(globalThis, 'fetch').mockImplementation((_path, options) =>
      options?.method === 'PATCH'
        ? response({ code: 'not_found' }, 404)
        : response(profile),
    );
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(await screen.findByLabelText('Отображаемое название'), {
      target: { value: 'Unsaved private name' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    await screen.findByText(/Доступ утрачен/);
    expect(screen.queryByText('Synthetic Street 1')).toBeNull();
    expect(screen.queryByLabelText('Отображаемое название')).toBeNull();
  });

  it('account switch clears the old profile and rejects a late successful mutation response', async () => {
    go('club', '100');
    let resolveOld: (result: Response) => void = () => {};
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((_path, options) => {
        if (
          (options?.headers as Record<string, string>)[
            'X-Telegram-Init-Data'
          ] === 'synthetic-client-b'
        )
          return response({ code: 'not_found' }, 404);
        if (options?.method === 'PATCH')
          return new Promise((resolve) => {
            resolveOld = resolve;
          });
        return response(profile);
      });
    const view = render(<ClientAccess initialToken={null} />);
    fireEvent.change(await screen.findByLabelText('Отображаемое название'), {
      target: { value: 'Old private account' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    auth.initData = 'synthetic-client-b';
    view.rerender(<ClientAccess initialToken={null} />);
    await screen.findByText(/Доступ утрачен/);
    await act(async () =>
      resolveOld(await response({ ...profile, name: 'Old private account' })),
    );
    expect(screen.queryByText('Old private account')).toBeNull();
    expect(screen.queryByText('Synthetic Street 1')).toBeNull();
    expect(
      fetch.mock.calls.find(([, options]) => options?.method === 'PATCH')?.[1]
        ?.signal?.aborted,
    ).toBe(true);
  });
});
