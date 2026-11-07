import { useEffect, useState, type ReactNode } from 'react';
import type { BookingRulesView } from './ClubBookingRules';
import type { Preview } from './GuestBookingPreview';
import type { CalendarView } from './calendar';
import type { ClientLayout, ClientLayoutTable } from './layout.api';
import { authorityLost, OnboardingError } from './onboarding.api';
import { useRemote } from './onboarding.hooks';
import type { ClubProfile } from './onboarding.types';
import { availabilityReasons } from './tableAvailability';

const configured = 'Настроено';
const needsSetup = 'Требует настройки';
const disconnected = 'Не подключено';
const unverified = 'Состояние пока невозможно проверить';
const pending = 'Проверяем…';

type Action = { label: string; open: () => void };
type Check = {
  status: string;
  detail: ReactNode;
  action?: Action;
};
type Remote<T> = { data?: T; loading: boolean; error?: unknown };

function unavailable<T>(remote: Remote<T>): Check {
  if (remote.loading)
    return { status: pending, detail: 'Загружаем данные клуба.' };
  return {
    status: unverified,
    detail: authorityLost(remote.error)
      ? 'Сведения недоступны с текущими правами.'
      : 'Сервер не подтвердил данные для этого клуба.',
  };
}

function validNightId(id: string | null | undefined) {
  return typeof id === 'string' && /^[1-9][0-9]*$/.test(id);
}

type NightWindow = {
  businessDate: string;
  opensAt: string;
  closesAt: string;
  nightId?: string | null;
};

function sameNights(
  expected: NightWindow[],
  actual: NightWindow[],
  checkIds = false,
) {
  if (expected.length !== actual.length) return false;
  const byDate = new Map(actual.map((night) => [night.businessDate, night]));
  const expectedDates = new Set(expected.map((night) => night.businessDate));
  return (
    byDate.size === actual.length &&
    expectedDates.size === expected.length &&
    expected.every((night) => {
      const other = byDate.get(night.businessDate);
      return (
        other &&
        Date.parse(night.opensAt) === Date.parse(other.opensAt) &&
        Date.parse(night.closesAt) === Date.parse(other.closesAt) &&
        (!checkIds || (night.nightId ?? null) === (other.nightId ?? null))
      );
    })
  );
}

function isPlaced(table: ClientLayoutTable) {
  return [table.x, table.y].every(
    (value) => typeof value === 'number' && value >= 0 && value <= 1,
  );
}

function Row({ title, check }: { title: string; check: Check }) {
  return (
    <li className="ob-setup-row">
      <h3>{title}</h3>
      <p>
        <strong>{check.status}</strong>
      </p>
      <div className="po-muted">{check.detail}</div>
      {check.action && (
        <button type="button" className="ob-button" onClick={check.action.open}>
          {check.action.label}
        </button>
      )}
    </li>
  );
}

export default function ClubSetup({
  credential,
  profile,
  openProfile,
  openLayout,
  openCalendar,
  openBookingRules,
  openBookingPreview,
}: {
  credential: string;
  profile: ClubProfile;
  openProfile?: () => void;
  openLayout?: () => void;
  openCalendar?: () => void;
  openBookingRules?: () => void;
  openBookingPreview?: () => void;
}) {
  const base = '/api/client/clubs/' + profile.id;
  const options = { retainOnForeground: true };
  const layoutRead = useRemote<ClientLayout>(
    credential,
    base + '/layout',
    undefined,
    options,
  );
  const calendarRead = useRemote<CalendarView>(
    credential,
    base + '/calendar',
    undefined,
    options,
  );
  const rulesRead = useRemote<BookingRulesView>(
    credential,
    base + '/booking-rules',
    undefined,
    options,
  );
  const previewRead = useRemote<Preview>(
    credential,
    profile.canPreviewBooking ? base + '/booking-preview' : null,
    undefined,
    options,
  );

  const layout =
    layoutRead.data?.clubId === profile.id &&
    layoutRead.data.clientId === profile.clientId &&
    layoutRead.data.permissions?.includes('client.club.read') &&
    Array.isArray(layoutRead.data.halls)
      ? layoutRead.data
      : undefined;
  const calendar =
    calendarRead.data?.clubId === profile.id &&
    calendarRead.data.timezone === profile.timezone &&
    Array.isArray(calendarRead.data.nights) &&
    Array.isArray(calendarRead.data.invalidDates)
      ? calendarRead.data
      : undefined;
  const rules =
    rulesRead.data?.clubId === profile.id &&
    rulesRead.data.timezone === profile.timezone &&
    /^[1-9][0-9]*$/.test(rulesRead.data.version) &&
    Array.isArray(rulesRead.data.nights)
      ? rulesRead.data
      : undefined;
  const preview =
    previewRead.data?.clubId === profile.id &&
    previewRead.data.timezone === profile.timezone &&
    Array.isArray(previewRead.data.nights) &&
    ['GUEST_BOOKING_DISABLED', 'TABLE_MAPPING_REQUIRED'].includes(
      previewRead.data.bookingBlocker,
    )
      ? previewRead.data
      : undefined;

  const previewRefused =
    authorityLost(previewRead.error) ||
    Boolean(previewRead.data && previewRead.data.clubId !== profile.id);
  const [previewBlocked, setPreviewBlocked] = useState(false);
  useEffect(() => {
    if (previewRefused) setPreviewBlocked(true);
    else if (preview) setPreviewBlocked(false);
  }, [previewRefused, preview]);
  const previewError = previewRead.error;
  const previewTemporarilyUnavailable =
    previewError instanceof OnboardingError &&
    (previewError.status === 0 || previewError.status >= 500);
  const calendarRulesMatch = Boolean(
    calendar && rules && sameNights(calendar.nights, rules.nights),
  );
  const calendarPreviewMatches = Boolean(
    calendar && preview && sameNights(calendar.nights, preview.nights, true),
  );
  const snapshotsDisagree = Boolean(
    (calendar && rules && !calendarRulesMatch) ||
      (calendar && preview && !calendarPreviewMatches),
  );
  const invalidNightData = Boolean(
    (calendarRead.data && !calendar) ||
      (rulesRead.data && !rules) ||
      (previewRead.data && !preview),
  );
  const nightReads = [
    calendarRead,
    rulesRead,
    ...(profile.canPreviewBooking ? [previewRead] : []),
  ];
  const nightReadsPending = nightReads.some(
    ({ loading, revalidating }) => loading || revalidating,
  );
  const checkAgain = () => {
    calendarRead.refresh();
    rulesRead.refresh();
    previewRead.refresh();
  };

  const missingProfile = [
    ['название', profile.name],
    ['страну', profile.countryCode],
    ['город', profile.city],
    ['адрес', profile.address],
    ['часовой пояс', profile.timezone],
  ]
    .filter(([, value]) => !value?.trim())
    .map(([label]) => label);
  const profileCheck: Check = {
    status: missingProfile.length ? needsSetup : configured,
    detail: missingProfile.length
      ? 'Проверьте ' + missingProfile.join(', ') + '.'
      : 'Название, местоположение и часовой пояс сохранены.',
    action: openProfile
      ? { label: 'Редактировать профиль', open: openProfile }
      : undefined,
  };

  const halls = layout?.halls ?? [];
  const tables = halls.flatMap((hall) => hall.tables);
  const plans = halls.filter((hall) => Boolean(hall.plan)).length;
  const placements = halls.reduce(
    (total, hall) =>
      total + (hall.plan ? hall.tables.filter(isPlaced).length : 0),
    0,
  );
  const layoutCheck: Check = layout
    ? {
        status:
          halls.length > 0 && plans === halls.length ? configured : needsSetup,
        detail: halls.length ? (
          <ul>
            {halls.map((hall) => (
              <li key={hall.id}>
                {hall.name} ·{' '}
                {hall.plan ? 'Схема сохранена' : 'Схема не загружена'}
              </li>
            ))}
          </ul>
        ) : (
          'Добавьте зал и загрузите его схему.'
        ),
      }
    : unavailable(layoutRead);
  const tablesCheck: Check = layout
    ? {
        status:
          halls.length > 0 &&
          halls.every(
            (hall) =>
              hall.plan &&
              hall.tables.length > 0 &&
              hall.tables.every(isPlaced),
          )
            ? configured
            : needsSetup,
        detail: tables.length ? (
          <>
            <p>
              На схемах размещено столов: {placements} из {tables.length}.
            </p>
            <ul>
              {halls.map((hall) => (
                <li key={hall.id}>
                  {hall.name} ·{' '}
                  {hall.tables.length === 0
                    ? 'Нет столов'
                    : hall.plan
                      ? 'Без размещения: ' +
                        hall.tables.filter((table) => !isPlaced(table)).length
                      : 'Загрузите схему для размещения столов'}
                </li>
              ))}
            </ul>
          </>
        ) : (
          'Добавьте физические столы и разместите их на схемах.'
        ),
      }
    : unavailable(layoutRead);

  const materialized =
    calendar?.nights.filter((night) => validNightId(night.nightId)).length ?? 0;
  const calendarCheck: Check = calendar
    ? {
        status: snapshotsDisagree
          ? unverified
          : nightReadsPending
            ? pending
            : materialized > 0 &&
                materialized === calendar.nights.length &&
                calendar.invalidDates.length === 0
              ? configured
              : needsSetup,
        detail: (
          <>
            <p>
              Сформировано ближайших ночей: {materialized} из{' '}
              {calendar.nights.length}.
            </p>
            {calendar.nights.length === 0 ? (
              <p>
                В ближайшем расписании нет ночей. Добавьте рабочие дни и часы.
              </p>
            ) : materialized < calendar.nights.length ? (
              <p>Сформируйте рассчитанные ночи в календаре.</p>
            ) : null}
            {calendar.invalidDates.length > 0 && (
              <p>Для части дат нужно уточнить часы работы.</p>
            )}
          </>
        ),
      }
    : unavailable(calendarRead);

  const inconsistent =
    rules?.nights.filter(
      (night) =>
        night.cutoffAtOrBeforeOpen ||
        night.arrivalAtOrAfterClose ||
        night.cutoffAfterArrival,
    ).length ?? 0;
  const rulesCheck: Check = rules
    ? {
        status: snapshotsDisagree
          ? unverified
          : nightReadsPending
            ? pending
            : rules.version === '1' || inconsistent > 0
              ? needsSetup
              : !calendarRulesMatch || rules.nights.length === 0
                ? unverified
                : configured,
        detail: (
          <>
            <p>
              {rules.version === '1'
                ? 'Действуют исходные значения. Проверьте и сохраните правила клуба.'
                : 'Индивидуальные правила сохранены.'}
            </p>
            {inconsistent > 0 && (
              <p>
                Для {inconsistent} ночей проверьте время окончания приёма броней
                и прибытия.
              </p>
            )}
          </>
        ),
      }
    : unavailable(rulesRead);

  const tableIssues = [
    'TABLE_UNLINKED',
    'TABLE_LINK_MISMATCH',
    'BOOKING_TABLE_INACTIVE',
    'LAYOUT_INACTIVE',
  ] as const;
  const tableCounts = tableIssues.map((reason) => ({
    reason,
    count: tables.filter((table) => table.bookingLinkStatus === reason).length,
  }));
  const activeLinks = tables.filter(
    (table) => table.bookingLinkStatus === 'ACTIVE_LINK',
  ).length;
  const unknownTables =
    tables.length -
    activeLinks -
    tableCounts.reduce((total, item) => total + item.count, 0);
  const tableLinks: Check = layout
    ? {
        status:
          tables.length === 0
            ? needsSetup
            : tables.every(
                  (table) => table.bookingLinkStatus === 'TABLE_UNLINKED',
                )
              ? disconnected
              : unknownTables > 0
                ? unverified
                : activeLinks === tables.length
                  ? configured
                  : needsSetup,
        detail: tables.length ? (
          <>
            <p>
              Активных подтверждённых связей: {activeLinks} из {tables.length}.
            </p>
            {tableCounts
              .filter(({ count }) => count > 0)
              .map(({ reason, count }) => (
                <p key={reason}>
                  {availabilityReasons[reason]} Столов: {count}.
                </p>
              ))}
            {unknownTables > 0 && (
              <p>Статус связи пока неизвестен для столов: {unknownTables}.</p>
            )}
            <p>
              Доступность стола зависит от выбранной ночи. Её можно проверить в
              закрытом предпросмотре, если он доступен.
            </p>
          </>
        ) : (
          'В залах клуба пока нет физических столов.'
        ),
      }
    : unavailable(layoutRead);

  const verifiedNights =
    preview?.nights.filter(
      (night) =>
        validNightId(night.nightId) && night.eventStatus === 'VERIFIED',
    ).length ?? 0;
  const unlinkedNights =
    preview?.nights.filter((night) => night.eventStatus === 'UNLINKED')
      .length ?? 0;
  const invalidLinks =
    preview?.nights.filter((night) => night.eventStatus === 'INVALID_LINK')
      .length ?? 0;
  const unsavedNights =
    preview?.nights.filter((night) => night.eventStatus === 'NOT_MATERIALIZED')
      .length ?? 0;
  const unknownNights =
    (preview?.nights.length ?? 0) -
    verifiedNights -
    unlinkedNights -
    invalidLinks -
    unsavedNights;
  const nightLinks: Check = preview
    ? {
        status: snapshotsDisagree
          ? unverified
          : nightReadsPending
            ? pending
            : !calendarPreviewMatches
              ? unverified
              : preview.nights.length === 0
                ? needsSetup
                : verifiedNights === preview.nights.length
                  ? configured
                  : unlinkedNights === preview.nights.length
                    ? disconnected
                    : unlinkedNights + invalidLinks + unsavedNights > 0
                      ? needsSetup
                      : unverified,
        detail:
          preview.nights.length === 0 ? (
            'Нет ближайших ночей для проверки связи с событиями.'
          ) : (
            <>
              <p>
                Связь и время события проверены: {verifiedNights} из{' '}
                {preview.nights.length}.
              </p>
              {unlinkedNights > 0 && (
                <p>Без связанного события: {unlinkedNights}.</p>
              )}
              {invalidLinks > 0 && (
                <p>Связь не подтверждает расписание ночи: {invalidLinks}.</p>
              )}
              {unsavedNights > 0 && (
                <p>Рассчитаны, но ещё не сформированы: {unsavedNights}.</p>
              )}
              {unknownNights > 0 && (
                <p>Не удалось проверить сведения для ночей: {unknownNights}.</p>
              )}
            </>
          ),
      }
    : profile.canPreviewBooking
      ? unavailable(previewRead)
      : {
          status: unverified,
          detail:
            'Проверка связей ночей доступна владельцу в закрытом предпросмотре.',
        };

  return (
    <section className="po-card ob-card" aria-labelledby="club-setup">
      <h2 id="club-setup">Подготовка клуба</h2>
      <p>
        Настройки доступны в закрытом кабинете. Гостевое бронирование выключено.
      </p>
      {(snapshotsDisagree ||
        invalidNightData ||
        Boolean(
          calendarRead.error ||
            rulesRead.error ||
            previewTemporarilyUnavailable,
        )) && (
        <div className="po-notice" role="status">
          <p>
            {snapshotsDisagree
              ? 'Ответы сервера о ближайших ночах расходятся. Повторите проверку.'
              : 'Не удалось проверить все сведения о ближайших ночах.'}
          </p>
          <button type="button" className="ob-button" onClick={checkAgain}>
            Повторить проверку ночей
          </button>
        </div>
      )}
      <ul className="ob-setup-list">
        <Row title="Профиль клуба" check={profileCheck} />
        <Row
          title="Залы и отдельные схемы"
          check={{
            ...layoutCheck,
            action:
              openLayout &&
              (!layoutRead.data || layout) &&
              !authorityLost(layoutRead.error)
                ? { label: 'Залы и столы', open: openLayout }
                : undefined,
          }}
        />
        <Row
          title="Столы и размещение"
          check={{
            ...tablesCheck,
            action:
              openLayout && halls.length > 0
                ? {
                    label: 'Открыть залы для работы со столами',
                    open: openLayout,
                  }
                : undefined,
          }}
        />
        <Row
          title="Календарь и сформированные ночи"
          check={{
            ...calendarCheck,
            action:
              openCalendar &&
              (!calendarRead.data || calendar) &&
              !authorityLost(calendarRead.error)
                ? { label: 'Календарь клуба', open: openCalendar }
                : undefined,
          }}
        />
        <Row
          title="Правила бронирования"
          check={{
            ...rulesCheck,
            action:
              openBookingRules &&
              (!rulesRead.data || rules) &&
              !authorityLost(rulesRead.error)
                ? { label: 'Правила бронирования', open: openBookingRules }
                : undefined,
          }}
        />
        <Row title="Связи столов для бронирования" check={tableLinks} />
        <Row
          title="Связи ночей с событиями"
          check={{
            ...nightLinks,
            action:
              openBookingPreview &&
              profile.canPreviewBooking &&
              !previewRefused &&
              !previewBlocked
                ? { label: 'Гостевой предпросмотр', open: openBookingPreview }
                : undefined,
          }}
        />
        <Row
          title="Отдельный бот клуба"
          check={{
            status: unverified,
            detail:
              'Кабинет пока не получает статус отдельного бота. Night Concierge открывает доступ к платформе.',
          }}
        />
      </ul>
    </section>
  );
}
