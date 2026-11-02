import { useCallback, useEffect, useState } from 'react';
import { Building2, LayoutGrid, Users } from 'lucide-react';
import { ApplicationList } from './ClientAccess';
import { useCommand, useRemote } from './onboarding.hooks';
import {
  ApplicationSummary,
  CommandState,
  Field,
  RemoteState,
  Shell,
} from './OnboardingShared';
import {
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

type OwnerScreen =
  | { type: 'overview' | 'invite' | 'clubs' | 'clients' | 'applications' }
  | { type: 'invitation' | 'client' | 'application'; id: string };
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
  if (
    (type === 'invitation' || type === 'client' || type === 'application') &&
    id &&
    /^[1-9][0-9]*$/.test(id)
  )
    return { type, id };
  return { type: 'overview' };
}

export default function PlatformOnboarding({
  credential,
  invite,
}: {
  credential: string;
  invite?: InvitationDraft;
}) {
  if (invite)
    return <PlatformNavigation credential={credential} invite={invite} />;
  return <PlatformSession key={credential} credential={credential} />;
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
  const navigate = useCallback((next: OwnerScreen, replace = false) => {
    const current = currentScreen();
    if (!replace && JSON.stringify(current) === JSON.stringify(next)) return;
    const url = new URL(window.location.href);
    url.searchParams.set('mode', 'platform-owner');
    if (next.type === 'overview') url.searchParams.delete('screen');
    else url.searchParams.set('screen', next.type);
    if ('id' in next) url.searchParams.set('resource', next.id);
    else url.searchParams.delete('resource');
    if (replace) window.history.replaceState({ onboarding: true }, '', url);
    else window.history.pushState({ onboarding: true }, '', url);
    setScreen(next);
  }, []);
  useEffect(() => {
    const initial = currentScreen();
    if (initial.type !== 'overview' && !window.history.state?.onboarding) {
      const original = window.location.href;
      const overview = new URL(original);
      overview.searchParams.delete('screen');
      overview.searchParams.delete('resource');
      window.history.replaceState({ onboarding: true }, '', overview);
      window.history.pushState({ onboarding: true }, '', original);
    }
    const pop = () => setScreen(currentScreen());
    window.addEventListener('popstate', pop);
    return () => window.removeEventListener('popstate', pop);
  }, []);
  const overview = screen.type === 'overview';
  const back = useCallback(() => window.history.back(), []);
  const home = () => navigate({ type: 'overview' });
  const openInvitation = (invitation: Invitation) => {
    invite.forgetLink();
    navigate({ type: 'invitation', id: invitation.id });
  };
  const openApplication = (application: ClubApplication) =>
    navigate({ type: 'application', id: application.id });
  if (screen.type === 'invite')
    return <OwnerInvite draft={invite} back={back} open={openInvitation} />;
  if (screen.type === 'invitation')
    return (
      <OwnerInvitation
        key={screen.id}
        credential={credential}
        id={screen.id}
        back={back}
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
        back={back}
        open={openInvitation}
      />
    );
  if (screen.type === 'application')
    return (
      <OwnerApplication
        key={screen.id}
        credential={credential}
        id={screen.id}
        back={back}
      />
    );
  return (
    <Shell
      title={
        overview
          ? 'Управление платформой'
          : screen.type === 'clubs'
            ? 'Клубы платформы'
            : screen.type === 'applications'
              ? 'Заявки на подключение'
              : 'Клиенты и доступы'
      }
      subtitle={
        overview ? 'Клиенты, приглашения и разрешённые заведения.' : undefined
      }
      owner
      back={overview ? undefined : back}
      navigation={
        <nav className="po-nav" aria-label="Навигация платформы">
          <button aria-current={overview ? 'page' : undefined} onClick={home}>
            <LayoutGrid size={21} />
            <span>Обзор</span>
          </button>
          <button
            aria-current={screen.type === 'clubs' ? 'page' : undefined}
            onClick={() => navigate({ type: 'clubs' })}
          >
            <Building2 size={21} />
            <span>Клубы</span>
          </button>
          <button
            aria-current={screen.type === 'clients' ? 'page' : undefined}
            onClick={() => navigate({ type: 'clients' })}
          >
            <Users size={21} />
            <span>Доступы</span>
          </button>
        </nav>
      }
    >
      {overview && (
        <>
          <div className="po-context">
            <strong>Платформа</strong>
            <span className="po-context-badge">Обзор</span>
          </div>
          <OwnerOverview credential={credential} />
          <button
            className="po-primary"
            onClick={() => navigate({ type: 'invite' })}
          >
            Пригласить клиента
          </button>
          <button
            className="ob-button"
            onClick={() => navigate({ type: 'clients' })}
          >
            Клиенты и доступы
          </button>
          <button
            className="ob-button"
            onClick={() => navigate({ type: 'applications' })}
          >
            История заявок
          </button>
        </>
      )}
      {screen.type === 'clubs' && (
        <OwnerClubs
          credential={credential}
          open={(id) => navigate({ type: 'client', id })}
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
  draft,
  back,
  open,
}: {
  draft: ReturnType<typeof useInvitationDraft>;
  back: () => void;
  open: (invitation: Invitation) => void;
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
      title="Пригласить клиента"
      subtitle="Доступ к кабинету с разрешённым количеством заведений."
      owner
      back={back}
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
            нужному человеку. Отзыв доступа не отменяет прежние действия или
            раскрытие данных.
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
            onClick={draft.reset}
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
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState(false);
  const link = invitation.invitationLink;
  const usable = Boolean(
    link &&
      /^https:\/\/t\.me\/[A-Za-z][A-Za-z0-9_]{4,31}\?startapp=ci_[A-Za-z0-9_-]{43}$/.test(
        link,
      ),
  );
  useEffect(() => {
    setCopied(false);
    setCopyError(false);
  }, [link]);
  return (
    <section className="po-card ob-card">
      <h2>{usable ? 'Приглашение создано' : 'Ссылка не получена'}</h2>
      {usable ? (
        <>
          <p>Отправьте эту ссылку человеку в Telegram.</p>
          <p className="ob-link">{link}</p>
          <p>
            Ссылка действует семь суток — до{' '}
            {new Date(invitation.expiresAt).toLocaleString('ru-RU')}.
          </p>
          <button
            type="button"
            className="ob-button"
            onClick={() => {
              setCopied(false);
              void Promise.resolve()
                .then(() => navigator.clipboard.writeText(link!))
                .then(() => {
                  setCopied(true);
                  setCopyError(false);
                })
                .catch(() => setCopyError(true));
            }}
          >
            Копировать ссылку
          </button>
          {copied && <p role="status">Ссылка скопирована</p>}
          {copyError && (
            <p role="alert">Не удалось скопировать. Выделите ссылку вручную.</p>
          )}
          <p className="po-muted">
            Ссылка показывается только при выдаче. Если потеряете её, выпустите
            новую.
          </p>
        </>
      ) : (
        <p className="po-notice">
          Первоначальный ответ со ссылкой не получен. Запись сохранена; откройте
          приглашение и выпустите новую ссылку.
        </p>
      )}
    </section>
  );
}

function OwnerInvitation({
  credential,
  id,
  back,
  open,
}: {
  credential: string;
  id: string;
  back: () => void;
  open: (invitation: Invitation) => void;
}) {
  const remote = useRemote<Invitation>(
    credential,
    '/api/platform/onboarding/invitations/' + id,
  );
  const [invitation, setInvitation] = useState<Invitation>();
  const [confirmed, setConfirmed] = useState(false);
  const command = useCommand<Invitation>(credential);
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
        if (name === 'reissue') {
          setInvitation(result);
        } else {
          setInvitation(result);
          setConfirmed(false);
        }
      },
    );
  };
  return (
    <Shell title="Приглашение клиента" owner back={back}>
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

function OwnerOverview({ credential }: { credential: string }) {
  const remote = useRemote<{
    clients: number;
    archivedClients: number;
    clubs: number;
    activeMembers: number;
    openInvitations: number;
  }>(credential, '/api/platform/onboarding/summary');
  return (
    <section aria-label="Сводка платформы">
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
        Сохранённые заведения и их клиентские организации. Публичность и доступ
        пользователей — разные состояния.
      </p>
      <RemoteState {...remote} />
      {remote.data?.items.length === 0 && (
        <p className="po-card ob-card">Заведений клиентов пока нет.</p>
      )}
      {remote.data?.items.map((club) => (
        <section className="po-card ob-card" key={club.id}>
          <h2>{club.name}</h2>
          <p>Организация: {club.clientName}</p>
          <p>{club.published ? 'Опубликован' : 'Не опубликован'}</p>
          {!club.published && (
            <p className="po-muted">
              Заведение пока недоступно гостям и для бронирования.
            </p>
          )}
          <p>
            Организация:{' '}
            {club.clientArchived
              ? 'В архиве'
              : club.clientActive
                ? 'Активна'
                : 'Ожидает подтверждения'}
          </p>
          <button className="ob-button" onClick={() => open(club.clientId)}>
            Доступы организации «{club.clientName}»
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
}: {
  lifecycle: ReturnType<typeof useClientLifecycle>;
  limitDraft: ReturnType<typeof useClientLimitDraft>;
  credential: string;
  id: string;
  back: () => void;
  open: (invitation: Invitation) => void;
}) {
  const remote = useRemote<ClientDetail>(
    credential,
    '/api/platform/onboarding/clients/' + id,
  );
  return (
    <Shell title={remote.data?.name ?? 'Клиент и доступы'} owner back={back}>
      <RemoteState {...remote} />
      {remote.data && (
        <>
          <section className="po-card ob-card">
            <h2>{organizationLabel(remote.data)}</h2>
            <p>
              Активных пользователей:{' '}
              {remote.data.activeMembers ??
                remote.data.members.filter((member) => member.active).length}
            </p>
            <p className="po-muted">
              Отзыв доступа отключает пользователя. Организация, заведения и
              история сохраняются.
            </p>
          </section>
          <ClientLifecycle
            draft={lifecycle}
            client={remote.data}
            updated={remote.refresh}
          />
          {!remote.data.archived && (
            <>
              <ClientLimit
                draft={limitDraft}
                client={remote.data}
                updated={remote.refresh}
              />
            </>
          )}
          <section className="ob-section">
            <h2>Доступы</h2>
            {remote.data.members.length === 0 && <p>Доступы ещё не выданы</p>}
            {remote.data.members.map((member) => (
              <MemberAccess
                key={member.userId + ':' + member.version}
                member={member}
                clientId={id}
                credential={credential}
                updated={remote.refresh}
                readOnly={remote.data?.archived}
              />
            ))}
          </section>
          <section className="ob-section">
            <h2>Приглашения</h2>
            {remote.data.invitations.map((invitation) => (
              <button
                key={invitation.id}
                className="po-card ob-card ob-row"
                onClick={() => open(invitation)}
              >
                <strong>{invitation.recipientLabel}</strong>
                <small>{invitationLabels[invitation.status]}</small>
              </button>
            ))}
          </section>
          <section className="ob-section">
            <h2>Клубы клиента</h2>
            {remote.data.clubs.length === 0 ? (
              <p>Клубов пока нет</p>
            ) : (
              remote.data.clubs.map((club) => (
                <p className="po-card ob-card" key={club.id}>
                  {club.name}
                </p>
              ))
            )}
          </section>
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

const clubPermissionLabels: Record<string, string> = {
  'client.club.read': 'чтение клуба',
  'client.club.profile.write': 'редактирование профиля',
  'client.club.layout.write': 'редактирование залов и столов',
};

function MemberAccess({
  credential,
  clientId,
  member,
  updated,
  readOnly = false,
}: {
  readOnly?: boolean;
  credential: string;
  clientId: string;
  member: Membership;
  updated: () => void;
}) {
  const command = useCommand<Membership>(credential);
  const [manage, setManage] = useState(false);
  const [grants, setGrants] = useState<string[]>([]);
  const [confirmed, setConfirmed] = useState(false);
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
              {member.clubGrants.map((grant) => (
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
                      clubGrants: member.clubGrants
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
}: {
  credential: string;
  id: string;
  back: () => void;
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
  const command = useCommand<ClubApplication>(credential);
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
        setConfirmed(false);
      },
    );
  };
  return (
    <Shell
      title={application?.venueName || 'Рассмотрение заявки'}
      owner
      back={back}
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
