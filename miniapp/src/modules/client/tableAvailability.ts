export const tableStatuses = {
  FREE: {
    label: 'Свободен',
    symbol: '✓',
    color: 'border-emerald-700 bg-emerald-100 text-emerald-900',
  },
  HOLD: {
    label: 'Временный HOLD',
    symbol: 'H',
    color: 'border-amber-700 bg-amber-100 text-amber-900',
  },
  BOOKED: {
    label: 'Забронирован',
    symbol: 'Б',
    color: 'border-blue-700 bg-blue-100 text-blue-900',
  },
  OCCUPIED: {
    label: 'Занят',
    symbol: '●',
    color: 'border-red-700 bg-red-100 text-red-900',
  },
  UNAVAILABLE: {
    label: 'Недоступен',
    symbol: '×',
    color: 'border-gray-600 bg-gray-200 text-gray-900',
  },
  UNKNOWN: {
    label: 'Невозможно определить доступность',
    symbol: '?',
    color: 'border-slate-500 bg-white text-slate-800',
  },
};

export const availabilityReasons = {
  LAYOUT_INACTIVE: 'Зал или стол на схеме отключён.',
  TABLE_UNLINKED: 'У стола нет подтверждённой связи с системой бронирования.',
  TABLE_LINK_MISMATCH: 'Связь стола не удалось подтвердить.',
  BOOKING_TABLE_INACTIVE: 'Стол отключён для продаж.',
  NIGHT_CANCELLED: 'Клубная ночь отменена.',
  EVENT_UNLINKED: 'Для клубной ночи не связано событие.',
  EVENT_MISMATCH: 'Связь ночи с событием или его время не подтверждены.',
  OCCUPANCY_CONFLICT:
    'Данные об удержании, брони или сессии противоречат друг другу.',
};

export type TableAvailability = {
  id: string;
  status: keyof typeof tableStatuses;
  reason?: keyof typeof availabilityReasons;
};

export type AvailabilitySnapshot = {
  clubId: string;
  hallId: string;
  nightId: string;
  businessDate: string;
  layoutRevision: string;
  sampledAt: string;
  tables: TableAvailability[];
};

export function validAvailabilityTables(tables: TableAvailability[]) {
  return (
    Array.isArray(tables) &&
    tables.every(
      (row) =>
        row &&
        typeof row.id === 'string' &&
        Object.hasOwn(tableStatuses, row.status),
    ) &&
    new Set(tables.map((row) => row.id)).size === tables.length
  );
}
