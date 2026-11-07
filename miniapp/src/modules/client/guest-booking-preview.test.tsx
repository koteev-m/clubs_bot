import { beforeEach } from 'vitest';
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

beforeEach(async () => {
  const { webcrypto } = await vi.importActual<{ webcrypto: Crypto }>(
    'node:crypto',
  );
  vi.stubGlobal('crypto', webcrypto);
});

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  vi.restoreAllMocks();
});

it.each([
  ['HOLD', undefined, 'Другой гость оформляет бронь.'],
  ['UNKNOWN', 'TABLE_UNLINKED', 'У стола нет подтверждённой связи'],
  ['UNKNOWN', 'FUTURE_REASON', 'Снимок не подтвердил состояние этого стола.'],
  ['UNKNOWN', 'NIGHT_CANCELLED', 'Клубная ночь отменена.'],
  ['UNAVAILABLE', 'BOOKING_TABLE_INACTIVE', 'Стол отключён для продаж.'],
  ['UNAVAILABLE', undefined, 'Стол недоступен в выбранную ночь.'],
])(
  'explains %s with reason %s without exposing a booking command',
  async (status, reason, explanation) => {
    const request = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async (path) =>
        new Response(
          JSON.stringify(
            String(path) ===
              '/api/client/clubs/42/booking-preview/nights/20/halls/7/availability'
              ? {
                  clubId: '42',
                  hallId: '7',
                  nightId: '20',
                  businessDate: '2026-11-06',
                  layoutRevision: '1',
                  sampledAt: '2026-11-05T12:00:00Z',
                  tables: [{ id: '51', status, reason }],
                }
              : {
                  ...club,
                  nights: [
                    {
                      nightId: '20',
                      businessDate: '2026-11-06',
                      opensAt: '2026-11-07T00:00+03:00',
                      closesAt: '2026-11-07T12:00+03:00',
                      cutoffAt: '2026-11-07T10:00+03:00',
                      arrivalBy: null,
                      eventStatus: 'VERIFIED',
                    },
                  ],
                },
          ),
        ),
    );
    mount();
    const list = await screen.findByLabelText('Столы на схеме');
    const table = await within(list).findByRole('button', {
      name: /^#8 · У окна/,
    });
    fireEvent.click(table);
    const card = screen.getByRole('region', { name: 'Карточка стола' });
    await within(card).findByText(new RegExp(explanation!));
    expect(request.mock.calls.map(([path]) => path)).toContain(
      '/api/client/clubs/42/booking-preview/nights/20/halls/7/availability',
    );
    expect(card.textContent).not.toContain('Свободен');
    expect(
      screen.getByRole<HTMLButtonElement>('button', { name: 'Удержать стол' })
        .disabled,
    ).toBe(true);
    expect(request.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(
      true,
    );
  },
);

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
  ).toContain('Доступность не проверена');
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
  await screen
    .findByRole('alert', { name: '' })
    .then((alert) =>
      expect(alert.textContent).toContain(
        'Не удалось получить актуальный снимок',
      ),
    );
  expect(card.textContent).not.toContain('Свободен');
  expect(card.textContent).toContain('Доступность не проверена');
  responseMode = 'OCCUPIED';
  fireEvent.change(screen.getByRole('combobox', { name: 'Клубная ночь' }), {
    target: { value: '2026-10-25' },
  });
  await screen.findByText(
    'Нет подтверждённого снимка занятости. Обновите занятость.',
    { selector: '[role=status]' },
  );
  expect(card.textContent).not.toContain('Занят');
  expect(request.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(
    true,
  );
});

it.each([
  [
    'NOT_MATERIALIZED',
    'Ночь ещё не сохранена. Снимок занятости пока недоступен.',
  ],
  [
    'VERIFIED',
    'Сервер не подтвердил номер ночи. Повторите загрузку предпросмотра.',
  ],
])(
  'does not request availability or fabricate an id for %s without a night id',
  async (eventStatus, message) => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      Response.json({
        ...club,
        nights: [
          {
            nightId: null,
            businessDate: '2026-11-06',
            eventStatus,
            arrivalBy: null,
            opensAt: '2026-11-07T00:00+03:00',
            closesAt: '2026-11-07T12:00+03:00',
            cutoffAt: '2026-11-07T10:00+03:00',
          },
        ],
      }),
    );
    mount();
    await screen.findByText(message);
    expect(fetch.mock.calls.map(([path]) => path)).toEqual([
      '/api/client/clubs/42/booking-preview',
    ]);
    expect(
      screen.queryByRole('button', { name: 'Обновить занятость' }),
    ).toBeNull();
  },
);

it('final review regression: retries after binary 404 / layout 503 must not paint an old layout over a new image', async () => {
  const original = {
    ...club.halls[0],
    plan: {
      contentType: 'image/png',
      sizeBytes: 3,
      sha256:
        'cba06b5736faf67e54b07b561eae94395e774c517a7d910a54369e1263ccfbd4',
    },
    layoutRevision: '1',
  };
  const replacement = {
    ...original,
    plan: {
      contentType: 'image/png',
      sizeBytes: 11,
      sha256:
        '95713e9cbdd1dfcb2d4080c2537f418d43ca0da25f0d7d6631f4f7c97b89dc47',
    },
    layoutRevision: '2',
    tables: [{ ...original.tables[0], label: 'Новое положение', x: 0.8 }],
  };
  let layouts = 0,
    plans = 0;
  const create = Object.getOwnPropertyDescriptor(URL, 'createObjectURL');
  const revoke = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL');
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: () => 'blob:new-plan',
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: () => undefined,
  });
  try {
    const request = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async (path) => {
        const url = String(path);
        if (url.endsWith('/booking-preview'))
          return Response.json({
            ...club,
            halls: [layouts > 1 ? replacement : original],
          });
        if (url.endsWith('/layout')) {
          ++layouts;
          if (layouts === 2) return Response.json({}, { status: 503 });
          return Response.json({
            clubId: '42',
            clientId: '9',
            permissions: ['client.club.read'],
            halls: [layouts === 1 ? original : replacement],
          });
        }
        if (url.endsWith('/plan'))
          return ++plans === 1
            ? Response.json({}, { status: 404 })
            : new Response('replacement');
        throw Error('unexpected path ' + url);
      });
    mount();
    const retry = await screen.findByRole('button', {
      name: 'Повторить загрузку схемы',
    });
    fireEvent.click(retry);
    await screen.findByRole('img');
    expect(
      request.mock.calls.filter(([path]) =>
        String(path).endsWith('/booking-preview'),
      ),
    ).toHaveLength(2);
    expect(screen.queryAllByRole('button', { name: /У окна/ })).toHaveLength(0);
  } finally {
    if (create) Object.defineProperty(URL, 'createObjectURL', create);
    if (revoke) Object.defineProperty(URL, 'revokeObjectURL', revoke);
  }
});

it('bounds an automatic snapshot refresh for an inconsistent image and leaves an explicit retry', async () => {
  const hall = {
    ...club.halls[0],
    plan: { contentType: 'image/png', sizeBytes: 9, sha256: '0'.repeat(64) },
  };
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (path) => {
      if (String(path).endsWith('/plan')) return new Response('bad image');
      if (String(path).endsWith('/layout'))
        return Response.json({
          clubId: '42',
          clientId: '9',
          permissions: ['client.club.read'],
          halls: [hall],
        });
      return Response.json({ ...club, halls: [hall] });
    });
  mount();
  const retry = await screen.findByRole('button', {
    name: 'Повторить загрузку схемы',
  });
  const reads = () =>
    fetch.mock.calls.filter(([path]) =>
      String(path).endsWith('/booking-preview'),
    ).length;
  expect(reads()).toBe(2);
  expect(screen.queryByRole('img')).toBeNull();
  fireEvent.click(retry);
  await screen.findByRole('button', { name: 'Повторить загрузку схемы' });
  expect(reads()).toBe(3);
  expect(screen.queryByRole('img')).toBeNull();
});
