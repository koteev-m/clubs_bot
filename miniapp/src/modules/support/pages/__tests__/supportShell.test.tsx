import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SupportShell from '../SupportShell';
import {
  getSupportTicket,
  listPermittedSupportClubs,
  listSupportTickets,
  replyToSupportTicket,
  SUPPORT_REPLY_MAX_LENGTH,
  SupportApiError,
  SupportClub,
  SupportReplyResponse,
  SupportTicketStatus,
  SupportTicketSummary,
  SupportTicketThread,
  takeSupportTicketInWork,
} from '../../api/support.api';

vi.mock('../../api/support.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/support.api')>('../../api/support.api');
  return {
    ...actual,
    listPermittedSupportClubs: vi.fn(),
    listSupportTickets: vi.fn(),
    getSupportTicket: vi.fn(),
    takeSupportTicketInWork: vi.fn(),
    replyToSupportTicket: vi.fn(),
  };
});

const viewOnlyClubs: SupportClub[] = [
  { id: 1, name: 'Club A', canReply: false, canTakeInWork: false },
  { id: 2, name: 'Club B', canReply: false, canTakeInWork: false },
];

const fullAccessClubs: SupportClub[] = [
  { id: 1, name: 'Club A', canReply: true, canTakeInWork: true },
  { id: 2, name: 'Club B', canReply: false, canTakeInWork: false },
];

const replyOnlyClubs: SupportClub[] = [
  { id: 1, name: 'Club A', canReply: true, canTakeInWork: false },
];

const takeOnlyClubs: SupportClub[] = [
  { id: 1, name: 'Club A', canReply: false, canTakeInWork: true },
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

  it('shows reply but not take for IN_PROGRESS when the server permits both actions', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(fullAccessClubs);
    vi.mocked(getSupportTicket).mockResolvedValue(threadWithStatus('in_progress'));

    render(<SupportShell />);
    await openTicket();

    expect(screen.queryByRole('button', { name: 'Взять в работу' })).toBeNull();
    expect(screen.getByLabelText('Ответ')).toBeTruthy();
  });

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
