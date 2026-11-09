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
    vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      response(approved),
    );
    render(<ClientAccess initialToken={null} />);
    await screen.findByText('Synthetic Street 1');
    expect(screen.getByText('Test Unit')).toBeTruthy();
    expect(
      screen.getByRole('button', {
        name: 'Использовать данные для создания',
      }),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Создать клуб' })).toBeNull();
  });

  it('saves only mutable profile fields and reopens the saved club through its own client', async () => {
    go('profile', '100');
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
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(
      await screen.findByLabelText('Отображаемое название заведения'),
      {
        target: { value: 'Saved synthetic club' },
      },
    );
    fireEvent.change(screen.getByLabelText('Описание клуба'), {
      target: { value: 'Synthetic description' },
    });
    fireEvent.change(screen.getByLabelText('Часовой пояс IANA'), {
      target: { value: 'Europe/Paris' },
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
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
    fireEvent.click(screen.getByRole('button', { name: 'Настройки клуба' }));
    fireEvent.click(
      await screen.findByRole('button', { name: 'Назад к клубу' }),
    );
    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Организация и другие заведения',
      }),
    );
    await screen.findByRole('heading', {
      name: 'Synthetic Client',
      level: 1,
    });
    expect(screen.queryByText('Independent Client')).toBeNull();
    expect(screen.queryByText('Synthetic Venue')).toBeNull();
    fireEvent.click(
      await screen.findByRole('button', { name: /Saved synthetic club/ }),
    );
    fireEvent.click(
      await screen.findByRole('button', { name: 'Перейти к настройкам' }),
    );
    fireEvent.click(
      await screen.findByRole('button', { name: /^Профиль Название/ }),
    );
    await screen.findByLabelText('Отображаемое название заведения');
    expect(value('Описание клуба')).toBe('Synthetic description');
    expect(value('Часовой пояс IANA')).toBe('Europe/Paris');
    expect(
      fetch.mock.calls.some(([path]) =>
        String(path).endsWith('/clients/21/onboarding'),
      ),
    ).toBe(false);
  });

  it.each([
    ['Moscow', 'Moscow', '', ''],
    ['Москва', 'Москва', '   ', ''],
    ['Paris', 'Île-de-France', 'Зал 2', 'Île-de-France'],
    ['Paris', 'Texas', '', 'Texas'],
  ])(
    'renders source location %s / %s without losing distinguishing details',
    async (city, region, unit, expectedRegion) => {
      go('profile', '100');
      vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
        response({ ...profile, city, region, unit }),
      );
      render(<ClientAccess initialToken={null} />);
      await screen.findByText(city, { selector: 'dd' });
      expect(screen.queryByText('Регион', { selector: 'dt' }) !== null).toBe(
        Boolean(expectedRegion),
      );
      if (expectedRegion)
        expect(
          screen.getByText(expectedRegion, { selector: 'dd' }),
        ).toBeTruthy();
      expect(
        screen.queryByText('Помещение', { selector: 'dt' }) !== null,
      ).toBe(Boolean(unit.trim()));
      if (unit.trim())
        expect(screen.getByText(unit, { selector: 'dd' })).toBeTruthy();
      expect(screen.queryByText('Отдельный бот клуба')).toBeNull();
      expect(screen.queryByRole('button', { name: /Подключ/ })).toBeNull();
      expect(screen.getByText('Организация:', { exact: false })).toBeTruthy();
    },
  );

  it.each(['Asia/Kathmandu', 'America/New_York', 'Future/Server_Zone'])(
    'preserves the saved %s zone during ordinary edits, even with older browser zone data',
    async (timezone) => {
      go('profile', '100');
      const fetch = vi
        .spyOn(globalThis, 'fetch')
        .mockImplementation((_path, options) =>
          response({
            ...profile,
            timezone,
            ...(options?.method === 'PATCH'
              ? { name: 'Новое название', version: '2' }
              : {}),
          }),
        );
      render(<ClientAccess initialToken={null} />);
      fireEvent.change(
        await screen.findByLabelText('Отображаемое название заведения'),
        { target: { value: 'Новое название' } },
      );
      expect(
        screen.getByLabelText('Часовой пояс IANA').closest('details')?.open,
      ).toBe(false);
      fireEvent.click(
        screen.getByRole('button', { name: 'Сохранить профиль' }),
      );
      await screen.findByText('Сохранено');
      const patch = fetch.mock.calls.find(
        ([, options]) => options?.method === 'PATCH',
      );
      expect(JSON.parse(String(patch?.[1]?.body))).toEqual({
        name: 'Новое название',
        description: '',
        timezone,
        expectedVersion: '1',
      });
      expect(value('Часовой пояс IANA')).toBe(timezone);
    },
  );

  it('validates the name and timezone and leaves readonly location out of mutation payload', async () => {
    go('profile', '100');
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => response(profile));
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(
      await screen.findByLabelText('Отображаемое название заведения'),
      {
        target: { value: ' ' },
      },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
    await screen.findByText(/Укажите название клуба/);
    fireEvent.change(
      screen.getByLabelText('Отображаемое название заведения'),
      {
        target: { value: 'Valid synthetic name' },
      },
    );
    fireEvent.change(screen.getByLabelText('Часовой пояс IANA'), {
      target: { value: 'Unknown/Synthetic' },
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
    await screen.findByText(/Укажите часовой пояс IANA/);
    expect(
      fetch.mock.calls.filter(([, options]) => options?.method === 'PATCH'),
    ).toHaveLength(0);
  });

  it.each(['client.club.read', 'client.club.profile.write', 'GLOBAL'])(
    'a single %s permission never renders a save action',
    async (permission) => {
      go('profile', '100');
      vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
        response({
          ...profile,
          permissions: [permission],
          availableActions: ['update'],
        }),
      );
      render(<ClientAccess initialToken={null} />);
      if (permission !== 'client.club.read') {
        await screen.findByText(/Доступ утрачен/);
        expect(
          screen.queryByLabelText('Отображаемое название заведения'),
        ).toBeNull();
        return;
      }
      await screen.findByText('Профиль доступен только для просмотра.');
      expect(
        screen.queryByRole('button', { name: 'Сохранить профиль' }),
      ).toBeNull();
      expect(
        (
          screen.getByLabelText(
            'Отображаемое название заведения',
          ) as HTMLInputElement
        ).closest('fieldset')?.disabled,
      ).toBe(true);
    },
  );

  it('a failed profile read can be retried and server validation preserves unsaved input', async () => {
    go('profile', '100');
    let reads = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation((_path, options) => {
      if (options?.method === 'PATCH')
        return response({ code: 'validation_error' }, 400);
      return ++reads === 1
        ? Promise.reject(new Error('synthetic network failure'))
        : response(profile);
    });
    render(<ClientAccess initialToken={null} />);
    await screen.findByText(
      'Не удалось загрузить данные. Повторите попытку.',
    );
    expect(screen.queryByText('Synthetic Street 1')).toBeNull();
    fireEvent.click(
      screen.getByRole('button', { name: 'Повторить загрузку' }),
    );
    fireEvent.change(
      await screen.findByLabelText('Отображаемое название заведения'),
      {
        target: { value: 'Preserved synthetic input' },
      },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
    await screen.findByText(
      'Проверьте заполненные поля и повторите действие.',
    );
    expect(value('Отображаемое название заведения')).toBe(
      'Preserved synthetic input',
    );
    expect(screen.queryByText('Сохранено')).toBeNull();
  });

  it('profile conflict reads current server version, preserves input and requires explicit reload before saving', async () => {
    go('profile', '100');
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
    fireEvent.change(
      await screen.findByLabelText('Отображаемое название заведения'),
      {
        target: { value: 'Unsaved synthetic input' },
      },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
    await screen.findByText(/Запись уже изменена/);
    await screen.findByRole('heading', { name: 'Concurrent name', level: 1 });
    expect(value('Отображаемое название заведения')).toBe(
      'Unsaved synthetic input',
    );
    expect(
      (
        screen.getByRole('button', {
          name: 'Сохранить профиль',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    expect(
      screen.queryByRole('button', {
        name: 'Перенести мои правки на актуальный профиль',
      }),
    ).toBeNull();
    fireEvent.click(
      screen.getByRole('button', { name: 'Загрузить актуальный профиль' }),
    );
    await waitFor(() =>
      expect(value('Отображаемое название заведения')).toBe(
        'Concurrent name',
      ),
    );
    expect(
      fetch.mock.calls.filter(([, options]) => options?.method === 'PATCH'),
    ).toHaveLength(1);
  });

  it('does not send a stale name under a newer version after a delayed foreground read', async () => {
    go('profile', '100');
    let resolveForeground!: (result: Response) => void;
    const foreground = new Promise<Response>((resolve) => {
      resolveForeground = resolve;
    });
    const changed = { ...profile, name: 'Concurrent name', version: '2' };
    let waitForForeground = false;
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((path, options) => {
        if (options?.method === 'PATCH') return response(changed);
        if (!String(path).endsWith('/profile'))
          return response({ code: 'not_found' }, 404);
        return waitForForeground ? foreground : response(profile);
      });
    render(<ClientAccess initialToken={null} />);
    const description = await screen.findByLabelText('Описание клуба');
    waitForForeground = true;
    fireEvent.focus(window);
    fireEvent.change(description, { target: { value: 'My description' } });
    await act(async () => resolveForeground(await response(changed)));
    await screen.findByRole('heading', { name: changed.name, level: 1 });
    expect(value('Описание клуба')).toBe('My description');
    const save = screen.getByRole<HTMLButtonElement>('button', {
      name: 'Сохранить профиль',
    });
    expect(save.disabled).toBe(true);
    fireEvent.click(save);
    expect(
      fetch.mock.calls.some(([, options]) => options?.method === 'PATCH'),
    ).toBe(false);
    fireEvent.click(
      screen.getByRole('button', {
        name: 'Перенести мои правки на актуальный профиль',
      }),
    );
    expect(value('Отображаемое название заведения')).toBe(changed.name);
    expect(value('Описание клуба')).toBe('My description');
    fireEvent.click(save);
    const patch = fetch.mock.calls.find(
      ([, options]) => options?.method === 'PATCH',
    );
    expect(JSON.parse(String(patch?.[1]?.body))).toMatchObject({
      expectedVersion: '2',
      name: changed.name,
      description: 'My description',
    });
  });

  it('unknown profile save keeps exactly the same command across retry', async () => {
    go('profile', '100');
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
    fireEvent.change(
      await screen.findByLabelText('Отображаемое название заведения'),
      {
        target: { value: 'Saved once' },
      },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
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
    go('profile', '100');
    vi.spyOn(globalThis, 'fetch').mockImplementation((_path, options) =>
      options?.method === 'PATCH'
        ? response({ code: 'not_found' }, 404)
        : response(profile),
    );
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(
      await screen.findByLabelText('Отображаемое название заведения'),
      {
        target: { value: 'Unsaved private name' },
      },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
    await screen.findByText(/Доступ утрачен/);
    expect(screen.queryByText('Synthetic Street 1')).toBeNull();
    expect(
      screen.queryByLabelText('Отображаемое название заведения'),
    ).toBeNull();
  });

  it('account switch clears the old profile and rejects a late successful mutation response', async () => {
    go('profile', '100');
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
    fireEvent.change(
      await screen.findByLabelText('Отображаемое название заведения'),
      {
        target: { value: 'Old private account' },
      },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
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

it('final review regression: a delayed save must never restore the club after a confirmed GET revoke', async () => {
  go('profile', profile.id);
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  let revoked = false;
  let lateRecheck = false;
  let resolveWrite!: (result: Response) => void;
  const write = new Promise<Response>((resolve) => {
    resolveWrite = resolve;
  });
  vi.spyOn(globalThis, 'fetch').mockImplementation((path, options) => {
    if (options?.method === 'PATCH') return write;
    if (!String(path).endsWith('/profile')) return response({}, 404);
    return lateRecheck
      ? response({}, 503)
      : revoked
        ? response({ code: 'not_found' }, 404)
        : response(profile);
  });
  try {
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(await screen.findByLabelText('Описание клуба'), {
      target: { value: 'Local edit' },
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
    revoked = true;
    fireEvent.focus(window);
    await screen.findByText(/Доступ утрачен/);
    expect(screen.queryByText('Synthetic Street 1')).toBeNull();
    lateRecheck = true;
    await act(async () =>
      resolveWrite(
        await response({
          ...profile,
          version: '2',
          description: 'Local edit',
        }),
      ),
    );
    await screen.findByRole('button', { name: 'Повторить загрузку' });
    expect(screen.queryByText('Synthetic Street 1')).toBeNull();
  } finally {
    cleanup();
    vi.restoreAllMocks();
  }
});

it('final review regression: delayed valid replay cannot replace a newer profile when the next GET fails', async () => {
  go('profile', profile.id);
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  let reads = 0,
    writes = 0;
  let resolveReplay!: (result: Response) => void;
  const replay = new Promise<Response>((resolve) => {
    resolveReplay = resolve;
  });
  vi.spyOn(globalThis, 'fetch').mockImplementation((path, options) => {
    if (options?.method === 'PATCH')
      return ++writes === 1 ? response({}, 503) : replay;
    if (!String(path).endsWith('/profile')) return response({}, 404);
    ++reads;
    if (reads === 1) return response(profile);
    if (reads === 2)
      return response({
        ...profile,
        name: 'Newer confirmed profile',
        version: '3',
        description: 'Local edit',
      });
    return response({}, 503);
  });
  try {
    render(<ClientAccess initialToken={null} />);
    fireEvent.change(await screen.findByLabelText('Описание клуба'), {
      target: { value: 'Local edit' },
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить профиль' }),
    );
    fireEvent.click(
      await screen.findByRole('button', { name: 'Повторить ту же операцию' }),
    );
    expect(writes).toBe(2);
    const alreadySampledReplay = await response({
      ...profile,
      version: '2',
      description: 'Local edit',
    });
    fireEvent.focus(window);
    await screen.findByRole('heading', {
      name: 'Newer confirmed profile',
      level: 1,
    });
    await act(async () => resolveReplay(alreadySampledReplay));
    await screen.findByRole('button', { name: 'Повторить загрузку' });
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      'Newer confirmed profile',
    );
  } finally {
    cleanup();
    vi.restoreAllMocks();
  }
});
