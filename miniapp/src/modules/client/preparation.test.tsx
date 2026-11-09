import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClubCabinet from './ClubCabinet';
import { PreparationView } from './preparation.types';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const profile = {
  id: '42',
  clientId: '21',
  clientName: 'Моя организация',
  name: 'Север',
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Адрес',
  unit: '',
  version: '1',
  savedAt: '2026-11-09T00:00:00Z',
  permissions: [
    'client.club.read',
    'client.club.profile.write',
    'client.club.team.manage',
  ],
  availableActions: ['update'],
};
const initial: PreparationView = {
  clubId: '42',
  clientId: '21',
  name: 'Север',
  version: '1',
  status: 'unassigned',
  published: false,
  reason: null,
  assignee: null,
  invitation: null,
  canManage: true,
  canSubmit: false,
  canInvite: true,
  canWrite: true,
  configurationChanged: false,
};
const staff = {
  userId: '9',
  displayName: 'Анна',
  username: null,
  owner: false,
  active: true,
};
const props = {
  credential: 'synthetic',
  id: '42',
  back: vi.fn(),
  open: vi.fn(),
  reportDirty: vi.fn(),
  reportPending: vi.fn(),
};
function deferred() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const reply = (data: unknown, status = 200) =>
  Response.json(data, { status });
const team = {
  clubId: '42',
  clientId: '21',
  version: '3',
  canWrite: true,
  roles: ['ENTRY_MANAGER', 'MANAGER', 'CLUB_ADMIN'].map((code) => ({
    code,
    permissions: ['client.club.read'],
    assignable: true,
  })),
  members: [],
  invitations: [],
};
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  props.open.mockClear();
  props.back.mockClear();
});
function api(
  get: () => PreparationView = () => initial,
  patch?: (
    body: Record<string, string>,
    options: RequestInit,
  ) => Promise<Response>,
) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (path, options) => {
      if (String(path).endsWith('/profile')) return reply(profile);
      if (String(path).endsWith('/team')) return reply(team);
      if (String(path).endsWith('/calendar'))
        return reply({
          clubId: '42',
          timezone: profile.timezone,
          nights: [],
          invalidDates: [],
        });
      if (String(path).endsWith('/preparation'))
        return options?.method === 'PATCH' && patch
          ? patch(JSON.parse(String(options.body)), options)
          : reply(get());
      return reply({}, 404);
    });
}
it('offers three creation choices and persists self selection before opening settings', async () => {
  let saved = initial;
  const fetch = api(
    () => saved,
    async (body) => {
      expect(body).toEqual({ action: 'self', expectedVersion: '1' });
      saved = {
        ...initial,
        version: '2',
        status: 'in_progress',
        assignee: { ...staff, owner: true },
        canSubmit: true,
      };
      return reply(saved);
    },
  );
  render(<ClubCabinet {...props} mode="choice" />);
  await screen.findByRole('button', { name: 'Назначить позже' });
  fireEvent.click(
    screen.getByRole('button', { name: 'Настроить самостоятельно' }),
  );
  await waitFor(() =>
    expect(props.open).toHaveBeenLastCalledWith('settings'),
  );
  expect(
    fetch.mock.calls.filter(([, options]) => options?.method === 'PATCH'),
  ).toHaveLength(1);
});
it('delegates through a full package invitation and reports pending instead of ownership or success', async () => {
  let saved = initial;
  api(
    () => saved,
    async (body) => {
      expect(body).toEqual({
        action: 'invite',
        expectedVersion: '1',
        expectedTeamVersion: '3',
        recipientLabel: 'Анна',
      });
      saved = {
        ...initial,
        version: '2',
        status: 'invitation_pending',
        invitation: { id: '8', status: 'issued', recipientLabel: 'Анна' },
      };
      return reply({
        ...saved,
        createdInvitationId: '8',
        invitationLink: 'https://t.me/synthetic_bot?startapp=ci_synthetic',
      });
    },
  );
  render(<ClubCabinet {...props} mode="choice" />);
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'Назначить ответственного за подготовку',
    }),
  );
  fireEvent.change(
    await screen.findByLabelText('Имя приглашённого ответственного'),
    { target: { value: 'Анна' } },
  );
  fireEvent.click(
    screen.getByRole('button', { name: 'Пригласить ответственного' }),
  );
  await screen.findByLabelText('Ссылка ответственного');
  expect(
    screen.getByText('Приглашение ожидает принятия или подтверждения'),
  ).toBeTruthy();
  expect(
    screen.getByText(
      /без управления сотрудниками, денежных операций и прав собственности/,
    ),
  ).toBeTruthy();
  expect(
    screen.queryByRole('button', { name: 'Принять подготовку' }),
  ).toBeNull();
});
it('requires a reason to return submitted work and does not equate acceptance with publication', async () => {
  let saved: PreparationView = {
    ...initial,
    status: 'submitted',
    assignee: staff,
    version: '4',
  };
  api(
    () => saved,
    async (body) => {
      expect(body).toEqual({
        action: 'return',
        expectedVersion: '4',
        reason: 'Исправьте часы',
      });
      saved = {
        ...saved,
        status: 'returned',
        version: '5',
        reason: body.reason,
      };
      return reply(saved);
    },
  );
  render(<ClubCabinet {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Вернуть на доработку' }),
  );
  expect(
    screen
      .getByRole('button', { name: 'Сохранить возврат на доработку' })
      .hasAttribute('disabled'),
  ).toBe(true);
  fireEvent.change(screen.getByLabelText('Причина доработки'), {
    target: { value: 'Исправьте часы' },
  });
  fireEvent.click(
    screen.getByRole('button', { name: 'Сохранить возврат на доработку' }),
  );
  await screen.findByText('Возвращено на доработку');
  expect(screen.getByText('Не опубликован')).toBeTruthy();
  expect(screen.getByText(/Причина доработки: Исправьте часы/)).toBeTruthy();
});
it('keeps an unknown invitation and its key through Telegram foreground and a temporary profile failure', async () => {
  const attempts: RequestInit[] = [];
  let failProfile = false;
  const base = api(
    () => initial,
    async (_body, options) => {
      attempts.push(options);
      return attempts.length === 1
        ? reply({}, 503)
        : reply({
            ...initial,
            version: '2',
            status: 'invitation_pending',
            invitation: { id: '8', status: 'issued', recipientLabel: 'Анна' },
            createdInvitationId: '8',
          });
    },
  );
  const handler = base.getMockImplementation()!;
  base.mockImplementation(async (path, options) =>
    failProfile && String(path).endsWith('/profile')
      ? reply({}, 503)
      : handler(path, options),
  );
  render(<ClubCabinet {...props} mode="choice" />);
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'Назначить ответственного за подготовку',
    }),
  );
  fireEvent.change(
    await screen.findByLabelText('Имя приглашённого ответственного'),
    { target: { value: 'Анна' } },
  );
  fireEvent.click(
    screen.getByRole('button', { name: 'Пригласить ответственного' }),
  );
  await screen.findByText('Результат операции пока неизвестен');
  failProfile = true;
  fireEvent.focus(window);
  await screen.findByText('Не удалось загрузить данные. Повторите попытку.');
  expect(
    screen
      .getByLabelText('Имя приглашённого ответственного')
      .getAttribute('value'),
  ).toBe('Анна');
  fireEvent.click(
    screen.getByRole('button', { name: 'Повторить ту же операцию' }),
  );
  await screen.findByText(
    /Приглашение сохранено. Ссылка повторно не показывается/,
  );
  expect(attempts).toHaveLength(2);
  expect(attempts[1].body).toBe(attempts[0].body);
  expect(attempts[1].headers).toEqual(attempts[0].headers);
  expect(props.open).not.toHaveBeenCalled();
});
it('fences a same-version late owner command after staff access is revoked', async () => {
  let saved: PreparationView = {
    ...initial,
    status: 'submitted',
    assignee: staff,
  };
  const pending = deferred();
  api(
    () => saved,
    async () => pending.promise,
  );
  render(<ClubCabinet {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Принять подготовку' }),
  );
  saved = { ...saved, assignee: { ...staff, active: false } };
  fireEvent.focus(window);
  await screen.findByText(/Доступ ответственного отозван/);
  await act(async () =>
    pending.resolve(
      reply({ ...saved, version: '2', status: 'accepted', assignee: staff }),
    ),
  );
  expect(screen.queryByText('Принято владельцем')).toBeNull();
  expect(screen.getByText(/Доступ ответственного отозван/)).toBeTruthy();
});
it('clears the previous club on switch and rejects substituted client IDs', async () => {
  const pending = deferred();
  const fetch = api(() => initial);
  const handler = fetch.getMockImplementation()!;
  fetch.mockImplementation(async (path, options) =>
    String(path).includes('/42/preparation')
      ? pending.promise
      : String(path).includes('/99/profile')
        ? reply({ ...profile, id: '99', clientId: '22', name: 'Другой клуб' })
        : String(path).includes('/99/preparation')
          ? reply({ ...initial, clubId: '99', clientId: '21' })
          : handler(path, options),
  );
  const view = render(<ClubCabinet {...props} />);
  await screen.findByRole('heading', { name: 'Север', level: 1 });
  view.rerender(<ClubCabinet {...props} id="99" />);
  await screen.findByRole('heading', { name: 'Другой клуб', level: 1 });
  await act(async () => pending.resolve(reply(initial)));
  expect(
    screen.queryByRole('heading', { name: 'Север', level: 1 }),
  ).toBeNull();
  expect(
    screen.queryByRole('button', {
      name: 'Назначить ответственного за подготовку',
    }),
  ).toBeNull();
});
it('keeps owner controls separate from readonly configuration and honest missing reports', async () => {
  api(() => ({
    ...initial,
    canManage: false,
    canInvite: false,
    canWrite: false,
  }));
  render(<ClubCabinet {...props} />);
  await screen.findByText('Данных пока нет.');
  expect(
    screen.queryByRole('button', { name: 'Принять подготовку' }),
  ).toBeNull();
  expect(
    screen.queryByRole('button', {
      name: 'Назначить ответственного за подготовку',
    }),
  ).toBeNull();
  expect(
    screen.queryByRole('heading', { name: 'Залы и отдельные схемы' }),
  ).toBeNull();
  fireEvent.click(
    await screen.findByRole('button', { name: /^Залы, зоны и столы/ }),
  );
  expect(props.open).toHaveBeenCalledWith('layout');
});

it('removes the one-time invitation link when current invitation authority is lost', async () => {
  let saved = initial;
  api(
    () => saved,
    async () => {
      saved = {
        ...initial,
        version: '2',
        status: 'invitation_pending',
        invitation: { id: '8', status: 'issued', recipientLabel: 'Анна' },
      };
      return reply({
        ...saved,
        createdInvitationId: '8',
        invitationLink: 'https://t.me/synthetic_bot?startapp=ci_synthetic',
      });
    },
  );
  render(<ClubCabinet {...props} mode="choice" />);
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'Назначить ответственного за подготовку',
    }),
  );
  fireEvent.change(
    await screen.findByLabelText('Имя приглашённого ответственного'),
    { target: { value: 'Анна' } },
  );
  fireEvent.click(
    screen.getByRole('button', { name: 'Пригласить ответственного' }),
  );
  await screen.findByLabelText('Ссылка ответственного');
  saved = { ...saved, canManage: false, canInvite: false, canWrite: false };
  fireEvent.focus(window);
  await waitFor(() =>
    expect(screen.queryByLabelText('Ссылка ответственного')).toBeNull(),
  );
  expect(
    screen.queryByRole('button', { name: 'Скопировать ссылку' }),
  ).toBeNull();
});

it('does not claim ownership when staff opens the creation-choice URL', async () => {
  api(() => ({
    ...initial,
    canManage: false,
    canInvite: false,
    canWrite: false,
  }));
  render(<ClubCabinet {...props} mode="choice" />);
  await screen.findByText('Ответственный не назначен');
  expect(screen.queryByText(/Право собственности остаётся у вас/)).toBeNull();
  expect(
    screen.queryByRole('button', { name: 'Настроить самостоятельно' }),
  ).toBeNull();
});
