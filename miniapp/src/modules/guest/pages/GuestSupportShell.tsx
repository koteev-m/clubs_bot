import { useCallback, useEffect, useRef, useState } from 'react';
import {
  addMySupportTicketMessage,
  GUEST_SUPPORT_MESSAGE_MAX_LENGTH,
  GuestSupportApiError,
  GuestSupportTicketMessage,
  GuestSupportTicketThread,
  isGuestSupportRequestCanceled,
  listMySupportTickets,
  loadMySupportTicket,
  SupportTicketSummary,
  ticketStatusLabels,
  ticketTopicLabels,
  TicketStatus,
  TicketTopic,
} from '../api/support.api';

type RequestStatus = 'idle' | 'loading' | 'ready' | 'error';

const senderLabels: Record<string, string> = {
  guest: 'Вы',
  agent: 'Поддержка',
  system: 'Система',
};

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('ru-RU');
}

function topicLabel(topic: string): string {
  return topic in ticketTopicLabels
    ? ticketTopicLabels[topic as TicketTopic]
    : 'Другая тема';
}

function statusLabel(status: string): string {
  return status in ticketStatusLabels
    ? ticketStatusLabels[status as TicketStatus]
    : 'Статус недоступен';
}

function canGuestContinue(status: string): boolean {
  return status === 'new' || status === 'in_progress' || status === 'resolved';
}

function senderLabel(senderType: string): string {
  return senderLabels[senderType] ?? 'Сообщение';
}

function ThreadMessage({ message }: { message: GuestSupportTicketMessage }) {
  return (
    <article
      className="rounded-lg border border-gray-100 bg-gray-50 p-3"
      data-testid="guest-support-message"
    >
      <div className="flex items-start justify-between gap-3">
        <span className="text-xs font-medium text-gray-700">{senderLabel(message.senderType)}</span>
        <time className="text-xs text-gray-500">{formatDate(message.createdAt)}</time>
      </div>
      <p className="mt-2 whitespace-pre-wrap break-words text-sm text-gray-900">{message.text}</p>
    </article>
  );
}

/** Bounded owner-only support surface. It intentionally does not mount the broader GuestShell. */
export default function GuestSupportShell() {
  const [tickets, setTickets] = useState<SupportTicketSummary[]>([]);
  const [ticketsStatus, setTicketsStatus] = useState<RequestStatus>('loading');
  const [ticketsError, setTicketsError] = useState<string | null>(null);
  const [ticketsRefresh, setTicketsRefresh] = useState(0);
  const [listNotice, setListNotice] = useState<string | null>(null);

  const [selectedTicketId, setSelectedTicketId] = useState<number | null>(null);
  const [thread, setThread] = useState<GuestSupportTicketThread | null>(null);
  const [threadStatus, setThreadStatus] = useState<RequestStatus>('idle');
  const [threadError, setThreadError] = useState<string | null>(null);
  const [threadRefresh, setThreadRefresh] = useState(0);

  const [messageText, setMessageText] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [mutationSuccess, setMutationSuccess] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const ticketsRequestId = useRef(0);
  const threadRequestId = useRef(0);
  const mutationInFlight = useRef(false);

  const clearSelectedTicket = useCallback(() => {
    threadRequestId.current += 1;
    setSelectedTicketId(null);
    setThread(null);
    setThreadStatus('idle');
    setThreadError(null);
    setMessageText('');
    setIsSubmitting(false);
    setMutationError(null);
    setMutationSuccess(null);
    mutationInFlight.current = false;
  }, []);

  const handleForbidden = useCallback(() => {
    ticketsRequestId.current += 1;
    threadRequestId.current += 1;
    setTickets([]);
    setTicketsStatus('idle');
    setTicketsError(null);
    setListNotice(null);
    setSelectedTicketId(null);
    setThread(null);
    setThreadStatus('idle');
    setThreadError(null);
    setMessageText('');
    setIsSubmitting(false);
    setMutationError(null);
    setMutationSuccess(null);
    mutationInFlight.current = false;
    setForbidden(true);
  }, []);

  const handleNotFound = useCallback(() => {
    clearSelectedTicket();
    setListNotice('Обращение не найдено');
    setTicketsRefresh((value) => value + 1);
  }, [clearSelectedTicket]);

  useEffect(() => {
    if (forbidden) return;

    const controller = new AbortController();
    const requestId = ++ticketsRequestId.current;
    setTickets([]);
    setTicketsStatus('loading');
    setTicketsError(null);

    listMySupportTickets(controller.signal)
      .then((data) => {
        if (requestId !== ticketsRequestId.current) return;
        setTickets(data);
        setTicketsStatus('ready');
      })
      .catch((error: GuestSupportApiError) => {
        if (requestId !== ticketsRequestId.current || isGuestSupportRequestCanceled(error)) return;
        if (error.status === 403) {
          handleForbidden();
          return;
        }
        setTicketsError('Не удалось загрузить обращения');
        setTicketsStatus('error');
      });

    return () => controller.abort();
  }, [forbidden, handleForbidden, ticketsRefresh]);

  useEffect(() => {
    if (forbidden || selectedTicketId === null) return;

    const controller = new AbortController();
    const requestId = ++threadRequestId.current;
    setThread(null);
    setThreadStatus('loading');
    setThreadError(null);

    loadMySupportTicket(selectedTicketId, controller.signal)
      .then((data) => {
        if (requestId !== threadRequestId.current) return;
        setThread(data);
        setThreadStatus('ready');
        if (!canGuestContinue(data.ticket.status)) setMessageText('');
      })
      .catch((error: GuestSupportApiError) => {
        if (requestId !== threadRequestId.current || isGuestSupportRequestCanceled(error)) return;
        if (error.status === 403) {
          handleForbidden();
          return;
        }
        if (error.status === 404) {
          handleNotFound();
          return;
        }
        setThreadError('Не удалось загрузить обращение');
        setThreadStatus('error');
      });

    return () => controller.abort();
  }, [forbidden, handleForbidden, handleNotFound, selectedTicketId, threadRefresh]);

  const handleOpenTicket = useCallback((ticketId: number) => {
    threadRequestId.current += 1;
    setThread(null);
    setThreadStatus('loading');
    setThreadError(null);
    setMessageText('');
    setMutationError(null);
    setMutationSuccess(null);
    setListNotice(null);
    setSelectedTicketId(ticketId);
    window.scrollTo(0, 0);
  }, []);

  const refreshSelectedTicketAndList = useCallback(() => {
    setTicketsRefresh((value) => value + 1);
    setThreadRefresh((value) => value + 1);
  }, []);

  const trimmedMessage = messageText.trim();
  const canContinue = thread !== null && canGuestContinue(thread.ticket.status);
  const isMessageValid =
    trimmedMessage.length > 0 && trimmedMessage.length <= GUEST_SUPPORT_MESSAGE_MAX_LENGTH;

  const handleSubmit = useCallback(async () => {
    if (
      mutationInFlight.current ||
      !thread ||
      !canGuestContinue(thread.ticket.status) ||
      !isMessageValid
    ) {
      return;
    }

    mutationInFlight.current = true;
    setIsSubmitting(true);
    setMutationError(null);
    setMutationSuccess(null);
    try {
      await addMySupportTicketMessage(thread.ticket.id, trimmedMessage);
      setMessageText('');
      setMutationSuccess('Сообщение отправлено');
      refreshSelectedTicketAndList();
    } catch (error) {
      if (isGuestSupportRequestCanceled(error)) return;
      const apiError = error instanceof GuestSupportApiError ? error : null;
      if (apiError?.status === 403) {
        handleForbidden();
        return;
      }
      if (apiError?.status === 404) {
        handleNotFound();
        return;
      }
      if (apiError?.status === 409) {
        setMutationError('Состояние обращения изменилось. Данные обновляются.');
        refreshSelectedTicketAndList();
        return;
      }
      setMutationError('Не удалось отправить сообщение');
    } finally {
      mutationInFlight.current = false;
      setIsSubmitting(false);
    }
  }, [handleForbidden, handleNotFound, isMessageValid, refreshSelectedTicketAndList, thread, trimmedMessage]);

  if (forbidden) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center bg-gray-50 px-4 text-center">
        <h1 className="text-lg font-semibold text-gray-900">Нет доступа к обращениям</h1>
        <p className="mt-2 text-sm text-gray-500">Не удалось подтвердить доступ к вашим обращениям.</p>
      </main>
    );
  }

  return (
    <div className="min-h-screen overflow-x-hidden bg-gray-50">
      <header
        className="sticky top-0 z-10 bg-white px-4 pb-3 shadow-sm"
        style={{ paddingTop: 'calc(0.75rem + env(safe-area-inset-top, 0px))' }}
        data-testid="guest-support-header"
      >
        <h1 className="text-base font-semibold text-gray-900">Мои обращения</h1>
      </header>

      <main
        className="mx-auto w-full max-w-xl space-y-4 px-4 pt-4"
        style={{ paddingBottom: 'calc(1.5rem + env(safe-area-inset-bottom, 0px))' }}
        data-testid="guest-support-content"
      >
        {selectedTicketId === null ? (
          <section className="rounded-lg bg-white p-4 shadow-sm" aria-label="Список обращений">
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-sm font-semibold text-gray-900">Ваши обращения</h2>
              <button
                type="button"
                className="text-sm text-blue-600 disabled:opacity-50"
                disabled={ticketsStatus === 'loading'}
                onClick={() => {
                  setListNotice(null);
                  setTicketsRefresh((value) => value + 1);
                }}
              >
                Обновить
              </button>
            </div>

            {listNotice && (
              <p className="mt-3 text-sm text-red-600" role="status">
                {listNotice}
              </p>
            )}
            {ticketsStatus === 'loading' && (
              <p className="mt-4 text-sm text-gray-500">Загрузка обращений...</p>
            )}
            {ticketsStatus === 'error' && (
              <div className="mt-4 space-y-2">
                <p className="text-sm text-red-600">{ticketsError}</p>
                <button
                  type="button"
                  className="text-sm text-blue-600"
                  onClick={() => setTicketsRefresh((value) => value + 1)}
                >
                  Повторить
                </button>
              </div>
            )}
            {ticketsStatus === 'ready' && tickets.length === 0 && (
              <p className="mt-4 text-sm text-gray-500">Пока нет обращений.</p>
            )}
            {ticketsStatus === 'ready' && tickets.length > 0 && (
              <div className="mt-4 space-y-3">
                {tickets.map((ticket) => (
                  <button
                    key={ticket.id}
                    type="button"
                    className="block w-full rounded-lg border border-gray-100 p-3 text-left"
                    aria-label={`Открыть обращение ${ticket.id}`}
                    onClick={() => handleOpenTicket(ticket.id)}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="text-sm font-medium text-gray-900">{topicLabel(ticket.topic)}</p>
                        <p className="mt-1 text-xs text-gray-500">Обновлено: {formatDate(ticket.updatedAt)}</p>
                      </div>
                      <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-700">
                        {statusLabel(ticket.status)}
                      </span>
                    </div>
                    <p className="mt-2 line-clamp-3 whitespace-pre-wrap break-words text-sm text-gray-600">
                      {ticket.lastMessagePreview || 'Нет сообщений'}
                    </p>
                  </button>
                ))}
              </div>
            )}
          </section>
        ) : (
          <section className="rounded-lg bg-white p-4 shadow-sm" aria-label="Обращение">
            <div className="flex items-center justify-between gap-3">
              <button
                type="button"
                className="text-sm text-blue-600 disabled:opacity-50"
                disabled={isSubmitting}
                onClick={clearSelectedTicket}
              >
                ← К обращениям
              </button>
              <button
                type="button"
                className="text-sm text-blue-600 disabled:opacity-50"
                disabled={threadStatus === 'loading' || isSubmitting}
                onClick={refreshSelectedTicketAndList}
              >
                Обновить
              </button>
            </div>

            {mutationSuccess && (
              <p className="mt-4 text-sm text-green-700" role="status">
                {mutationSuccess}
              </p>
            )}
            {mutationError && (
              <p className="mt-4 text-sm text-red-600" role="alert">
                {mutationError}
              </p>
            )}
            {threadStatus === 'loading' && (
              <p className="mt-4 text-sm text-gray-500">Загрузка обращения...</p>
            )}
            {threadStatus === 'error' && (
              <div className="mt-4 space-y-2">
                <p className="text-sm text-red-600">{threadError}</p>
                <button
                  type="button"
                  className="text-sm text-blue-600"
                  onClick={() => setThreadRefresh((value) => value + 1)}
                >
                  Повторить
                </button>
              </div>
            )}
            {threadStatus === 'ready' && thread && (
              <div className="mt-4">
                <div className="border-b border-gray-100 pb-4">
                  <div className="flex items-start justify-between gap-3">
                    <h2 className="text-base font-semibold text-gray-900">
                      {topicLabel(thread.ticket.topic)}
                    </h2>
                    <span
                      className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-700"
                      data-testid="guest-support-ticket-status"
                    >
                      {statusLabel(thread.ticket.status)}
                    </span>
                  </div>
                  <p className="mt-2 text-xs text-gray-500">
                    Создано: {formatDate(thread.ticket.createdAt)}
                  </p>
                  <p className="mt-1 text-xs text-gray-500">
                    Обновлено: {formatDate(thread.ticket.updatedAt)}
                  </p>
                </div>

                <div className="mt-4 space-y-3" aria-label="История обращения">
                  {thread.messages.map((message) => (
                    <ThreadMessage key={message.id} message={message} />
                  ))}
                </div>

                {canContinue && (
                  <form
                    className="mt-4 space-y-2 border-t border-gray-100 pt-4"
                    onSubmit={(event) => {
                      event.preventDefault();
                      void handleSubmit();
                    }}
                  >
                    <label className="block text-sm text-gray-600" htmlFor="guest-support-message">
                      Новое сообщение
                      <textarea
                        id="guest-support-message"
                        className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm text-gray-900"
                        rows={4}
                        maxLength={GUEST_SUPPORT_MESSAGE_MAX_LENGTH}
                        value={messageText}
                        disabled={isSubmitting}
                        onChange={(event) => {
                          setMessageText(event.target.value);
                          setMutationError(null);
                          setMutationSuccess(null);
                        }}
                      />
                    </label>
                    <div className="flex items-center justify-between gap-3">
                      <span className="text-xs text-gray-500">
                        {messageText.length}/{GUEST_SUPPORT_MESSAGE_MAX_LENGTH}
                      </span>
                      <button
                        type="submit"
                        className="rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                        disabled={!isMessageValid || isSubmitting}
                      >
                        {isSubmitting ? 'Отправка...' : 'Отправить'}
                      </button>
                    </div>
                  </form>
                )}
              </div>
            )}
          </section>
        )}
      </main>
    </div>
  );
}
