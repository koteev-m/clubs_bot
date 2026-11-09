import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClubCalendar from './ClubCalendar';
import { calendarCommandIntent, calendarCommandRetry } from './calendarCommand';
import { calendarBody, calendarChanges } from './calendar';
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const empty = {
  clubId: '42',
  name: 'Unknown recovery',
  timezone: 'Europe/London',
  version: '1',
  canWrite: true,
  weekly: [],
  holidays: [],
  exceptions: [],
  nights: [],
  invalidDates: [],
  reconciliation: { businessDate: '2027-08-01', storedDates: [] },
};
const props = {
  credential: 'synthetic',
  id: '42',
  back: vi.fn(),
  reportDirty: vi.fn(),
  reportPending: vi.fn(),
};
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.clearAllMocks();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
const recovery = () =>
  screen.getByRole('button', {
    name: /Проверить результат операции|Повторить ту же операцию/,
  });
it('reads the committed no-op result after midnight without another mutation or a new key', async () => {
  vi.setSystemTime(new Date('2027-08-01T22:59:00Z'));
  let current = { ...empty };
  const writes: RequestInit[] = [];
  const resolutions: RequestInit[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve')) {
      resolutions.push(request!);
      return Response.json({ status: 'completed', calendar: current });
    }
    if (request?.method === 'PATCH') {
      writes.push(request);
      return Response.json({ code: 'unavailable' }, { status: 503 });
    }
    return Response.json(current);
  });
  render(<ClubCalendar {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  fireEvent.click(
    screen.getByRole('button', { name: 'Подтвердить формирование' }),
  );
  await screen.findByText('Результат операции пока неизвестен');
  vi.setSystemTime(new Date('2027-08-01T23:01:00Z'));
  current = {
    ...empty,
    reconciliation: { businessDate: '2027-08-02', storedDates: [] },
  };
  fireEvent.focus(window);
  await screen.findByRole('heading', { name: 'Календарь клуба' });
  fireEvent.click(recovery());
  await waitFor(() =>
    expect(screen.queryByText('Результат операции пока неизвестен')).toBeNull(),
  );
  expect(writes).toHaveLength(1);
  expect(resolutions).toHaveLength(1);
  expect(resolutions[0].body).toBe(writes[0].body);
  expect(resolutions[0].headers).toEqual(writes[0].headers);
  fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
  expect(props.back).toHaveBeenCalledOnce();
});
it('does not transfer unknown-plan consent in a London UTC London response before React commits', async () => {
  vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
  const a = {
    ...empty,
    reconciliation: { businessDate: '2027-10-31', storedDates: [] },
    weekly: [
      {
        day: 6,
        mode: 'open',
        open: '01:30',
        close: '05:00',
        startNextDay: true,
        nextDay: false,
      },
    ],
  };
  const b = { ...a, timezone: 'UTC' };
  let current = a;
  let reads = 0;
  let writes = 0;
  let finish!: (r: Response) => void;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve'))
      return Response.json({
        status: 'not_committed_at_snapshot',
        calendar: current,
      });
    if (request?.method === 'PATCH') {
      writes++;
      return Response.json({ code: 'unavailable' }, { status: 503 });
    }
    if (++reads === 3) return new Promise<Response>((r) => (finish = r));
    return Response.json(current);
  });
  render(<ClubCalendar {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  fireEvent.change(screen.getByLabelText('Выбор времени при переводе часов'), {
    target: { value: 'first' },
  });
  fireEvent.click(
    screen.getByRole('button', { name: 'Подтвердить формирование' }),
  );
  await screen.findByText('Результат операции пока неизвестен');
  current = b;
  fireEvent.focus(window);
  await screen.findByText('Unknown recovery · UTC');
  expect(
    screen.queryByLabelText('Выбор времени при переводе часов'),
  ).toBeNull();
  fireEvent.focus(window);
  await waitFor(() => expect(reads).toBe(3));
  const retry = recovery();
  await act(async () => {
    const response = Response.json(a);
    vi.spyOn(response, 'json').mockResolvedValue(a);
    finish(response);
    for (let n = 0; n < 12; n++) await Promise.resolve();
    retry.click();
  });
  expect(writes).toBe(1);
});
const overlap = {
  ...empty,
  reconciliation: { businessDate: '2027-10-31', storedDates: [] },
  weekly: [
    {
      day: 6,
      mode: 'open' as const,
      open: '01:30',
      close: '05:00',
      startNextDay: true,
      nextDay: false,
    },
  ],
};
const startUnknown = async (hasOverlap = false) => {
  render(<ClubCalendar {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  if (hasOverlap)
    fireEvent.change(
      screen.getByLabelText('Выбор времени при переводе часов'),
      { target: { value: 'first' } },
    );
  fireEvent.click(
    screen.getByRole('button', { name: 'Подтвердить формирование' }),
  );
  await screen.findByText('Результат операции пока неизвестен');
};
it('reconstructs the immutable original wire plan and requires fresh consent independently of a rendered UTC plan', () => {
  const before = {
    ...overlap,
    weekly: [{ ...overlap.weekly[0], open: '00:30' }],
  };
  const body = calendarBody(before, calendarChanges(before, overlap));
  const intent = calendarCommandIntent(
    before,
    body,
    'explicit-original-consent',
  );
  const encoded = JSON.stringify(intent.body);
  expect(
    calendarCommandRetry(
      intent,
      { ...before, timezone: 'UTC' },
      '2027-10-31',
      '',
    ),
  ).toBe('incompatible');
  expect(calendarCommandRetry(intent, before, '2027-10-31', '')).toBe(
    'consent_required',
  );
  expect(
    calendarCommandRetry(
      intent,
      before,
      '2027-10-31',
      'explicit-original-consent',
    ),
  ).toBe('allowed');
  before.weekly[0].open = '02:30';
  expect(JSON.stringify(intent.body)).toBe(encoded);
  expect(Object.isFrozen(intent.body.weekly)).toBe(true);
});
it('executes only an explicit compatible retry after a negative receipt snapshot using the original body and key', async () => {
  vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
  const writes: RequestInit[] = [];
  const reads: RequestInit[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve')) {
      reads.push(request!);
      return Response.json({
        status: 'not_committed_at_snapshot',
        calendar: overlap,
      });
    }
    if (request?.method === 'PATCH') {
      writes.push(request);
      return writes.length === 1
        ? Response.json({ code: 'unavailable' }, { status: 503 })
        : Response.json(overlap);
    }
    return Response.json(overlap);
  });
  await startUnknown(true);
  fireEvent.click(recovery());
  await screen.findByRole('button', { name: 'Повторить исходную команду' });
  expect(writes).toHaveLength(1);
  fireEvent.click(
    screen.getByRole('button', { name: 'Повторить исходную команду' }),
  );
  await screen.findByText('Сохранено');
  expect(writes).toHaveLength(2);
  expect(reads).toHaveLength(1);
  expect(writes[1].body).toBe(writes[0].body);
  expect(writes[1].headers).toEqual(writes[0].headers);
  expect(reads[0].headers).toEqual(writes[0].headers);
});
it('keeps original intent on indeterminate or unavailable resolver and later resolves without another mutation', async () => {
  vi.setSystemTime(new Date('2027-08-01T00:00:00Z'));
  let resolution = 0;
  let writes = 0;
  const bodies: string[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve')) {
      resolution++;
      bodies.push(String(request?.body));
      return resolution === 1
        ? Response.json({ status: 'indeterminate' })
        : resolution === 2
          ? Response.json({ code: 'unavailable' }, { status: 503 })
          : Response.json({ status: 'completed', calendar: empty });
    }
    if (request?.method === 'PATCH') {
      writes++;
      return Response.json({ code: 'unavailable' }, { status: 503 });
    }
    return Response.json(empty);
  });
  await startUnknown();
  fireEvent.click(recovery());
  await screen.findByText(
    'Пока нельзя подтвердить исход. Повторите проверку позже.',
  );
  expect(
    screen.queryByRole('button', { name: 'Повторить исходную команду' }),
  ).toBeNull();
  fireEvent.click(recovery());
  await waitFor(() => expect(resolution).toBe(2));
  await waitFor(() =>
    expect((recovery() as HTMLButtonElement).disabled).toBe(false),
  );
  expect(screen.getByText('Результат операции пока неизвестен')).toBeTruthy();
  fireEvent.click(recovery());
  await screen.findByText(
    'Операция выполнена. Показано актуальное расписание.',
  );
  expect(writes).toBe(1);
  expect(new Set(bodies).size).toBe(1);
});
it('serializes duplicate receipt clicks and does not produce a duplicate mutation', async () => {
  vi.setSystemTime(new Date('2027-08-01T00:00:00Z'));
  let finish!: (r: Response) => void;
  let reads = 0;
  let writes = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve')) {
      reads++;
      return new Promise<Response>((r) => (finish = r));
    }
    if (request?.method === 'PATCH') {
      writes++;
      return Response.json({ code: 'unavailable' }, { status: 503 });
    }
    return Response.json(empty);
  });
  await startUnknown();
  const button = recovery();
  fireEvent.click(button);
  fireEvent.click(button);
  expect(reads).toBe(1);
  expect(writes).toBe(1);
  await act(async () =>
    finish(Response.json({ status: 'completed', calendar: empty })),
  );
  await screen.findByText(
    'Операция выполнена. Показано актуальное расписание.',
  );
  expect(writes).toBe(1);
});
it('retains unknown rather than treating malformed receipt data as a successful operation', async () => {
  vi.setSystemTime(new Date('2027-08-01T00:00:00Z'));
  let reads = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve'))
      return Response.json(
        ++reads === 1
          ? {
              status: 'completed',
              calendar: { ...empty, clubId: '43', name: 'Private other club' },
            }
          : { status: 'completed', calendar: empty },
      );
    if (request?.method === 'PATCH')
      return Response.json({ code: 'unavailable' }, { status: 503 });
    return Response.json(empty);
  });
  await startUnknown();
  fireEvent.click(recovery());
  await waitFor(() =>
    expect((recovery() as HTMLButtonElement).disabled).toBe(false),
  );
  expect(screen.getByText('Результат операции пока неизвестен')).toBeTruthy();
  expect(screen.queryByText(/Private other club/)).toBeNull();
  fireEvent.click(recovery());
  await screen.findByText(
    'Операция выполнена. Показано актуальное расписание.',
  );
});
it.each(['club', 'identity'] as const)(
  'drops an uncancellable old receipt after changing %s',
  async (kind) => {
    vi.setSystemTime(new Date('2027-08-01T00:00:00Z'));
    const Abort = globalThis.AbortController;
    vi.stubGlobal(
      'AbortController',
      class extends Abort {
        abort() {}
      },
    );
    let finish!: (r: Response) => void;
    let fresh = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
      if (String(path).endsWith('/commands/resolve'))
        return new Promise<Response>((r) => (finish = r));
      if (request?.method === 'PATCH')
        return Response.json({ code: 'unavailable' }, { status: 503 });
      return Response.json(
        fresh
          ? {
              ...empty,
              clubId: kind === 'club' ? '43' : '42',
              name: 'New allowed context',
            }
          : empty,
      );
    });
    const app = render(<ClubCalendar {...props} />);
    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Сформировать ближайшие ночи',
      }),
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Подтвердить формирование' }),
    );
    await screen.findByText('Результат операции пока неизвестен');
    fireEvent.click(recovery());
    fresh = true;
    app.rerender(
      <ClubCalendar
        {...props}
        id={kind === 'club' ? '43' : '42'}
        credential={
          kind === 'identity' ? 'fresh-telegram-identity' : 'synthetic'
        }
      />,
    );
    await screen.findByText('New allowed context · Europe/London');
    await act(async () =>
      finish(
        Response.json({
          status: 'completed',
          calendar: { ...empty, name: 'Old private result' },
        }),
      ),
    );
    expect(screen.queryByText(/Old private result/)).toBeNull();
    expect(
      screen.queryByText('Операция выполнена. Показано актуальное расписание.'),
    ).toBeNull();
  },
);
it('does not apply a receipt read from before revoke after fresh admission', async () => {
  vi.setSystemTime(new Date('2027-08-01T00:00:00Z'));
  const Abort = globalThis.AbortController;
  vi.stubGlobal(
    'AbortController',
    class extends Abort {
      abort() {}
    },
  );
  let denied = false;
  let finish!: (r: Response) => void;
  let reads = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve')) {
      if (++reads === 1) return new Promise<Response>((r) => (finish = r));
      return Response.json({
        status: 'completed',
        calendar: { ...empty, name: 'Restored authorized club' },
      });
    }
    if (request?.method === 'PATCH')
      return Response.json({ code: 'unavailable' }, { status: 503 });
    return denied
      ? Response.json({ code: 'not_found' }, { status: 404 })
      : Response.json({
          ...empty,
          name: reads ? 'Restored authorized club' : empty.name,
        });
  });
  await startUnknown();
  fireEvent.click(recovery());
  denied = true;
  fireEvent.focus(window);
  await waitFor(() =>
    expect(screen.queryByText('Unknown recovery · Europe/London')).toBeNull(),
  );
  denied = false;
  fireEvent.focus(window);
  await screen.findByText('Restored authorized club · Europe/London');
  await act(async () =>
    finish(
      Response.json({
        status: 'completed',
        calendar: { ...empty, name: 'Old private result' },
      }),
    ),
  );
  expect(screen.queryByText(/Old private result/)).toBeNull();
  expect(screen.getByText('Результат операции пока неизвестен')).toBeTruthy();
  fireEvent.click(recovery());
  await screen.findByText(
    'Операция выполнена. Показано актуальное расписание.',
  );
});
it('does not retire unknown based on a newer GET when the receipt absence snapshot predates that version', async () => {
  vi.setSystemTime(new Date('2027-08-01T00:00:00Z'));
  let current = { ...empty };
  let finish!: (r: Response) => void;
  let lookups = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve')) {
      if (++lookups === 1) return new Promise<Response>((r) => (finish = r));
      return Response.json({ status: 'completed', calendar: current });
    }
    if (request?.method === 'PATCH')
      return Response.json({ code: 'unavailable' }, { status: 503 });
    return Response.json(current);
  });
  await startUnknown();
  fireEvent.click(recovery());
  current = { ...current, version: '2', name: 'Latest saved calendar' };
  fireEvent.focus(window);
  await screen.findByText('Latest saved calendar · Europe/London');
  await act(async () =>
    finish(
      Response.json({ status: 'not_committed_at_snapshot', calendar: empty }),
    ),
  );
  expect(screen.getByText('Результат операции пока неизвестен')).toBeTruthy();
  expect(screen.queryByText(/она уже не может изменить/)).toBeNull();
  fireEvent.click(recovery());
  await screen.findByText(
    'Операция выполнена. Показано актуальное расписание.',
  );
});
it('keeps the original unknown identity after a reversible retry conflict and requires new consent after timezone roundtrip', async () => {
  vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
  let current = { ...overlap };
  const writes: RequestInit[] = [];
  const reads: RequestInit[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve')) {
      reads.push(request!);
      return Response.json({
        status: 'not_committed_at_snapshot',
        calendar: current,
      });
    }
    if (request?.method === 'PATCH') {
      writes.push(request);
      if (writes.length === 1)
        return Response.json({ code: 'unavailable' }, { status: 503 });
      if (writes.length === 2) {
        current = { ...current, timezone: 'UTC' };
        return Response.json({ code: 'version_conflict' }, { status: 409 });
      }
      return Response.json(current);
    }
    return Response.json(current);
  });
  await startUnknown(true);
  fireEvent.click(recovery());
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить исходную команду' }),
  );
  await waitFor(() => expect(writes).toHaveLength(2));
  await screen.findByText('Результат операции пока неизвестен');
  expect(
    screen.queryByRole('button', {
      name: 'Перенести мои правки на актуальную версию',
    }),
  ).toBeNull();
  fireEvent.focus(window);
  await screen.findByText('Unknown recovery · UTC');
  current = { ...overlap };
  fireEvent.focus(window);
  await screen.findByText('Unknown recovery · Europe/London');
  fireEvent.click(recovery());
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить исходную команду' }),
  );
  expect(writes).toHaveLength(2);
  expect(
    screen.getByText(
      'Подтвердите время исходной команды заново перед повтором.',
    ),
  ).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Выбор времени при переводе часов'), {
    target: { value: 'first' },
  });
  fireEvent.click(
    screen.getByRole('button', { name: 'Повторить исходную команду' }),
  );
  await screen.findByText('Сохранено');
  expect(writes).toHaveLength(3);
  expect(writes.every((w) => w.body === writes[0].body)).toBe(true);
  expect(reads).toHaveLength(2);
  for (const attempt of [...writes, ...reads])
    expect(attempt.headers).toEqual(writes[0].headers);
});
it('does not forget an unknown command when a retransmission returns an unacceptable result payload', async () => {
  vi.setSystemTime(new Date('2027-08-01T00:00:00Z'));
  let writes = 0;
  let lookups = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve'))
      return Response.json({
        status: ++lookups === 1 ? 'not_committed_at_snapshot' : 'completed',
        calendar: empty,
      });
    if (request?.method === 'PATCH') {
      writes++;
      return writes === 1
        ? Response.json({ code: 'unavailable' }, { status: 503 })
        : Response.json({
            ...empty,
            clubId: '43',
            name: 'Foreign unacceptable result',
          });
    }
    return Response.json(empty);
  });
  await startUnknown();
  fireEvent.click(recovery());
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить исходную команду' }),
  );
  await waitFor(() => expect(writes).toBe(2));
  await screen.findByText('Результат операции пока неизвестен');
  expect(screen.queryByText(/Foreign unacceptable result/)).toBeNull();
  fireEvent.click(recovery());
  await screen.findByText(
    'Операция выполнена. Показано актуальное расписание.',
  );
  expect(writes).toBe(2);
  expect(lookups).toBe(2);
});
