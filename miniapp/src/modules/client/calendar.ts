export interface Hours {
  open: string | null;
  close: string | null;
  nextDay: boolean;
  startNextDay?: boolean;
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
