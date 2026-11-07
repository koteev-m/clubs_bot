import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClientAccess from './ClientAccess';
import type { ClientLayout, ClientLayoutHall } from './layout.api';

vi.mock('../auth/hooks/useInitData', () => ({
  useInitData: () => ({ initData: 'synthetic-owner' }),
}));
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));

const profile = {
  id: '42',
  clientId: '21',
  clientName: 'Тестовая организация',
  name: 'Тестовый клуб',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Тестовый адрес',
  timezone: 'Europe/Moscow',
  description: '',
  unit: '',
  version: '1',
  savedAt: '2026-10-31T00:00:00Z',
  permissions: ['client.club.read', 'client.club.profile.write'],
  availableActions: ['update'],
  canPreviewBooking: true,
};
const hall: ClientLayoutHall = {
  id: '1',
  name: 'Main',
  layoutRevision: '1',
  plan: { contentType: 'image/png', sha256: 'synthetic', sizeBytes: 8 },
  tables: [],
};
const baseLayout: ClientLayout = {
  id: '42',
  clubId: '42',
  clientId: '21',
  halls: [],
  permissions: ['client.club.read'],
};
const night = {
  businessDate: '2026-11-07',
  nightId: '100',
  opensAt: '2026-11-07T22:00+03:00',
  closesAt: '2026-11-08T04:00+03:00',
};
const otherNight = {
  businessDate: '2026-11-14',
  nightId: '101',
  opensAt: '2026-11-14T22:00+03:00',
  closesAt: '2026-11-15T04:00+03:00',
};
const verified = (item: typeof night) => ({ ...item, eventStatus: 'VERIFIED' });
const previewWith = (nights: unknown[]) => ({
  ...(defaultReads['booking-preview'] as object),
  nights,
});
const defaultReads: Record<string, unknown> = {
  profile,
  layout: baseLayout,
  calendar: { clubId: '42', nights: [], invalidDates: [] },
  'booking-rules': { clubId: '42', version: '1', nights: [] },
  'booking-preview': {
    clubId: '42',
    bookingBlocker: 'TABLE_MAPPING_REQUIRED',
    halls: [],
    nights: [],
  },
};
function respond(reads: Record<string, unknown>) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (input, options) => {
      const match = String(input).match(/^\/api\/client\/clubs\/42\/([^/]+)$/);
      expect(options?.method).toBe('GET');
      expect(options?.cache).toBe('no-store');
      expect(options?.headers).toHaveProperty(
        'X-Telegram-Init-Data',
        'synthetic-owner',
      );
      const value = match ? reads[match[1]] : undefined;
      const status = typeof value === 'number' ? value : value ? 200 : 404;
      return new Response(
        JSON.stringify(
          status === 200 && value && typeof value === 'object'
            ? { timezone: profile.timezone, ...value }
            : { code: 'not_found' },
        ),
        { status },
      );
    });
}
function row(heading: string) {
  return within(
    screen.getByRole('heading', { name: heading, level: 3 }).closest('li')!,
  );
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  window.history.replaceState(
    {},
    '',
    '/app?mode=client&screen=club&resource=42',
  );
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('shows missing setup in an empty club and never uses the platform bot as club-bot evidence', async () => {
  const fetch = respond({ ...defaultReads });
  render(<ClientAccess initialToken={null} />);
  await screen.findByText('Добавьте зал и загрузите его схему.');
  expect(row('Профиль клуба').getByText('Настроено')).toBeTruthy();
  expect(
    row('Залы и отдельные схемы').getByText('Требует настройки'),
  ).toBeTruthy();
  expect(row('Столы и размещение').getByText('Требует настройки')).toBeTruthy();
  expect(
    row('Календарь и сформированные ночи').getByText(
      'Сформировано ближайших ночей: 0 из 0.',
    ),
  ).toBeTruthy();
  expect(
    row('Правила бронирования').getByText(/Действуют исходные значения/),
  ).toBeTruthy();
  expect(
    row('Отдельный бот клуба').getByText('Состояние пока невозможно проверить'),
  ).toBeTruthy();
  expect(row('Отдельный бот клуба').queryByRole('button')).toBeNull();
  expect(screen.getByText(/Гостевое бронирование выключено/)).toBeTruthy();
  expect(fetch.mock.calls).toHaveLength(5);
});

it('separates hall plans, physical placement, persisted rules and failed booking prerequisites', async () => {
  respond({
    ...defaultReads,
    layout: {
      ...baseLayout,
      halls: [
        {
          ...hall,
          tables: [
            {
              id: '10',
              hallId: '1',
              tableNumber: 1,
              x: null,
              y: null,
              bookingLinkStatus: 'BOOKING_TABLE_INACTIVE',
            },
            {
              id: '11',
              hallId: '1',
              tableNumber: 2,
              x: 0,
              y: 1,
              bookingLinkStatus: 'ACTIVE_LINK',
            },
          ],
        },
        {
          ...hall,
          id: '2',
          name: 'Welcome',
          plan: null,
          tables: [
            {
              id: '12',
              hallId: '2',
              tableNumber: 3,
              x: 0.5,
              y: 0.5,
              bookingLinkStatus: 'TABLE_LINK_MISMATCH',
            },
          ],
        },
      ],
    },
    calendar: {
      clubId: '42',
      nights: [night, { ...otherNight, nightId: null }],
      invalidDates: [],
    },
    'booking-rules': {
      clubId: '42',
      version: '2',
      nights: [night, otherNight],
    },
    'booking-preview': {
      clubId: '42',
      bookingBlocker: 'GUEST_BOOKING_DISABLED',
      halls: [],
      nights: [
        { ...night, eventStatus: 'INVALID_LINK' },
        { ...otherNight, nightId: null, eventStatus: 'NOT_MATERIALIZED' },
      ],
    },
  });
  render(<ClientAccess initialToken={null} />);
  await screen.findByText('Welcome · Схема не загружена');
  expect(
    row('Залы и отдельные схемы').getByText('Main · Схема сохранена'),
  ).toBeTruthy();
  expect(
    row('Столы и размещение').getByText('На схемах размещено столов: 1 из 3.'),
  ).toBeTruthy();
  expect(
    row('Календарь и сформированные ночи').getByText(
      'Сформировано ближайших ночей: 1 из 2.',
    ),
  ).toBeTruthy();
  expect(row('Правила бронирования').getByText('Настроено')).toBeTruthy();
  expect(
    row('Связи столов для бронирования').getByText(/Стол отключён для продаж/),
  ).toBeTruthy();
  expect(
    row('Связи столов для бронирования').getByText(
      /Связь стола не удалось подтвердить/,
    ),
  ).toBeTruthy();
  expect(
    row('Связи ночей с событиями').getByText(
      'Связь не подтверждает расписание ночи: 1.',
    ),
  ).toBeTruthy();
  expect(
    row('Связи ночей с событиями').getByRole('button', {
      name: 'Гостевой предпросмотр',
    }),
  ).toBeTruthy();
  expect(
    screen.queryByRole('button', {
      name: /Опубликовать|Активировать|Включить/,
    }),
  ).toBeNull();
});

it('labels confirmed missing connections separately from a failed or mismatched read', async () => {
  const reads = {
    ...defaultReads,
    layout: {
      ...baseLayout,
      halls: [
        {
          ...hall,
          tables: [
            {
              id: '10',
              hallId: '1',
              x: 0.1,
              y: 0.1,
              bookingLinked: false,
              bookingLinkStatus: 'TABLE_UNLINKED',
            },
          ],
        },
      ],
    },
    calendar: { clubId: '42', nights: [night], invalidDates: [] },
    'booking-rules': { clubId: '42', version: '1', nights: [night] },
    'booking-preview': previewWith([{ ...night, eventStatus: 'UNLINKED' }]),
  } as Record<string, unknown>;
  respond(reads);
  render(<ClientAccess initialToken={null} />);
  await screen.findByText('Без связанного события: 1.');
  expect(row('Залы и отдельные схемы').getByText('Настроено')).toBeTruthy();
  expect(row('Столы и размещение').getByText('Настроено')).toBeTruthy();
  expect(
    row('Связи столов для бронирования').getByText('Не подключено'),
  ).toBeTruthy();
  expect(
    row('Связи ночей с событиями').getByText('Не подключено'),
  ).toBeTruthy();
  reads.layout = {
    ...baseLayout,
    clientId: '999',
    halls: [{ ...hall, name: 'Чужой зал' }],
  };
  reads['booking-preview'] = 404;
  fireEvent.focus(window);
  await waitFor(() =>
    expect(row('Связи ночей с событиями').queryByRole('button')).toBeNull(),
  );
  expect(
    row('Связи ночей с событиями').getByText(
      'Состояние пока невозможно проверить',
    ),
  ).toBeTruthy();
  expect(
    row('Залы и отдельные схемы').getByText(
      'Состояние пока невозможно проверить',
    ),
  ).toBeTruthy();
  expect(screen.queryByText(/Чужой зал/)).toBeNull();

  reads.profile = 404;
  fireEvent.focus(window);
  await screen.findByText(/Доступ утрачен/);
  expect(screen.queryByRole('region', { name: 'Подготовка клуба' })).toBeNull();
  expect(screen.queryByText(/Тестовый клуб/)).toBeNull();
});

it('does not request setup details when the profile belongs to another client', async () => {
  const fetch = respond({ profile: 404 });
  render(<ClientAccess initialToken={null} />);
  await screen.findByText(/Доступ утрачен/);
  expect(screen.queryByRole('region', { name: 'Подготовка клуба' })).toBeNull();
  expect(fetch.mock.calls.map(([path]) => path)).toEqual([
    '/api/client/clubs/42/profile',
  ]);
});

it('keeps guest booking closed even when every available setup check succeeds', async () => {
  respond({
    ...defaultReads,
    layout: {
      ...baseLayout,
      halls: [
        {
          ...hall,
          tables: [{ x: 0.2, y: 0.4, bookingLinkStatus: 'ACTIVE_LINK' }],
        },
      ],
    },
    calendar: { clubId: '42', nights: [night], invalidDates: [] },
    'booking-rules': { clubId: '42', version: '2', nights: [night] },
    'booking-preview': {
      clubId: '42',
      bookingBlocker: 'GUEST_BOOKING_DISABLED',
      nights: [{ ...night, eventStatus: 'VERIFIED' }],
    },
  });
  render(<ClientAccess initialToken={null} />);
  await screen.findByText('Связь и время события проверены: 1 из 1.');
  const setup = screen.getByRole('region', { name: 'Подготовка клуба' });
  expect(within(setup).getAllByText('Настроено')).toHaveLength(7);
  expect(
    within(setup).getByText(
      'Настройки доступны в закрытом кабинете. Гостевое бронирование выключено.',
    ),
  ).toBeTruthy();
  expect(
    within(setup).queryByRole('button', { name: /Опубликовать|Включить/ }),
  ).toBeNull();
});

it.each([
  ['fewer nights', [night], [verified(night)]],
  [
    'different date',
    [night, { ...otherNight, businessDate: '2026-11-15' }],
    [verified(night), verified(otherNight)],
  ],
  [
    'different interval',
    [night, otherNight],
    [
      verified(night),
      verified({ ...otherNight, closesAt: '2026-11-15T05:00+03:00' }),
    ],
  ],
  [
    'different id',
    [night, otherNight],
    [verified(night), verified({ ...otherNight, nightId: '900' })],
  ],
  ['empty rules', [], [verified(night), verified(otherNight)]],
])(
  'withholds readiness for %s and recovers on a consistent reread',
  async (_, rules, links) => {
    const reads = {
      ...defaultReads,
      calendar: { clubId: '42', nights: [night, otherNight], invalidDates: [] },
      'booking-rules': { clubId: '42', version: '2', nights: rules },
      'booking-preview': previewWith(links),
    };
    respond(reads);
    render(<ClientAccess initialToken={null} />);
    const retry = await screen.findByRole('button', {
      name: 'Повторить проверку ночей',
    });
    for (const title of [
      'Календарь и сформированные ночи',
      'Правила бронирования',
      'Связи ночей с событиями',
    ]) {
      expect(row(title).queryByText('Настроено')).toBeNull();
    }
    expect(row('Профиль клуба').getByText('Настроено')).toBeTruthy();
    reads['booking-rules'] = {
      clubId: '42',
      version: '2',
      nights: [otherNight, night],
    };
    reads['booking-preview'] = previewWith([
      verified(night),
      verified(otherNight),
    ]);
    fireEvent.click(retry);
    await waitFor(() =>
      expect(
        row('Связи ночей с событиями').getByText('Настроено'),
      ).toBeTruthy(),
    );
    expect(row('Правила бронирования').getByText('Настроено')).toBeTruthy();
  },
);

it('keeps preview navigation on a temporary error and can retry inside the preview', async () => {
  const reads = { ...defaultReads };
  respond(reads);
  render(<ClientAccess initialToken={null} />);
  const open = await screen.findByRole('button', {
    name: 'Гостевой предпросмотр',
  });
  reads['booking-preview'] = 503;
  fireEvent.focus(window);
  await screen.findByText(
    'Не удалось проверить все сведения о ближайших ночах.',
  );
  expect(open).toBe(
    screen.getByRole('button', { name: 'Гостевой предпросмотр' }),
  );
  fireEvent.click(
    await screen.findByRole('button', { name: 'Гостевой предпросмотр' }),
  );
  await screen.findByRole('heading', { name: 'Гостевой предпросмотр' });
  Object.assign(reads, {
    'booking-preview': {
      ...previewWith([]),
      timezone: 'Europe/Moscow',
      name: profile.name,
    },
  });
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить загрузку' }),
  );
  await screen.findByText(
    'Нет ближайших ночей. Добавьте ночь в календаре клуба.',
  );
});

it.each([403, 404, { ...previewWith([]), clubId: '99' }])(
  'does not expose preview navigation after a refused or foreign response (%j)',
  async (previewResult) => {
    const reads = { ...defaultReads };
    respond(reads);
    render(<ClientAccess initialToken={null} />);
    await screen.findByRole('button', { name: 'Гостевой предпросмотр' });
    reads['booking-preview'] = previewResult;
    fireEvent.focus(window);
    if (previewResult === 403) {
      await screen.findByRole('heading', {
        name: 'Нужна авторизация в Telegram',
      });
    } else {
      await waitFor(() =>
        expect(
          row('Связи ночей с событиями').getByText(
            'Состояние пока невозможно проверить',
          ),
        ).toBeTruthy(),
      );
    }
    expect(
      screen.queryByRole('button', { name: 'Гостевой предпросмотр' }),
    ).toBeNull();
  },
);

it('final review regression: timezone update must invalidate prior verified event links', async () => {
  const before = {
    ...defaultReads,
    profile,
    calendar: {
      clubId: '42',
      timezone: profile.timezone,
      nights: [night],
      invalidDates: [],
    },
    'booking-rules': {
      clubId: '42',
      timezone: profile.timezone,
      version: '2',
      nights: [night],
    },
    'booking-preview': {
      ...previewWith([verified(night)]),
      timezone: profile.timezone,
    },
  };
  let saved = false;
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (path, options) => {
      if (options?.method === 'PATCH') saved = true;
      const key = String(path).split('/').at(-1) as keyof typeof before;
      const nextProfile = { ...profile, timezone: 'Asia/Tokyo', version: '2' };
      const data =
        saved && key === 'profile'
          ? nextProfile
          : saved && key === 'booking-preview'
            ? {
                ...previewWith([
                  { ...verified(night), eventStatus: 'INVALID_LINK' },
                ]),
                timezone: 'Asia/Tokyo',
              }
            : before[key];
      return Response.json(data);
    });
  render(<ClientAccess initialToken={null} />);
  await screen.findByText('Связь и время события проверены: 1 из 1.');
  fireEvent.change(screen.getByLabelText('Часовой пояс IANA'), {
    target: { value: 'Asia/Tokyo' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
  await screen.findByText('Сохранено');
  expect(
    fetch.mock.calls.filter(([path]) =>
      String(path).endsWith('/booking-preview'),
    ),
  ).toHaveLength(2);
  expect(row('Связи ночей с событиями').queryByText('Настроено')).toBeNull();
});

it('final review regression: retry in flight after a preview 503 must preserve the authorized entry', async () => {
  const reads = { ...defaultReads, 'booking-preview': 503 };
  const fetch = respond(reads);
  render(<ClientAccess initialToken={null} />);
  await screen.findByRole('button', { name: 'Гостевой предпросмотр' });
  fetch.mockImplementation((path) =>
    String(path).endsWith('/booking-preview')
      ? new Promise(() => {})
      : Promise.resolve(Response.json({})),
  );
  fireEvent.click(
    screen.getByRole('button', { name: 'Повторить проверку ночей' }),
  );
  expect(
    screen.queryByRole('button', { name: 'Гостевой предпросмотр' }),
  ).not.toBeNull();
});

it('invalidates shifted night intervals until all timezone reads recover and verifies INVALID_LINK', async () => {
  let changed = false;
  let phase: 'hold' | 'failed' | 'invalid' | 'verified' = 'hold';
  const pending: Array<() => void> = [];
  const shifted = {
    ...night,
    opensAt: '2026-11-08T00:00+09:00',
    closesAt: '2026-11-08T12:00+09:00',
  };
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    const key = String(path).split('/').at(-1)!;
    if (options?.method === 'PATCH') changed = true;
    if (key === 'profile')
      return Response.json(
        changed
          ? { ...profile, timezone: 'Asia/Tokyo', version: '2' }
          : profile,
      );
    if (key === 'layout') return Response.json(baseLayout);
    if (changed && phase === 'hold')
      return new Promise<Response>((resolve) => {
        pending.push(() => resolve(Response.json({}, { status: 503 })));
      });
    if (changed && phase === 'failed')
      return Response.json({}, { status: 503 });
    const timezone = changed ? 'Asia/Tokyo' : profile.timezone;
    const currentNight = changed ? shifted : night;
    const reads: Record<string, unknown> = {
      calendar: {
        clubId: profile.id,
        timezone,
        nights: [currentNight],
        invalidDates: [],
      },
      'booking-rules': {
        clubId: profile.id,
        timezone,
        version: '2',
        nights: [currentNight],
      },
      'booking-preview': {
        ...previewWith([
          {
            ...currentNight,
            eventStatus: phase === 'invalid' ? 'INVALID_LINK' : 'VERIFIED',
          },
        ]),
        timezone,
      },
    };
    return Response.json(reads[key]);
  });
  render(<ClientAccess initialToken={null} />);
  await screen.findByText('Связь и время события проверены: 1 из 1.');
  fireEvent.change(screen.getByLabelText('Часовой пояс IANA'), {
    target: { value: 'Asia/Tokyo' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
  await waitFor(() => expect(pending).toHaveLength(3));
  for (const title of [
    'Календарь и сформированные ночи',
    'Правила бронирования',
    'Связи ночей с событиями',
  ]) {
    expect(row(title).queryByText('Настроено')).toBeNull();
  }
  phase = 'failed';
  pending.forEach((resolve) => resolve());
  await screen.findByText(
    'Не удалось проверить все сведения о ближайших ночах.',
  );
  expect(
    screen.getByRole('button', { name: 'Гостевой предпросмотр' }),
  ).toBeTruthy();
  phase = 'invalid';
  fireEvent.click(
    screen.getByRole('button', { name: 'Повторить проверку ночей' }),
  );
  await screen.findByText('Связь не подтверждает расписание ночи: 1.');
  expect(row('Связи ночей с событиями').queryByText('Настроено')).toBeNull();
  phase = 'verified';
  fireEvent.focus(window);
  await waitFor(() =>
    expect(row('Связи ночей с событиями').getByText('Настроено')).toBeTruthy(),
  );
});

it('keeps a preview refusal through a temporary recheck and restores entry only after an authorized response', async () => {
  const reads = { ...defaultReads };
  respond(reads);
  render(<ClientAccess initialToken={null} />);
  await screen.findByRole('button', { name: 'Гостевой предпросмотр' });
  reads['booking-preview'] = 404;
  fireEvent.focus(window);
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: 'Гостевой предпросмотр' }),
    ).toBeNull(),
  );
  reads['booking-preview'] = 503;
  fireEvent.focus(window);
  await screen.findByText(
    'Не удалось проверить все сведения о ближайших ночах.',
  );
  expect(
    screen.queryByRole('button', { name: 'Гостевой предпросмотр' }),
  ).toBeNull();
  reads['booking-preview'] = defaultReads['booking-preview'];
  fireEvent.click(
    screen.getByRole('button', { name: 'Повторить проверку ночей' }),
  );
  await screen.findByRole('button', { name: 'Гостевой предпросмотр' });
});

it('summarizes saved finance and module settings without declaring guest or reward readiness', async () => {
  const configuredProfile = {
    ...profile,
    permissions: [
      ...profile.permissions,
      'client.club.finance.read',
      'client.club.modules.read',
    ],
  };
  const finance = {
    clubId: '42',
    clientId: '21',
    version: '2',
    canWrite: true,
    bracelets: [{ id: '1', name: 'Гостевой', enabled: true, orderIndex: 0 }],
    revenueGroups: [{ id: '2', name: 'Бар', enabled: true, orderIndex: 0 }],
    revenueArticles: [
      {
        id: '3',
        groupId: '2',
        name: 'Напитки',
        enabled: true,
        orderIndex: 0,
        includeInTotal: true,
        showSeparately: false,
      },
    ],
  };
  const ids = [
    'stamps',
    'early_arrival',
    'badges',
    'prizes',
    'contests',
    'table_loyalty',
    'music',
  ];
  const modules = {
    clubId: '42',
    clientId: '21',
    version: '2',
    name: 'Клуб',
    canWrite: true,
    settingsPresent: true,
    earlyWindowMinutes: 180,
    flags: {
      stampsEnabled: false,
      earlyEnabled: false,
      badgesEnabled: false,
      prizesEnabled: false,
      contestsEnabled: false,
      tablesLoyaltyEnabled: false,
    },
    modules: ids.map((id) => ({
      id,
      status: id === 'early_arrival' ? 'configured' : 'unavailable',
      blockedBy: 'DEC-013',
      savedEnabled: false,
      activationAvailable: false,
    })),
  };
  let unavailable = false;
  const reads = { ...defaultReads, profile: configuredProfile };
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (input, options) => {
      expect(options?.method).toBe('GET');
      const suffix = String(input).replace('/api/client/clubs/42/', '');
      if (['finance/template', 'modules-settings'].includes(suffix)) {
        if (unavailable)
          return new Response(JSON.stringify({ code: 'not_found' }), {
            status: 404,
          });
        return new Response(
          JSON.stringify(suffix === 'finance/template' ? finance : modules),
        );
      }
      return new Response(
        JSON.stringify(reads[suffix as keyof typeof reads] ?? {}),
      );
    });
  render(<ClientAccess initialToken={null} />);
  await waitFor(() =>
    expect(row('Финансовый шаблон').getByText('Настроено')).toBeTruthy(),
  );
  expect(
    row('Дополнительные модули').getByText(
      'Правило настроено · запуск недоступен',
    ),
  ).toBeTruthy();
  expect(
    row('Дополнительные модули').getByText(/180 мин после начала ночи/),
  ).toBeTruthy();
  expect(screen.getByText(/Гостевое бронирование выключено/)).toBeTruthy();
  unavailable = true;
  fireEvent(window, new Event('focus'));
  await waitFor(() =>
    expect(
      row('Финансовый шаблон').getByText('Состояние пока невозможно проверить'),
    ).toBeTruthy(),
  );
  expect(
    row('Дополнительные модули').queryByText(
      'Правило настроено · запуск недоступен',
    ),
  ).toBeNull();
  expect(
    fetch.mock.calls.every(([, options]) => options?.method === 'GET'),
  ).toBe(true);
});
