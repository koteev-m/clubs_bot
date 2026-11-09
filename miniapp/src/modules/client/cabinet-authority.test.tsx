import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClubCabinet from './ClubCabinet';
import type { CalendarView } from './calendar';
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const profile = {
  id: '42',
  clientId: '21',
  clientName: 'Private tenant',
  name: 'Private review club',
  description: 'Private profile',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Private address',
  unit: '',
  version: '1',
  savedAt: '2026-11-09T00:00:00Z',
  permissions: ['client.club.read', 'client.club.profile.write'],
  availableActions: ['update'],
};
const preparation = {
  clubId: '42',
  clientId: '21',
  name: profile.name,
  version: '2',
  status: 'in_progress',
  published: false,
  reason: null,
  assignee: {
    userId: '9',
    displayName: 'Responsible',
    username: null,
    owner: false,
    active: true,
  },
  invitation: null,
  canManage: false,
  canSubmit: true,
  canInvite: false,
  canWrite: true,
  configurationChanged: false,
};
const calendar: CalendarView = {
  clubId: '42',
  name: profile.name,
  timezone: profile.timezone,
  version: '1',
  canWrite: true,
  weekly: [],
  holidays: [],
  exceptions: [],
  invalidDates: [],
  nights: [
    {
      businessDate: '2090-01-08',
      opensAt: '2090-01-09T00:00:00+03:00',
      closesAt: '2090-01-09T08:00:00+03:00',
      source: 'EXCEPTION',
      nightId: '99',
    },
  ],
};
const props = {
  credential: 'synthetic',
  id: '42',
  back: vi.fn(),
  open: vi.fn(),
  reportDirty: vi.fn(),
  reportPending: vi.fn(),
};
function deferred() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const reply = (data: unknown, status = 200) =>
  Response.json(data, { status });
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
it('P2 fences a late calendar response and erases the entire private cabinet after preparation denial', async () => {
  const late = deferred();
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (path, options) => {
      if (String(path).endsWith('/profile')) return reply(profile);
      if (String(path).endsWith('/preparation'))
        return options?.method === 'PATCH'
          ? reply({ code: 'not_found' }, 404)
          : reply(preparation);
      if (String(path).endsWith('/calendar')) return late.promise;
      return reply({}, 404);
    });
  render(<ClubCabinet {...props} />);
  await waitFor(() =>
    expect(
      fetch.mock.calls.some(([path]) => String(path).endsWith('/calendar')),
    ).toBe(true),
  );
  fireEvent.click(
    await screen.findByRole('button', { name: 'Передать на проверку' }),
  );
  await screen.findByText(/Доступ утрачен или запись недоступна/);
  await act(async () => late.resolve(reply(calendar)));
  expect(screen.queryByRole('heading', { name: profile.name })).toBeNull();
  expect(screen.queryByText('Вечеринка 08.01.2090')).toBeNull();
  expect(
    screen.queryByRole('button', { name: 'Перейти к настройкам' }),
  ).toBeNull();
});

it('P2 rejects completed old profile and preparation responses even without working cancellation and after recovery starts', async () => {
  const NativeAbortController = globalThis.AbortController;
  vi.stubGlobal(
    'AbortController',
    class extends NativeAbortController {
      abort() {}
    },
  );
  const events = vi.spyOn(window, 'dispatchEvent');
  const write = deferred();
  const oldProfile = deferred();
  const freshProfile = deferred();
  const oldPreparation = deferred();
  const oldCalendar = [deferred(), deferred()];
  let profiles = 0;
  let preparations = 0;
  let calendars = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    if (String(path).endsWith('/profile'))
      return ++profiles === 1
        ? reply(profile)
        : profiles === 2
          ? oldProfile.promise
          : freshProfile.promise;
    if (String(path).endsWith('/preparation'))
      return options?.method === 'PATCH'
        ? write.promise
        : ++preparations === 2
          ? oldPreparation.promise
          : reply(preparation);
    if (String(path).endsWith('/calendar'))
      return calendars < 2
        ? oldCalendar[calendars++].promise
        : reply({ ...calendar, nights: [] });
    return reply({}, 404);
  });
  render(<ClubCabinet {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Передать на проверку' }),
  );
  fireEvent.focus(window);
  await waitFor(() => expect(profiles).toBe(2));
  await act(async () => write.resolve(reply({ code: 'not_found' }, 404)));
  await screen.findByText(/Доступ утрачен или запись недоступна/);
  expect(screen.queryByRole('heading', { name: profile.name })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
  await waitFor(() => expect(profiles).toBe(3));
  await act(async () => {
    oldProfile.resolve(reply({ ...profile, name: 'Old authority response' }));
    oldCalendar.forEach((item) => item.resolve(reply(calendar)));
  });
  expect(screen.queryByText('Old authority response')).toBeNull();
  expect(screen.queryByText('Вечеринка 08.01.2090')).toBeNull();
  await act(async () =>
    freshProfile.resolve(
      reply({ ...profile, name: 'Fresh authorized club' }),
    ),
  );
  await screen.findByRole('heading', { name: 'Fresh authorized club' });
  await act(async () =>
    oldPreparation.resolve(reply({ code: 'forbidden' }, 403)),
  );
  expect(
    events.mock.calls.filter(
      ([event]) => event.type === 'client-authority-lost',
    ),
  ).toHaveLength(0);
  expect(
    screen.getByRole('heading', { name: 'Fresh authorized club' }),
  ).toBeTruthy();
  expect(screen.queryByText('Вечеринка 08.01.2090')).toBeNull();
});

it('P2 switching clubs fences an old mutation denial and pending calendar without clearing the allowed club', async () => {
  const write = deferred();
  const late = deferred();
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    const other = String(path).includes('/43/');
    if (String(path).endsWith('/profile'))
      return reply(
        other
          ? { ...profile, id: '43', name: 'Allowed other club' }
          : profile,
      );
    if (String(path).endsWith('/preparation'))
      return options?.method === 'PATCH'
        ? write.promise
        : reply(
            other
              ? {
                  ...preparation,
                  clubId: '43',
                  canSubmit: false,
                  canWrite: false,
                }
              : preparation,
          );
    if (String(path).endsWith('/calendar'))
      return other
        ? reply({ ...calendar, clubId: '43', nights: [] })
        : late.promise;
    return reply({}, 404);
  });
  const view = render(<ClubCabinet {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Передать на проверку' }),
  );
  view.rerender(<ClubCabinet {...props} id="43" />);
  await screen.findByRole('heading', { name: 'Allowed other club' });
  await act(async () => {
    write.resolve(reply({ code: 'not_found' }, 404));
    late.resolve(reply(calendar));
  });
  expect(
    screen.getByRole('heading', { name: 'Allowed other club' }),
  ).toBeTruthy();
  expect(screen.queryByRole('heading', { name: profile.name })).toBeNull();
  expect(screen.queryByText('Вечеринка 08.01.2090')).toBeNull();
});

it.each([400, 409, 503, 0])(
  'P2 keeps a valid club context and original unknown intent after non-revoking error %s',
  async (status) => {
    const late = deferred();
    const attempts: RequestInit[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      async (path, options) => {
        if (String(path).endsWith('/profile')) return reply(profile);
        if (String(path).endsWith('/calendar')) return late.promise;
        if (String(path).endsWith('/preparation')) {
          if (options?.method !== 'PATCH') return reply(preparation);
          attempts.push(options);
          if (attempts.length > 1)
            return reply({
              ...preparation,
              version: '3',
              status: 'submitted',
              canSubmit: false,
              canWrite: false,
            });
          if (!status) throw new TypeError('Synthetic network timeout');
          return reply(
            { code: status === 409 ? 'version_conflict' : 'request_failed' },
            status,
          );
        }
        return reply({}, 404);
      },
    );
    render(<ClubCabinet {...props} />);
    fireEvent.click(
      await screen.findByRole('button', { name: 'Передать на проверку' }),
    );
    if (status === 503 || status === 0)
      await screen.findByText('Результат операции пока неизвестен');
    else await screen.findByRole('alert');
    await act(async () => late.resolve(reply(calendar)));
    expect(screen.getByRole('heading', { name: profile.name })).toBeTruthy();
    expect(screen.getByText('Вечеринка 08.01.2090')).toBeTruthy();
    if (status === 503 || status === 0) {
      fireEvent.click(
        screen.getByRole('button', { name: 'Повторить ту же операцию' }),
      );
      await screen.findByText('Отправлено на проверку');
      expect(attempts).toHaveLength(2);
      expect(attempts[1].body).toBe(attempts[0].body);
      expect(attempts[1].headers).toEqual(attempts[0].headers);
    }
  },
);

it('P2 fences an HTTP 200 whose body finishes parsing only after authority loss', async () => {
  const NativeAbortController = globalThis.AbortController;
  vi.stubGlobal(
    'AbortController',
    class extends NativeAbortController {
      abort() {}
    },
  );
  let finishBody!: (value: CalendarView) => void;
  const body = new Promise<CalendarView>((resolve) => {
    finishBody = resolve;
  });
  const received = reply({});
  vi.spyOn(received, 'json').mockReturnValue(body);
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    if (String(path).endsWith('/profile')) return reply(profile);
    if (String(path).endsWith('/calendar')) return received;
    if (String(path).endsWith('/preparation'))
      return options?.method === 'PATCH'
        ? reply({ code: 'not_found' }, 404)
        : reply(preparation);
    return reply({}, 404);
  });
  render(<ClubCabinet {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Передать на проверку' }),
  );
  await screen.findByText(/Доступ утрачен или запись недоступна/);
  await act(async () => finishBody(calendar));
  expect(screen.queryByRole('heading', { name: profile.name })).toBeNull();
  expect(screen.queryByText('Вечеринка 08.01.2090')).toBeNull();
});
