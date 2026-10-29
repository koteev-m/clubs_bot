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
import PlatformOnboarding from './PlatformOnboarding';

const auth = vi.hoisted(() => ({ initData: 'signed-client', refreshEpoch: 0 }));
vi.mock('../auth/hooks/useInitData', () => ({ useInitData: () => auth }));
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const reply = (body: unknown, status = 200) =>
  Promise.resolve({
    ok: status < 400,
    status,
    json: async () => body,
  } as Response);
const context = {
  id: '20',
  name: 'Компания',
  permissions: ['client.application.manage'],
  clubs: [],
  allowedClubs: 1,
  createdClubs: 0,
  availableClubs: 1,
};
const profile = {
  id: '100',
  clientId: '20',
  clientName: 'Компания',
  name: 'Клуб',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Улица 1',
  unit: '',
  timezone: 'Europe/Moscow',
  description: '',
  version: '1',
  savedAt: '2026-10-29T12:00:00Z',
  permissions: ['client.club.read'],
  availableActions: [],
};
const cities = [
  {
    id: '1',
    countryCode: 'RU',
    name: 'Москва',
    region: 'Москва',
    timezone: 'Europe/Moscow',
  },
  {
    id: '2',
    countryCode: 'RU',
    name: 'Москва',
    region: 'Другой регион',
    timezone: 'Asia/Yekaterinburg',
  },
];
function mockApi(create = () => reply(profile), current = context) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation((path) => {
    const url = String(path);
    if (url.endsWith('/context'))
      return reply({ clients: [{ id: '20', name: 'Компания' }] });
    if (url.endsWith('/onboarding')) return reply(current);
    if (url.endsWith('/locations'))
      return reply({ countries: [{ code: 'RU', name: 'Россия' }] });
    if (url.endsWith('/locations/search'))
      return reply({ cities, hasMore: false });
    if (url.endsWith('/clubs')) return create();
    if (url.endsWith('/profile')) return reply(profile);
    return reply({ items: [], nextCursor: null });
  });
}
async function fill() {
  fireEvent.click(
    await screen.findByRole('button', { name: 'Создать заведение' }),
  );
  fireEvent.change(await screen.findByLabelText('Название заведения'), {
    target: { value: 'Клуб' },
  });
  fireEvent.change(screen.getByLabelText('Страна'), {
    target: { value: 'RU' },
  });
  fireEvent.change(screen.getByLabelText('Город'), {
    target: { value: 'Москва' },
  });
  fireEvent.change(await screen.findByLabelText('Город и регион'), {
    target: { value: '2' },
  });
  fireEvent.change(screen.getByLabelText('Полный адрес'), {
    target: { value: 'Улица 1' },
  });
}
function pop(screenName: string, position: number) {
  act(() => {
    history.replaceState(
      { clientPosition: position },
      '',
      '/app?mode=client&screen=' + screenName + '&resource=20',
    );
    window.dispatchEvent(
      new PopStateEvent('popstate', { state: { clientPosition: position } }),
    );
  });
}
describe('simplified onboarding', () => {
  beforeEach(() => {
    auth.initData = 'signed-client';
    auth.refreshEpoch = 0;
    history.replaceState({}, '', '/app?mode=client');
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('accepts a new invitation with one action and no checkbox, code or owner confirmation', async () => {
    const invite = {
      id: '10',
      clientId: '20',
      clientName: 'Компания',
      status: 'issued',
      version: '1',
      acceptanceMode: 'direct',
      expiresAt: '2026-11-04T12:00:00Z',
      availableActions: ['claim'],
    };
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((path) => {
      const url = String(path);
      if (url.endsWith('/context'))
        return reply({ clients: [{ id: '20', name: 'Компания' }] });
      if (url.endsWith('/onboarding')) return reply(context);
      if (url.endsWith('/search'))
        return reply({ items: [], nextCursor: null });
      return reply(
        url.endsWith('/claim')
          ? { ...invite, status: 'accepted', version: '2' }
          : invite,
      );
    });
    render(<ClientAccess initialToken={'a'.repeat(43)} />);
    const accept = await screen.findByRole('button', {
      name: 'Принять приглашение',
    });
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(
      fetch.mock.calls.every(([path]) => String(path).endsWith('/resolve')),
    ).toBe(true);
    fireEvent.click(accept);
    await screen.findByText('Можно добавить: 1');
    expect(screen.queryByText(/одноразовый код/)).toBeNull();
    expect(
      fetch.mock.calls.filter(([path]) => String(path).endsWith('/claim')),
    ).toHaveLength(1);
  });

  it('skips one context, selects country by name and explicit region, creates directly and opens the saved private profile', async () => {
    const fetch = mockApi();
    render(<ClientAccess initialToken={null} />);
    await fill();
    expect(screen.queryByLabelText('Код страны (2 буквы)')).toBeNull();
    expect(screen.queryByLabelText('Часовой пояс')).toBeNull();
    expect(screen.queryByLabelText('Комментарий')).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Создать заведение' }));
    await screen.findByText('Непубличный');
    expect(screen.getByText('Бот не подключён')).toBeTruthy();
    const create = fetch.mock.calls.find(([path]) =>
      String(path).endsWith('/clubs'),
    )!;
    expect(JSON.parse(String(create[1]?.body))).toEqual({
      venueName: 'Клуб',
      countryCode: 'RU',
      cityId: '2',
      address: 'Улица 1',
    });
    expect(
      fetch.mock.calls.some(([path]) =>
        /\/(submit|approve)$/.test(String(path)),
      ),
    ).toBe(false);
    expect(location.search).toContain('screen=club&resource=100');
  });

  it('retains input and shows a refusal next to create, then permits a corrected retry', async () => {
    mockApi(() => reply({ code: 'club_limit_reached' }, 409));
    render(<ClientAccess initialToken={null} />);
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Создать заведение' }));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Лимит заведений исчерпан');
    expect(alert.closest('form')).toBeTruthy();
    expect(
      (screen.getByLabelText('Название заведения') as HTMLInputElement).value,
    ).toBe('Клуб');
    expect(
      (
        screen.getByRole('button', {
          name: 'Создать заведение',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false);
  });

  it('retains the same pending intent through Back Forward and foreground; retry cannot double-create', async () => {
    let count = 0;
    const fetch = mockApi(() =>
      ++count === 1
        ? Promise.reject(new Error('lost response'))
        : reply(profile),
    );
    const view = render(<ClientAccess initialToken={null} />);
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Создать заведение' }));
    await screen.findByText('Результат операции пока неизвестен');
    pop('client-space', 0);
    await screen.findByText('Можно добавить: 1');
    pop('new-club', 1);
    await screen.findByText('Результат операции пока неизвестен');
    auth.refreshEpoch++;
    view.rerender(<ClientAccess initialToken={null} />);
    act(() => window.dispatchEvent(new Event('pageshow')));
    await screen.findByRole('button', { name: 'Повторить ту же операцию' });
    fireEvent.click(
      screen.getByRole('button', { name: 'Повторить ту же операцию' }),
    );
    await screen.findByText('Непубличный');
    const commands = fetch.mock.calls.filter(([path]) =>
      String(path).endsWith('/clubs'),
    );
    expect(commands).toHaveLength(2);
    expect(commands[0][1]?.body).toBe(commands[1][1]?.body);
    expect(commands[0][1]?.headers).toEqual(commands[1][1]?.headers);
    expect(Object.keys(localStorage)).toHaveLength(0);
    expect(Object.keys(sessionStorage)).toHaveLength(0);
  });

  it('clears private form on account change and does not replay old pending commands', async () => {
    const fetch = mockApi(() => Promise.reject(new Error('lost')));
    const view = render(<ClientAccess initialToken={null} />);
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Создать заведение' }));
    await screen.findByText('Результат операции пока неизвестен');
    auth.initData = 'signed-another';
    view.rerender(<ClientAccess initialToken={null} />);
    await screen.findByLabelText('Название заведения');
    expect(
      (screen.getByLabelText('Название заведения') as HTMLInputElement).value,
    ).toBe('');
    expect(screen.queryByText('Результат операции пока неизвестен')).toBeNull();
    expect(
      fetch.mock.calls.filter(([path]) => String(path).endsWith('/clubs')),
    ).toHaveLength(1);
  });

  it('shows no creation capacity without a configured limit and preserves readable clubs', async () => {
    mockApi(undefined, {
      ...context,
      availableClubs: 0,
      allowedClubs: null,
    } as unknown as typeof context);
    render(<ClientAccess initialToken={null} />);
    const button = await screen.findByRole('button', {
      name: 'Создать заведение',
    });
    expect((button as HTMLButtonElement).disabled).toBe(true);
    expect(
      screen.getByText(/владелец платформы должен установить лимит/),
    ).toBeTruthy();
  });

  it('owner sees counts, updates the total with version and displays below-usage refusal', async () => {
    history.replaceState(
      {},
      '',
      '/app?mode=platform-owner&screen=client&resource=20',
    );
    const detail = {
      ...context,
      allowedClubs: 3,
      createdClubs: 1,
      availableClubs: 2,
      limitVersion: '4',
      members: [],
      invitations: [],
    };
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((path) =>
        String(path).endsWith('/limit')
          ? reply({ code: 'club_limit_below_usage' }, 409)
          : reply(detail),
      );
    render(<PlatformOnboarding credential="signed-owner" />);
    await screen.findByText('Можно добавить: 2');
    fireEvent.change(screen.getByLabelText('Разрешено всего'), {
      target: { value: '2' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить лимит' }));
    await screen.findByText(/Лимит не может быть меньше/);
    await waitFor(() => expect(fetch.mock.calls).toHaveLength(2));
    expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toEqual({
      allowedClubs: 2,
      expectedVersion: '4',
    });
  });
  it('keeps owner limit command and input through Back Forward and foreground revalidation', async () => {
    history.replaceState(
      {},
      '',
      '/app?mode=platform-owner&screen=client&resource=20',
    );
    const detail = {
      ...context,
      limitVersion: '1',
      members: [],
      invitations: [],
    };
    let attempts = 0;
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((path) => {
      if (String(path).endsWith('/limit'))
        return ++attempts === 1
          ? Promise.reject(new Error('lost'))
          : reply({
              ...detail,
              allowedClubs: 2,
              availableClubs: 2,
              limitVersion: '2',
            });
      if (String(path).endsWith('/search'))
        return reply({ items: [], nextCursor: null });
      return reply(detail);
    });
    render(<PlatformOnboarding credential="signed-owner" />);
    fireEvent.change(await screen.findByLabelText('Разрешено всего'), {
      target: { value: '2' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить лимит' }));
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
    await screen.findByText('Результат операции пока неизвестен');
    act(() => window.dispatchEvent(new Event('pageshow')));
    await screen.findByText('Результат операции пока неизвестен');
    expect(
      (screen.getByLabelText('Разрешено всего') as HTMLInputElement).value,
    ).toBe('2');
    fireEvent.click(
      screen.getByRole('button', { name: 'Повторить ту же операцию' }),
    );
    await screen.findByText('Сохранено');
    const mutations = fetch.mock.calls.filter(([path]) =>
      String(path).endsWith('/limit'),
    );
    expect(mutations).toHaveLength(2);
    expect(mutations[0][1]?.body).toBe(mutations[1][1]?.body);
    expect(mutations[0][1]?.headers).toEqual(mutations[1][1]?.headers);
  });

  it('late denial from an aborted old account cannot clear the current account', async () => {
    let finish!: (body: unknown) => void;
    vi.spyOn(globalThis, 'fetch').mockImplementation((_path, options) => {
      if (
        (options?.headers as Record<string, string>)['X-Telegram-Init-Data'] ===
        'signed-client'
      )
        return Promise.resolve({
          ok: false,
          status: 403,
          json: () =>
            new Promise((resolve) => {
              finish = resolve;
            }),
        } as Response);
      return reply({
        clients: [
          { id: '21', name: 'New account cabinet' },
          { id: '22', name: 'Another context' },
        ],
      });
    });
    const view = render(<ClientAccess initialToken={null} />);
    await waitFor(() => expect(finish).toBeDefined());
    auth.initData = 'signed-new-account';
    view.rerender(<ClientAccess initialToken={null} />);
    await screen.findByText('New account cabinet');
    await act(async () => finish({ code: 'forbidden' }));
    expect(screen.getByText('New account cabinet')).toBeTruthy();
    expect(screen.queryByText('Нужна авторизация в Telegram')).toBeNull();
  });

  it('visible credential disappearance destroys private drafts while hidden suppression is temporary', async () => {
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
    mockApi();
    const view = render(<ClientAccess initialToken={null} />);
    await fill();
    auth.initData = '';
    view.rerender(<ClientAccess initialToken={null} />);
    expect(screen.getByText('Нужна авторизация в Telegram')).toBeTruthy();
    expect(screen.queryByLabelText('Название заведения')).toBeNull();
    auth.initData = 'signed-client';
    view.rerender(<ClientAccess initialToken={null} />);
    await screen.findByLabelText('Название заведения');
    expect(
      (screen.getByLabelText('Название заведения') as HTMLInputElement).value,
    ).toBe('');
  });

  it('Back between two clients restores each draft without mixing its fields or pending target', async () => {
    mockApi();
    render(<ClientAccess initialToken={null} />);
    await fill();
    act(() => {
      history.replaceState(
        { clientPosition: 3 },
        '',
        '/app?mode=client&screen=new-club&resource=21',
      );
      window.dispatchEvent(
        new PopStateEvent('popstate', { state: { clientPosition: 3 } }),
      );
    });
    fireEvent.change(await screen.findByLabelText('Название заведения'), {
      target: { value: 'Other client venue' },
    });
    pop('new-club', 2);
    await screen.findByLabelText('Название заведения');
    expect(
      (screen.getByLabelText('Название заведения') as HTMLInputElement).value,
    ).toBe('Клуб');
    expect(
      (screen.getByLabelText('Полный адрес') as HTMLInputElement).value,
    ).toBe('Улица 1');
    act(() => {
      history.replaceState(
        { clientPosition: 3 },
        '',
        '/app?mode=client&screen=new-club&resource=21',
      );
      window.dispatchEvent(
        new PopStateEvent('popstate', { state: { clientPosition: 3 } }),
      );
    });
    await screen.findByLabelText('Название заведения');
    expect(
      (screen.getByLabelText('Название заведения') as HTMLInputElement).value,
    ).toBe('Other client venue');
    expect(
      (screen.getByLabelText('Полный адрес') as HTMLInputElement).value,
    ).toBe('');
  });
});
