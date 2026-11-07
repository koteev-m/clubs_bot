import { RefObject, useEffect, useRef, useState } from 'react';
import { authorityLost, OnboardingError } from './onboarding.api';
import { useCommand, useRemote } from './onboarding.hooks';
import {
  CommandState,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from './OnboardingShared';
import {
  calendarBody,
  calendarDraft,
  clubToday,
  calendarChanges,
  calendarValidation,
  CalendarView,
  dateLabel,
  hasChanges,
  localLabel,
  reapplyChanges,
  Rules,
  weekday,
} from './calendar';
import { CalendarRulesEditor } from './CalendarRulesEditor';
import './calendar.css';

const sources = {
  WEEKEND_RULE: 'По расписанию',
  HOLIDAY: 'Праздник',
  EXCEPTION: 'Изменение на дату',
};

function CalendarPreview({
  view,
  heading,
}: {
  view: CalendarView;
  heading: RefObject<HTMLHeadingElement>;
}) {
  return (
    <section className="po-card ob-card cc-preview">
      <h2 ref={heading} tabIndex={-1}>
        Ближайшие клубные ночи
      </h2>
      <p className="po-muted">По сохранённому расписанию. Время местное.</p>
      {view.nights.length === 0 && (
        <p>
          В ближайшем расписании пока нет ночей. Выберите день вечеринки и часы
          ниже, затем нажмите «Сохранить расписание». Если расписание уже
          сохранено, нажмите «Сформировать ближайшие ночи».
        </p>
      )}
      <ol>
        {view.nights.map((night) => (
          <li key={night.businessDate}>
            <strong>
              Ночь {dateLabel(night.businessDate)} ·{' '}
              {weekday(night.businessDate)}
            </strong>
            <span className="po-muted">
              {sources[night.source]} ·{' '}
              {night.nightId
                ? `№ ${night.nightId}`
                : 'Рассчитана по расписанию · ещё не сформирована'}
            </span>
            <span>
              Начало:{' '}
              <time dateTime={night.opensAt}>{localLabel(night.opensAt)}</time>
            </span>
            <span>
              Окончание:{' '}
              <time dateTime={night.closesAt}>
                {localLabel(night.closesAt)}
              </time>
            </span>
          </li>
        ))}
      </ol>
      {Boolean(view.cancelledNights?.length) && (
        <div>
          <h3>Отменённые ночи</h3>
          <ul>
            {view.cancelledNights?.map((night) => (
              <li key={night.nightId}>
                <strong>
                  Ночь {dateLabel(night.businessDate)} ·{' '}
                  {weekday(night.businessDate)} · № {night.nightId}
                </strong>
                <span>
                  Последний интервал {localLabel(night.opensAt)} —{' '}
                  {localLabel(night.closesAt)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {view.invalidDates.length > 0 && (
        <p className="po-notice" role="status">
          Проверьте часы для дат: {view.invalidDates.map(dateLabel).join(', ')}.
          Для них не удалось рассчитать интервал с учётом перевода часов.
        </p>
      )}
    </section>
  );
}

export default function ClubCalendar({
  credential,
  id,
  back,
  reportDirty,
  reportPending,
}: {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (value: boolean) => void;
  reportPending: (value: boolean) => void;
}) {
  const path = '/api/client/clubs/' + id + '/calendar';
  const remote = useRemote<CalendarView>(credential, path);
  const command = useCommand<CalendarView>(credential);
  const [latest, setLatest] = useState<CalendarView>();
  const [base, setBase] = useState<CalendarView>();
  const [draft, setDraft] = useState<Rules>();
  const [validation, setValidation] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [reviewedConflict, setReviewedConflict] = useState(false);
  const refresh = remote.refresh;
  const lastRemote = useRef<CalendarView>();
  const previewHeading = useRef<HTMLHeadingElement>(null);
  const changes = base && draft ? calendarChanges(base, draft) : undefined;
  const dirty = changes ? hasChanges(changes) : false;
  const leave = useUnsavedChanges(dirty || command.blocked, reportDirty);

  useEffect(() => {
    const next = remote.data;
    if (!next || lastRemote.current === next) return;
    lastRemote.current = next;
    setLatest((previous) =>
      previous && BigInt(previous.version) > BigInt(next.version)
        ? previous
        : next,
    );
    if (!dirty && !command.blocked && !conflict) {
      setBase((previous) =>
        previous && BigInt(previous.version) > BigInt(next.version)
          ? previous
          : next,
      );
      setDraft((previous) =>
        base && BigInt(base.version) > BigInt(next.version)
          ? previous
          : calendarDraft(next),
      );
    }
  }, [remote.data, dirty, command.blocked, conflict, base]);

  useEffect(() => {
    reportPending(command.blocked);
    return () => reportPending(false);
  }, [reportPending, command.blocked]);

  const writeDenied = authorityLost(command.error);
  useEffect(() => {
    if (writeDenied) refresh();
  }, [writeDenied, refresh]);

  const view = authorityLost(remote.error) ? undefined : latest;
  const checking = remote.loading || Boolean(remote.error);
  const writeUnavailable = writeDenied && view?.canWrite;
  const stale = Boolean(
    base &&
      view &&
      (base.version !== view.version || base.timezone !== view.timezone),
  );
  const needsReview = !command.blocked && (conflict || (dirty && stale));
  const blocked = !view?.canWrite || checking || command.blocked || needsReview;
  const accept = (saved: CalendarView) => {
    setLatest(saved);
    setBase(saved);
    setDraft(calendarDraft(saved));
    setReviewedConflict(false);
    setValidation(null);
    refresh();
  };
  useEffect(() => {
    if (command.success) previewHeading.current?.focus();
  }, [command.success]);

  const versionConflict =
    command.error instanceof OnboardingError &&
    command.error.code === 'version_conflict';
  useEffect(() => {
    if (versionConflict) {
      setConflict(true);
      refresh();
    }
  }, [versionConflict, refresh]);

  return (
    <Shell
      title="Календарь клуба"
      subtitle={view ? view.name + ' · ' + view.timezone : undefined}
      back={() => {
        if (!command.blocked && leave()) back();
      }}
      backLabel="Профиль клуба"
    >
      <RemoteState {...remote} />
      {view && base && draft && changes && (
        <>
          <p className="po-muted">
            Календарь доступен в кабинете клуба. Сохранение не открывает
            бронирование для гостей.
          </p>
          <CalendarPreview view={view} heading={previewHeading} />
          {!view.canWrite && (
            <p className="po-notice">
              Календарь доступен только для просмотра.
            </p>
          )}
          {needsReview && view.canWrite && (
            <section className="po-notice cc-conflict" role="alert">
              <p>
                Расписание или часовой пояс уже изменились. Ниже остался ваш
                черновик, выше — актуальные ночи.
              </p>
              {(stale || conflict) && (
                <button
                  type="button"
                  className="ob-button"
                  disabled={checking || writeDenied || !stale}
                  onClick={() => {
                    setDraft(reapplyChanges(calendarDraft(view), changes));
                    setBase(view);
                    setConflict(false);
                    setReviewedConflict(true);
                    command.reset();
                  }}
                >
                  Перенести мои правки на актуальную версию
                </button>
              )}
              <button type="button" className="ob-button" onClick={refresh}>
                Обновить расписание
              </button>
            </section>
          )}
          {view.canWrite && !dirty && (
            <>
              <button
                className="ob-button"
                type="button"
                disabled={blocked || writeDenied}
                onClick={() =>
                  command.run(
                    path,
                    calendarBody(base, changes, true),
                    accept,
                    'PATCH',
                  )
                }
              >
                Сформировать ближайшие ночи
              </button>
              <p className="po-muted">
                Это отдельное действие по сохранённому расписанию: сохраняет
                постоянные номера ночей на ближайшие 56 календарных дней и для
                будущих разовых правил. Существующие ночи сверяются с правилами;
                защищённые изменения могут быть отклонены. Мероприятия и брони
                не создаются, клуб не публикуется.
              </p>
            </>
          )}
          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (blocked || !dirty || writeDenied) return;
              const problem = calendarValidation(changes);
              setValidation(problem);
              if (!problem)
                command.run(path, calendarBody(base, changes), accept, 'PATCH');
            }}
          >
            <p className="po-muted">
              «Сохранить расписание» сохраняет ваши правки и обновляет
              затронутые ночи по действующим правилам. «Сформировать ближайшие
              ночи» применяет уже сохранённое расписание к ближайшему периоду.
              Оба действия требуют вашего нажатия.
            </p>
            <CalendarRulesEditor
              referenceDate={clubToday(view.timezone)}
              value={view.canWrite ? draft : view}
              onChange={(rules) => {
                setDraft(rules);
                setValidation(null);
              }}
              disabled={blocked || writeDenied}
              readOnly={!view.canWrite}
            />
            {validation && (
              <p className="po-notice" role="alert">
                {validation}
              </p>
            )}
            {reviewedConflict && (
              <p className="po-notice">
                Проверьте черновик: при сохранении ваши правки заменят текущие
                правила только для изменённых вами дней и дат.
              </p>
            )}
            {view.canWrite && (
              <button
                className="po-primary"
                type="submit"
                disabled={blocked || !dirty || writeDenied}
              >
                Сохранить расписание
              </button>
            )}
          </form>
        </>
      )}
      {!versionConflict && <CommandState command={command} />}
      {writeUnavailable && !checking && (
        <button type="button" className="ob-button" onClick={command.reset}>
          Продолжить редактирование
        </button>
      )}
    </Shell>
  );
}
