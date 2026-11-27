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
import ClubNightDesk from './ClubNightDesk';
import type { Command, Desk, Table } from './clubNightDesk.types';
import { permissions } from './clubNightDesk.types';
import type { ClubProfile } from './onboarding.types';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));

const profile: ClubProfile = {
  id: '42',
  clientId: '11',
  clientName: 'Организация',
  name: 'Синтетический клуб',
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Москва',
  address: '',
  unit: '',
  version: '1',
  savedAt: null,
  permissions: ['client.club.read', ...Object.values(permissions)],
  availableActions: [],
};
const free: Table = {
  id: '1',
  number: 1,
  label: 'Стол 1',
  capacity: 6,
  status: 'free',
  sessionId: null,
  lastSessionId: null,
  bookingId: null,
};
const view = (): Desk => ({
  actorId: '99',
  clientId: '11',
  clubId: '42',
  permissions: Object.values(permissions),
  night: {
    id: '7',
    businessDate: '2026-11-27',
    opensAt: new Date(Date.now() - 3600000).toISOString(),
    closesAt: new Date(Date.now() + 3600000).toISOString(),
  },
  halls: [{ id: '5', name: 'Основной зал', tables: [structuredClone(free)] }],
  bookings: [],
  categories: {
    version: '3',
    primary: { id: '1', name: 'Бар' },
    extras: [{ id: '2', name: 'Шары' }],
  },
});
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status });
const receipt = (command: Command) => ({
  action: command.action,
  nightId: command.nightId,
  tableId: command.tableId,
  sessionId:
    command.action === 'seat' ||
    command.action === 'top_up' ||
    command.action === 'release'
      ? '57'
      : null,
  bookingId: command.bookingId ?? 'booking-created',
  operationId: command.amountMinor ? '15' : undefined,
  amountMinor: command.amountMinor,
  method: command.method,
  cashMinor: command.cashMinor,
  cardMinor:
    command.cashMinor && command.amountMinor
      ? (BigInt(command.amountMinor) - BigInt(command.cashMinor)).toString()
      : undefined,
});
const deferred = () => {
  let finish!: (value: Response) => void;
  return {
    promise: new Promise<Response>((resolve) => {
      finish = resolve;
    }),
    resolve: (value: Response) => finish(value),
  };
};
function mount(credential = 'synthetic') {
  return render(<ClubNightDesk profile={profile} credential={credential} />);
}
function useServer(
  get: () => Desk | Promise<Response>,
  onPost: (command: Command, key: string) => Response | Promise<Response>,
) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (url, options) => {
      expect(String(url)).toBe('/api/client/clubs/42/operations');
      expect(options?.cache).toBe('no-store');
      if (options?.method === 'POST') {
        const key = (options.headers as Record<string, string>)[
          'Idempotency-Key'
        ];
        expect(key).toBeTruthy();
        return onPost(JSON.parse(String(options.body)), key);
      }
      const data = await get();
      return data instanceof Response ? data : response(data);
    });
}
function amount(form: HTMLElement, value: string) {
  fireEvent.change(within(form).getByLabelText('Фактически получено'), {
    target: { value },
  });
}
async function openTable() {
  fireEvent.click(await screen.findByRole('button', { name: 'Столы' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Стол 1' }));
}
async function openSeat() {
  await openTable();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Посадить · Стол 1' }),
  );
  return screen.getByRole('form', { name: 'Посадка гостей' });
}
function submitDeposit(form: HTMLElement) {
  fireEvent.click(
    within(form).getByRole('button', { name: 'Посадить и записать оплату' }),
  );
}
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  sessionStorage.clear();
});

it('offers immediate walk-in seating and records cash with an exact automatic remainder', async () => {
  const current = view();
  const writes = vi.fn((command: Command) => {
    current.halls[0].tables[0] = {
      ...free,
      status: 'seated',
      sessionId: '57',
      lastSessionId: '57',
    };
    return response(receipt(command));
  });
  useServer(() => current, writes);
  mount();
  await screen.findByRole('button', { name: 'Посадить гостей' });
  const form = await openSeat();
  const confirm = within(form).getByRole('button', {
    name: 'Посадить и записать оплату',
  });
  amount(form, '15000');
  fireEvent.change(within(form).getByLabelText('Шары'), {
    target: { value: '17000' },
  });
  fireEvent.click(within(form).getByRole('radio', { name: 'Наличные' }));
  expect((confirm as HTMLButtonElement).disabled).toBe(true);
  fireEvent.change(within(form).getByLabelText('Шары'), {
    target: { value: '3000' },
  });
  expect(within(form).getByText(/12\s000,00/)).toBeTruthy();
  expect(within(form).getByText(/15\s000,00/)).toBeTruthy();
  submitDeposit(form);
  await screen.findByText('Стол 1 · гости посажены');
  expect(writes).toHaveBeenCalledWith(
    {
      action: 'seat',
      nightId: '7',
      tableId: '1',
      expectedLastSessionId: null,
      mode: 'DEPOSIT',
      amountMinor: '1500000',
      method: 'CASH',
      categoryVersion: '3',
      extras: { '2': '300000' },
    },
    expect.any(String),
  );
  await screen.findByText('Занят');
  expect(screen.getByText(/Записано .*Наличные/)).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Следующий стол' })).toBeTruthy();
});

it('splits mixed tender independently of allocation and preserves the exact lost-response retry', async () => {
  const writes = vi.fn<[Command, string], Response>();
  writes.mockImplementationOnce(() => response({}, 503));
  writes.mockImplementation((command) => response(receipt(command)));
  useServer(view, writes);
  const initial = mount();
  const form = await openSeat();
  const confirm = within(form).getByRole('button', {
    name: 'Посадить и записать оплату',
  }) as HTMLButtonElement;
  amount(form, '15000');
  fireEvent.click(within(form).getByRole('radio', { name: 'Комбинированная' }));
  const cash = within(form).getByLabelText('Наличными');
  for (const invalid of ['0', '15000', '15001']) {
    fireEvent.change(cash, { target: { value: invalid } });
    expect(confirm.disabled).toBe(true);
  }
  fireEvent.change(cash, { target: { value: '5000' } });
  fireEvent.change(within(form).getByLabelText('Шары'), {
    target: { value: '3000' },
  });
  expect(within(form).getByText(/10\s000,00 ₽/)).toBeTruthy();
  expect(within(form).getByText(/12\s000,00/)).toBeTruthy();
  submitDeposit(form);
  expect(await screen.findByText(/Неизвестно, сохранена ли исходная запись/)).toBeTruthy();
  const [command, key] = writes.mock.calls[0];
  expect(command).toMatchObject({
    method: 'MIXED',
    amountMinor: '1500000',
    cashMinor: '500000',
    extras: { '2': '300000' },
  });
  initial.unmount();
  mount();
  fireEvent.click(await screen.findByRole('button', { name: 'Проверить исходную запись' }));
  await screen.findByText('Стол 1 · гости посажены');
  expect(writes).toHaveBeenLastCalledWith(command, key);
  expect(screen.getByText(/наличными 5\s000,00 · картой 10\s000,00/)).toBeTruthy();
});

it('offers an independent card top-up with all money in the main category', async () => {
  const current = view();
  current.halls[0].tables[0] = {
    ...free,
    status: 'seated',
    sessionId: '57',
    lastSessionId: '57',
    payments: [{ operationId: '2', amountMinor: '80000', method: 'CASH' }],
  };
  const writes = vi.fn((command: Command) => response(receipt(command)));
  useServer(() => current, writes);
  mount();
  await openTable();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Доплата · Стол 1' }),
  );
  const form = screen.getByRole('form', { name: 'Доплата по посадке' });
  expect(within(form).getByText(/800,00 · Наличные/)).toBeTruthy();
  const cash = within(form).getByRole('radio', {
    name: 'Наличные',
  }) as HTMLInputElement;
  expect(cash.checked).toBe(false);
  amount(form, '20,75');
  fireEvent.click(within(form).getByRole('radio', { name: 'Карта' }));
  expect(within(form).getAllByText('20,75')).toHaveLength(2);
  fireEvent.click(
    within(form).getByRole('button', { name: 'Записать доплату' }),
  );
  await screen.findByText('Стол 1 · доплата записана');
  expect(writes).toHaveBeenCalledWith(
    {
      action: 'top_up',
      nightId: '7',
      tableId: '1',
      expectedLastSessionId: '57',
      sessionId: '57',
      amountMinor: '2075',
      method: 'CARD',
      categoryVersion: '3',
      extras: {},
    },
    expect.any(String),
  );
});

it('seats an existing booking using a recorded deposit without duplicating money or planned attendance', async () => {
  const current = view();
  current.halls[0].tables[0] = {
    ...free,
    status: 'reserved',
    bookingId: 'book',
    payments: [{ operationId: '2', amountMinor: '80000' }],
  };
  current.bookings = [
    {
      id: 'book',
      tableId: '1',
      guestName: 'Тестовый гость',
      guests: 3,
      status: 'BOOKED',
      phone: '+70000000000',
      username: 'guest',
      minDeposit: '500',
      source: 'CLUB',
      updatedAt: '2026-11-27T18:20:30Z',
    },
  ];
  const writes = vi.fn((command: Command) => response(receipt(command)));
  useServer(() => current, writes);
  mount();
  fireEvent.click(await screen.findByRole('button', { name: 'Брони' }));
  fireEvent.change(screen.getByRole('searchbox', { name: 'Поиск брони' }), {
    target: { value: 'guest' },
  });
  fireEvent.click(screen.getByRole('button', { name: /Тестовый гость/ }));
  expect(screen.getByText('@guest')).toBeTruthy();
  expect(screen.getByText('Отметка отсутствует')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Посадить по брони' }));
  const form = screen.getByRole('form', { name: 'Посадка гостей' });
  expect(within(form).getByText(/Депозит уже записан/)).toBeTruthy();
  expect(within(form).queryByLabelText('Фактически получено')).toBeNull();
  fireEvent.click(
    within(form).getByRole('button', { name: 'Посадить гостей' }),
  );
  await screen.findByText('Стол 1 · гости посажены');
  expect(writes).toHaveBeenCalledWith(
    {
      action: 'seat',
      nightId: '7',
      tableId: '1',
      expectedLastSessionId: null,
      bookingId: 'book',
      mode: 'DEPOSIT',
    },
    expect.any(String),
  );
});

it('restores the immutable command after reopening and retries the same idempotency key', async () => {
  const current = view();
  const writes = vi.fn<[Command, string], Response>();
  writes.mockImplementationOnce(() => {
    throw new Error('connection was lost');
  });
  writes.mockImplementation((command) => response(receipt(command)));
  useServer(() => current, writes);
  const first = mount();
  const form = await openSeat();
  amount(form, '5');
  fireEvent.click(within(form).getByRole('radio', { name: 'Карта' }));
  submitDeposit(form);
  const lookup = 'Проверить исходную запись';
  await screen.findByRole('button', { name: lookup });
  first.unmount();
  expect(JSON.stringify(sessionStorage)).not.toContain('synthetic');
  mount('different signed update');
  fireEvent.click(await screen.findByRole('button', { name: lookup }));
  await screen.findByText('Стол 1 · гости посажены');
  expect(writes.mock.calls).toHaveLength(2);
  expect(writes.mock.calls[1]).toEqual(writes.mock.calls[0]);
  expect(sessionStorage.length).toBe(0);
});

it('keeps the original draft during revalidation and requires an explicit category refresh', async () => {
  const current = view();
  const wake = deferred();
  let reads = 0;
  const writes = vi.fn((command: Command) => response(receipt(command)));
  useServer(() => (++reads === 1 ? current : wake.promise), writes);
  mount();
  const form = await openSeat();
  amount(form, '15000');
  fireEvent.click(within(form).getByRole('radio', { name: 'Карта' }));
  act(() => window.dispatchEvent(new Event('focus')));
  await screen.findByText(/Проверяем доступ/);
  const money = within(form).getByLabelText(
    'Фактически получено',
  ) as HTMLInputElement;
  expect(money.value).toBe('15000');
  const confirm = within(form).getByRole('button', {
    name: 'Посадить и записать оплату',
  }) as HTMLButtonElement;
  expect(confirm.disabled).toBe(true);
  await act(async () =>
    wake.resolve(
      response({
        ...current,
        categories: { ...current.categories, version: '4' },
      }),
    ),
  );
  expect(within(form).getByText(/Категории изменились/)).toBeTruthy();
  fireEvent.click(
    within(form).getByRole('button', {
      name: 'Загрузить актуальные категории',
    }),
  );
  submitDeposit(form);
  await screen.findByText('Стол 1 · гости посажены');
  expect(writes).toHaveBeenCalledWith(
    expect.objectContaining({ categoryVersion: '4', amountMinor: '1500000' }),
    expect.any(String),
  );
});

it('drops private state after a wake-up denial and rejects a neighbouring club payload', async () => {
  const current = view();
  const denied = deferred();
  let reads = 0;
  const writes = vi.fn();
  useServer(() => (++reads === 1 ? current : denied.promise), writes);
  mount();
  const form = await openSeat();
  amount(form, '15');
  act(() => window.dispatchEvent(new Event('focus')));
  await act(async () => denied.resolve(response({ code: 'not_found' }, 404)));
  await screen.findByText(/Доступ утрачен/);
  expect(screen.queryByRole('form')).toBeNull();
  expect(writes).not.toHaveBeenCalled();
  cleanup();
  vi.restoreAllMocks();
  useServer(() => ({ ...current, clubId: '43' }), writes);
  mount();
  await screen.findByRole('button', { name: 'Повторить загрузку' });
  expect(
    screen.queryByRole('button', { name: 'Посадить · Стол 1' }),
  ).toBeNull();
});

it('sends manual booking and booking schedule changes for the exact nightly table', async () => {
  const current = view();
  const writes = vi.fn((command: Command) => response(receipt(command)));
  useServer(() => current, writes);
  mount();
  await openTable();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Забронировать · Стол 1' }),
  );
  const form = screen.getByRole('form', { name: 'Создать бронь' });
  fireEvent.change(within(form).getByLabelText('Имя гостя'), {
    target: { value: 'Новый гость' },
  });
  fireEvent.change(within(form).getByLabelText('Плановое число гостей'), {
    target: { value: '3' },
  });
  fireEvent.change(within(form).getByLabelText(/Ожидаемый приход/), {
    target: { value: '2026-11-28T01:30' },
  });
  fireEvent.click(within(form).getByRole('button', { name: 'Создать бронь' }));
  await screen.findByText('Стол 1 · бронь создана');
  expect(writes).toHaveBeenCalledWith(
    {
      action: 'create_booking',
      nightId: '7',
      tableId: '1',
      guestName: 'Новый гость',
      guests: 3,
      arrivalBy: '2026-11-27T22:30:00.000Z',
    },
    expect.any(String),
  );

  current.bookings = [
    {
      id: 'book',
      tableId: '1',
      guestName: 'Новый гость',
      guests: 3,
      status: 'BOOKED',
      updatedAt: '2026-11-27T18:20:30Z',
    },
  ];
  await act(async () => window.dispatchEvent(new Event('focus')));
  fireEvent.click(await screen.findByRole('button', { name: 'Брони' }));
  fireEvent.click(await screen.findByRole('button', { name: /Новый гость/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Изменить бронь' }));
  const edit = screen.getByRole('form', { name: 'Изменить бронь' });
  fireEvent.change(within(edit).getByLabelText('Плановое число гостей'), {
    target: { value: '4' },
  });
  fireEvent.click(within(edit).getByRole('button', { name: 'Изменить бронь' }));
  await screen.findByText('Стол 1 · бронь обновлена');
  expect(writes).toHaveBeenCalledWith(
    {
      action: 'update_booking',
      nightId: '7',
      tableId: '1',
      bookingId: 'book',
      expectedUpdatedAt: '2026-11-27T18:20:30Z',
      guests: 4,
    },
    expect.any(String),
  );
  await waitFor(() => expect(writes).toHaveBeenCalledTimes(2));
});

it('keeps an unsent deposit tied to its original table through a WebView reload', async () => {
  const current = view();
  const writes = vi.fn((command: Command) => response(receipt(command)));
  useServer(() => current, writes);
  const first = mount();
  const form = await openSeat();
  amount(form, '15000');
  fireEvent.click(within(form).getByRole('radio', { name: 'Карта' }));
  first.unmount();
  current.night = { ...current.night!, id: '8', businessDate: '2026-11-28' };
  mount('new signature for same user');
  const restored = await screen.findByRole('form', { name: 'Посадка гостей' });
  expect(within(restored).getByText(/Открыта другая ночь/)).toBeTruthy();
  expect(
    (within(restored).getByLabelText('Фактически получено') as HTMLInputElement)
      .value,
  ).toBe('15000');
  expect(
    (
      within(restored).getByRole('button', {
        name: 'Посадить и записать оплату',
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(writes).not.toHaveBeenCalled();
  fireEvent.click(
    within(restored).getByRole('button', { name: 'Закрыть форму' }),
  );
  expect(sessionStorage.length).toBe(0);
});

it('submits no payment in a without-deposit seating mode', async () => {
  const current = view();
  const writes = vi.fn((command: Command) => response(receipt(command)));
  useServer(() => current, writes);
  mount();
  const form = await openSeat();
  fireEvent.change(within(form).getByLabelText('Режим посадки'), {
    target: { value: 'BILL' },
  });
  expect(within(form).queryByLabelText('Фактически получено')).toBeNull();
  fireEvent.click(
    within(form).getByRole('button', { name: 'Посадить гостей' }),
  );
  await screen.findByText('Стол 1 · гости посажены');
  expect(writes).toHaveBeenCalledWith(
    {
      action: 'seat',
      nightId: '7',
      tableId: '1',
      expectedLastSessionId: null,
      mode: 'BILL',
    },
    expect.any(String),
  );
});

it('asks for newly received money when a linked legacy deposit has a zero balance', async () => {
  const current = view();
  current.halls[0].tables[0] = {
    ...free,
    status: 'reserved',
    bookingId: 'book',
    payments: [{ operationId: 'legacy-zero', amountMinor: '0' }],
  };
  const writes = vi.fn((command: Command) => response(receipt(command)));
  useServer(() => current, writes);
  mount();
  const form = await openSeat();
  expect(within(form).queryByText(/Депозит уже записан/)).toBeNull();
  amount(form, '200');
  fireEvent.click(within(form).getByRole('radio', { name: 'Карта' }));
  submitDeposit(form);
  await screen.findByText('Стол 1 · гости посажены');
  expect(writes).toHaveBeenCalledWith(
    expect.objectContaining({
      action: 'seat',
      bookingId: 'book',
      amountMinor: '20000',
      method: 'CARD',
    }),
    expect.any(String),
  );
});

it('hides finances without the server table-money permission', async () => {
  const current = view();
  current.permissions = current.permissions.filter((permission) => permission !== permissions.payments);
  useServer(() => current, vi.fn());
  mount();
  await screen.findByRole('heading', { name: 'Смена' });
  expect(screen.queryByRole('button', { name: 'Финансы' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Брони' })).toBeTruthy();
});

it('cancels a reservation with a quick reason and opens a new walk-in on the freed table', async () => {
  const current = view();
  current.halls[0].tables[0] = { ...free, status: 'reserved', bookingId: 'book' };
  current.bookings = [{ id: 'book', tableId: '1', status: 'BOOKED',
    guestName: 'Старый гость', guests: 2, updatedAt: '2026-11-27T18:20:30Z' }];
  const writes = vi.fn((body: Command) => {
    if (body.action === 'cancel_booking') {
      current.bookings[0].status = 'CANCELLED';
      current.halls[0].tables[0] = free;
    }
    return response(receipt(body));
  });
  useServer(() => current, writes);
  mount();
  fireEvent.click(await screen.findByRole('button', { name: /Старый гость/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Отменить бронь' }));
  const cancellation = screen.getByRole('form', { name: 'Отменить бронь' });
  fireEvent.change(within(cancellation).getByLabelText('Причина отмены'), { target: { value: 'Не придёт' } });
  fireEvent.click(within(cancellation).getByRole('button', { name: 'Отменить бронь' }));
  expect(writes.mock.calls[0][0]).toMatchObject({ action: 'cancel_booking', bookingId: 'book', reason: 'Не придёт' });
  fireEvent.click(await screen.findByRole('button', { name: 'Посадить других гостей' }));
  const form = screen.getByRole('form', { name: 'Посадка гостей' });
  expect(within(form).getByText(/Стол 1/)).toBeTruthy();
  expect(within(form).queryByText('Старый гость')).toBeNull();
});

it('drops a remembered card when the server returns a closed work night', async () => {
  const closed = { ...view(), night: null, blocked: 'no_current_night' };
  const memory = new Map([['42', { tab: 'shift' as const, night: '', nights: {},
    search: '', filter: '', bookingId: 'obsolete-booking', tableMap: false }]]);
  useServer(() => closed, vi.fn());
  render(<ClubNightDesk profile={profile} credential="synthetic" workspaceMemory={memory} />);
  expect(await screen.findByRole('heading', { name: 'Смена' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Все брони' })).toBeTruthy();
  expect(screen.queryByRole('region', { name: 'Карточка брони' })).toBeNull();
});
