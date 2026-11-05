import { useCallback, useEffect, useState } from 'react';
import HallPlanViewer from '../admin/pages/HallPlanViewer';
import { dateLabel, localLabel, weekday } from './calendar';
import { ClientLayoutHall, createClientLayoutApi } from './layout.api';
import { useRemote } from './onboarding.hooks';
import { OnboardingError } from './onboarding.api';
import {
  availabilityReasons,
  type AvailabilitySnapshot,
  tableStatuses,
  validAvailabilityTables,
} from './tableAvailability';
import {
  Field,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from './OnboardingShared';

interface PreviewNight {
  nightId?: string | null;
  businessDate: string;
  opensAt: string;
  closesAt: string;
  cutoffAt: string;
  arrivalBy: string | null;
  eventStatus: keyof typeof eventStatus;
}

interface Preview {
  clubId: string;
  name: string;
  timezone: string;
  bookingBlocker: 'TABLE_MAPPING_REQUIRED' | 'GUEST_BOOKING_DISABLED';
  halls: ClientLayoutHall[];
  nights: PreviewNight[];
}

const eventStatus = {
  VERIFIED: 'Событие связано, время проверено.',
  UNLINKED: 'Для этой ночи ещё не связано событие.',
  INVALID_LINK: 'Связь с событием не подтверждает расписание этой ночи.',
  NOT_MATERIALIZED:
    'Ночь рассчитана по расписанию. Нажмите «Сформировать ближайшие ночи» в календаре.',
};

export default function GuestBookingPreview({
  credential,
  id,
  back,
  reportDirty,
}: {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (dirty: boolean) => void;
}) {
  const remote = useRemote<Preview>(
    credential,
    `/api/client/clubs/${id}/booking-preview`,
  );
  const [deniedView, setDeniedView] = useState<Preview>();
  const planDenied = Boolean(remote.data && deniedView === remote.data);
  const denyPlan = useCallback(() => setDeniedView(remote.data), [remote.data]);
  const view =
    !planDenied &&
    remote.data?.clubId === id &&
    (remote.data.bookingBlocker === 'TABLE_MAPPING_REQUIRED' ||
      remote.data.bookingBlocker === 'GUEST_BOOKING_DISABLED')
      ? remote.data
      : undefined;
  const [nightDate, setNightDate] = useState('');
  const [hallId, setHallId] = useState('');
  const [drafts, setDrafts] = useState<Record<string, number>>({});
  const leave = useUnsavedChanges(Object.keys(drafts).length > 0, reportDirty);
  const night =
    view?.nights.find((item) => item.businessDate === nightDate) ??
    view?.nights[0];
  const hall = view?.halls.find((item) => item.id === hallId) ?? view?.halls[0];
  const availability = useRemote<AvailabilitySnapshot>(
    credential,
    night?.nightId && hall
      ? `/api/client/clubs/${id}/booking-preview/nights/${night.nightId}/halls/${hall.id}/availability`
      : null,
  );
  const availabilityDenied =
    availability.error instanceof OnboardingError &&
    [401, 403, 404].includes(availability.error.status);
  useEffect(() => {
    if (availabilityDenied) denyPlan();
  }, [availabilityDenied, denyPlan]);
  const snapshot = availability.data;
  const matchingSnapshot =
    snapshot?.clubId === id &&
    snapshot.hallId === hall?.id &&
    snapshot.nightId === night?.nightId &&
    snapshot.businessDate === night?.businessDate;
  const validSnapshot =
    matchingSnapshot &&
    snapshot.layoutRevision === hall?.layoutRevision &&
    validAvailabilityTables(snapshot.tables) &&
    Number.isFinite(Date.parse(snapshot.sampledAt))
      ? snapshot
      : undefined;
  const refreshAvailability = () => {
    if (matchingSnapshot && snapshot.layoutRevision !== hall?.layoutRevision) {
      remote.refresh();
    }
    availability.refresh();
  };
  return (
    <Shell
      title="Гостевой предпросмотр"
      subtitle={view?.name}
      back={() => {
        if (leave()) back();
      }}
    >
      <RemoteState {...remote} />
      {planDenied && <p role="alert">Доступ к сохранённой схеме утрачен.</p>}
      {!planDenied && remote.data && !view && (
        <p role="alert">Не удалось подтвердить данные предпросмотра.</p>
      )}
      {(remote.error != null || planDenied) && (
        <button className="ob-button" type="button" onClick={remote.refresh}>
          Повторить загрузку
        </button>
      )}
      {view && !availabilityDenied && (
        <>
          <section className="po-card ob-card">
            <h2>Бронирование стола</h2>
            <p className="po-notice">
              Закрытый предпросмотр. Гостевое бронирование выключено.
              {view.bookingBlocker === 'TABLE_MAPPING_REQUIRED' &&
                ' Столы схемы требуют подключения.'}
            </p>
            <p>Политика первого запуска — подтверждение без онлайн-оплаты.</p>
            <p className="po-muted">Время клуба · {view.timezone}</p>
            {night ? (
              <>
                <Field label="Клубная ночь">
                  <select
                    value={night.businessDate}
                    onChange={(event) => setNightDate(event.target.value)}
                  >
                    {view.nights.map((item) => (
                      <option key={item.businessDate} value={item.businessDate}>
                        {dateLabel(item.businessDate)} ·{' '}
                        {weekday(item.businessDate)}
                      </option>
                    ))}
                  </select>
                </Field>
                <p>
                  Начало{' '}
                  <time dateTime={night.opensAt}>
                    {localLabel(night.opensAt)}
                  </time>
                </p>
                <p>
                  Окончание{' '}
                  <time dateTime={night.closesAt}>
                    {localLabel(night.closesAt)}
                  </time>
                </p>
                <p className="po-muted">
                  Приём бронирований по правилам до{' '}
                  <time dateTime={night.cutoffAt}>
                    {localLabel(night.cutoffAt)}
                  </time>
                </p>
                <p className="po-muted">{eventStatus[night.eventStatus]}</p>
              </>
            ) : (
              <p>Нет ближайших ночей. Добавьте ночь в календаре клуба.</p>
            )}
          </section>
          {hall ? (
            <section className="po-card ob-card">
              <Field label="Зал">
                <select
                  value={hall.id}
                  onChange={(event) => setHallId(event.target.value)}
                >
                  {view.halls.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              </Field>
              <div className="space-y-2" aria-label="Снимок занятости">
                {night?.nightId && (
                  <button
                    type="button"
                    className="ob-button"
                    onClick={refreshAvailability}
                  >
                    Обновить занятость
                  </button>
                )}
                {validSnapshot ? (
                  <p role="status">
                    Снимок на{' '}
                    <time dateTime={validSnapshot.sampledAt}>
                      {new Intl.DateTimeFormat('ru-RU', {
                        dateStyle: 'short',
                        timeStyle: 'medium',
                        timeZone: view.timezone,
                      }).format(new Date(validSnapshot.sampledAt))}
                    </time>
                    {' · '}
                    {view.timezone}
                  </p>
                ) : night?.nightId && availability.loading ? (
                  <p role="status">Загружаем занятость столов…</p>
                ) : (
                  <p role={night?.nightId ? 'alert' : undefined}>
                    {night?.nightId
                      ? 'Не удалось получить актуальный снимок. Обновите занятость.'
                      : 'Снимок доступен для сохранённой клубной ночи.'}
                  </p>
                )}
                <p>
                  Статусы показывают состояние на момент снимка и могут
                  измениться.
                </p>
                <p>
                  «Оформляют бронь (HOLD)» — стол временно удерживается на время
                  оформления. Это ещё не подтверждённая бронь. Предпросмотр не
                  создаёт удержание.
                </p>
                <p>
                  «Требуется настройка» — доступность не подтверждена. Такой
                  стол нельзя считать свободным; причина указана в карточке.
                </p>
                <ul
                  aria-label="Легенда занятости"
                  className="flex flex-wrap gap-2 text-sm"
                >
                  {Object.values(tableStatuses).map(
                    ({ label, symbol, color }) => (
                      <li
                        className={`rounded border px-2 ${color}`}
                        key={label}
                      >
                        <span aria-hidden="true">{symbol} · </span>
                        {label}
                      </li>
                    ),
                  )}
                </ul>
              </div>
              <PreviewHall
                key={hall.id + ':' + hall.layoutRevision}
                credential={credential}
                clubId={id}
                hall={hall}
                night={night}
                snapshot={validSnapshot}
                drafts={drafts}
                onGuests={(key, count) =>
                  setDrafts((previous) => ({ ...previous, [key]: count }))
                }
                onDenied={denyPlan}
              />
            </section>
          ) : (
            <p className="po-card ob-card">
              Нет сохранённых залов. Добавьте зал в «Залы и столы».
            </p>
          )}
          <section className="po-card ob-card">
            <h2>Мои брони</h2>
            <p>В режиме предпросмотра история броней недоступна.</p>
          </section>
        </>
      )}
    </Shell>
  );
}

function PreviewHall({
  credential,
  clubId,
  hall,
  night,
  snapshot,
  drafts,
  onGuests,
  onDenied,
}: {
  credential: string;
  clubId: string;
  hall: ClientLayoutHall;
  night?: PreviewNight;
  snapshot?: AvailabilitySnapshot;
  drafts: Record<string, number>;
  onGuests: (key: string, guests: number) => void;
  onDenied: () => void;
}) {
  const [plan, setPlan] = useState<{ url?: string; failed?: boolean }>({});
  const [attempt, retryPlan] = useState(0);
  const hasPlan = Boolean(hall.plan);
  const hallId = hall.id;
  useEffect(() => {
    if (!hasPlan) return;
    setPlan({});
    const controller = new AbortController();
    const api = createClientLayoutApi(
      credential,
      clubId,
      () => undefined,
      onDenied,
    );
    let url: string | undefined;
    void api.editor
      .fetchHallPlanBlob(clubId, hallId, undefined, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        if (result.blob) {
          url = URL.createObjectURL(result.blob);
          setPlan({ url });
        } else {
          setPlan({ failed: true });
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) setPlan({ failed: true });
      });
    return () => {
      controller.abort();
      api.dispose();
      if (url) URL.revokeObjectURL(url);
    };
  }, [credential, clubId, hallId, hasPlan, attempt, onDenied]);
  if (hasPlan && !plan.url && !plan.failed) {
    return <p role="status">Загружаем схему…</p>;
  }
  const byTable = new Map(snapshot?.tables.map((entry) => [entry.id, entry]));
  return (
    <>
      {!hasPlan && (
        <p className="po-muted">
          Для зала пока нет изображения. Столы доступны в списке.
        </p>
      )}
      {plan.failed && (
        <div role="alert">
          <p>Изображение схемы недоступно.</p>
          <button
            className="ob-button"
            type="button"
            onClick={() => retryPlan((value) => value + 1)}
          >
            Повторить загрузку схемы
          </button>
        </div>
      )}
      <HallPlanViewer
        planUrl={plan.url}
        tables={hall.tables}
        introduction="Выберите сохранённый стол на схеме или в списке."
        tableBadge={(table) => {
          const status = byTable.get(table.id)?.status;
          return status && Object.hasOwn(tableStatuses, status)
            ? tableStatuses[status]
            : tableStatuses.UNKNOWN;
        }}
        tableDetails={(table) => {
          const state = byTable.get(table.id);
          const reason = state?.reason;
          const knownReason =
            reason && Object.hasOwn(availabilityReasons, reason);
          const key = `${night?.businessDate ?? ''}:${hall.id}:${table.id}`;
          return (
            <>
              {knownReason && <p>{availabilityReasons[reason]}</p>}
              {(!state || state.status === 'UNKNOWN') && !knownReason && (
                <p>
                  {!night?.nightId
                    ? 'Для выбранной ночи нет сохранённого номера. Сформируйте ближайшие ночи в календаре.'
                    : !snapshot
                      ? 'Нет актуального подтверждённого снимка занятости. Нажмите «Обновить занятость».'
                      : 'Снимок не подтвердил состояние этого стола. Обновите занятость и проверьте его подключение.'}
                </p>
              )}
              {state?.status === 'HOLD' && (
                <p>
                  Другой гость оформляет бронь. Стол временно удерживается до
                  завершения оформления или истечения срока HOLD.
                </p>
              )}
              {state?.status === 'UNAVAILABLE' && !knownReason && (
                <p>
                  Стол недоступен в выбранную ночь. Обновите занятость и
                  проверьте настройки зала, стола и ночи.
                </p>
              )}
              <p>
                {table.bookingLinked
                  ? 'Связан со столом бронирования'
                  : 'Требует подключения'}
              </p>
              {(table.x === null || table.y === null) && (
                <p>Стол пока не размещён на схеме.</p>
              )}
              <p>
                Прибытие по правилам клуба{' '}
                {night?.arrivalBy ? (
                  <time dateTime={night.arrivalBy}>
                    {localLabel(night.arrivalBy)}
                  </time>
                ) : (
                  'пока не задано для выбранной ночи.'
                )}
              </p>
              <label className="flex flex-col gap-1 text-sm text-gray-800">
                <span>Количество гостей</span>
                <input
                  className="min-h-[44px] rounded border border-gray-400 bg-white px-3 text-gray-900"
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={table.capacity}
                  value={Math.min(drafts[key] ?? 1, table.capacity)}
                  onChange={(event) => {
                    const count = event.target.valueAsNumber;
                    if (
                      Number.isInteger(count) &&
                      count >= 1 &&
                      count <= table.capacity
                    )
                      onGuests(key, count);
                  }}
                />
              </label>
              <button
                className="min-h-[44px] rounded border border-gray-400 bg-gray-200 px-3 py-2 text-sm text-gray-700"
                type="button"
                disabled
              >
                Удержать стол
              </button>
            </>
          );
        }}
      />
    </>
  );
}
