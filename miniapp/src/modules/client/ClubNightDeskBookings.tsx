import { useState } from 'react';
import { wallTimeOptions } from './calendar';
import {
  dateFormat,
  formatTime,
  permissions,
  permits,
  seatingAvailable,
  tableName,
} from './clubNightDesk.types';
import type {
  Booking,
  BookingAction,
  Command,
  Desk,
  Hall,
  Night,
  Table,
} from './clubNightDesk.types';

const bookingStatuses: Record<string, string> = {
  BOOKED: 'Подтверждена',
  CONFIRMED: 'Подтверждена',
  SEATED: 'Гости за столом',
  NO_SHOW: 'Не пришли',
  CANCELLED: 'Отменена',
  EXPIRED: 'Истекла',
};
const entryStatuses: Record<string, string> = {
  ARRIVED: 'Вход зарегистрирован',
  LATE: 'Вход зарегистрирован с опозданием',
  DENIED: 'Вход запрещён',
  REFUSED: 'Вход запрещён',
};
const titles: Record<BookingAction, string> = {
  create_booking: 'Создать бронь',
  update_booking: 'Изменить бронь',
  move_booking: 'Перенести бронь',
  cancel_booking: 'Отменить бронь',
  extend_wait: 'Продлить ожидание',
  no_show: 'Отметить неявку',
  stop_sales: 'Остановить продажи',
  resume_sales: 'Возобновить продажи',
};

export type BookingDraft = {
  action: BookingAction;
  night: Night;
  table: Table;
  booking?: Booking;
  guestName: string;
  phone: string;
  guests: string;
  localTime: string;
  preferredInstant: string;
  note: string;
  reason: string;
  destination: string;
};
export function newBookingDraft(
  action: BookingAction,
  night: Night,
  table: Table,
  booking: Booking | undefined,
  timezone: string,
): BookingDraft {
  const instant =
    action === 'extend_wait' ? booking?.waitUntil : booking?.arrivalBy;
  return {
    action,
    night,
    table,
    booking,
    guestName: '',
    phone: '',
    guests: booking?.guests.toString() ?? '',
    localTime: instant ? clubLocalTime(instant, timezone) : '',
    preferredInstant: instant ?? '',
    note: '',
    reason: '',
    destination: '',
  };
}
export function bookingSuccess(action: BookingAction) {
  switch (action) {
    case 'create_booking':
      return 'бронь создана';
    case 'update_booking':
      return 'бронь обновлена';
    case 'move_booking':
      return 'бронь перенесена';
    case 'cancel_booking':
      return 'бронь отменена';
    case 'extend_wait':
      return 'ожидание продлено';
    case 'no_show':
      return 'неявка отмечена';
    case 'stop_sales':
      return 'продажи остановлены';
    case 'resume_sales':
      return 'продажи возобновлены';
  }
}
const isActive = (booking: Booking) =>
  ['BOOKED', 'CONFIRMED'].includes(booking.status);

export function BookingsList({
  view,
  timezone,
  disabled,
  selected,
  select,
  open,
  seat,
}: {
  view: Desk;
  timezone: string;
  disabled: boolean;
  selected?: string;
  select: (id?: string) => void;
  open: (action: BookingAction, table: Table, booking?: Booking) => void;
  seat: (hall: Hall, table: Table) => void;
}) {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('');
  const tables = view.halls.flatMap(({ tables }) => tables);
  const selectedBooking = view.bookings.find(({ id }) => id === selected);
  const term = search.trim().toLocaleLowerCase('ru');
  const shownBookings = view.bookings.filter((booking) => {
    const table = tables.find(({ id }) => id === booking.tableId);
    return (
      (!filter || filter === booking.status) &&
      (!term ||
        [
          booking.guestName,
          booking.username,
          booking.phone,
          table && tableName(table),
          table?.number,
        ].some((value) =>
          value?.toString().toLocaleLowerCase('ru').includes(term),
        ))
    );
  });
  return (
    <section className="night-desk-bookings" aria-label="Бронирования">
      <div className="night-desk-fields">
        <label className="ob-field">
          Поиск брони
          <input
            type="search"
            value={search}
            placeholder="Имя, телефон или стол"
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <label className="ob-field">
          Статус
          <select
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          >
            <option value="">Все статусы</option>
            {Array.from(new Set(view.bookings.map(({ status }) => status))).map(
              (status) => (
                <option key={status} value={status}>
                  {bookingStatuses[status] ?? status}
                </option>
              ),
            )}
          </select>
        </label>
      </div>
      {selectedBooking && (
        <BookingCard
          booking={selectedBooking}
          view={view}
          timezone={timezone}
          close={() => select(undefined)}
          seat={seat}
          open={open}
          disabled={disabled}
        />
      )}
      {shownBookings.length === 0 && <p>По этим условиям брони не найдены.</p>}
      <ul className="night-desk-booking-list">
        {shownBookings.map((booking) => (
          <li key={booking.id}>
            <button
              type="button"
              className="ob-button"
              onClick={() => select(booking.id)}
            >
              <strong>{booking.guestName ?? 'Гость без имени'}</strong>
              <span>
                {bookingStatuses[booking.status] ?? booking.status} · гостей:{' '}
                {booking.guests}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function BookingCard({
  booking,
  view,
  timezone,
  close,
  seat,
  open,
  disabled,
}: {
  booking: Booking;
  view: Desk;
  timezone: string;
  close: () => void;
  seat: (hall: Hall, table: Table) => void;
  open: (action: BookingAction, table: Table, booking: Booking) => void;
  disabled: boolean;
}) {
  const hall = view.halls.find(({ tables }) =>
    tables.some(({ id }) => id === booking.tableId),
  );
  const table = hall?.tables.find(({ id }) => id === booking.tableId);
  const canSeat =
    hall &&
    table &&
    isActive(booking) &&
    seatingAvailable(table) &&
    table.bookingId === booking.id &&
    permits(view, permissions.seat) &&
    entryStatuses[booking.entryStatus ?? ''] !== entryStatuses.DENIED;
  const canChange =
    table && isActive(booking) && permits(view, permissions.booking);
  const user = booking.username?.replace(/^@/, '');
  const entry = booking.entryStatus;
  const details: Array<[string, string | number]> = [
    ['Ночь', view.night ? dateFormat(view.night.businessDate) : 'Не указана'],
    [
      'Ожидаемый приход',
      booking.arrivalBy ? formatTime(booking.arrivalBy, timezone) : 'Не указан',
    ],
    [
      'Зал и стол',
      hall && table ? hall.name + ' · ' + tableName(table) : 'Стол не найден',
    ],
    ['Плановое число гостей', booking.guests],
    ['Статус брони', bookingStatuses[booking.status] ?? booking.status],
    [
      'Вход',
      entry
        ? (entryStatuses[entry] ?? 'Состояние не подтверждено')
        : 'Отметка отсутствует',
    ],
    [
      'Источник',
      booking.source === 'CLUB'
        ? 'Клуб'
        : (booking.source ?? 'Источник не подтверждён'),
    ],
    ['Минимальный депозит', booking.minDeposit ?? 'Не указан'],
    [
      'Ожидание до',
      booking.waitUntil
        ? formatTime(booking.waitUntil, timezone)
        : 'Не указано',
    ],
  ];
  if (user) details.unshift(['Telegram', '@' + user]);
  if (booking.phone) details.unshift(['Телефон для брони', booking.phone]);
  if (booking.conditions) details.push(['Условия брони', booking.conditions]);
  if (booking.note) details.push(['Пожелания', booking.note]);
  if (booking.cancellationReason)
    details.push(['Причина отмены', booking.cancellationReason]);
  return (
    <section className="night-desk-form" aria-label="Карточка брони">
      <h3>{booking.guestName ?? 'Гость без имени'}</h3>
      <dl className="po-details">
        {details.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      <div className="night-desk-actions">
        {canSeat && (
          <button
            type="button"
            className="ob-button po-primary"
            onClick={() => seat(hall, table)}
            disabled={disabled}
          >
            Посадить по брони
          </button>
        )}
        {canChange &&
          (
            [
              'update_booking',
              'move_booking',
              'extend_wait',
              'cancel_booking',
              'no_show',
            ] as const
          ).map((action) => (
            <button
              key={action}
              type="button"
              className="ob-button"
              onClick={() => open(action, table, booking)}
              disabled={disabled}
            >
              {titles[action]}
            </button>
          ))}
        <button type="button" className="ob-button" onClick={close}>
          Закрыть карточку
        </button>
      </div>
    </section>
  );
}

export function BookingForm({
  draft,
  view,
  timezone,
  fresh,
  blocked,
  problem,
  set,
  execute,
  close,
}: {
  draft: BookingDraft;
  view?: Desk;
  timezone: string;
  fresh: boolean;
  blocked: boolean;
  problem: string;
  set: (draft: BookingDraft) => void;
  execute: (body: Command, table: Table, night: Night) => void;
  close: () => void;
}) {
  const { action, table, night, booking } = draft;
  const [validation, setValidation] = useState('');
  const current = view?.halls
    .flatMap(({ tables }) => tables)
    .find(({ id }) => id === table.id);
  const latestBooking =
    booking && view?.bookings.find(({ id }) => id === booking.id);
  const scopeChanged = Boolean(
    view &&
      (view.night?.id !== night.id ||
        (booking
          ? latestBooking?.updatedAt !== booking.updatedAt
          : !current ||
            current.status !== table.status ||
            current.salesStopped !== table.salesStopped)),
  );
  const disabled =
    !fresh ||
    blocked ||
    scopeChanged ||
    !view ||
    !permits(view, permissions.booking);
  const optionalTime =
    action === 'create_booking' || action === 'update_booking';
  const asksTime = optionalTime || action === 'extend_wait';
  const [date, time] = draft.localTime.split('T');
  const times =
    time && /^\d{4}-\d{2}-\d{2}$/.test(date)
      ? wallTimeOptions(date, time, timezone)
      : [];
  const moveOptions =
    view?.halls.flatMap((hall) =>
      hall.tables
        .filter(
          (option) =>
            option.status === 'free' &&
            !option.salesStopped &&
            option.id !== table.id &&
            option.capacity >= (booking?.guests ?? 0),
        )
        .map((option) => ({
          option,
          label: hall.name + ' · ' + tableName(option),
        })),
    ) ?? [];
  const update = (values: Partial<BookingDraft>) => {
    setValidation('');
    set({ ...draft, ...values });
  };

  function submit() {
    if (disabled) return;
    const body: Command = { action, nightId: night.id, tableId: table.id };
    if (booking) {
      body.bookingId = booking.id;
      body.expectedUpdatedAt = booking.updatedAt;
    }
    if (optionalTime) {
      const guests = /^\d{1,2}$/.test(draft.guests) ? Number(draft.guests) : 0;
      if (guests < 1 || guests > Math.min(table.capacity, 50)) {
        setValidation('Проверьте число гостей с учётом мест за столом.');
        return;
      }
      body.guests = guests;
    }
    if (asksTime && (draft.localTime || !optionalTime)) {
      const preferred = times.find(
        (candidate) =>
          Date.parse(candidate) === Date.parse(draft.preferredInstant),
      );
      const instant = times.length === 1 ? times[0] : preferred;
      if (!instant) {
        setValidation(
          'Укажите существующее местное время и выберите смещение, если время повторяется.',
        );
        return;
      }
      if (action === 'extend_wait')
        body.waitUntil = new Date(instant).toISOString();
      else body.arrivalBy = new Date(instant).toISOString();
    }
    if (action === 'create_booking') {
      if (!draft.guestName.trim()) {
        setValidation('Укажите имя для брони.');
        return;
      }
      body.guestName = draft.guestName.trim();
      if (draft.phone.trim()) {
        const phone = draft.phone.trim().replace(/[ ()-]/g, '');
        if (!/^\+[1-9]\d{6,14}$/.test(phone)) {
          setValidation(
            'Укажите телефон в международном формате с + или оставьте поле пустым.',
          );
          return;
        }
        body.phone = phone;
      }
      if (draft.note.trim()) body.note = draft.note.trim();
    }
    if (action === 'move_booking') {
      if (!moveOptions.some(({ option }) => option.id === draft.destination)) {
        setValidation('Выберите доступный стол.');
        return;
      }
      body.destinationTableId = draft.destination;
    }
    if (action === 'cancel_booking') {
      if (!draft.reason.trim()) {
        setValidation('Укажите причину отмены.');
        return;
      }
      body.reason = draft.reason.trim();
    }
    execute(body, table, night);
  }

  return (
    <form
      aria-label={titles[action]}
      className="night-desk-form"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <h3>{titles[action]}</h3>
      <p>
        <strong>{tableName(table)}</strong> · {dateFormat(night.businessDate)}
        {booking?.guestName && ' · ' + booking.guestName}
      </p>
      {scopeChanged && (
        <p className="po-notice" role="alert">
          Исходные данные изменились. Ввод сохранён для сверки. Закройте форму
          перед новой операцией.
        </p>
      )}
      <fieldset disabled={blocked}>
        {action === 'create_booking' && (
          <>
            <label className="ob-field">
              Имя гостя
              <input
                value={draft.guestName}
                maxLength={120}
                required
                onChange={(event) => update({ guestName: event.target.value })}
              />
            </label>
            <label className="ob-field">
              Телефон для брони (по желанию)
              <input
                type="tel"
                value={draft.phone}
                maxLength={30}
                onChange={(event) => update({ phone: event.target.value })}
              />
            </label>
            <label className="ob-field">
              Пожелания (по желанию)
              <textarea
                value={draft.note}
                maxLength={500}
                onChange={(event) => update({ note: event.target.value })}
              />
            </label>
          </>
        )}
        {optionalTime && (
          <label className="ob-field">
            Плановое число гостей
            <input
              type="number"
              min={1}
              max={Math.min(table.capacity, 50)}
              value={draft.guests}
              required
              onChange={(event) => update({ guests: event.target.value })}
            />
          </label>
        )}
        {asksTime && (
          <label className="ob-field">
            {optionalTime ? 'Ожидаемый приход' : 'Ожидать до'} · время клуба
            <input
              type="datetime-local"
              value={draft.localTime}
              required={!optionalTime}
              onChange={(event) =>
                update({ localTime: event.target.value, preferredInstant: '' })
              }
            />
          </label>
        )}
        {times.length > 1 && (
          <label className="ob-field">
            Какое смещение используется
            <select
              value={
                times.find(
                  (candidate) =>
                    Date.parse(candidate) ===
                    Date.parse(draft.preferredInstant),
                ) ?? ''
              }
              required
              onChange={(event) =>
                update({ preferredInstant: event.target.value })
              }
            >
              <option value="">Выберите смещение</option>
              {times.map((instant) => (
                <option key={instant} value={instant}>
                  UTC{instant.slice(19)}
                </option>
              ))}
            </select>
          </label>
        )}
        {action === 'move_booking' && (
          <label className="ob-field">
            Новый стол
            <select
              value={draft.destination}
              required
              onChange={(event) => update({ destination: event.target.value })}
            >
              <option value="">Выберите стол</option>
              {moveOptions.map(({ option, label }) => (
                <option key={option.id} value={option.id}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        )}
        {action === 'cancel_booking' && (
          <label className="ob-field">
            Причина отмены
            <textarea
              value={draft.reason}
              required
              maxLength={500}
              onChange={(event) => update({ reason: event.target.value })}
            />
          </label>
        )}
      </fieldset>
      {(validation || problem) && (
        <p className="po-notice" role="alert">
          {validation || problem}
        </p>
      )}
      <div className="night-desk-actions">
        <button
          type="submit"
          className="ob-button po-primary"
          disabled={disabled}
        >
          {titles[action]}
        </button>
        <button
          type="button"
          className="ob-button"
          disabled={blocked}
          onClick={close}
        >
          Закрыть форму
        </button>
      </div>
    </form>
  );
}
function clubLocalTime(instant: string, timeZone: string) {
  const date = new Date(instant);
  if (Number.isNaN(date.valueOf())) return '';
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const field = (type: string) =>
    parts.find((part) => part.type === type)?.value;
  return `${field('year')}-${field('month')}-${field('day')}T${field('hour')}:${field('minute')}`;
}
