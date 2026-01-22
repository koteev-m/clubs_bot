import { useState } from 'react';
import { hostCheckin, searchHostGuests, HostSearchItem } from '../api/entry.api';
import { useUiStore } from '../../../shared/store/ui';

const denyOptions = [
  { value: 'NO_ID', label: 'Нет документа' },
  { value: 'DRESS_CODE', label: 'Дресс-код' },
  { value: 'CAPACITY', label: 'Нет мест' },
  { value: 'OTHER', label: 'Другое' },
];

interface EntryCheckinScreenProps {
  clubId: number;
  eventId: number;
}

export default function EntryCheckinScreen({ clubId, eventId }: EntryCheckinScreenProps) {
  const { addToast } = useUiStore();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<HostSearchItem[]>([]);
  const [denyReasons, setDenyReasons] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);

  const handleSearch = async () => {
    if (!clubId || !eventId) {
      addToast('Укажите клуб и событие');
      return;
    }
    if (query.trim().length < 2) {
      addToast('Введите минимум 2 символа');
      return;
    }
    setLoading(true);
    try {
      const res = await searchHostGuests(clubId, eventId, query.trim());
      setResults(res.data);
    } catch {
      addToast('Не удалось выполнить поиск');
    } finally {
      setLoading(false);
    }
  };

  const updateResult = (item: HostSearchItem, payload: { bookingStatus?: string; entryStatus?: string; outcome: string }) => {
    setResults((prev) =>
      prev.map((existing) => {
        const key = getItemKey(existing);
        if (key !== getItemKey(item)) return existing;
        return {
          ...existing,
          status: payload.entryStatus || payload.bookingStatus || payload.outcome,
          arrived: payload.outcome !== 'DENIED',
        };
      }),
    );
  };

  const handleArrive = async (item: HostSearchItem) => {
    if (!clubId || !eventId) {
      addToast('Укажите клуб и событие');
      return;
    }
    try {
      const res = await hostCheckin({
        clubId,
        eventId,
        bookingId: item.bookingId,
        guestListEntryId: item.guestListEntryId,
        action: 'ARRIVE',
      });
      addToast(res.data.outcomeStatus);
      updateResult(item, {
        bookingStatus: res.data.bookingStatus,
        entryStatus: res.data.entryStatus,
        outcome: res.data.outcomeStatus,
      });
    } catch {
      addToast('Не удалось отметить вход');
    }
  };

  const handleDeny = async (item: HostSearchItem) => {
    if (!clubId || !eventId) {
      addToast('Укажите клуб и событие');
      return;
    }
    const key = getItemKey(item);
    const reason = denyReasons[key];
    if (!reason) {
      addToast('Выберите причину отказа');
      return;
    }
    try {
      const res = await hostCheckin({
        clubId,
        eventId,
        bookingId: item.bookingId,
        guestListEntryId: item.guestListEntryId,
        action: 'DENY',
        denyReason: reason,
      });
      addToast(res.data.outcomeStatus === 'DENIED' ? `DENIED: ${res.data.denyReason || reason}` : res.data.outcomeStatus);
      updateResult(item, {
        bookingStatus: res.data.bookingStatus,
        entryStatus: res.data.entryStatus,
        outcome: res.data.outcomeStatus,
      });
    } catch {
      addToast('Не удалось выполнить отказ');
    }
  };

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Поиск по ФИО"
          className="border p-2 w-full rounded"
        />
        <button onClick={handleSearch} className="p-2 border rounded w-full" disabled={loading}>
          {loading ? 'Поиск...' : 'Найти'}
        </button>
      </div>

      <div className="space-y-3">
        {results.map((item) => {
          const key = getItemKey(item);
          return (
            <div key={key} className="border rounded p-3 space-y-2">
              <div className="flex items-center justify-between">
                <div>
                  <div className="font-medium">{item.displayName || 'Без имени'}</div>
                  <div className="text-xs text-gray-500">
                    {item.kind} · статус: {item.status}
                  </div>
                </div>
                <div className="text-xs text-gray-500">Гостей: {item.guestCount}</div>
              </div>

              <div className="flex flex-wrap gap-2 items-center">
                <button
                  type="button"
                  className="px-3 py-1 border rounded"
                  onClick={() => handleArrive(item)}
                  disabled={item.arrived}
                >
                  {item.arrived ? 'Уже пришёл' : 'Arrived'}
                </button>
                <select
                  className="border p-1 rounded"
                  value={denyReasons[key] || ''}
                  onChange={(e) =>
                    setDenyReasons((prev) => ({
                      ...prev,
                      [key]: e.target.value,
                    }))
                  }
                >
                  <option value="">Причина отказа</option>
                  {denyOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <button type="button" className="px-3 py-1 border rounded" onClick={() => handleDeny(item)}>
                  Deny
                </button>
              </div>
            </div>
          );
        })}
        {!results.length && <div className="text-sm text-gray-500">Результаты появятся здесь.</div>}
      </div>
    </div>
  );
}

function getItemKey(item: HostSearchItem) {
  return item.kind === 'BOOKING' ? `booking-${item.bookingId}` : `entry-${item.guestListEntryId}`;
}
