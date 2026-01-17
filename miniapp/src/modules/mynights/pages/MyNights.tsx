import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { downloadBookingIcs, fetchBookingQr, fetchMyBookings, MyBookingDto } from '../api/mynights.api';
import { format } from 'date-fns';
import { ru } from 'date-fns/locale';
import { getApiErrorInfo, isRequestCanceled } from '../../../shared/api/error';
import QrCodeBlock from '../../../shared/ui/QrCodeBlock';

interface CountdownProps {
  arriveBy: string;
  now: number;
}

function Countdown({ arriveBy, now }: CountdownProps) {
  const target = useMemo(() => new Date(arriveBy).getTime(), [arriveBy]);
  const diff = Math.max(0, target - now);
  const minutes = Math.floor(diff / 60000);
  const seconds = Math.floor((diff % 60000) / 1000)
    .toString()
    .padStart(2, '0');
  return <span className="text-sm text-gray-500">До прибытия: {minutes}м {seconds}с</span>;
}

export default function MyNights() {
  const [status, setStatus] = useState<'upcoming' | 'past'>('upcoming');
  const [bookings, setBookings] = useState<MyBookingDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [qrPayloads, setQrPayloads] = useState<Record<number, string>>({});
  const [qrVisible, setQrVisible] = useState<Record<number, boolean>>({});
  const [now, setNow] = useState(Date.now());
  const isMounted = useRef(true);
  const controllerRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const handleStatusChange = (nextStatus: 'upcoming' | 'past') => {
    if (nextStatus === status) return;
    setBookings([]);
    setError('');
    setQrPayloads({});
    setQrVisible({});
    setStatus(nextStatus);
  };

  useEffect(() => {
    return () => {
      isMounted.current = false;
    };
  }, []);

  const load = useCallback(async () => {
    requestIdRef.current += 1;
    const requestId = requestIdRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setLoading(true);
    setError('');
    setQrPayloads({});
    setQrVisible({});
    try {
      const res = await fetchMyBookings(status, { signal: controller.signal });
      if (!isMounted.current || requestId !== requestIdRef.current) return;
      setBookings(res.data.bookings);
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (!isMounted.current || requestId !== requestIdRef.current) return;
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!hasResponse) {
        setError('Не удалось связаться с сервером');
        return;
      }
      setError(code === 'validation_error' ? 'Неверный фильтр статуса' : 'Не удалось загрузить бронирования');
    } finally {
      if (isMounted.current && requestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  }, [status]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    return () => {
      controllerRef.current?.abort();
    };
  }, []);

  const toggleQr = async (bookingId: number) => {
    const isShown = qrVisible[bookingId];
    if (isShown) {
      setQrVisible((prev) => ({ ...prev, [bookingId]: false }));
      return;
    }
    if (qrPayloads[bookingId]) {
      setQrVisible((prev) => ({ ...prev, [bookingId]: true }));
      return;
    }
    try {
      const res = await fetchBookingQr(bookingId);
      if (!isMounted.current) return;
      setQrPayloads((prev) => ({ ...prev, [bookingId]: res.data.qrPayload }));
      setQrVisible((prev) => ({ ...prev, [bookingId]: true }));
    } catch (error) {
      if (isRequestCanceled(error)) return;
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!isMounted.current) return;
      if (!hasResponse) {
        setError('Не удалось связаться с сервером');
        return;
      }
      setError(code === 'forbidden' ? 'Бронь недоступна' : 'Не удалось получить QR');
    }
  };

  const saveIcs = async (bookingId: number) => {
    try {
      const res = await downloadBookingIcs(bookingId);
      const url = URL.createObjectURL(res.data);
      const a = document.createElement('a');
      a.href = url;
      a.download = `booking-${bookingId}.ics`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      if (isRequestCanceled(error)) return;
      const { code, hasResponse } = getApiErrorInfo(error);
      if (!isMounted.current) return;
      if (!hasResponse) {
        setError('Не удалось связаться с сервером');
        return;
      }
      setError(code === 'forbidden' ? 'Бронь недоступна' : 'Не удалось выгрузить календарь');
    }
  };

  const renderBooking = (item: MyBookingDto) => {
    const arriveText = format(new Date(item.arrivalWindow[0]), 'dd MMM HH:mm', { locale: ru });
    const qrShown = qrVisible[item.booking.id];
    const qrPayload = qrPayloads[item.booking.id];
    return (
      <div key={item.booking.id} className="border rounded p-3 space-y-2 bg-white">
        <div className="flex items-center justify-between">
          <div>
            <div className="font-semibold">Клуб #{item.booking.clubId}</div>
            <div className="text-sm text-gray-600">Ивент #{item.booking.eventId}</div>
          </div>
          {item.canPlusOne && <span className="px-2 py-1 text-xs bg-green-100 text-green-700 rounded">+1 доступен</span>}
        </div>
        <div className="text-sm">Окно прибытия: {arriveText}</div>
        {!item.isPast && <Countdown arriveBy={item.arriveBy} now={now} />}
        <div className="flex gap-2">
          <button
            className="px-3 py-2 text-sm rounded bg-blue-600 text-white"
            type="button"
            onClick={() => void toggleQr(item.booking.id)}
          >
            {qrShown ? 'Скрыть QR' : 'Показать QR'}
          </button>
          <button
            className="px-3 py-2 text-sm rounded bg-gray-200"
            type="button"
            onClick={() => void saveIcs(item.booking.id)}
          >
            Добавить в календарь
          </button>
        </div>
        {qrShown && qrPayload && <QrCodeBlock payload={qrPayload} size={220} />}
      </div>
    );
  };

  return (
    <div className="p-4 space-y-3 bg-gray-50 min-h-screen">
      <div className="flex gap-2">
        <button
          className={`px-3 py-2 rounded ${status === 'upcoming' ? 'bg-blue-600 text-white' : 'bg-white border'}`}
          type="button"
          onClick={() => handleStatusChange('upcoming')}
        >
          Предстоящие
        </button>
        <button
          className={`px-3 py-2 rounded ${status === 'past' ? 'bg-blue-600 text-white' : 'bg-white border'}`}
          type="button"
          onClick={() => handleStatusChange('past')}
        >
          Прошедшие
        </button>
        <button
          className="ml-auto text-sm underline disabled:text-gray-400"
          type="button"
          onClick={() => void load()}
          disabled={loading}
        >
          Обновить
        </button>
      </div>
      {error && <div className="text-red-600 text-sm">{error}</div>}
      {loading && <div>Загрузка...</div>}
      {!loading && !error && bookings.length === 0 && (
        <div className="text-sm text-gray-500">Бронирования не найдены</div>
      )}
      <div className="space-y-3">{bookings.map(renderBooking)}</div>
    </div>
  );
}
