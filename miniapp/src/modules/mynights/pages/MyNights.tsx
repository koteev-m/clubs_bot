import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { downloadBookingIcs, fetchBookingQr, fetchMyBookings, MyBookingDto } from '../api/mynights.api';
import { format } from 'date-fns';
import { ru } from 'date-fns/locale';
import { getApiErrorInfo, isRequestCanceled } from '../../../shared/api/error';

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
  const [now, setNow] = useState(Date.now());
  const isMounted = useRef(true);

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    return () => {
      isMounted.current = false;
    };
  }, []);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      setLoading(true);
      setError('');
      try {
        const res = await fetchMyBookings(status, { signal });
        if (!isMounted.current) return;
        setBookings(res.data.bookings);
      } catch (error) {
        if (isRequestCanceled(error)) return;
        const { code, hasResponse } = getApiErrorInfo(error);
        if (!isMounted.current) return;
        if (!hasResponse) {
          setError('Не удалось связаться с сервером');
          return;
        }
        setError(code === 'validation_error' ? 'Неверный фильтр статуса' : 'Не удалось загрузить бронирования');
      } finally {
        if (isMounted.current) {
          setLoading(false);
        }
      }
    },
    [status],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const openQr = async (bookingId: number) => {
    try {
      const res = await fetchBookingQr(bookingId);
      if (!isMounted.current) return;
      setQrPayloads((prev) => ({ ...prev, [bookingId]: res.data.qrPayload }));
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
      const blob = new Blob([res.data], { type: 'text/calendar' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `booking-${bookingId}.ics`;
      a.click();
      URL.revokeObjectURL(url);
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
    const qrShown = qrPayloads[item.booking.id];
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
            onClick={() => void openQr(item.booking.id)}
          >
            Показать QR
          </button>
          <button
            className="px-3 py-2 text-sm rounded bg-gray-200"
            type="button"
            onClick={() => void saveIcs(item.booking.id)}
          >
            Добавить в календарь
          </button>
        </div>
        {qrShown && (
          <div className="text-xs break-all bg-gray-50 p-2 rounded border">QR payload: {qrShown}</div>
        )}
      </div>
    );
  };

  return (
    <div className="p-4 space-y-3 bg-gray-50 min-h-screen">
      <div className="flex gap-2">
        <button
          className={`px-3 py-2 rounded ${status === 'upcoming' ? 'bg-blue-600 text-white' : 'bg-white border'}`}
          type="button"
          onClick={() => setStatus('upcoming')}
        >
          Предстоящие
        </button>
        <button
          className={`px-3 py-2 rounded ${status === 'past' ? 'bg-blue-600 text-white' : 'bg-white border'}`}
          type="button"
          onClick={() => setStatus('past')}
        >
          Прошедшие
        </button>
        <button className="ml-auto text-sm underline" type="button" onClick={() => void load()}>
          Обновить
        </button>
      </div>
      {error && <div className="text-red-600 text-sm">{error}</div>}
      {loading && <div>Загрузка...</div>}
      {!loading && bookings.length === 0 && <div className="text-sm text-gray-500">Бронирования не найдены</div>}
      <div className="space-y-3">{bookings.map(renderBooking)}</div>
    </div>
  );
}
