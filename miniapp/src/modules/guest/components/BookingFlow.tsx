import { useEffect, useMemo, useRef, useState } from 'react';
import { http } from '../../../shared/api/http';
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

interface ApiErrorResponse {
  error?: {
    code?: string;
  };
}

type Step = 'table' | 'guests' | 'rules' | 'confirm';

function useIdempotency(key: string) {
  return {
    next: () => {
      const existing = sessionStorage.getItem(key);
      if (existing) return existing;
      const fresh = crypto.randomUUID();
      sessionStorage.setItem(key, fresh);
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
  } catch (e) {
    return '';
  }
}

function getErrorInfo(error: unknown): { code: string; hasResponse: boolean } {
  if (!error || typeof error !== 'object') {
    return { code: 'error', hasResponse: false };
  }
  if (!('response' in error)) {
    return { code: 'error', hasResponse: false };
  }
  const response = (error as { response?: { data?: ApiErrorResponse } }).response;
  return {
    code: response?.data?.error?.code ?? 'error',
    hasResponse: Boolean(response),
  };
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
  const [lastAction, setLastAction] = useState<(() => Promise<void>) | null>(null);
  const controller = useRef<AbortController | null>(null);
  const holdKey = useIdempotency('booking-hold-key');
  const confirmKey = useIdempotency('booking-confirm-key');
  const plusOneKey = useIdempotency('booking-plus1-key');

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

  useEffect(() => {
    setStep(selectedTable ? 'guests' : 'table');
    setHold(null);
    setAgreeRules(false);
    setError(null);
    setLastAction(null);
  }, [selectedTable]);

  if (!selectedClub || !selectedNight || !selectedTable) return null;

  const cancelPending = () => {
    controller.current?.abort();
  };

  const performHold = async () => {
    if (!name.trim() || guests < 1 || !agreeRules) {
      setError('Заполните данные гостей и подтвердите правила');
      return;
    }
    if (!selectedEventId) {
      setError('Выберите событие');
      return;
    }
    const eventId = selectedEventId;
    cancelPending();
    setLoading(true);
    setError(null);
    setLastAction(() => performHold);
    const key = holdKey.next();
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
      holdKey.clear();
      setHold(res.data);
      setStep('confirm');
      setLastAction(null);
    } catch (error) {
      const { code, hasResponse } = getErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
        setLastAction(() => performHold);
      } else {
        handleServerError(code);
      }
    } finally {
      setLoading(false);
    }
  };

  const performConfirm = async () => {
    if (!hold) return;
    cancelPending();
    setLoading(true);
    setError(null);
    setLastAction(() => performConfirm);
    const key = confirmKey.next();
    controller.current = new AbortController();
    try {
      const res = await http.post<HoldResponse>(`/api/clubs/${selectedClub}/bookings/confirm`, { bookingId: hold.booking.id }, {
        headers: { 'Idempotency-Key': key },
        signal: controller.current.signal,
      });
      confirmKey.clear();
      setHold(res.data);
      setLastAction(null);
    } catch (error) {
      const { code, hasResponse } = getErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
        setLastAction(() => performConfirm);
      } else {
        handleServerError(code);
      }
    } finally {
      setLoading(false);
    }
  };

  const performPlusOne = async () => {
    if (!hold) return;
    cancelPending();
    setLoading(true);
    setError(null);
    setLastAction(() => performPlusOne);
    const key = plusOneKey.next();
    controller.current = new AbortController();
    try {
      const res = await http.post<HoldResponse>(`/api/bookings/${hold.booking.id}/plus-one`, undefined, {
        headers: { 'Idempotency-Key': key },
        signal: controller.current.signal,
      });
      plusOneKey.clear();
      setHold(res.data);
      setLastAction(null);
    } catch (error) {
      const { code, hasResponse } = getErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
        setLastAction(() => performPlusOne);
      } else {
        handleServerError(code);
      }
    } finally {
      setLoading(false);
    }
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
        />
        <button
          className="bg-blue-600 text-white px-3 py-2 rounded"
          disabled={loading}
          onClick={() => setStep('rules')}
        >
          Далее
        </button>
      </div>

      {step !== 'table' && (
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
              onClick={performConfirm}
            >
              Подтвердить
            </button>
            {!hold.booking.plusOneUsed &&
              latePlusOneDeadline &&
              hold.booking.status === 'BOOKED' &&
              !atCapacity && (
              <button className="bg-amber-500 text-white px-3 py-2 rounded" disabled={loading} onClick={performPlusOne}>
                Добавить +1
              </button>
            )}
            {atCapacity && <span className="text-sm text-gray-600">Достигнута вместимость стола</span>}
          </div>
        </div>
      )}

      {error && (
        <div className="text-red-600 text-sm space-x-2 flex items-center">
          <span>{error}</span>
          {lastAction && (
            <button
              className="px-2 py-1 bg-red-100 text-red-700 rounded"
              disabled={loading}
              onClick={() => lastAction()}
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
