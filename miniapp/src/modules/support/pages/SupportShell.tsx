import { useCallback, useEffect, useRef, useState } from 'react';
import {
  closeSupportTicket,
  getSupportTicket,
  isSupportRequestCanceled,
  listPermittedSupportClubs,
  listSupportTickets,
  replyToSupportTicket,
  resolveSupportTicket,
  SUPPORT_REPLY_MAX_LENGTH,
  SupportApiError,
  SupportClub,
  SupportStatusMutationResponse,
  SupportTicketStatus,
  SupportTicketSummary,
  SupportTicketThread,
  supportTicketStatuses,
  takeSupportTicketInWork,
} from '../api/support.api';

type RequestStatus = 'idle' | 'loading' | 'ready' | 'error';
type MutationAction = 'take' | 'reply' | 'resolve' | 'close';

const statusLabels: Record<SupportTicketStatus, string> = {
  new: 'Новое',
  opened: 'Открыто',
  in_progress: 'В работе',
  answered: 'Отвечено',
  resolved: 'Решено',
  closed: 'Закрыто',
};

const mutationFailureMessages: Record<MutationAction, string> = {
  take: 'Не удалось взять обращение в работу',
  reply: 'Не удалось сохранить ответ',
  resolve: 'Не удалось решить обращение',
  close: 'Не удалось закрыть обращение',
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

/** Operational support surface. Server responses are the only source of authorization scope. */
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
  const [ticketNotice, setTicketNotice] = useState<string | null>(null);
  const [replyText, setReplyText] = useState('');
  const [resolveConfirmationTicketId, setResolveConfirmationTicketId] = useState<number | null>(null);
  const [mutationAction, setMutationAction] = useState<MutationAction | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [mutationSuccess, setMutationSuccess] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const clubsRequestId = useRef(0);
  const ticketsRequestId = useRef(0);
  const threadRequestId = useRef(0);
  const mutationInFlight = useRef(false);

  const clearThread = useCallback(() => {
    threadRequestId.current += 1;
    setSelectedTicketId(null);
    setThread(null);
    setThreadStatus('idle');
    setThreadError(null);
    setTicketNotice(null);
    setReplyText('');
    setResolveConfirmationTicketId(null);
    setMutationError(null);
    setMutationSuccess(null);
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
    setTicketNotice(null);
    setReplyText('');
    setResolveConfirmationTicketId(null);
    setMutationAction(null);
    setMutationError(null);
    setMutationSuccess(null);
    mutationInFlight.current = false;
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
    if (clubsStatus !== 'ready' || selectedClubId === null) return;
    if (clubs.some((club) => club.id === selectedClubId)) return;
    clearTicketsAndThread();
    setSelectedClubId(null);
  }, [clearTicketsAndThread, clubs, clubsStatus, selectedClubId]);

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
    setTicketNotice(null);
    setReplyText('');
    setResolveConfirmationTicketId(null);
    setMutationError(null);
    setMutationSuccess(null);
    setSelectedTicketId(ticketId);
    window.scrollTo(0, 0);
  }, []);

  const refreshSelectedTicketAndList = useCallback(() => {
    setTicketsRefresh((value) => value + 1);
    setThreadRefresh((value) => value + 1);
  }, []);

  const handleMutationForbidden = useCallback(() => {
    setClubs([]);
    setClubsStatus('loading');
    setReplyText('');
    setResolveConfirmationTicketId(null);
    setMutationAction(null);
    setMutationSuccess(null);
    setMutationError('Права доступа изменились. Доступные действия обновляются.');
    mutationInFlight.current = false;
    setClubsRefresh((value) => value + 1);
  }, []);

  const handleMutationNotFound = useCallback(() => {
    threadRequestId.current += 1;
    setSelectedTicketId(null);
    setThread(null);
    setThreadStatus('idle');
    setThreadError(null);
    setReplyText('');
    setResolveConfirmationTicketId(null);
    setMutationAction(null);
    setMutationError(null);
    setMutationSuccess(null);
    mutationInFlight.current = false;
    setTicketNotice('Обращение не найдено');
    setTicketsRefresh((value) => value + 1);
  }, []);

  const handleMutationFailure = useCallback(
    (action: MutationAction, error: unknown) => {
      if (isSupportRequestCanceled(error)) return;
      const apiError = error instanceof SupportApiError ? error : null;
      if (apiError?.status === 403) {
        handleMutationForbidden();
        return;
      }
      if (apiError?.status === 404) {
        handleMutationNotFound();
        return;
      }
      if (apiError?.status === 409) {
        setResolveConfirmationTicketId(null);
        setMutationSuccess(null);
        setMutationError('Состояние обращения изменилось. Данные обновляются.');
        refreshSelectedTicketAndList();
        return;
      }
      setMutationSuccess(null);
      setMutationError(mutationFailureMessages[action]);
    },
    [handleMutationForbidden, handleMutationNotFound, refreshSelectedTicketAndList],
  );

  const selectedTicketClub = thread
    ? clubs.find((club) => club.id === thread.ticket.clubId)
    : undefined;
  const canTakeInWork =
    thread?.ticket.status === 'new' && selectedTicketClub?.canTakeInWork === true;
  const canReply =
    (thread?.ticket.status === 'new' || thread?.ticket.status === 'in_progress') &&
    selectedTicketClub?.canReply === true;
  const canResolve =
    thread?.ticket.status === 'in_progress' && selectedTicketClub?.canManageStatus === true;
  const canClose =
    thread?.ticket.status === 'resolved' && selectedTicketClub?.canManageStatus === true;
  const resolveConfirmationOpen =
    thread !== null && resolveConfirmationTicketId === thread.ticket.id;
  const trimmedReplyText = replyText.trim();
  const isReplyValid =
    trimmedReplyText.length > 0 && trimmedReplyText.length <= SUPPORT_REPLY_MAX_LENGTH;

  const applyStatusMutation = useCallback((updatedTicket: SupportStatusMutationResponse) => {
    setThread((current) => {
      if (!current || current.ticket.id !== updatedTicket.id) return current;
      return {
        ...current,
        ticket: {
          ...current.ticket,
          status: updatedTicket.status,
          updatedAt: updatedTicket.updatedAt,
        },
      };
    });
  }, []);

  const handleTakeInWork = useCallback(async () => {
    if (mutationInFlight.current || !thread || !canTakeInWork) return;
    mutationInFlight.current = true;
    setMutationAction('take');
    setMutationError(null);
    setMutationSuccess(null);
    try {
      await takeSupportTicketInWork(thread.ticket.id);
      setMutationSuccess('Обращение взято в работу');
      refreshSelectedTicketAndList();
    } catch (error) {
      handleMutationFailure('take', error);
    } finally {
      mutationInFlight.current = false;
      setMutationAction(null);
    }
  }, [canTakeInWork, handleMutationFailure, refreshSelectedTicketAndList, thread]);

  const handleReply = useCallback(async () => {
    if (mutationInFlight.current || !thread || !canReply || !isReplyValid) return;
    mutationInFlight.current = true;
    setMutationAction('reply');
    setMutationError(null);
    setMutationSuccess(null);
    try {
      await replyToSupportTicket(thread.ticket.id, trimmedReplyText);
      setReplyText('');
      setMutationSuccess('Ответ сохранён');
      refreshSelectedTicketAndList();
    } catch (error) {
      handleMutationFailure('reply', error);
    } finally {
      mutationInFlight.current = false;
      setMutationAction(null);
    }
  }, [canReply, handleMutationFailure, isReplyValid, refreshSelectedTicketAndList, thread, trimmedReplyText]);

  const handleShowResolveConfirmation = useCallback(() => {
    if (mutationInFlight.current || !thread || !canResolve) return;
    setMutationError(null);
    setMutationSuccess(null);
    setResolveConfirmationTicketId(thread.ticket.id);
  }, [canResolve, thread]);

  const handleResolve = useCallback(async () => {
    if (
      mutationInFlight.current ||
      !thread ||
      !canResolve ||
      resolveConfirmationTicketId !== thread.ticket.id
    ) {
      return;
    }
    mutationInFlight.current = true;
    setMutationAction('resolve');
    setMutationError(null);
    setMutationSuccess(null);
    try {
      const updatedTicket = await resolveSupportTicket(thread.ticket.id);
      setResolveConfirmationTicketId(null);
      applyStatusMutation(updatedTicket);
      setMutationSuccess('Обращение решено.');
      refreshSelectedTicketAndList();
    } catch (error) {
      handleMutationFailure('resolve', error);
    } finally {
      mutationInFlight.current = false;
      setMutationAction(null);
    }
  }, [
    applyStatusMutation,
    canResolve,
    handleMutationFailure,
    refreshSelectedTicketAndList,
    resolveConfirmationTicketId,
    thread,
  ]);

  const handleClose = useCallback(async () => {
    if (mutationInFlight.current || !thread || !canClose) return;
    mutationInFlight.current = true;
    setMutationAction('close');
    setMutationError(null);
    setMutationSuccess(null);
    try {
      const updatedTicket = await closeSupportTicket(thread.ticket.id);
      applyStatusMutation(updatedTicket);
      setMutationSuccess('Обращение закрыто.');
      refreshSelectedTicketAndList();
    } catch (error) {
      handleMutationFailure('close', error);
    } finally {
      mutationInFlight.current = false;
      setMutationAction(null);
    }
  }, [applyStatusMutation, canClose, handleMutationFailure, refreshSelectedTicketAndList, thread]);

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
            {ticketNotice && (
              <p className="mt-3 text-sm text-red-600" role="status">
                {ticketNotice}
              </p>
            )}
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
            <button
              type="button"
              className="text-sm text-blue-600 disabled:opacity-50"
              disabled={mutationAction !== null}
              onClick={clearThread}
            >
              ← К обращениям
            </button>

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

                {(canTakeInWork || canReply || canResolve || canClose) && (
                  <div className="mt-4 space-y-4 border-b border-gray-100 pb-4">
                    {canTakeInWork && (
                      <button
                        type="button"
                        className="w-full rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                        disabled={mutationAction !== null}
                        onClick={() => void handleTakeInWork()}
                      >
                        {mutationAction === 'take' ? 'Сохранение...' : 'Взять в работу'}
                      </button>
                    )}
                    {canReply && (
                      <form
                        className="space-y-2"
                        onSubmit={(event) => {
                          event.preventDefault();
                          void handleReply();
                        }}
                      >
                        <label className="block text-sm text-gray-600" htmlFor="support-reply">
                          Ответ
                          <textarea
                            id="support-reply"
                            className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm text-gray-900"
                            rows={4}
                            maxLength={SUPPORT_REPLY_MAX_LENGTH}
                            value={replyText}
                            disabled={mutationAction !== null}
                            onChange={(event) => {
                              setReplyText(event.target.value);
                              setMutationError(null);
                              setMutationSuccess(null);
                            }}
                          />
                        </label>
                        <div className="flex items-center justify-between gap-3">
                          <span className="text-xs text-gray-500">
                            {replyText.length}/{SUPPORT_REPLY_MAX_LENGTH}
                          </span>
                          <button
                            type="submit"
                            className="rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                            disabled={!isReplyValid || mutationAction !== null}
                          >
                            {mutationAction === 'reply' ? 'Сохранение...' : 'Сохранить ответ'}
                          </button>
                        </div>
                      </form>
                    )}
                    {canResolve && !resolveConfirmationOpen && (
                      <button
                        type="button"
                        className="w-full rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                        disabled={mutationAction !== null}
                        onClick={handleShowResolveConfirmation}
                      >
                        Решить обращение
                      </button>
                    )}
                    {canResolve && resolveConfirmationOpen && (
                      <div
                        className="space-y-3 rounded border border-gray-200 p-3"
                        role="group"
                        aria-label="Подтверждение решения обращения"
                      >
                        <p className="text-sm text-gray-700">Подтвердите, что обращение решено.</p>
                        <div className="flex items-center justify-end gap-3">
                          <button
                            type="button"
                            className="rounded px-4 py-2 text-sm font-medium text-gray-700 disabled:opacity-50"
                            disabled={mutationAction !== null}
                            onClick={() => setResolveConfirmationTicketId(null)}
                          >
                            Отмена
                          </button>
                          <button
                            type="button"
                            className="rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                            disabled={mutationAction !== null}
                            onClick={() => void handleResolve()}
                          >
                            {mutationAction === 'resolve' ? 'Сохранение...' : 'Подтвердить'}
                          </button>
                        </div>
                      </div>
                    )}
                    {canClose && (
                      <button
                        type="button"
                        className="w-full rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                        disabled={mutationAction !== null}
                        onClick={() => void handleClose()}
                      >
                        {mutationAction === 'close' ? 'Сохранение...' : 'Закрыть обращение'}
                      </button>
                    )}
                  </div>
                )}

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
