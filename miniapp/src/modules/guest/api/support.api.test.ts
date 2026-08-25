import { fireEvent, render, screen } from '@testing-library/react';
import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { http } from '../../../shared/api/http';
import GuestSupportShell from '../pages/GuestSupportShell';
import {
  addMySupportTicketMessage,
  GuestSupportApiError,
  isGuestSupportRequestCanceled,
  listMySupportTickets,
  loadMySupportTicket,
} from './support.api';

type ApiFailurePayload = {
  code?: string;
  message?: string;
  error?: {
    code?: string;
    message?: string;
  };
};

type GuestSupportOperation = {
  name: string;
  rejectWith: (error: unknown) => void;
  invoke: () => Promise<unknown>;
};

const operations: GuestSupportOperation[] = [
  {
    name: 'list',
    rejectWith: (error) => {
      vi.spyOn(http, 'get').mockRejectedValueOnce(error);
    },
    invoke: () => listMySupportTickets(),
  },
  {
    name: 'detail',
    rejectWith: (error) => {
      vi.spyOn(http, 'get').mockRejectedValueOnce(error);
    },
    invoke: () => loadMySupportTicket(41),
  },
  {
    name: 'add-message',
    rejectWith: (error) => {
      vi.spyOn(http, 'post').mockRejectedValueOnce(error);
    },
    invoke: () => addMySupportTicketMessage(41, 'Только текст'),
  },
];

const failures = [
  {
    status: 403,
    code: 'support_ticket_forbidden',
    body: {
      error: {
        code: 'support_ticket_forbidden',
        message: 'RAW_PRIVATE_403_BODY telegram=991',
      },
    },
  },
  {
    status: 404,
    code: 'support_ticket_not_found',
    body: {
      code: 'support_ticket_not_found',
      message: 'RAW_PRIVATE_404_BODY foreign-ticket=41',
    },
  },
  {
    status: 500,
    code: 'internal_error',
    body: {
      code: 'internal_error',
      message: 'RAW_PRIVATE_500_BODY SQL owner_predicate',
    },
  },
] as const;

function axiosFailure(status: number, data: ApiFailurePayload): AxiosError<ApiFailurePayload> {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response: AxiosResponse<ApiFailurePayload> = {
    data,
    status,
    statusText: 'Failure',
    headers: new AxiosHeaders(),
    config,
  };
  return new AxiosError(
    'RAW_AXIOS_TRANSPORT_MESSAGE',
    AxiosError.ERR_BAD_RESPONSE,
    config,
    undefined,
    response,
  );
}

async function captureFailure(operation: GuestSupportOperation, error: unknown): Promise<unknown> {
  operation.rejectWith(error);
  try {
    await operation.invoke();
    throw new Error('Expected guest support operation to reject');
  } catch (caught) {
    return caught;
  }
}

describe('guest support API contract', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('uses only owner-safe routes and never supplies client-derived identity or club authority', async () => {
    const signal = new AbortController().signal;
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValueOnce({ data: [] } as never)
      .mockResolvedValueOnce({ data: { ticket: {}, messages: [] } } as never);
    const post = vi.spyOn(http, 'post').mockResolvedValueOnce({ data: { messageId: 10 } } as never);

    await listMySupportTickets(signal);
    await loadMySupportTicket(41, signal);
    await addMySupportTicketMessage(41, 'Только текст', signal);

    expect(get).toHaveBeenNthCalledWith(1, '/api/support/tickets/my', { signal });
    expect(get).toHaveBeenNthCalledWith(2, '/api/support/tickets/my/41', { signal });
    expect(post).toHaveBeenCalledWith(
      '/api/support/tickets/41/messages',
      { text: 'Только текст' },
      { signal },
    );
    const serializedCalls = JSON.stringify([...get.mock.calls, ...post.mock.calls]);
    expect(serializedCalls).not.toMatch(/userId|telegramId|ownerId|clubId|role|permission/i);
  });

  describe.each(operations)('$name normalization', (operation) => {
    it.each(failures)(
      'keeps $status/$code actionable while replacing raw response details with a bounded message',
      async ({ status, code, body }) => {
        const normalized = await captureFailure(operation, axiosFailure(status, body));

        expect(normalized).toBeInstanceOf(GuestSupportApiError);
        expect(normalized).toMatchObject({
          message: 'Ошибка запроса',
          status,
          code,
          isAbort: false,
        });
        expect(isGuestSupportRequestCanceled(normalized)).toBe(false);
        expect(String(normalized)).not.toMatch(/RAW_|telegram=|foreign-ticket|SQL|owner_predicate/);
        expect(JSON.stringify(normalized)).not.toMatch(
          /RAW_|telegram=|foreign-ticket|SQL|owner_predicate|response/,
        );
        expect(normalized).not.toHaveProperty('response');
      },
    );

    it('normalizes Axios cancellation distinctly without retaining transport detail', async () => {
      const normalized = await captureFailure(
        operation,
        new CanceledError('RAW_CANCELED_REQUEST_DETAIL'),
      );

      expect(normalized).toBeInstanceOf(GuestSupportApiError);
      expect(normalized).toMatchObject({
        message: 'Запрос отменен',
        isAbort: true,
      });
      expect((normalized as GuestSupportApiError).status).toBeUndefined();
      expect((normalized as GuestSupportApiError).code).toBeUndefined();
      expect(isGuestSupportRequestCanceled(normalized)).toBe(true);
      expect(String(normalized)).not.toContain('RAW_CANCELED_REQUEST_DETAIL');
      expect(JSON.stringify(normalized)).not.toContain('RAW_CANCELED_REQUEST_DETAIL');
    });
  });

  it('feeds a bounded real-normalizer failure into the guest support component', async () => {
    Object.defineProperty(window, 'scrollTo', { configurable: true, value: vi.fn() });
    vi.spyOn(http, 'get')
      .mockResolvedValueOnce({
        data: [
          {
            id: 41,
            clubId: 7,
            topic: 'other',
            status: 'new',
            updatedAt: '2026-08-21T10:00:00Z',
            lastMessagePreview: 'Persisted question',
            lastSenderType: 'guest',
          },
        ],
      } as never)
      .mockResolvedValueOnce({
        data: {
          ticket: {
            id: 41,
            clubId: 7,
            topic: 'other',
            status: 'new',
            createdAt: '2026-08-21T09:00:00Z',
            updatedAt: '2026-08-21T10:00:00Z',
          },
          messages: [
            {
              id: 101,
              senderType: 'guest',
              text: 'Persisted question',
              attachments: null,
              createdAt: '2026-08-21T09:00:00Z',
            },
          ],
        },
      } as never);
    const post = vi.spyOn(http, 'post').mockRejectedValueOnce(
      axiosFailure(500, {
        code: 'internal_error',
        message: 'RAW_COMPONENT_PRIVATE_BODY SQL telegram=991',
      }),
    );

    render(createElement(GuestSupportShell));
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть обращение 41' }));
    await screen.findByText('Persisted question');
    fireEvent.change(screen.getByLabelText('Новое сообщение'), {
      target: { value: 'Продолжение' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Отправить' }));

    expect(await screen.findByText('Не удалось отправить сообщение')).toBeTruthy();
    expect(document.body.textContent).not.toMatch(
      /RAW_COMPONENT_PRIVATE_BODY|RAW_AXIOS_TRANSPORT_MESSAGE|SQL|telegram=991/,
    );
    expect(screen.getByText('Persisted question')).toBeTruthy();
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith(
      '/api/support/tickets/41/messages',
      { text: 'Продолжение' },
      { signal: undefined },
    );
  });
});
