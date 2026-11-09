import { useEffect, useId, useState } from 'react';
import { Field } from './OnboardingShared';
import {
  dateLabel,
  defaultHours,
  describeNight,
  Hours,
  nextWeekday,
  shiftDate,
  Rule,
  Rules,
} from './calendar';

const weekdays = [
  'Понедельник',
  'Вторник',
  'Среда',
  'Четверг',
  'Пятница',
  'Суббота',
  'Воскресенье',
];

function NightHours({
  hours,
  update,
  businessDate,
  inherited = false,
}: {
  hours: Hours;
  update: (hours: Hours) => void;
  businessDate: string;
  inherited?: boolean;
}) {
  const description =
    hours.open && hours.close && hours.startNextDay !== null
      ? describeNight(businessDate, hours)
      : null;
  return (
    <>
      <Field label="День открытия">
        <select
          aria-label="День открытия"
          value={
            hours.startNextDay === null
              ? 'schedule'
              : hours.startNextDay
                ? 'next'
                : 'same'
          }
          onChange={(event) =>
            update({
              ...hours,
              startNextDay:
                event.target.value === 'schedule'
                  ? null
                  : event.target.value === 'next',
            })
          }
        >
          {(inherited || hours.startNextDay === null) && (
            <option value="schedule">По расписанию</option>
          )}
          <option value="same">
            День вечеринки · {weekdayName(businessDate)},{' '}
            {dateLabel(businessDate)}
          </option>
          <option value="next">
            Следующий день · {weekdayName(shiftDate(businessDate, 1))},{' '}
            {dateLabel(shiftDate(businessDate, 1))}
          </option>
        </select>
      </Field>
      <div className="cc-times">
        <Field label="Время открытия">
          <input
            type="time"
            step="1"
            value={hours.open ?? ''}
            onChange={(event) => update({ ...hours, open: event.target.value })}
          />
        </Field>
        <Field label="Время закрытия">
          <input
            type="time"
            step="1"
            value={hours.close ?? ''}
            onChange={(event) =>
              update({ ...hours, close: event.target.value })
            }
          />
        </Field>
      </div>
      <Field label="День закрытия">
        <select
          aria-label="День закрытия"
          value={hours.nextDay ? 'next' : 'same'}
          onChange={(event) =>
            update({ ...hours, nextDay: event.target.value === 'next' })
          }
        >
          <option value="same">
            {weekdayName(shiftDate(businessDate, hours.startNextDay ? 1 : 0))},{' '}
            {dateLabel(shiftDate(businessDate, hours.startNextDay ? 1 : 0))} · в
            день открытия
          </option>
          <option value="next">
            {weekdayName(shiftDate(businessDate, hours.startNextDay ? 2 : 1))},{' '}
            {dateLabel(shiftDate(businessDate, hours.startNextDay ? 2 : 1))} ·
            после дня открытия
          </option>
        </select>
      </Field>
      <div aria-live="polite" className="po-notice">
        {description ? (
          <>
            <p>{description.text}</p>
            <p>{description.dates}</p>
          </>
        ) : (
          <p>
            {hours.open && hours.close && hours.startNextDay === null
              ? 'Для собственных часов выберите день открытия.'
              : inherited
                ? 'Если часы в этом правиле не заданы, сервер берёт их из расписания на эту дату, если оно есть. Действующий интервал показан в предпросмотре.'
                : 'Укажите время открытия и закрытия для расчёта интервала.'}
          </p>
        )}
      </div>
    </>
  );
}

const closed: Rule = {
  mode: 'closed',
  open: null,
  close: null,
  nextDay: false,
};
const open: Rule = { mode: 'open', ...defaultHours };
const weekdayName = (date: string) =>
  new Intl.DateTimeFormat('ru-RU', { weekday: 'long', timeZone: 'UTC' }).format(
    new Date(date + 'T00:00:00Z'),
  );
type DatedRule = Rules['holidays'][number];

function replaceDate(
  rows: DatedRule[],
  date: string,
  rule: Rule | null,
): DatedRule[] {
  const next = rows.filter((item) => item.date !== date);
  if (rule) next.push({ ...rule, date });
  return next.sort((a, b) => a.date.localeCompare(b.date));
}

function DatedRules({
  holiday = false,
  rows,
  update,
  disabled,
  readOnly,
  selectedDate,
}: {
  holiday?: boolean;
  selectedDate?: string;
  rows: DatedRule[];
  update: (rows: DatedRule[]) => void;
  disabled: boolean;
  readOnly: boolean;
}) {
  const [date, setDate] = useState(selectedDate ?? '');
  useEffect(() => {
    if (selectedDate) setDate(selectedDate);
  }, [selectedDate]);
  const heading = useId();
  const canAdd =
    /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(date) &&
    date >= '0001-01-01' &&
    date <= '9998-12-31' &&
    !rows.some((row) => row.date === date);
  const add = (rule: Rule) => {
    if (!canAdd) return;
    update(replaceDate(rows, date, rule));
    setDate('');
  };
  return (
    <section className="po-card ob-card cc-rules" aria-labelledby={heading}>
      <h2 id={heading}>
        {holiday
          ? 'Праздничные исключения'
          : 'Добавить, изменить или отменить ночь'}
      </h2>
      <p className="po-muted">
        {holiday
          ? 'Укажите праздничные ночи или дни, когда клуб закрыт. Изменение на ту же дату имеет приоритет.'
          : 'Добавьте дополнительную ночь, измените часы или отмените ночь. Выберите дату вечеринки, даже если клуб откроется после полуночи на следующий день.'}
      </p>
      {!readOnly && (
        <fieldset className="cc-date-add" disabled={disabled}>
          <Field label={holiday ? 'Дата праздника' : 'Дата вечеринки'}>
            <input
              type="date"
              min="0001-01-01"
              max="9998-12-31"
              value={date}
              onChange={(event) => setDate(event.target.value)}
            />
          </Field>
          {date && rows.some((row) => row.date === date) && (
            <p>Правило для этой даты уже есть. Его можно изменить ниже.</p>
          )}
          <div className="cc-actions">
            <button
              className="ob-button"
              type="button"
              disabled={!canAdd}
              onClick={() => add(open)}
            >
              {holiday
                ? 'Добавить праздничную ночь'
                : 'Добавить ночь или изменить часы'}
            </button>
            <button
              className="ob-button"
              type="button"
              disabled={!canAdd}
              onClick={() => add(closed)}
            >
              {holiday ? 'Закрыть на праздник' : 'Отменить ночь'}
            </button>
          </div>
        </fieldset>
      )}
      {rows.length === 0 && (
        <p className="po-muted">Нет сохранённых правил в этом списке.</p>
      )}
      {rows.map((row) => (
        <fieldset className="cc-rule" key={row.date} disabled={disabled}>
          <legend>
            <time dateTime={row.date}>
              {row.date.split('-').reverse().join('.')}
            </time>
          </legend>
          <Field label="Режим">
            <select
              value={row.mode}
              onChange={(event) =>
                update(
                  replaceDate(
                    rows,
                    row.date,
                    event.target.value === 'open' ? open : closed,
                  ),
                )
              }
            >
              <option value="open">Клуб открыт</option>
              <option value="closed">Клуб закрыт</option>
            </select>
          </Field>
          {row.mode === 'open' && (
            <NightHours
              businessDate={row.date}
              hours={row}
              inherited={row.open === null || row.close === null}
              update={(hours) =>
                update(replaceDate(rows, row.date, { ...row, ...hours }))
              }
            />
          )}
          {!readOnly && (
            <button
              className="ob-button"
              type="button"
              onClick={() => update(replaceDate(rows, row.date, null))}
            >
              Убрать правило на эту дату
            </button>
          )}
        </fieldset>
      ))}
    </section>
  );
}

export function CalendarRulesEditor({
  value,
  onChange,
  disabled,
  readOnly,
  referenceDate,
  section = 'all',
  selectedDate,
}: {
  value: Rules;
  onChange: (rules: Rules) => void;
  disabled: boolean;
  readOnly: boolean;
  referenceDate: string;
  section?: 'weekly' | 'special' | 'all';
  selectedDate?: string;
}) {
  const updateDay = (day: number, rule: Rule | null) => {
    const weekly = value.weekly.filter((row) => row.day !== day);
    if (rule) weekly.push({ ...rule, day });
    onChange({ ...value, weekly });
  };
  return (
    <div className="cc-rules">
      {section !== 'special' && (
        <section className="po-card ob-card cc-rules">
          <h2>Постоянное расписание</h2>
          <p className="po-muted">
            Выберите день, к которому относится вечеринка. Например, вечеринка
            пятницы может открыться в субботу в 00:00 и закончиться в субботу в
            12:00. Даты ниже — ближайший пример каждого недельного правила.
            Исключения, праздники и перевод часов учитываются в расчёте после
            сохранения.
          </p>
          {weekdays.map((name, index) => {
            const day = index + 1;
            const row = value.weekly.find((rule) => rule.day === day);
            return (
              <fieldset className="cc-rule" key={day} disabled={disabled}>
                <legend>{name}</legend>
                <label className="ob-check">
                  <input
                    type="checkbox"
                    checked={Boolean(row)}
                    onChange={(event) =>
                      updateDay(day, event.target.checked ? open : null)
                    }
                  />
                  <span>Проводим вечеринку</span>
                </label>
                {row && (
                  <NightHours
                    businessDate={nextWeekday(referenceDate, day)}
                    hours={row}
                    update={(hours) => updateDay(day, { ...row, ...hours })}
                  />
                )}
              </fieldset>
            );
          })}
        </section>
      )}
      {section !== 'weekly' && (
        <>
          <DatedRules
            selectedDate={selectedDate}
            rows={value.exceptions}
            update={(exceptions) => onChange({ ...value, exceptions })}
            disabled={disabled}
            readOnly={readOnly}
          />
          <DatedRules
            holiday
            rows={value.holidays}
            update={(holidays) => onChange({ ...value, holidays })}
            disabled={disabled}
            readOnly={readOnly}
          />
        </>
      )}
    </div>
  );
}
