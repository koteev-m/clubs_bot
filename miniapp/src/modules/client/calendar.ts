export interface Hours {
  open: string | null;
  close: string | null;
  nextDay: boolean;
  startNextDay?: boolean | null;
}

export type Rule = Hours & { mode: 'open' | 'closed' };
type DayRule = Rule & { day: number };
type DateRule = Rule & { date: string };
export interface Rules {
  weekly: DayRule[];
  holidays: DateRule[];
  exceptions: DateRule[];
}

export interface CalendarView extends Rules {
  clubId: string;
  name: string;
  timezone: string;
  version: string;
  canWrite: boolean;
  nights: CalendarNight[];
  cancelledNights?: CalendarNight[];
  invalidDates: string[];
  // Full finite stored-date selection, unlike the eight-night read preview.
  reconciliation?: { businessDate: string; storedDates: string[] };
}

export type CalendarNight = {
  businessDate: string;
  opensAt: string;
  closesAt: string;
  source: 'WEEKEND_RULE' | 'HOLIDAY' | 'EXCEPTION';
  nightId?: string | null;
  timezone?: string;
};

export const defaultHours: Hours = {
  open: '22:00',
  close: '06:00',
  nextDay: true,
};

// Legacy dated DTOs report false for an inherited opening day when no opening time is set.
export function calendarDraft(view: Rules): Rules {
  const inherited = (row: DateRule): DateRule => ({
    ...row,
    startNextDay:
      row.open === null && !row.startNextDay ? null : row.startNextDay,
  });
  return {
    weekly: view.weekly,
    holidays: view.holidays.map(inherited),
    exceptions: view.exceptions.map(inherited),
  };
}

type Delta<K, T extends Rule> = { id: K; rule: T | null };

function delta<K, T extends Rule>(
  original: T[],
  draft: T[],
  keyOf: (rule: T) => K,
): Delta<K, T>[] {
  const before = new Map(original.map((row) => [keyOf(row), row]));
  const after = new Map(draft.map((row) => [keyOf(row), row]));
  const changes: Delta<K, T>[] = [];
  for (const id of new Set([...before.keys(), ...after.keys()])) {
    const oldRule = before.get(id);
    const rule = after.get(id);
    if (
      oldRule?.mode === rule?.mode &&
      oldRule?.open === rule?.open &&
      oldRule?.close === rule?.close &&
      oldRule?.nextDay === rule?.nextDay &&
      Boolean(oldRule?.startNextDay) === Boolean(rule?.startNextDay)
    )
      continue;
    changes.push({ id, rule: rule ?? null });
  }
  return changes;
}

const dayOf = (row: DayRule) => row.day;
const dateOf = (row: DateRule) => row.date;

export function calendarChanges(original: Rules, draft: Rules) {
  return {
    weekly: delta(original.weekly, draft.weekly, dayOf),
    holidays: delta(original.holidays, draft.holidays, dateOf),
    exceptions: delta(original.exceptions, draft.exceptions, dateOf),
  };
}

type Changes = ReturnType<typeof calendarChanges>;

export function hasChanges(changes: Changes) {
  return (
    changes.weekly.length +
      changes.holidays.length +
      changes.exceptions.length >
    0
  );
}

function applyChanges<K, T extends Rule>(
  original: T[],
  changes: Delta<K, T>[],
  keyOf: (rule: T) => K,
) {
  const rows = new Map(original.map((row) => [keyOf(row), row]));
  for (const { id, rule } of changes) {
    if (rule) rows.set(id, rule);
    else rows.delete(id);
  }
  return Array.from(rows.values());
}

export function reapplyChanges(base: Rules, changes: Changes): Rules {
  return {
    weekly: applyChanges(base.weekly, changes.weekly, dayOf),
    holidays: applyChanges(base.holidays, changes.holidays, dateOf).sort(
      (a, b) => a.date.localeCompare(b.date),
    ),
    exceptions: applyChanges(base.exceptions, changes.exceptions, dateOf).sort(
      (a, b) => a.date.localeCompare(b.date),
    ),
  };
}

function payload<K>(
  changes: { id: K; rule: Rule | null }[],
  field: 'day' | 'date',
) {
  return changes.map(({ id, rule }) => ({
    [field]: id,
    ...(rule?.mode === 'open'
      ? {
          mode: 'open',
          open: rule.open,
          close: rule.close,
          nextDay: rule.nextDay,
          ...(rule.startNextDay ? { startNextDay: true } : {}),
        }
      : { mode: rule?.mode ?? 'remove' }),
  }));
}

export function calendarBody(
  base: CalendarView,
  changes: Changes,
  reconcile = false,
) {
  return {
    expectedVersion: base.version,
    ...(reconcile ? { reconcile: true } : {}),
    timezone: base.timezone,
    ...(base.reconciliation
      ? { reconciliationDate: base.reconciliation.businessDate }
      : {}),
    weekly: payload(changes.weekly, 'day'),
    holidays: payload(changes.holidays, 'date'),
    exceptions: payload(changes.exceptions, 'date'),
  };
}

function wallSeconds(time: string) {
  const [hour, minute, second = '0'] = time.split(':');
  return Number(hour) * 3600 + Number(minute) * 60 + Number(second);
}

export function calendarValidation(
  changes: ReturnType<typeof calendarChanges>,
): string | null {
  const rows = [...changes.weekly, ...changes.holidays, ...changes.exceptions];
  if (rows.length > 366) return 'Сохраните не более 366 изменений за один раз.';
  for (const { rule } of rows) {
    if (rule?.mode !== 'open') continue;
    if (!rule.open || !rule.close)
      return 'Укажите время начала и окончания каждой открытой ночи.';
    if (rule.startNextDay === null)
      return 'Выберите день открытия для правила с собственными часами.';
    if (!rule.nextDay && wallSeconds(rule.open) >= wallSeconds(rule.close))
      return 'Если окончание не позже начала, выберите окончание на следующий день после открытия или измените время.';
  }
  return null;
}

export function dateLabel(date: string) {
  return date.split('-').reverse().join('.');
}

const weekdays = new Intl.DateTimeFormat('ru-RU', {
  weekday: 'long',
  timeZone: 'UTC',
});

export function weekday(date: string) {
  return weekdays.format(new Date(date + 'T00:00:00Z'));
}

const partyDays = [
  'воскресенья',
  'понедельника',
  'вторника',
  'среды',
  'четверга',
  'пятницы',
  'субботы',
];

/** Calendar arithmetic on wall dates only. DST instants remain server-owned. */
export function shiftDate(date: string, days: number) {
  const value = new Date(date + 'T00:00:00Z');
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

export function clubToday(timezone: string) {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  const part = (type: string) =>
    parts.find((item) => item.type === type)!.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}

export function nextWeekday(date: string, day: number) {
  const current = new Date(date + 'T00:00:00Z').getUTCDay() || 7;
  return shiftDate(date, (day - current + 7) % 7);
}

export function describeNight(businessDate: string, hours: Hours) {
  const time = (value: string | null) =>
    value?.match(/^\d{2}:\d{2}:00$/) ? value.slice(0, 5) : value || '—';
  const opening = shiftDate(businessDate, hours.startNextDay ? 1 : 0);
  const closing = shiftDate(opening, hours.nextDay ? 1 : 0);
  const party = partyDays[new Date(businessDate + 'T00:00:00Z').getUTCDay()];
  return {
    text: `Вечеринка ${party}. Открытие: ${weekday(opening)}, ${time(hours.open)}. Закрытие: ${weekday(closing)}, ${time(hours.close)}.`,
    dates: `Дата вечеринки: ${dateLabel(businessDate)}. Открытие: ${dateLabel(opening)}. Закрытие: ${dateLabel(closing)}.`,
  };
}

/** Render the wall time and offset returned by the server; the device's zone is irrelevant. */
export function localLabel(iso: string) {
  const [date, timeWithOffset] = iso.split('T');
  return (
    weekday(date) +
    ', ' +
    dateLabel(date) +
    ' ' +
    timeWithOffset.replace(/(Z|[+-].+)$/, ' UTC$1').replace('UTCZ', 'UTC+00:00')
  );
}

export function nightTitle(date: string) {
  return 'Ночь ' + partyDays[new Date(date + 'T00:00:00Z').getUTCDay()];
}

/** Offset candidates come from IANA data in Intl, never from the device zone. */
function wallTimeAnalysis(date: string, time: string, timezone: string) {
  const wall = Date.parse(date + 'T' + time + 'Z');
  const format = new Intl.DateTimeFormat('en-GB', {
    timeZone: timezone,
    calendar: 'gregory',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
    timeZoneName: 'longOffset',
  });
  const parts = (instant: number) => {
    const values = format.formatToParts(new Date(instant));
    const field = (type: string) =>
      values.find((part) => part.type === type)!.value;
    return {
      date: `${field('year').padStart(4, '0')}-${field('month')}-${field('day')}`,
      time: `${field('hour')}:${field('minute')}:${field('second')}`,
      zone: field('timeZoneName'),
    };
  };
  const offset = (zone: string) => {
    if (zone === 'GMT') return 0;
    const match = /^GMT([+-])(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(zone);
    if (!match) throw new Error('Timezone offset unavailable');
    return (
      (match[1] === '+' ? 1 : -1) *
      (Number(match[2]) * 3600 + Number(match[3]) * 60 + Number(match[4] ?? 0))
    );
  };
  const offsets = new Set<number>();
  for (let hours = -48; hours <= 48; hours += 12)
    offsets.add(offset(parts(wall + hours * 3600000).zone));
  const expected = time.length === 5 ? time + ':00' : time;
  const candidates = [...offsets].map((seconds) => wall - seconds * 1000);
  const options = candidates
    .map((instant) => ({ instant }))
    .filter(({ instant }) => {
      const local = parts(instant);
      return local.date === date && local.time === expected;
    })
    .sort((a, b) => a.instant - b.instant)
    .map(({ instant }) => {
      const local = parts(instant);
      return `${local.date}T${local.time}${local.zone === 'GMT' ? '+00:00' : local.zone.slice(3)}`;
    });
  return {
    options,
    earliest: Math.min(...candidates),
    latest: Math.max(...candidates),
  };
}

export function wallTimeOptions(date: string, time: string, timezone: string) {
  return wallTimeAnalysis(date, time, timezone).options;
}

const calendarLookbackDays = 3;
const calendarHorizonDays = 56;

const normalizedRules = (rules: Rules) => {
  const values = (row: Rule) => [
    row.mode,
    row.open,
    row.close,
    row.nextDay,
    row.startNextDay ?? null,
  ];
  return [
    [...rules.weekly]
      .sort((a, b) => a.day - b.day)
      .map((row) => [row.day, ...values(row)]),
    ...[rules.holidays, rules.exceptions].map((rows) =>
      [...rows]
        .sort((a, b) => a.date.localeCompare(b.date))
        .map((row) => [row.date, ...values(row)]),
    ),
  ];
};

/** Exact source snapshot identity, independent of response/array object identity. */
export function calendarSnapshotKey(view: CalendarView) {
  return JSON.stringify([
    view.clubId,
    view.version,
    view.timezone,
    normalizedRules(calendarDraft(view)),
    view.reconciliation?.businessDate,
    view.reconciliation && [...view.reconciliation.storedDates].sort(),
  ]);
}

/** Same finite candidates as ClubOperationalNights.reconcile, not its eight-row preview. */
export function calendarAffectedDates(
  previous: CalendarView,
  rules: Rules,
  today: string,
) {
  if (
    !previous.reconciliation ||
    previous.reconciliation.businessDate !== today
  )
    throw new Error('Calendar reconciliation snapshot needs refresh');
  const first = shiftDate(today, -calendarLookbackDays);
  const changes = calendarChanges(calendarDraft(previous), rules);
  return [
    ...new Set([
      ...Array.from(
        { length: calendarHorizonDays + calendarLookbackDays },
        (_, day) => shiftDate(first, day),
      ),
      ...[
        ...previous.holidays,
        ...previous.exceptions,
        ...rules.holidays,
        ...rules.exceptions,
      ]
        .map(({ date }) => date)
        .filter((date) => date >= first),
      ...[...changes.holidays, ...changes.exceptions].map(({ id }) => id),
      ...previous.reconciliation.storedDates.filter((date) => date >= first),
    ]),
  ].sort();
}

/** Local DST decisions; stored dependencies/frozen intervals remain server-owned. */
export function calendarPlan(
  rules: Rules,
  timezone: string,
  today: string,
  previous?: CalendarView,
) {
  const first = shiftDate(today, -calendarLookbackDays);
  const dates = previous
    ? calendarAffectedDates(previous, rules, today)
    : [
        ...new Set([
          ...Array.from(
            { length: calendarHorizonDays + calendarLookbackDays },
            (_, day) => shiftDate(first, day),
          ),
          ...[...rules.holidays, ...rules.exceptions]
            .map(({ date }) => date)
            .filter((date) => date >= first),
        ]),
      ].sort();
  const changes = previous && calendarChanges(calendarDraft(previous), rules);
  const touched = new Set(
    changes && [...changes.holidays, ...changes.exceptions].map(({ id }) => id),
  );
  const dayStartBound = wallTimeAnalysis(today, '00:00', timezone).earliest;
  const nights: CalendarNight[] = [];
  const gaps: string[] = [];
  const overlaps: string[] = [];
  const invalidDates: string[] = [];
  for (const date of dates) {
    const day = new Date(date + 'T00:00:00Z').getUTCDay() || 7;
    const weekly = rules.weekly.find((rule) => rule.day === day);
    const window = weekly?.mode === 'open' ? weekly : undefined;
    let source: CalendarNight['source'] = 'WEEKEND_RULE';
    const override = (row: Rule | undefined, fallback: Hours | undefined) => {
      if (!row) return fallback;
      if (row.mode === 'closed') return undefined;
      const inherited = row.open === null && row.close === null;
      const partial = row.open === null || row.close === null;
      const open = row.open ?? fallback?.open;
      const close = row.close ?? fallback?.close;
      if (!open || !close) return undefined;
      return {
        open,
        close,
        // V067 guarantees NULL close_next_day for missing dated hours.
        // The resolver inherits the day only when BOTH hours are inherited;
        // a partial override infers the day from the effective wall times.
        nextDay: inherited
          ? (fallback?.nextDay ?? close < open)
          : partial
            ? close < open
            : row.nextDay,
        startNextDay: row.startNextDay ?? fallback?.startNextDay ?? false,
      };
    };
    const holiday = rules.holidays.find((rule) => rule.date === date);
    if (holiday) source = 'HOLIDAY';
    let effective = override(holiday, window);
    const exception = rules.exceptions.find((rule) => rule.date === date);
    if (exception) {
      source = 'EXCEPTION';
      effective = override(exception, effective ?? window);
    }
    if (!effective?.open || !effective.close) continue;
    const openingDate = shiftDate(date, effective.startNextDay ? 1 : 0);
    const closingDate = shiftDate(openingDate, effective.nextDay ? 1 : 0);
    const closing = wallTimeAnalysis(closingDate, effective.close, timezone);
    // Prove expiry in instant space, even for a full skipped wall date (Apia).
    // Invalid wall times are never silently shifted or used as an allowed interval.
    if (date < today && !touched.has(date) && closing.latest < dayStartBound)
      continue;
    const starts = wallTimeOptions(openingDate, effective.open, timezone);
    const ends = closing.options;
    if (!starts.length)
      gaps.push(`${dateLabel(openingDate)} ${effective.open}`);
    if (!ends.length) gaps.push(`${dateLabel(closingDate)} ${effective.close}`);
    if (starts.length > 1)
      overlaps.push(`${dateLabel(openingDate)} ${effective.open}`);
    if (ends.length > 1)
      overlaps.push(`${dateLabel(closingDate)} ${effective.close}`);
    if (!starts.length || !ends.length) continue;
    if (Date.parse(starts[0]) >= Date.parse(ends[0])) {
      invalidDates.push(date);
      continue;
    }
    nights.push({
      businessDate: date,
      opensAt: starts[0],
      closesAt: ends[0],
      source,
    });
  }
  return {
    // Equality compares all inputs and exact decisions; no lossy hash or persisted consent.
    identity: JSON.stringify([
      previous && calendarSnapshotKey(previous),
      normalizedRules(rules),
      timezone,
      today,
      dates,
      nights,
      gaps,
      overlaps,
      invalidDates,
    ]),
    complete: Boolean(previous?.reconciliation),
    affectedDates: dates,
    nights,
    gaps: [...new Set(gaps)],
    overlaps: [...new Set(overlaps)],
    invalidDates,
  };
}

export function calendarChangeDescriptions(changes: Changes, today: string) {
  const describe = (label: string, date: string, rule: Rule | null) => {
    if (!rule)
      return (
        label +
        ': отдельное правило будет удалено. Итог зависит от оставшегося расписания и особых дат.'
      );
    if (rule.mode === 'closed') return label + ': ночь отменена.';
    return label + ': ' + describeNight(date, rule).text;
  };
  return [
    ...changes.weekly.map(({ id, rule }) => {
      const date = nextWeekday(today, id);
      return describe(nightTitle(date), date, rule);
    }),
    ...changes.holidays.map(({ id, rule }) =>
      describe('Праздник ' + dateLabel(id), id, rule),
    ),
    ...changes.exceptions.map(({ id, rule }) =>
      describe('Особая дата ' + dateLabel(id), id, rule),
    ),
  ];
}

export function validCalendarView(view: CalendarView, clubId: string) {
  const dateValid = (date: string) =>
    typeof date === 'string' &&
    /^\d{4}-\d{2}-\d{2}$/.test(date) &&
    Number.isFinite(Date.parse(date + 'T00:00:00Z')) &&
    new Date(date + 'T00:00:00Z').toISOString().slice(0, 10) === date;
  const instantValid = (instant: string) =>
    typeof instant === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(instant) &&
    Number.isFinite(Date.parse(instant));
  if (
    !view ||
    view.clubId !== clubId ||
    typeof view.name !== 'string' ||
    typeof view.version !== 'string' ||
    !/^[0-9]+$/.test(view.version) ||
    typeof view.canWrite !== 'boolean' ||
    typeof view.timezone !== 'string'
  )
    return false;
  if (
    view.reconciliation !== undefined &&
    (!view.reconciliation ||
      !dateValid(view.reconciliation.businessDate) ||
      !Array.isArray(view.reconciliation.storedDates) ||
      !view.reconciliation.storedDates.every(dateValid) ||
      new Set(view.reconciliation.storedDates).size !==
        view.reconciliation.storedDates.length)
  )
    return false;
  const rule = (row: Rule) =>
    row &&
    ['open', 'closed'].includes(row.mode) &&
    (row.open === null || typeof row.open === 'string') &&
    (row.close === null || typeof row.close === 'string') &&
    typeof row.nextDay === 'boolean';
  if (
    !Array.isArray(view.weekly) ||
    !view.weekly.every(
      (row) =>
        rule(row) && Number.isInteger(row.day) && row.day >= 1 && row.day <= 7,
    )
  )
    return false;
  for (const rows of [view.holidays, view.exceptions])
    if (
      !Array.isArray(rows) ||
      !rows.every((row) => rule(row) && dateValid(row.date))
    )
      return false;
  const night = (row: CalendarNight) =>
    row &&
    dateValid(row.businessDate) &&
    instantValid(row.opensAt) &&
    instantValid(row.closesAt) &&
    ['WEEKEND_RULE', 'HOLIDAY', 'EXCEPTION'].includes(row.source);
  if (
    !Array.isArray(view.nights) ||
    !view.nights.every(night) ||
    (view.cancelledNights !== undefined &&
      (!Array.isArray(view.cancelledNights) ||
        !view.cancelledNights.every(night))) ||
    !Array.isArray(view.invalidDates) ||
    !view.invalidDates.every(dateValid)
  )
    return false;
  try {
    new Intl.DateTimeFormat('en', { timeZone: view.timezone }).format();
    return true;
  } catch {
    return false;
  }
}
