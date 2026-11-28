import InvitationDelivery, { invitationScope, invitationRole } from './InvitationDelivery';
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Building2, ClipboardList, LayoutGrid, Users } from 'lucide-react';
import { ApplicationList } from './ClientAccess';
import { useCommand, useRemote } from './onboarding.hooks';
import {
  ApplicationSummary,
  CommandState,
  Field,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from './OnboardingShared';
import {
  Club,
  ClientDetail,
  ClientSummary,
  ClubApplication,
  Invitation,
  invitationLabels,
  Membership,
  Page,
  PlatformClub,
} from './onboarding.types';
import { authorityLost, errorMessage } from './onboarding.api';
import { InvitationDraft, useInvitationDraft } from './useInvitationDraft';

const platformSections = [
  { type: 'overview', label: 'Обзор', Icon: LayoutGrid },
  { type: 'clients', label: 'Клиенты', Icon: Users },
  { type: 'clubs', label: 'Клубы', Icon: Building2 },
  { type: 'applications', label: 'Заявки', Icon: ClipboardList },
] as const;
const clientSections = [
  { label: 'Обзор', value: undefined },
  { label: 'Клубы', value: 'clubs' },
  { label: 'Люди и доступы', value: 'people' },
  { label: 'Приглашения', value: 'invitations' },
] as const;
type ClientSection = (typeof clientSections)[number]['value'];
type OwnerScreen =
  | { type: (typeof platformSections)[number]['type'] | 'invite' }
  | { type: 'invitation' | 'application'; id: string }
  | { type: 'client'; id: string; section?: ClientSection };
function currentScreen(): OwnerScreen {
  const params = new URLSearchParams(window.location.search);
  const type = params.get('screen');
  const id = params.get('resource');
  if (
    type === 'invite' ||
    type === 'clubs' ||
    type === 'clients' ||
    type === 'applications'
  )
    return { type };
  if (id && /^[1-9][0-9]*$/.test(id)) {
    if (type === 'client') {
      const section = clientSections.find(({ value }) => value === params.get('section'))?.value;
      return section ? { type, id, section } : { type, id };
    }
    if (type === 'invitation' || type === 'application') return { type, id };
  }
  return { type: 'overview' };
}

// Commands owned by a detail screen must survive until the server gives a
// definite result. Session-owned commands already survive Back and Forward.
const NavigationGuards = createContext<{ pending: Set<symbol>; drafts: Set<symbol> } | null>(null);

function useDetailDraft(dirty: boolean) {
  const guards = useContext(NavigationGuards);
  useUnsavedChanges(dirty);
  useLayoutEffect(() => {
    if (!guards || !dirty) return;
    const entry = Symbol();
    guards.drafts.add(entry);
    return () => { guards.drafts.delete(entry); };
  }, [guards, dirty]);
}

function useDetailCommand<T>(credential: string) {
  const command = useCommand<T>(credential);
  const pending = useContext(NavigationGuards)?.pending;
  useLayoutEffect(() => {
    if (!command.blocked || !pending) return;
    const entry = Symbol();
    pending.add(entry);
    const preventUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', preventUnload);
    return () => {
      pending.delete(entry);
      window.removeEventListener('beforeunload', preventUnload);
    };
  }, [pending, command.blocked]);
  return command;
}

export default function PlatformOnboarding({
  credential,
  invite,
}: {
  credential: string;
  invite?: InvitationDraft;
}) {
  const [guards] = useState(() => ({ pending: new Set<symbol>(), drafts: new Set<symbol>() }));
  return (
    <NavigationGuards.Provider value={guards}>
      {invite ? <PlatformNavigation credential={credential} invite={invite} /> :
        <PlatformSession key={credential} credential={credential} />}
    </NavigationGuards.Provider>
  );
}

function PlatformSession({ credential }: { credential: string }) {
  // The draft and original command survive route changes only in this authorized
  // component's memory. Changing credentials destroys the entire session.
  const invite = useInvitationDraft(credential);
  return <PlatformNavigation credential={credential} invite={invite} />;
}

function PlatformNavigation({
  credential,
  invite,
}: {
  credential: string;
  invite: ReturnType<typeof useInvitationDraft>;
}) {
  const [screen, setScreen] = useState<OwnerScreen>(currentScreen);
  const limitDraft = useClientLimitDraft(credential);
  const lifecycle = useClientLifecycle(credential);
  const returning = useRef(false);
  const guards = useContext(NavigationGuards);
  const canLeave = useCallback(() => !guards?.pending.size &&
    (!guards?.drafts.size || window.confirm('Есть несохранённые изменения. Уйти без сохранения?')), [guards]);
  const [receiptId, setReceiptId] = useState<string>();
  const depth = useRef<number>(window.history.state?.ownerTrail?.length ?? 0);
  const navigate = useCallback((next: OwnerScreen, replace = false) => {
    if (!canLeave()) return false;
    const current = currentScreen();
    if (!replace && JSON.stringify(current) === JSON.stringify(next)) return true;
    const url = new URL(window.location.href);
    url.searchParams.set('mode', 'platform-owner');
    if (next.type === 'overview') url.searchParams.delete('screen');
    else url.searchParams.set('screen', next.type);
    if ('id' in next) url.searchParams.set('resource', next.id);
    else url.searchParams.delete('resource');
    if (next.type === 'client' && next.section) url.searchParams.set('section', next.section);
    else url.searchParams.delete('section');
    const trail: string[] = window.history.state?.ownerTrail ?? [];
    const state = { onboarding: true, ownerTrail: replace ? trail : [...trail, JSON.stringify(current)] };
    if (replace) window.history.replaceState(state, '', url);
    else window.history.pushState(state, '', url);
    depth.current = state.ownerTrail.length;
    setScreen(next);
    return true;
  }, [canLeave]);
  useEffect(() => {
    const pop = () => {
      const target = window.history.state?.ownerTrail?.length ?? 0;
      if (!returning.current && target !== depth.current && !canLeave()) {
        window.history.go(depth.current - target);
        return;
      }
      depth.current = target;
      returning.current = false;
      setScreen(currentScreen());
    };
    window.addEventListener('popstate', pop);
    return () => window.removeEventListener('popstate', pop);
  }, [canLeave]);
  const overview = screen.type === 'overview';
  const returnTo = (parent: OwnerScreen) => {
    if (returning.current) return;
    const trail: string[] = window.history.state?.ownerTrail ?? [];
    const index = trail.lastIndexOf(JSON.stringify(parent));
    if (index < 0) navigate(parent, true);
    else if (canLeave()) {
      returning.current = true;
      window.history.go(index - trail.length);
    }
  };
  const home = () => returnTo({ type: 'overview' });
  const back = () => {
    if (screen.type === 'client') {
      const trail: string[] = window.history.state?.ownerTrail ?? [];
      const clients = trail.lastIndexOf(JSON.stringify({ type: 'clients' }));
      const clubs = trail.lastIndexOf(JSON.stringify({ type: 'clubs' }));
      returnTo({ type: clubs > clients ? 'clubs' : 'clients' });
    } else if (screen.type === 'invitation') returnTo({ type: 'clients' });
    else if (screen.type === 'application') returnTo({ type: 'applications' });
    else home();
  };
  const activeSection = screen.type === 'client' || screen.type === 'invitation' ? 'clients'
    : screen.type === 'application' ? 'applications'
      : screen.type === 'invite' ? 'overview' : screen.type;
  const onSection = (type: (typeof platformSections)[number]['type']) => {
    if (type === 'overview') home();
    else navigate({ type }, !overview && !('id' in screen) && screen.type !== 'invite');
  };
  const navigation = (
    <nav className="po-nav" aria-label="Навигация платформы">
      {platformSections.map(({ type, label, Icon }) => (
        <button key={type} type="button" aria-current={activeSection === type ? 'page' : undefined}
          onClick={() => onSection(type)}>
          <Icon size={21} aria-hidden="true" />
          <span>{label}</span>
        </button>
      ))}
    </nav>
  );
  const openInvitation = (invitation: Invitation) => {
    if (navigate({ type: 'invitation', id: invitation.id }, screen.type === 'invite' || screen.type === 'invitation')) {
      invite.forgetLink();
      setReceiptId(undefined);
    }
  };
  const openApplication = (application: ClubApplication) =>
    navigate({ type: 'application', id: application.id });
  useEffect(() => {
    if (screen.type === 'invite' && invite.invitation) {
      setReceiptId(invite.invitation.id);
      navigate({ type: 'invitation', id: invite.invitation.id }, true);
    }
  }, [screen.type, invite.invitation, navigate]);
  const forgetLink = invite.forgetLink;
  useEffect(() => {
    if (receiptId && screen.type !== 'invitation' && screen.type !== 'invite') {
      setReceiptId(undefined);
      forgetLink();
    }
  }, [screen.type, receiptId, forgetLink]);
  const receipt = screen.type === 'invitation' && screen.id === receiptId && invite.invitation?.id === screen.id
    ? invite.invitation : undefined;
  if (screen.type === 'invite' || receipt)
    return (
      <OwnerInvite
        draft={invite}
        navigation={navigation}
        back={receipt ? () => returnTo({ type: 'client', id: receipt.clientId, section: 'invitations' }) : back}
        open={openInvitation}
        another={() => {
          invite.reset();
          setReceiptId(undefined);
          navigate({ type: 'invite' }, true);
        }}
      />
    );
  if (screen.type === 'invitation')
    return (
      <OwnerInvitation
        key={screen.id}
        credential={credential}
        id={screen.id}
        navigation={navigation}
        back={(clientId) => clientId ? returnTo({ type: 'client', id: clientId, section: 'invitations' }) : back()}
        open={openInvitation}
      />
    );
  if (screen.type === 'client')
    return (
      <OwnerClient
        limitDraft={limitDraft}
        lifecycle={lifecycle}
        key={screen.id}
        credential={credential}
        id={screen.id}
        navigation={navigation}
        section={screen.section}
        select={(section) => {
          if (section !== screen.section) navigate(section ? { type: 'client', id: screen.id, section } :
            { type: 'client', id: screen.id }, true);
        }}
        back={back}
        open={openInvitation}
      />
    );
  if (screen.type === 'application')
    return (
      <OwnerApplication
        navigation={navigation}
        key={screen.id}
        credential={credential}
        id={screen.id}
        back={back}
      />
    );
  return (
    <Shell
      title={overview ? 'Управление платформой' : screen.type === 'clubs' ? 'Клубы платформы'
        : screen.type === 'applications' ? 'Заявки на подключение' : 'Клиенты'}
      owner
      back={overview ? undefined : back}
      navigation={navigation}
    >
      {overview && (
        <OwnerOverview credential={credential} inviteClient={() => {
          if (invite.invitation && !invite.command.blocked) {
            invite.reset();
            setReceiptId(undefined);
          }
          navigate({ type: 'invite' });
        }} />
      )}
      {screen.type === 'clubs' && (
        <OwnerClubs
          credential={credential}
          open={(id) => navigate({ type: 'client', id, section: 'clubs' })}
        />
      )}
      {screen.type === 'clients' && (
        <OwnerClients
          credential={credential}
          open={(client) => navigate({ type: 'client', id: client.id })}
        />
      )}
      {screen.type === 'applications' && (
        <ApplicationList
          credential={credential}
          path="/api/platform/onboarding/applications/search"
          owner
          open={openApplication}
        />
      )}
    </Shell>
  );
}

function OwnerInvite({
  another,
  draft,
  back,
  open,
  navigation,
}: {
  draft: ReturnType<typeof useInvitationDraft>;
  another: () => void;
  back: () => void;
  open: (invitation: Invitation) => void;
  navigation: ReactNode;
}) {
  const {
    clientName,
    setClientName,
    allowedClubs,
    setAllowedClubs,
    recipientLabel,
    setRecipientLabel,
    invitation,
    setInvitation,
    command,
  } = draft;
  return (
    <Shell
      title={invitation ? 'Приглашение клиента' : 'Пригласить клиента'}
      subtitle="Доступ к кабинету с разрешённым количеством заведений."
      owner
      back={back}
      navigation={navigation}
    >
      {!invitation ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            command.run(
              '/api/platform/onboarding/invitations',
              {
                clientName,
                recipientLabel,
                allowedClubs: Number(allowedClubs),
              },
              setInvitation,
            );
          }}
        >
          <p>
            Создайте ссылку, затем самостоятельно отправьте её получателю в
            Telegram.
          </p>
          <Field label="Название клиента или компании">
            <input
              required
              maxLength={255}
              placeholder="MIX Group"
              aria-describedby="invite-client-help"
              value={clientName}
              disabled={command.blocked}
              onChange={(event) => setClientName(event.target.value)}
            />
          </Field>
          <p id="invite-client-help" className="po-muted">
            Так клиент будет называться в вашей платформе. Это не название
            конкретного клуба.
          </p>
          <Field label="Имя получателя">
            <input
              required
              maxLength={255}
              placeholder="Иван Петров"
              aria-describedby="invite-recipient-help"
              value={recipientLabel}
              disabled={command.blocked}
              onChange={(event) => setRecipientLabel(event.target.value)}
            />
          </Field>
          <p id="invite-recipient-help" className="po-muted">
            Имя для вашего удобства. Telegram ID или ссылка на профиль не нужны.
          </p>
          <Field label="Разрешено заведений">
            <input
              type="number"
              min="0"
              step="1"
              required
              value={allowedClubs}
              disabled={command.blocked}
              onChange={(event) => setAllowedClubs(event.target.value)}
            />
          </Field>
          <p className="po-notice">
            Ссылка действует семь суток. Её фактический получатель сможет
            принять приглашение и открыть кабинет. Передавайте ссылку только
            нужному человеку. После присоединения подтвердите владельца бизнеса
            в карточке клиента, чтобы он мог создавать заведения.
          </p>
          <button
            type="submit"
            className="po-primary"
            disabled={
              command.blocked ||
              !clientName.trim() ||
              !recipientLabel.trim() ||
              !/^(0|[1-9][0-9]*)$/.test(allowedClubs)
            }
          >
            {command.saving ? 'Создаём приглашение…' : 'Создать ссылку'}
          </button>
        </form>
      ) : (
        <>
          <InvitationLink invitation={invitation} />
          <button className="po-primary" onClick={() => open(invitation)}>
            Открыть приглашение
          </button>
          <button
            type="button"
            className="ob-button"
            disabled={command.blocked}
            onClick={another}
          >
            Пригласить другого клиента
          </button>
        </>
      )}
      <InvitationCommandState command={command} />
    </Shell>
  );
}

function InvitationCommandState({
  command,
}: {
  command: ReturnType<typeof useCommand<Invitation>>;
}) {
  // Keep the retry control in place during the request: removing this tall
  // notice clamps mobile scroll position before the response arrives.
  return (
    <div className="ob-invitation-command" aria-live="polite">
      {(command.saving || command.unknown) && (
        <div className="po-notice">
          <div>
            <h2>
              {command.saving
                ? 'Ожидаем ответ сервера…'
                : 'Ответ сервера не получен, результат нужно проверить.'}
            </h2>
            <p>
              Проверка повторит ту же команду и не создаст второго клиента. Если
              запись сохранена, а ссылка потеряна, откройте приглашение и явно
              выпустите новую ссылку.
            </p>
            <button
              type="button"
              className="ob-button"
              disabled={command.saving}
              onClick={command.retry}
            >
              Проверить результат
            </button>
          </div>
        </div>
      )}
      {!command.unknown && Boolean(command.error) && (
        <div className="po-notice" role="alert">
          <div>
            <h2>Не удалось создать приглашение.</h2>
            <p>{errorMessage(command.error)}</p>
          </div>
        </div>
      )}
    </div>
  );
}

function InvitationLink({ invitation }: { invitation: Invitation }) {
  return (
    <InvitationDelivery key={invitation.id} link={invitation.invitationLink} prefix="ci"
      recipient={invitation.recipientLabel} role={invitationRole(invitation)}
      scope={invitationScope(invitation)} expiresAt={invitation.expiresAt}
      missingLinkHelp="Откройте приглашение и выпустите новую ссылку для этого клиента." />
  );
}

function OwnerInvitation({
  credential,
  id,
  back,
  open,
  navigation,
}: {
  credential: string;
  id: string;
  back: (clientId?: string) => void;
  open: (invitation: Invitation) => void;
  navigation: ReactNode;
}) {
  const remote = useRemote<Invitation>(
    credential,
    '/api/platform/onboarding/invitations/' + id,
  );
  const [invitation, setInvitation] = useState<Invitation>();
  const [confirmed, setConfirmed] = useState(false);
  const command = useDetailCommand<Invitation>(credential);
  useDetailDraft(Boolean(confirmed && invitation?.availableActions.includes('confirm')));
  useEffect(() => {
    if (authorityLost(command.error) || remote.error) setInvitation(undefined);
    else if (remote.data) setInvitation(remote.data);
  }, [remote.data, remote.error, command.error]);
  const action = (name: string) => {
    if (!invitation) return;
    command.run(
      '/api/platform/onboarding/invitations/' + id + '/' + name,
      { expectedVersion: invitation.version },
      (result) => {
        setInvitation(result);
        setConfirmed(false);
      },
    );
  };
  return (
    <Shell title="Приглашение клиента" owner back={() => back(invitation?.clientId)} backDisabled={command.blocked} navigation={navigation}>
      <RemoteState {...remote} />
      {invitation && (
        <>
          {invitation.id !== id ? (
            <>
              <InvitationLink invitation={invitation} />
              <button className="po-primary" onClick={() => open(invitation)}>
                Открыть новое приглашение
              </button>
            </>
          ) : (
            <>
              <section className="po-card ob-card">
                <h2>{invitation.clientName}</h2>
                <p>{invitation.recipientLabel}</p>
                <p>{invitationLabels[invitation.status]}</p>
                <p className="po-muted">
                  До {new Date(invitation.expiresAt).toLocaleString('ru-RU')}
                </p>
              </section>
              {invitation.candidate && (
                <section className="po-card ob-card">
                  <h2>Зафиксированный кандидат</h2>
                  <p>
                    {invitation.candidate.displayName ||
                      invitation.candidate.username ||
                      'Telegram-пользователь'}
                  </p>
                  <p className="po-muted">
                    Telegram ID: {invitation.candidate.telegramUserId}
                  </p>
                  {invitation.candidate.username && (
                    <p>@{invitation.candidate.username}</p>
                  )}
                  {invitation.verificationCode && (
                    <>
                      <p>Код для сверки в исходном личном чате</p>
                      <p className="ob-code">{invitation.verificationCode}</p>
                    </>
                  )}
                </section>
              )}
              {invitation.availableActions.includes('confirm') && (
                <>
                  <label className="ob-check">
                    <input
                      type="checkbox"
                      checked={confirmed}
                      onChange={(event) => setConfirmed(event.target.checked)}
                    />
                    <span>
                      Я сверил кандидата и код в исходном личном чате.
                    </span>
                  </label>
                  <button
                    className="po-primary"
                    disabled={!confirmed || command.blocked}
                    onClick={() => action('confirm')}
                  >
                    Предоставить доступ
                  </button>
                </>
              )}
              {invitation.availableActions.includes('revoke') && (
                <button
                  className="ob-button ob-danger"
                  disabled={command.blocked}
                  onClick={() => {
                    if (window.confirm('Отозвать это приглашение?'))
                      action('revoke');
                  }}
                >
                  Отозвать приглашение
                </button>
              )}
              {invitation.availableActions.includes('reissue') && (
                <button
                  className="ob-button"
                  disabled={command.blocked}
                  onClick={() => {
                    if (
                      window.confirm(
                        'Выпустить новую ссылку? Прежняя сразу перестанет действовать.',
                      )
                    )
                      action('reissue');
                  }}
                >
                  Выпустить новую ссылку
                </button>
              )}
            </>
          )}
          <button
            className="ob-button"
            disabled={command.saving}
            onClick={remote.refresh}
          >
            Обновить статус
          </button>
        </>
      )}
      <CommandState command={command} refresh={remote.refresh} />
    </Shell>
  );
}

function OwnerOverview({ credential, inviteClient }: { credential: string; inviteClient: () => void }) {
  const remote = useRemote<{
    clients: number;
    archivedClients: number;
    clubs: number;
    activeMembers: number;
    openInvitations: number;
  }>(credential, '/api/platform/onboarding/summary');
  return (
    <section className="po-owner-overview" aria-label="Сводка платформы">
      <button type="button" className="po-primary" onClick={inviteClient}>Пригласить клиента</button>
      <RemoteState {...remote} />
      {remote.data && (
        <dl className="po-stats">
          {[
            ['Организации вне архива', remote.data.clients],
            ['Заведения клиентов', remote.data.clubs],
            ['Активные пользовательские доступы', remote.data.activeMembers],
            ['Действующие приглашения', remote.data.openInvitations],
          ].map(([label, value]) => (
            <div className="po-card" key={label}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

function OwnerClubs({
  credential,
  open,
}: {
  credential: string;
  open: (id: string) => void;
}) {
  const [cursor, setCursor] = useState<string>();
  const remote = useRemote<Page<PlatformClub>>(
    credential,
    '/api/platform/onboarding/clubs/search',
    {
      limit: 50,
      ...(cursor ? { cursor } : {}),
    },
  );
  return (
    <section aria-label="Заведения платформы">
      <p className="po-muted">
        Здесь показана публикация для гостей. Готовность внутренних операций проверяется в рабочем кабинете клуба.
      </p>
      <RemoteState {...remote} />
      {remote.data?.items.length === 0 && (
        <p className="po-card ob-card">Заведений клиентов пока нет.</p>
      )}
      {remote.data?.items.map((club) => (
        <section className="po-card ob-card" key={club.id}>
          <h2>{club.name}</h2>
          <p>{club.clientName}</p>
          <div className="po-club-guest-status">
            <span className="po-muted">Для гостей</span>
            <strong>{club.published ? 'Опубликован' : 'Не опубликован'}</strong>
          </div>
          <p>
            Организация:{' '}
            {club.clientArchived
              ? 'В архиве'
              : club.clientActive
                ? 'Активна'
                : 'Ожидает подтверждения'}
          </p>
          <button className="ob-button" onClick={() => open(club.clientId)}>
            Клубы организации «{club.clientName}»
          </button>
        </section>
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

function organizationLabel(client: ClientSummary) {
  return client.archived
    ? 'Организация в архиве'
    : client.active
      ? 'Организация активна'
      : 'Организация ожидает подтверждения';
}

function OwnerClients({
  credential,
  open,
}: {
  credential: string;
  open: (client: ClientSummary) => void;
}) {
  const [cursor, setCursor] = useState<string>();
  const [status, setStatus] = useState('active');
  const remote = useRemote<Page<ClientSummary>>(
    credential,
    '/api/platform/onboarding/clients/search',
    { limit: 50, status, ...(cursor ? { cursor } : {}) },
  );
  return (
    <>
      <Field label="Список организаций">
        <select
          value={status}
          onChange={(event) => {
            setStatus(event.target.value);
            setCursor(undefined);
          }}
        >
          <option value="active">Вне архива</option>
          <option value="archived">В архиве</option>
          <option value="all">Все организации</option>
        </select>
      </Field>
      <p className="po-muted">
        Статус организации не означает доступ пользователя. Отзыв полномочий
        сохраняет организацию и её данные.
      </p>
      <RemoteState {...remote} />
      {remote.data?.items.length === 0 && (
        <p className="po-card ob-card">В этом списке организаций пока нет.</p>
      )}
      {remote.data?.items.map((client) => (
        <button
          key={client.id}
          className="po-card ob-card ob-row"
          onClick={() => open(client)}
        >
          <strong>{client.name}</strong>
          <small>{organizationLabel(client)}</small>
          {client.activeMembers !== undefined && (
            <small>Активных пользователей: {client.activeMembers}</small>
          )}
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
    </>
  );
}

function OwnerClient({
  credential,
  id,
  back,
  open,
  limitDraft,
  lifecycle,
  section,
  select,
  navigation,
}: {
  lifecycle: ReturnType<typeof useClientLifecycle>;
  limitDraft: ReturnType<typeof useClientLimitDraft>;
  credential: string;
  id: string;
  back: () => void;
  open: (invitation: Invitation) => void;
  section?: ClientSection;
  select: (section: ClientSection) => void;
  navigation: ReactNode;
}) {
  const remote = useRemote<ClientDetail>(
    credential,
    '/api/platform/onboarding/clients/' + id,
  );
  const client = remote.data;
  const sectionNavigation = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    sectionNavigation.current?.closest('main')?.scrollTo?.(0, 0);
  }, [section]);
  return (
    <Shell title={client?.name ?? 'Клиент'} owner back={back} navigation={navigation}>
      <RemoteState {...remote} />
      {client && (
        <>
          <nav ref={sectionNavigation} className="po-client-nav" aria-label="Разделы карточки клиента">
            {clientSections.map(({ label, value }) => (
              <button key={label} type="button" className="ob-button"
                aria-current={section === value ? 'page' : undefined} onClick={() => select(value)}>
                {label}
              </button>
            ))}
          </nav>
          {section === undefined && (
            <>
              <section className="po-card ob-card">
                <h2>{organizationLabel(client)}</h2>
                <p>Активных пользователей: {client.activeMembers ?? client.members.filter((member) => member.active).length}</p>
              </section>
              {!client.archived && <ClientLimit draft={limitDraft} client={client} updated={remote.refresh} />}
              <ClientLifecycle draft={lifecycle} client={client} updated={remote.refresh} />
            </>
          )}
          {section === 'clubs' && (
            <section className="ob-section">
              <h2>Клубы клиента</h2>
              {client.clubs.length === 0 && <p>Клубов пока нет</p>}
              {client.clubs.map((club) => <p className="po-card ob-card" key={club.id}>{club.name}</p>)}
            </section>
          )}
          {section === 'people' && (
            <section className="ob-section">
              <h2>Люди и доступы</h2>
              <p className="po-muted">
                Отзыв доступа отключает пользователя. Организация, заведения и история сохраняются.
              </p>
              {client.members.length === 0 && <p>Доступы ещё не выданы</p>}
              {client.members.map((member) => (
                <MemberAccess
                  key={member.userId + ':' + member.version}
                  member={member}
                  clubs={client.clubs}
                  clientId={id}
                  credential={credential}
                  updated={remote.refresh}
                  readOnly={client.archived}
                />
              ))}
            </section>
          )}
          {section === 'invitations' && (
            <section className="ob-section">
              <h2>Приглашения</h2>
              {client.invitations.length === 0 && <p>Приглашений пока нет</p>}
              {client.invitations.map((invitation) => (
                <button key={invitation.id} className="po-card ob-card ob-row" onClick={() => open(invitation)}>
                  <strong>{invitation.recipientLabel}</strong>
                  <small>{invitationLabels[invitation.status]}</small>
                </button>
              ))}
            </section>
          )}
        </>
      )}
    </Shell>
  );
}

function useClientLifecycle(credential: string) {
  const command = useCommand<ClientDetail>(credential);
  const [target, setTarget] = useState('');
  const [result, setResult] = useState<ClientDetail>();
  return { command, target, setTarget, result, setResult };
}

const archiveBlockerLabels: Record<string, string> = {
  clubs: 'есть связанные заведения',
  active_memberships: 'есть активные пользовательские доступы',
  active_grants: 'есть действующие полномочия',
  active_invitations: 'есть действующие приглашения',
};

function ClientLifecycle({
  draft,
  client,
  updated,
}: {
  draft: ReturnType<typeof useClientLifecycle>;
  client: ClientDetail;
  updated: () => void;
}) {
  const { command, target, result } = draft;
  useEffect(() => {
    if (result?.id === client.id) {
      draft.setResult(undefined);
      updated();
    }
  }, [result, client.id, draft, updated]);
  const ownCommand = target === client.id;
  return (
    <section className="po-card ob-card" aria-label="Архив организации">
      <h2>
        {client.archived ? 'Организация в архиве' : 'Архивирование организации'}
      </h2>
      <p>
        Архив скрывает пустую организацию из основного списка и закрывает
        доступ. История сохраняется. Восстановление организации не возвращает
        отозванные пользовательские права.
      </p>
      {!client.archived && Boolean(client.archiveBlockers?.length) && (
        <p>
          Пока нельзя архивировать:{' '}
          {client.archiveBlockers
            ?.map((reason) => archiveBlockerLabels[reason] ?? reason)
            .join('; ')}
          .
        </p>
      )}
      {client.availableActions
        ?.filter((action) => action === 'archive' || action === 'restore')
        .map((action) => (
          <button
            key={action}
            className="ob-button"
            disabled={command.blocked || !client.version}
            onClick={() => {
              if (
                !window.confirm(
                  action === 'archive'
                    ? 'Архивировать пустую организацию? История сохранится.'
                    : 'Восстановить организацию? Отозванные пользовательские права останутся отключены.',
                )
              )
                return;
              command.reset();
              draft.setTarget(client.id);
              command.run(
                '/api/platform/onboarding/clients/' + client.id + '/' + action,
                { expectedVersion: client.version },
                draft.setResult,
              );
            }}
          >
            {action === 'archive'
              ? 'Архивировать организацию'
              : 'Восстановить организацию'}
          </button>
        ))}
      {ownCommand && <CommandState command={command} refresh={updated} />}
      {!ownCommand && command.blocked && (
        <p role="status">
          Операция для другой организации ожидает результата. Вернитесь в её
          карточку и повторите ту же команду.
        </p>
      )}
    </section>
  );
}

const bookingRulesPermission = 'client.club.booking_rules.write';

const clubPermissionLabels: Record<string, string> = {
  'client.club.read': 'чтение клуба',
  'client.club.profile.write': 'редактирование профиля',
  'client.club.layout.write': 'редактирование залов и столов',
  'client.club.calendar.write': 'редактирование календаря клуба',
  'client.club.finance.read': 'чтение финансового шаблона',
  'client.club.finance.write': 'редактирование финансового шаблона',
  'client.club.modules.read': 'чтение настроек модулей',
  'client.club.modules.write': 'редактирование настроек модулей',
  [bookingRulesPermission]: 'редактирование правил бронирования',
};

function MemberAccess({
  credential,
  clientId,
  member,
  clubs,
  updated,
  readOnly = false,
}: {
  readOnly?: boolean;
  credential: string;
  clientId: string;
  member: Membership;
  clubs: Club[];
  updated: () => void;
}) {
  const command = useDetailCommand<Membership>(credential);
  const restorableClubGrants = member.clubGrants.filter(
    (grant) => grant.permission !== bookingRulesPermission,
  );
  const [manage, setManage] = useState(false);
  const [grants, setGrants] = useState<string[]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [selectedClub, setSelectedClub] = useState('');
  const [classifications, setClassifications] = useState<
    Record<string, boolean>
  >({});
  useDetailDraft(!readOnly && !authorityLost(command.error) && Boolean(
    member.active ? selectedClub : manage || grants.length || confirmed,
  ));
  const forReview =
    member.businessClubsForReview?.find((club) => club.clubId === selectedClub)
      ?.grants ?? [];
  const grantKey = (grant: (typeof forReview)[number]) =>
    [selectedClub, grant.userId, grant.permission, grant.updatedAt].join(':');
  const memberPath =
    '/api/platform/onboarding/clients/' +
    clientId +
    '/members/' +
    member.userId;
  const ownedClubIds = member.businessClubIds ?? [];
  const confirmOwnership = (suffix: string, message: string, extra = {}) => {
    if (window.confirm(message))
      command.run(
        memberPath + suffix,
        { expectedVersion: member.version, ...extra },
        updated,
      );
  };
  if (authorityLost(command.error))
    return (
      <section className="po-card ob-card">
        <CommandState command={command} refresh={updated} />
      </section>
    );
  return (
    <section className="po-card ob-card">
      <h3>
        {member.displayName || member.username || 'Telegram-пользователь'}
      </h3>
      <p className="po-muted">Telegram ID: {member.telegramUserId}</p>
      <p>{member.active ? 'Доступ активен' : 'Доступ отозван'}</p>
      {readOnly ? (
        <p>Организация в архиве. Управление доступом недоступно.</p>
      ) : (
        <>
          {member.active ? (
            <>
              {member.canConfirmBusinessOwner === true && (
                <button
                  className="ob-button"
                  disabled={command.blocked}
                  onClick={() =>
                    confirmOwnership(
                      '/business-owner',
                      'Подтвердить этого пользователя как владельца бизнеса? Он сможет создавать клубы в пределах лимита организации.',
                    )
                  }
                >
                  Подтвердить владельца бизнеса
                </button>
              )}
              {member.businessOwner === true && (
                <>
                  <p>Владелец бизнеса</p>
                  {clubs
                    .filter((club) => ownedClubIds.includes(club.id))
                    .map((club) => (
                      <p key={club.id}>Подтверждённое заведение: {club.name}</p>
                    ))}
                  {clubs.some((club) => !ownedClubIds.includes(club.id)) && (
                    <>
                      <Field label="Подтвердить существующее заведение владельца">
                        <select
                          value={selectedClub}
                          onChange={(event) => {
                            setSelectedClub(event.target.value);
                            setClassifications({});
                          }}
                          disabled={command.blocked}
                        >
                          <option value="">Выберите заведение</option>
                          {clubs
                            .filter((club) => !ownedClubIds.includes(club.id))
                            .map((club) => (
                              <option key={club.id} value={club.id}>
                                {club.name}
                              </option>
                            ))}
                        </select>
                      </Field>
                      {forReview.length > 0 && (
                        <fieldset
                          disabled={command.blocked}
                          className="ob-fields"
                        >
                          <legend>
                            Проверьте прежние полномочия пользователей
                          </legend>
                          {forReview.map((grant) => (
                            <Field
                              key={grantKey(grant)}
                              label={
                                'Пользователь №' +
                                grant.userId +
                                ' · ' +
                                (clubPermissionLabels[grant.permission] ??
                                  grant.permission)
                              }
                            >
                              <select
                                value={
                                  grantKey(grant) in classifications
                                    ? String(classifications[grantKey(grant)])
                                    : ''
                                }
                                onChange={(event) =>
                                  setClassifications({
                                    ...classifications,
                                    [grantKey(grant)]:
                                      event.target.value === 'true',
                                  })
                                }
                              >
                                <option value="" disabled>
                                  Выберите решение
                                </option>
                                <option value="true">
                                  Оставить независимое полномочие
                                </option>
                                <option value="false">
                                  Отозвать полномочие
                                </option>
                              </select>
                            </Field>
                          ))}
                        </fieldset>
                      )}
                      <button
                        className="ob-button"
                        disabled={
                          command.blocked ||
                          !selectedClub ||
                          ownedClubIds.includes(selectedClub) ||
                          !forReview.every(
                            (g) => grantKey(g) in classifications,
                          )
                        }
                        onClick={() => {
                          const club = clubs.find(
                            (item) => item.id === selectedClub,
                          );
                          if (club && !ownedClubIds.includes(club.id))
                            confirmOwnership(
                              '/business-clubs/' + club.id,
                              'Подтвердить право этого владельца на заведение «' +
                                club.name +
                                '»?',
                              forReview.length
                                ? {
                                    reviewedGrants: forReview.map((grant) => ({
                                      ...grant,
                                      keep: classifications[grantKey(grant)],
                                    })),
                                  }
                                : {},
                            );
                        }}
                      >
                        Подтвердить владение заведением
                      </button>
                    </>
                  )}
                </>
              )}
              {member.permissions.includes('client.application.manage') && (
                <p>Управление заявками</p>
              )}
              {member.clubGrants
                .filter((grant) => grant.active)
                .map((grant) => (
                  <p key={grant.clubId + grant.permission}>
                    {grant.clubName}:{' '}
                    {clubPermissionLabels[grant.permission] ?? grant.permission}
                  </p>
                ))}
              <button
                className="ob-button ob-danger"
                disabled={command.blocked}
                onClick={() => {
                  if (
                    window.confirm(
                      'Отозвать доступ к этому пространству, заявкам и клубам? Данные сохранятся.',
                    )
                  )
                    command.run(
                      '/api/platform/onboarding/clients/' +
                        clientId +
                        '/members/' +
                        member.userId +
                        '/revoke',
                      {
                        userId: member.userId,
                        expectedVersion: member.version,
                      },
                      updated,
                    );
                }}
              >
                Отозвать доступ
              </button>
            </>
          ) : (
            <>
              <p>
                Отметьте только те прежние полномочия, которые нужно вернуть.
                Доступ к остальным клубам не появится.
              </p>
              {member.restorablePermissions.includes(
                'client.application.manage',
              ) && (
                <label className="ob-check">
                  <input
                    type="checkbox"
                    checked={manage}
                    onChange={(event) => setManage(event.target.checked)}
                  />
                  <span>Управление заявками</span>
                </label>
              )}
              {restorableClubGrants.map((grant) => (
                <label
                  className="ob-check"
                  key={grant.clubId + grant.permission}
                >
                  <input
                    type="checkbox"
                    checked={grants.includes(
                      grant.clubId + ':' + grant.permission,
                    )}
                    onChange={(event) =>
                      setGrants((value) =>
                        event.target.checked
                          ? [...value, grant.clubId + ':' + grant.permission]
                          : value.filter(
                              (item) =>
                                item !== grant.clubId + ':' + grant.permission,
                            ),
                      )
                    }
                  />
                  <span>
                    {grant.clubName}:{' '}
                    {clubPermissionLabels[grant.permission] ?? grant.permission}
                  </span>
                </label>
              ))}
              <label className="ob-check">
                <input
                  type="checkbox"
                  checked={confirmed}
                  onChange={(event) => setConfirmed(event.target.checked)}
                />
                <span>
                  Восстановить доступ этому же Telegram-аккаунту только с
                  выбранными полномочиями.
                </span>
              </label>
              <button
                className="ob-button"
                disabled={command.blocked || !confirmed}
                onClick={() =>
                  command.run(
                    '/api/platform/onboarding/clients/' +
                      clientId +
                      '/members/' +
                      member.userId +
                      '/restore',
                    {
                      userId: member.userId,
                      expectedVersion: member.version,
                      permissions: manage ? ['client.application.manage'] : [],
                      clubGrants: restorableClubGrants
                        .filter((grant) =>
                          grants.includes(
                            grant.clubId + ':' + grant.permission,
                          ),
                        )
                        .map((grant) => ({
                          clubId: grant.clubId,
                          permission: grant.permission,
                        })),
                    },
                    updated,
                  )
                }
              >
                Восстановить выбранный доступ
              </button>
            </>
          )}
        </>
      )}
      <CommandState command={command} refresh={updated} />
    </section>
  );
}

function OwnerApplication({
  credential,
  id,
  back,
  navigation,
}: {
  credential: string;
  id: string;
  back: () => void;
  navigation: ReactNode;
}) {
  const remote = useRemote<ClubApplication>(
    credential,
    '/api/platform/onboarding/applications/' + id,
  );
  const [application, setApplication] = useState<ClubApplication>();
  const [reason, setReason] = useState('');
  const [internalNote, setInternalNote] = useState('');
  const [commercialReference, setCommercialReference] = useState('');
  const [commercialAgreedAt, setCommercialAgreedAt] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const command = useDetailCommand<ClubApplication>(credential);
  useDetailDraft(Boolean(application && (reason || internalNote || commercialReference || commercialAgreedAt || confirmed)));
  useEffect(() => {
    if (authorityLost(command.error) || remote.error) {
      setApplication(undefined);
      setReason('');
      setInternalNote('');
      setCommercialReference('');
      setCommercialAgreedAt('');
    } else if (remote.data) setApplication(remote.data);
  }, [remote.data, remote.error, command.error]);
  const action = (name: string) => {
    if (!application) return;
    command.run(
      '/api/platform/onboarding/applications/' + id + '/' + name,
      {
        expectedVersion: application.version,
        ...(name === 'review' ? {} : { reason }),
        ...(name === 'approve'
          ? {
              commercialReference,
              commercialAgreedAt,
              platformConfirmed: confirmed,
              internalNote,
            }
          : {}),
      },
      (result) => {
        setApplication(result);
        setReason('');
        setInternalNote('');
        setCommercialReference('');
        setCommercialAgreedAt('');
        setConfirmed(false);
      },
    );
  };
  return (
    <Shell
      title={application?.venueName || 'Рассмотрение заявки'}
      owner
      back={back}
      navigation={navigation}
    >
      <RemoteState {...remote} />
      {application && (
        <>
          <p className="po-muted">
            Заявка №{application.id} · автор #{application.authorUserId}
          </p>
          {application.author && (
            <section className="po-card ob-card">
              <h2>Подтверждённый автор</h2>
              <p>
                {application.author.displayName ||
                  application.author.username ||
                  'Telegram-пользователь'}
              </p>
              <p className="po-muted">
                Telegram ID: {application.author.telegramUserId}
              </p>
            </section>
          )}
          <ApplicationSummary application={application} />
          {application.commercialReference && (
            <section className="po-card ob-card">
              <h2>Коммерческое согласование</h2>
              <p>{application.commercialReference}</p>
              <p>{application.commercialAgreedAt}</p>
              {application.internalNote && (
                <p>Внутренняя заметка: {application.internalNote}</p>
              )}
            </section>
          )}
          {application.availableActions.includes('review') && (
            <button
              className="po-primary"
              disabled={command.blocked}
              onClick={() => action('review')}
            >
              Начать рассмотрение
            </button>
          )}
          {(application.availableActions.includes('reject') ||
            application.availableActions.includes('cancel') ||
            application.availableActions.includes('approve')) && (
            <Field label="Причина / сообщение клиенту">
              <textarea
                value={reason}
                maxLength={2000}
                disabled={command.blocked}
                onChange={(event) => setReason(event.target.value)}
                rows={3}
              />
            </Field>
          )}
          {application.availableActions.includes('approve') && (
            <section className="po-card ob-card">
              <h2>Внешнее коммерческое согласование</h2>
              <Field label="Номер или ссылка согласования">
                <input
                  value={commercialReference}
                  maxLength={512}
                  disabled={command.blocked}
                  onChange={(event) =>
                    setCommercialReference(event.target.value)
                  }
                />
              </Field>
              <Field label="Дата согласования">
                <input
                  type="date"
                  value={commercialAgreedAt}
                  disabled={command.blocked}
                  onChange={(event) =>
                    setCommercialAgreedAt(event.target.value)
                  }
                />
              </Field>
              <Field label="Внутренняя заметка платформы">
                <textarea
                  value={internalNote}
                  maxLength={2000}
                  disabled={command.blocked}
                  onChange={(event) => setInternalNote(event.target.value)}
                />
              </Field>
              <label className="ob-check">
                <input
                  type="checkbox"
                  checked={confirmed}
                  onChange={(event) => setConfirmed(event.target.checked)}
                />
                <span>
                  Подтверждаю согласование для «{application.venueName}», клиент
                  «{application.clientName}».
                </span>
              </label>
              <p className="po-muted">
                Это ручная фиксация согласования. Платёжные операции не
                выполняются.
              </p>
              <button
                className="po-primary"
                disabled={
                  command.blocked ||
                  !confirmed ||
                  !commercialReference.trim() ||
                  !commercialAgreedAt
                }
                onClick={() => action('approve')}
              >
                Одобрить подключение
              </button>
            </section>
          )}
          {application.availableActions.includes('reject') && (
            <button
              className="ob-button ob-danger"
              disabled={command.blocked || !reason.trim()}
              onClick={() => {
                if (
                  window.confirm(
                    'Отклонить заявку? Причина будет видна клиенту.',
                  )
                )
                  action('reject');
              }}
            >
              Отклонить заявку
            </button>
          )}
          {application.availableActions.includes('cancel') && (
            <button
              className="ob-button ob-danger"
              disabled={command.blocked || !reason.trim()}
              onClick={() => {
                if (
                  window.confirm(
                    'Отменить заявку или неиспользованное одобрение?',
                  )
                )
                  action('cancel');
              }}
            >
              Отменить заявку
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
    </Shell>
  );
}

function useClientLimitDraft(credential: string) {
  const [clientId, setClientId] = useState('');
  const [value, setValue] = useState('');
  const [result, setResult] = useState<ClientDetail>();
  const command = useCommand<ClientDetail>(credential);
  return { clientId, setClientId, value, setValue, result, setResult, command };
}

function ClientLimit({
  draft,
  client,
  updated,
}: {
  draft: ReturnType<typeof useClientLimitDraft>;
  client: ClientDetail;
  updated: () => void;
}) {
  const { command, result } = draft;
  useEffect(() => {
    if (draft.clientId !== client.id && !command.blocked) {
      draft.setClientId(client.id);
      draft.setValue(client.allowedClubs?.toString() ?? '');
      command.reset();
    }
  }, [client, draft, command]);
  useEffect(() => {
    if (result?.id === client.id) {
      draft.setResult(undefined);
      updated();
    }
  }, [result, client.id, updated, draft]);
  if (command.blocked && draft.clientId !== client.id)
    return (
      <p className="po-notice">
        Изменение лимита другого клиента ожидает результата. Вернитесь в его
        карточку и проверьте результат.
      </p>
    );
  return (
    <section className="po-card ob-card">
      <h2>Лимит заведений</h2>
      <p>Разрешено всего: {client.allowedClubs ?? 'не настроено'}</p>
      <p>Создано: {client.createdClubs ?? client.clubs.length}</p>
      <p>Можно добавить: {client.availableClubs ?? 0}</p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          command.run(
            '/api/platform/onboarding/clients/' + client.id + '/limit',
            {
              allowedClubs: Number(draft.value),
              expectedVersion: client.limitVersion,
            },
            draft.setResult,
          );
        }}
      >
        <Field label="Разрешено всего">
          <input
            type="number"
            min={client.createdClubs ?? 0}
            step="1"
            required
            value={draft.value}
            disabled={command.blocked}
            onChange={(event) => draft.setValue(event.target.value)}
          />
        </Field>
        <button
          type="submit"
          className="po-primary"
          disabled={command.blocked || !/^(0|[1-9][0-9]*)$/.test(draft.value)}
        >
          {command.saving ? 'Сохраняем…' : 'Сохранить лимит'}
        </button>
        <CommandState command={command} refresh={updated} />
      </form>
    </section>
  );
}
