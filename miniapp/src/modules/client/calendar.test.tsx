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
import { CalendarView, defaultHours } from './calendar';

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
const reply = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status });
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
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
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
  const fridayFieldset = await screen.findByRole('group', { name: 'Пятница' });
  fireEvent.change(within(fridayFieldset).getByLabelText('Начало'), {
    target: { value: '20:30' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить календарь' }));
  const reapply = await screen.findByRole('button', {
    name: 'Перенести мои правки на актуальную версию',
  });
  expect(
    (within(fridayFieldset).getByLabelText('Начало') as HTMLInputElement).value,
  ).toBe('20:30');
  expect(
    (
      screen.getByRole('button', {
        name: 'Сохранить календарь',
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
        'Рабочий день',
      ) as HTMLInputElement
    ).checked,
  ).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить календарь' }));
  await screen.findByText('Сохранено');
  expect(mutations).toHaveLength(2);
  expect(mutations[1].key).not.toBe(mutations[0].key);
  expect(mutations[1].body).toEqual({
    expectedVersion: '2',
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
    screen.queryByRole('button', { name: 'Сохранить календарь' }),
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
  const friday = await screen.findByRole('group', { name: 'Пятница' });
  const openingDay =
    within(friday).getByLabelText<HTMLSelectElement>('День открытия');
  fireEvent.change(openingDay, { target: { value: 'next' } });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить календарь' }));
  await screen.findByText(/Ночь \(03\.11\.2026\) связана/);
  expect(openingDay.value).toBe('next');
  expect(screen.queryByText('Сохранено')).toBeNull();
});
