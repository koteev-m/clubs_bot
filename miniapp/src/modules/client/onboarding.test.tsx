import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ClientAccess from './ClientAccess';
import PlatformOnboarding from './PlatformOnboarding';
import { ClubApplication, Invitation } from './onboarding.types';

const auth = vi.hoisted(() => ({ initData: 'synthetic-client-a' }));
vi.mock('../auth/hooks/useInitData', () => ({ useInitData: () => auth }));
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({
    initDataUnsafe: { user: { first_name: 'Synthetic Candidate' } },
    BackButton: {
      show: vi.fn(),
      hide: vi.fn(),
      onClick: vi.fn(),
      offClick: vi.fn(),
    },
  }),
}));
const token = 'a'.repeat(43);
const invitation: Invitation = {
  id: '10',
  clientId: '20',
  clientName: 'Synthetic Client',
  recipientLabel: 'Candidate',
  status: 'issued',
  version: '1',
  expiresAt: '2026-11-04T12:00:00Z',
  candidate: null,
  verificationCode: null,
  availableActions: ['claim'],
};
const draft: ClubApplication = {
  id: '30',
  clientId: '20',
  clientName: 'Synthetic Client',
  authorUserId: '90',
  status: 'draft',
  version: '1',
  venueName: 'Synthetic Venue',
  countryCode: 'RU',
  city: 'Test City',
  address: 'Test Street 1',
  unit: '',
  timezone: 'Europe/Moscow',
  comment: '',
  previousApplicationId: null,
  submittedAt: null,
  decisionReason: null,
  history: [],
  availableActions: ['update', 'submit', 'cancel'],
};
const response = (data: unknown, status = 200) =>
  Promise.resolve({
    ok: status < 400,
    status,
    json: async () => data,
  } as Response);
const requestPath = (input: RequestInfo | URL) => String(input);

describe('connected onboarding screens', () => {
  beforeEach(() => {
    auth.initData = 'synthetic-client-a';
    window.history.replaceState({}, '', '/app?mode=client');
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('preview only resolves, explicit claim shows a human code and never grants itself', async () => {
    const claimed: Invitation = {
      ...invitation,
      status: 'claimed',
      version: '2',
      candidate: {
        userId: '90',
        telegramUserId: '880062001',
        displayName: 'Synthetic',
        username: null,
      },
      verificationCode: 'ABC123',
      availableActions: [],
    };
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((path) =>
        response(
          String(path).endsWith('/claim') || String(path).endsWith('/10')
            ? claimed
            : invitation,
        ),
      );
    render(<ClientAccess initialToken={token} />);
    await screen.findByText('Synthetic Client');
    expect(
      fetch.mock.calls.every(
        (call) => !requestPath(call[0]).endsWith('/claim'),
      ),
    ).toBe(true);
    const claim = screen.getByRole('button', {
      name: 'Принять приглашение',
    }) as HTMLButtonElement;
    expect(claim.disabled).toBe(false);
    expect(screen.queryByRole('checkbox')).toBeNull();
    fireEvent.click(claim);
    await screen.findByText('ABC123');
    expect(screen.getByText('Ожидаем подтверждение платформы')).toBeTruthy();
    expect(
      screen.queryByRole('button', { name: 'Предоставить доступ' }),
    ).toBeNull();
    const command = fetch.mock.calls.find((call) =>
      requestPath(call[0]).endsWith('/claim'),
    )!;
    expect(JSON.parse(String(command[1]?.body))).toEqual({
      token,
      expectedVersion: '1',
    });
    expect(
      (command[1]?.headers as Record<string, string>)['Idempotency-Key'],
    ).toBeTruthy();
    expect(window.location.href).not.toContain(token);
  });

  it('shows only authorized client and clubs, and requires server create authority', async () => {
    const approved: ClubApplication = {
      ...draft,
      status: 'approved',
      version: '4',
      availableActions: [],
      history: [
        { status: 'approved', at: '2026-10-28T12:00:00Z', reason: null },
      ],
    };
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((path) => {
      if (String(path).endsWith('/context'))
        return response({
          clients: [
            {
              id: '20',
              name: 'Client A',
              clubs: [{ id: '100', name: 'Club A1' }],
            },
          ],
        });
      if (String(path).endsWith('/onboarding'))
        return response({
          id: '20',
          name: 'Client A',
          permissions: ['client.application.manage'],
          clubs: [{ id: '100', name: 'Club A1' }],
        });
      if (String(path).endsWith('/search'))
        return response({ items: [approved], nextCursor: null });
      return response(approved);
    });
    render(<ClientAccess initialToken={null} />);
    await screen.findByRole('button', { name: /Club A1/ });
    expect(screen.queryByText('Club A2')).toBeNull();
    fireEvent.click(
      await screen.findByRole('button', { name: /Synthetic Venue/ }),
    );
    await screen.findByText('Подключение одобрено.');
    expect(screen.queryByRole('button', { name: /Создать.*клуб/i })).toBeNull();
    expect(
      fetch.mock.calls.some((call) =>
        /\/api\/clubs/.test(requestPath(call[0])),
      ),
    ).toBe(false);
  });

  it('keeps an old draft readable and offers reuse without submit or checkbox', async () => {
    window.history.replaceState(
      {},
      '',
      '/app?mode=client&screen=application&resource=30',
    );
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => response(draft));
    render(<ClientAccess initialToken={null} />);
    await screen.findByText('Test Street 1');
    expect(
      screen.getByRole('button', { name: 'Использовать данные для создания' }),
    ).toBeTruthy();
    expect(
      screen.queryByRole('button', { name: 'Отправить заявку' }),
    ).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });

  it('unknown mutation outcome retries the identical key and payload after lost response', async () => {
    window.history.replaceState(
      {},
      '',
      '/app?mode=platform-owner&screen=invite',
    );
    let attempts = 0;
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
      attempts++;
      return attempts === 1
        ? Promise.reject(new Error('lost response'))
        : response({ ...invitation, availableActions: ['revoke', 'reissue'] });
    });
    render(<PlatformOnboarding credential="synthetic-owner" />);
    fireEvent.change(screen.getByLabelText('Название клиента или компании'), {
      target: { value: 'Synthetic Client' },
    });
    fireEvent.change(screen.getByLabelText('Имя получателя'), {
      target: { value: 'Candidate' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Создать ссылку' }));
    await screen.findByText(
      'Ответ сервера не получен, результат нужно проверить.',
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Проверить результат' }),
    );
    await screen.findByText(/Первоначальный ответ со ссылкой не получен/);
    expect(fetch.mock.calls).toHaveLength(2);
    expect(fetch.mock.calls[0][1]?.body).toEqual(fetch.mock.calls[1][1]?.body);
    expect(fetch.mock.calls[0][1]?.headers).toEqual(
      fetch.mock.calls[1][1]?.headers,
    );
  });

  it('account switch discards old private responses and uses the new explicit credential', async () => {
    let resolveOld: (data: Response) => void = () => {};
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((_path, options) => {
        const identity = (options?.headers as Record<string, string>)[
          'X-Telegram-Init-Data'
        ];
        if (identity === 'synthetic-client-a')
          return new Promise((resolve) => {
            resolveOld = resolve;
          });
        return response({
          clients: [
            { id: '21', name: 'Client B', clubs: [] },
            { id: '22', name: 'Client C', clubs: [] },
          ],
        });
      });
    const view = render(<ClientAccess initialToken={null} />);
    auth.initData = 'synthetic-client-b';
    view.rerender(<ClientAccess initialToken={null} />);
    await screen.findByText('Client B');
    await act(async () =>
      resolveOld(
        await response({
          clients: [{ id: '20', name: 'Private Client A', clubs: [] }],
        }),
      ),
    );
    expect(screen.queryByText('Private Client A')).toBeNull();
    expect(fetch.mock.calls[0][1]?.signal?.aborted).toBe(true);
  });

  it('owner confirmation requires human verification and sends no verification code as credential', async () => {
    window.history.replaceState(
      {},
      '',
      '/app?mode=platform-owner&screen=invitation&resource=10',
    );
    const candidate: Invitation = {
      ...invitation,
      status: 'claimed',
      version: '2',
      verificationCode: 'ABC123',
      candidate: {
        userId: '90',
        telegramUserId: '880062001',
        displayName: 'Synthetic',
        username: null,
      },
      availableActions: ['confirm', 'revoke', 'reissue'],
    };
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((path) =>
        response(
          String(path).endsWith('/confirm')
            ? { ...candidate, status: 'accepted', availableActions: [] }
            : candidate,
        ),
      );
    render(<PlatformOnboarding credential="synthetic-owner" />);
    await screen.findByText('ABC123');
    const button = screen.getByRole('button', {
      name: 'Предоставить доступ',
    }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(button);
    await screen.findByText('Доступ предоставлен');
    expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toEqual({
      expectedVersion: '2',
    });
  });

  it('revoked history read removes previously visible application data', async () => {
    window.history.replaceState(
      {},
      '',
      '/app?mode=client&screen=application&resource=30',
    );
    let revoked = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      revoked ? response({ code: 'not_found' }, 404) : response(draft),
    );
    render(<ClientAccess initialToken={null} />);
    await screen.findByText('Test Street 1');
    revoked = true;
    act(() => window.dispatchEvent(new Event('pageshow')));
    await screen.findByText(/Доступ утрачен/);
    expect(screen.queryByText('Test Street 1')).toBeNull();
  });
});
