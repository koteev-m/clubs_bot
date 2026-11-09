import { useCallback, useRef, useState } from 'react';
import { useRemote } from './onboarding.hooks';
import { authorityLost, OnboardingError } from './onboarding.api';
import { ClubProfile } from './onboarding.types';
import { CalendarView, validCalendarView } from './calendar';
import { PreparationView } from './preparation.types';
import ClubPreparation from './ClubPreparation';
import ClubSetup from './ClubSetup';
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
  | 'booking-preview'
  | 'settings'
  | 'readiness'
  | 'club';
type Props = {
  credential: string;
  id: string;
  mode?: 'overview' | 'settings' | 'readiness' | 'choice';
  back: (clientId?: string) => void;
  leaveClub?: () => void;
  open: (section: Section) => void;
  reportDirty: (value: boolean) => void;
  reportPending: (value: boolean) => void;
};
function NextNight({
  credential,
  profile,
  open,
}: {
  credential: string;
  profile: ClubProfile;
  open: () => void;
}) {
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
  const next = remote.data?.nights
    .filter((night) => Date.parse(night.closesAt) > Date.now())
    .sort((a, b) => Date.parse(a.opensAt) - Date.parse(b.opensAt))[0];
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
    <section className="po-card ob-card" aria-label="Ближайшая клубная ночь">
      <h2>Ближайшая клубная ночь</h2>
      {remote.loading ? (
        <p>Проверяем расписание…</p>
      ) : remote.error ? (
        <p>Проверка недоступна.</p>
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
        <p>Данных пока нет. Настройте расписание клуба.</p>
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
      write: can('profile.write'),
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
  return (
    <nav className="cm-sections" aria-label="Разделы настройки">
      {sections.map((section) => (
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
  const denied = useCallback((error: unknown) => setDenial({ error }), []);
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
  const owner = preparation?.canManage;
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
        mode === 'overview'
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
        mode === 'overview'
          ? 'Организация и другие заведения'
          : 'Назад к клубу'
      }
    >
      <RemoteState {...remote} />
      {profile && (
        <>
          <div className="po-context">
            <span>
              {profile.clientName} · <strong>{profile.name}</strong>
            </span>
            {mode === 'overview' && (
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
              {mode === 'choice' && preparation?.canManage && (
                <p className="po-notice">
                  {preparation.published
                    ? 'Клуб сохранён.'
                    : 'Клуб не опубликован.'}{' '}
                  Право собственности остаётся у вас. Выберите, кто займётся
                  подготовкой.
                </p>
              )}
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
              {mode === 'overview' && (
                <>
                  <div className="cm-actions">
                    <button
                      type="button"
                      className="ob-button"
                      disabled={pending}
                      onClick={() => safeOpen('settings')}
                    >
                      {owner
                        ? 'Открыть настройки клуба'
                        : 'Перейти к настройкам'}
                    </button>
                    {profile.permissions.includes(
                      'client.club.team.manage',
                    ) &&
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
                  {preparation && !owner && (
                    <section aria-label="Задачи по конфигурации">
                      <h2>Настройка клуба</h2>
                      <p className="po-muted">
                        Открывайте разделы, проверяйте сохранённые значения и
                        выполняйте доступные настройки.
                      </p>
                      <Settings profile={profile} open={safeOpen} />
                    </section>
                  )}
                  <NextNight
                    key={profile.id + ':' + profile.timezone}
                    credential={credential}
                    profile={profile}
                    open={callbacks.openCalendar}
                  />
                  <section className="po-card ob-card">
                    <h2>Показатели и отчёты</h2>
                    <p>Данных пока нет.</p>
                    <p className="po-muted">
                      Подтверждённые отчёты о работе клуба в этом кабинете
                      пока не подключены.
                    </p>
                  </section>
                  <section className="po-card ob-card">
                    <h2>Требует внимания</h2>
                    <p>
                      {!profile.description.trim()
                        ? 'Добавьте описание клуба.'
                        : 'Проверьте сохранённые настройки перед началом работы.'}
                    </p>
                    <p className="po-muted">
                      Полный список проверок и незавершённых пунктов доступен
                      отдельно.
                    </p>
                    <button
                      type="button"
                      className="ob-button"
                      onClick={() => safeOpen('readiness')}
                    >
                      Открыть проверку готовности
                    </button>
                  </section>
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
              <button
                type="button"
                className="ob-button"
                onClick={() => safeOpen('readiness')}
              >
                Подробная проверка готовности
              </button>
            </>
          )}
          {mode === 'readiness' && (
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
