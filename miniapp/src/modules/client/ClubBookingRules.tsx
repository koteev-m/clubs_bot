import { useEffect, useRef, useState } from 'react';
import { dateLabel, localLabel } from './calendar';
import { authorityLost, OnboardingError } from './onboarding.api';
import { useCommand, useRemote } from './onboarding.hooks';
import {
  CommandState,
  Field,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from './OnboardingShared';

const fields = [
  {
    id: 'cutoffMinutes',
    label: 'Приём онлайн-броней, мин до закрытия',
    hint: 'За сколько минут до закрытия клуба перестать принимать новые брони онлайн.',
    min: 0,
    required: true,
  },
  {
    id: 'holdMinutes',
    label: 'Время на оформление брони, мин',
    hint: 'Столько времени есть у гостя с момента выбора стола, чтобы подтвердить бронь. Рекомендуем 10 минут. Пустое поле — срок не задан.',
    min: 1,
    required: false,
  },
  {
    id: 'retentionMinutes',
    label: 'Ожидание при опоздании, мин',
    hint: 'Сколько держать стол после времени приезда, которое гость указал в подтверждённой брони.',
    min: 0,
    required: true,
  },
] as const;
const maxMinutes = 1440;
type RuleField = (typeof fields)[number]['id'];
type RuleValues = Record<RuleField, number | null>;
type Edits = Partial<Record<RuleField, string>>;
type Draft = { version: string; edits: Edits };
type Night = {
  businessDate: string;
  opensAt: string;
  closesAt: string;
  cutoffAt: string;
  cutoffAtOrBeforeOpen: boolean;
};
export type BookingRulesView = {
  clubId: string;
  name: string;
  timezone: string;
  version: string;
  canWrite: boolean;
  rules: RuleValues;
  nights: Night[];
  invalidDates: string[];
};

function nightExamples(night: Night, rules: RuleValues, timezone: string) {
  const opens = Date.parse(night.opensAt);
  const closes = Date.parse(night.closesAt);
  const minute = 60_000;
  if (rules.cutoffMinutes === null || rules.retentionMinutes === null) return;
  const cutoff = closes - rules.cutoffMinutes * minute;
  // In this example the guest picks a table before both opening and the booking cutoff.
  const selectBeforeOpening = opens <= cutoff;
  const selectionBase = selectBeforeOpening ? night.opensAt : night.closesAt;
  const selectionMinutes =
    -60 - (selectBeforeOpening ? 0 : rules.cutoffMinutes);
  try {
    const formatter = new Intl.DateTimeFormat('ru-RU', {
      timeZone: timezone,
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      second: [night.opensAt, night.closesAt].some((iso) =>
        /T\d{2}:\d{2}:\d{2}/.test(iso),
      )
        ? '2-digit'
        : undefined,
      timeZoneName: 'shortOffset',
    });
    const at = (origin: string, minutes = 0) => {
      const instant = Date.parse(origin) + minutes * minute;
      // All rules add whole minutes, so the original fractional second survives
      // intact even when its precision exceeds JavaScript Date's milliseconds.
      const fraction = origin.match(/\.(\d+)/)?.[1];
      return formatter
        .formatToParts(instant)
        .map(({ type, value }) =>
          type === 'second' && fraction ? value + ',' + fraction : value,
        )
        .join('')
        .replace('GMT', 'UTC');
    };
    return {
      cutoffMinutes: `Закрытие — ${at(night.closesAt)}. Приём броней закончится ${at(night.closesAt, -rules.cutoffMinutes)}.`,
      holdMinutes:
        rules.holdMinutes === null
          ? undefined
          : `Если выбрать стол ${at(selectionBase, selectionMinutes)}, оформить бронь можно до ${at(selectionBase, selectionMinutes + rules.holdMinutes)}.`,
      retentionMinutes: `Если гость указал приезд к открытию (${at(night.opensAt)}), стол ждёт его до ${at(night.opensAt, rules.retentionMinutes)}.`,
    };
  } catch {
    // An older WebView may not know the club zone. The saved server preview still has its offsets.
    return;
  }
}

function BookingPreview({ view }: { view: BookingRulesView }) {
  return (
    <section
      className="po-card ob-card"
      aria-labelledby="booking-rules-preview"
    >
      <h2 id="booking-rules-preview">Предпросмотр сохранённых правил</h2>
      <p>
        Время на оформление брони:{' '}
        {view.rules.holdMinutes === null
          ? 'срок не задан'
          : `${view.rules.holdMinutes} мин с начала оформления`}
      </p>
      <p>
        Гость указывает ориентировочное время приезда в пределах клубной ночи.
        Подтверждённая бронь закрепляет стол на всю ночь. При опоздании стол
        ждёт гостя {view.rules.retentionMinutes} мин после выбранного времени
        приезда.
      </p>
      {view.nights.length === 0 && (
        <p>
          Добавьте ближайшую рабочую ночь в «Календаре клуба», чтобы увидеть
          расчёт.
        </p>
      )}
      {view.invalidDates.length > 0 && (
        <p>
          Календарь не смог рассчитать ночи на даты{' '}
          {view.invalidDates.map(dateLabel).join(', ')}.
        </p>
      )}
      {view.nights.map((night) => (
        <details className="po-card ob-card" key={night.businessDate}>
          <summary>Вечеринка {dateLabel(night.businessDate)}</summary>
          <p>
            Открытие{' '}
            <time dateTime={night.opensAt}>{localLabel(night.opensAt)}</time>
          </p>
          <p>
            Закрытие{' '}
            <time dateTime={night.closesAt}>{localLabel(night.closesAt)}</time>
          </p>
          <p>
            Приём онлайн-броней до{' '}
            <time dateTime={night.cutoffAt}>{localLabel(night.cutoffAt)}</time>
          </p>
          {night.cutoffAtOrBeforeOpen && (
            <p>Бронирование прекращается к открытию или раньше.</p>
          )}
        </details>
      ))}
    </section>
  );
}

function parseRules(edits: Edits, saved: RuleValues): RuleValues | undefined {
  const { cutoffMinutes, holdMinutes, retentionMinutes } = saved;
  const values = { cutoffMinutes, holdMinutes, retentionMinutes };
  for (const field of fields) {
    const text = edits[field.id];
    if (text === undefined) continue;
    if (text === '' && !field.required) {
      values[field.id] = null;
      continue;
    }
    const minutes = Number(text);
    if (!/^\d+$/.test(text) || minutes < field.min || minutes > maxMinutes)
      return;
    values[field.id] = minutes;
  }
  return values;
}

type BookingRulesProps = {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (dirty: boolean) => void;
  reportPending: (pending: boolean) => void;
};

export default function ClubBookingRules(props: BookingRulesProps) {
  return (
    <BookingRulesResource key={props.credential + ':' + props.id} {...props} />
  );
}

function BookingRulesResource(props: BookingRulesProps) {
  const path = '/api/client/clubs/' + props.id + '/booking-rules';
  const [view, setView] = useState<BookingRulesView>();
  const latest = useRef<BookingRulesView>();
  const epoch = useRef(0);
  const clear = () => {
    epoch.current++;
    latest.current = undefined;
    setView(undefined);
  };
  const receive = (next: BookingRulesView) => {
    if (
      next.clubId !== props.id ||
      !/^[1-9]\d*$/.test(next.version) ||
      typeof next.canWrite !== 'boolean'
    )
      throw new OnboardingError(404, 'not_found');
    const previous = latest.current;
    if (previous?.canWrite && !next.canWrite) epoch.current++;
    // A settings revision does not version grants; the latest read decides canWrite.
    latest.current =
      previous && BigInt(next.version) < BigInt(previous.version)
        ? { ...previous, canWrite: next.canWrite }
        : next;
    setView(latest.current);
  };
  const remote = useRemote<BookingRulesView>(
    props.credential,
    path,
    undefined,
    {
      onSuccess: receive,
      onError: (error) => {
        if (authorityLost(error)) clear();
      },
    },
  );
  if (!view)
    return (
      <Shell title="Правила бронирования" back={props.back} backLabel="Назад">
        <RemoteState {...remote} />
      </Shell>
    );
  const authorized = epoch.current;
  return (
    <RulesEditor
      key={authorized}
      {...props}
      view={view}
      path={path}
      remote={remote}
      onRefusal={() => {
        if (epoch.current === authorized) {
          clear();
          remote.refresh();
        }
      }}
      onSaved={(result) => {
        if (epoch.current !== authorized) return false;
        receive(result);
        return true;
      }}
    />
  );
}

function RulesEditor({
  credential,
  back,
  reportDirty,
  reportPending,
  view,
  path,
  remote,
  onRefusal,
  onSaved,
}: BookingRulesProps & {
  view: BookingRulesView;
  path: string;
  remote: ReturnType<typeof useRemote<BookingRulesView>>;
  onRefusal: () => void;
  onSaved: (result: BookingRulesView) => boolean;
}) {
  const command = useCommand<BookingRulesView>(credential, {
    onError: (error) => {
      if (authorityLost(error)) onRefusal();
    },
  });
  const [draft, setDraft] = useState<Draft>();
  const [invalid, setInvalid] = useState(false);
  const refresh = remote.refresh;
  const leave = useUnsavedChanges(
    Boolean(draft) || command.blocked,
    reportDirty,
  );
  const conflict =
    command.error instanceof OnboardingError &&
    command.error.code === 'version_conflict';

  useEffect(() => {
    reportPending(command.blocked);
    return () => reportPending(false);
  }, [command.blocked, reportPending]);
  useEffect(() => {
    if (conflict) refresh();
  }, [conflict, refresh]);

  const checking = remote.loading || Boolean(remote.error);
  const stale = Boolean(draft && draft.version !== view.version);
  const needsReview = draft && (conflict || stale);
  const blocked =
    !view.canWrite || checking || command.blocked || Boolean(needsReview);
  const draftRules = draft ? parseRules(draft.edits, view.rules) : undefined;
  const exampleRules = draft && view.canWrite ? draftRules : view.rules;
  const examples =
    view.nights[0] && exampleRules
      ? nightExamples(view.nights[0], exampleRules, view.timezone)
      : undefined;

  return (
    <Shell
      title="Правила бронирования"
      subtitle={view.name + ' · ' + view.timezone}
      back={() => {
        if (!command.blocked && leave()) back();
      }}
      backLabel="Назад"
    >
      <RemoteState {...remote} />
      <p className="po-muted">
        Настройки клуба. Предпросмотр рассчитан по календарю. Гостевое
        бронирование использует свои действующие правила.
      </p>
      {!view.canWrite && (
        <p className="po-notice">Правила доступны для просмотра.</p>
      )}
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (!draft || blocked) return;
          const rules = parseRules(draft.edits, view.rules);
          setInvalid(!rules);
          if (rules)
            command.run(
              path,
              { ...rules, expectedVersion: draft.version },
              (saved) => {
                if (!onSaved(saved)) return false;
                setDraft(undefined);
                refresh();
              },
              'PATCH',
            );
        }}
      >
        {examples && (
          <p className="po-muted">
            Примеры для вечеринки {dateLabel(view.nights[0].businessDate)} по
            времени клуба.
          </p>
        )}
        <fieldset className="ob-fields" disabled={blocked}>
          {fields.map((field) => (
            <Field label={field.label} key={field.id}>
              <input
                type="text"
                inputMode="numeric"
                pattern="[0-9]*"
                required={field.required}
                aria-label={field.label}
                aria-describedby={field.id + '-hint'}
                value={
                  (view.canWrite ? draft?.edits[field.id] : undefined) ??
                  view.rules[field.id] ??
                  ''
                }
                onChange={(event) => {
                  const text = event.target.value;
                  setInvalid(false);
                  setDraft((current) => ({
                    version: current?.version ?? view.version,
                    edits: { ...current?.edits, [field.id]: text },
                  }));
                }}
              />
              <small id={field.id + '-hint'}>
                {field.hint} {examples?.[field.id]}
              </small>
            </Field>
          ))}
        </fieldset>
        {invalid && (
          <p className="po-notice" role="alert">
            Введите целое число минут от 0 до {maxMinutes}. Для оформления брони
            укажите минимум 1 минуту или оставьте поле пустым.
          </p>
        )}
        {needsReview && view.canWrite && !command.blocked && (
          <section className="po-card ob-card" role="alert">
            <p>
              Правила изменились. Сравните черновик с последними сохранёнными
              значениями.
            </p>
            <ul>
              {fields.map((field) => (
                <li key={field.id}>
                  {field.label} · {view.rules[field.id] ?? 'не задано'}
                </li>
              ))}
            </ul>
            <button
              className="ob-button"
              type="button"
              disabled={checking || !stale}
              onClick={() => {
                setDraft({ ...draft, version: view.version });
                command.reset();
              }}
            >
              Перенести мои правки
            </button>
          </section>
        )}
        {view.canWrite && (
          <button
            className="po-primary"
            type="submit"
            disabled={blocked || !draft}
          >
            Сохранить правила
          </button>
        )}
      </form>
      {!conflict && <CommandState command={command} refresh={refresh} />}
      <BookingPreview view={view} />
    </Shell>
  );
}
