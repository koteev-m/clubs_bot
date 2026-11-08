import { cleanup, render, screen } from '@testing-library/react';
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
      screen.queryByRole('button', { name: 'Подтвердить Telegram-аккаунт' }),
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
