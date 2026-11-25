import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import ClubBookingRules, { BookingRulesView } from './ClubBookingRules';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));

const initial: BookingRulesView = {
  clubId: '42',
  name: 'Клуб',
  timezone: 'Europe/Berlin',
  version: '1',
  canWrite: true,
  rules: {
    cutoffMinutes: 120,
    holdMinutes: null,
    retentionMinutes: 30,
  },
  nights: [],
  invalidDates: [],
};
const reply = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status });

function mount() {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  render(
    <ClubBookingRules
      credential="synthetic"
      id="42"
      back={vi.fn()}
      reportDirty={vi.fn()}
      reportPending={vi.fn()}
    />,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('never applies the HOLD recommendation on read or unrelated save and clears it only on an explicit edit', async () => {
  let saved = { ...initial, rules: { ...initial.rules, arrivalMinutes: 5, holdMinutes: 17 } };
  const writes: Record<string, unknown>[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, request) => {
    if (request?.method !== 'PATCH') return reply(saved);
    const { expectedVersion, ...rules } = JSON.parse(String(request.body));
    writes.push({ expectedVersion, ...rules });
    saved = { ...saved, version: String(Number(saved.version) + 1), rules };
    return reply(saved);
  });
  mount();
  const hold = await screen.findByRole<HTMLInputElement>('textbox', {
    name: /Время на оформление/,
  });
  expect(hold.value).toBe('17');
  expect(writes).toHaveLength(0);
  expect(screen.queryByText(/Примеры для вечеринки/)).toBeNull();
  expect(screen.getByText(/Добавьте ближайшую рабочую ночь/)).toBeTruthy();
  expect(screen.getByText(/Рекомендуем 10 минут/)).toBeTruthy();
  fireEvent.change(
    screen.getByRole('textbox', { name: /Приём онлайн-броней/ }),
    { target: { value: '90' } },
  );
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить правила' }));
  await screen.findByText('Сохранено');
  expect(writes[0]).toMatchObject({
    holdMinutes: 17,
    cutoffMinutes: 90,
  });
  fireEvent.change(hold, { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить правила' }));
  await waitFor(() => expect(writes).toHaveLength(2));
  expect(writes[1]).toMatchObject({ holdMinutes: null });
  expect(writes.every((body) => !('arrivalMinutes' in body))).toBe(true);
});

it('explains three draft deadlines on the club clock through the repeated hour', async () => {
  const night = {
    businessDate: '2026-10-24',
    opensAt: '2026-10-25T01:50+02:00',
    closesAt: '2026-10-25T03:10+01:00',
    cutoffAt: '2026-10-25T01:10+02:00',
    // Stale server responses and cached clients can still carry deprecated fields.
    arrivalBy: '2026-10-25T02:00+02:00',
    cutoffAtOrBeforeOpen: true,
    arrivalAtOrAfterClose: true,
    cutoffAfterArrival: true,
  };
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValue(reply({ ...initial, nights: [night] }));
  mount();
  await screen.findByText(/Примеры для вечеринки 24.10.2026/);
  expect(screen.queryByText(/поздн.*приезд|Последнее время приезда/i)).toBeNull();
  expect(screen.queryByText(/Если выбрать стол/)).toBeNull();
  const edit = (label: RegExp, value: string) =>
    fireEvent.change(screen.getByRole('textbox', { name: label }), {
      target: { value },
    });
  edit(/Приём онлайн-броней/, '20');
  expect(screen.queryByRole('textbox', { name: /Самый поздний приезд/ })).toBeNull();
  edit(/Время на оформление/, '10');
  edit(/Ожидание при опоздании/, '90');
  expect(
    screen.getByText(
      /Закрытие — .*03:10 UTC\+1.*Приём броней закончится .*02:50 UTC\+1/,
    ),
  ).toBeTruthy();
  expect(
    screen.getByText(
      /Если выбрать стол .*00:50 UTC\+2.*оформить бронь можно до .*01:00 UTC\+2/,
    ),
  ).toBeTruthy();
  expect(
    screen.getByText(
      /Если гость указал приезд к открытию .*01:50 UTC\+2.*стол ждёт его до .*02:20 UTC\+1/,
    ),
  ).toBeTruthy();
  expect(screen.queryByText(/поздн.*приезд|Последнее время приезда/i)).toBeNull();
  expect(
    fetch.mock.calls.every(([, request]) => request?.method !== 'PATCH'),
  ).toBe(true);
});

it.each(['45', '45.123456'])(
  'preserves seconds %s in the closing time and its booking deadline',
  async (seconds) => {
    const night = {
      businessDate: '2026-11-27',
      opensAt: '2026-11-27T22:00Z',
      closesAt: `2026-11-28T06:00:${seconds}Z`,
      cutoffAt: `2026-11-28T05:30:${seconds}Z`,
      arrivalBy: null,
      cutoffAtOrBeforeOpen: false,
      arrivalAtOrAfterClose: false,
      cutoffAfterArrival: false,
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      reply({
        ...initial,
        timezone: 'Europe/London',
        nights: [night],
        rules: { ...initial.rules, cutoffMinutes: 30 },
      }),
    );
    mount();
    const example = await screen.findByText(/Приём броней закончится/);
    const localSeconds = seconds.replace('.', ',');
    expect(example.textContent).toContain('06:00:' + localSeconds);
    expect(example.textContent).toContain('05:30:' + localSeconds);
  },
);

it('retains an edit after a conflict and preserves a concurrent change to a different field', async () => {
  let stored = initial;
  const patches: unknown[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, options) => {
    if (options?.method !== 'PATCH') return reply(stored);
    const body = JSON.parse(String(options.body));
    patches.push(body);
    if (patches.length === 1) {
      stored = {
        ...initial,
        version: '2',
        rules: { ...initial.rules, retentionMinutes: 45 },
      };
      return reply({ code: 'version_conflict' }, 409);
    }
    stored = {
      ...stored,
      version: '3',
      rules: { ...stored.rules, holdMinutes: 13 },
    };
    return reply(stored);
  });
  mount();
  const hold = await screen.findByRole<HTMLInputElement>('textbox', {
    name: /Время на оформление/,
  });
  fireEvent.change(hold, { target: { value: '1.5' } });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить правила' }));
  await screen.findByText(/Введите целое число минут/);
  expect(hold.value).toBe('1.5');
  expect(patches).toHaveLength(0);
  fireEvent.change(hold, { target: { value: '13' } });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить правила' }));
  const rebase = await screen.findByRole<HTMLButtonElement>('button', {
    name: 'Перенести мои правки',
  });
  expect(hold.value).toBe('13');
  await waitFor(() => expect(rebase.disabled).toBe(false));
  fireEvent.click(rebase);
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить правила' }));
  await screen.findByText('Сохранено');
  expect(patches).toHaveLength(2);
  expect(patches[1]).toEqual({
    ...initial.rules,
    retentionMinutes: 45,
    holdMinutes: 13,
    expectedVersion: '2',
  });
});

it('removes the settings and server preview after read access is revoked', async () => {
  let allowed = true;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    allowed
      ? reply({ ...initial, canWrite: false })
      : reply({ code: 'not_found' }, 404),
  );
  mount();
  await screen.findByText('Правила доступны для просмотра.');
  expect(
    screen.queryByRole('button', { name: 'Сохранить правила' }),
  ).toBeNull();
  allowed = false;
  act(() => {
    window.dispatchEvent(new Event('focus'));
  });
  await waitFor(() =>
    expect(screen.queryByText('Предпросмотр сохранённых правил')).toBeNull(),
  );
  expect(screen.queryByText('Клуб · Europe/Berlin')).toBeNull();
});

it('forgets the last booking rules and the draft across denied reads, failed retry, and a later new grant', async () => {
  let status = 200;
  let saved = { ...initial, rules: { ...initial.rules, holdMinutes: 17 } };
  const original = saved;
  let finishWrite!: (response: Response) => void;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, options) => {
    if (options?.method === 'PATCH') return new Promise<Response>((resolve) => { finishWrite = resolve; });
    return status === 200 ? reply(saved) : reply({ code: status === 404 ? 'not_found' : 'internal_error' }, status);
  });
  mount();
  fireEvent.change(await screen.findByDisplayValue('17'), { target: { value: '77' } });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить правила' }));
  await waitFor(() => expect(finishWrite).toBeDefined());
  status = 404;
  fireEvent.focus(window);
  await screen.findByText(/Доступ утрачен/);
  expect(screen.queryAllByRole('textbox')).toHaveLength(0);
  status = 503;
  fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
  await screen.findByRole('alert');
  expect(screen.queryAllByRole('textbox')).toHaveLength(0);
  status = 200;
  saved = { ...initial, name: 'Новая версия клуба', rules: { ...initial.rules, holdMinutes: 29 } };
  fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
  await screen.findByDisplayValue('29');
  await act(async () => finishWrite(reply({ ...original, version: '9' })));
  expect(screen.getByDisplayValue('29')).toBeTruthy();
  expect(screen.queryByDisplayValue('17')).toBeNull();
  expect(screen.queryByDisplayValue('77')).toBeNull();
  expect(screen.queryByText('Сохранено')).toBeNull();
});
