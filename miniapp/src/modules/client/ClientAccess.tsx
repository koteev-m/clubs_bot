import { useCallback, useEffect, useRef, useState } from 'react';
import { useInitData } from '../auth/hooks/useInitData';
import { useTelegram } from '../../app/providers/TelegramProvider';
import { authorityLost, OnboardingError } from './onboarding.api';
import ClubProfile from './ClubProfile';
import { scrubClientLaunchUrl } from './clientEntry';
import { useCommand, useRemote } from './onboarding.hooks';
import {
  ApplicationSummary,
  blankVenue,
  CommandState,
  RemoteState,
  Shell,
  useUnsavedChanges,
  VenueForm,
  venueFields,
} from './OnboardingShared';
import {
  applicationLabels,
  ClientSummary,
  ClubApplication,
  ClubProfile as Profile,
  Invitation,
  invitationLabels,
  OnboardingContext,
  Page,
  VenueFields,
} from './onboarding.types';

type Screen =
  | { type: 'home' }
  | { type: 'space'; id: string }
  | { type: 'application'; id: string }
  | { type: 'club'; id: string }
  | { type: 'new'; id: string; previous?: ClubApplication }
  | { type: 'invitation'; id?: string };
function savedScreen(): Screen {
  const params = new URLSearchParams(window.location.search);
  const id = params.get('resource');
  if (!id || !/^[1-9][0-9]*$/.test(id)) return { type: 'home' };
  if (params.get('screen') === 'client-space') return { type: 'space', id };
  if (params.get('screen') === 'application')
    return { type: 'application', id };
  if (params.get('screen') === 'invitation') return { type: 'invitation', id };
  if (params.get('screen') === 'club') return { type: 'club', id };
  return { type: 'home' };
}

export default function ClientAccess({
  initialToken,
  onTokenConsumed,
}: {
  initialToken: string | null;
  onTokenConsumed?: () => void;
}) {
  const { initData, refreshEpoch = 0 } = useInitData();
  useEffect(() => scrubClientLaunchUrl(), []);
  if (!initData)
    return (
      <Shell title="Нужна авторизация в Telegram">
        <p>Откройте мини-приложение заново из Telegram.</p>
      </Shell>
    );
  return (
    <ClientWorkspace
      key={refreshEpoch + ':' + initData}
      credential={initData}
      initialToken={initialToken}
      onTokenConsumed={onTokenConsumed}
    />
  );
}

function ClientWorkspace({
  credential,
  initialToken,
  onTokenConsumed,
}: {
  credential: string;
  initialToken: string | null;
  onTokenConsumed?: () => void;
}) {
  const [token, setToken] = useState(initialToken);
  const [screen, setScreen] = useState<Screen>(() =>
    token ? { type: 'invitation' } : savedScreen(),
  );
  const dirty = useRef(false);
  const position = useRef(
    Number.isSafeInteger(window.history.state?.clientPosition)
      ? Number(window.history.state.clientPosition)
      : 0,
  );
  const reportDirty = useCallback((value: boolean) => {
    dirty.current = value;
  }, []);
  const navigate = useCallback((next: Screen, replace = false) => {
    const url = new URL(window.location.href);
    url.searchParams.set('mode', 'client');
    url.searchParams.delete('screen');
    url.searchParams.delete('resource');
    if (next.type !== 'home' && next.type !== 'new') {
      url.searchParams.set(
        'screen',
        next.type === 'space' ? 'client-space' : next.type,
      );
      if (next.id) url.searchParams.set('resource', next.id);
    }
    if (!replace) position.current += 1;
    const state = { onboarding: true, clientPosition: position.current };
    if (replace) window.history.replaceState(state, '', url);
    else window.history.pushState(state, '', url);
    setScreen(next);
  }, []);
  useEffect(() => {
    window.history.replaceState(
      { onboarding: true, clientPosition: position.current },
      '',
      window.location.href,
    );
    const pop = (event: PopStateEvent) => {
      const nextPosition = Number.isSafeInteger(event.state?.clientPosition)
        ? Number(event.state.clientPosition)
        : 0;
      if (nextPosition === position.current) return;
      if (
        dirty.current &&
        !window.confirm('Есть несохранённые изменения. Уйти без сохранения?')
      ) {
        window.history.go(position.current - nextPosition);
        return;
      }
      position.current = nextPosition;
      dirty.current = false;
      setScreen(savedScreen());
    };
    window.addEventListener('popstate', pop);
    return () => window.removeEventListener('popstate', pop);
  }, []);
  const home = useCallback(() => {
    setToken(null);
    onTokenConsumed?.();
    navigate({ type: 'home' });
  }, [navigate, onTokenConsumed]);
  if (screen.type === 'invitation')
    return (
      <InvitationScreen
        credential={credential}
        token={token}
        id={screen.id}
        home={home}
        resolved={(id) => navigate({ type: 'invitation', id }, true)}
        claimed={() => {
          setToken(null);
          onTokenConsumed?.();
        }}
      />
    );
  if (screen.type === 'space')
    return (
      <ClientSpace
        key={screen.id}
        credential={credential}
        id={screen.id}
        back={home}
        navigate={navigate}
      />
    );
  if (screen.type === 'new')
    return (
      <NewApplication
        key={screen.id + ':' + (screen.previous?.id ?? '')}
        credential={credential}
        clientId={screen.id}
        previous={screen.previous}
        reportDirty={reportDirty}
        back={() => navigate({ type: 'space', id: screen.id })}
        saved={(application) =>
          navigate({ type: 'application', id: application.id })
        }
      />
    );
  if (screen.type === 'application')
    return (
      <ClientApplication
        key={screen.id}
        credential={credential}
        id={screen.id}
        back={home}
        navigate={navigate}
        reportDirty={reportDirty}
      />
    );
  if (screen.type === 'club')
    return (
      <ClubProfile
        key={screen.id}
        credential={credential}
        id={screen.id}
        home={home}
        reportDirty={reportDirty}
      />
    );
  return <ClientHome credential={credential} navigate={navigate} />;
}

function ClientHome({
  credential,
  navigate,
}: {
  credential: string;
  navigate: (screen: Screen) => void;
}) {
  const remote = useRemote<{ clients: ClientSummary[] }>(
    credential,
    '/api/client/context',
  );
  return (
    <Shell
      title="Мои клубы"
      subtitle="Ваши клиентские пространства и разрешённые заведения."
    >
      <RemoteState {...remote} />
      {remote.data?.clients.length === 0 && (
        <section className="po-card po-empty">
          <h2>Нет доступных клиентских пространств</h2>
          <p>
            Откройте приглашение или обратитесь к отправителю для подтверждения
            доступа.
          </p>
        </section>
      )}
      {remote.data?.clients.map((client) => (
        <button
          key={client.id}
          className="po-card ob-card ob-row"
          onClick={() => navigate({ type: 'space', id: client.id })}
        >
          <strong>{client.name}</strong>
          <small>
            {client.clubs?.length
              ? 'Открыть клубы и заявки'
              : 'Клубов пока нет'}
          </small>
        </button>
      ))}
      <button className="ob-button" onClick={remote.refresh}>
        Обновить кабинет
      </button>
      <p className="po-muted">
        Для повторного входа откройте «Мои клубы» в личном чате платформенного
        бота.
      </p>
    </Shell>
  );
}

function InvitationScreen({
  credential,
  token,
  id,
  home,
  resolved,
  claimed,
}: {
  credential: string;
  token: string | null;
  id?: string;
  home: () => void;
  resolved: (id: string) => void;
  claimed: () => void;
}) {
  const webApp = useTelegram();
  const remote = useRemote<Invitation>(
    credential,
    token ? '/api/client/invitations/resolve' : '/api/client/invitations/' + id,
    token ? { token } : undefined,
  );
  const command = useCommand<Invitation>(credential);
  const [current, setCurrent] = useState<Invitation>();
  const invitation = current;
  const [confirmed, setConfirmed] = useState(false);
  useEffect(() => {
    if (authorityLost(command.error) || remote.error) setCurrent(undefined);
    else if (remote.data) setCurrent(remote.data);
  }, [remote.data, remote.error, command.error]);
  useEffect(() => {
    if (remote.data && id !== remote.data.id) resolved(remote.data.id);
  }, [id, remote.data, resolved]);
  const refresh = () => {
    setCurrent(undefined);
    remote.refresh();
  };
  return (
    <Shell title="Приглашение клиента" back={home}>
      <RemoteState {...remote} />
      {invitation && (
        <>
          <div className="po-card ob-card">
            <h2>{invitation.clientName}</h2>
            <p>{invitationLabels[invitation.status]}</p>
            <p className="po-muted">
              Действует до{' '}
              {new Date(invitation.expiresAt).toLocaleString('ru-RU')}
            </p>
          </div>
          {invitation.status === 'issued' && token && (
            <>
              <p>
                Подтвердите текущий Telegram-аккаунт. Доступ к кабинету выдаст
                владелец платформы после сверки.
              </p>
              <label className="ob-check">
                <input
                  type="checkbox"
                  checked={confirmed}
                  onChange={(event) => setConfirmed(event.target.checked)}
                />
                <span>
                  Подтверждаю свой аккаунт
                  {webApp.initDataUnsafe?.user?.first_name
                    ? ': ' + webApp.initDataUnsafe.user.first_name
                    : ''}
                </span>
              </label>
              <button
                className="po-primary"
                disabled={!confirmed || command.blocked}
                onClick={() =>
                  command.run(
                    '/api/client/invitations/' + invitation.id + '/claim',
                    { token, expectedVersion: invitation.version },
                    (result) => {
                      setCurrent(result);
                      claimed();
                    },
                  )
                }
              >
                Подтвердить аккаунт
              </button>
            </>
          )}
          {invitation.status === 'claimed' && (
            <section className="po-card ob-card">
              <h2>Ожидаем подтверждение платформы</h2>
              <p>
                Сообщите этот одноразовый код отправителю в исходном личном
                чате:
              </p>
              <p className="ob-code">{invitation.verificationCode}</p>
              <p className="po-muted">
                Код нужен для сверки человека. Сам по себе он не предоставляет
                доступ.
              </p>
            </section>
          )}
          {invitation.status === 'accepted' && (
            <button className="po-primary" onClick={home}>
              Открыть «Мои клубы»
            </button>
          )}
          {(invitation.status === 'revoked' ||
            invitation.status === 'expired') && (
            <p className="po-notice">
              Обратитесь к отправителю за новым приглашением.
            </p>
          )}
          <button
            className="ob-button"
            disabled={command.saving}
            onClick={refresh}
          >
            Обновить статус
          </button>
        </>
      )}
      <CommandState command={command} refresh={refresh} />
    </Shell>
  );
}

function ClientSpace({
  credential,
  id,
  back,
  navigate,
}: {
  credential: string;
  id: string;
  back: () => void;
  navigate: (screen: Screen) => void;
}) {
  const remote = useRemote<OnboardingContext>(
    credential,
    '/api/client/clients/' + id + '/onboarding',
  );
  return (
    <Shell title={remote.data?.name ?? 'Клиентское пространство'} back={back}>
      <RemoteState {...remote} />
      {remote.data && (
        <>
          <section className="ob-section">
            <h2>Мои клубы</h2>
            {remote.data.clubs.length === 0 && (
              <p className="po-card ob-card">Клубов пока нет</p>
            )}
            {remote.data.clubs.map((club) => (
              <button
                key={club.id}
                className="po-card ob-card ob-row"
                onClick={() => navigate({ type: 'club', id: club.id })}
              >
                <strong>{club.name}</strong>
                <small>Открыть клуб</small>
              </button>
            ))}
          </section>
          {remote.data.permissions.includes('client.application.manage') && (
            <>
              <button
                className="po-primary"
                onClick={() => navigate({ type: 'new', id })}
              >
                Подключить заведение
              </button>
              <ApplicationList
                credential={credential}
                path={'/api/client/clients/' + id + '/applications/search'}
                open={(app) => navigate({ type: 'application', id: app.id })}
              />
            </>
          )}
        </>
      )}
    </Shell>
  );
}

export function ApplicationList({
  credential,
  path,
  open,
  owner = false,
}: {
  credential: string;
  path: string;
  open: (application: ClubApplication) => void;
  owner?: boolean;
}) {
  const [cursor, setCursor] = useState<string>();
  const remote = useRemote<Page<ClubApplication>>(credential, path, {
    limit: 50,
    ...(cursor ? { cursor } : {}),
  });
  return (
    <section className="ob-section">
      <h2>{owner ? 'Очередь заявок' : 'Заявки на подключение'}</h2>
      <RemoteState {...remote} />
      {remote.data?.items.length === 0 && (
        <p className="po-card ob-card">Заявок пока нет</p>
      )}
      {remote.data?.items.map((application) => (
        <button
          className="po-card ob-card ob-row"
          key={application.id}
          onClick={() => open(application)}
        >
          <strong>{application.venueName || 'Черновик без названия'}</strong>
          <small>
            {applicationLabels[application.status]} · {application.clientName}
          </small>
        </button>
      ))}
      {cursor && (
        <button className="ob-button" onClick={() => setCursor(undefined)}>
          В начало списка
        </button>
      )}
      {remote.data?.nextCursor && (
        <button
          className="ob-button"
          onClick={() => setCursor(remote.data?.nextCursor ?? undefined)}
        >
          Следующая страница
        </button>
      )}
    </section>
  );
}

function NewApplication({
  credential,
  clientId,
  previous,
  back,
  saved,
  reportDirty,
}: {
  credential: string;
  clientId: string;
  previous?: ClubApplication;
  back: () => void;
  saved: (application: ClubApplication) => void;
  reportDirty: (dirty: boolean) => void;
}) {
  const initial = previous ? venueFields(previous) : blankVenue;
  const [fields, setFields] = useState<VenueFields>(initial);
  const command = useCommand<ClubApplication>(credential);
  const leave = useUnsavedChanges(
    JSON.stringify(fields) !== JSON.stringify(initial),
    reportDirty,
  );
  return (
    <Shell
      title="Подключить заведение"
      subtitle="Сохраните черновик, проверьте данные и отправьте заявку. Отправленные сведения нельзя изменить."
      back={() => {
        if (leave()) back();
      }}
    >
      {previous && (
        <p className="po-notice">
          Новая заявка связана с предыдущей №{previous.id}.
        </p>
      )}
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          command.run(
            '/api/client/clients/' + clientId + '/applications',
            {
              ...fields,
              ...(previous ? { previousApplicationId: previous.id } : {}),
            },
            saved,
          );
        }}
      >
        <VenueForm
          value={fields}
          onChange={setFields}
          disabled={command.blocked}
        />
        <button
          className="po-primary"
          type="submit"
          formNoValidate
          disabled={command.blocked}
        >
          Сохранить черновик
        </button>
      </form>
      <CommandState command={command} />
    </Shell>
  );
}

function ClientApplication({
  credential,
  id,
  back,
  navigate,
  reportDirty,
}: {
  credential: string;
  id: string;
  back: () => void;
  navigate: (screen: Screen) => void;
  reportDirty: (dirty: boolean) => void;
}) {
  const remote = useRemote<ClubApplication>(
    credential,
    '/api/client/applications/' + id,
  );
  const [application, setApplication] = useState<ClubApplication>();
  const [fields, setFields] = useState<VenueFields>();
  const command = useCommand<ClubApplication>(credential);
  const createClub = useCommand<Profile>(credential);
  const createConflict =
    createClub.error instanceof OnboardingError &&
    createClub.error.status === 409;
  const reload = remote.refresh;
  const [submitConfirmed, setSubmitConfirmed] = useState(false);
  useEffect(() => {
    if (
      authorityLost(command.error) ||
      authorityLost(createClub.error) ||
      remote.error
    ) {
      setApplication(undefined);
      setFields(undefined);
    } else if (remote.data) {
      setApplication(remote.data);
      setFields((value) => value ?? venueFields(remote.data!));
    }
  }, [remote.data, remote.error, command.error, createClub.error]);
  useEffect(() => {
    if (createConflict) reload();
  }, [createConflict, reload]);
  const dirty = Boolean(
    application &&
      fields &&
      JSON.stringify(venueFields(application)) !== JSON.stringify(fields),
  );
  const leave = useUnsavedChanges(dirty || createClub.unknown, reportDirty);
  const accept = (result: ClubApplication) => {
    setApplication(result);
    setFields(venueFields(result));
    setSubmitConfirmed(false);
  };
  return (
    <Shell
      title={application?.venueName || 'Заявка на подключение'}
      back={() => {
        if (leave()) back();
      }}
    >
      <RemoteState {...remote} />
      {application && (
        <>
          {application.status === 'draft' &&
          fields &&
          application.availableActions.includes('update') ? (
            <form
              noValidate
              onSubmit={(event) => {
                event.preventDefault();
                command.run(
                  '/api/client/applications/' + id,
                  { ...fields, expectedVersion: application.version },
                  accept,
                  'PATCH',
                );
              }}
            >
              <p className="po-context">Черновик · {application.clientName}</p>
              <VenueForm
                value={fields}
                onChange={setFields}
                disabled={command.blocked}
              />
              <button
                className="po-primary"
                type="submit"
                formNoValidate
                disabled={command.blocked || !dirty}
              >
                Сохранить черновик
              </button>
            </form>
          ) : (
            <ApplicationSummary application={application} />
          )}
          {application.status === 'approved' &&
            application.availableActions.includes('createClub') &&
            !application.createdClubId && (
              <button
                className="po-primary"
                disabled={createClub.blocked || command.blocked}
                onClick={() =>
                  createClub.run(
                    '/api/client/applications/' + id + '/club',
                    { expectedVersion: application.version },
                    (club) => navigate({ type: 'club', id: club.id }),
                  )
                }
              >
                Создать клуб
              </button>
            )}
          {application.createdClubId &&
            application.availableActions.includes('openClub') && (
              <button
                className="po-primary"
                onClick={() =>
                  navigate({ type: 'club', id: application.createdClubId! })
                }
              >
                Открыть клуб
              </button>
            )}
          {application.availableActions.includes('submit') && (
            <>
              <label className="ob-check">
                <input
                  type="checkbox"
                  checked={submitConfirmed}
                  onChange={(event) => setSubmitConfirmed(event.target.checked)}
                />
                <span>
                  Данные верны. После отправки изменить заявку нельзя.
                </span>
              </label>
              {dirty && (
                <p className="po-muted">Сначала сохраните изменения.</p>
              )}
              <button
                className="po-primary"
                disabled={command.blocked || dirty || !submitConfirmed}
                onClick={() =>
                  command.run(
                    '/api/client/applications/' + id + '/submit',
                    { expectedVersion: application.version },
                    accept,
                  )
                }
              >
                Отправить заявку
              </button>
            </>
          )}
          {application.availableActions.includes('cancel') && (
            <button
              className="ob-button ob-danger"
              disabled={command.blocked}
              onClick={() => {
                if (
                  window.confirm('Отменить заявку? Это действие завершит её.')
                )
                  command.run(
                    '/api/client/applications/' + id + '/cancel',
                    { expectedVersion: application.version },
                    accept,
                  );
              }}
            >
              Отменить заявку
            </button>
          )}
          {(application.status === 'rejected' ||
            application.status === 'cancelled') && (
            <button
              className="po-primary"
              disabled={command.blocked}
              onClick={() =>
                navigate({
                  type: 'new',
                  id: application.clientId,
                  previous: application,
                })
              }
            >
              Подать заново
            </button>
          )}
          <button
            className="ob-button"
            disabled={command.saving}
            onClick={remote.refresh}
          >
            Обновить заявку
          </button>
        </>
      )}
      <CommandState command={command} refresh={remote.refresh} />
      <CommandState command={createClub} refresh={remote.refresh} />
    </Shell>
  );
}
