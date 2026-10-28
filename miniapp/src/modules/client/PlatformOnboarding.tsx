import { useCallback, useEffect, useState } from 'react';
import { Building2, LayoutGrid, Settings, Users } from 'lucide-react';
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
} from './onboarding.types';
import { authorityLost } from './onboarding.api';

type OwnerScreen =
  | { type: 'overview' | 'invite' | 'clients' | 'applications' }
  | { type: 'invitation' | 'client' | 'application'; id: string };
function currentScreen(): OwnerScreen {
  const params = new URLSearchParams(window.location.search);
  const type = params.get('screen');
  const id = params.get('resource');
  if (type === 'invite' || type === 'clients' || type === 'applications')
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
}: {
  credential: string;
}) {
  const [screen, setScreen] = useState<OwnerScreen>(currentScreen);
  const navigate = useCallback((next: OwnerScreen, replace = false) => {
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
  const openInvitation = (invitation: Invitation) =>
    navigate({ type: 'invitation', id: invitation.id });
  const openApplication = (application: ClubApplication) =>
    navigate({ type: 'application', id: application.id });
  if (screen.type === 'invite')
    return (
      <OwnerInvite credential={credential} back={back} open={openInvitation} />
    );
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
          : screen.type === 'applications'
            ? 'Заявки на подключение'
            : 'Клиенты и доступы'
      }
      subtitle={
        overview ? 'Клиенты, приглашения и решения по заявкам.' : undefined
      }
      owner
      back={overview ? undefined : back}
      navigation={
        <nav className="po-nav" aria-label="Навигация платформы">
          <button aria-current={overview ? 'page' : undefined} onClick={home}>
            <LayoutGrid size={21} />
            <span>Обзор</span>
          </button>
          <button onClick={() => navigate({ type: 'clients' })}>
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
          <button disabled>
            <Settings size={21} />
            <span>Настройки</span>
            <small>Позже</small>
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
            Рассмотреть заявки
          </button>
        </>
      )}
      {screen.type === 'clients' ? (
        <OwnerClients
          credential={credential}
          open={(client) => navigate({ type: 'client', id: client.id })}
        />
      ) : (
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
  credential,
  back,
  open,
}: {
  credential: string;
  back: () => void;
  open: (invitation: Invitation) => void;
}) {
  const [clientName, setClientName] = useState('');
  const [recipientLabel, setRecipientLabel] = useState('');
  const [invitation, setInvitation] = useState<Invitation>();
  const command = useCommand<Invitation>(credential);
  return (
    <Shell
      title="Пригласить клиента"
      subtitle="Доступ к кабинету и подаче заявок. Каждый клуб требует отдельного одобрения."
      owner
      back={back}
    >
      {!invitation ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            command.run(
              '/api/platform/onboarding/invitations',
              { clientName, recipientLabel },
              setInvitation,
            );
          }}
        >
          <Field label="Название клиентского пространства">
            <input
              required
              maxLength={255}
              value={clientName}
              disabled={command.blocked}
              onChange={(event) => setClientName(event.target.value)}
            />
          </Field>
          <Field label="Кому отправляете приглашение">
            <input
              required
              maxLength={255}
              value={recipientLabel}
              disabled={command.blocked}
              onChange={(event) => setRecipientLabel(event.target.value)}
            />
          </Field>
          <p className="po-notice">
            Ссылка действует семь суток. Получатель подтвердит аккаунт и сообщит
            код в вашем исходном личном чате. После сверки вы отдельно
            предоставите доступ.
          </p>
          <button className="po-primary" disabled={command.blocked}>
            Создать ссылку
          </button>
        </form>
      ) : (
        <>
          <InvitationLink invitation={invitation} />
          <button className="po-primary" onClick={() => open(invitation)}>
            Открыть приглашение
          </button>
        </>
      )}
      <CommandState command={command} />
    </Shell>
  );
}

function InvitationLink({ invitation }: { invitation: Invitation }) {
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState(false);
  return (
    <section className="po-card ob-card">
      <h2>Приглашение создано</h2>
      {invitation.invitationLink ? (
        <>
          <p>Передайте ссылку получателю в личном чате.</p>
          <p className="ob-link">{invitation.invitationLink}</p>
          <button
            className="ob-button"
            onClick={() => {
              void navigator.clipboard
                .writeText(invitation.invitationLink!)
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

function OwnerClients({
  credential,
  open,
}: {
  credential: string;
  open: (client: ClientSummary) => void;
}) {
  const [cursor, setCursor] = useState<string>();
  const remote = useRemote<Page<ClientSummary>>(
    credential,
    '/api/platform/onboarding/clients/search',
    { limit: 50, ...(cursor ? { cursor } : {}) },
  );
  return (
    <>
      <RemoteState {...remote} />
      {remote.data?.items.length === 0 && (
        <p className="po-card ob-card">
          Клиентов пока нет. Создайте приглашение.
        </p>
      )}
      {remote.data?.items.map((client) => (
        <button
          key={client.id}
          className="po-card ob-card ob-row"
          onClick={() => open(client)}
        >
          <strong>{client.name}</strong>
          <small>{client.active ? 'Активен' : 'Ожидает подтверждения'}</small>
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
}: {
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

function MemberAccess({
  credential,
  clientId,
  member,
  updated,
}: {
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
      {member.active ? (
        <>
          {member.permissions.includes('client.application.manage') && (
            <p>Управление заявками</p>
          )}
          {member.clubGrants
            .filter((grant) => grant.active)
            .map((grant) => (
              <p key={grant.clubId + grant.permission}>
                {grant.clubName}: чтение клуба
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
                  { userId: member.userId, expectedVersion: member.version },
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
            Отметьте только те прежние полномочия, которые нужно вернуть. Доступ
            к остальным клубам не появится.
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
            <label className="ob-check" key={grant.clubId + grant.permission}>
              <input
                type="checkbox"
                checked={grants.includes(grant.clubId + ':' + grant.permission)}
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
              <span>{grant.clubName}: чтение клуба</span>
            </label>
          ))}
          <label className="ob-check">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(event) => setConfirmed(event.target.checked)}
            />
            <span>
              Восстановить доступ этому же Telegram-аккаунту только с выбранными
              полномочиями.
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
                      grants.includes(grant.clubId + ':' + grant.permission),
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
