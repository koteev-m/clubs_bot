import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { http } from '../../../shared/api/http';
import { getApiErrorInfo, isRequestCanceled } from '../../../shared/api/error';
import { useGuestStore } from '../state/guest.store';
import { format } from 'date-fns';
import { ru } from 'date-fns/locale';

interface BookingPayload {
  tableId: number;
  eventId: number;
  guestCount: number;
}

interface BookingDto {
  id: number;
  clubId: number;
  tableId: number;
  eventId: number;
  status: string;
  guestCount: number;
  plusOneUsed: boolean;
  latePlusOneAllowedUntil?: string | null;
}

interface HoldResponse {
  booking: BookingDto;
  arrivalWindow: string[];
  latePlusOneAllowedUntil?: string | null;
}

type Step = 'table' | 'guests' | 'rules' | 'confirm';
type LastAction = 'hold' | 'confirm' | 'plusOne' | null;

function createIdempotency(key: string) {
  return {
    next: (fingerprint: string) => {
      const existing = sessionStorage.getItem(key);
      if (existing) {
        try {
          const parsed = JSON.parse(existing) as { key?: string; fingerprint?: string };
          if (parsed?.key && parsed.fingerprint === fingerprint) {
            return parsed.key;
          }
        } catch {
          // ignore and regenerate
        }
      }
      const fresh = crypto.randomUUID();
      sessionStorage.setItem(key, JSON.stringify({ key: fresh, fingerprint }));
      return fresh;
    },
    clear: () => sessionStorage.removeItem(key),
  };
}

function formatInterval(range?: string[]): string {
  if (!range || range.length !== 2) return '';
  const [from, to] = range;
  try {
    return `${format(new Date(from), 'HH:mm', { locale: ru })} – ${format(new Date(to), 'HH:mm', { locale: ru })}`;
  } catch {
    return '';
  }
}

export default function BookingFlow() {
  const {
    selectedClub,
    selectedNight,
    selectedEventId,
    selectedTable,
    selectedTableCapacity,
    guests,
    setGuests,
    setTable,
    setNight,
  } = useGuestStore();
  const [step, setStep] = useState<Step>('table');
  const [name, setName] = useState('');
  const [agreeRules, setAgreeRules] = useState(false);
  const [hold, setHold] = useState<HoldResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastAction, setLastAction] = useState<LastAction>(null);
  const hasHold = Boolean(hold);
  const controller = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);
  const holdKey = useMemo(() => createIdempotency('booking-hold-key'), []);
  const confirmKey = useMemo(() => createIdempotency('booking-confirm-key'), []);
  const plusOneKey = useMemo(() => createIdempotency('booking-plus1-key'), []);

  const latePlusOneDeadline = useMemo(() => hold?.latePlusOneAllowedUntil ?? hold?.booking.latePlusOneAllowedUntil, [hold]);
  const [deadlineText, setDeadlineText] = useState('');
  const atCapacity =
    selectedTableCapacity !== undefined && selectedTableCapacity !== null && hold?.booking
      ? hold.booking.guestCount >= selectedTableCapacity
      : false;
  const handleServerError = useBookingErrorHandler({
    selectedNight,
    selectedEventId,
    setTable,
    setNight,
    resetFlow: () => {
      setHold(null);
      setStep('table');
      setAgreeRules(false);
      holdKey.clear();
      confirmKey.clear();
      plusOneKey.clear();
    },
    setError: (msg) => setError(msg),
    clearAction: () => setLastAction(null),
  });

  useEffect(() => {
    if (!latePlusOneDeadline) return;
    const update = () => {
      const diff = new Date(latePlusOneDeadline).getTime() - Date.now();
      if (diff <= 0) {
        setDeadlineText('доступно до окончания');
      } else {
        const minutes = Math.floor(diff / 60000);
        const seconds = Math.floor((diff % 60000) / 1000);
        setDeadlineText(`${minutes} мин ${seconds.toString().padStart(2, '0')} сек`);
      }
    };
    update();
    const id = window.setInterval(update, 1000);
    return () => window.clearInterval(id);
  }, [latePlusOneDeadline]);

  const cancelPendingAndInvalidate = useCallback(
    (resetLoading = false) => {
      controller.current?.abort();
      requestIdRef.current += 1;
      if (resetLoading) {
        setLoading(false);
      }
    },
    [setLoading],
  );

  useEffect(() => {
    cancelPendingAndInvalidate(true);
    setError(null);
    setLastAction(null);
  }, [cancelPendingAndInvalidate, selectedClub, selectedNight, selectedEventId, selectedTable]);

  useEffect(() => {
    if (!hold) return;
    const mismatchedContext =
      String(selectedClub) !== String(hold.booking.clubId) ||
      String(selectedTable) !== String(hold.booking.tableId) ||
      String(selectedEventId) !== String(hold.booking.eventId);
    if (!mismatchedContext) return;
    cancelPendingAndInvalidate(true);
    setHold(null);
    setAgreeRules(false);
    setError(null);
    setLastAction(null);
    holdKey.clear();
    confirmKey.clear();
    plusOneKey.clear();
    setStep(selectedTable ? 'guests' : 'table');
  }, [
    cancelPendingAndInvalidate,
    confirmKey,
    hold,
    holdKey,
    plusOneKey,
    selectedClub,
    selectedEventId,
    selectedTable,
  ]);

  useEffect(() => {
    return () => {
      cancelPendingAndInvalidate();
      controller.current = null;
    };
  }, [cancelPendingAndInvalidate]);

  useEffect(() => {
    setStep(selectedTable ? 'guests' : 'table');
    setHold(null);
    setAgreeRules(false);
    setError(null);
    setLastAction(null);
    confirmKey.clear();
    plusOneKey.clear();
  }, [confirmKey, plusOneKey, selectedTable]);

  const holdFingerprint = useMemo(
    () =>
      JSON.stringify({
        selectedClub,
        selectedTable,
        selectedEventId,
        guests,
        selectedNight,
      }),
    [guests, selectedClub, selectedEventId, selectedNight, selectedTable],
  );

  const confirmFingerprint = useMemo(() => {
    if (!hold) return '';
    return JSON.stringify({ selectedClub, bookingId: hold.booking.id });
  }, [hold, selectedClub]);

  if (!selectedClub || !selectedNight || !selectedTable) return null;

  const performHold = async () => {
    if (!name.trim() || guests < 1 || !agreeRules) {
      setError('Заполните данные гостей и подтвердите правила');
      setLastAction(null);
      return;
    }
    if (!selectedEventId) {
      setError('Выберите событие');
      setLastAction(null);
      return;
    }
    const eventId = selectedEventId;
    cancelPendingAndInvalidate();
    const requestId = requestIdRef.current;
    setLoading(true);
    setError(null);
    setLastAction('hold');
    const key = holdKey.next(holdFingerprint);
    controller.current = new AbortController();
    try {
      const payload: BookingPayload = {
        tableId: selectedTable,
        eventId,
        guestCount: guests,
      };
      const res = await http.post<HoldResponse>(`/api/clubs/${selectedClub}/bookings/hold`, payload, {
        headers: { 'Idempotency-Key': key },
        signal: controller.current.signal,
      });
      if (requestId !== requestIdRef.current) return;
      confirmKey.clear();
      plusOneKey.clear();
      setHold(res.data);
      setStep('confirm');
      setLastAction(null);
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestId !== requestIdRef.current) return;
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
        setLastAction('hold');
      } else {
        handleServerError(code);
      }
    } finally {
      if (requestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  };

  const performConfirm = async () => {
    if (!hold) return;
    cancelPendingAndInvalidate();
    const requestId = requestIdRef.current;
    setLoading(true);
    setError(null);
    setLastAction('confirm');
    const key = confirmKey.next(confirmFingerprint);
    controller.current = new AbortController();
    try {
      const res = await http.post<HoldResponse>(`/api/clubs/${selectedClub}/bookings/confirm`, { bookingId: hold.booking.id }, {
        headers: { 'Idempotency-Key': key },
        signal: controller.current.signal,
      });
      if (requestId !== requestIdRef.current) return;
      confirmKey.clear();
      if (res.data.booking.status === 'BOOKED') {
        holdKey.clear();
      }
      setHold(res.data);
      setLastAction(null);
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestId !== requestIdRef.current) return;
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
        setLastAction('confirm');
      } else {
        handleServerError(code);
      }
    } finally {
      if (requestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  };

  const performPlusOne = async () => {
    if (!hold) return;
    cancelPendingAndInvalidate();
    const requestId = requestIdRef.current;
    setLoading(true);
    setError(null);
    setLastAction('plusOne');
    const key = plusOneKey.next(confirmFingerprint);
    controller.current = new AbortController();
    try {
      const res = await http.post<HoldResponse>(`/api/bookings/${hold.booking.id}/plus-one`, undefined, {
        headers: { 'Idempotency-Key': key },
        signal: controller.current.signal,
      });
      if (requestId !== requestIdRef.current) return;
      plusOneKey.clear();
      setHold(res.data);
      setLastAction(null);
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestId !== requestIdRef.current) return;
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
        setLastAction('plusOne');
      } else {
        handleServerError(code);
      }
    } finally {
      if (requestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  };

  const retryLastAction = () => {
    if (loading) return;
    switch (lastAction) {
      case 'hold':
        void performHold();
        return;
      case 'confirm':
        void performConfirm();
        return;
      case 'plusOne':
        void performPlusOne();
        return;
      default:
        return;
    }
  };

  const editBooking = () => {
    cancelPendingAndInvalidate(true);
    setHold(null);
    setAgreeRules(false);
    setError(null);
    setLastAction(null);
    holdKey.clear();
    confirmKey.clear();
    plusOneKey.clear();
    setStep('guests');
  };

  return (
    <div className="space-y-4 border rounded-lg p-4">
      <h3 className="text-lg font-semibold">Бронирование</h3>
      <StepPill step="Выбор стола" active={step === 'table'} />
      <div className="text-sm text-gray-700">Стол #{selectedTable} · Ночь {selectedNight}</div>
      <StepPill step="Данные гостей" active={step === 'guests'} />
      <div className="space-y-2">
        <label className="block text-sm">Имя</label>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="border rounded px-2 py-1 w-full"
          placeholder="Имя гостя"
          disabled={loading || hasHold}
        />
        <label className="block text-sm">Количество гостей</label>
        <input
          type="number"
          min={1}
          max={selectedTableCapacity}
          value={guests}
          onChange={(e) => {
            const next = Math.max(1, Number(e.target.value));
            const capped = selectedTableCapacity ? Math.min(selectedTableCapacity, next) : next;
            setGuests(capped);
          }}
          className="border rounded px-2 py-1 w-full"
          disabled={loading || hasHold}
        />
        <button
          className="bg-blue-600 text-white px-3 py-2 rounded"
          disabled={loading || hasHold}
          type="button"
          onClick={() => setStep('rules')}
        >
          Далее
        </button>
      </div>

      {step !== 'table' && !hasHold && (
        <>
          <StepPill step="Правила" active={step === 'rules'} />
          <label className="inline-flex items-center space-x-2 text-sm">
            <input type="checkbox" checked={agreeRules} onChange={(e) => setAgreeRules(e.target.checked)} />
            <span>Подтверждаю правила клуба</span>
          </label>
          <div className="space-x-2">
            <button
              className="bg-green-600 text-white px-3 py-2 rounded"
              disabled={loading}
              type="button"
              onClick={performHold}
            >
              Забронировать
            </button>
          </div>
        </>
      )}

      {hold && (
        <div className="space-y-2 border-t pt-3">
          <StepPill step="Подтверждение" active />
          <div className="text-sm">Окно прибытия: {formatInterval(hold.arrivalWindow)}</div>
          {latePlusOneDeadline && (
            <div className="text-sm">+1 доступен до: {format(new Date(latePlusOneDeadline), 'HH:mm', { locale: ru })} ({deadlineText})</div>
          )}
          <div className="space-x-2">
            <button
              className="bg-purple-600 text-white px-3 py-2 rounded"
              disabled={loading || hold.booking.status === 'BOOKED'}
              type="button"
              onClick={performConfirm}
            >
              Подтвердить
            </button>
            {!hold.booking.plusOneUsed &&
              latePlusOneDeadline &&
              hold.booking.status === 'BOOKED' &&
              !atCapacity && (
              <button
                className="bg-amber-500 text-white px-3 py-2 rounded"
                disabled={loading}
                type="button"
                onClick={performPlusOne}
              >
                Добавить +1
              </button>
            )}
            {atCapacity && <span className="text-sm text-gray-600">Достигнута вместимость стола</span>}
          </div>
          {hold.booking.status !== 'BOOKED' && (
            <div>
              <button className="bg-gray-200 text-gray-900 px-3 py-2 rounded" disabled={loading} type="button" onClick={editBooking}>
                Изменить данные
              </button>
            </div>
          )}
        </div>
      )}

      {error && (
        <div className="text-red-600 text-sm space-x-2 flex items-center">
          <span>{error}</span>
          {lastAction && (
            <button
              className="px-2 py-1 bg-red-100 text-red-700 rounded"
              disabled={loading}
              type="button"
              onClick={retryLastAction}
            >
              Повторить
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function StepPill({ step, active }: { step: string; active?: boolean }) {
  return (
    <div className={`inline-flex items-center px-2 py-1 text-xs rounded-full ${active ? 'bg-blue-100 text-blue-800' : 'bg-gray-100 text-gray-600'}`}>
      {step}
    </div>
  );
}

function messageForCode(code: string): string {
  switch (code) {
    case 'table_not_available':
      return 'Стол недоступен';
    case 'capacity_exceeded':
      return 'Превышена вместимость стола';
    case 'hold_expired':
      return 'Время удержания истекло';
    case 'late_plus_one_expired':
      return 'Время для +1 истекло';
    case 'plus_one_already_used':
      return '+1 уже использован';
    case 'forbidden':
      return 'Действие недоступно для этой брони';
    case 'rate_limited':
      return 'Слишком часто, попробуйте через пару секунд';
    case 'validation_error':
      return 'Проверьте введенные данные';
    default:
      return 'Ошибка. Попробуйте позже';
  }
}

function useBookingErrorHandler(
  opts: {
    selectedNight?: string;
    selectedEventId?: number;
    setTable: (id?: number) => void;
    setNight: (night: string, eventId?: number) => void;
    resetFlow: () => void;
    setError: (msg: string) => void;
    clearAction: () => void;
  },
) {
  const { selectedNight, selectedEventId, setTable, setNight, resetFlow, setError, clearAction } = opts;

  return (code: string) => {
    const message = messageForCode(code);
    if (code === 'table_not_available') {
      setTable(undefined);
      if (selectedNight && selectedEventId) {
        setNight(selectedNight, selectedEventId);
      }
      resetFlow();
    }
    if (code === 'forbidden') {
      setTable(undefined);
      if (selectedNight && selectedEventId) {
        setNight(selectedNight, selectedEventId);
      }
      resetFlow();
    }
    setError(message);
    clearAction();
  };
}
