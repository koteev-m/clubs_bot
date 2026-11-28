import { CalendarDays, ChevronRight, Grid2X2, Menu, Moon, Wallet } from 'lucide-react';
import { useState } from 'react';
import { dateFormat, formatTime, tableName } from './clubNightDesk.types';
import type { Desk, TableStatus } from './clubNightDesk.types';

export type WorkspaceTab = 'shift' | 'bookings' | 'tables' | 'finance';
export type WorkspaceLocation = {
  tab: WorkspaceTab;
  night: string;
  nights: Partial<Record<WorkspaceTab, string>>;
  search: string;
  filter: string;
  tableId?: string;
  bookingId?: string;
  tableMap: boolean;
  hallId?: string;
  status?: TableStatus;
};
const sections = [
  { id: 'shift', label: 'Смена', Icon: Moon },
  { id: 'bookings', label: 'Брони', Icon: CalendarDays },
  { id: 'tables', label: 'Столы', Icon: Grid2X2 },
  { id: 'finance', label: 'Финансы', Icon: Wallet },
] as const;
const metrics = [
  { status: 'free', label: 'Свободны' },
  { status: 'seated', label: 'Заняты' },
  { status: 'reserved', label: 'Ждём брони' },
] as const;

export function WorkspaceHeader({ name, view, timezone, menu, clubs, chooseClub, manage }: {
  name: string;
  view?: Desk;
  timezone: string;
  menu: { label: string; run: () => void }[];
  clubs: { id: string; name: string }[];
  chooseClub?: (id: string) => void;
  manage?: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const night = view?.night;
  const businessDate = night && dateFormat(night.businessDate);
  const openingDate = night && new Intl.DateTimeFormat('ru-RU', {
    timeZone: timezone, day: '2-digit', month: '2-digit', year: 'numeric',
  }).format(new Date(night.opensAt));
  return (
    <header className="night-work-header">
      <div className="night-work-title">
        <div><span className="night-work-brand">nc · Night Concierge</span><h2>{name}</h2></div>
        {(menu.length > 0 || clubs.length > 1) &&
          <button type="button" className="ob-button night-work-menu" aria-label="Меню кабинета"
            aria-expanded={expanded} onClick={() => setExpanded(!expanded)}><Menu size={19} /></button>}
      </div>
      <div className="night-work-meta">
        <span>{night
          ? businessDate + ' · ' + (businessDate === openingDate ? '' : openingDate + ', ') +
            formatTime(night.opensAt, timezone) + '–' + formatTime(night.closesAt, timezone)
          : view ? 'Нет открытой ночи' : 'Загружаем ночь'}</span>
        {view?.night && <span data-open={!view.blocked && !view.future && !view.historical}>
          {view.blocked
            ? 'Недоступна' : view.future ? 'Запланирована' : view.historical ? 'Завершена' : 'Открыта'}</span>}
      </div>
      {manage && <button type="button" className="ob-button club-management-entry" onClick={manage}>Управление клубом</button>}
      {expanded && (
        <div className="night-work-menu-panel">
          {chooseClub && clubs.length > 1 && (
            <label className="ob-field">Клуб
              <select value={view?.clubId ?? ''} onChange={(event) => chooseClub(event.target.value)}>
                {clubs.map((club) => <option key={club.id} value={club.id}>{club.name}</option>)}
              </select>
            </label>
          )}
          {menu.map(({ label, run }) => <button key={label} type="button" className="ob-button"
            onClick={() => { setExpanded(false); run(); }}>{label}</button>)}
        </div>
      )}
    </header>
  );
}

export function WorkspaceNavigation({ tab, finance, select }: {
  tab: WorkspaceTab;
  finance: boolean;
  select: (section: WorkspaceTab) => void;
}) {
  return (
    <nav className="night-work-nav" aria-label="Разделы рабочей ночи">
      {sections.filter(({ id }) => id !== 'finance' || finance).map(({ id, label, Icon }) => (
        <button key={id} type="button" aria-current={tab === id ? 'page' : undefined} onClick={() => select(id)}>
          <Icon size={19} aria-hidden="true" /><span>{label}</span>
        </button>
      ))}
    </nav>
  );
}

export function ShiftOverview({ view, timezone, showTables, openBooking, showBookings }: {
  view: Desk;
  timezone: string;
  showTables: (status: TableStatus) => void;
  openBooking: (id: string) => void;
  showBookings: () => void;
}) {
  if (view.blocked && view.blocked !== 'no_current_night') {
    return <h3 id="night-work-shift-title">Смена</h3>;
  }
  const tables = view.halls.flatMap((hall) => hall.tables);
  const upcoming = view.bookings.filter(({ status }) => status === 'BOOKED' || status === 'CONFIRMED')
    .sort((a, b) => (a.arrivalBy ?? 'z').localeCompare(b.arrivalBy ?? 'z'));
  const attention = upcoming.filter(({ waitUntil, entryStatus }) =>
    entryStatus === 'DENIED' || (entryStatus !== 'ARRIVED' && entryStatus !== 'LATE' &&
      Boolean(waitUntil && Date.parse(waitUntil) <= Date.now())));
  return (
    <section className="night-work-shift" aria-labelledby="night-work-shift-title">
      <h3 id="night-work-shift-title">Смена</h3>
      {view.night && (
        <div className="night-work-metrics">
          {metrics.map(({ status, label }) => (
            <button type="button" key={status} onClick={() => showTables(status)}>
              <strong>{tables.filter((table) => table.status === status).length}</strong><span>{label}</span>
            </button>
          ))}
        </div>
      )}
      <div className="night-work-section-title">
        <h4>Ближайшие брони</h4>
        <button type="button" className="ob-button" onClick={showBookings}>Все брони</button>
      </div>
      {attention.length > 0 && <button type="button" className="ob-button night-work-attention"
        onClick={() => openBooking(attention[0].id)}>Требуют внимания · {attention.length}</button>}
      {upcoming.length ? <ul className="night-desk-booking-list">
        {upcoming.slice(0, 4).map((booking) => {
          const table = tables.find(({ id }) => id === booking.tableId);
          return <li key={booking.id}><button type="button" className="ob-button night-work-reservation"
            onClick={() => openBooking(booking.id)}>
            <strong>{booking.arrivalBy ? formatTime(booking.arrivalBy, timezone) : '—'}</strong>
            <span><b>{booking.guestName ?? 'Гость без имени'}{table && ' · ' + tableName(table)}</b>
              <small>Гостей: {booking.guests} · {booking.entryStatus === 'DENIED' ? 'Отказ во входе' :
                booking.entryStatus === 'ARRIVED' || booking.entryStatus === 'LATE' ? 'Пришли' : 'Ожидаем'}</small>
            </span><ChevronRight size={16} aria-hidden="true" />
          </button></li>;
        })}
      </ul> : <p className="po-muted">{view.night ? 'Нет ожидаемых броней.' : 'Выберите ближайшую ночь в разделе «Брони».'}</p>}
    </section>
  );
}

export function WorkspaceMoney({ view, timezone, amount }: {
  view: Desk;
  timezone: string;
  amount: (value: string | bigint | undefined) => string;
}) {
  if (!view.tableMoney) return <p className="po-muted">Поступления доступны для начавшихся ночей.</p>;
  const rows = view.tableMoney;
  const sum = (values: (string | undefined | null)[]) => values.reduce<bigint>((total, value) =>
    total + (typeof value === 'string' && /^-?\d+$/.test(value) ? BigInt(value) : 0n), 0n);
  const categories = new Map<string, bigint>();
  for (const row of rows) for (const part of row.allocations) {
    categories.set(part.name, (categories.get(part.name) ?? 0n) + sum([part.amountMinor]));
  }
  const totals: [string, bigint][] = [
    ['Наличные', sum(rows.map((row) => row.method === 'CASH' ? row.amountMinor : row.cashMinor))],
    ['Карта', sum(rows.map((row) => row.method === 'CARD' ? row.amountMinor : row.cardMinor))],
    ['Первоначальные депозиты', sum(rows.filter((row) => row.kind === 'INITIAL').map((row) => row.amountMinor))],
    ['Доплаты', sum(rows.filter((row) => row.kind === 'TOPUP').map((row) => row.amountMinor))],
  ];
  const unknown = sum(rows.filter((row) => !row.method).map((row) => row.amountMinor));
  if (unknown) totals.push(['Способ оплаты неизвестен', unknown]);
  const rubles = (value: string | bigint | undefined) => {
    const formatted = amount(value);
    return formatted === 'Сумма неизвестна' ? formatted : formatted + '\u00a0₽';
  };
  return (
    <section aria-label="Поступления по столам" className="night-work-money">
      <div className="night-work-money-total">
        <h3>Поступления по столам</h3>
        <strong>{rubles(sum(rows.map((row) => row.amountMinor)))}</strong>
        <p>Депозиты и доплаты до корректировок</p>
      </div>
      <section>
        <h4>За выбранную ночь</h4>
        <dl className="po-details">{totals.map(([label, value]) => (
          <div key={label}><dt>{label}</dt><dd>{rubles(value)}</dd></div>
        ))}</dl>
      </section>
      {categories.size > 0 && <section>
        <h4>Распределение по категориям</h4>
        <dl className="po-details">{[...categories].map(([label, value]) => (
          <div key={label}><dt>{label}</dt><dd>{rubles(value)}</dd></div>
        ))}</dl>
      </section>}
      <section>
        <h4>Денежные операции столов</h4>
        {rows.length ? <ul className="night-desk-booking-list">{rows.map((row) => (
          <li key={row.operationId} className="night-desk-table">
            <strong>Стол №{row.tableNumber} (текущий номер) · {rubles(row.amountMinor)}</strong>
            <span>{formatTime(row.recordedAt, timezone)} · {row.kind === 'INITIAL' ? 'Депозит' : 'Доплата'} · {
              row.method === 'MIXED' ? 'наличные ' + rubles(row.cashMinor) + ' / карта ' + rubles(row.cardMinor) :
                row.method === 'CASH' ? 'наличные' : row.method === 'CARD' ? 'карта' : 'способ неизвестен'}</span>
          </li>
        ))}</ul> : <p>Операций за эту ночь нет.</p>}
      </section>
    </section>
  );
}
