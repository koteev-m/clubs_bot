import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SupportShell from '../SupportShell';
import {
  getSupportTicket,
  listPermittedSupportClubs,
  listSupportTickets,
  SupportApiError,
  SupportClub,
  SupportTicketSummary,
  SupportTicketThread,
} from '../../api/support.api';

vi.mock('../../api/support.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/support.api')>('../../api/support.api');
  return {
    ...actual,
    listPermittedSupportClubs: vi.fn(),
    listSupportTickets: vi.fn(),
    getSupportTicket: vi.fn(),
  };
});

const clubs: SupportClub[] = [
  { id: 1, name: 'Club A' },
  { id: 2, name: 'Club B' },
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

const thread: SupportTicketThread = {
  ticket: {
    id: 41,
    clubId: 1,
    topic: 'booking',
    status: 'new',
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

function createDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

describe('SupportShell', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    Object.defineProperty(window, 'scrollTo', { configurable: true, value: vi.fn() });
  });

  it('loads permitted clubs and renders filtered list, detail, and complete thread', async () => {
    const clubsRequest = createDeferred<SupportClub[]>();
    vi.mocked(listPermittedSupportClubs).mockReturnValue(clubsRequest.promise);
    vi.mocked(listSupportTickets).mockResolvedValue([ticket]);
    vi.mocked(getSupportTicket).mockResolvedValue(thread);

    render(<SupportShell />);

    expect(screen.getByText('Загрузка клубов...')).toBeTruthy();
    await act(async () => clubsRequest.resolve(clubs));
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

    expect(screen.queryByRole('button', { name: /ответить/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /назначить|взять|закрыть|решить/i })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '← К обращениям' }));
    expect(await screen.findByText('Нужна помощь с бронью')).toBeTruthy();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
  });

  it('clears the previous ticket and thread when the club changes', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(clubs);
    vi.mocked(listSupportTickets).mockImplementation(async ({ clubId }) => (clubId === 1 ? [ticket] : []));
    vi.mocked(getSupportTicket).mockResolvedValue(thread);

    render(<SupportShell />);

    fireEvent.change(await screen.findByLabelText('Клуб'), { target: { value: '1' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение #41' }));
    expect(await screen.findByText('Первое сообщение')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Клуб'), { target: { value: '2' } });

    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByText('Нужна помощь с бронью')).toBeNull();
    expect(await screen.findByText('Обращений нет.')).toBeTruthy();
    expect(listSupportTickets).toHaveBeenLastCalledWith({ clubId: 2 }, expect.any(AbortSignal));
  });

  it('clears all cached support data and shows a forbidden state after any 403', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(clubs);
    vi.mocked(listSupportTickets).mockImplementation(async ({ clubId }) => {
      if (clubId === 1) return [ticket];
      throw new SupportApiError('Forbidden', { status: 403, code: 'support_ticket_forbidden' });
    });
    vi.mocked(getSupportTicket).mockResolvedValue(thread);

    render(<SupportShell />);

    fireEvent.change(await screen.findByLabelText('Клуб'), { target: { value: '1' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение #41' }));
    expect(await screen.findByText('Первое сообщение')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Клуб'), { target: { value: '2' } });

    expect(await screen.findByText('Нет доступа к поддержке')).toBeTruthy();
    expect(screen.queryByLabelText('Клуб')).toBeNull();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByText('Нужна помощь с бронью')).toBeNull();
  });

  it('renders bounded empty and error states without exposing raw errors', async () => {
    vi.mocked(listPermittedSupportClubs).mockResolvedValue(clubs);
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
