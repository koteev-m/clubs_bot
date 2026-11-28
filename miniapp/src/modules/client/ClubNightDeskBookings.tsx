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
  hallName?: string;
  deposit: string;
  depositEdited?: boolean;
  preferredInstant: string;
  arrivalEdited?: boolean;
  note: string;
  reason: string;
  cancelChoice: string;
  destination: string;
};
export function newBookingDraft(
  action: BookingAction,
  night: Night,
  table: Table,
  booking: Booking | undefined,
  timezone: string,
  hallName?: string,
): BookingDraft {
  const instant =
    action === 'extend_wait' ? booking?.waitUntil : booking?.arrivalBy;
  return {
    action,
    night,
    table,
    booking,
    hallName,
    deposit: booking?.bookingDepositMinor != null ? minorToRubles(booking.bookingDepositMinor) :
      action === 'create_booking' ? minorToRubles(String(rublesToMinor(table.minDeposit ?? '0') ?? 0n)) : '',
    guestName: '',
    phone: '',
    guests: booking?.guests.toString() ?? '',
    localTime: instant ? (action === 'extend_wait' ? clubLocalTime(instant, timezone) : clubLocalTime(instant, timezone).slice(11)) : '',
    preferredInstant: instant ?? '',
    note: '',
    reason: '',
    cancelChoice: '',
    destination: action === 'move_booking' ? table.id : '',
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
  search, setSearch, filter, setFilter,
}: {
  view: Desk;
  search: string; setSearch: (value: string) => void;
  filter: string; setFilter: (value: string) => void;
  timezone: string;
  disabled: boolean;
  selected?: string;
  select: (id?: string) => void;
  open: (action: BookingAction, table: Table, booking?: Booking) => void;
  seat: (hall: Hall, table: Table) => void;
}) {
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
      <div className="night-work-booking-controls" hidden={Boolean(selectedBooking)}>
        <label className="ob-field">
          <span className="sr-only">Поиск брони</span>
          <input
            type="search"
            value={search}
            placeholder="Гость / стол"
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <label className="ob-field">
          <span className="sr-only">Статус</span>
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
      {!selectedBooking && shownBookings.length === 0 && <p>По этим условиям брони не найдены.</p>}
      <ul className="night-desk-booking-list" hidden={Boolean(selectedBooking)}>
        {shownBookings.map((booking) => (
          <li key={booking.id}>
            <button
              type="button"
              className="ob-button"
              onClick={() => select(booking.id)}
            >
              <strong>{booking.arrivalBy && formatTime(booking.arrivalBy, timezone) + ' · '}
                {booking.guestName ?? 'Гость без имени'}
                {tables.find((table) => table.id === booking.tableId)?.label && ' · ' + tableName(tables.find((table) => table.id === booking.tableId)!)}
              </strong>
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
    ...(table?.bookingId === booking.id && table.seatedGuests !== undefined ? [['Фактически сели', table.seatedGuests] as [string, number]] : []),
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
    ['Депозит по брони', booking.bookingDepositMinor == null ? 'Нет согласованной суммы' : formatBookingDeposit(booking.bookingDepositMinor)],
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
    <section className="night-desk-form night-work-booking-card" aria-label="Карточка брони">
      <button type="button" className="po-back" onClick={close}>Назад</button>
      <h3>{booking.guestName ?? 'Гость без имени'}{table && ' · ' + tableName(table)}</h3>
      <div className="night-work-booking-actions">
        {canChange && (['move_booking','cancel_booking'] as const).map((action) => (
          <button key={action} type="button"
            className={action === 'cancel_booking' ? 'ob-button ob-danger' : 'ob-button'}
            onClick={() => open(action, table, booking)} disabled={disabled}
            aria-label={titles[action]}>{action === 'move_booking' ? 'Перенести' : titles[action]}</button>
        ))}
        {canSeat && <button type="button" className="ob-button po-primary" onClick={() => seat(hall, table)}
          disabled={disabled}>Посадить по брони</button>}
      </div>
      <dl className="po-details">
        {details.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      <div className="night-desk-actions">
        {canChange &&
          (
            [
              'update_booking',
              'extend_wait',
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
  const moving = action === 'move_booking';
  const asksTime = optionalTime || action === 'extend_wait' || moving;
  const extending = action === 'extend_wait';
  const [date, time] = draft.localTime.split('T');
  const times = extending
    ? time && /^\d{4}-\d{2}-\d{2}$/.test(date) ? wallTimeOptions(date, time, timezone) : []
    : arrivalOptions(night, draft.localTime, timezone);
  const preferred = times.find((candidate) => Date.parse(candidate) === Date.parse(draft.preferredInstant));
  const original = !extending && !draft.arrivalEdited && booking?.arrivalBy;
  const arrival = original || (times.length === 1 ? times[0] : preferred);
  const currentMinimum = rublesToMinor(current?.minDeposit ?? table.minDeposit ?? '0') ?? 0n;
  const savedMinimum = rublesToMinor(booking?.minDeposit ?? '0') ?? 0n;
  const minimum = currentMinimum > savedMinimum ? currentMinimum : savedMinimum;
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
    if (asksTime && (!moving || draft.arrivalEdited) && (draft.localTime || action === 'extend_wait')) {
      const instant = arrival;
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
    if (action === 'create_booking' || (action === 'update_booking' && draft.depositEdited)) {
      const deposit = rublesToMinor(draft.deposit);
      if (deposit === null || (deposit < minimum && String(deposit) !== booking?.bookingDepositMinor)) {
        setValidation('Укажите депозит не ниже ' + formatBookingDeposit(String(minimum)) + '.');
        return;
      }
      body.bookingDepositMinor = String(deposit);
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
      if (draft.destination !== table.id && !moveOptions.some(({ option }) => option.id === draft.destination)) {
        setValidation('Выберите доступный стол.');
        return;
      }
      if (draft.destination !== table.id) body.destinationTableId = draft.destination;
      if (body.arrivalBy && Date.parse(body.arrivalBy) === Date.parse(booking?.arrivalBy ?? '')) delete body.arrivalBy;
      if (!body.destinationTableId && !body.arrivalBy) {
        setValidation('Измените стол или время прибытия.');
        return;
      }
    }
    if (action === 'cancel_booking') {
      if (!draft.cancelChoice || (draft.cancelChoice === 'Другое' && !draft.reason.trim())) {
        setValidation('Укажите причину отмены.');
        return;
      }
      body.reason = draft.cancelChoice === 'Другое' ? draft.reason.trim() : draft.cancelChoice;
    }
    execute(body, table, night);
  }

  const notice = (validation || problem) && <p className="po-notice" role="alert">{validation || problem}</p>;
  return (
    <form
      aria-label={titles[action]}
      className={action === 'create_booking' ? 'night-desk-form night-work-seat-form' : 'night-desk-form'}
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      {action === 'create_booking' ? (
        <div className="night-work-form-heading">
          <h3>{titles[action]}</h3>
          <button type="button" className="ob-button" aria-label="Закрыть форму" disabled={blocked} onClick={close}>
            Закрыть
          </button>
        </div>
      ) : <h3>{titles[action]}</h3>}
      <p>
        Ночь {dateFormat(night.businessDate)} · <strong>{draft.hallName ? draft.hallName + ' · ' : ''}{tableName(table)}</strong>
        {action === 'create_booking' && ' · мест: ' + table.capacity}
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
        {optionalTime && (
          <label className="ob-field">
            Депозит по брони, ₽
            <input inputMode="decimal" value={draft.deposit} onChange={(event) => update({ deposit: event.target.value, depositEdited: true })} />
            <small>Минимум: {formatBookingDeposit(String(minimum))}. Оплата отдельно.</small>
          </label>
        )}
        {asksTime && (
          <label className="ob-field">
            {extending ? 'Ожидать до' : 'Ожидаемый приход'} · время клуба
            <input
              type={extending ? "datetime-local" : "time"}
              value={draft.localTime}
              required={action === 'extend_wait' || (moving && Boolean(booking?.arrivalBy))}
              onChange={(event) =>
                update({ localTime: event.target.value, preferredInstant: '', arrivalEdited: true })
              }
            />
          </label>
        )}
        {asksTime && !extending && draft.localTime && (
          <small className="po-muted">{arrival ? 'Приход: ' + arrivalLabel(arrival, timezone) :
            times.length ? 'Выберите точное время прихода.' : 'Время вне интервала ночи или не существует в часовом поясе клуба.'}</small>
        )}
        {times.length > 1 && !original && (!moving || draft.arrivalEdited) && (
          <label className="ob-field">
            Какое время используется
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
                update({ preferredInstant: event.target.value, arrivalEdited: true })
              }
            >
              <option value="">Выберите смещение</option>
              {times.map((instant) => (
                <option key={instant} value={instant}>
                  {arrivalLabel(instant, timezone)} · UTC{instant.slice(19)}
                </option>
              ))}
            </select>
          </label>
        )}
        {moving && (
          <>
            <label className="ob-field">
              Новый стол
              <select value={draft.destination} onChange={(event) => update({ destination: event.target.value })}>
                <option value={table.id}>{tableName(table)} · текущий</option>
                {moveOptions.map(({ option, label }) => (
                  <option key={option.id} value={option.id}>{label} · свободен</option>
                ))}
              </select>
            </label>
            <p>После переноса: {moveOptions.find(({ option }) => option.id === draft.destination)?.label ?? tableName(table)}
              {' · '}{arrival ? arrivalLabel(arrival, timezone) : 'время не задано'}</p>
          </>
        )}
        {action === 'create_booking' && (
          <details>
            <summary>Телефон и пожелания (по желанию)</summary>
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
          </details>
        )}
        {action === 'cancel_booking' && (
          <>
            <label className="ob-field">
              Причина отмены
              <select value={draft.cancelChoice} required onChange={(event) => update({ cancelChoice: event.target.value })}>
                <option value="">Выберите причину</option>
                {['Гость отменил', 'Не придёт', 'Другое'].map((reason) => <option key={reason}>{reason}</option>)}
              </select>
            </label>
            {draft.cancelChoice === 'Другое' && (
              <label className="ob-field">
                Объяснение
                <textarea value={draft.reason} required maxLength={500}
                  onChange={(event) => update({ reason: event.target.value })} />
              </label>
            )}
          </>
        )}
      </fieldset>
      {action !== 'create_booking' && notice}
      <div className={action === 'create_booking' ? 'night-work-form-submit' : 'night-desk-actions'}>
        {action === 'create_booking' && notice}
        <button
          type="submit"
          className="ob-button po-primary"
          disabled={disabled}
        >
          {titles[action]}
        </button>
        {action !== 'create_booking' && (
          <button
            type="button"
            className="ob-button"
            disabled={blocked}
            onClick={close}
          >
            Закрыть форму
          </button>
        )}
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

function rublesToMinor(value: string) {
  const parts = /^(\d{1,10})(?:[.,](\d{0,2}))?$/.exec(value.trim());
  return parts ? BigInt(parts[1]) * 100n + BigInt((parts[2] ?? '').padEnd(2, '0')) : null;
}

function minorToRubles(value: string) {
  if (!/^\d{1,12}$/.test(value)) return '';
  const amount = BigInt(value);
  return String(amount / 100n) + ',' + String(amount % 100n).padStart(2, '0');
}

function formatBookingDeposit(value: string) {
  if (!/^\d{1,12}$/.test(value)) return 'Сумма не подтверждена';
  const [whole, fraction] = minorToRubles(value).split(',');
  return new Intl.NumberFormat('ru-RU').format(BigInt(whole)) + ',' + fraction + ' ₽';
}

function arrivalLabel(instant: string, timezone: string) {
  const [day, time] = clubLocalTime(instant, timezone).split('T');
  return dateFormat(day) + ', ' + time;
}

/** Resolve against the actual UTC interval. The business date can precede opening. */
export function arrivalOptions(night: Night, time: string, timezone: string) {
  if (!/^\d{2}:\d{2}$/.test(time)) return [];
  const start = Date.parse(night.opensAt);
  const end = Date.parse(night.closesAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return [];
  const first = Date.parse(clubLocalTime(night.opensAt, timezone).slice(0, 10));
  const last = Date.parse(clubLocalTime(night.closesAt, timezone).slice(0, 10));
  const options: string[] = [];
  for (let day = first; day <= last; day += 86400000) {
    const date = new Date(day).toISOString().slice(0, 10);
    options.push(...wallTimeOptions(date, time, timezone).filter((candidate) => {
      const instant = Date.parse(candidate);
      return instant >= start && instant < end;
    }));
  }
  return options;
}
