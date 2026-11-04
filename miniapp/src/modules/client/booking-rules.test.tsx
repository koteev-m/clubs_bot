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
  name: 'Клуб',
  timezone: 'Europe/Berlin',
  version: '1',
  canWrite: true,
  rules: {
    cutoffMinutes: 120,
    arrivalMinutes: null,
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
    name: /Предварительное удержание/,
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
