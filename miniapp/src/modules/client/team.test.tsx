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
import ClubTeam from './ClubTeam';
import type { TeamView } from './team.types';
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const base: TeamView = {
  clubId: '100',
  clientId: '20',
  name: 'Club A1',
  version: '1',
  canWrite: true,
  roles: [
    {
      code: 'ENTRY_MANAGER',
      permissions: ['client.club.read'],
      assignable: true,
    },
    {
      code: 'MANAGER',
      permissions: ['client.club.read', 'client.club.profile.write'],
      assignable: true,
    },
    {
      code: 'CLUB_ADMIN',
      permissions: ['client.club.read', 'client.club.finance.read'],
      assignable: false,
    },
  ],
  members: [],
  invitations: [],
};
const props = {
  credential: 'synthetic',
  id: '100',
  back: vi.fn(),
  reportDirty: vi.fn(),
  reportPending: vi.fn(),
};
const response = (value: unknown, status = 200) =>
  Response.json(value, { status });
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
async function start() {
  fireEvent.change(await screen.findByLabelText('Подпись получателя'), {
    target: { value: 'Colleague' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Создать приглашение' }));
}
it('keeps one-time links in memory and repeats an unknown issue with its original body and key', async () => {
  const calls: RequestInit[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_path, options) => {
    if (options?.method === 'POST') {
      calls.push(options);
      return calls.length === 1
        ? response({}, 503)
        : response({
            ...base,
            version: '2',
            createdInvitationId: '7',
            invitationLink:
              'https://t.me/synthetic_bot?startapp=ci_' + 's'.repeat(43),
          });
    }
    return response(base);
  });
  render(<ClubTeam {...props} />);
  await start();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить ту же операцию' }),
  );
  expect(await screen.findByLabelText('Ссылка приглашения')).toBeTruthy();
  expect(calls[1].headers).toEqual(calls[0].headers);
  expect(calls[1].body).toBe(calls[0].body);
  expect(JSON.parse(calls[0].body as string)).toEqual({
    action: 'invite',
    expectedVersion: '1',
    recipientLabel: 'Colleague',
    role: 'ENTRY_MANAGER',
  });
  expect(sessionStorage.length).toBe(0);
  expect(localStorage.length).toBe(0);
});
it('requires reason and explicit confirmation after previewing a replacement package', async () => {
  const member = {
    userId: '9',
    displayName: 'Member',
    username: null,
    role: 'ENTRY_MANAGER' as const,
    owner: false,
    version: '1',
    active: true,
    membershipActive: true,
    permissions: ['client.club.read'],
    canChange: true,
    canDisable: true,
  };
  const view = { ...base, members: [member] };
  let body: unknown;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_path, options) => {
    if (options?.method === 'POST') {
      body = JSON.parse(options.body as string);
      return response({ ...view, version: '2' });
    }
    return response(view);
  });
  render(<ClubTeam {...props} />);
  fireEvent.click(
    await screen.findByRole('button', { name: 'Изменить роль №9' }),
  );
  const form = within(
    screen.getByRole('region', { name: 'Подтверждение изменения доступа' }),
  );
  const confirm = form.getByRole<HTMLButtonElement>('button', {
    name: 'Подтвердить изменение доступа',
  });
  expect(confirm.disabled).toBe(true);
  fireEvent.change(form.getByLabelText('Новая роль'), {
    target: { value: 'MANAGER' },
  });
  fireEvent.change(form.getByLabelText('Причина изменения доступа'), {
    target: { value: 'New responsibilities' },
  });
  expect(confirm.disabled).toBe(true);
  fireEvent.click(form.getByRole('checkbox'));
  fireEvent.click(confirm);
  await waitFor(() =>
    expect(body).toEqual({
      action: 'change_role',
      expectedVersion: '1',
      confirmed: true,
      userId: '9',
      expectedMemberVersion: '1',
      reason: 'New responsibilities',
      role: 'MANAGER',
    }),
  );
});
it('fences a late issue after team denial and reauthorization', async () => {
  let resolve!: (value: Response) => void;
  let allowed = true;
  const pending = new Promise<Response>((done) => {
    resolve = done;
  });
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_path, options) =>
    options?.method === 'POST'
      ? pending
      : response(allowed ? base : { code: 'not_found' }, allowed ? 200 : 404),
  );
  render(<ClubTeam {...props} />);
  await start();
  allowed = false;
  fireEvent.focus(window);
  await screen.findByText(/Доступ утрачен/);
  allowed = true;
  fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
  await screen.findByLabelText('Подпись получателя');
  await act(async () =>
    resolve(
      response({
        ...base,
        version: '2',
        invitationLink:
          'https://t.me/synthetic_bot?startapp=ci_' + 's'.repeat(43),
      }),
    ),
  );
  expect(screen.queryByLabelText('Ссылка приглашения')).toBeNull();
  expect(screen.queryByText('Сохранено')).toBeNull();
});
