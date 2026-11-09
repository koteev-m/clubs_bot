import { RefObject, useEffect, useId, useMemo, useRef, useState } from 'react';
import { authorityLost, errorMessage, OnboardingError } from './onboarding.api';
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
  calendarPlan,
  calendarSnapshotKey,
  clubToday,
  calendarChanges,
  calendarChangeDescriptions,
  calendarValidation,
  CalendarNight,
  CalendarView,
  dateLabel,
  hasChanges,
  localLabel,
  nightTitle,
  reapplyChanges,
  Rules,
  validCalendarView,
} from './calendar';
import { CalendarRulesEditor } from './CalendarRulesEditor';
import {
  calendarCommandIntent,
  calendarCommandRetry,
  type CalendarCommandIntent,
} from './calendarCommand';
import './calendar.css';

const sources = {
  WEEKEND_RULE: 'По расписанию',
  HOLIDAY: 'Праздник',
  EXCEPTION: 'Изменение на дату',
};
const areas = [
  { id: 'upcoming', title: 'Предстоящие ночи' },
  { id: 'weekly', title: 'Постоянное расписание' },
  { id: 'special', title: 'Особые даты' },
] as const;
type Area = (typeof areas)[number]['id'];

function NightInterval({ night }: { night: CalendarNight }) {
  return (
    <>
      <span>
        Открытие:{' '}
        <time dateTime={night.opensAt}>{localLabel(night.opensAt)}</time>
      </span>
      <span>
        Закрытие:{' '}
        <time dateTime={night.closesAt}>{localLabel(night.closesAt)}</time>
      </span>
    </>
  );
}

function CalendarPreview({
  view,
  heading,
  edit,
}: {
  view: CalendarView;
  heading: RefObject<HTMLHeadingElement>;
  edit: (date: string) => void;
}) {
  return (
    <section className="po-card ob-card cc-preview">
      <h2 ref={heading} tabIndex={-1}>
        Предстоящие ночи
      </h2>
      <p className="po-muted">
        Сохранённое расписание · время клуба {view.timezone}.
      </p>
      {view.nights.length === 0 && (
        <p>
          Ночей пока нет. Задайте постоянное расписание или добавьте особую
          дату. По сохранённым правилам можно отдельно сформировать ближайшие
          ночи.
        </p>
      )}
      <ol>
        {view.nights.map((night) => (
          <li key={night.businessDate}>
            <strong>{nightTitle(night.businessDate)}</strong>
            <span>
              Дата вечеринки:{' '}
              <time dateTime={night.businessDate}>
                {dateLabel(night.businessDate)}
              </time>
            </span>
            <NightInterval night={night} />
            <span className="po-muted">
              {sources[night.source]} ·{' '}
              {night.nightId
                ? 'Сформирована и сохранена'
                : 'Правило сохранено · ночь ещё не сформирована'}
            </span>
            <details>
              <summary>Посмотреть ночь {dateLabel(night.businessDate)}</summary>
              <p className="po-muted">
                {night.nightId
                  ? `Номер ночи: ${night.nightId}.`
                  : 'Постоянный номер появится после формирования.'}{' '}
                Клубная дата сохраняется, даже если открытие после полуночи.
              </p>
              {view.canWrite && (
                <button
                  type="button"
                  className="ob-button"
                  onClick={() => edit(night.businessDate)}
                >
                  Изменить ночь {dateLabel(night.businessDate)}
                </button>
              )}
            </details>
          </li>
        ))}
      </ol>
      {Boolean(view.cancelledNights?.length) && (
        <details>
          <summary>Отменённые ночи ({view.cancelledNights?.length})</summary>
          <ul>
            {view.cancelledNights?.map((night) => (
              <li key={night.nightId}>
                <strong>
                  {nightTitle(night.businessDate)} ·{' '}
                  {dateLabel(night.businessDate)} · отменена
                </strong>
                <span className="po-muted">Последний сохранённый интервал</span>
                <NightInterval night={night} />
              </li>
            ))}
          </ul>
        </details>
      )}
      {view.invalidDates.length > 0 && (
        <p className="po-notice" role="status">
          Проверка интервала недоступна для дат:{' '}
          {view.invalidDates.map(dateLabel).join(', ')}. Проверьте часы в особых
          датах.
        </p>
      )}
    </section>
  );
}

interface Props {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (value: boolean) => void;
  reportPending: (value: boolean) => void;
}

export default function ClubCalendar(props: Props) {
  return (
    <ClubCalendarSession key={props.credential + ':' + props.id} {...props} />
  );
}

function ClubCalendarSession({
  credential,
  id,
  back,
  reportDirty,
  reportPending,
}: Props) {
  const path = '/api/client/clubs/' + id + '/calendar';
  const authority = useRef(false);
  const confirmed = useRef<CalendarView>();
  const authoritySequence = useRef(0);
  const lastWriteAuthority = useRef(false);
  const approvedPlan = useRef('');
  const sourceEpoch = useRef(0);
  const remote = useRemote<CalendarView>(credential, path, undefined, {
    onSuccess: (next) => {
      if (!validCalendarView(next, id)) {
        authority.current = false;
        confirmed.current = undefined;
        authoritySequence.current += 1;
        throw new OnboardingError(502, 'invalid_response');
      }
      if (lastWriteAuthority.current && !next.canWrite)
        authoritySequence.current += 1;
      lastWriteAuthority.current = next.canWrite;
      authority.current = next.clubId === id;
      if (authority.current) {
        const prior =
          confirmed.current && calendarSnapshotKey(confirmed.current);
        confirmed.current =
          confirmed.current &&
          BigInt(confirmed.current.version) > BigInt(next.version)
            ? { ...confirmed.current, canWrite: next.canWrite }
            : next;
        if (calendarSnapshotKey(confirmed.current) !== prior) {
          sourceEpoch.current++;
          approvedPlan.current = '';
          setOverlapConsent('');
        }
      } else confirmed.current = undefined;
    },
    onError: (error) => {
      if (authorityLost(error)) {
        authority.current = false;
        confirmed.current = undefined;
        authoritySequence.current += 1;
      }
    },
  });
  const command = useCommand<CalendarView>(credential, {
    preserveUnknownOnRetryFailure: true,
  });
  const [latest, setLatest] = useState<CalendarView>();
  const [base, setBase] = useState<CalendarView>();
  const [draft, setDraft] = useState<Rules>();
  const [validation, setValidation] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [reviewedConflict, setReviewedConflict] = useState(false);
  const [area, setArea] = useState<Area>('upcoming');
  const [selectedDate, setSelectedDate] = useState<string>();
  const [confirmation, setConfirmation] = useState<
    'save' | 'materialize' | null
  >(null);
  const [overlapConsent, setOverlapConsent] = useState('');
  const issuedPlan = useRef<CalendarCommandIntent>();
  const receiptState = useRef<'indeterminate' | 'not_committed_at_snapshot'>();
  const [receiptStatus, setReceiptStatus] =
    useState<typeof receiptState.current>();
  const [receiptNotice, setReceiptNotice] = useState<string>();
  const refresh = remote.refresh;
  const lastRemote = useRef<CalendarView>();
  const previewHeading = useRef<HTMLHeadingElement>(null);
  const confirmationHeading = useRef<HTMLHeadingElement>(null);
  const tabsId = useId();
  const changes = base && draft ? calendarChanges(base, draft) : undefined;
  const dirty = changes ? hasChanges(changes) : false;
  const leave = useUnsavedChanges(dirty || command.blocked, reportDirty);

  useEffect(() => {
    const next = remote.data;
    if (!next || lastRemote.current === next) return;
    lastRemote.current = next;
    if (next.clubId !== id) {
      authority.current = false;
      setLatest(undefined);
      setBase(undefined);
      setDraft(undefined);
      setConfirmation(null);
      return;
    }
    setLatest((previous) =>
      previous && BigInt(previous.version) > BigInt(next.version)
        ? { ...previous, canWrite: next.canWrite }
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
  }, [remote.data, dirty, command.blocked, conflict, base, id]);

  useEffect(() => {
    if (!authorityLost(remote.error)) return;
    setLatest(undefined);
    setBase(undefined);
    setDraft(undefined);
    setConfirmation(null);
  }, [remote.error]);
  useEffect(() => {
    reportPending(command.blocked);
    return () => reportPending(false);
  }, [reportPending, command.blocked]);

  const writeDenied = authorityLost(command.error);
  useEffect(() => {
    if (writeDenied) refresh();
  }, [writeDenied, refresh]);
  const view =
    authorityLost(remote.error) || !authority.current ? undefined : latest;
  const checking = remote.loading || Boolean(remote.error);
  const writeUnavailable = writeDenied && view?.canWrite;
  const stale = Boolean(
    base && view && calendarSnapshotKey(base) !== calendarSnapshotKey(view),
  );
  const needsReview = !command.blocked && (conflict || (dirty && stale));
  const blocked = !view?.canWrite || checking || command.blocked || needsReview;
  const today = view ? clubToday(view.timezone) : '';
  const plan = useMemo(() => {
    if (!confirmation || !draft || !view || !base) return undefined;
    try {
      const result = calendarPlan(draft, view.timezone, today, base);
      return result.complete ? result : undefined;
    } catch {
      return undefined;
    }
  }, [confirmation, draft, view, base, today]);
  const planKey =
    plan &&
    view &&
    JSON.stringify([confirmation, calendarSnapshotKey(view), plan.identity]);
  const overlapConfirmed = Boolean(planKey && overlapConsent === planKey);
  // Also runs synchronously in the dispatch handler: a GET callback can update
  // confirmed.current before React commits the next render/effect.
  const dispatchPlanKey = (
    source: CalendarView,
    rules: Rules,
    operation: 'save' | 'materialize',
  ) => {
    if (
      !source.canWrite ||
      !base ||
      calendarSnapshotKey(source) !== calendarSnapshotKey(base)
    )
      return undefined;
    try {
      const checked = calendarPlan(
        rules,
        source.timezone,
        clubToday(source.timezone),
        source,
      );
      if (
        !checked.complete ||
        checked.gaps.length ||
        checked.invalidDates.length
      )
        return undefined;
      return JSON.stringify([
        operation,
        calendarSnapshotKey(source),
        checked.identity,
      ]);
    } catch {
      return undefined;
    }
  };
  const retry = () => {
    const issued = issuedPlan.current;
    const current = confirmed.current;
    if (
      !authority.current ||
      !current ||
      !issued ||
      command.resolving ||
      receiptState.current !== 'not_committed_at_snapshot'
    )
      return;
    const decision = calendarCommandRetry(
      issued,
      current,
      clubToday(current.timezone),
      approvedPlan.current,
    );
    if (decision !== 'allowed') {
      setValidation(
        decision === 'consent_required'
          ? 'Подтвердите время исходной команды заново перед повтором.'
          : 'Исходная команда не соответствует актуальному расписанию. Повтор остановлен; проверьте её результат ещё раз.',
      );
      return;
    }
    receiptState.current = undefined;
    setReceiptStatus(undefined);
    command.retry();
  };
  const resolveUnknown = () => {
    const issued = issuedPlan.current;
    if (!issued || command.resolving || command.saving) return;
    const authorityEpoch = authoritySequence.current;
    const snapshotEpoch = sourceEpoch.current;
    command.resolve<{
      status: 'completed' | 'indeterminate' | 'not_committed_at_snapshot';
      calendar?: CalendarView;
    }>(path + '/commands/resolve', (result) => {
      if (!authority.current || authorityEpoch !== authoritySequence.current)
        return false;
      if (result.status === 'indeterminate') {
        receiptState.current = 'indeterminate';
        setReceiptStatus('indeterminate');
        return false;
      }
      if (
        !['completed', 'not_committed_at_snapshot'].includes(result.status) ||
        !result.calendar ||
        !validCalendarView(result.calendar, id) ||
        !result.calendar.canWrite
      )
        throw new OnboardingError(502, 'invalid_response');
      const known = confirmed.current;
      const received = result.calendar;
      const snapshot =
        known &&
        (BigInt(known.version) > BigInt(received.version) ||
          (sourceEpoch.current !== snapshotEpoch &&
            known.version === received.version))
          ? known
          : received;
      if (
        !known ||
        calendarSnapshotKey(known) !== calendarSnapshotKey(snapshot)
      ) {
        approvedPlan.current = '';
        setOverlapConsent('');
        sourceEpoch.current++;
      }
      confirmed.current = snapshot;
      setLatest(snapshot);
      if (result.status === 'completed') {
        setBase(snapshot);
        setDraft(calendarDraft(snapshot));
        setConflict(false);
        setReviewedConflict(false);
        setConfirmation(null);
        setValidation(null);
        setArea('upcoming');
        receiptState.current = undefined;
        setReceiptStatus(undefined);
        setReceiptNotice('Операция выполнена. Показано актуальное расписание.');
        refresh();
        return true;
      }
      // No receipt while holding the actor serialization lock is a snapshot
      // fact, not cancellation: a delayed original request can still arrive.
      receiptState.current = 'not_committed_at_snapshot';
      setReceiptStatus('not_committed_at_snapshot');
      if (BigInt(received.version) > BigInt(issued.body.expectedVersion)) {
        setConfirmation(null);
        setConflict(true);
        setReceiptNotice(
          'Сохранённый результат не найден. Версия исходной команды устарела; она уже не может изменить расписание. Проверьте актуальную версию.',
        );
        return 'obsolete';
      }
      return false;
    });
  };

  const issuedAuthoritySequence = authoritySequence.current;
  const accept = (saved: CalendarView) => {
    if (
      !authority.current ||
      issuedAuthoritySequence !== authoritySequence.current ||
      !validCalendarView(saved, id) ||
      Boolean(
        confirmed.current &&
          BigInt(confirmed.current.version) > BigInt(saved.version),
      )
    ) {
      refresh();
      return false;
    }
    confirmed.current = saved;
    setLatest(saved);
    setBase(saved);
    setDraft(calendarDraft(saved));
    setReviewedConflict(false);
    setValidation(null);
    setConfirmation(null);
    setArea('upcoming');
    refresh();
  };
  useEffect(() => {
    if (command.success) previewHeading.current?.focus();
  }, [command.success]);
  useEffect(() => {
    if (confirmation) confirmationHeading.current?.focus();
  }, [confirmation]);
  const versionConflict =
    !command.unknown &&
    command.error instanceof OnboardingError &&
    command.error.code === 'version_conflict';
  useEffect(() => {
    if (versionConflict) {
      setConflict(true);
      setConfirmation(null);
      refresh();
    }
  }, [versionConflict, refresh]);

  const prepare = (operation: 'save' | 'materialize') => {
    if (blocked || writeDenied || !changes) return;
    const problem = calendarValidation(changes);
    setValidation(problem);
    if (!problem) {
      approvedPlan.current = '';
      setOverlapConsent('');
      receiptState.current = undefined;
      setReceiptStatus(undefined);
      setReceiptNotice(undefined);
      setConfirmation(operation);
    }
  };
  const update = (rules: Rules) => {
    setDraft(rules);
    setValidation(null);
    setConfirmation(null);
    approvedPlan.current = '';
    setOverlapConsent('');
  };

  return (
    <Shell
      title="Календарь клуба"
      subtitle={view ? view.name + ' · ' + view.timezone : undefined}
      back={() => {
        if (confirmation && !command.blocked) {
          setConfirmation(null);
          return;
        }
        if (!command.blocked && leave()) back();
      }}
      backLabel="Назад"
    >
      <RemoteState {...remote} />
      {view && base && draft && changes && (
        <>
          <nav
            className="cc-tabs"
            role="tablist"
            aria-label="Разделы календаря"
          >
            {areas.map((item, index) => (
              <button
                key={item.id}
                type="button"
                role="tab"
                id={tabsId + '-tab-' + item.id}
                aria-controls={tabsId + '-panel-' + item.id}
                aria-selected={area === item.id}
                tabIndex={area === item.id ? 0 : -1}
                onClick={() => setArea(item.id)}
                onKeyDown={(event) => {
                  let next = index;
                  if (event.key === 'ArrowRight')
                    next = (index + 1) % areas.length;
                  else if (event.key === 'ArrowLeft')
                    next = (index + areas.length - 1) % areas.length;
                  else if (event.key === 'Home') next = 0;
                  else if (event.key === 'End') next = areas.length - 1;
                  else return;
                  event.preventDefault();
                  setArea(areas[next].id);
                  document
                    .getElementById(tabsId + '-tab-' + areas[next].id)
                    ?.focus();
                }}
              >
                {item.title}
              </button>
            ))}
          </nav>
          <p className="po-muted">
            Календарь сохраняется для этого клуба. Публичность и гостевые
            бронирования не меняются.
          </p>
          {!view.canWrite && (
            <p className="po-notice">
              Календарь доступен только для просмотра.
            </p>
          )}
          {dirty && (
            <p className="po-notice" role="status">
              Есть несохранённые изменения. Переключение разделов сохраняет
              черновик.
            </p>
          )}
          {needsReview && view.canWrite && (
            <section className="po-notice cc-conflict" role="alert">
              <p>
                Расписание или часовой пояс уже изменились. Ваш черновик
                сохранён; проверьте актуальную версию перед записью.
              </p>
              <button
                type="button"
                className="ob-button"
                disabled={checking || writeDenied || !stale}
                onClick={() => {
                  setDraft(reapplyChanges(calendarDraft(view), changes));
                  setBase(view);
                  setConflict(false);
                  setConfirmation(null);
                  setReviewedConflict(true);
                  command.reset();
                }}
              >
                Перенести мои правки на актуальную версию
              </button>
              <button type="button" className="ob-button" onClick={refresh}>
                Обновить расписание
              </button>
            </section>
          )}
          <div
            role="tabpanel"
            id={tabsId + '-panel-upcoming'}
            aria-labelledby={tabsId + '-tab-upcoming'}
            hidden={area !== 'upcoming'}
          >
            <CalendarPreview
              view={view}
              heading={previewHeading}
              edit={(date) => {
                setSelectedDate(date);
                setArea('special');
              }}
            />
            {view.canWrite && !dirty && !confirmation && (
              <>
                <button
                  className="po-primary"
                  type="button"
                  disabled={blocked || writeDenied}
                  onClick={() => prepare('materialize')}
                >
                  Сформировать ближайшие ночи
                </button>
                <p className="po-muted">
                  Отдельно сохраняет ночи по действующим правилам на ближайшие
                  56 дней и будущие особые даты.
                </p>
              </>
            )}
          </div>
          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (dirty) prepare('save');
            }}
          >
            {(['weekly', 'special'] as const).map((section) => (
              <div
                key={section}
                role="tabpanel"
                id={tabsId + '-panel-' + section}
                aria-labelledby={tabsId + '-tab-' + section}
                hidden={area !== section}
              >
                <CalendarRulesEditor
                  section={section}
                  selectedDate={
                    section === 'special' ? selectedDate : undefined
                  }
                  referenceDate={today}
                  value={view.canWrite ? draft : view}
                  onChange={update}
                  disabled={blocked || writeDenied || confirmation !== null}
                  readOnly={!view.canWrite}
                />
              </div>
            ))}
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
            {view.canWrite && dirty && !confirmation && (
              <button
                className="po-primary"
                type="submit"
                disabled={blocked || writeDenied}
              >
                Сохранить расписание
              </button>
            )}
          </form>
          {confirmation && (
            <section
              className="po-card ob-card cc-confirm"
              aria-labelledby={tabsId + '-confirm'}
            >
              <h2
                id={tabsId + '-confirm'}
                ref={confirmationHeading}
                tabIndex={-1}
              >
                {confirmation === 'save'
                  ? 'Проверьте новое расписание'
                  : 'Проверьте формирование ночей'}
              </h2>
              <p>
                {confirmation === 'save'
                  ? `Изменятся правила: дни недели — ${changes.weekly.length}, особые даты — ${changes.exceptions.length + changes.holidays.length}. Затронутые ночи будут согласованы с ними.`
                  : `Будут сформированы ближайшие ночи по уже сохранённым правилам. Расписание не изменится.`}
              </p>
              {confirmation === 'save' && (
                <ul className="cc-changes">
                  {calendarChangeDescriptions(changes, today).map(
                    (description) => (
                      <li key={description}>{description}</li>
                    ),
                  )}
                </ul>
              )}
              <p className="po-notice">
                Если у ночи есть связанные брони, HOLD, посещения, финансовые
                записи или мероприятие, защищённое изменение будет отклонено
                целиком. Связанные данные не переносятся и не удаляются.
              </p>
              <p className="po-muted">
                Предварительные интервалы в {view.timezone}. Окончательный
                результат проверит сервер при сохранении. Мероприятия и брони не
                создаются, клуб не публикуется.
              </p>
              {!plan && (
                <p className="po-notice" role="alert">
                  Проверка времени недоступна. Изменения не отправлены; обновите
                  расписание и повторите проверку.
                  <button type="button" className="ob-button" onClick={refresh}>
                    Обновить проверку времени
                  </button>
                </p>
              )}
              {plan && (
                <>
                  {plan.gaps.length > 0 && (
                    <p className="po-notice" role="alert">
                      Такого местного времени нет из-за перевода часов:{' '}
                      {plan.gaps.join(', ')}. Выберите существующее время.
                      Сохранение остановлено, автоматического сдвига не будет.
                    </p>
                  )}
                  {plan.invalidDates.length > 0 && (
                    <p className="po-notice" role="alert">
                      Закрытие должно быть после открытия:{' '}
                      {plan.invalidDates.map(dateLabel).join(', ')}. Проверьте
                      дни и часы.
                    </p>
                  )}
                  {plan.overlaps.length > 0 && (
                    <div className="cc-rules po-notice">
                      <p>
                        Время встречается дважды при переводе часов:{' '}
                        {plan.overlaps.join(', ')}.
                      </p>
                      <label>
                        Выбор времени при переводе часов
                        <select
                          value={overlapConfirmed ? 'first' : ''}
                          onChange={(event) => {
                            const consent =
                              event.target.value === 'first' && planKey
                                ? planKey
                                : '';
                            approvedPlan.current = consent;
                            setOverlapConsent(consent);
                          }}
                        >
                          <option value="">Выберите явно</option>
                          <option value="first">
                            Использовать первое вхождение времени
                          </option>
                        </select>
                      </label>
                      <p>
                        Действующий календарь поддерживает первое вхождение. Для
                        другого момента вернитесь и выберите часы вне
                        повторяющегося интервала.
                      </p>
                    </div>
                  )}
                  {plan.nights.length === 0 && !plan.gaps.length && (
                    <p>
                      В ближайшие 56 дней и сохранённые особые даты открытых
                      ночей нет.
                    </p>
                  )}
                  <ol className="cc-result">
                    {plan.nights.slice(0, 8).map((night) => (
                      <li key={night.businessDate}>
                        <strong>
                          {nightTitle(night.businessDate)} ·{' '}
                          {dateLabel(night.businessDate)}
                        </strong>
                        <NightInterval night={night} />
                        <span className="po-muted">
                          {sources[night.source]}
                        </span>
                      </li>
                    ))}
                  </ol>
                  {plan.nights.length > 8 && (
                    <p className="po-muted">
                      Показаны первые 8 из {plan.nights.length} предварительно
                      рассчитанных ночей.
                    </p>
                  )}
                </>
              )}
              <div className="cc-actions">
                <button
                  type="button"
                  className="ob-button"
                  disabled={command.blocked}
                  onClick={() => setConfirmation(null)}
                >
                  Вернуться к редактированию
                </button>
                <button
                  type="button"
                  className="po-primary"
                  disabled={
                    blocked ||
                    writeDenied ||
                    !plan ||
                    Boolean(
                      plan.gaps.length ||
                        plan.invalidDates.length ||
                        (plan.overlaps.length && !overlapConfirmed),
                    )
                  }
                  onClick={() => {
                    if (
                      blocked ||
                      writeDenied ||
                      !plan ||
                      plan.gaps.length ||
                      plan.invalidDates.length ||
                      (plan.overlaps.length && !overlapConfirmed)
                    )
                      return;
                    const source = confirmed.current;
                    const currentKey =
                      source && dispatchPlanKey(source, draft, confirmation);
                    if (
                      !authority.current ||
                      !currentKey ||
                      currentKey !== planKey ||
                      (plan.overlaps.length &&
                        approvedPlan.current !== currentKey)
                    ) {
                      setValidation(
                        'Расписание обновилось. Проверьте новый план и подтвердите время заново.',
                      );
                      refresh();
                      return;
                    }
                    const body = calendarBody(
                      base,
                      changes,
                      confirmation === 'materialize',
                    );
                    try {
                      issuedPlan.current = calendarCommandIntent(
                        base,
                        body,
                        currentKey,
                      );
                    } catch {
                      setValidation(
                        'Проверка исходной команды недоступна. Обновите расписание.',
                      );
                      return;
                    }
                    receiptState.current = undefined;
                    setReceiptStatus(undefined);
                    setReceiptNotice(undefined);
                    command.run(path, issuedPlan.current.body, accept, 'PATCH');
                  }}
                >
                  {confirmation === 'save'
                    ? 'Подтвердить расписание'
                    : 'Подтвердить формирование'}
                </button>
              </div>
            </section>
          )}
        </>
      )}
      {receiptNotice && (
        <p className="po-notice" role="status">
          {receiptNotice}
        </p>
      )}
      {command.unknown ? (
        <section className="po-notice" role="alert">
          <h2>Результат операции пока неизвестен</h2>
          <p>
            Ответ не получен. Проверка сохранённого результата не выполняет
            операцию повторно.
          </p>
          {Boolean(command.error) && <p>{errorMessage(command.error)}</p>}
          {command.resolving && (
            <p role="status" aria-busy="true">
              Проверяем результат…
            </p>
          )}
          {receiptStatus === 'indeterminate' && (
            <p>Пока нельзя подтвердить исход. Повторите проверку позже.</p>
          )}
          {receiptStatus === 'not_committed_at_snapshot' && (
            <p>
              На момент проверки сохранённый результат не найден. Исходный
              запрос ещё может завершиться; перед повтором проверьте время.
            </p>
          )}
          <button
            className="ob-button"
            disabled={command.resolving || command.saving}
            onClick={resolveUnknown}
          >
            Проверить результат операции
          </button>
          {receiptStatus === 'not_committed_at_snapshot' && (
            <button
              className="ob-button"
              disabled={command.resolving || command.saving}
              onClick={retry}
            >
              Повторить исходную команду
            </button>
          )}
        </section>
      ) : (
        !versionConflict && !receiptNotice && <CommandState command={command} />
      )}

      {writeUnavailable && !checking && (
        <button type="button" className="ob-button" onClick={command.reset}>
          Продолжить редактирование
        </button>
      )}
    </Shell>
  );
}
