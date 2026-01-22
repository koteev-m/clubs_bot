import { useEffect, useState } from 'react';
import { fetchWaitlist, inviteWaitlistEntry, WaitlistEntry } from '../api/entry.api';
import { useUiStore } from '../../../shared/store/ui';

interface EntryWaitlistScreenProps {
  clubId: number;
  eventId: number;
}

export default function EntryWaitlistScreen({ clubId, eventId }: EntryWaitlistScreenProps) {
  const { addToast } = useUiStore();
  const [items, setItems] = useState<WaitlistEntry[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    if (!clubId || !eventId) {
      addToast('Укажите клуб и событие');
      return;
    }
    setLoading(true);
    try {
      const res = await fetchWaitlist(clubId, eventId);
      setItems(res.data);
    } catch {
      addToast('Не удалось загрузить лист ожидания');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (clubId && eventId) {
      void load();
    }
  }, [clubId, eventId]);

  const handleInvite = async (entry: WaitlistEntry) => {
    try {
      const res = await inviteWaitlistEntry(entry.id, clubId, eventId);
      const enabled = (res.data as { enabled?: boolean; reason?: string }).enabled;
      if (!enabled) {
        addToast('Инвайт недоступен для листа ожидания');
        return;
      }
      addToast('Инвайт создан');
    } catch {
      addToast('Не удалось создать инвайт');
    }
  };

  return (
    <div className="space-y-3">
      <button type="button" className="p-2 border rounded w-full" onClick={load} disabled={loading}>
        {loading ? 'Загрузка...' : 'Обновить'}
      </button>
      <div className="space-y-2">
        {items.map((item) => (
          <div key={item.id} className="border rounded p-3 space-y-1">
            <div className="flex items-center justify-between">
              <div className="font-medium">#{item.id}</div>
              <div className="text-xs text-gray-500">{item.status}</div>
            </div>
            <div className="text-xs text-gray-500">Гостей: {item.partySize}</div>
            <button type="button" className="mt-2 px-3 py-1 border rounded" onClick={() => handleInvite(item)}>
              Пригласить
            </button>
          </div>
        ))}
        {!items.length && <div className="text-sm text-gray-500">Очередь пуста.</div>}
      </div>
    </div>
  );
}
