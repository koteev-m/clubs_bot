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
  await screen.findByRole('heading', { name: 'Пригласить управляющего' });
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
  const row = (await screen.findByText(source.displayName)).closest('article')!;
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
