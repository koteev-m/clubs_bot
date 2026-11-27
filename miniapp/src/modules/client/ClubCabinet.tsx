import { useCallback, useEffect, useRef, useState } from 'react';
import { useRemote } from './onboarding.hooks';
import { authorityLost, OnboardingError } from './onboarding.api';
import { businessRoleNames, ClubProfile } from './onboarding.types';
import { CalendarView, validCalendarView } from './calendar';
import type { ClientLayout } from './layout.api';
import { PreparationView } from './preparation.types';
import ClubPreparation from './ClubPreparation';
import ClubNightDesk from './ClubNightDesk';
import type { WorkspaceLocation } from './ClubNightWorkspaceViews';
import ClubSetup from './ClubSetup';
import BusinessOverview from './BusinessOverview';
import { RemoteState, Shell } from './OnboardingShared';
import {
  ClubRequestScopeProvider,
  createClubRequestScope,
} from './clubRequestScope';
import './cabinet.css';

type Section =
  | 'profile'
  | 'layout'
  | 'calendar'
  | 'booking-rules'
  | 'finance'
  | 'modules'
  | 'team'
  | 'managers'
  | 'booking-preview'
  | 'deposit-configuration'
  | 'operational-reports'
  | 'financial-report-access'
  | 'settings'
  | 'readiness'
  | 'club';
type Props = {
  credential: string;
  id: string;
  mode?: 'overview' | 'settings' | 'readiness' | 'choice';
  back: (clientId?: string) => void;
  leaveClub?: () => void;
  openClub?: (id: string) => void;
  workspaceMemory?: Map<string, WorkspaceLocation>;
  open: (section: Section) => void;
  reportDirty: (value: boolean) => void;
  reportPending: (value: boolean) => void;
};
function NextNight({
  credential,
  profile,
  open,
  showCurrent,
}: {
  credential: string;
  profile: ClubProfile;
  open: () => void;
  showCurrent: boolean;
}) {
  const [, setClockTick] = useState(0);
  const remote = useRemote<CalendarView>(
    credential,
    '/api/client/clubs/' + profile.id + '/calendar',
    undefined,
    {
      onSuccess: (view) => {
        if (
          !validCalendarView(view, profile.id) ||
          view.timezone !== profile.timezone ||
          !Array.isArray(view.nights) ||
          !Array.isArray(view.invalidDates) ||
          view.nights.some(
            (night) =>
              !/^\d{4}-\d{2}-\d{2}$/.test(night.businessDate) ||
              !Number.isFinite(Date.parse(night.opensAt)) ||
              !Number.isFinite(Date.parse(night.closesAt)),
          )
        )
          throw new OnboardingError(404, 'not_found');
      },
    },
  );
  const now = Date.now();
  const next = remote.data?.nights
    .filter((night) => Date.parse(night.closesAt) > now)
    .sort((a, b) => Date.parse(a.opensAt) - Date.parse(b.opensAt))[0];
  const current = next !== undefined && Date.parse(next.opensAt) <= now;
  const boundary = next && Date.parse(current ? next.closesAt : next.opensAt);
  useEffect(() => {
    if (!showCurrent || boundary === undefined) return;
    const timer = window.setTimeout(
      () => setClockTick((tick) => tick + 1),
      Math.min(Math.max(0, boundary - now), 60_000),
    );
    return () => window.clearTimeout(timer);
  }, [boundary, now, showCurrent]);
  const title =
    showCurrent && current
      ? 'Текущая ночь по расписанию'
      : 'Ближайшая клубная ночь';
  const dateTime = (date: string) =>
    new Intl.DateTimeFormat('ru-RU', {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      hour: '2-digit',
      minute: '2-digit',
      timeZone: profile.timezone,
    }).format(new Date(date));
  return (
    <section className="po-card ob-card" aria-label={title}>
      <h2>{title}</h2>
      {remote.loading ? (
        <p>Проверяем расписание…</p>
      ) : remote.error ? (
        <RemoteState {...remote} />
      ) : next ? (
        <>
          <p className="cm-status">
            Вечеринка {next.businessDate.split('-').reverse().join('.')}
          </p>
          <p>Открытие: {dateTime(next.opensAt)}</p>
          <p>Закрытие: {dateTime(next.closesAt)}</p>
          <p className="po-muted">
            {next.nightId
              ? 'Ночь сформирована'
              : 'По расписанию · ночь ещё не сформирована'}
          </p>
        </>
      ) : (
        <p>В предварительном расписании нет предстоящих ночей.</p>
      )}
      {Boolean(remote.data?.invalidDates.length) && (
        <p className="po-notice">
          Есть даты, для которых интервал не подтверждён. Откройте календарь.
        </p>
      )}
      <button type="button" className="ob-button" onClick={open}>
        Открыть календарь
      </button>
    </section>
  );
}

function HallOverview({
  credential,
  profile,
  open,
}: {
  credential: string;
  profile: ClubProfile;
  open: () => void;
}) {
  const remote = useRemote<ClientLayout>(
    credential,
    '/api/client/clubs/' + profile.id + '/layout',
    undefined,
    {
      onSuccess: (layout) => {
        if (
          layout.clubId !== profile.id ||
          layout.clientId !== profile.clientId ||
          !Array.isArray(layout.permissions) ||
          !layout.permissions.includes('client.club.read') ||
          !Array.isArray(layout.halls) ||
          !layout.halls.every(
            (hall) =>
              typeof hall?.id === 'string' &&
              typeof hall.name === 'string' &&
              Array.isArray(hall.tables) &&
              hall.tables.every(
                (table) =>
                  table?.hallId === hall.id &&
                  Number.isSafeInteger(table.capacity) &&
                  table.capacity > 0,
              ),
          )
        )
          throw new OnboardingError(503, 'invalid_response');
      },
    },
  );
  const halls = remote.data?.halls;
  return (
    <section className="po-card ob-card cm-halls" aria-label="Обзор залов">
      <h2>Обзор залов</h2>
      <RemoteState {...remote} />
      {halls &&
        (halls.length > 0 ? (
          <ul>
            {halls.map((hall) => (
              <li key={hall.id}>
                <strong>{hall.name}</strong>
                <span>Столов в настройках: {hall.tables.length}</span>
                <span>
                  Мест за этими столами:{' '}
                  {hall.tables.reduce(
                    (total, table) => total + table.capacity,
                    0,
                  )}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p>Залы ещё не добавлены.</p>
        ))}
      <button type="button" className="ob-button" onClick={open}>
        Открыть залы и столы
      </button>
    </section>
  );
}

function hasLimitedFloorSettings(profile: ClubProfile) {
  return profile.businessRole === 'FLOOR_MANAGER' &&
    !profile.permissions.includes('client.club.operations.team.manage');
}

function Settings({
  profile,
  open,
}: {
  profile: ClubProfile;
  open: Props['open'];
}) {
  const can = (permission: string) =>
    profile.permissions.includes('client.club.' + permission);
  const sections: {
    key: Section;
    title: string;
    hint: string;
    write: boolean;
  }[] = [
    {
      key: 'profile',
      title: 'Профиль',
      hint: 'Название, описание и часовой пояс',
      write:
        can('profile.write') &&
        profile.availableActions?.includes('update') === true,
    },
    {
      key: 'calendar',
      title: 'Расписание и ночи',
      hint: 'Предстоящие ночи, неделя и особые даты',
      write: can('calendar.write'),
    },
    {
      key: 'layout',
      title: 'Залы, зоны и столы',
      hint: 'Схемы залов и размещение столов',
      write: can('layout.write'),
    },
    {
      key: 'booking-rules',
      title: 'Правила бронирования',
      hint: 'Сохранённые условия для столов',
      write: can('booking_rules.write'),
    },
    ...(can('operations.deposit_categories.write')
      ? [{
          key: 'deposit-configuration' as const,
          title: 'Распределение депозита',
          hint: 'Основная и дополнительные категории клуба',
          write: true,
        }]
      : []),
    ...(can('finance.read')
      ? [
          {
            key: 'finance' as const,
            title: 'Финансовые шаблоны',
            hint: 'Только настройки, без денежных операций',
            write: can('finance.write'),
          },
        ]
      : []),
    ...(can('modules.read')
      ? [
          {
            key: 'modules' as const,
            title: 'Настройки модулей',
            hint: 'Доступные параметры клуба',
            write: can('modules.write'),
          },
        ]
      : []),
  ];
  const visible = hasLimitedFloorSettings(profile)
    ? sections.filter((section) => section.write || (section.key === 'calendar' && can('night.create')))
    : sections;
  if (visible.length === 0) return <p>Разделы настройки пока не делегированы.</p>;
  return (
    <nav className="cm-sections" aria-label="Разделы настройки">
      {visible.map((section) => (
        <button
          type="button"
          className="po-card ob-card cm-section"
          key={section.key}
          onClick={() => open(section.key)}
        >
          <strong>{section.title}</strong>
          <span>{section.hint}</span>
          <small>
            {section.write ? 'Просмотр и изменение' : 'Только просмотр'}
          </small>
          <span className="cm-chevron" aria-hidden="true">
            →
          </span>
        </button>
      ))}
    </nav>
  );
}
export default function ClubCabinet(props: Props) {
  return (
    <CabinetContext key={props.credential + ':' + props.id} {...props} />
  );
}
function CabinetContext(props: Props) {
  const [denial, setDenial] = useState<{ error: unknown }>();
  const { id, workspaceMemory } = props;
  const denied = useCallback((error: unknown) => {
    workspaceMemory?.delete(id);
    setDenial({ error });
  }, [id, workspaceMemory]);
  const [scope, setScope] = useState(() =>
    createClubRequestScope(props.credential, props.id, denied),
  );
  if (denial)
    return (
      <Shell
        title="Кабинет клуба"
        back={props.leaveClub ?? (() => props.back())}
        backLabel="Мои заведения"
      >
        <RemoteState
          loading={false}
          error={denial.error}
          refresh={() => {
            setScope(
              createClubRequestScope(props.credential, props.id, denied),
            );
            setDenial(undefined);
          }}
        />
      </Shell>
    );
  return (
    <ClubRequestScopeProvider value={scope}>
      <Cabinet key={props.mode} {...props} />
    </ClubRequestScopeProvider>
  );
}

function Cabinet({
  credential,
  id,
  mode = 'overview',
  back,
  leaveClub,
  openClub,
  workspaceMemory,
  open,
  reportDirty,
  reportPending,
}: Props) {
  const [pending, setPending] = useState(false);
  const dirty = useRef(false);
  const trackDirty = useCallback(
    (value: boolean) => {
      dirty.current = value;
      reportDirty(value);
    },
    [reportDirty],
  );
  const trackPending = useCallback(
    (value: boolean) => {
      setPending(value);
      reportPending(value);
    },
    [reportPending],
  );
  const canLeave = () =>
    !pending &&
    (!dirty.current ||
      window.confirm('Есть несохранённые изменения. Уйти без сохранения?'));
  const safeOpen: Props['open'] = (section) => {
    if (canLeave()) open(section);
  };
  const [preparation, setPreparation] = useState<PreparationView>();
  const [profile, setProfile] = useState<ClubProfile>();
  const client = useRef<string>();
  const remote = useRemote<ClubProfile>(
    credential,
    '/api/client/clubs/' + id + '/profile',
    undefined,
    {
      retainOnForeground: true,
      onError: (error) => {
        if (authorityLost(error)) {
          setProfile(undefined);
          setPreparation(undefined);
        }
      },
      onSuccess: (received) => {
        if (
          received.id !== id ||
          !/^[1-9][0-9]*$/.test(received.clientId) ||
          (client.current && received.clientId !== client.current) ||
          !Array.isArray(received.permissions) ||
          !received.permissions.includes('client.club.read')
        )
          throw new OnboardingError(404, 'not_found');
        client.current = received.clientId;
        setProfile(received);
      },
    },
  );
  const businessRole = profile?.businessRole;
  const clubManager = businessRole === 'CLUB_MANAGER';
  const floorManager = businessRole === 'FLOOR_MANAGER';
  const permits = (permission: string) => profile?.permissions.includes('client.club.operations.' + permission);
  const floorSettings = profile?.permissions.some((permission) => [
    'client.club.profile.write', 'client.club.layout.write', 'client.club.calendar.write',
    'client.club.night.create', 'client.club.booking_rules.write',
  ].includes(permission));
  const networkCanConfigure =
    businessRole === 'NETWORK_MANAGER' &&
    profile?.permissions.includes('client.club.profile.write');
  const owner = !businessRole && preparation?.canManage;
  const callbacks = {
    openProfile: () => safeOpen('profile'),
    openLayout: () => safeOpen('layout'),
    openFinance: () => safeOpen('finance'),
    openModules: () => safeOpen('modules'),
    openTeam: () => safeOpen('team'),
    openCalendar: () => safeOpen('calendar'),
    openBookingRules: () => safeOpen('booking-rules'),
    openBookingPreview: () => safeOpen('booking-preview'),
  };
  const deskBack = useRef<() => boolean>();
  if (profile && (mode === 'overview' || mode === 'choice') && permits('read')) {
    const menu = [
      ...(clubManager || floorSettings ? [{ label: 'Настройки клуба', run: () => safeOpen('settings') }] : []),
      ...(permits('team.manage') ? [{ label: 'Команда клуба', run: () => safeOpen('managers') }] : []),
      ...(permits('analytics.read') ? [{ label: 'Операционные показатели', run: () => safeOpen('operational-reports') }] : []),
      { label: 'Другие кабинеты', run: () => { if (canLeave()) (leaveClub ?? back)(); } },
    ];
    return (
      <Shell compact title={profile.name} backDisabled={pending} back={() => {
        if (!deskBack.current?.() && canLeave()) (leaveClub ?? back)();
      }}>
        <ClubNightDesk credential={credential} profile={profile} reportDirty={trackDirty}
          reportPending={trackPending} reportBack={(handler) => { deskBack.current = handler; }}
          openClub={openClub ? (clubId) => { if (canLeave()) openClub(clubId); } : undefined}
          menu={menu} workspaceMemory={workspaceMemory}
          financialReport={permits('financial_reports.read')
            ? <BusinessOverview credential={credential} profile={profile} financialOnly /> : undefined} />
      </Shell>
    );
  }
  return (
    <Shell
      title={
        mode === 'settings'
          ? 'Настройки клуба'
          : mode === 'readiness'
            ? 'Проверка готовности'
            : profile?.name || 'Кабинет клуба'
      }
      subtitle={
        businessRole && (mode === 'overview' || mode === 'choice')
          ? businessRoleNames[businessRole]
          : mode === 'overview'
            ? owner
              ? 'Контроль клуба'
              : 'Кабинет клуба'
            : profile?.name
      }
      back={() => {
        if (canLeave()) back(profile?.clientId);
      }}
      backDisabled={pending}
      backLabel={
        mode === 'overview' ? 'Организация и другие заведения' : 'Назад к клубу'
      }
    >
      <RemoteState {...remote} />
      {profile && (
        <>
          <div className="po-context">
            <span>
              {profile.clientName} · <strong>{profile.name}</strong>
            </span>
            {mode === 'overview' && !businessRole && (
              <span className="po-context-badge">
                {preparation
                  ? preparation.published
                    ? 'Опубликован'
                    : 'Не опубликован'
                  : 'Статус: проверка недоступна'}
              </span>
            )}
          </div>
          {(mode === 'overview' || mode === 'choice') && (
            <>
              {mode === 'choice' && !businessRole && preparation?.canManage && (
                <p className="po-notice">
                  {preparation.published
                    ? 'Клуб сохранён.'
                    : 'Клуб не опубликован.'}{' '}
                  Право собственности остаётся у вас. Выберите, кто займётся
                  подготовкой.
                </p>
              )}
              {!businessRole && (
                <ClubPreparation
                  credential={credential}
                  id={id}
                  clientId={profile.clientId}
                  reportDirty={trackDirty}
                  reportPending={trackPending}
                  openTeam={callbacks.openTeam}
                  openSettings={() => open('settings')}
                  openReadiness={() => safeOpen('readiness')}
                  choice={mode === 'choice'}
                  onChosen={() => open('club')}
                  onView={setPreparation}
                  externallyBlocked={
                    remote.loading ||
                    remote.revalidating ||
                    Boolean(remote.error)
                  }
                />
              )}
              {(mode === 'overview' || businessRole) && (
                <>
                  {permits('read') && (
                    <ClubNightDesk
                      credential={credential}
                      profile={profile}
                      reportDirty={trackDirty}
                      reportPending={trackPending}
                    />
                  )}
                  <div className="cm-actions">
                    {permits('financial_reports.read') && (
                    <BusinessOverview credential={credential} profile={profile} financialOnly />
                  )}
                  {!clubManager && (!floorManager || floorSettings) && (
                      <button
                        type="button"
                        className="ob-button"
                        disabled={pending}
                        onClick={() => safeOpen('settings')}
                      >
                        {businessRole === 'BUSINESS_OWNER' ||
                        (businessRole === 'NETWORK_MANAGER' &&
                          !networkCanConfigure)
                          ? 'Сохранённые настройки'
                          : owner
                            ? 'Открыть настройки клуба'
                            : 'Перейти к настройкам'}
                      </button>
                    )}
                    {businessRole &&
                      profile.permissions.includes(
                        'client.staff.assign.club_manager',
                      ) && (
                        <button
                          type="button"
                          className="ob-button"
                          onClick={() => safeOpen('managers')}
                        >
                          Управляющие
                        </button>
                      )}
                    {permits('team.manage') && (
                      <button type="button" className="ob-button" disabled={pending} onClick={() => safeOpen('managers')}>
                        Команда клуба
                      </button>
                    )}
                    {permits('analytics.read') && (
                      <button type="button" className="ob-button" disabled={pending} onClick={() => safeOpen('operational-reports')}>
                        Операционные показатели
                      </button>
                    )}
                    {businessRole === 'BUSINESS_OWNER' && (
                      <button type="button" className="ob-button" onClick={() => safeOpen('financial-report-access')}>
                        Допуск к финансовым итогам
                      </button>
                    )}
                    {profile.permissions.includes('client.club.team.manage') &&
                      owner && (
                        <button
                          type="button"
                          className="ob-button"
                          disabled={pending}
                          onClick={callbacks.openTeam}
                        >
                          Команда клуба
                        </button>
                      )}
                  </div>
                  {clubManager && (
                    <>
                      <NextNight
                        key={profile.id + ':' + profile.timezone}
                        credential={credential}
                        profile={profile}
                        open={callbacks.openCalendar}
                        showCurrent={true}
                      />
                      <HallOverview
                        credential={credential}
                        profile={profile}
                        open={callbacks.openLayout}
                      />
                    </>
                  )}
                  {(clubManager ||
                    networkCanConfigure ||
                    (!businessRole && preparation && !owner)) && (
                    <section aria-label="Настройка клуба">
                      <h2>Настройка клуба</h2>
                      <Settings profile={profile} open={safeOpen} />
                    </section>
                  )}
                  {profile.permissions.includes('client.club.reports.read') && (
                    <BusinessOverview
                      credential={credential}
                      profile={profile}
                    />
                  )}
                  {!clubManager && !floorManager && (
                    <NextNight
                      key={profile.id + ':' + profile.timezone}
                      credential={credential}
                      profile={profile}
                      open={callbacks.openCalendar}
                      showCurrent={false}
                    />
                  )}
                  {!floorManager && <section className="po-card ob-card">
                    <h2>Готовность клуба</h2>
                    {!profile.description.trim() && (
                      <p>В профиле клуба пока нет описания.</p>
                    )}
                    <button
                      type="button"
                      className="ob-button"
                      onClick={() => safeOpen('readiness')}
                    >
                      Открыть проверку готовности
                    </button>
                  </section>}
                </>
              )}
            </>
          )}
          {mode === 'settings' && (
            <>
              <p className="po-muted">
                Сохранённые значения и изменения доступны по вашим текущим
                разрешениям.
              </p>
              <Settings profile={profile} open={safeOpen} />
              {!floorManager && <button
                type="button"
                className="ob-button"
                onClick={() => safeOpen('readiness')}
              >
                Подробная проверка готовности
              </button>}
            </>
          )}
          {mode === 'readiness' && !floorManager && (
            <ClubSetup
              key={profile.clientId + ':' + id + ':' + profile.timezone}
              credential={credential}
              profile={profile}
              {...callbacks}
            />
          )}
        </>
      )}
    </Shell>
  );
}
