import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClientAccess from './ClientAccess';
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
vi.mock('../auth/hooks/useInitData', () => ({
  useInitData: () => ({ initData: 'synthetic-staff' }),
}));
beforeEach(() => {
  history.replaceState(null, '', '/app?mode=client');
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
it.each(['expired', 'revoked'])(
  'shows %s staff invitation without an acceptance or grant action',
  async (status) => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      Response.json({
        id: '10',
        clientId: '20',
        clubId: '100',
        clientName: 'Client A',
        clubName: 'Club A1',
        role: 'ENTRY_MANAGER',
        acceptanceMode: 'staff',
        version: '1',
        status,
        expiresAt: '2026-11-08T00:00:00Z',
        candidate: null,
        verificationCode: null,
        availableActions: [],
      }),
    );
    render(<ClientAccess initialToken={'s'.repeat(43)} />);
    await screen.findByRole('heading', { name: 'Приглашение в команду' });
    expect(
      screen.queryByRole('button', { name: 'Принять приглашение' }),
    ).toBeNull();
    expect(
      await screen.findByText(
        'Обратитесь к отправителю за новым приглашением.',
      ),
    ).toBeTruthy();
    expect(
      fetch.mock.calls.every(
        ([, options]) =>
          !options?.headers ||
          !(options.headers as Record<string, string>)['Idempotency-Key'],
      ),
    ).toBe(true);
  },
);
it('shows a bound candidates code while waiting without claiming that permissions were granted', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    Response.json({
      id: '10',
      clientId: '20',
      clubId: '100',
      clientName: 'Client A',
      clubName: 'Club A1',
      role: 'MANAGER',
      acceptanceMode: 'staff',
      version: '2',
      status: 'claimed',
      expiresAt: '2026-11-08T00:00:00Z',
      candidate: {
        userId: '9',
        telegramUserId: '880062004',
        username: null,
        displayName: null,
      },
      verificationCode: '123456',
      availableActions: [],
    }),
  );
  history.replaceState(
    null,
    '',
    '/app?mode=client&screen=invitation&resource=10',
  );
  render(<ClientAccess initialToken={null} />);
  await screen.findByRole('heading', {
    name: 'Ожидаем подтверждение владельца клуба',
  });
  expect(screen.getByText('123456')).toBeTruthy();
  expect(screen.getByText(/Клубные разрешения пока не выданы/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Открыть кабинет' })).toBeNull();
});

it('renews an expired manager code only after the bound recipient confirms again', async () => {
  const invitation = {
    id: '10',
    clientId: '20',
    clubId: '100',
    clubName: 'Club A1',
    clientName: 'Client A',
    role: 'CLUB_MANAGER',
    acceptanceMode: 'manager',
    version: '2',
    status: 'claimed',
    expiresAt: '2026-11-30T00:00:00Z',
    candidate: { userId: '9', telegramUserId: '880062004' },
    verificationCode: null,
    availableActions: ['claim'],
  };
  history.replaceState(
    null,
    '',
    '/app?mode=client&screen=manager-invitation&resource=10',
  );
  const calls: RequestInit[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (url, options) => {
    if (String(url).endsWith('/10/claim')) {
      calls.push(options!);
      return Response.json({
        ...invitation,
        version: '3',
        verificationCode: '621078',
        availableActions: [],
      });
    }
    return Response.json(invitation);
  });
  render(<ClientAccess initialToken={null} />);
  const renew = await screen.findByRole('button', {
    name: 'Обновить подтверждение',
  });
  expect(calls).toHaveLength(0);
  expect(screen.queryByRole('button', { name: 'Открыть кабинет' })).toBeNull();
  fireEvent.click(renew);
  expect(await screen.findByText('621078')).toBeTruthy();
  expect(calls).toHaveLength(1);
  expect(JSON.parse(calls[0].body as string)).toEqual({
    expectedVersion: '2',
  });
  expect(
    screen.queryByRole('button', { name: 'Обновить подтверждение' }),
  ).toBeNull();
});

it.each(['staff', 'manager'] as const)(
  'previews a direct %s link and opens the cabinet only after explicit acceptance',
  async (kind) => {
    const token = 'a'.repeat(43);
    const invitation = {
      id: '13',
      clubId: '7',
      version: '1',
      status: 'issued',
      acceptanceFlow: 'direct',
      acceptanceMode: kind,
      role: kind === 'manager' ? 'NETWORK_MANAGER' : 'ENTRY_MANAGER',
      clientId: '20',
      clientName: 'Организация',
      clubName: 'Север',
      clubs:
        kind === 'manager'
          ? [
              { id: '7', name: 'Север' },
              { id: '8', name: 'Юг' },
            ]
          : undefined,
      expiresAt: '2026-12-01T00:00:00Z',
      availableActions: ['claim'],
    };
    const claims: object[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
      if (String(path).endsWith('/claim')) {
        claims.push(JSON.parse(String(options?.body)));
        return Response.json({
          ...invitation,
          status: 'accepted',
          version: '2',
          availableActions: [],
        });
      }
      if (!String(path).endsWith('/resolve'))
        return Response.json({ code: 'service_unavailable' }, { status: 503 });
      return Response.json(invitation);
    });
    render(
      <ClientAccess
        initialToken={token}
        invitationKind={kind === 'manager' ? 'manager' : 'client'}
      />,
    );
    await screen.findByRole('heading', { name: 'Организация' });
    expect(
      screen.getByText(
        kind === 'manager' ? 'Заведения: Север, Юг' : 'Заведения: Север',
      ),
    ).toBeTruthy();
    expect(claims).toHaveLength(0);
    fireEvent.click(
      screen.getByRole('button', { name: 'Принять приглашение' }),
    );
    await waitFor(() =>
      expect(location.search).toContain(
        kind === 'manager'
          ? 'screen=client-space&resource=20'
          : 'screen=club&resource=7',
      ),
    );
    expect(claims).toEqual([{ token, expectedVersion: '1' }]);
    expect(document.querySelector('.ob-code')).toBeNull();
  },
);
