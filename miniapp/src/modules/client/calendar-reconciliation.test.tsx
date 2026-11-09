import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import ClubCalendar from './ClubCalendar';
import {
  calendarAffectedDates,
  calendarPlan,
  calendarSnapshotKey,
  CalendarView,
  shiftDate,
  validCalendarView,
} from './calendar';
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const weekly = {
  day: 6,
  mode: 'open' as const,
  open: '01:30',
  close: '05:00',
  startNextDay: true,
  nextDay: false,
};
const original = (today = '2027-08-01') => ({
  clubId: '42',
  name: 'Calendar review',
  timezone: 'Europe/London',
  version: '1',
  canWrite: true,
  weekly: [weekly],
  holidays: [],
  exceptions: [],
  nights: [],
  invalidDates: [],
  reconciliation: { businessDate: today, storedDates: [] as string[] },
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
const mount = () => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  return render(
    <ClubCalendar
      credential="synthetic"
      id="42"
      back={vi.fn()}
      reportDirty={vi.fn()}
      reportPending={vi.fn()}
    />,
  );
};
it('checks removed out-of-horizon exception against the new weekly fallback', () => {
  const before = {
    ...original(),
    exceptions: [{ ...weekly, date: '2027-10-30', open: '00:30' }],
  };
  const after = { ...before, exceptions: [] };
  const plan = calendarPlan(after, before.timezone, '2027-08-01', before);
  expect(plan.overlaps).toContain('31.10.2027 01:30');
  expect(
    plan.nights.find((n) => n.businessDate === '2027-10-30')?.opensAt,
  ).toBe('2027-10-31T01:30:00+01:00');
});
it.each(['hours', 'timezone', 'version'] as const)(
  'invalidates overlap consent after an authorized %s snapshot, before another write',
  async (kind) => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
    let current: CalendarView = original('2027-10-31');
    let resolveRead!: (r: Response) => void;
    let reads = 0;
    const writes: unknown[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_path, request) => {
      if (request?.method === 'PATCH') {
        writes.push(JSON.parse(String(request.body)));
        return Response.json(current);
      }
      if (++reads === 1) return Response.json(current);
      return new Promise<Response>((resolve) => {
        resolveRead = resolve;
      });
    });
    mount();
    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Сформировать ближайшие ночи',
      }),
    );
    fireEvent.change(
      screen.getByLabelText('Выбор времени при переводе часов'),
      { target: { value: 'first' } },
    );
    const confirm = screen.getByRole<HTMLButtonElement>('button', {
      name: 'Подтвердить формирование',
    });
    expect(confirm.disabled).toBe(false);
    current = {
      ...current,
      version: '2',
      timezone: kind === 'timezone' ? 'Europe/Berlin' : current.timezone,
      weekly: [
        {
          ...weekly,
          open:
            kind === 'timezone'
              ? '02:45'
              : kind === 'hours'
                ? '01:45'
                : '01:30',
        },
      ],
    };
    fireEvent.focus(window);
    await waitFor(() => expect(reads).toBe(2));
    await act(async () => resolveRead(Response.json(current)));
    const choice = screen.getByLabelText<HTMLSelectElement>(
      'Выбор времени при переводе часов',
    );
    expect(choice.value).toBe('');
    expect(confirm.disabled).toBe(true);
    fireEvent.click(confirm);
    expect(writes).toEqual([]);
    fireEvent.change(choice, { target: { value: 'first' } });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toMatchObject({
      expectedVersion: '2',
      timezone: current.timezone,
    });
  },
);

it('uses the exact finite server candidate set, including hidden stored dates, both dated versions and older explicit edits', () => {
  const before = {
    ...original(),
    reconciliation: {
      businessDate: '2027-08-01',
      storedDates: ['2028-10-28', '2028-11-04'],
    },
    holidays: [{ ...weekly, date: '2027-09-30', open: '00:30' }],
    exceptions: [
      { ...weekly, date: '2027-10-30', open: '00:30' },
      { ...weekly, date: '2027-07-20', open: '00:30' },
    ],
  };
  const draft = {
    ...before,
    holidays: [{ ...weekly, date: '2028-03-25' }],
    exceptions: [],
  };
  const dates = calendarAffectedDates(before, draft, '2027-08-01');
  const horizon = Array.from({ length: 59 }, (_, i) =>
    shiftDate('2027-07-29', i),
  );
  expect(dates).toEqual(
    [
      ...new Set([
        ...horizon,
        '2027-07-20',
        '2027-09-30',
        '2027-10-30',
        '2028-03-25',
        '2028-10-28',
        '2028-11-04',
      ]),
    ].sort(),
  );
  const plan = calendarPlan(draft, before.timezone, '2027-08-01', before);
  expect(plan.overlaps).toContain('31.10.2027 01:30');
  expect(plan.overlaps).toContain('29.10.2028 01:30');
  expect(plan.gaps).toContain('26.03.2028 01:30');
  expect(plan.complete).toBe(true);
});
it.each(['holidays', 'exceptions'] as const)(
  'checks addition, retime and removal of future %s using effective fallback',
  (field) => {
    const initial = { ...original(), weekly: [{ ...weekly, open: '00:30' }] };
    const added = { ...initial, [field]: [{ ...weekly, date: '2027-10-30' }] };
    expect(
      calendarPlan(added, initial.timezone, '2027-08-01', initial).overlaps,
    ).toContain('31.10.2027 01:30');
    const changed = {
      ...added,
      [field]: [{ ...weekly, date: '2027-10-30', open: '01:45' }],
    };
    expect(
      calendarPlan(changed, initial.timezone, '2027-08-01', added).overlaps,
    ).toContain('31.10.2027 01:45');
    const before = {
      ...added,
      weekly: [weekly],
      [field]: [{ ...weekly, date: '2027-10-30', open: '00:30' }],
    };
    expect(
      calendarPlan(
        { ...before, [field]: [] },
        initial.timezone,
        '2027-08-01',
        before,
      ).overlaps,
    ).toContain('31.10.2027 01:30');
  },
);
it('checks a removed spring exception outside the horizon and retains exception-over-holiday-over-weekly priority', () => {
  const before = {
    ...original('2027-01-01'),
    exceptions: [{ ...weekly, date: '2027-03-27', open: '00:30' }],
  };
  expect(
    calendarPlan(
      { ...before, exceptions: [] },
      before.timezone,
      '2027-01-01',
      before,
    ).gaps,
  ).toContain('28.03.2027 01:30');
  const holiday = { ...weekly, date: '2027-03-27', open: '00:30' };
  const safe = { ...before, holidays: [holiday], exceptions: [] };
  expect(
    calendarPlan(safe, before.timezone, '2027-01-01', before).gaps,
  ).toEqual([]);
  expect(
    calendarPlan(
      { ...safe, exceptions: [{ ...holiday, open: '01:30' }] },
      before.timezone,
      '2027-01-01',
      safe,
    ).gaps,
  ).toContain('28.03.2027 01:30');
});
it('keeps explicitly removed dates before the lookback rather than silently omitting their fallback', () => {
  const before = {
    ...original('2027-11-10'),
    exceptions: [{ ...weekly, date: '2027-10-30', open: '00:30' }],
  };
  const plan = calendarPlan(
    { ...before, exceptions: [] },
    before.timezone,
    '2027-11-10',
    before,
  );
  expect(plan.affectedDates).toContain('2027-10-30');
  expect(plan.overlaps).toContain('31.10.2027 01:30');
});
it('keeps ordinary Friday midnight-to-eight decisions valid, with club timezone independent of device time', () => {
  const before = {
    ...original(),
    weekly: [{ ...weekly, day: 5, open: '00:00', close: '08:00' }],
  };
  const plan = calendarPlan(before, before.timezone, '2027-08-01', before);
  expect(plan.complete).toBe(true);
  expect(plan.gaps).toEqual([]);
  expect(plan.overlaps).toEqual([]);
  const night = plan.nights.find((n) => n.businessDate === '2027-08-06')!;
  expect(night.opensAt).toBe('2027-08-07T00:00:00+01:00');
  expect(night.closesAt).toBe('2027-08-07T08:00:00+01:00');
  expect(Date.parse(night.closesAt) - Date.parse(night.opensAt)).toBe(
    8 * 3600000,
  );
});
it('fails closed when the server omits its full stored set or the club day has changed', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2027-08-01T00:00:00Z'));
  const current = { ...original(), reconciliation: undefined };
  const writes: unknown[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method === 'PATCH') writes.push(request.body);
    return Response.json(current);
  });
  mount();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  expect(
    screen.getByRole<HTMLButtonElement>('button', {
      name: 'Подтвердить формирование',
    }).disabled,
  ).toBe(true);
  fireEvent.click(
    screen.getByRole('button', { name: 'Подтвердить формирование' }),
  );
  expect(writes).toEqual([]);
  expect(() =>
    calendarAffectedDates(original(), original(), '2027-08-02'),
  ).toThrow();
});
it('rejects malformed full-date metadata and produces stable identities for equivalent response arrays', () => {
  const a = {
    ...original(),
    reconciliation: {
      businessDate: '2027-08-01',
      storedDates: ['2027-10-30', '2028-10-28'],
    },
  };
  expect(
    validCalendarView(
      { ...a, reconciliation: { ...a.reconciliation, storedDates: ['bad'] } },
      '42',
    ),
  ).toBe(false);
  expect(
    validCalendarView(
      {
        ...a,
        reconciliation: {
          ...a.reconciliation,
          storedDates: ['2027-10-30', '2027-10-30'],
        },
      },
      '42',
    ),
  ).toBe(false);
  const b = {
    ...a,
    reconciliation: {
      ...a.reconciliation,
      storedDates: [...a.reconciliation.storedDates].reverse(),
    },
  };
  expect(calendarSnapshotKey(a)).toBe(calendarSnapshotKey(b));
  expect(calendarPlan(a, a.timezone, '2027-08-01', a).identity).toBe(
    calendarPlan(b, b.timezone, '2027-08-01', b).identity,
  );
});

it('requires new consent after a version conflict and explicit reapply', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
  let current = original('2027-10-31');
  const writes: Record<string, unknown>[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method === 'PATCH') {
      writes.push(JSON.parse(String(request.body)));
      if (writes.length === 1) {
        current = {
          ...current,
          version: '2',
          weekly: [{ ...weekly, open: '01:45' }],
        };
        return Response.json({ code: 'version_conflict' }, { status: 409 });
      }
    }
    return Response.json(current);
  });
  mount();
  fireEvent.click(
    await screen.findByRole('tab', { name: 'Постоянное расписание' }),
  );
  fireEvent.change(screen.getAllByLabelText('Время закрытия')[0], {
    target: { value: '06:00' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить расписание' }));
  fireEvent.change(screen.getByLabelText('Выбор времени при переводе часов'), {
    target: { value: 'first' },
  });
  fireEvent.click(
    screen.getByRole('button', { name: 'Подтвердить расписание' }),
  );
  const reapply = await screen.findByRole('button', {
    name: 'Перенести мои правки на актуальную версию',
  });
  await waitFor(() =>
    expect((reapply as HTMLButtonElement).disabled).toBe(false),
  );
  fireEvent.click(reapply);
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить расписание' }));
  const confirm = screen.getByRole<HTMLButtonElement>('button', {
    name: 'Подтвердить расписание',
  });
  expect(confirm.disabled).toBe(true);
  fireEvent.click(confirm);
  expect(writes).toHaveLength(1);
  fireEvent.change(screen.getByLabelText('Выбор времени при переводе часов'), {
    target: { value: 'first' },
  });
  fireEvent.click(confirm);
  await waitFor(() => expect(writes).toHaveLength(2));
  expect(writes[1]).toMatchObject({
    expectedVersion: '2',
    reconciliationDate: '2027-10-31',
  });
});
it('resolves the original command identity without a second write after the source version increases', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
  let current = original('2027-10-31');
  const writes: { body: unknown; key: unknown }[] = [];
  const resolutions: { body: unknown; key: unknown }[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, request) => {
    if (String(path).endsWith('/commands/resolve')) {
      resolutions.push({
        body: JSON.parse(String(request?.body)),
        key: (request?.headers as Record<string, string>)['Idempotency-Key'],
      });
      return Response.json({ status: 'completed', calendar: current });
    }
    if (request?.method === 'PATCH') {
      writes.push({
        body: JSON.parse(String(request.body)),
        key: (request.headers as Record<string, string>)['Idempotency-Key'],
      });
      if (writes.length === 1)
        return Response.json({ code: 'unavailable' }, { status: 503 });
      return Response.json({ code: 'version_conflict' }, { status: 409 });
    }
    return Response.json(current);
  });
  mount();
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
  current = {
    ...current,
    version: '2',
    weekly: [{ ...weekly, open: '01:45' }],
  };
  fireEvent.focus(window);
  await waitFor(() =>
    expect(
      screen.getByLabelText<HTMLSelectElement>(
        'Выбор времени при переводе часов',
      ).value,
    ).toBe(''),
  );
  fireEvent.click(
    screen.getByRole('button', { name: 'Проверить результат операции' }),
  );
  await screen.findByText(
    'Операция выполнена. Показано актуальное расписание.',
  );
  expect(writes).toHaveLength(1);
  expect(resolutions).toHaveLength(1);
  expect(writes[0].key).toEqual(expect.any(String));
  expect(resolutions[0]).toEqual(writes[0]);
  expect(resolutions[0].body).toMatchObject({
    expectedVersion: '1',
    timezone: 'Europe/London',
    reconciliationDate: '2027-10-31',
    reconcile: true,
  });
});
it('blocks an unknown retry if authorized inputs changed without changing optimistic preconditions', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
  let current = original('2027-10-31');
  let writes = 0;
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
    return Response.json(current);
  });
  mount();
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
  current = { ...current, weekly: [{ ...weekly, open: '01:45' }] };
  fireEvent.focus(window);
  await waitFor(() =>
    expect(
      screen.getByLabelText<HTMLSelectElement>(
        'Выбор времени при переводе часов',
      ).value,
    ).toBe(''),
  );
  fireEvent.click(
    screen.getByRole('button', { name: 'Проверить результат операции' }),
  );
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить исходную команду' }),
  );
  expect(writes).toBe(1);
  expect(screen.getByText(/Повтор остановлен/)).toBeTruthy();
});
it('does not accept an uncancellable old GET after a fresher source invalidated consent', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
  const Abort = globalThis.AbortController;
  vi.stubGlobal(
    'AbortController',
    class extends Abort {
      abort() {}
    },
  );
  const before = original('2027-10-31');
  const after = {
    ...before,
    version: '2',
    weekly: [{ ...weekly, open: '01:45' }],
  };
  let reads = 0;
  let old!: (r: Response) => void;
  let fresh!: (r: Response) => void;
  let writes = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method === 'PATCH') {
      writes++;
      return Response.json(after);
    }
    if (++reads === 1) return Response.json(before);
    return new Promise<Response>((resolve) => {
      if (reads === 2) old = resolve;
      else fresh = resolve;
    });
  });
  mount();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  fireEvent.change(screen.getByLabelText('Выбор времени при переводе часов'), {
    target: { value: 'first' },
  });
  fireEvent.focus(window);
  await waitFor(() => expect(reads).toBe(2));
  fireEvent.focus(window);
  await waitFor(() => expect(reads).toBe(3));
  await act(async () => fresh(Response.json(after)));
  await act(async () => old(Response.json(before)));
  expect(
    screen.getByLabelText<HTMLSelectElement>('Выбор времени при переводе часов')
      .value,
  ).toBe('');
  fireEvent.click(
    screen.getByRole('button', { name: 'Подтвердить формирование' }),
  );
  expect(writes).toBe(0);
  expect(screen.getByText(/31.10.2027 01:45/, { selector: 'p' })).toBeTruthy();
  vi.unstubAllGlobals();
});
it.each([
  {
    name: 'fully inherited NULL',
    open: null,
    close: null,
    nextDayOverride: null,
    weeklyNext: true,
    expectedOverlap: true,
  },
  {
    name: 'partial hours NULL',
    open: '23:00',
    close: null,
    nextDayOverride: null,
    weeklyNext: false,
    expectedOverlap: true,
  },
  {
    name: 'explicit FALSE',
    open: '00:30',
    close: '01:30',
    nextDayOverride: false,
    weeklyNext: false,
    expectedOverlap: false,
  },
  {
    name: 'explicit TRUE',
    open: '00:30',
    close: '01:30',
    nextDayOverride: true,
    weeklyNext: false,
    expectedOverlap: true,
  },
])(
  'preserves valid persisted closing-day semantics for $name',
  ({ open, close, nextDayOverride, weeklyNext, expectedOverlap }) => {
    const before = {
      ...original(),
      weekly: [
        {
          ...weekly,
          open: '00:30',
          close: '01:30',
          startNextDay: false,
          nextDay: weeklyNext,
        },
      ],
      holidays: [
        {
          date: '2027-10-30',
          mode: 'open' as const,
          open,
          close,
          nextDay: nextDayOverride ?? false,
          startNextDay: false,
        },
      ],
    };
    const plan = calendarPlan(before, before.timezone, '2027-08-01', before);
    expect(plan.overlaps.includes('31.10.2027 01:30')).toBe(expectedOverlap);
    const night = plan.nights.find((n) => n.businessDate === '2027-10-30')!;
    expect(night.closesAt).toBe(
      expectedOverlap
        ? '2027-10-31T01:30:00+01:00'
        : '2027-10-30T01:30:00+01:00',
    );
  },
);
it('uses the emitted explicit closing day after replacing inherited hours with own hours', () => {
  const before = {
    ...original(),
    weekly: [{ ...weekly, startNextDay: false }],
    holidays: [
      {
        date: '2027-10-30',
        mode: 'open' as const,
        open: '23:00',
        close: null,
        nextDay: false,
      },
    ],
  };
  const plan = calendarPlan(before, before.timezone, '2027-08-01', before);
  expect(
    plan.nights.find((n) => n.businessDate === '2027-10-30')?.closesAt,
  ).toBe('2027-10-31T05:00:00+00:00');
  const full = {
    ...original(),
    holidays: [
      {
        ...weekly,
        date: '2027-10-30',
        open: '00:30',
        close: '01:30',
        startNextDay: false,
        nextDay: true,
      },
    ],
  };
  const edited = {
    ...full,
    holidays: [{ ...full.holidays[0], nextDay: false }],
  };
  expect(
    calendarPlan(edited, full.timezone, '2027-08-01', full).overlaps,
  ).toEqual([]);
});
it('never resurrects old consent when authorized inputs change and then return to the same plan', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
  const initial = original('2027-10-31');
  let current = initial;
  let reads = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
    reads++;
    return Response.json(current);
  });
  mount();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  fireEvent.change(screen.getByLabelText('Выбор времени при переводе часов'), {
    target: { value: 'first' },
  });
  current = {
    ...initial,
    timezone: 'Europe/Berlin',
    weekly: [{ ...weekly, open: '02:45' }],
  };
  fireEvent.focus(window);
  await waitFor(() => expect(reads).toBe(2));
  await waitFor(() =>
    expect(
      screen.getByLabelText<HTMLSelectElement>(
        'Выбор времени при переводе часов',
      ).value,
    ).toBe(''),
  );
  current = initial;
  fireEvent.focus(window);
  await waitFor(() => expect(reads).toBe(3));
  await screen.findByText(/31.10.2027 01:30/, { selector: 'p' });
  expect(
    screen.getByLabelText<HTMLSelectElement>('Выбор времени при переводе часов')
      .value,
  ).toBe('');
  expect(
    screen.getByRole<HTMLButtonElement>('button', {
      name: 'Подтвердить формирование',
    }).disabled,
  ).toBe(true);
});
it('does not skip a previous business date whose full-day IANA gap shifts it into today on the server', () => {
  const before = {
    ...original('2011-12-31'),
    timezone: 'Pacific/Apia',
    weekly: [
      { ...weekly, day: 5, open: '00:30', close: '08:00', startNextDay: false },
    ],
  };
  const plan = calendarPlan(before, before.timezone, '2011-12-31', before);
  expect(plan.affectedDates).toContain('2011-12-30');
  expect(plan.gaps).toContain('30.12.2011 00:30');
  expect(plan.gaps).toContain('30.12.2011 08:00');
  expect(plan.nights.some((n) => n.businessDate === '2011-12-30')).toBe(false);
});
it('proves expired London closing gaps are frozen instead of blocking an ordinary current schedule', () => {
  const before = {
    ...original('2027-03-30'),
    weekly: [
      {
        ...weekly,
        day: 6,
        open: '23:00',
        close: '01:30',
        startNextDay: false,
        nextDay: true,
      },
    ],
  };
  const plan = calendarPlan(before, before.timezone, '2027-03-30', before);
  expect(plan.gaps).toEqual([]);
  expect(plan.overlaps).toEqual([]);
  expect(plan.nights.some((n) => n.businessDate === '2027-03-27')).toBe(false);
});
it.each(['timezone', 'day'] as const)(
  'does not treat a reversible %s change as permission to retry an unknown old plan',
  async (kind) => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2027-10-31T00:00:00Z'));
    let current = original('2027-10-31');
    let writes = 0;
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
      return Response.json(current);
    });
    mount();
    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Сформировать ближайшие ночи',
      }),
    );
    fireEvent.change(
      screen.getByLabelText('Выбор времени при переводе часов'),
      { target: { value: 'first' } },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Подтвердить формирование' }),
    );
    await screen.findByText('Результат операции пока неизвестен');
    if (kind === 'timezone')
      current = {
        ...current,
        timezone: 'Europe/Berlin',
        weekly: [{ ...weekly, open: '02:45' }],
      };
    else {
      vi.setSystemTime(new Date('2027-11-01T00:00:00Z'));
      current = {
        ...current,
        reconciliation: { businessDate: '2027-11-01', storedDates: [] },
      };
    }
    fireEvent.focus(window);
    await screen.findByText('Результат операции пока неизвестен');
    await act(async () => {});
    fireEvent.click(
      screen.getByRole('button', { name: 'Проверить результат операции' }),
    );
    fireEvent.click(
      await screen.findByRole('button', { name: 'Повторить исходную команду' }),
    );
    expect(writes).toBe(1);
    expect(screen.getByText(/Повтор остановлен/)).toBeTruthy();
  },
);
