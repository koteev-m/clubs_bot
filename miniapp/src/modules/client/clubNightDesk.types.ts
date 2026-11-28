export const permissions = {
  read: 'client.club.operations.read',
  booking: 'client.club.operations.booking.write',
  seat: 'client.club.operations.seat',
  release: 'client.club.operations.release',
  payments: 'client.club.operations.table_money.read',
  deposit: 'client.club.operations.deposit.record',
  topUp: 'client.club.operations.top_up.record',
} as const;

export type Method = 'CASH' | 'CARD' | 'MIXED';
export type Mode = 'DEPOSIT' | 'BILL' | 'CLUB';
export const bookingActions = [
  'create_booking',
  'update_booking',
  'move_booking',
  'cancel_booking',
  'extend_wait',
  'no_show',
  'stop_sales',
  'resume_sales',
] as const;
export type BookingAction = (typeof bookingActions)[number];
export type SessionAction = 'seat' | 'release' | 'top_up';
export type Action = SessionAction | BookingAction;
export type TableStatus =
  | 'free'
  | 'reserved'
  | 'seated'
  | 'stopped'
  | 'unavailable';
export type Payment = {
  operationId: string;
  amountMinor: string;
  method?: Method | null;
  cashMinor?: string;
  cardMinor?: string;
};
export type Table = {
  id: string;
  number: number;
  label: string;
  capacity: number;
  status: TableStatus;
  sessionId: string | null;
  lastSessionId: string | null;
  bookingId: string | null;
  seatedGuests?: number;
  x?: number;
  y?: number;
  payments?: Payment[];
  salesStopped?: boolean;
  minDeposit?: string | null;
};
export type Hall = {
  id: string;
  name: string;
  tables: Table[];
  plan?: { sha256: string; layoutRevision: string };
};
export type Night = {
  id: string;
  businessDate: string;
  opensAt: string;
  closesAt: string;
};
export type Booking = {
  id: string;
  tableId: string | null;
  guestName?: string | null;
  username?: string | null;
  phone?: string | null;
  guests: number;
  arrivalBy?: string | null;
  status: string;
  entryStatus?: string | null;
  minDeposit?: string | null;
  bookingDepositMinor?: string | null;
  source?: string | null;
  waitUntil?: string | null;
  note?: string | null;
  conditions?: string | null;
  updatedAt: string;
  cancellationReason?: string | null;
};
export type Category = { id: string; name: string };
export type Categories = {
  version: string;
  primary: Category;
  extras: Category[];
};
export type Desk = {
  actorId: string;
  clubId: string;
  clientId: string;
  night: Night | null;
  upcomingNights?: Night[];
  future?: boolean;
  historical?: boolean;
  recentNights?: Night[];
  tableMoney?: (Payment & {
    tableId: string;
    tableNumber: number;
    recordedAt: string;
    kind: 'INITIAL' | 'TOPUP';
    allocations: { name: string; amountMinor: string }[];
  })[];
  blocked?: string;
  halls: Hall[];
  bookings: Booking[];
  categories: Categories;
  permissions: string[];
};

export type Command = {
  action: Action;
  nightId: string;
  tableId: string;
  expectedLastSessionId?: string | null;
  bookingId?: string;
  sessionId?: string;
  mode?: Mode;
  guests?: number;
  note?: string;
  amountMinor?: string;
  method?: Method;
  cashMinor?: string;
  categoryVersion?: string;
  extras?: Record<string, string>;
  guestName?: string;
  phone?: string;
  arrivalBy?: string;
  expectedUpdatedAt?: string;
  bookingDepositMinor?: string;
  destinationTableId?: string;
  reason?: string;
  waitUntil?: string;
};
export type Receipt = {
  action: Action;
  nightId: string;
  tableId: string;
  sessionId: string | null;
  bookingId?: string | null;
} & Partial<Payment>;
export type Intent = {
  key: string;
  body: Command;
  label: string;
  businessDate: string;
};

export function isBookingAction(action: Action): action is BookingAction {
  return (bookingActions as readonly Action[]).includes(action);
}

export function permits(view: Desk, permission: string) {
  return view.permissions.includes(permission);
}
export function tableName(table: Table) {
  return table.label.trim() || 'Стол ' + table.number;
}
export function seatingAvailable(table: Table) {
  return (
    !table.salesStopped &&
    (table.status === 'free' ||
      (table.status === 'reserved' && Boolean(table.bookingId)))
  );
}
export const dateFormat = (date: string) => date.split('-').reverse().join('.');
export function formatTime(value: string, timeZone: string) {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return 'Время не подтверждено';
  return new Intl.DateTimeFormat('ru-RU', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}
