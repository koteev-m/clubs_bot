import {
  calendarBody,
  calendarDraft,
  calendarPlan,
  calendarSnapshotKey,
  calendarValidation,
  reapplyChanges,
  type CalendarView,
  type Rules,
  validCalendarView,
} from './calendar';

type Request = ReturnType<typeof calendarBody>;
const immutable = <T>(value: T): T => {
  const cloned: T = JSON.parse(JSON.stringify(value));
  const freeze = (entry: unknown) => {
    if (entry && typeof entry === 'object') {
      Object.values(entry).forEach(freeze);
      Object.freeze(entry);
    }
  };
  freeze(cloned);
  return cloned;
};
type Changes = Parameters<typeof reapplyChanges>[1];

/** Reconstruct the effective rules of the immutable wire request, not a rendered draft. */
function requestRules(source: CalendarView, body: Request): Rules {
  const changes: Changes = { weekly: [], holidays: [], exceptions: [] };
  for (const field of ['weekly', 'holidays', 'exceptions'] as const) {
    const rows = body[field];
    if (!Array.isArray(rows)) throw new Error('Invalid calendar intent');
    for (const entry of rows) {
      const row = entry as Record<string, unknown>;
      const key = field === 'weekly' ? 'day' : 'date';
      const id = row[key];
      if (
        field === 'weekly'
          ? !Number.isInteger(id) || Number(id) < 1 || Number(id) > 7
          : typeof id !== 'string'
      )
        throw new Error('Invalid calendar intent');
      const mode = row.mode;
      if (
        mode === 'open' &&
        (typeof row.open !== 'string' ||
          typeof row.close !== 'string' ||
          typeof row.nextDay !== 'boolean' ||
          (row.startNextDay !== undefined &&
            typeof row.startNextDay !== 'boolean'))
      )
        throw new Error('Invalid calendar intent');
      if (!['open', 'closed', 'remove'].includes(String(mode)))
        throw new Error('Invalid calendar intent');
      const rule =
        mode === 'remove'
          ? null
          : mode === 'closed'
            ? {
                mode: 'closed' as const,
                open: null,
                close: null,
                nextDay: false,
              }
            : {
                mode: 'open' as const,
                open: row.open as string,
                close: row.close as string,
                nextDay: row.nextDay as boolean,
                startNextDay: row.startNextDay === true,
              };
      if (field === 'weekly')
        changes.weekly.push({
          id: id as number,
          rule: rule ? { ...rule, day: id as number } : null,
        });
      else
        changes[field].push({
          id: id as string,
          rule: rule ? { ...rule, date: id as string } : null,
        });
    }
  }
  if (calendarValidation(changes)) throw new Error('Invalid calendar intent');
  const rules = reapplyChanges(calendarDraft(source), changes);
  if (!validCalendarView({ ...source, ...rules }, source.clubId))
    throw new Error('Invalid calendar intent');
  return rules;
}
function inspect(source: CalendarView, body: Request, today: string) {
  if (
    !source.canWrite ||
    body.expectedVersion !== source.version ||
    body.timezone !== source.timezone ||
    body.reconciliationDate !== today ||
    source.reconciliation?.businessDate !== today
  )
    throw new Error(
      'Calendar intent is incompatible with current configuration',
    );
  const rules = requestRules(source, body);
  const plan = calendarPlan(rules, source.timezone, today, source);
  if (!plan.complete || plan.gaps.length || plan.invalidDates.length)
    throw new Error('Calendar intent is unsupported');
  const identity = JSON.stringify([
    calendarSnapshotKey(source),
    body,
    plan.identity,
  ]);
  return { identity, plan, rules };
}
export function calendarCommandIntent(
  source: CalendarView,
  body: Request,
  approvalKey: string,
) {
  const snapshot = immutable(source);
  const request = immutable(body);
  const checked = inspect(snapshot, request, request.reconciliationDate ?? '');
  return {
    source: snapshot,
    body: request,
    approvalKey,
    identity: checked.identity,
  };
}
export type CalendarCommandIntent = ReturnType<typeof calendarCommandIntent>;

/** Synchronous dispatch decision. No React state/previous render participates. */
export function calendarCommandRetry(
  intent: CalendarCommandIntent,
  current: CalendarView,
  today: string,
  approvedPlan: string,
) {
  try {
    const checked = inspect(current, intent.body, today);
    if (checked.identity !== intent.identity) return 'incompatible' as const;
    if (checked.plan.overlaps.length && approvedPlan !== intent.approvalKey)
      return 'consent_required' as const;
    return 'allowed' as const;
  } catch {
    return 'incompatible' as const;
  }
}
