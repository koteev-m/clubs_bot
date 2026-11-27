import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClientAccess from './ClientAccess';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
vi.mock('../auth/hooks/useInitData', () => ({
  useInitData: () => ({ initData: 'synthetic-manager' }),
}));
beforeEach(() => {
  history.replaceState({}, '', '/app?mode=client&screen=managers&resource=42');
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const assignment = {
  id: '035e29a6-f687-412f-ab80-260326eab060',
  role: 'CLUB_MANAGER',
  userId: '391',
  displayName: 'Назначение клуба',
  version: '2',
  canRevoke: true,
};
const roster = {
  clubId: '42',
  clientId: '101',
  version: '7',
  canWrite: true,
  name: 'Тестовый клуб',
  roles: ['CLUB_MANAGER'],
  invitations: [],
  assignments: [assignment],
  legacyAssignments: [],
};

it('uses the availability of this source and does not offer to revoke a protected assignment', async () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    Response.json({
      ...roster,
      assignments: [{ ...assignment, canRevoke: false }],
    }),
  );
  render(<ClientAccess initialToken={null} />);
  const row = (await screen.findByText(assignment.displayName)).closest(
    'article',
  )!;
  expect(
    within(row).queryByRole('button', { name: 'Отозвать назначение' }),
  ).toBeNull();
});

it('rechecks the roster after an operation is refused and keeps independently allowed actions in the open cabinet', async () => {
  let refused = false;
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (_url, options) => {
      if (options?.method === 'POST') {
        refused = true;
        return Response.json({ code: 'forbidden' }, { status: 403 });
      }
      return Response.json({
        ...roster,
        assignments: [{ ...assignment, canRevoke: !refused }],
      });
    });
  render(<ClientAccess initialToken={null} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Отозвать назначение' }),
  );
  fireEvent.change(screen.getByLabelText('Причина отзыва'), {
    target: { value: 'Отзыв' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Подтвердить отзыв' }));
  await waitFor(() =>
    expect(
      fetch.mock.calls.filter(([, options]) => options?.method === 'GET'),
    ).toHaveLength(2),
  );
  await screen.findByRole('button', { name: 'Пригласить управляющего' });
  expect(
    screen.queryByRole('button', { name: 'Отозвать назначение' }),
  ).toBeNull();
  expect(screen.queryByText('Нужна авторизация в Telegram')).toBeNull();
  expect(screen.getByRole('alert').textContent).toMatch(/недоступн|не разреш/);
});

it('revokes only the selected historical club assignment and preserves the independent manager row for the same person', async () => {
  const source = {
    id: '83',
    role: 'CLUB_ADMIN',
    userId: assignment.userId,
    displayName: 'Прежний сотрудник',
    version: '5',
    canRevoke: true,
  };
  const writes: object[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, options) => {
    if (options?.method === 'POST')
      writes.push(JSON.parse(String(options.body)));
    return Response.json({
      ...roster,
      legacyAssignments: writes.length ? [] : [source],
    });
  });
  render(<ClientAccess initialToken={null} />);
  const row = (await screen.findByText(source.displayName)).closest(
    'article',
  )!;
  fireEvent.click(within(row).getByRole('button', { name: /Отозвать/ }));
  fireEvent.change(screen.getByLabelText('Причина отзыва'), {
    target: { value: 'Завершил работу' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Подтвердить отзыв' }));
  await waitFor(() =>
    expect(writes).toEqual([
      {
        action: 'revoke_legacy_assignment',
        expectedVersion: '7',
        assignmentId: '83',
        expectedAssignmentVersion: '5',
        confirmed: true,
        reason: 'Завершил работу',
      },
    ]),
  );
  await waitFor(() =>
    expect(screen.queryByText(source.displayName)).toBeNull(),
  );
  expect(screen.getByText(assignment.displayName)).toBeTruthy();
});

it('guards a role-only invitation draft until the sender discards it', async () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    Response.json({
      ...roster,
      roles: ['CLUB_MANAGER', 'NETWORK_MANAGER'],
    }),
  );
  const discard = vi.spyOn(window, 'confirm').mockReturnValue(false);
  render(<ClientAccess initialToken={null} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Пригласить управляющего' }),
  );
  fireEvent.change(await screen.findByLabelText('Роль управляющего'), {
    target: { value: 'NETWORK_MANAGER' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
  expect(discard).toHaveBeenCalledOnce();
  expect(
    (screen.getByLabelText('Роль управляющего') as HTMLSelectElement).value,
  ).toBe('NETWORK_MANAGER');
  discard.mockReturnValue(true);
  fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
  expect(
    await screen.findByRole('heading', { name: 'Управляющие' }),
  ).toBeTruthy();
});

it('replaces the sent manager form and opens its receipt on browser Forward without issuing another link', async () => {
  const link = 'https://t.me/TestBot?startapp=cm_' + 'a'.repeat(43);
  const invitation = { id: '18', clientId: roster.clientId, clubId: roster.clubId,
    role: 'CLUB_MANAGER', acceptanceMode: 'manager', status: 'issued',
    recipientLabel: 'Анна', availableActions: ['revoke'], expiresAt: '2026-12-01T12:00:00Z' };
  let writes = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, options) => {
    if (options?.method === 'POST') writes++;
    return Response.json(writes ? { ...roster, version: '8', invitations: [invitation],
      createdInvitationId: '18', invitationLink: link } : roster);
  });
  render(<ClientAccess initialToken={null} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Пригласить управляющего' }));
  fireEvent.change(await screen.findByLabelText('Имя получателя'), { target: { value: 'Анна' } });
  fireEvent.click(screen.getByRole('button', { name: 'Создать приглашение' }));
  await screen.findByDisplayValue(link);
  expect(location.search).toContain('screen=managers&resource=42&invitation=18');
  fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
  await screen.findByRole('heading', { name: 'Управляющие' });
  act(() => history.forward());
  await screen.findByRole('heading', { name: 'Приглашение управляющего' });
  expect(location.search).toContain('invitation=18');
  expect(screen.queryByLabelText('Имя получателя')).toBeNull();
  expect(screen.queryByDisplayValue(link)).toBeNull();
  expect(writes).toBe(1);
});

it('returns from a directly opened receipt to the roster and then the club', async () => {
  history.replaceState({}, '', '/app?mode=client&screen=managers&resource=42&invitation=18');
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => Response.json(roster));
  render(<ClientAccess initialToken={null} />);
  await screen.findByText(/Ссылка больше недоступна/);
  fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
  await screen.findByRole('heading', { name: 'Управляющие' });
  expect(location.search).toBe('?mode=client&screen=managers&resource=42');
  fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
  expect(location.search).toBe('?mode=client&screen=club&resource=42');
});

it('confirms only the selected configuration section and shows the package when inviting a manager', async () => {
  const permission = 'client.club.night.create';
  const writing = 'client.club.operations.deposit.record';
  const writes: object[] = [];
  const team = {
    ...roster,
    roles: ['FLOOR_MANAGER', 'ENTRANCE_MANAGER', 'FINANCE_MANAGER', 'PROMOTER'],
    rolePermissions: { FLOOR_MANAGER: [writing] },
    delegablePermissions: ['client.club.calendar.write', permission],
  };
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, options) => {
    if (options?.method === 'POST')
      writes.push(JSON.parse(String(options.body)));
    return Response.json({
      ...team,
      version: writes.length ? '8' : '7',
      assignments: [
        {
          ...assignment,
          role: 'FLOOR_MANAGER',
          delegations: writes.length ? [{ id: 'grant-1', version: '1', permission }] : [],
        },
      ],
    });
  });

  render(<ClientAccess initialToken={null} />);
  await screen.findByRole('heading', { name: 'Команда клуба' });
  fireEvent.click(screen.getByRole('button', { name: /Выдать доступ.*Формирование операционной ночи/ }));
  await screen.findByRole('button', { name: /Отозвать доступ.*Формирование операционной ночи/ });
  expect(writes).toEqual([{
    action: 'delegate',
    expectedVersion: '7',
    assignmentId: assignment.id,
    expectedAssignmentVersion: assignment.version,
    confirmed: true,
    permission,
  }]);
  expect(screen.getByRole('button', { name: /Выдать доступ.*Календарь и расписание/ })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Пригласить сотрудника' }));
  await screen.findByText('Запись полученного депозита');
  expect((screen.getByLabelText('Роль сотрудника') as HTMLSelectElement).value).toBe('FLOOR_MANAGER');
});
