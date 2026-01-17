import { useGuestStore } from '../../guest/state/guest.store';
import { useEffect, useRef, useState } from 'react';
import { http } from '../../../shared/api/http';
import { NightDto } from '../../../shared/types';
import { isRequestCanceled } from '../../../shared/api/error';
import { useUiStore } from '../../../shared/store/ui';

/** Picker component for night selection. */
export default function NightPicker() {
  const { selectedClub, selectedNight, setNight } = useGuestStore();
  const [nights, setNights] = useState<NightDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const { addToast } = useUiStore();
  const abortRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  useEffect(() => {
    if (!selectedClub) {
      abortRef.current?.abort();
      abortRef.current = null;
      setNights([]);
      setLoading(false);
      setError('');
      return;
    }

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const requestId = ++requestIdRef.current;

    setNights([]);
    setLoading(true);
    setError('');

    http
      .get<NightDto[]>(`/api/clubs/${selectedClub}/nights?limit=8`, { signal: controller.signal })
      .then((r) => {
        if (requestIdRef.current !== requestId) return;
        setNights(r.data);
      })
      .catch((error) => {
        if (isRequestCanceled(error)) return;
        if (requestIdRef.current !== requestId) return;
        setError('Не удалось загрузить ночи');
        addToast('Не удалось загрузить ночи');
      })
      .finally(() => {
        if (requestIdRef.current === requestId) {
          setLoading(false);
        }
      });

    return () => {
      controller.abort();
      if (abortRef.current === controller) {
        abortRef.current = null;
      }
    };
  }, [addToast, selectedClub]);

  if (!selectedClub) return null;

  const onChange = (value: string) => {
    const match = nights.find((n) => n.startUtc === value);
    setNight(value, match?.eventId);
  };

  return (
    <div className="space-y-1">
      <select
        value={selectedNight ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className="p-2"
        disabled={loading}
      >
        <option value="" disabled>
          Выберите ночь
        </option>
        {nights.map((n) => (
          <option key={n.startUtc} value={n.startUtc}>
            {n.name}
          </option>
        ))}
      </select>
      {loading ? <div className="text-sm text-gray-500">Загрузка...</div> : null}
      {error ? <div className="text-sm text-red-600">{error}</div> : null}
    </div>
  );
}
