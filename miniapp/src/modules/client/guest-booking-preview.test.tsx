import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import GuestBookingPreview from './GuestBookingPreview';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));

const club = {
  clubId: '42',
  name: 'Клуб',
  timezone: 'Europe/Berlin',
  bookingBlocker: 'TABLE_MAPPING_REQUIRED',
  nights: [],
  halls: [
    {
      id: '7',
      name: 'Сохранённый зал',
      layoutRevision: '1',
      plan: null,
      tables: [
        {
          id: '51',
          hallId: '7',
          tableNumber: 8,
          label: 'У окна',
          capacity: 4,
          minDeposit: '5000',
          x: 0.2,
          y: 0.3,
        },
      ],
    },
  ],
};

function mount() {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  const back = vi.fn();
  render(
    <GuestBookingPreview
      credential="synthetic"
      id="42"
      back={back}
      reportDirty={vi.fn()}
    />,
  );
  return back;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('shows saved table details without pretending the table is available or sending a mutation', async () => {
  const request = vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValue(new Response(JSON.stringify(club)));
  const back = mount();
  await screen.findByText(/Нет ближайших ночей/);
  await screen.findByText(/пока нет изображения/);
  fireEvent.click(screen.getByRole('button', { name: /^#8 · У окна/ }));
  expect(screen.queryByText(/5000/)).toBeNull();
  expect(screen.getByText('Требует подключения')).toBeTruthy();
  expect(
    screen.getByRole('region', { name: 'Карточка стола' }).textContent,
  ).toContain('Невозможно определить доступность');
  const guests = screen.getByRole<HTMLInputElement>('spinbutton', {
    name: 'Количество гостей',
  });
  fireEvent.change(guests, { target: { value: '9' } });
  expect(guests.value).toBe('1');
  fireEvent.change(guests, { target: { value: '4' } });
  expect(guests.value).toBe('4');
  expect(
    screen.getByRole<HTMLButtonElement>('button', { name: 'Удержать стол' })
      .disabled,
  ).toBe(true);
  vi.spyOn(window, 'confirm').mockReturnValue(false);
  fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
  expect(back).not.toHaveBeenCalled();
  expect(request.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(
    true,
  );
});

it('shows access failure without any table or booking data', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ code: 'not_found' }), { status: 404 }),
  );
  mount();
  await screen.findByText(/Доступ утрачен/);
  expect(screen.queryByText('Сохранённый зал')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Удержать стол' })).toBeNull();
});

it('discards the previous table state on refresh failure and accepts only the selected night snapshot', async () => {
  const now = '2026-10-24T19:00:00Z';
  const baseNight = {
    opensAt: '2026-10-24T21:00+02:00',
    closesAt: '2026-10-25T08:00+01:00',
    cutoffAt: '2026-10-25T06:00+01:00',
    arrivalBy: null,
    eventStatus: 'VERIFIED',
  };
  let responseMode = 'FREE';
  const request = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (path) => {
      if (!String(path).endsWith('/availability')) {
        return new Response(
          JSON.stringify({
            ...club,
            bookingBlocker: 'GUEST_BOOKING_DISABLED',
            halls: club.halls.map((hall) => ({
              ...hall,
              tables: hall.tables.map((table) => ({
                ...table,
                bookingLinked: true,
              })),
            })),
            nights: [
              { ...baseNight, nightId: '20', businessDate: '2026-10-24' },
              { ...baseNight, nightId: '21', businessDate: '2026-10-25' },
            ],
          }),
        );
      }
      if (responseMode === 'ERROR') return new Response(null, { status: 500 });
      return new Response(
        JSON.stringify({
          clubId: '42',
          hallId: '7',
          nightId: '20',
          businessDate: '2026-10-24',
          layoutRevision: '1',
          sampledAt: now,
          tables: [{ id: '51', status: responseMode }],
        }),
      );
    });
  mount();
  const list = await screen.findByLabelText('Столы на схеме');
  await within(list).findByRole('button', { name: /Свободен/ });
  fireEvent.click(within(list).getByRole('button', { name: /Свободен/ }));
  const card = screen.getByRole('region', { name: 'Карточка стола' });
  expect(card.textContent).toContain('Свободен');
  responseMode = 'ERROR';
  fireEvent.click(screen.getByRole('button', { name: 'Обновить занятость' }));
  await screen.findByText(/Не удалось получить актуальный снимок/);
  expect(card.textContent).not.toContain('Свободен');
  expect(card.textContent).toContain('Невозможно определить доступность');
  responseMode = 'OCCUPIED';
  fireEvent.change(screen.getByRole('combobox', { name: 'Клубная ночь' }), {
    target: { value: '2026-10-25' },
  });
  await screen.findByText(/Не удалось получить актуальный снимок/);
  expect(card.textContent).not.toContain('Занят');
  expect(request.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(
    true,
  );
});
