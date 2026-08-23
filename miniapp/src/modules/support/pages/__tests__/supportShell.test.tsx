import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http } from '../../../../shared/api/http';
import SupportShell from '../SupportShell';
import {
  closeSupportTicket,
  getSupportTicket,
  listPermittedSupportClubs,
  listSupportTickets,
  replyToSupportTicket,
  resolveSupportTicket,
  SUPPORT_REPLY_MAX_LENGTH,
  SupportApiError,
  SupportClub,
  SupportReplyResponse,
  SupportStatusMutationResponse,
  SupportTicketStatus,
  SupportTicketSummary,
  SupportTicketThread,
  takeSupportTicketInWork,
} from '../../api/support.api';

vi.mock('../../api/support.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/support.api')>('../../api/support.api');
  return {
    ...actual,
    closeSupportTicket: vi.fn(),
    listPermittedSupportClubs: vi.fn(),
    listSupportTickets: vi.fn(),
    getSupportTicket: vi.fn(),
    takeSupportTicketInWork: vi.fn(),
    replyToSupportTicket: vi.fn(),
    resolveSupportTicket: vi.fn(),
  };
});

const viewOnlyClubs: SupportClub[] = [
  { id: 1, name: 'Club A', canReply: false, canTakeInWork: false, canManageStatus: false },
  { id: 2, name: 'Club B', canReply: false, canTakeInWork: false, canManageStatus: false },
];

const fullAccessClubs: SupportClub[] = [
  { id: 1, name: 'Club A', canReply: true, canTakeInWork: true, canManageStatus: true },
  { id: 2, name: 'Club B', canReply: false, canTakeInWork: false, canManageStatus: false },
];

const replyOnlyClubs: SupportClub[] = [
  { id: 1, name: 'Club A', canReply: true, canTakeInWork: false, canManageStatus: false },
];

const takeOnlyClubs: SupportClub[] = [
  { id: 1, name: 'Club A', canReply: false, canTakeInWork: true, canManageStatus: true },
];

const ticket: SupportTicketSummary = {
  id: 41,
  clubId: 1,
  topic: 'booking',
  status: 'new',
  updatedAt: '2026-08-21T10:00:00Z',
  lastMessagePreview: 'Нужна помощь с бронью',
  lastSenderType: 'guest',
};

function threadWithStatus(status: SupportTicketStatus, clubId = 1): SupportTicketThread {
  return {
    ticket: {
      id: 41,
      clubId,
      topic: 'booking',
      status,
      createdAt: '2026-08-21T09:00:00Z',
      updatedAt: '2026-08-21T10:00:00Z',
    },
    messages: [
      {
        id: 101,
        senderType: 'guest',
        text: 'Первое сообщение',
        attachments: null,
        createdAt: '2026-08-21T09:00:00Z',
      },
      {
        id: 102,
        senderType: 'agent',
        text: 'Второе сообщение',
        attachments: '[{"name":"answer.txt"}]',
        createdAt: '2026-08-21T10:00:00Z',
      },
    ],
  };
}

const thread = threadWithStatus('new');

const takeResponse = {
  id: 41,
  clubId: 1,
  topic: 'booking',
  status: 'in_progress' as const,
  updatedAt: '2026-08-21T10:01:00Z',
};

const replyResponse: SupportReplyResponse = {
  ticketId: 41,
  clubId: 1,
  replyMessageId: 103,
  replyCreatedAt: '2026-08-21T10:01:00Z',
  ticketStatus: 'in_progress',
};

function statusMutationResponse(status: 'resolved' | 'closed'): SupportStatusMutationResponse {
  return {
    id: 41,
    clubId: 1,
    topic: 'booking',
    status,
    updatedAt: status === 'resolved' ? '2026-08-21T10:02:00Z' : '2026-08-21T10:03:00Z',
  };
}

const resolveResponse = statusMutationResponse('resolved');
const closeResponse = statusMutationResponse('closed');

function createDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

async function openTicket() {
  fireEvent.change(await screen.findByLabelText('Клуб'), { target: { value: '1' } });
  fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение #41' }));
  await screen.findByText('Первое сообщение');
}

describe('SupportShell', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    Object.defineProperty(window, 'scrollTo', { configurable: true, value: vi.fn() });
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(viewOnlyClubs);
    vi.mocked(listSupportTickets).mockResolvedValue([ticket]);
    vi.mocked(getSupportTicket).mockResolvedValue(thread);
    vi.mocked(takeSupportTicketInWork).mockResolvedValue(takeResponse);
    vi.mocked(replyToSupportTicket).mockResolvedValue(replyResponse);
    vi.mocked(resolveSupportTicket).mockResolvedValue(resolveResponse);
    vi.mocked(closeSupportTicket).mockResolvedValue(closeResponse);
  });

  it('loads permitted clubs and renders filtered list, detail, and complete thread', async () => {
    const clubsRequest = createDeferred<SupportClub[]>();
    vi.mocked(listPermittedSupportClubs).mockReturnValue(clubsRequest.promise);

    render(<SupportShell />);

    expect(screen.getByText('Загрузка клубов...')).toBeTruthy();
    await act(async () => clubsRequest.resolve(viewOnlyClubs));
    expect(await screen.findByRole('option', { name: 'Club A' })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Club B' })).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Клуб'), { target: { value: '1' } });
    expect(await screen.findByText('Нужна помощь с бронью')).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Решено' })).toBeTruthy();
    expect(listSupportTickets).toHaveBeenLastCalledWith({ clubId: 1 }, expect.any(AbortSignal));

    fireEvent.change(screen.getByLabelText('Статус'), { target: { value: 'new' } });
    await waitFor(() => {
      expect(listSupportTickets).toHaveBeenLastCalledWith(
        { clubId: 1, status: 'new' },
        expect.any(AbortSignal),
      );
    });

    fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение #41' }));
    expect(await screen.findByText('Первое сообщение')).toBeTruthy();
    expect(getSupportTicket).toHaveBeenCalledWith(41, expect.any(AbortSignal));

    const messages = screen.getAllByTestId('support-message');
    expect(messages).toHaveLength(2);
    expect(within(messages[0]).getByText('Первое сообщение')).toBeTruthy();
    expect(within(messages[1]).getByText('Второе сообщение')).toBeTruthy();
    expect(within(messages[1]).getByText('[{"name":"answer.txt"}]')).toBeTruthy();

    expect(screen.queryByLabelText('Ответ')).toBeNull();
    expect(screen.queryByRole('button', { name: /назначить|взять|закрыть|решить/i })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '← К обращениям' }));
    expect(await screen.findByText('Нужна помощь с бронью')).toBeTruthy();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
  });

  it('uses only server capabilities and ticket status to expose take and reply controls', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);

    render(<SupportShell />);
    await openTicket();

    expect(screen.getByRole('button', { name: 'Взять в работу' })).toBeTruthy();
    expect(screen.getByLabelText('Ответ')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Сохранить ответ' })).toBeTruthy();
  });

  it('does not combine a capability from one club with a ticket from another club', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket).mockResolvedValue(threadWithStatus('new', 2));

    render(<SupportShell />);
    await openTicket();

    expect(screen.queryByRole('button', { name: 'Взять в работу' })).toBeNull();
    expect(screen.queryByLabelText('Ответ')).toBeNull();
  });

  it('shows reply and resolve but not take for IN_PROGRESS when the server permits both actions', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket).mockResolvedValue(threadWithStatus('in_progress'));

    render(<SupportShell />);
    await openTicket();

    expect(screen.queryByRole('button', { name: 'Взять в работу' })).toBeNull();
    expect(screen.getByLabelText('Ответ')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Решить обращение' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Закрыть обращение' })).toBeNull();
  });

  it('does not combine status management from one club with a RESOLVED ticket from another club', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket).mockResolvedValue(threadWithStatus('resolved', 2));

    render(<SupportShell />);
    await openTicket();

    expect(screen.queryByRole('button', { name: 'Закрыть обращение' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Решить обращение' })).toBeNull();
    expect(screen.queryByLabelText('Ответ')).toBeNull();
  });

  it('requires a separate resolve confirmation and refreshes detail and list after success', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket)
      .mockResolvedValueOnce(threadWithStatus('in_progress'))
      .mockResolvedValueOnce(threadWithStatus('resolved'));

    render(<SupportShell />);
    await openTicket();

    fireEvent.click(screen.getByRole('button', { name: 'Решить обращение' }));
    expect(resolveSupportTicket).toHaveBeenCalledTimes(0);
    expect(screen.getByRole('group', { name: 'Подтверждение решения обращения' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Подтвердить' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }));
    expect(screen.queryByRole('group', { name: 'Подтверждение решения обращения' })).toBeNull();
    expect(resolveSupportTicket).toHaveBeenCalledTimes(0);

    fireEvent.click(screen.getByRole('button', { name: 'Решить обращение' }));
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    expect(await screen.findByText('Обращение решено.')).toBeTruthy();
    expect(resolveSupportTicket).toHaveBeenCalledTimes(1);
    expect(resolveSupportTicket).toHaveBeenCalledWith(41);
    await waitFor(() => {
      expect(getSupportTicket).toHaveBeenCalledTimes(2);
      expect(listSupportTickets).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByRole('button', { name: 'Закрыть обращение' })).toBeTruthy();
    expect(screen.queryByLabelText('Ответ')).toBeNull();
    expect(screen.queryByText(/доставлен/i)).toBeNull();
  });

  it('closes RESOLVED directly and leaves the refreshed CLOSED ticket without controls', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket)
      .mockResolvedValueOnce(threadWithStatus('resolved'))
      .mockResolvedValueOnce(threadWithStatus('closed'));

    render(<SupportShell />);
    await openTicket();

    expect(screen.queryByRole('group', { name: 'Подтверждение решения обращения' })).toBeNull();
    expect(screen.queryByLabelText('Ответ')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Закрыть обращение' }));

    expect(await screen.findByText('Обращение закрыто.')).toBeTruthy();
    expect(closeSupportTicket).toHaveBeenCalledTimes(1);
    expect(closeSupportTicket).toHaveBeenCalledWith(41);
    await waitFor(() => {
      expect(getSupportTicket).toHaveBeenCalledTimes(2);
      expect(listSupportTickets).toHaveBeenCalledTimes(2);
    });
    expect(screen.queryByRole('button', { name: /взять|решить|закрыть/i })).toBeNull();
    expect(screen.queryByLabelText('Ответ')).toBeNull();
    expect(screen.queryByText(/доставлен/i)).toBeNull();
  });

  it.each(['in_progress', 'resolved'] as const)(
    'does not expose resolve or close for %s without the server status-management capability',
    async (status) => {
      vi.mocked(listPermittedSupportClubs).mockResolvedValue(replyOnlyClubs);
      vi.mocked(getSupportTicket).mockResolvedValue(threadWithStatus(status));

      render(<SupportShell />);
      await openTicket();

      if (status === 'in_progress') {
        expect(screen.getByLabelText('Ответ')).toBeTruthy();
      } else {
        expect(screen.queryByLabelText('Ответ')).toBeNull();
      }
      expect(screen.queryByRole('button', { name: /решить|закрыть/i })).toBeNull();
    },
  );

  it.each(['opened', 'answered', 'closed'] as const)(
    'keeps %s tickets read-only even when the server returns mutation capabilities',
    async (status) => {
      vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
      vi.mocked(getSupportTicket).mockResolvedValue(threadWithStatus(status));

      render(<SupportShell />);
      await openTicket();

      expect(screen.queryByRole('button', { name: 'Взять в работу' })).toBeNull();
      expect(screen.queryByLabelText('Ответ')).toBeNull();
      expect(screen.queryByRole('button', { name: /закрыть|решить|переоткрыть/i })).toBeNull();
      expect(screen.queryByLabelText('Новый статус')).toBeNull();
    },
  );

  it('takes a NEW ticket and refreshes both the ticket detail and list', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(takeOnlyClubs);
    vi.mocked(getSupportTicket)
      .mockResolvedValueOnce(thread)
      .mockResolvedValueOnce(threadWithStatus('in_progress'));

    render(<SupportShell />);
    await openTicket();
    expect(screen.queryByLabelText('Ответ')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Взять в работу' }));

    expect(await screen.findByText('Обращение взято в работу')).toBeTruthy();
    expect(takeSupportTicketInWork).toHaveBeenCalledTimes(1);
    expect(takeSupportTicketInWork).toHaveBeenCalledWith(41);
    await waitFor(() => {
      expect(getSupportTicket).toHaveBeenCalledTimes(2);
      expect(listSupportTickets).toHaveBeenCalledTimes(2);
    });
  });

  it('submits one trimmed reply, reports persistence, and refreshes the thread and list', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(replyOnlyClubs);
    vi.mocked(getSupportTicket)
      .mockResolvedValueOnce(thread)
      .mockResolvedValueOnce(threadWithStatus('in_progress'));

    render(<SupportShell />);
    await openTicket();

    expect(screen.queryByRole('button', { name: 'Взять в работу' })).toBeNull();
    const input = screen.getByLabelText('Ответ') as HTMLTextAreaElement;
    const submit = screen.getByRole('button', { name: 'Сохранить ответ' }) as HTMLButtonElement;
    expect(input.maxLength).toBe(SUPPORT_REPLY_MAX_LENGTH);
    fireEvent.change(input, { target: { value: '   ' } });
    expect(submit.disabled).toBe(true);
    fireEvent.change(input, { target: { value: '  Сохраняем ответ  ' } });
    fireEvent.click(submit);

    expect(await screen.findByText('Ответ сохранён')).toBeTruthy();
    expect(replyToSupportTicket).toHaveBeenCalledTimes(1);
    expect(replyToSupportTicket).toHaveBeenCalledWith(41, 'Сохраняем ответ');
    await waitFor(() => {
      expect(getSupportTicket).toHaveBeenCalledTimes(2);
      expect(listSupportTickets).toHaveBeenCalledTimes(2);
    });
    expect(((await screen.findByLabelText('Ответ')) as HTMLTextAreaElement).value).toBe('');
    expect(screen.queryByText(/доставлен/i)).toBeNull();
  });

  it('prevents duplicate reply submission while the first request is pending', async () => {
    const request = createDeferred<SupportReplyResponse>();
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(replyOnlyClubs);
    vi.mocked(replyToSupportTicket).mockReturnValue(request.promise);

    render(<SupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Ответ'), { target: { value: 'Один ответ' } });
    const submit = screen.getByRole('button', { name: 'Сохранить ответ' });
    fireEvent.click(submit);
    fireEvent.click(submit);

    expect(replyToSupportTicket).toHaveBeenCalledTimes(1);
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    await act(async () => request.resolve(replyResponse));
    expect(await screen.findByText('Ответ сохранён')).toBeTruthy();
  });

  it('prevents duplicate resolve submission while the confirmed request is pending', async () => {
    const request = createDeferred<SupportStatusMutationResponse>();
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket)
      .mockResolvedValueOnce(threadWithStatus('in_progress'))
      .mockResolvedValueOnce(threadWithStatus('resolved'));
    vi.mocked(resolveSupportTicket).mockReturnValue(request.promise);

    render(<SupportShell />);
    await openTicket();
    fireEvent.click(screen.getByRole('button', { name: 'Решить обращение' }));
    const confirm = screen.getByRole('button', { name: 'Подтвердить' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);

    expect(resolveSupportTicket).toHaveBeenCalledTimes(1);
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    await act(async () => request.resolve(resolveResponse));
    expect(await screen.findByText('Обращение решено.')).toBeTruthy();
  });

  it('prevents duplicate close submission while the request is pending', async () => {
    const request = createDeferred<SupportStatusMutationResponse>();
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket)
      .mockResolvedValueOnce(threadWithStatus('resolved'))
      .mockResolvedValueOnce(threadWithStatus('closed'));
    vi.mocked(closeSupportTicket).mockReturnValue(request.promise);

    render(<SupportShell />);
    await openTicket();
    const close = screen.getByRole('button', { name: 'Закрыть обращение' });
    fireEvent.click(close);
    fireEvent.click(close);

    expect(closeSupportTicket).toHaveBeenCalledTimes(1);
    expect((close as HTMLButtonElement).disabled).toBe(true);
    await act(async () => request.resolve(closeResponse));
    expect(await screen.findByText('Обращение закрыто.')).toBeTruthy();
  });

  it('clears reply state and refetches server capabilities after a mutation 403', async () => {
    vi.mocked(listPermittedSupportClubs)
      .mockResolvedValueOnce(replyOnlyClubs)
      .mockResolvedValueOnce(replyOnlyClubs);
    vi.mocked(replyToSupportTicket).mockRejectedValue(
      new SupportApiError('raw forbidden detail', {
        status: 403,
        code: 'support_ticket_forbidden',
      }),
    );

    render(<SupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Ответ'), { target: { value: 'Не сохранять' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить ответ' }));

    expect(await screen.findByText('Права доступа изменились. Доступные действия обновляются.')).toBeTruthy();
    await waitFor(() => expect(listPermittedSupportClubs).toHaveBeenCalledTimes(2));
    expect(((await screen.findByLabelText('Ответ')) as HTMLTextAreaElement).value).toBe('');
    expect(screen.queryByText('Ответ сохранён')).toBeNull();
    expect(screen.queryByText(/raw forbidden detail/i)).toBeNull();
    expect(screen.getByText('Первое сообщение')).toBeTruthy();
  });

  it('clears resolve confirmation and pending state then refetches capabilities after a 403', async () => {
    vi.mocked(listPermittedSupportClubs)
      .mockResolvedValueOnce(fullAccessClubs)
      .mockResolvedValueOnce(replyOnlyClubs);
    vi.mocked(getSupportTicket).mockResolvedValue(threadWithStatus('in_progress'));
    vi.mocked(resolveSupportTicket).mockRejectedValue(
      new SupportApiError('raw status permission detail', {
        status: 403,
        code: 'support_ticket_forbidden',
      }),
    );

    render(<SupportShell />);
    await openTicket();
    fireEvent.click(screen.getByRole('button', { name: 'Решить обращение' }));
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    expect(await screen.findByText('Права доступа изменились. Доступные действия обновляются.')).toBeTruthy();
    await waitFor(() => expect(listPermittedSupportClubs).toHaveBeenCalledTimes(2));
    expect(await screen.findByLabelText('Ответ')).toBeTruthy();
    expect(screen.queryByRole('group', { name: 'Подтверждение решения обращения' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Решить обращение' })).toBeNull();
    expect(screen.queryByText(/raw status permission detail/i)).toBeNull();
  });

  it('clears resolve confirmation and refreshes stale lifecycle data after a 409', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket)
      .mockResolvedValueOnce(threadWithStatus('in_progress'))
      .mockResolvedValueOnce(threadWithStatus('resolved'));
    vi.mocked(resolveSupportTicket).mockRejectedValue(
      new SupportApiError('raw invalid transition detail', {
        status: 409,
        code: 'invalid_state',
      }),
    );

    render(<SupportShell />);
    await openTicket();
    fireEvent.click(screen.getByRole('button', { name: 'Решить обращение' }));
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    expect(await screen.findByText('Состояние обращения изменилось. Данные обновляются.')).toBeTruthy();
    expect(screen.queryByRole('group', { name: 'Подтверждение решения обращения' })).toBeNull();
    await waitFor(() => {
      expect(getSupportTicket).toHaveBeenCalledTimes(2);
      expect(listSupportTickets).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByRole('button', { name: 'Закрыть обращение' })).toBeTruthy();
    expect(screen.queryByText(/raw invalid transition detail/i)).toBeNull();
  });

  it('clears a foreign or missing ticket and shows the bounded not-found state after mutation 404', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(replyOnlyClubs);
    vi.mocked(replyToSupportTicket).mockRejectedValue(
      new SupportApiError('ticket 41 belongs to another club', {
        status: 404,
        code: 'support_ticket_not_found',
      }),
    );

    render(<SupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Ответ'), { target: { value: 'Ответ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить ответ' }));

    expect(await screen.findByText('Обращение не найдено')).toBeTruthy();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByLabelText('Ответ')).toBeNull();
    expect(screen.queryByText(/another club|ticket 41/i)).toBeNull();
    await waitFor(() => expect(listSupportTickets).toHaveBeenCalledTimes(2));
  });

  it('clears a RESOLVED ticket after close returns an indistinguishable 404', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket).mockResolvedValue(threadWithStatus('resolved'));
    vi.mocked(closeSupportTicket).mockRejectedValue(
      new SupportApiError('foreign ticket 41 in club 9000', {
        status: 404,
        code: 'support_ticket_not_found',
      }),
    );

    render(<SupportShell />);
    await openTicket();
    fireEvent.click(screen.getByRole('button', { name: 'Закрыть обращение' }));

    expect(await screen.findByText('Обращение не найдено')).toBeTruthy();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Закрыть обращение' })).toBeNull();
    expect(screen.queryByText(/foreign ticket|club 9000/i)).toBeNull();
    await waitFor(() => expect(listSupportTickets).toHaveBeenCalledTimes(2));
  });

  it('renders bounded mutation failures without exposing raw server details', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(replyOnlyClubs);
    vi.mocked(replyToSupportTicket).mockRejectedValue(
      new SupportApiError('SQL: insert into support_messages secret reply body', {
        status: 500,
        code: 'internal_error',
      }),
    );

    render(<SupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Ответ'), { target: { value: 'Ответ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить ответ' }));

    expect(await screen.findByText('Не удалось сохранить ответ')).toBeTruthy();
    expect(screen.queryByText(/SQL:|secret reply body/)).toBeNull();
    expect(screen.queryByText('Ответ сохранён')).toBeNull();
  });

  it('clears the previous ticket and thread when the club changes', async () => {
    vi.mocked(listSupportTickets).mockImplementation(async ({ clubId }) => (clubId === 1 ? [ticket] : []));

    render(<SupportShell />);
    await openTicket();

    fireEvent.change(screen.getByLabelText('Клуб'), { target: { value: '2' } });

    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByText('Нужна помощь с бронью')).toBeNull();
    expect(await screen.findByText('Обращений нет.')).toBeTruthy();
    expect(listSupportTickets).toHaveBeenLastCalledWith({ clubId: 2 }, expect.any(AbortSignal));
  });

  it('clears all cached support data and shows a forbidden state after a read 403', async () => {
    vi.mocked(listSupportTickets).mockImplementation(async ({ clubId }) => {
      if (clubId === 1) return [ticket];
      throw new SupportApiError('Forbidden', { status: 403, code: 'support_ticket_forbidden' });
    });

    render(<SupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Клуб'), { target: { value: '2' } });

    expect(await screen.findByText('Нет доступа к поддержке')).toBeTruthy();
    expect(screen.queryByLabelText('Клуб')).toBeNull();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByText('Нужна помощь с бронью')).toBeNull();
  });

  it('renders bounded empty and read error states without exposing raw errors', async () => {
    vi.mocked(listSupportTickets).mockRejectedValue(
      new SupportApiError('SQL: select secret_body from support_messages', {
        status: 500,
        code: 'internal_error',
      }),
    );

    render(<SupportShell />);
    fireEvent.change(await screen.findByLabelText('Клуб'), { target: { value: '1' } });

    expect(await screen.findByText('Не удалось загрузить обращения')).toBeTruthy();
    expect(screen.queryByText(/secret_body|SQL:/)).toBeNull();
  });
});

describe('support lifecycle API contract', () => {
  it('sends only explicit confirmation for resolve and no request body for close', async () => {
    const actual = await vi.importActual<typeof import('../../api/support.api')>('../../api/support.api');
    const post = vi
      .spyOn(http, 'post')
      .mockResolvedValueOnce({ data: resolveResponse } as never)
      .mockResolvedValueOnce({ data: closeResponse } as never);

    try {
      await actual.resolveSupportTicket(41);
      await actual.closeSupportTicket(41);

      expect(post).toHaveBeenNthCalledWith(
        1,
        '/api/support/tickets/41/resolve',
        { confirmed: true },
        { signal: undefined },
      );
      expect(post).toHaveBeenNthCalledWith(
        2,
        '/api/support/tickets/41/close',
        undefined,
        { signal: undefined },
      );
    } finally {
      post.mockRestore();
    }
  });
});
