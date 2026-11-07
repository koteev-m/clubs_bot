import { useEffect, useState } from 'react';
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
    label: 'Прекращение онлайн-бронирования · минут до закрытия',
    hint: 'За сколько минут до закрытия перестать принимать онлайн-брони. Например, закрытие в 12:00, значение 120 — приём до 10:00.',
    min: 0,
    required: true,
  },
  {
    id: 'arrivalMinutes',
    label: 'Предельное время прибытия · минут после открытия',
    hint: 'До какого времени гость должен заявить прибытие. Например, открытие в 00:00, значение 180 — прибыть до 03:00. Пустое поле — время не задано.',
    min: 0,
    required: false,
  },
  {
    id: 'holdMinutes',
    label: 'Время на оформление брони (HOLD) · минут',
    hint: 'Выбранный стол временно удерживается, пока гость оформляет бронь. Рекомендуем 10 минут: начало в 21:00 — до 21:10. Пустое поле — срок не задан. Рекомендация не меняет сохранённое значение.',
    min: 1,
    required: false,
  },
  {
    id: 'retentionMinutes',
    label: 'Ожидание гостя после заявленного прибытия · минут',
    hint: 'Сколько ждать опаздывающего гостя с подтверждённой бронью. Например, прибытие в 01:00, значение 30 — ждать до 01:30. Это отдельный срок от HOLD при оформлении.',
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
  arrivalBy: string | null;
  cutoffAtOrBeforeOpen: boolean;
  arrivalAtOrAfterClose: boolean;
  cutoffAfterArrival: boolean;
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

function timeText(iso: string) {
  return iso.replace('T', ' ').replace(/Z$/, ' +00:00');
}

function BookingPreview({ view }: { view: BookingRulesView }) {
  return (
    <section
      className="po-card ob-card"
      aria-labelledby="booking-rules-preview"
    >
      <h2 id="booking-rules-preview">Предпросмотр сохранённых правил</h2>
      <p>
        Время на оформление (HOLD):{' '}
        {view.rules.holdMinutes === null
          ? 'срок не задан'
          : `${view.rules.holdMinutes} мин с начала оформления`}
      </p>
      <p>
        Ожидание гостя: {view.rules.retentionMinutes} мин после заявленного
        гостем времени.
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
          {view.invalidDates.join(', ')}.
        </p>
      )}
      {view.nights.map((night) => (
        <details className="po-card ob-card" key={night.businessDate}>
          <summary>Ночь {night.businessDate}</summary>
          <p>
            Открытие{' '}
            <time dateTime={night.opensAt}>{timeText(night.opensAt)}</time>
          </p>
          <p>
            Закрытие{' '}
            <time dateTime={night.closesAt}>{timeText(night.closesAt)}</time>
          </p>
          <p>
            Прекращение онлайн-бронирования{' '}
            <time dateTime={night.cutoffAt}>{timeText(night.cutoffAt)}</time>
          </p>
          <p>
            Прибытие до{' '}
            {night.arrivalBy ? (
              <time dateTime={night.arrivalBy}>
                {timeText(night.arrivalBy)}
              </time>
            ) : (
              '— время не задано'
            )}
          </p>
          {night.cutoffAtOrBeforeOpen && (
            <p>Бронирование прекращается к открытию или раньше.</p>
          )}
          {night.arrivalAtOrAfterClose && (
            <p>Время прибытия приходится на закрытие или позже.</p>
          )}
          {night.cutoffAfterArrival && (
            <p className="po-notice" role="status">
              Проверьте правила: онлайн-бронирование остаётся открытым после
              предельного времени прибытия. Гость сможет начать оформление,
              когда прибыть вовремя уже нельзя.
            </p>
          )}
        </details>
      ))}
    </section>
  );
}

function parseRules(edits: Edits, saved: RuleValues): RuleValues | undefined {
  const values = { ...saved };
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

export default function ClubBookingRules({
  credential,
  id,
  back,
  reportDirty,
  reportPending,
}: {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (dirty: boolean) => void;
  reportPending: (pending: boolean) => void;
}) {
  const path = '/api/client/clubs/' + id + '/booking-rules';
  const remote = useRemote<BookingRulesView>(credential, path);
  const command = useCommand<BookingRulesView>(credential);
  const [latest, setLatest] = useState<BookingRulesView>();
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
  const denied = authorityLost(command.error);

  useEffect(() => {
    const received = remote.data;
    if (received) {
      setLatest((current) =>
        !current || BigInt(received.version) >= BigInt(current.version)
          ? received
          : current,
      );
    }
  }, [remote.data]);
  useEffect(() => {
    reportPending(command.blocked);
    return () => reportPending(false);
  }, [command.blocked, reportPending]);
  useEffect(() => {
    if (conflict || denied) refresh();
  }, [conflict, denied, refresh]);

  const view = authorityLost(remote.error) ? undefined : latest;
  const checking = remote.loading || Boolean(remote.error);
  const stale = Boolean(draft && view && draft.version !== view.version);
  const needsReview = draft && (conflict || stale);
  const blocked =
    !view?.canWrite ||
    checking ||
    command.blocked ||
    Boolean(needsReview) ||
    denied;
  const draftRules =
    view && draft ? parseRules(draft.edits, view.rules) : undefined;
  const contradictoryDates =
    draftRules?.arrivalMinutes != null && view
      ? view.nights
          .filter(
            (night) =>
              Date.parse(night.closesAt) - draftRules.cutoffMinutes! * 60000 >
              Date.parse(night.opensAt) + draftRules.arrivalMinutes! * 60000,
          )
          .map((night) => night.businessDate)
      : [];

  return (
    <Shell
      title="Правила бронирования"
      subtitle={view ? view.name + ' · ' + view.timezone : undefined}
      back={() => {
        if (!command.blocked && leave()) back();
      }}
      backLabel="Профиль клуба"
    >
      <RemoteState {...remote} />
      {view && (
        <>
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
                    setLatest(saved);
                    setDraft(undefined);
                    refresh();
                  },
                  'PATCH',
                );
            }}
          >
            <fieldset className="ob-fields" disabled={blocked}>
              {fields.map((field) => (
                <Field label={field.label} key={field.id}>
                  <input
                    type="text"
                    inputMode="numeric"
                    pattern="[0-9]*"
                    required={field.required}
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
                  <small id={field.id + '-hint'}>{field.hint}</small>
                </Field>
              ))}
            </fieldset>
            {invalid && (
              <p className="po-notice" role="alert">
                Введите целое число минут от 0 до {maxMinutes}. Для HOLD укажите
                минимум 1 минуту или оставьте поле пустым.
              </p>
            )}
            {contradictoryDates.length > 0 && (
              <p className="po-notice" role="status">
                Проверьте черновик для ночей {contradictoryDates.join(', ')}:
                онлайн-бронирование закончится позже предельного времени
                прибытия. Измените один из сроков, если это не задумано.
              </p>
            )}
            {needsReview && view.canWrite && !command.blocked && (
              <section className="po-card ob-card" role="alert">
                <p>
                  Правила изменились. Сравните черновик с последними
                  сохранёнными значениями.
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
                  disabled={checking || denied || !stale}
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
        </>
      )}
    </Shell>
  );
}
