import { useCallback, useEffect, useRef, useState } from 'react';
import { useInitData } from '../auth/hooks/useInitData';
import NewClub, { blankNewVenue } from './NewClub';
import { authorityLost } from './onboarding.api';
import ClubProfile from './ClubProfile';
import ClubLayout from './ClubLayout';
import { scrubClientLaunchUrl } from './clientEntry';
import { useCommand, useRemote } from './onboarding.hooks';
import {
  ApplicationSummary,
  CommandState,
  RemoteState,
  Shell,
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
  NewVenueFields,
} from './onboarding.types';

type Screen =
  | { type: 'home' }
  | { type: 'space'; id: string }
  | { type: 'application'; id: string }
  | { type: 'club'; id: string }
  | { type: 'layout'; id: string; hallId?: string }
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
  if (params.get('screen') === 'new-club') return { type: 'new', id };
  if (params.get('screen') === 'layout') {
    const hallId = params.get('hall');
    return {
      type: 'layout',
      id,
      ...(hallId && /^[1-9][0-9]*$/.test(hallId) ? { hallId } : {}),
    };
  }
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
  const { initData } = useInitData();
  const lastCredential = useRef(initData);
  if (initData || !document.hidden) lastCredential.current = initData;
  const [revoked, setRevoked] = useState<string | null>(null);
  useEffect(() => {
    const lost = (event: Event) => {
      const credential = (event as CustomEvent<string>).detail;
      if (credential === lastCredential.current) setRevoked(credential);
    };
    window.addEventListener('client-authority-lost', lost);
    return () => window.removeEventListener('client-authority-lost', lost);
  }, []);
  const credential = initData || lastCredential.current;
  useEffect(() => scrubClientLaunchUrl(), []);
  if (!credential || revoked === credential)
    return (
      <Shell title="Нужна авторизация в Telegram">
        <p>Откройте мини-приложение заново из Telegram.</p>
      </Shell>
    );
  return (
    <div hidden={!initData}>
      <ClientWorkspace
        key={credential}
        credential={credential}
        initialToken={initialToken}
        onTokenConsumed={onTokenConsumed}
      />
    </div>
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
  const [draftClient, setDraftClient] = useState(
    screen.type === 'new' ? screen.id : '',
  );
  const [draft, setDraft] = useState<NewVenueFields>(blankNewVenue);
  const [previous, setPrevious] = useState<ClubApplication>();
  const createClub = useCommand<Profile>(credential);
  const invitationCommand = useCommand<Invitation>(credential);
  const drafts = useRef(
    new Map<string, { fields: NewVenueFields; previous?: ClubApplication }>(),
  );
  if (draftClient) drafts.current.set(draftClient, { fields: draft, previous });
  const pendingClient = useRef('');
  pendingClient.current = createClub.blocked ? draftClient : '';
  const dirty = useRef(false);
  const layoutPending = useRef(false);
  const reportLayoutPending = useCallback((value: boolean) => {
    layoutPending.current = value;
  }, []);
  const position = useRef(
    Number.isSafeInteger(window.history.state?.clientPosition)
      ? Number(window.history.state.clientPosition)
      : 0,
  );
  const reportDirty = useCallback((value: boolean) => {
    dirty.current = value;
  }, []);
  const navigate = useCallback(
    (next: Screen, replace = false) => {
      if (
        next.type === 'new' &&
        createClub.blocked &&
        draftClient &&
        next.id !== draftClient
      )
        next = { type: 'new', id: draftClient };
      const url = new URL(window.location.href);
      url.searchParams.set('mode', 'client');
      url.searchParams.delete('screen');
      url.searchParams.delete('resource');
      url.searchParams.delete('hall');
      if (next.type === 'layout' && next.hallId)
        url.searchParams.set('hall', next.hallId);
      if (next.type !== 'home') {
        url.searchParams.set(
          'screen',
          next.type === 'space'
            ? 'client-space'
            : next.type === 'new'
              ? 'new-club'
              : next.type,
        );
        if (next.id) url.searchParams.set('resource', next.id);
      }
      if (!replace) position.current += 1;
      const state = { onboarding: true, clientPosition: position.current };
      if (replace) window.history.replaceState(state, '', url);
      else window.history.pushState(state, '', url);
      if (
        next.type === 'new' &&
        (next.id !== draftClient ||
          (next.previous && next.previous.id !== previous?.id)) &&
        !createClub.blocked
      ) {
        setDraftClient(next.id);
        setDraft(
          next.previous
            ? {
                venueName: next.previous.venueName,
                countryCode: next.previous.countryCode,
                cityId: '',
                address: [next.previous.address, next.previous.unit]
                  .filter(Boolean)
                  .join(', '),
              }
            : (drafts.current.get(next.id)?.fields ?? blankNewVenue),
        );
        setPrevious(next.previous ?? drafts.current.get(next.id)?.previous);
      }
      setScreen(next);
    },
    [draftClient, createClub.blocked, previous?.id],
  );
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
      if (layoutPending.current) {
        window.history.go(position.current - nextPosition);
        return;
      }
      if (
        dirty.current &&
        !window.confirm('Есть несохранённые изменения. Уйти без сохранения?')
      ) {
        window.history.go(position.current - nextPosition);
        return;
      }
      position.current = nextPosition;
      dirty.current = false;
      let next = savedScreen();
      if (next.type === 'new') {
        if (pendingClient.current && next.id !== pendingClient.current) {
          next = { type: 'new', id: pendingClient.current };
          const url = new URL(window.location.href);
          url.searchParams.set('resource', next.id);
          window.history.replaceState(event.state, '', url);
        }
        const retained = drafts.current.get(next.id);
        setDraftClient(next.id);
        setDraft(retained?.fields ?? blankNewVenue);
        setPrevious(retained?.previous);
      }
      setScreen(next);
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
        command={invitationCommand}
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
      <NewClub
        credential={credential}
        clientId={screen.id}
        value={draft}
        onChange={setDraft}
        previous={previous}
        command={createClub}
        back={() => navigate({ type: 'space', id: screen.id })}
        saved={(club) => {
          drafts.current.delete(screen.id);
          setDraft(blankNewVenue);
          setDraftClient('');
          setPrevious(undefined);
          navigate({ type: 'club', id: club.id });
        }}
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
  if (screen.type === 'layout')
    return (
      <ClubLayout
        key={screen.id}
        credential={credential}
        id={screen.id}
        hallId={screen.hallId}
        back={() => navigate({ type: 'club', id: screen.id })}
        openHall={(hallId) =>
          navigate({ type: 'layout', id: screen.id, hallId })
        }
        reportDirty={reportDirty}
        reportPending={reportLayoutPending}
      />
    );
  if (screen.type === 'club')
    return (
      <ClubProfile
        key={screen.id}
        credential={credential}
        id={screen.id}
        home={(clientId) =>
          clientId ? navigate({ type: 'space', id: clientId }) : home()
        }
        reportDirty={reportDirty}
        openLayout={() => navigate({ type: 'layout', id: screen.id })}
      />
    );
  return <ClientHome credential={credential} navigate={navigate} />;
}

function ClientHome({
  credential,
  navigate,
}: {
  credential: string;
  navigate: (screen: Screen, replace?: boolean) => void;
}) {
  const remote = useRemote<{ clients: ClientSummary[] }>(
    credential,
    '/api/client/context',
  );
  useEffect(() => {
    if (remote.data?.clients.length === 1)
      navigate({ type: 'space', id: remote.data.clients[0].id }, true);
  }, [remote.data, navigate]);
  return (
    <Shell
      title="Мои заведения"
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
            {client.clubs?.length ? 'Открыть заведения' : 'Клубов пока нет'}
          </small>
        </button>
      ))}
      <button className="ob-button" onClick={remote.refresh}>
        Обновить кабинет
      </button>
      <p className="po-muted">
        Для повторного входа используйте Mini App или кнопку «Открыть кабинет» в
        меню бота.
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
  command,
}: {
  credential: string;
  token: string | null;
  id?: string;
  home: () => void;
  resolved: (id: string) => void;
  claimed: () => void;
  command: ReturnType<typeof useCommand<Invitation>>;
}) {
  const remote = useRemote<Invitation>(
    credential,
    token ? '/api/client/invitations/resolve' : '/api/client/invitations/' + id,
    token ? { token } : undefined,
  );
  const [current, setCurrent] = useState<Invitation>();
  const invitation = current;
  useEffect(() => {
    if (authorityLost(command.error) || remote.error) setCurrent(undefined);
    else if (remote.data) setCurrent(remote.data);
  }, [remote.data, remote.error, command.error]);
  useEffect(() => {
    if (remote.data && id !== remote.data.id) resolved(remote.data.id);
  }, [id, remote.data, resolved]);
  useEffect(() => {
    if (
      remote.data?.status === 'accepted' &&
      remote.data.acceptanceMode === 'direct'
    )
      home();
  }, [remote.data, home]);
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
                {invitation.acceptanceMode === 'direct'
                  ? 'Примите приглашение, чтобы открыть свой кабинет.'
                  : 'Это прежнее приглашение: после принятия потребуется подтверждение владельца платформы.'}
              </p>
              <button
                className="po-primary"
                disabled={command.blocked}
                onClick={() =>
                  command.run(
                    '/api/client/invitations/' + invitation.id + '/claim',
                    { token, expectedVersion: invitation.version },
                    (result) => {
                      setCurrent(result);
                      claimed();
                      if (
                        result.status === 'accepted' &&
                        result.acceptanceMode === 'direct'
                      )
                        home();
                    },
                  )
                }
              >
                {command.saving ? 'Принимаем…' : 'Принять приглашение'}
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
              Открыть кабинет
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
    <Shell
      title={remote.data?.name ?? 'Клиентское пространство'}
      subtitle="Организация · клиентское пространство"
      back={back}
    >
      <RemoteState {...remote} />
      {remote.data && (
        <>
          <section className="po-card ob-card">
            <p>Разрешено всего: {remote.data.allowedClubs ?? 'не настроено'}</p>
            <p>
              Создано: {remote.data.createdClubs ?? remote.data.clubs.length}
            </p>
            <p>Можно добавить: {remote.data.availableClubs ?? 0}</p>
            {remote.data.allowedClubs == null && (
              <p>
                Для нового заведения владелец платформы должен установить лимит.
                Существующие заведения доступны.
              </p>
            )}
            {remote.data.allowedClubs != null &&
              remote.data.availableClubs === 0 && (
                <p>
                  Для дополнительного заведения согласуйте увеличение лимита с
                  владельцем платформы.
                </p>
              )}
          </section>
          <section className="ob-section">
            <h2>Мои заведения</h2>
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
                disabled={!remote.data.availableClubs}
                onClick={() => navigate({ type: 'new', id })}
              >
                Создать заведение
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
      <h2>{owner ? 'История заявок' : 'Сохранённые заявки'}</h2>
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

function ClientApplication({
  credential,
  id,
  back,
  navigate,
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
  return (
    <Shell title="Сохранённая заявка" back={back}>
      <RemoteState {...remote} />
      {remote.data && (
        <>
          <ApplicationSummary application={remote.data} />
          <p className="po-notice">
            Заявка сохранена как история. Новые заведения создаются в пределах
            лимита клиента.
          </p>
          {remote.data.createdClubId &&
          remote.data.availableActions.includes('openClub') ? (
            <button
              className="po-primary"
              onClick={() =>
                navigate({ type: 'club', id: remote.data!.createdClubId! })
              }
            >
              Открыть клуб
            </button>
          ) : (
            <button
              className="po-primary"
              onClick={() =>
                navigate({
                  type: 'new',
                  id: remote.data!.clientId,
                  previous: remote.data!,
                })
              }
            >
              Использовать данные для создания
            </button>
          )}
        </>
      )}
    </Shell>
  );
}
