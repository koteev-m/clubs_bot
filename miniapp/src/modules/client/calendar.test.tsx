import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import ClubCalendar from './ClubCalendar';
import {
  calendarPlan,
  CalendarView,
  clubToday,
  defaultHours,
  shiftDate,
  wallTimeOptions,
} from './calendar';

const telegram = vi.hoisted(() => ({}));
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => telegram,
}));

const friday = { ...defaultHours, day: 5, mode: 'open' as const };
const base: CalendarView = {
  clubId: '42',
  name: 'Клуб',
  timezone: 'Europe/London',
  version: '1',
  canWrite: true,
  weekly: [friday],
  holidays: [],
  exceptions: [],
  nights: [],
  invalidDates: [],
};
const reply = (data: unknown, status = 200) => {
  const view = data as CalendarView;
  const snapshot =
    view?.weekly && view.timezone && !view.reconciliation
      ? {
          ...view,
          reconciliation: {
            businessDate: clubToday(view.timezone),
            storedDates: [],
          },
        }
      : data;
  return new Response(JSON.stringify(snapshot), { status });
};
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
const openArea = async (name: string) => {
  fireEvent.click(await screen.findByRole('tab', { name }));
};
const save = () => {
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить расписание' }));
  const confirmation = screen.queryByRole('button', {
    name: 'Подтвердить расписание',
  });
  if (confirmation) fireEvent.click(confirmation);
};
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const saturdayAfterMidnight = {
  day: 6,
  mode: 'open' as const,
  open: '01:30',
  close: '05:00',
  startNextDay: true,
  nextDay: false,
};

it('finds the spring gap on the previous business date instead of proposing a silently shifted night', () => {
  const plan = calendarPlan(
    { weekly: [saturdayAfterMidnight], holidays: [], exceptions: [] },
    'Europe/London',
    '2027-03-28',
  );
  expect(plan.gaps).toContain('28.03.2027 01:30');
  expect(plan.nights.some((night) => night.businessDate === '2027-03-27')).toBe(
    false,
  );
});

it('finds the autumn overlap on the previous business date and previews the canonical first occurrence', () => {
  const plan = calendarPlan(
    { weekly: [saturdayAfterMidnight], holidays: [], exceptions: [] },
    'Europe/London',
    '2027-10-31',
  );
  expect(plan.overlaps).toContain('31.10.2027 01:30');
  const night = plan.nights.find((row) => row.businessDate === '2027-10-30');
  expect(night).toMatchObject({
    opensAt: '2027-10-31T01:30:00+01:00',
    closesAt: '2027-10-31T05:00:00+00:00',
  });
  expect(Date.parse(night!.closesAt) - Date.parse(night!.opensAt)).toBe(
    4.5 * 3600000,
  );
});

it.each([
  { today: '2027-03-28', kind: 'gap' },
  { today: '2027-10-31', kind: 'overlap' },
])(
  'checks a DST $kind on the previous business date before materialization',
  async ({ today, kind }) => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(today + 'T00:30:00Z'));
    const writes: unknown[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
      if (request?.method === 'PATCH') writes.push(request.body);
      return reply({ ...base, weekly: [saturdayAfterMidnight] });
    });
    mount();
    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Сформировать ближайшие ночи',
      }),
    );
    const confirm = screen.getByRole<HTMLButtonElement>('button', {
      name: 'Подтвердить формирование',
    });
    expect(confirm.disabled).toBe(true);
    fireEvent.click(confirm);
    expect(writes).toEqual([]);
    expect(screen.queryByText('Сохранено')).toBeNull();
    if (kind === 'gap') {
      expect(screen.getByText(/Такого местного времени нет/)).toBeTruthy();
    } else {
      expect(screen.getByText(/Время встречается дважды/)).toBeTruthy();
      fireEvent.change(
        screen.getByLabelText('Выбор времени при переводе часов'),
        { target: { value: 'first' } },
      );
      expect(confirm.disabled).toBe(false);
      fireEvent.click(confirm);
      await waitFor(() => expect(writes).toHaveLength(1));
    }
  },
);

it.each([
  { today: '2027-03-28', kind: 'gap' },
  { today: '2027-10-31', kind: 'overlap' },
])(
  'checks a previous-business-date DST $kind before saving weekly edits',
  async ({ today, kind }) => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(today + 'T00:30:00Z'));
    let saved = {
      ...base,
      weekly: [{ ...saturdayAfterMidnight, open: '00:30' }],
    };
    const writes: Record<string, unknown>[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
      if (request?.method === 'PATCH') {
        const body = JSON.parse(String(request.body));
        writes.push(body);
        saved = { ...saved, version: '2', weekly: body.weekly };
      }
      return reply(saved);
    });
    mount();
    await openArea('Постоянное расписание');
    fireEvent.change(
      within(screen.getByRole('group', { name: 'Суббота' })).getByLabelText(
        'Время открытия',
      ),
      { target: { value: '01:30' } },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить расписание' }),
    );
    const confirm = screen.getByRole<HTMLButtonElement>('button', {
      name: 'Подтвердить расписание',
    });
    expect(confirm.disabled).toBe(true);
    fireEvent.click(confirm);
    expect(writes).toEqual([]);
    expect(screen.queryByText('Сохранено')).toBeNull();
    if (kind === 'overlap') {
      fireEvent.change(
        screen.getByLabelText('Выбор времени при переводе часов'),
        { target: { value: 'first' } },
      );
      fireEvent.click(confirm);
      await screen.findByText('Сохранено');
      expect(writes).toHaveLength(1);
      expect(writes[0]).toMatchObject({
        expectedVersion: '1',
        timezone: 'Europe/London',
        weekly: [{ ...saturdayAfterMidnight }],
      });
    }
  },
);

it.each([
  { today: '2027-03-28', kind: 'gap' },
  { today: '2027-10-31', kind: 'overlap' },
])(
  'checks a DST $kind at closing after both midnight offsets from a business date two days earlier',
  ({ today, kind }) => {
    const date = shiftDate(today, -2);
    const plan = calendarPlan(
      {
        weekly: [],
        holidays: [],
        exceptions: [
          {
            date,
            mode: 'open',
            open: '23:00',
            close: '01:30',
            startNextDay: true,
            nextDay: true,
          },
        ],
      },
      'Europe/London',
      today,
    );
    if (kind === 'gap') {
      expect(plan.gaps).toContain('28.03.2027 01:30');
      expect(plan.nights).toEqual([]);
    } else {
      expect(plan.overlaps).toContain('31.10.2027 01:30');
      expect(plan.nights).toEqual([
        {
          businessDate: date,
          opensAt: '2027-10-30T23:00:00+01:00',
          closesAt: '2027-10-31T01:30:00+01:00',
          source: 'EXCEPTION',
        },
      ]);
      expect(
        Date.parse(plan.nights[0].closesAt) -
          Date.parse(plan.nights[0].opensAt),
      ).toBe(2.5 * 3600000);
    }
  },
);

it.each([
  { today: '2027-03-30', offset: '+01:00' },
  { today: '2027-11-02', offset: '+00:00' },
])(
  'keeps the three-day lookback free of expired DST warnings at $today and retains the complete future horizon',
  ({ today, offset }) => {
    const first = shiftDate(today, -3);
    const last = shiftDate(today, 55);
    const extra = shiftDate(today, 70);
    const plan = calendarPlan(
      {
        weekly: Array.from({ length: 7 }, (_, index) => ({
          ...friday,
          day: index + 1,
        })),
        holidays: [{ ...saturdayAfterMidnight, date: first }],
        exceptions: [
          { ...saturdayAfterMidnight, date: shiftDate(first, -1) },
          {
            ...saturdayAfterMidnight,
            date: extra,
            open: '00:00',
            close: '08:00',
          },
        ],
      },
      'Europe/London',
      today,
    );
    expect(plan.gaps).toEqual([]);
    expect(plan.overlaps).toEqual([]);
    expect(plan.nights.some((night) => night.businessDate === first)).toBe(
      false,
    );
    expect(plan.nights.some((night) => night.businessDate === last)).toBe(true);
    expect(
      plan.nights.some((night) => night.businessDate === shiftDate(last, 1)),
    ).toBe(false);
    expect(
      plan.nights.find((night) => night.businessDate === extra),
    ).toMatchObject({
      opensAt: shiftDate(extra, 1) + 'T00:00:00' + offset,
      closesAt: shiftDate(extra, 1) + 'T08:00:00' + offset,
      source: 'EXCEPTION',
    });
  },
);

it('uses the club date and IANA zone when another local zone is still on the previous day', () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2027-03-28T00:30:00Z'));
  expect(clubToday('Pacific/Honolulu')).toBe('2027-03-27');
  expect(clubToday('Europe/London')).toBe('2027-03-28');
  const plan = calendarPlan(
    { weekly: [saturdayAfterMidnight], holidays: [], exceptions: [] },
    'Europe/London',
    clubToday('Europe/London'),
  );
  expect(plan.gaps).toEqual(['28.03.2027 01:30']);
  expect(plan.nights.some((night) => night.businessDate === '2027-03-27')).toBe(
    false,
  );
});

it('retains a normal previous-business-date night and the Friday midnight interval without DST acknowledgement', () => {
  const ordinary = calendarPlan(
    { weekly: [saturdayAfterMidnight], holidays: [], exceptions: [] },
    'Europe/London',
    '2027-04-04',
  );
  expect(ordinary.gaps).toEqual([]);
  expect(ordinary.overlaps).toEqual([]);
  expect(ordinary.nights[0]).toMatchObject({
    businessDate: '2027-04-03',
    opensAt: '2027-04-04T01:30:00+01:00',
    closesAt: '2027-04-04T05:00:00+01:00',
  });
  const midnight = calendarPlan(
    {
      weekly: [
        {
          ...friday,
          open: '00:00',
          close: '08:00',
          startNextDay: true,
          nextDay: false,
        },
      ],
      holidays: [],
      exceptions: [],
    },
    'Europe/London',
    '2027-04-03',
  );
  expect(midnight.gaps).toEqual([]);
  expect(midnight.overlaps).toEqual([]);
  expect(midnight.nights[0]).toMatchObject({
    businessDate: '2027-04-02',
    opensAt: '2027-04-03T00:00:00+01:00',
    closesAt: '2027-04-03T08:00:00+01:00',
  });
});

it('keeps the draft on a version conflict and resubmits only the reviewed changes', async () => {
  let saved = base;
  const mutations: { key: string; body: object }[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method !== 'PATCH') return reply(saved);
    const body = JSON.parse(String(request.body));
    mutations.push({
      key: new Headers(request.headers).get('Idempotency-Key')!,
      body,
    });
    if (mutations.length === 1) {
      saved = {
        ...base,
        version: '2',
        timezone: 'Europe/Berlin',
        weekly: [friday, { ...friday, day: 6 }],
      };
      return reply({ code: 'version_conflict' }, 409);
    }
    saved = {
      ...saved,
      version: '3',
      weekly: [{ ...friday, open: '20:30' }, saved.weekly[1]],
    };
    return reply(saved);
  });
  mount();
  await openArea('Постоянное расписание');
  const fridayFieldset = await screen.findByRole('group', { name: 'Пятница' });
  fireEvent.change(within(fridayFieldset).getByLabelText('Время открытия'), {
    target: { value: '20:30' },
  });
  save();
  const reapply = await screen.findByRole('button', {
    name: 'Перенести мои правки на актуальную версию',
  });
  expect(
    (
      within(fridayFieldset).getByLabelText(
        'Время открытия',
      ) as HTMLInputElement
    ).value,
  ).toBe('20:30');
  expect(
    (
      screen.getByRole('button', {
        name: 'Сохранить расписание',
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  await waitFor(() =>
    expect((reapply as HTMLButtonElement).disabled).toBe(false),
  );
  fireEvent.click(reapply);
  expect(
    (
      within(screen.getByRole('group', { name: 'Суббота' })).getByLabelText(
        'Проводим вечеринку',
      ) as HTMLInputElement
    ).checked,
  ).toBe(true);
  save();
  await screen.findByText('Сохранено');
  expect(mutations).toHaveLength(2);
  expect(mutations[1].key).not.toBe(mutations[0].key);
  expect(mutations[1].body).toEqual({
    expectedVersion: '2',
    reconciliationDate: clubToday('Europe/Berlin'),
    timezone: 'Europe/Berlin',
    weekly: [{ ...friday, open: '20:30' }],
    holidays: [],
    exceptions: [],
  });
});

it('refreshes read authority without displaying calendar data after revocation', async () => {
  let allowed = true;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    allowed
      ? reply({ ...base, canWrite: false })
      : reply({ code: 'not_found' }, 404),
  );
  mount();
  await screen.findByText('Календарь доступен только для просмотра.');
  expect(
    screen.queryByRole('button', { name: 'Сохранить расписание' }),
  ).toBeNull();
  allowed = false;
  act(() => {
    window.dispatchEvent(new Event('focus'));
  });
  await waitFor(() =>
    expect(screen.queryByText('Ближайшие клубные ночи')).toBeNull(),
  );
  expect(screen.queryByText('Клуб · Europe/London')).toBeNull();
});

it('shows the blocked business date and keeps the draft when dependencies prevent a change', async () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) =>
    request?.method === 'PATCH'
      ? reply(
          {
            code: 'calendar_night_dependencies',
            details: { businessDate: '2026-11-03' },
          },
          409,
        )
      : reply(base),
  );
  mount();
  await openArea('Постоянное расписание');
  const friday = await screen.findByRole('group', { name: 'Пятница' });
  const openingDay =
    within(friday).getByLabelText<HTMLSelectElement>('День открытия');
  fireEvent.change(openingDay, { target: { value: 'next' } });
  save();
  await screen.findByText(/Ночь \(03\.11\.2026\) связана/);
  expect(openingDay.value).toBe('next');
  expect(screen.queryByText('Сохранено')).toBeNull();
});

it('explains inherited dated hours without contradicting the saved overnight preview on reopen', async () => {
  let saved: CalendarView = {
    ...base,
    weekly: [
      {
        ...friday,
        open: '00:00',
        close: '12:00',
        startNextDay: true,
        nextDay: false,
      },
    ],
    holidays: [
      {
        date: '2026-11-06',
        mode: 'open',
        open: null,
        close: null,
        nextDay: false,
      },
    ],
    nights: [
      {
        businessDate: '2026-11-06',
        nightId: '100',
        source: 'HOLIDAY',
        opensAt: '2026-11-07T00:00+00:00',
        closesAt: '2026-11-07T12:00+00:00',
      },
    ],
  };
  const writes: Record<string, unknown>[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method === 'PATCH') {
      const body = JSON.parse(String(request.body));
      writes.push(body);
      saved = {
        ...saved,
        version: '2',
        holidays: body.holidays,
        nights: [
          {
            ...saved.nights[0],
            opensAt: '2026-11-06T00:00+00:00',
            closesAt: '2026-11-06T12:00+00:00',
          },
        ],
      };
    }
    return reply(saved);
  });
  const check = async () => {
    await openArea('Особые даты');
    const holiday = within(
      await screen.findByRole('group', { name: '06.11.2026' }),
    );
    expect(holiday.getByText(/сервер берёт их из расписания/)).toBeTruthy();
    expect(holiday.queryByText(/Дата вечеринки:/)).toBeNull();
    const opening = holiday.getByLabelText<HTMLSelectElement>('День открытия');
    expect(opening.value).toBe('schedule');
    expect(screen.getByText(/07.11.2026 00:00/)).toBeTruthy();
    expect(screen.getByText(/07.11.2026 12:00/)).toBeTruthy();
  };
  const first = mount();
  await check();
  first.unmount();
  const reopened = mount();
  await check();
  const holiday = within(screen.getByRole('group', { name: '06.11.2026' }));
  fireEvent.change(holiday.getByLabelText('Время открытия'), {
    target: { value: '00:00' },
  });
  fireEvent.change(holiday.getByLabelText('Время закрытия'), {
    target: { value: '12:00' },
  });
  expect(holiday.getByLabelText<HTMLSelectElement>('День открытия').value).toBe(
    'schedule',
  );
  save();
  await screen.findByText(
    'Выберите день открытия для правила с собственными часами.',
  );
  expect(writes).toHaveLength(0);
  fireEvent.change(holiday.getByLabelText('День открытия'), {
    target: { value: 'same' },
  });
  save();
  await screen.findByText('Сохранено');
  expect(writes).toEqual([
    {
      expectedVersion: '1',
      reconciliationDate: clubToday(base.timezone),
      timezone: base.timezone,
      weekly: [],
      exceptions: [],
      holidays: [
        {
          date: '2026-11-06',
          mode: 'open',
          open: '00:00',
          close: '12:00',
          nextDay: false,
        },
      ],
    },
  ]);
  reopened.unmount();
  mount();
  await openArea('Особые даты');
  const own = within(await screen.findByRole('group', { name: '06.11.2026' }));
  expect(own.getByLabelText<HTMLSelectElement>('День открытия').value).toBe(
    'same',
  );
  expect(own.getByLabelText<HTMLInputElement>('Время открытия').value).toBe(
    '00:00',
  );
});

it('starts with upcoming nights, preserves a draft across areas, and requires a concrete preview before saving', async () => {
  const writes: unknown[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method === 'PATCH')
      writes.push(JSON.parse(String(request.body)));
    return reply(base);
  });
  mount();
  const upcoming = await screen.findByRole('tab', { name: 'Предстоящие ночи' });
  expect(upcoming.getAttribute('aria-selected')).toBe('true');
  expect(screen.queryByRole('group', { name: 'Пятница' })).toBeNull();
  await openArea('Постоянное расписание');
  const friday = within(screen.getByRole('group', { name: 'Пятница' }));
  fireEvent.change(friday.getByLabelText('Время открытия'), {
    target: { value: '00:00' },
  });
  fireEvent.change(friday.getByLabelText('Время закрытия'), {
    target: { value: '08:00' },
  });
  fireEvent.change(friday.getByLabelText('День открытия'), {
    target: { value: 'next' },
  });
  fireEvent.change(friday.getByLabelText('День закрытия'), {
    target: { value: 'same' },
  });
  expect(
    friday.getByText(
      'Вечеринка пятницы. Открытие: суббота, 00:00. Закрытие: суббота, 08:00.',
    ),
  ).toBeTruthy();
  await openArea('Особые даты');
  await openArea('Постоянное расписание');
  expect(friday.getByLabelText<HTMLInputElement>('Время открытия').value).toBe(
    '00:00',
  );
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить расписание' }));
  expect(writes).toHaveLength(0);
  expect(
    screen.getByRole('heading', { name: 'Проверьте новое расписание' }),
  ).toBeTruthy();
  expect(
    screen.getByText(/защищённое изменение будет отклонено целиком/),
  ).toBeTruthy();
  expect(screen.getAllByText(/Ночь пятницы/).length).toBeGreaterThan(0);
  fireEvent.click(
    screen.getByRole('button', { name: 'Подтвердить расписание' }),
  );
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]).toMatchObject({
    weekly: [{ day: 5, startNextDay: true, nextDay: false }],
    expectedVersion: '1',
  });
});

it('previews materialization separately without altering schedule and makes no request on cancel', async () => {
  const writes: unknown[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method === 'PATCH')
      writes.push(JSON.parse(String(request.body)));
    return reply(base);
  });
  mount();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  expect(writes).toHaveLength(0);
  fireEvent.click(
    screen.getByRole('button', { name: 'Вернуться к редактированию' }),
  );
  expect(writes).toHaveLength(0);
  fireEvent.click(
    screen.getByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  fireEvent.click(
    screen.getByRole('button', { name: 'Подтвердить формирование' }),
  );
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]).toMatchObject({
    reconcile: true,
    weekly: [],
    holidays: [],
    exceptions: [],
  });
});

it('uses IANA offsets for overlap and gap checks and requires explicit overlap selection', async () => {
  expect(wallTimeOptions('2027-10-31', '01:30', 'Europe/London')).toEqual([
    '2027-10-31T01:30:00+01:00',
    '2027-10-31T01:30:00+00:00',
  ]);
  expect(wallTimeOptions('2027-03-28', '01:30', 'Europe/London')).toEqual([]);
  const dated = {
    date: '2027-10-31',
    mode: 'open' as const,
    open: '01:30',
    close: '05:00',
    nextDay: false,
  };
  const writes: unknown[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method === 'PATCH') writes.push(request.body);
    return reply({ ...base, weekly: [], exceptions: [dated] });
  });
  mount();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  const confirm = screen.getByRole<HTMLButtonElement>('button', {
    name: 'Подтвердить формирование',
  });
  expect(confirm.disabled).toBe(true);
  expect(screen.getByText(/Время встречается дважды/)).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Выбор времени при переводе часов'), {
    target: { value: 'first' },
  });
  expect(confirm.disabled).toBe(false);
  fireEvent.click(confirm);
  await waitFor(() => expect(writes).toHaveLength(1));
});

it('blocks nonexistent wall times instead of silently moving the opening through a DST gap', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    reply({
      ...base,
      weekly: [],
      exceptions: [
        {
          date: '2027-03-28',
          mode: 'open',
          open: '01:30',
          close: '05:00',
          nextDay: false,
        },
      ],
    }),
  );
  mount();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Сформировать ближайшие ночи' }),
  );
  expect(screen.getByText(/Такого местного времени нет/)).toBeTruthy();
  expect(
    screen.getByRole<HTMLButtonElement>('button', {
      name: 'Подтвердить формирование',
    }).disabled,
  ).toBe(true);
});

it('applies exception over holiday over weekly, preserving party date at midnight and real offsets', () => {
  const plan = calendarPlan(
    {
      weekly: [{ ...friday, open: '22:00', close: '06:00' }],
      holidays: [
        {
          date: '2026-11-06',
          mode: 'closed',
          open: null,
          close: null,
          nextDay: false,
        },
      ],
      exceptions: [
        {
          date: '2026-11-06',
          mode: 'open',
          open: '00:00',
          close: '08:00',
          startNextDay: true,
          nextDay: false,
        },
      ],
    },
    'Asia/Kathmandu',
    '2026-11-06',
  );
  expect(plan.nights[0]).toEqual({
    businessDate: '2026-11-06',
    opensAt: '2026-11-07T00:00:00+05:45',
    closesAt: '2026-11-07T08:00:00+05:45',
    source: 'EXCEPTION',
  });
  expect(plan.gaps).toEqual([]);
});

it('clears old club data and draft immediately when the club context changes', async () => {
  let finish!: (response: Response) => void;
  const next = new Promise<Response>((resolve) => {
    finish = resolve;
  });
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) =>
    String(input).includes('/43/') ? next : reply(base),
  );
  const mounted = mount();
  await screen.findByText('Клуб · Europe/London');
  mounted.rerender(
    <ClubCalendar
      credential="synthetic"
      id="43"
      back={vi.fn()}
      reportDirty={vi.fn()}
      reportPending={vi.fn()}
    />,
  );
  expect(screen.queryByText('Клуб · Europe/London')).toBeNull();
  expect(screen.queryByRole('group', { name: 'Пятница' })).toBeNull();
  await act(async () => {
    finish(reply({ ...base, clubId: '43', name: 'Другой клуб' }));
  });
  await screen.findByText('Другой клуб · Europe/London');
});

it('does not restore revoked data from a late mutation response, including after fresh readmission', async () => {
  let allowed = true;
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => {
    finish = resolve;
  });
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method === 'PATCH') return pending;
    return allowed ? reply(base) : reply({ code: 'not_found' }, 404);
  });
  mount();
  await openArea('Постоянное расписание');
  fireEvent.change(
    within(screen.getByRole('group', { name: 'Пятница' })).getByLabelText(
      'Время открытия',
    ),
    { target: { value: '20:00' } },
  );
  save();
  allowed = false;
  act(() => window.dispatchEvent(new Event('focus')));
  await waitFor(() =>
    expect(screen.queryByText('Клуб · Europe/London')).toBeNull(),
  );
  allowed = true;
  act(() => window.dispatchEvent(new Event('focus')));
  await screen.findByText('Клуб · Europe/London');
  await act(async () => {
    finish(reply({ ...base, version: '100', name: 'Старый ответ' }));
  });
  expect(screen.queryByText('Старый ответ · Europe/London')).toBeNull();
  expect(screen.queryByText('Сохранено')).toBeNull();
});

it('rejects a malformed or foreign club response before rendering private calendar data', async () => {
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValue(reply({ ...base, clubId: '99', name: 'Чужой клуб' }));
  const first = mount();
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  expect(screen.queryByText('Чужой клуб · Europe/London')).toBeNull();
  first.unmount();
  fetch.mockResolvedValue(reply({ ...base, nights: null }));
  mount();
  await screen.findByRole('button', { name: 'Повторить загрузку' });
  expect(screen.queryByText('Клуб · Europe/London')).toBeNull();
});
