import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  addMySupportTicketMessage,
  GuestSupportApiError,
  GuestSupportMessageResponse,
  GuestSupportTicketThread,
  listMySupportTickets,
  loadMySupportTicket,
  SupportTicketSummary,
} from '../../api/support.api';
import GuestSupportShell from '../GuestSupportShell';

vi.mock('../../api/support.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/support.api')>(
    '../../api/support.api',
  );
  return {
    ...actual,
    addMySupportTicketMessage: vi.fn(),
    listMySupportTickets: vi.fn(),
    loadMySupportTicket: vi.fn(),
  };
});

const ticket: SupportTicketSummary = {
  id: 41,
  clubId: 7,
  topic: 'booking',
  status: 'new',
  updatedAt: '2026-08-21T10:00:00Z',
  lastMessagePreview: 'Нужна помощь с бронью',
  lastSenderType: 'guest',
};

const secondTicket: SupportTicketSummary = {
  id: 42,
  clubId: 99,
  topic: 'complaint',
  status: 'resolved',
  updatedAt: '2026-08-21T09:00:00Z',
  lastMessagePreview: 'Вопрос решён',
  lastSenderType: 'agent',
};

function threadWithStatus(
  status: string,
  ticketId = 41,
  messages: GuestSupportTicketThread['messages'] = [
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
      text: 'Ответ поддержки',
      attachments: null,
      createdAt: '2026-08-21T10:00:00Z',
    },
    {
      id: 103,
      senderType: 'system',
      text: 'Системное сообщение',
      attachments: null,
      createdAt: '2026-08-21T10:01:00Z',
    },
  ],
): GuestSupportTicketThread {
  return {
    ticket: {
      id: ticketId,
      clubId: ticketId === 41 ? 7 : 99,
      topic: ticketId === 41 ? 'booking' : 'complaint',
      status,
      createdAt: '2026-08-21T09:00:00Z',
      updatedAt: '2026-08-21T10:01:00Z',
    },
    messages,
  };
}

const messageResponse: GuestSupportMessageResponse = {
  messageId: 104,
  ticketId: 41,
  senderType: 'guest',
  createdAt: '2026-08-21T10:02:00Z',
};

function createDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

async function openTicket(ticketId = 41) {
  fireEvent.click(await screen.findByRole('button', { name: `Открыть обращение ${ticketId}` }));
  await screen.findByLabelText('История обращения');
}

const excludedControlNames: Array<[string, RegExp]> = [
  ['calendar truth', /календар|calendar/i],
  ['operational-night UI', /операционн.*ноч|operational.?night/i],
  ['rich club detail', /детали клуба|профиль клуба|club (details|profile)/i],
  ['booking actions', /забронировать|создать бронь|book(?:ing)?/i],
  ['table actions', /столик|столом|управлять стол|table action|manage table/i],
  ['HOLD', /\bhold\b|удержание|холд/i],
  ['payments', /оплатить|плат[её]ж|payment/i],
  ['deposits', /депозит|deposit/i],
  ['Night Pass', /night pass|ночн.*пропуск/i],
  ['check-in', /check.?in|чек.?ин|отметить вход/i],
  ['loyalty', /лояльност|loyalty/i],
  ['music', /музык|music/i],
  ['broadcasts', /рассыл|broadcast/i],
  ['channel posts', /публикац.*канал|channel post/i],
  ['exports', /экспорт|export/i],
  ['iBota', /ibota/i],
  ['Guest Mode', /guest mode|гостев.*режим/i],
  ['AI functions', /\bAI\b|(^|\s)ИИ($|\s)|искусственн.*интеллект/i],
  ['complete guest home', /главная гостя|guest home|полная главная/i],
  ['registration/profile', /регистрац|registration|редактировать профиль|edit profile/i],
  ['network analytics', /аналитик.*сет|network analytics/i],
  ['multi-club onboarding', /онбординг.*клуб|multi.?club onboarding/i],
  ['cross-club UX', /межклуб|cross.?club/i],
  ['SLA', /\bSLA\b/i],
  ['priority', /приоритет|priority/i],
  ['escalation', /эскалац|escalation/i],
  ['automatic assignment', /авто.*назнач|automatic assignment/i],
  ['automatic close', /авто.*закры|automatic close/i],
  ['AI classification', /AI.?классификац|классифицировать|AI classification/i],
  ['AI draft', /AI.?черновик|черновик ответа|AI draft/i],
  ['AI auto-answer', /AI.?автоответ|автоответ|AI auto.?answer/i],
  ['manual reopen', /переоткры|reopen/i],
  ['staff reply', /ответить от поддержки|ответ сотрудника|staff reply/i],
  ['staff take-in-work', /взять в работу|take in work/i],
  ['staff Resolve', /решить обращение|resolve(?: ticket)?/i],
  ['staff Close', /закрыть обращение|close(?: ticket)?/i],
  ['role management', /управлен.*рол|role management/i],
  ['permission management', /управлен.*разреш|permission management/i],
  ['delivery retry', /повторить доставку|retry delivery/i],
  ['delivery resend', /переотправить|повторн.*отправ|resend/i],
  ['delivery settings', /настройк.*достав|delivery settings/i],
  ['raw failure detail', /детали ошибки|код ошибки|raw failure|failure detail/i],
  ['staff navigation', /панель сотрудника|staff navigation|staff panel/i],
  ['admin navigation', /панель админ|admin navigation|admin panel/i],
  ['promoter navigation', /панель промоутер|promoter navigation|promoter panel/i],
  ['entry navigation', /панель входа|entry navigation|entry panel|менеджер входа/i],
  ['expiry reminder', /скоро слетит|expiry reminder/i],
  ['spontaneous tables', /спонтанн.*стол|walk.?in table/i],
  ['mystery upgrade', /mystery.?upgrade|мистери.*апгрейд/i],
  ['allocation categories', /аллокац|allocation/i],
  ['playlists/favourites', /плейлист|playlist|избранн|favourites?/i],
  ['auto-reports', /автоотч[её]т|auto.?report/i],
  ['cloning/templates', /клонир|clone|шаблон|template/i],
  ['club authority selector', /выбрать клуб|сменить клуб|select club/i],
];

const interactiveRoles = [
  'button',
  'link',
  'textbox',
  'combobox',
  'checkbox',
  'radio',
  'menuitem',
  'tab',
] as const;

function expectNoExcludedControls() {
  excludedControlNames.forEach(([capability, name]) => {
    interactiveRoles.forEach((role) => {
      expect(screen.queryByRole(role, { name }), `${capability} must have no ${role}`).toBeNull();
    });
  });
  expect(screen.queryByRole('navigation')).toBeNull();
}

describe('GuestSupportShell', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    Object.defineProperty(window, 'scrollTo', { configurable: true, value: vi.fn() });
    vi.mocked(listMySupportTickets).mockResolvedValue([ticket]);
    vi.mocked(loadMySupportTicket).mockResolvedValue(threadWithStatus('new'));
    vi.mocked(addMySupportTicketMessage).mockResolvedValue(messageResponse);
  });

  it('renders loading then the owner list in server order with only bounded fields', async () => {
    const request = createDeferred<SupportTicketSummary[]>();
    vi.mocked(listMySupportTickets).mockReturnValue(request.promise);

    render(<GuestSupportShell />);

    expect(screen.getByText('Загрузка обращений...')).toBeTruthy();
    await act(async () => request.resolve([ticket, secondTicket]));

    const openButtons = await screen.findAllByRole('button', { name: /Открыть обращение/ });
    expect(openButtons.map((button) => button.getAttribute('aria-label'))).toEqual([
      'Открыть обращение 41',
      'Открыть обращение 42',
    ]);
    expect(screen.getByText('Бронирование')).toBeTruthy();
    expect(screen.getByText('Нужна помощь с бронью')).toBeTruthy();
    expect(screen.getByText('Новое')).toBeTruthy();
    expect(screen.queryByLabelText(/клуб/i)).toBeNull();
    expect(screen.queryByText(/user id|telegram id|agent id/i)).toBeNull();
    expect(screen.queryByText(/забронировать|оплатить|лояльность|музыка|iBota/i)).toBeNull();
  });

  it('renders the bounded empty list state', async () => {
    vi.mocked(listMySupportTickets).mockResolvedValue([]);

    render(<GuestSupportShell />);

    expect(await screen.findByText('Пока нет обращений.')).toBeTruthy();
  });

  it('renders a bounded list error and retries without exposing raw details', async () => {
    vi.mocked(listMySupportTickets)
      .mockRejectedValueOnce(
        new GuestSupportApiError('SQL: select PRIVATE_OWNER from tickets', {
          status: 500,
          code: 'internal_error',
        }),
      )
      .mockResolvedValueOnce([ticket]);

    render(<GuestSupportShell />);

    expect(await screen.findByText('Не удалось загрузить обращения')).toBeTruthy();
    expect(screen.queryByText(/SQL:|PRIVATE_OWNER/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByText('Нужна помощь с бронью')).toBeTruthy();
    expect(listMySupportTickets).toHaveBeenCalledTimes(2);
  });

  it('opens complete ordered detail, refreshes it, and returns to the list', async () => {
    const refreshed = threadWithStatus('in_progress', 41, [
      ...threadWithStatus('new').messages,
      {
        id: 104,
        senderType: 'guest',
        text: 'Новое persisted сообщение',
        attachments: null,
        createdAt: '2026-08-21T10:02:00Z',
      },
    ]);
    vi.mocked(loadMySupportTicket)
      .mockResolvedValueOnce(threadWithStatus('new'))
      .mockResolvedValueOnce(refreshed);

    render(<GuestSupportShell />);
    await openTicket();

    const messages = screen.getAllByTestId('guest-support-message');
    expect(within(messages[0]).getByText('Вы')).toBeTruthy();
    expect(within(messages[0]).getByText('Первое сообщение')).toBeTruthy();
    expect(within(messages[1]).getByText('Поддержка')).toBeTruthy();
    expect(within(messages[1]).getByText('Ответ поддержки')).toBeTruthy();
    expect(within(messages[2]).getByText('Система')).toBeTruthy();
    expect(within(messages[2]).getByText('Системное сообщение')).toBeTruthy();
    expect(screen.getByText(/Создано:/)).toBeTruthy();
    expect(screen.getByText(/Обновлено:/)).toBeTruthy();
    expect(screen.queryByText('7')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }));
    expect(await screen.findByText('Новое persisted сообщение')).toBeTruthy();
    expect(screen.getByTestId('guest-support-ticket-status').textContent).toBe('В работе');
    expect(loadMySupportTicket).toHaveBeenCalledTimes(2);
    expect(listMySupportTickets).toHaveBeenCalledTimes(2);

    fireEvent.click(screen.getByRole('button', { name: '← К обращениям' }));
    expect(await screen.findByText('Нужна помощь с бронью')).toBeTruthy();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
  });

  it('drops a stale thread after back and reopen on another ticket', async () => {
    const staleRequest = createDeferred<GuestSupportTicketThread>();
    vi.mocked(listMySupportTickets).mockResolvedValue([ticket, secondTicket]);
    vi.mocked(loadMySupportTicket).mockImplementation((ticketId) => {
      if (ticketId === 41) return staleRequest.promise;
      return Promise.resolve(
        threadWithStatus('resolved', 42, [
          {
            id: 201,
            senderType: 'agent',
            text: 'Только второй thread',
            attachments: null,
            createdAt: '2026-08-21T11:00:00Z',
          },
        ]),
      );
    });

    render(<GuestSupportShell />);
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение 41' }));
    expect(screen.getByText('Загрузка обращения...')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '← К обращениям' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение 42' }));
    expect(await screen.findByText('Только второй thread')).toBeTruthy();

    await act(async () => {
      staleRequest.resolve(
        threadWithStatus('new', 41, [
          {
            id: 999,
            senderType: 'guest',
            text: 'STALE_PRIVATE_THREAD',
            attachments: null,
            createdAt: '2026-08-21T12:00:00Z',
          },
        ]),
      );
    });
    expect(screen.queryByText('STALE_PRIVATE_THREAD')).toBeNull();
    expect(screen.getByText('Только второй thread')).toBeTruthy();
  });

  it.each([
    ['new', 'new', 'Новое'],
    ['in_progress', 'in_progress', 'В работе'],
    ['resolved', 'in_progress', 'В работе'],
  ])(
    'submits one trimmed continuation from %s and reloads authoritative %s state',
    async (initialStatus, refreshedStatus, expectedLabel) => {
      const refreshed = threadWithStatus(refreshedStatus, 41, [
        ...threadWithStatus(initialStatus).messages,
        {
          id: 104,
          senderType: 'guest',
          text: 'Продолжение',
          attachments: null,
          createdAt: '2026-08-21T10:02:00Z',
        },
      ]);
      vi.mocked(loadMySupportTicket)
        .mockResolvedValueOnce(threadWithStatus(initialStatus))
        .mockResolvedValueOnce(refreshed);

      render(<GuestSupportShell />);
      await openTicket();

      const input = screen.getByLabelText('Новое сообщение') as HTMLTextAreaElement;
      const submit = screen.getByRole('button', { name: 'Отправить' }) as HTMLButtonElement;
      expect(input.maxLength).toBe(2000);
      fireEvent.change(input, { target: { value: '   ' } });
      expect(submit.disabled).toBe(true);
      fireEvent.change(input, { target: { value: 'x'.repeat(2001) } });
      expect(submit.disabled).toBe(true);
      fireEvent.change(input, { target: { value: '  Продолжение  ' } });
      fireEvent.click(submit);

      expect(await screen.findByText('Сообщение отправлено')).toBeTruthy();
      expect(addMySupportTicketMessage).toHaveBeenCalledTimes(1);
      expect(addMySupportTicketMessage).toHaveBeenCalledWith(41, 'Продолжение');
      await waitFor(() => {
        expect(loadMySupportTicket).toHaveBeenCalledTimes(2);
        expect(listMySupportTickets).toHaveBeenCalledTimes(2);
      });
      expect(screen.getByTestId('guest-support-ticket-status').textContent).toBe(expectedLabel);
      expect(await screen.findByText('Продолжение')).toBeTruthy();
      expect((screen.getByLabelText('Новое сообщение') as HTMLTextAreaElement).value).toBe('');
    },
  );

  it('enforces the literal 2000-character boundary before any continuation POST', async () => {
    vi.mocked(loadMySupportTicket)
      .mockResolvedValueOnce(threadWithStatus('new'))
      .mockResolvedValueOnce(threadWithStatus('new'));

    render(<GuestSupportShell />);
    await openTicket();

    const input = screen.getByLabelText('Новое сообщение') as HTMLTextAreaElement;
    const submit = screen.getByRole('button', { name: 'Отправить' });
    expect(input.maxLength).toBe(2000);

    fireEvent.change(input, { target: { value: '   ' } });
    fireEvent.click(submit);
    expect(addMySupportTicketMessage).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: 'x'.repeat(2001) } });
    expect(screen.getByText('2001/2000')).toBeTruthy();
    fireEvent.click(submit);
    expect(addMySupportTicketMessage).not.toHaveBeenCalled();

    const exactLimit = 'x'.repeat(2000);
    fireEvent.change(input, { target: { value: exactLimit } });
    expect(screen.getByText('2000/2000')).toBeTruthy();
    fireEvent.click(submit);

    await waitFor(() => expect(addMySupportTicketMessage).toHaveBeenCalledTimes(1));
    expect(addMySupportTicketMessage).toHaveBeenCalledWith(41, exactLimit);
  });

  it.each(['closed', 'opened', 'answered', 'waiting', 'unsupported']) (
    'keeps %s read-only with no mutation control',
    async (status) => {
      vi.mocked(loadMySupportTicket).mockResolvedValue(threadWithStatus(status));

      render(<GuestSupportShell />);
      await openTicket();

      expect(screen.getByText('Первое сообщение')).toBeTruthy();
      expect(screen.queryByLabelText('Новое сообщение')).toBeNull();
      expect(screen.queryByRole('button', { name: 'Отправить' })).toBeNull();
      expect(screen.queryByRole('button', { name: /переоткрыть|закрыть|решить/i })).toBeNull();
    },
  );

  it('prevents duplicate continuation while the first request is pending', async () => {
    const request = createDeferred<GuestSupportMessageResponse>();
    vi.mocked(addMySupportTicketMessage).mockReturnValue(request.promise);
    vi.mocked(loadMySupportTicket)
      .mockResolvedValueOnce(threadWithStatus('new'))
      .mockResolvedValueOnce(threadWithStatus('new'));

    render(<GuestSupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Новое сообщение'), { target: { value: 'Один раз' } });
    const submit = screen.getByRole('button', { name: 'Отправить' });
    fireEvent.click(submit);
    fireEvent.click(submit);

    expect(addMySupportTicketMessage).toHaveBeenCalledTimes(1);
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    await act(async () => request.resolve(messageResponse));
    expect(await screen.findByText('Сообщение отправлено')).toBeTruthy();
  });

  it('clears every authenticated action state after a mutation 403', async () => {
    vi.mocked(addMySupportTicketMessage).mockRejectedValue(
      new GuestSupportApiError('PRIVATE_FOREIGN_OWNER_METADATA', {
        status: 403,
        code: 'support_ticket_forbidden',
      }),
    );

    render(<GuestSupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Новое сообщение'), {
      target: { value: 'Не сохранять' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Отправить' }));

    expect(await screen.findByText('Нет доступа к обращениям')).toBeTruthy();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByText('Нужна помощь с бронью')).toBeNull();
    expect(screen.queryByLabelText('Новое сообщение')).toBeNull();
    expect(screen.queryByText(/PRIVATE_FOREIGN_OWNER_METADATA/)).toBeNull();
  });

  it('clears selection and returns to the own list after a detail 404', async () => {
    vi.mocked(loadMySupportTicket).mockRejectedValue(
      new GuestSupportApiError('foreign ticket 41 belongs to telegram 999', {
        status: 404,
        code: 'support_ticket_not_found',
      }),
    );

    render(<GuestSupportShell />);
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение 41' }));

    expect(await screen.findByText('Обращение не найдено')).toBeTruthy();
    expect(await screen.findByText('Нужна помощь с бронью')).toBeTruthy();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByText(/telegram 999|foreign ticket/i)).toBeNull();
    expect(listMySupportTickets).toHaveBeenCalledTimes(2);
  });

  it('clears selection and draft after a mutation 404', async () => {
    vi.mocked(addMySupportTicketMessage).mockRejectedValue(
      new GuestSupportApiError('missing internal ticket row 41', {
        status: 404,
        code: 'support_ticket_not_found',
      }),
    );

    render(<GuestSupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Новое сообщение'), { target: { value: 'Черновик' } });
    fireEvent.click(screen.getByRole('button', { name: 'Отправить' }));

    expect(await screen.findByText('Обращение не найдено')).toBeTruthy();
    expect(screen.queryByText('Первое сообщение')).toBeNull();
    expect(screen.queryByDisplayValue('Черновик')).toBeNull();
    expect(screen.queryByText(/internal ticket row/i)).toBeNull();
  });

  it('refreshes stale lifecycle after 409 and removes controls when the backend reports CLOSED', async () => {
    vi.mocked(loadMySupportTicket)
      .mockResolvedValueOnce(threadWithStatus('resolved'))
      .mockResolvedValueOnce(threadWithStatus('closed'));
    vi.mocked(addMySupportTicketMessage).mockRejectedValue(
      new GuestSupportApiError('raw support_ticket_closed detail', {
        status: 409,
        code: 'support_ticket_closed',
      }),
    );

    render(<GuestSupportShell />);
    await openTicket();
    fireEvent.change(screen.getByLabelText('Новое сообщение'), { target: { value: 'Поздно' } });
    fireEvent.click(screen.getByRole('button', { name: 'Отправить' }));

    expect(
      await screen.findByText('Состояние обращения изменилось. Данные обновляются.'),
    ).toBeTruthy();
    await waitFor(() => expect(loadMySupportTicket).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('guest-support-ticket-status').textContent).toBe('Закрыто');
    expect(screen.queryByLabelText('Новое сообщение')).toBeNull();
    expect(screen.queryByText(/raw support_ticket_closed detail/)).toBeNull();
  });

  it('renders bounded detail failures and retries without keeping a partial thread', async () => {
    vi.mocked(loadMySupportTicket)
      .mockRejectedValueOnce(
        new GuestSupportApiError('SQL: PRIVATE_MESSAGE_BODY', {
          status: 500,
          code: 'internal_error',
        }),
      )
      .mockResolvedValueOnce(threadWithStatus('new'));

    render(<GuestSupportShell />);
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение 41' }));

    expect(await screen.findByText('Не удалось загрузить обращение')).toBeTruthy();
    expect(screen.queryByText(/SQL:|PRIVATE_MESSAGE_BODY/)).toBeNull();
    expect(screen.queryByTestId('guest-support-message')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }));
    const history = await screen.findByLabelText('История обращения');
    expect(within(history).getByText('Первое сообщение')).toBeTruthy();
  });

  it('keeps explicit safe-area padding on the isolated mobile surface', async () => {
    render(<GuestSupportShell />);
    await screen.findByText('Нужна помощь с бронью');

    expect(screen.getByTestId('guest-support-header').getAttribute('style')).toContain(
      'safe-area-inset-top',
    );
    expect(screen.getByTestId('guest-support-content').getAttribute('style')).toContain(
      'safe-area-inset-bottom',
    );
  });

  it.each([
    ['list', null],
    ['active', 'in_progress'],
    ['resolved', 'resolved'],
    ['closed', 'closed'],
  ])('keeps the full semantic exclusion matrix absent on the %s surface', async (_, status) => {
    if (status !== null) {
      vi.mocked(loadMySupportTicket).mockResolvedValue(threadWithStatus(status));
    }

    render(<GuestSupportShell />);
    await screen.findByRole('button', { name: 'Открыть обращение 41' });
    if (status !== null) await openTicket();

    expectNoExcludedControls();
  });
});
