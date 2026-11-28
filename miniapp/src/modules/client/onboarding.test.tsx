import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ClientAccess from './ClientAccess';
import PlatformOnboarding from './PlatformOnboarding';
import { ClubApplication, Invitation } from './onboarding.types';

const telegramBack = vi.hoisted(() => ({ current: undefined as (() => void) | undefined }));
const auth = vi.hoisted(() => ({ initData: 'synthetic-client-a' }));
vi.mock('../auth/hooks/useInitData', () => ({ useInitData: () => auth }));
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({
    initDataUnsafe: { user: { first_name: 'Synthetic Candidate' } },
    BackButton: {
      show: vi.fn(),
      hide: vi.fn(),
      onClick: (handler: () => void) => { telegramBack.current = handler; },
      offClick: () => { telegramBack.current = undefined; },
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
    window.history.replaceState({}, '', '/app?mode=client&screen=client-space&resource=20');
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

  it.each([true, false])('keeps an old draft readable and gates reuse on canCreate=%s', async (canCreate) => {
    window.history.replaceState(
      {},
      '',
      '/app?mode=client&screen=application&resource=30',
    );
    vi.spyOn(globalThis, 'fetch').mockImplementation((path) =>
      response(String(path).endsWith('/onboarding') ? { canCreate } : draft),
    );
    render(<ClientAccess initialToken={null} />);
    await screen.findByText('Test Street 1');
    if (canCreate) {
      await screen.findByRole('button', { name: 'Использовать данные для создания' });
    } else {
      expect(screen.queryByRole('button', { name: 'Использовать данные для создания' })).toBeNull();
    }
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

  it('keeps an application decision draft on screen and Telegram Back until discard is confirmed', async () => {
    history.replaceState({}, '', '/app?mode=platform-owner&screen=application&resource=30');
    vi.spyOn(globalThis, 'fetch').mockImplementation((path) => response(
      String(path).endsWith('/search') ? { items: [], nextCursor: null } :
        { ...draft, availableActions: ['reject'] },
    ));
    const confirm = vi.mocked(window.confirm).mockReturnValue(false);
    render(<PlatformOnboarding credential="owner" />);
    const field = await screen.findByLabelText('Причина / сообщение клиенту') as HTMLTextAreaElement;
    fireEvent.change(field, { target: { value: 'Уточните адрес' } });
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    act(() => telegramBack.current!());
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(field.value).toBe('Уточните адрес');
    confirm.mockReturnValue(true);
    act(() => telegramBack.current!());
    expect(location.search).toContain('screen=applications');
  });

  it('protects unsent membership grants on browser Back and switching client sections', async () => {
    history.replaceState({ ownerTrail: [] }, '', '/app?mode=platform-owner&screen=clients');
    history.pushState({ ownerTrail: [JSON.stringify({ type: 'clients' })] }, '',
      '/app?mode=platform-owner&screen=client&resource=20&section=people');
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => response({
      id: '20', name: 'Организация', clubs: [], invitations: [invitation],
      members: [{ userId: '90', version: '1', active: false, permissions: [],
        clubGrants: [], restorablePermissions: ['client.application.manage'] }],
    }));
    const confirm = vi.mocked(window.confirm).mockReturnValue(false);
    render(<PlatformOnboarding credential="owner" />);
    const grant = await screen.findByRole('checkbox', { name: 'Управление заявками' }) as HTMLInputElement;
    fireEvent.click(grant);
    fireEvent.click(screen.getByRole('button', { name: 'Приглашения' }));
    const moves = vi.spyOn(history, 'go');
    act(() => history.back());
    await waitFor(() => expect(moves).toHaveBeenCalledWith(1));
    await waitFor(() => expect(location.search).toContain('screen=client&resource=20&section=people'));
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(grant.checked).toBe(true);
  });

  it('keeps an unknown invitation reissue and its key through browser Back', async () => {
    history.replaceState({ ownerTrail: [] }, '', '/app?mode=platform-owner&screen=client&resource=20');
    history.pushState({ ownerTrail: [JSON.stringify({ type: 'client', id: '20' })] }, '',
      '/app?mode=platform-owner&screen=invitation&resource=10');
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((path) =>
      String(path).endsWith('/reissue')
        ? Promise.reject(new Error('response lost'))
        : response({ ...invitation, availableActions: ['reissue'] }),
    );
    render(<PlatformOnboarding credential="synthetic-owner" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Выпустить новую ссылку' }));
    const retry = await screen.findByRole('button', { name: 'Повторить ту же операцию' });
    const moves = vi.spyOn(history, 'go');
    act(() => history.back());
    await waitFor(() => expect(moves).toHaveBeenCalledWith(1));
    await waitFor(() => expect(location.search).toContain('screen=invitation'));
    fireEvent.click(retry);
    const attempts = fetch.mock.calls.filter(([path]) => String(path).endsWith('/reissue'));
    expect(attempts).toHaveLength(2);
    expect(attempts[0][1]?.headers).toEqual(attempts[1][1]?.headers);
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
