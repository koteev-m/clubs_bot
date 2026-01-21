import { useCallback, useEffect, useMemo, useState } from 'react';
import HallPlanStage from '../../admin/pages/HallPlanStage';
import type { AdminTable } from '../../admin/api/admin.api';
import { useUiStore } from '../../../shared/store/ui';
import {
  assignBooking,
  getGuestListDetails,
  listClubEvents,
  listClubHalls,
  listGuestLists,
  listHallTables,
  listPromoterClubs,
  normalizePromoterError,
  PromoterClub,
  PromoterEvent,
  PromoterGuestList,
  PromoterGuestListEntry,
  PromoterHall,
  PromoterTable,
} from '../api/promoter.api';

type TablesScreenProps = {
  clubId: number | null;
  eventId: number | null;
  guestListId: number | null;
  onSelectClub: (id: number | null) => void;
  onSelectEvent: (id: number | null) => void;
  onSelectGuestList: (id: number | null) => void;
  onForbidden: () => void;
};

const parseOptionalId = (value: string) => {
  const id = Number(value);
  if (!Number.isFinite(id) || id <= 0) return null;
  return id;
};

const mapPromoterTableToAdmin = (table: PromoterTable): AdminTable => ({
  id: table.id,
  hallId: table.hallId,
  clubId: table.clubId,
  label: table.label,
  minDeposit: table.minDeposit,
  capacity: table.capacity,
  zone: table.zone ?? null,
  zoneName: table.zoneName ?? null,
  arrivalWindow: table.arrivalWindow ?? null,
  mysteryEligible: table.mysteryEligible,
  tableNumber: table.tableNumber,
  x: table.x,
  y: table.y,
});

export default function TablesScreen({
  clubId,
  eventId,
  guestListId,
  onSelectClub,
  onSelectEvent,
  onSelectGuestList,
  onForbidden,
}: TablesScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const [clubs, setClubs] = useState<PromoterClub[]>([]);
  const [events, setEvents] = useState<PromoterEvent[]>([]);
  const [halls, setHalls] = useState<PromoterHall[]>([]);
  const [tables, setTables] = useState<PromoterTable[]>([]);
  const [guestLists, setGuestLists] = useState<PromoterGuestList[]>([]);
  const [entries, setEntries] = useState<PromoterGuestListEntry[]>([]);
  const [selectedHallId, setSelectedHallId] = useState<number | null>(null);
  const [selectedEntryId, setSelectedEntryId] = useState<number | null>(null);
  const [selectedTableId, setSelectedTableId] = useState<number | null>(null);
  const [date, setDate] = useState('');

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
    if (!clubId) {
      setHalls([]);
      setTables([]);
      setGuestLists([]);
      return;
    }
    const controller = new AbortController();
    const load = async () => {
      try {
        const [hallsData, listsData] = await Promise.all([
          listClubHalls(clubId, controller.signal),
          listGuestLists({ clubId }, controller.signal),
        ]);
        setHalls(hallsData);
        setGuestLists(listsData);
        if (hallsData.length > 0) {
          setSelectedHallId(hallsData[0].id);
        }
        if (!guestListId && listsData.length > 0) {
          onSelectGuestList(listsData[0].id);
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
  }, [addToast, clubId, guestListId, onForbidden, onSelectGuestList]);

  useEffect(() => {
    if (!clubId || !date) {
      setEvents([]);
      return;
    }
    const controller = new AbortController();
    const load = async () => {
      try {
        const eventsData = await listClubEvents(clubId, date, controller.signal);
        setEvents(eventsData);
        if (eventsData.length > 0 && !eventId) {
          onSelectEvent(eventsData[0].id);
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
  }, [addToast, clubId, date, eventId, onForbidden, onSelectEvent]);

  useEffect(() => {
    if (!selectedHallId) {
      setTables([]);
      return;
    }
    const controller = new AbortController();
    const load = async () => {
      try {
        const tablesData = await listHallTables(selectedHallId, controller.signal);
        setTables(tablesData);
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
  }, [addToast, onForbidden, selectedHallId]);

  useEffect(() => {
    if (!guestListId) {
      setEntries([]);
      return;
    }
    const controller = new AbortController();
    const load = async () => {
      try {
        const details = await getGuestListDetails(guestListId, controller.signal);
        setEntries(details.entries);
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
  }, [addToast, guestListId, onForbidden]);

  const planUrl = useMemo(() => {
    if (!clubId || !selectedHallId) return '';
    return `/api/clubs/${clubId}/halls/${selectedHallId}/plan`;
  }, [clubId, selectedHallId]);

  const activeTables = useMemo(
    () =>
      tables.map((table) => ({
        ...table,
        hallId: selectedHallId ?? table.hallId,
        clubId: clubId ?? table.clubId,
      })),
    [clubId, selectedHallId, tables],
  );

  const stageTables = useMemo(() => activeTables.map(mapPromoterTableToAdmin), [activeTables]);

  const handleAssign = useCallback(async () => {
    if (!selectedEntryId || !selectedTableId || !selectedHallId || !eventId) {
      addToast('Выберите гостя, стол и событие');
      return;
    }
    try {
      await assignBooking({
        guestListEntryId: selectedEntryId,
        hallId: selectedHallId,
        tableId: selectedTableId,
        eventId,
      });
      addToast('Стол забронирован');
    } catch (error) {
      const normalized = normalizePromoterError(error);
      if (normalized.status === 401 || normalized.status === 403) {
        onForbidden();
      } else {
        addToast(normalized.message);
      }
    }
  }, [addToast, eventId, onForbidden, selectedEntryId, selectedHallId, selectedTableId]);

  return (
    <div className="px-4 py-6">
      <h2 className="text-lg font-semibold text-gray-900">Столы</h2>
      <div className="mt-4 rounded-lg bg-white p-4 shadow-sm">
        <div className="grid gap-3">
          <label className="text-xs text-gray-500">
            Клуб
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={clubId ?? ''}
              onChange={(event) => onSelectClub(parseOptionalId(event.target.value))}
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
              onChange={(event) => onSelectEvent(parseOptionalId(event.target.value))}
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
            Гостевой список
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={guestListId ?? ''}
              onChange={(event) => onSelectGuestList(parseOptionalId(event.target.value))}
            >
              <option value="">Выберите список</option>
              {guestLists.map((list) => (
                <option key={list.id} value={list.id}>
                  {list.name}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-gray-500">
            Гость
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={selectedEntryId ?? ''}
              onChange={(event) => setSelectedEntryId(parseOptionalId(event.target.value))}
            >
              <option value="">Выберите гостя</option>
              {entries.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.displayName}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-gray-500">
            Зал
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={selectedHallId ?? ''}
              onChange={(event) => setSelectedHallId(parseOptionalId(event.target.value))}
            >
              <option value="">Выберите зал</option>
              {halls.map((hall) => (
                <option key={hall.id} value={hall.id}>
                  {hall.name}
                </option>
              ))}
            </select>
          </label>
        </div>
      </div>

      {planUrl && activeTables.length > 0 ? (
        <div className="mt-6 rounded-lg bg-white p-4 shadow-sm">
          <div className="text-sm font-semibold text-gray-900">План зала</div>
          <div className="mt-3">
            <HallPlanStage
              planUrl={planUrl}
              tables={stageTables}
              selectedTableId={selectedTableId}
              readOnly
              onSelectTable={(id) => setSelectedTableId(id)}
              onCreateTable={() => {}}
              onMoveTable={() => {}}
            />
          </div>
        </div>
      ) : null}

      {activeTables.length > 0 ? (
        <div className="mt-6 rounded-lg bg-white p-4 shadow-sm">
          <div className="text-sm font-semibold text-gray-900">Выбрать стол</div>
          <div className="mt-2 flex flex-wrap gap-2">
            {activeTables.map((table) => (
              <button
                key={table.id}
                type="button"
                className={`rounded-md border px-3 py-2 text-xs ${
                  selectedTableId === table.id ? 'border-blue-600 bg-blue-50 text-blue-700' : 'border-gray-200'
                }`}
                onClick={() => setSelectedTableId(table.id)}
              >
                {table.label}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      <div className="mt-6">
        <button
          type="button"
          className="w-full rounded-md bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
          onClick={handleAssign}
        >
          Забронировать стол
        </button>
      </div>
    </div>
  );
}
