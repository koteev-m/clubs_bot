import { useCallback, useEffect, useState } from 'react';
import { fetchChecklist, updateChecklistItem, ChecklistItem } from '../api/entry.api';
import { useUiStore } from '../../../shared/store/ui';

interface EntryChecklistScreenProps {
  clubId: number;
  eventId: number;
}

export default function EntryChecklistScreen({ clubId, eventId }: EntryChecklistScreenProps) {
  const { addToast } = useUiStore();
  const [items, setItems] = useState<ChecklistItem[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    if (!clubId || !eventId) {
      addToast('Укажите клуб и событие');
      return;
    }
    setLoading(true);
    try {
      const res = await fetchChecklist(clubId, eventId);
      setItems(res.data.items);
    } catch {
      addToast('Не удалось загрузить чек-лист');
    } finally {
      setLoading(false);
    }
  }, [addToast, clubId, eventId]);

  useEffect(() => {
    if (clubId && eventId) {
      void load();
    }
  }, [clubId, eventId, load]);

  const toggleItem = async (item: ChecklistItem) => {
    try {
      const res = await updateChecklistItem(clubId, eventId, item.id, !item.done);
      setItems(res.data.items);
    } catch {
      addToast('Не удалось сохранить чек-лист');
    }
  };

  return (
    <div className="space-y-3">
      <button type="button" className="p-2 border rounded w-full" onClick={load} disabled={loading}>
        {loading ? 'Загрузка...' : 'Обновить'}
      </button>
      <div className="space-y-2">
        {items.map((item) => (
          <label key={item.id} className="flex items-center gap-2 border rounded p-2">
            <input type="checkbox" checked={item.done} onChange={() => void toggleItem(item)} />
            <span className="text-sm">{item.text}</span>
          </label>
        ))}
        {!items.length && <div className="text-sm text-gray-500">Чек-лист пуст.</div>}
      </div>
    </div>
  );
}
