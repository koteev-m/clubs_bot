import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getSupportTicket,
  isSupportRequestCanceled,
  listPermittedSupportClubs,
  listSupportTickets,
  SupportApiError,
  SupportClub,
  SupportTicketStatus,
  SupportTicketSummary,
  SupportTicketThread,
  supportTicketStatuses,
} from '../api/support.api';

type RequestStatus = 'idle' | 'loading' | 'ready' | 'error';

const statusLabels: Record<SupportTicketStatus, string> = {
  new: 'Новое',
  opened: 'Открыто',
  in_progress: 'В работе',
  answered: 'Отвечено',
  closed: 'Закрыто',
};

const topicLabels: Record<string, string> = {
  address: 'Адрес',
  dresscode: 'Дресс-код',
  booking: 'Бронирование',
  invite: 'Приглашения',
  lost_found: 'Потерянные вещи',
  complaint: 'Жалоба',
  other: 'Другое',
};

const senderLabels: Record<string, string> = {
  guest: 'Гость',
  agent: 'Поддержка',
  system: 'Система',
};

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('ru-RU');
}

function supportErrorMessage(scope: 'clubs' | 'tickets' | 'detail', error: SupportApiError): string {
  if (scope === 'detail' && error.status === 404) return 'Обращение не найдено';
  if (scope === 'clubs') return 'Не удалось загрузить доступные клубы';
  if (scope === 'tickets') return 'Не удалось загрузить обращения';
  return 'Не удалось загрузить обращение';
}

function removeSupportMode() {
  const url = new URL(window.location.href);
  url.searchParams.delete('mode');
  window.location.assign(url.toString());
}

/** Read-only operational support surface. Server responses are the only source of authorization scope. */
export default function SupportShell() {
  const [clubs, setClubs] = useState<SupportClub[]>([]);
  const [clubsStatus, setClubsStatus] = useState<RequestStatus>('loading');
  const [clubsError, setClubsError] = useState<string | null>(null);
  const [clubsRefresh, setClubsRefresh] = useState(0);

  const [selectedClubId, setSelectedClubId] = useState<number | null>(null);
  const [statusFilter, setStatusFilter] = useState<SupportTicketStatus | ''>('');
  const [tickets, setTickets] = useState<SupportTicketSummary[]>([]);
  const [ticketsStatus, setTicketsStatus] = useState<RequestStatus>('idle');
  const [ticketsError, setTicketsError] = useState<string | null>(null);
  const [ticketsRefresh, setTicketsRefresh] = useState(0);

  const [selectedTicketId, setSelectedTicketId] = useState<number | null>(null);
  const [thread, setThread] = useState<SupportTicketThread | null>(null);
  const [threadStatus, setThreadStatus] = useState<RequestStatus>('idle');
  const [threadError, setThreadError] = useState<string | null>(null);
  const [threadRefresh, setThreadRefresh] = useState(0);
  const [forbidden, setForbidden] = useState(false);

  const clubsRequestId = useRef(0);
  const ticketsRequestId = useRef(0);
  const threadRequestId = useRef(0);

  const clearThread = useCallback(() => {
    threadRequestId.current += 1;
    setSelectedTicketId(null);
    setThread(null);
    setThreadStatus('idle');
    setThreadError(null);
  }, []);

  const clearTicketsAndThread = useCallback(() => {
    ticketsRequestId.current += 1;
    setTickets([]);
    setTicketsStatus('idle');
    setTicketsError(null);
    clearThread();
  }, [clearThread]);

  const handleForbidden = useCallback(() => {
    clubsRequestId.current += 1;
    ticketsRequestId.current += 1;
    threadRequestId.current += 1;
    setClubs([]);
    setClubsStatus('idle');
    setClubsError(null);
    setSelectedClubId(null);
    setTickets([]);
    setTicketsStatus('idle');
    setTicketsError(null);
    setSelectedTicketId(null);
    setThread(null);
    setThreadStatus('idle');
    setThreadError(null);
    setForbidden(true);
  }, []);

  useEffect(() => {
    if (forbidden) return;

    const controller = new AbortController();
    const requestId = ++clubsRequestId.current;
    setClubs([]);
    setClubsStatus('loading');
    setClubsError(null);

    listPermittedSupportClubs(controller.signal)
      .then((data) => {
        if (requestId !== clubsRequestId.current) return;
        setClubs(data);
        setClubsStatus('ready');
      })
      .catch((error: SupportApiError) => {
        if (requestId !== clubsRequestId.current || isSupportRequestCanceled(error)) return;
        if (error.status === 403) {
          handleForbidden();
          return;
        }
        setClubsError(supportErrorMessage('clubs', error));
        setClubsStatus('error');
      });

    return () => controller.abort();
  }, [clubsRefresh, forbidden, handleForbidden]);

  useEffect(() => {
    if (forbidden || selectedClubId === null) return;

    const controller = new AbortController();
    const requestId = ++ticketsRequestId.current;
    setTickets([]);
    setTicketsStatus('loading');
    setTicketsError(null);

    listSupportTickets(
      {
        clubId: selectedClubId,
        ...(statusFilter ? { status: statusFilter } : {}),
      },
      controller.signal,
    )
      .then((data) => {
        if (requestId !== ticketsRequestId.current) return;
        setTickets(data);
        setTicketsStatus('ready');
      })
      .catch((error: SupportApiError) => {
        if (requestId !== ticketsRequestId.current || isSupportRequestCanceled(error)) return;
        if (error.status === 403) {
          handleForbidden();
          return;
        }
        setTicketsError(supportErrorMessage('tickets', error));
        setTicketsStatus('error');
      });

    return () => controller.abort();
  }, [forbidden, handleForbidden, selectedClubId, statusFilter, ticketsRefresh]);

  useEffect(() => {
    if (forbidden || selectedTicketId === null) return;

    const controller = new AbortController();
    const requestId = ++threadRequestId.current;
    setThread(null);
    setThreadStatus('loading');
    setThreadError(null);

    getSupportTicket(selectedTicketId, controller.signal)
      .then((data) => {
        if (requestId !== threadRequestId.current) return;
        setThread(data);
        setThreadStatus('ready');
      })
      .catch((error: SupportApiError) => {
        if (requestId !== threadRequestId.current || isSupportRequestCanceled(error)) return;
        if (error.status === 403) {
          handleForbidden();
          return;
        }
        setThreadError(supportErrorMessage('detail', error));
        setThreadStatus('error');
      });

    return () => controller.abort();
  }, [forbidden, handleForbidden, selectedTicketId, threadRefresh]);

  const handleSelectClub = useCallback(
    (clubId: number | null) => {
      clearTicketsAndThread();
      setSelectedClubId(clubId);
    },
    [clearTicketsAndThread],
  );

  const handleStatusFilter = useCallback(
    (status: SupportTicketStatus | '') => {
      clearTicketsAndThread();
      setStatusFilter(status);
    },
    [clearTicketsAndThread],
  );

  const handleOpenTicket = useCallback((ticketId: number) => {
    threadRequestId.current += 1;
    setThread(null);
    setThreadStatus('loading');
    setThreadError(null);
    setSelectedTicketId(ticketId);
    window.scrollTo(0, 0);
  }, []);

  if (forbidden) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center bg-gray-50 px-4 text-center">
        <h1 className="text-lg font-semibold text-gray-900">Нет доступа к поддержке</h1>
        <p className="mt-2 text-sm text-gray-500">Проверьте назначенные права доступа.</p>
        <button
          type="button"
          className="mt-4 rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white"
          onClick={removeSupportMode}
        >
          Вернуться
        </button>
      </main>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 pb-8">
      <header className="sticky top-0 z-10 bg-white px-4 py-3 shadow-sm">
        <div className="flex items-center justify-between">
          <h1 className="text-base font-semibold text-gray-900">Поддержка</h1>
          <button type="button" className="text-sm text-blue-600" onClick={removeSupportMode}>
            Выйти
          </button>
        </div>
      </header>

      <main className="space-y-4 px-4 py-4">
        <section className="rounded-lg bg-white p-4 shadow-sm">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-gray-900">Доступный клуб</h2>
            {clubsStatus === 'error' && (
              <button type="button" className="text-sm text-blue-600" onClick={() => setClubsRefresh((value) => value + 1)}>
                Повторить
              </button>
            )}
          </div>
          {clubsStatus === 'loading' && <p className="mt-3 text-sm text-gray-500">Загрузка клубов...</p>}
          {clubsStatus === 'error' && <p className="mt-3 text-sm text-red-600">{clubsError}</p>}
          {clubsStatus === 'ready' && clubs.length === 0 && (
            <p className="mt-3 text-sm text-gray-500">Нет клубов с доступом к поддержке.</p>
          )}
          {clubsStatus === 'ready' && clubs.length > 0 && (
            <label className="mt-3 block text-sm text-gray-600" htmlFor="support-club">
              Клуб
              <select
                id="support-club"
                className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm text-gray-900"
                value={selectedClubId ?? ''}
                onChange={(event) => handleSelectClub(event.target.value ? Number(event.target.value) : null)}
              >
                <option value="">Выберите клуб</option>
                {clubs.map((club) => (
                  <option key={club.id} value={club.id}>
                    {club.name}
                  </option>
                ))}
              </select>
            </label>
          )}
        </section>

        {selectedClubId !== null && selectedTicketId === null && (
          <section className="rounded-lg bg-white p-4 shadow-sm">
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-sm font-semibold text-gray-900">Обращения</h2>
              <button
                type="button"
                className="text-sm text-blue-600 disabled:opacity-50"
                disabled={ticketsStatus === 'loading'}
                onClick={() => setTicketsRefresh((value) => value + 1)}
              >
                Обновить
              </button>
            </div>
            <label className="mt-3 block text-sm text-gray-600" htmlFor="support-status">
              Статус
              <select
                id="support-status"
                className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm text-gray-900"
                value={statusFilter}
                onChange={(event) => handleStatusFilter(event.target.value as SupportTicketStatus | '')}
              >
                <option value="">Все статусы</option>
                {supportTicketStatuses.map((status) => (
                  <option key={status} value={status}>
                    {statusLabels[status]}
                  </option>
                ))}
              </select>
            </label>

            {ticketsStatus === 'loading' && <p className="mt-4 text-sm text-gray-500">Загрузка обращений...</p>}
            {ticketsStatus === 'error' && <p className="mt-4 text-sm text-red-600">{ticketsError}</p>}
            {ticketsStatus === 'ready' && tickets.length === 0 && (
              <p className="mt-4 text-sm text-gray-500">Обращений нет.</p>
            )}
            {ticketsStatus === 'ready' && tickets.length > 0 && (
              <div className="mt-4 space-y-3">
                {tickets.map((ticket) => (
                  <button
                    key={ticket.id}
                    type="button"
                    className="block w-full rounded border border-gray-100 p-3 text-left"
                    aria-label={`Открыть обращение #${ticket.id}`}
                    onClick={() => handleOpenTicket(ticket.id)}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="text-sm font-medium text-gray-900">
                          #{ticket.id} · {topicLabels[ticket.topic] ?? ticket.topic}
                        </p>
                        <p className="mt-1 text-xs text-gray-500">{formatDate(ticket.updatedAt)}</p>
                      </div>
                      <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-700">
                        {statusLabels[ticket.status] ?? ticket.status}
                      </span>
                    </div>
                    <p className="mt-2 line-clamp-3 whitespace-pre-wrap text-sm text-gray-600">
                      {ticket.lastMessagePreview || 'Нет сообщений'}
                    </p>
                  </button>
                ))}
              </div>
            )}
          </section>
        )}

        {selectedTicketId !== null && (
          <section className="rounded-lg bg-white p-4 shadow-sm">
            <button type="button" className="text-sm text-blue-600" onClick={clearThread}>
              ← К обращениям
            </button>

            {threadStatus === 'loading' && <p className="mt-4 text-sm text-gray-500">Загрузка обращения...</p>}
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
                      #{thread.ticket.id} · {topicLabels[thread.ticket.topic] ?? thread.ticket.topic}
                    </h2>
                    <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-700">
                      {statusLabels[thread.ticket.status] ?? thread.ticket.status}
                    </span>
                  </div>
                  <p className="mt-2 text-xs text-gray-500">Создано: {formatDate(thread.ticket.createdAt)}</p>
                  <p className="mt-1 text-xs text-gray-500">Обновлено: {formatDate(thread.ticket.updatedAt)}</p>
                </div>

                <div className="mt-4 space-y-3" aria-label="Переписка">
                  {thread.messages.length === 0 && <p className="text-sm text-gray-500">Сообщений нет.</p>}
                  {thread.messages.map((message) => (
                    <article key={message.id} className="rounded border border-gray-100 p-3" data-testid="support-message">
                      <div className="flex items-center justify-between gap-3 text-xs text-gray-500">
                        <span>{senderLabels[message.senderType] ?? message.senderType}</span>
                        <time dateTime={message.createdAt}>{formatDate(message.createdAt)}</time>
                      </div>
                      <p className="mt-2 whitespace-pre-wrap break-words text-sm text-gray-900">{message.text}</p>
                      {message.attachments && (
                        <div className="mt-2 break-words text-xs text-gray-600">
                          <span className="font-medium">Вложения: </span>
                          <span>{message.attachments}</span>
                        </div>
                      )}
                    </article>
                  ))}
                </div>
              </div>
            )}
          </section>
        )}
      </main>
    </div>
  );
}
