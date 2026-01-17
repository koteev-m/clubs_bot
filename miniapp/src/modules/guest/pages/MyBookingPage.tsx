import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { format } from 'date-fns';
import { ru } from 'date-fns/locale';
import {
  downloadBookingIcs,
  fetchBookingQr,
  fetchMyBookings,
  MyBookingDto,
  requestBookingPlusOne,
} from '../../mynights/api/mynights.api';
import { getApiErrorInfo, isRequestCanceled } from '../../../shared/api/error';
import { useUiStore } from '../../../shared/store/ui';
import QrCodeBlock from '../../../shared/ui/QrCodeBlock';

const selectActiveBooking = (bookings: MyBookingDto[]) => {
  if (bookings.length === 0) return null;
  const sorted = [...bookings].sort((a, b) => {
    const aTime = Date.parse(a.arrivalWindow?.[0] ?? '');
    const bTime = Date.parse(b.arrivalWindow?.[0] ?? '');
    if (Number.isNaN(aTime) && Number.isNaN(bTime)) return 0;
    if (Number.isNaN(aTime)) return 1;
    if (Number.isNaN(bTime)) return -1;
    return aTime - bTime;
  });
  return sorted[0] ?? null;
};

const formatArrivalWindow = (window?: string[]) => {
  if (!window || window.length < 2) return '';
  const [from, to] = window;
  try {
    const fromDate = new Date(from);
    const toDate = new Date(to);
    return `${format(fromDate, 'dd MMM', { locale: ru })} · ${format(fromDate, 'HH:mm', { locale: ru })}–${format(
      toDate,
      'HH:mm',
      { locale: ru },
    )}`;
  } catch {
    return '';
  }
};

const formatPlusOneDeadline = (value?: string | null) => {
  if (!value) return '';
  try {
    return format(new Date(value), 'dd MMM HH:mm', { locale: ru });
  } catch {
    return '';
  }
};

export default function MyBookingPage() {
  const { addToast } = useUiStore();
  const [booking, setBooking] = useState<MyBookingDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [qrPayload, setQrPayload] = useState('');
  const [isQrVisible, setIsQrVisible] = useState(false);
  const [qrLoading, setQrLoading] = useState(false);
  const [icsLoading, setIcsLoading] = useState(false);
  const [plusOneLoading, setPlusOneLoading] = useState(false);
  const requestIdRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const qrRequestIdRef = useRef(0);
  const plusOneRequestIdRef = useRef(0);
  const activeBookingIdRef = useRef<number | null>(null);

  const busy = loading || qrLoading || icsLoading || plusOneLoading;
  const arrivalWindowText = useMemo(
    () => formatArrivalWindow(booking?.arrivalWindow ?? booking?.booking.arrivalWindow),
    [booking],
  );
  const plusOneDeadlineText = useMemo(
    () =>
      formatPlusOneDeadline(
        booking?.latePlusOneAllowedUntil ?? booking?.booking.latePlusOneAllowedUntil,
      ),
    [booking],
  );

  const loadBooking = useCallback(async () => {
    const requestId = ++requestIdRef.current;
    controllerRef.current?.abort();
    controllerRef.current = new AbortController();
    setLoading(true);
    setError('');
    try {
      const res = await fetchMyBookings('upcoming', { signal: controllerRef.current.signal });
      if (requestId !== requestIdRef.current) return;
      setBooking(selectActiveBooking(res.data.bookings));
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestId !== requestIdRef.current) return;
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
      } else if (code === 'validation_error') {
        setError('Неверный фильтр статуса');
      } else {
        setError('Не удалось загрузить бронь');
      }
    } finally {
      if (requestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadBooking();
    return () => {
      controllerRef.current?.abort();
    };
  }, [loadBooking]);

  useEffect(() => {
    setQrPayload('');
    setIsQrVisible(false);
    activeBookingIdRef.current = booking?.booking.id ?? null;
  }, [booking?.booking.id]);

  const openQr = async () => {
    if (!booking || busy) return false;
    const requestId = ++qrRequestIdRef.current;
    const bookingId = booking.booking.id;
    setQrLoading(true);
    setError('');
    try {
      const res = await fetchBookingQr(bookingId);
      if (requestId !== qrRequestIdRef.current) return false;
      if (bookingId !== activeBookingIdRef.current) return false;
      setQrPayload(res.data.qrPayload);
      return true;
    } catch (error) {
      if (requestId !== qrRequestIdRef.current) return false;
      const { code } = getApiErrorInfo(error);
      setError(code === 'forbidden' ? 'Бронь недоступна' : 'Не удалось получить QR');
      return false;
    } finally {
      if (requestId === qrRequestIdRef.current) {
        setQrLoading(false);
      }
    }
  };

  const toggleQr = async () => {
    if (!booking || busy) return;
    if (isQrVisible) {
      setIsQrVisible(false);
      return;
    }
    if (qrPayload) {
      setIsQrVisible(true);
      return;
    }
    const loaded = await openQr();
    if (loaded) {
      setIsQrVisible(true);
    }
  };

  const saveIcs = async () => {
    if (!booking || busy) return;
    const bookingId = booking.booking.id;
    setIcsLoading(true);
    setError('');
    try {
      const res = await downloadBookingIcs(bookingId);
      const blob = res.data;
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `booking-${bookingId}.ics`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
      } else {
        setError(code === 'forbidden' ? 'Бронь недоступна' : 'Не удалось выгрузить календарь');
      }
    } finally {
      setIcsLoading(false);
    }
  };

  const addPlusOne = async () => {
    if (!booking || busy || !booking.canPlusOne) return;
    const requestId = ++plusOneRequestIdRef.current;
    const bookingId = booking.booking.id;
    setPlusOneLoading(true);
    setError('');
    try {
      await requestBookingPlusOne(bookingId);
      if (requestId !== plusOneRequestIdRef.current) return;
      if (bookingId !== activeBookingIdRef.current) return;
      addToast('+1 добавлен');
      void loadBooking();
    } catch (error) {
      if (requestId !== plusOneRequestIdRef.current) return;
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!hasResponse) {
        setError('Проблема с сетью. Повторите попытку');
        return;
      }
      switch (code) {
        case 'late_plus_one_expired':
          setError('Время для +1 уже прошло');
          break;
        case 'plus_one_already_used':
          setError('+1 уже добавлен');
          break;
        case 'capacity_exceeded':
          setError('Для стола достигнут лимит гостей');
          break;
        default:
          setError('Не удалось добавить +1');
          break;
      }
    } finally {
      if (requestId === plusOneRequestIdRef.current) {
        setPlusOneLoading(false);
      }
    }
  };

  const goToBooking = () => {
    const url = new URL(window.location.href);
    url.searchParams.set('tab', 'book');
    window.history.pushState({}, '', url);
    window.dispatchEvent(new PopStateEvent('popstate'));
  };

  return (
    <div className="p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Моя бронь</h2>
        <button className="text-sm underline" type="button" onClick={() => void loadBooking()} disabled={busy}>
          Обновить
        </button>
      </div>
      {loading && <div className="text-sm text-gray-500">Загрузка...</div>}
      {!loading && !booking && error && (
        <div className="rounded-lg border border-red-100 bg-red-50 p-4 text-sm text-red-700 space-y-3">
          <div>{error}</div>
          <button
            className="rounded bg-red-600 px-3 py-2 text-sm text-white disabled:opacity-60"
            type="button"
            onClick={() => void loadBooking()}
            disabled={busy}
          >
            Повторить
          </button>
        </div>
      )}
      {!loading && !booking && !error && (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-4 text-sm text-gray-600 space-y-3">
          <div>Активной брони нет.</div>
          <button className="rounded bg-blue-600 px-3 py-2 text-sm text-white" type="button" onClick={goToBooking}>
            Перейти к бронированию
          </button>
        </div>
      )}
      {booking && (
        <div className="rounded-lg border bg-white p-4 space-y-3">
          {error && <div className="text-sm text-red-600">{error}</div>}
          <div className="flex items-center justify-between">
            <div>
              <div className="font-semibold">Клуб #{booking.booking.clubId}</div>
              <div className="text-sm text-gray-600">Ивент #{booking.booking.eventId}</div>
            </div>
            <span className="text-xs rounded bg-gray-100 px-2 py-1 text-gray-700">{booking.booking.status}</span>
          </div>
          <div className="text-sm text-gray-700">Стол #{booking.booking.tableId ?? '—'}</div>
          <div className="text-sm text-gray-700">
            Окно прибытия: {arrivalWindowText || 'не указано'}
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              className="rounded bg-blue-600 px-3 py-2 text-sm text-white disabled:opacity-60"
              type="button"
              onClick={() => void toggleQr()}
              disabled={busy}
            >
              {isQrVisible ? 'Скрыть QR' : qrLoading ? 'Загрузка QR...' : 'Показать QR'}
            </button>
            <button
              className="rounded bg-gray-200 px-3 py-2 text-sm text-gray-800 disabled:opacity-60"
              type="button"
              onClick={() => void saveIcs()}
              disabled={busy}
            >
              {icsLoading ? 'Скачивание...' : 'Скачать .ics'}
            </button>
            <button
              className="rounded bg-green-600 px-3 py-2 text-sm text-white disabled:opacity-60"
              type="button"
              onClick={() => void addPlusOne()}
              disabled={!booking.canPlusOne || busy}
            >
              {plusOneLoading ? 'Добавляем +1...' : 'Добавить +1'}
            </button>
          </div>
          {booking.canPlusOne && (
            <div className="text-xs text-green-700 bg-green-50 border border-green-100 rounded px-2 py-1">
              +1 доступен до {plusOneDeadlineText || 'не указано'}
            </div>
          )}
          {isQrVisible && qrPayload && (
            <QrCodeBlock payload={qrPayload} size={220} />
          )}
        </div>
      )}
    </div>
  );
}
