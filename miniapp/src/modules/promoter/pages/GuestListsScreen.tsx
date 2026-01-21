import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  addGuestListEntriesBulk,
  addGuestListEntry,
  createGuestList,
  listClubEvents,
  listGuestLists,
  listPromoterClubs,
  normalizePromoterError,
  PromoterClub,
  PromoterEvent,
  PromoterGuestList,
} from '../api/promoter.api';
import { useUiStore } from '../../../shared/store/ui';

type GuestListsScreenProps = {
  clubId: number | null;
  guestListId: number | null;
  onSelectClub: (id: number | null) => void;
  onSelectGuestList: (id: number | null) => void;
  onForbidden: () => void;
};

const normalizeBulkText = (value: string) => {
  const lines = value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  return { lines, normalized: lines.join('\n') };
};

const formatWindowToIso = (date: string, window: string) => {
  const parts = window.split('-').map((item) => item.trim());
  if (parts.length !== 2) return null;
  const [start, end] = parts;
  if (!start || !end) return null;
  const startDate = new Date(`${date}T${start}:00Z`);
  const endDate = new Date(`${date}T${end}:00Z`);
  if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) return null;
  if (endDate <= startDate) {
    endDate.setUTCDate(endDate.getUTCDate() + 1);
  }
  return { startIso: startDate.toISOString(), endIso: endDate.toISOString() };
};

const parseOptionalId = (value: string) => {
  const id = Number(value);
  if (!Number.isFinite(id) || id <= 0) return null;
  return id;
};

export default function GuestListsScreen({
  clubId,
  guestListId,
  onSelectClub,
  onSelectGuestList,
  onForbidden,
}: GuestListsScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const [clubs, setClubs] = useState<PromoterClub[]>([]);
  const [guestLists, setGuestLists] = useState<PromoterGuestList[]>([]);
  const [events, setEvents] = useState<PromoterEvent[]>([]);
  const [createClubId, setCreateClubId] = useState<number | null>(clubId);
  const [date, setDate] = useState('');
  const [arrivalWindow, setArrivalWindow] = useState('');
  const [limit, setLimit] = useState(20);
  const [title, setTitle] = useState('');
  const [eventId, setEventId] = useState<number | null>(null);
  const [bulkText, setBulkText] = useState('');
  const [singleName, setSingleName] = useState('');
  const [loadingLists, setLoadingLists] = useState(true);

  const loadGuestLists = useCallback(async () => {
    setLoadingLists(true);
    try {
      const lists = await listGuestLists(clubId ? { clubId } : undefined);
      setGuestLists(lists);
      if (!guestListId && lists.length > 0) {
        onSelectGuestList(lists[0].id);
      }
    } catch (error) {
      const normalized = normalizePromoterError(error);
      if (normalized.status === 401 || normalized.status === 403) {
        onForbidden();
      } else {
        addToast(normalized.message);
      }
    } finally {
      setLoadingLists(false);
    }
  }, [addToast, clubId, guestListId, onForbidden, onSelectGuestList]);

  useEffect(() => {
    const controller = new AbortController();
    const load = async () => {
      try {
        const clubsData = await listPromoterClubs(controller.signal);
        setClubs(clubsData);
      } catch (error) {
        const normalized = normalizePromoterError(error);
        if (normalized.status === 401 || normalized.status === 403) {
          onForbidden();
        } else {
          addToast(normalized.message);
        }
      }
    };
    load();
    return () => controller.abort();
  }, [addToast, onForbidden]);

  useEffect(() => {
    loadGuestLists();
  }, [loadGuestLists]);

  useEffect(() => {
    if (!createClubId || !date) {
      setEvents([]);
      setEventId(null);
      return;
    }
    const controller = new AbortController();
    const load = async () => {
      try {
        const fetched = await listClubEvents(createClubId, date, controller.signal);
        setEvents(fetched);
        if (fetched.length > 0) {
          setEventId(fetched[0].id);
        } else {
          setEventId(null);
        }
      } catch (error) {
        const normalized = normalizePromoterError(error);
        if (normalized.status === 401 || normalized.status === 403) {
          onForbidden();
        } else {
          addToast(normalized.message);
        }
      }
    };
    load();
    return () => controller.abort();
  }, [addToast, createClubId, date, onForbidden]);

  const activeList = useMemo(
    () => guestLists.find((list) => list.id === guestListId) ?? null,
    [guestListId, guestLists],
  );

  const handleCreate = useCallback(async () => {
    if (!createClubId || !date || !arrivalWindow || !eventId) {
      addToast('Заполните клуб, дату и окно прибытия');
      return;
    }
    const window = formatWindowToIso(date, arrivalWindow);
    if (!window) {
      addToast('Окно прибытия должно быть в формате HH:mm-HH:mm');
      return;
    }
    try {
      const created = await createGuestList({
        clubId: createClubId,
        eventId,
        arrivalWindowStart: window.startIso,
        arrivalWindowEnd: window.endIso,
        limit,
        name: title || null,
      });
      addToast('Список создан');
      setGuestLists((prev) => [created.guestList, ...prev]);
      onSelectGuestList(created.guestList.id);
    } catch (error) {
      const normalized = normalizePromoterError(error);
      if (normalized.status === 401 || normalized.status === 403) {
        onForbidden();
      } else {
        addToast(normalized.message);
      }
    }
  }, [addToast, arrivalWindow, createClubId, date, eventId, limit, onForbidden, onSelectGuestList, title]);

  const handleBulkAdd = useCallback(async () => {
    if (!activeList) {
      addToast('Выберите гостевой список');
      return;
    }
    const { lines, normalized } = normalizeBulkText(bulkText);
    if (lines.length === 0) {
      addToast('Вставьте имена гостей');
      return;
    }
    try {
      const result = await addGuestListEntriesBulk(activeList.id, normalized);
      addToast(`Добавлено гостей: ${result.addedCount}`);
      setBulkText('');
    } catch (error) {
      const normalizedError = normalizePromoterError(error);
      if (normalizedError.status === 401 || normalizedError.status === 403) {
        onForbidden();
      } else {
        addToast(normalizedError.message);
      }
    }
  }, [activeList, addToast, bulkText, onForbidden]);

  const handleAddSingle = useCallback(async () => {
    if (!activeList) {
      addToast('Выберите гостевой список');
      return;
    }
    if (!singleName.trim()) {
      addToast('Введите имя гостя');
      return;
    }
    try {
      await addGuestListEntry(activeList.id, singleName.trim());
      addToast('Гость добавлен');
      setSingleName('');
    } catch (error) {
      const normalizedError = normalizePromoterError(error);
      if (normalizedError.status === 401 || normalizedError.status === 403) {
        onForbidden();
      } else {
        addToast(normalizedError.message);
      }
    }
  }, [activeList, addToast, onForbidden, singleName]);

  return (
    <div className="px-4 py-6">
      <h2 className="text-lg font-semibold text-gray-900">Списки</h2>
      <div className="mt-4 rounded-lg bg-white p-4 shadow-sm">
        <div className="text-sm font-semibold text-gray-900">Создать список</div>
        <div className="mt-3 grid gap-3">
          <label className="text-xs text-gray-500">
            Клуб
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={createClubId ?? ''}
              onChange={(event) => {
                const next = parseOptionalId(event.target.value);
                setCreateClubId(next);
                onSelectClub(next);
              }}
            >
              <option value="">Выберите клуб</option>
              {clubs.map((club) => (
                <option key={club.id} value={club.id}>
                  {club.name}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-gray-500">
            Дата
            <input
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              type="date"
              value={date}
              onChange={(event) => setDate(event.target.value)}
            />
          </label>
          <label className="text-xs text-gray-500">
            Событие
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={eventId ?? ''}
              onChange={(event) => setEventId(parseOptionalId(event.target.value))}
            >
              <option value="">Выберите событие</option>
              {events.map((event) => (
                <option key={event.id} value={event.id}>
                  {event.title ?? `Event #${event.id}`}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-gray-500">
            Окно прибытия (HH:mm-HH:mm)
            <input
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={arrivalWindow}
              onChange={(event) => setArrivalWindow(event.target.value)}
              placeholder="23:00-01:00"
            />
          </label>
          <label className="text-xs text-gray-500">
            Лимит
            <input
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              type="number"
              min={1}
              value={limit}
              onChange={(event) => setLimit(Number(event.target.value))}
            />
          </label>
          <label className="text-xs text-gray-500">
            Название (опционально)
            <input
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
            />
          </label>
          <button
            type="button"
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
            onClick={handleCreate}
          >
            Создать
          </button>
        </div>
      </div>

      <div className="mt-6 rounded-lg bg-white p-4 shadow-sm">
        <div className="text-sm font-semibold text-gray-900">Добавить гостей</div>
        <label className="mt-3 block text-xs text-gray-500">
          Гостевой список
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={guestListId ?? ''}
              onChange={(event) => onSelectGuestList(parseOptionalId(event.target.value))}
            >
            <option value="">Выберите список</option>
            {guestLists.map((list) => (
              <option key={list.id} value={list.id}>
                {list.name} (#{list.id})
              </option>
            ))}
          </select>
        </label>
        <div className="mt-3 grid gap-3">
          <label className="text-xs text-gray-500">
            Массовая вставка (1 строка = 1 гость)
            <textarea
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              rows={4}
              value={bulkText}
              onChange={(event) => setBulkText(event.target.value)}
            />
          </label>
          <button
            type="button"
            className="rounded-md border border-blue-600 px-4 py-2 text-sm font-semibold text-blue-600"
            onClick={handleBulkAdd}
          >
            Добавить пачкой
          </button>
        </div>
        <div className="mt-4 grid gap-3">
          <label className="text-xs text-gray-500">
            Добавить по одному
            <input
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={singleName}
              onChange={(event) => setSingleName(event.target.value)}
            />
          </label>
          <button
            type="button"
            className="rounded-md border border-gray-200 px-4 py-2 text-sm font-semibold text-gray-700"
            onClick={handleAddSingle}
          >
            Добавить гостя
          </button>
        </div>
      </div>

      <div className="mt-6 rounded-lg bg-white p-4 shadow-sm">
        <div className="text-sm font-semibold text-gray-900">Ваши списки</div>
        {loadingLists ? (
          <div className="mt-2 text-sm text-gray-500">Загрузка...</div>
        ) : guestLists.length === 0 ? (
          <div className="mt-2 text-sm text-gray-500">Пока нет списков.</div>
        ) : (
          <ul className="mt-2 space-y-2 text-sm text-gray-700">
            {guestLists.map((list) => (
              <li
                key={list.id}
                className={`rounded-md border px-3 py-2 ${list.id === guestListId ? 'border-blue-500 bg-blue-50' : 'border-gray-200'}`}
              >
                <button type="button" className="w-full text-left" onClick={() => onSelectGuestList(list.id)}>
                  <div className="font-medium">{list.name}</div>
                  <div className="text-xs text-gray-500">
                    Club #{list.clubId} · Event #{list.eventId} · лимит {list.limit}
                  </div>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

export { normalizeBulkText };
