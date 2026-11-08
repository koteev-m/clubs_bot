import { useEffect, useState } from 'react';
import { useClubConfiguration } from './configuration.hooks';
import {
  CommandState,
  Field,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from './OnboardingShared';
import { invitationLabels } from './onboarding.types';
import {
  permissionNames,
  roleNames,
  StaffRole,
  TeamView,
  validTeam,
} from './team.types';

type Intent = {
  action: 'confirm' | 'revoke_invitation' | 'change_role' | 'disable';
  target: string;
  version: string;
  targetVersion: string;
  role: StaffRole;
  reason: string;
  code: string;
  confirmed: boolean;
};
type Props = {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (value: boolean) => void;
  reportPending: (value: boolean) => void;
};
export default function ClubTeam(props: Props) {
  return <TeamEditor key={props.credential + ':' + props.id} {...props} />;
}
function Permissions({ values }: { values: string[] }) {
  return values.length ? (
    <ul>
      {values.map((value) => (
        <li key={value}>{permissionNames[value] ?? value}</li>
      ))}
    </ul>
  ) : (
    <p>Действующих разрешений нет.</p>
  );
}
function TeamEditor({
  credential,
  id,
  back,
  reportDirty,
  reportPending,
}: Props) {
  const state = useClubConfiguration<TeamView>(
    credential,
    id,
    '/team',
    validTeam,
  );
  const { view, remote, command } = state;
  const [label, setLabel] = useState('');
  const [role, setRole] = useState<StaffRole>('ENTRY_MANAGER');
  const [intent, setIntent] = useState<Intent>();
  const [link, setLink] = useState<string>();
  const [linkId, setLinkId] = useState<string>();
  const [missingLinkId, setMissingLinkId] = useState<string>();
  const [linkMessage, setLinkMessage] = useState('');
  useEffect(() => {
    if (
      link &&
      linkId &&
      view?.invitations.some(
        (item) =>
          item.id === linkId && !['issued', 'claimed'].includes(item.status),
      )
    ) {
      setLink(undefined);
      setLinkId(undefined);
      setLinkMessage('');
    }
  }, [link, linkId, view]);
  const leave = useUnsavedChanges(
    Boolean(label || intent || command.unknown),
    reportDirty,
  );
  const blocked = state.blocked;
  useEffect(() => {
    reportPending(command.blocked && !state.denied);
    return () => reportPending(false);
  }, [command.blocked, state.denied, reportPending]);
  useEffect(() => {
    if (state.denied) {
      setLabel('');
      setIntent(undefined);
      setLink(undefined);
      setLinkMessage('');
    }
  }, [state.denied]);
  const selected = view?.roles.find((item) => item.code === role);
  const intentRole = view?.roles.find((item) => item.code === intent?.role);
  const targetMember = view?.members.find(
    (member) => member.userId === intent?.target,
  );
  const targetInvitation = view?.invitations.find(
    (invitation) => invitation.id === intent?.target,
  );
  const conflict = Boolean(intent && view && intent.version !== view.version);
  const targetAvailable = !intent
    ? false
    : intent.action === 'confirm' || intent.action === 'revoke_invitation'
      ? Boolean(targetInvitation?.availableActions.includes(intent.action))
      : Boolean(
          intent.action === 'disable'
            ? targetMember?.canDisable
            : targetMember?.canChange,
        );
  const canSubmit =
    intent &&
    !blocked &&
    !conflict &&
    targetAvailable &&
    intent.confirmed &&
    (intent.action !== 'confirm' || /^[0-9]{6}$/.test(intent.code)) &&
    (!['change_role', 'disable'].includes(intent.action) ||
      Boolean(intent.reason.trim())) &&
    (intent.action !== 'change_role' || intentRole?.assignable);
  const open = (
    action: Intent['action'],
    target: string,
    targetVersion: string,
    selectedRole: StaffRole,
  ) => {
    if (!view || blocked) return;
    command.reset();
    setIntent({
      action,
      target,
      targetVersion,
      version: view.version,
      role: selectedRole,
      reason: '',
      code: '',
      confirmed: false,
    });
  };
  const save = () => {
    if (!intent || !canSubmit) return;
    const invitationAction = ['confirm', 'revoke_invitation'].includes(
      intent.action,
    );
    state.run(
      {
        action: intent.action,
        expectedVersion: intent.version,
        confirmed: true,
        ...(invitationAction
          ? {
              invitationId: intent.target,
              expectedInvitationVersion: intent.targetVersion,
              ...(intent.action === 'confirm'
                ? { verificationCode: intent.code }
                : {}),
            }
          : {
              userId: intent.target,
              expectedMemberVersion: intent.targetVersion,
              reason: intent.reason.trim(),
              ...(intent.action === 'change_role' ? { role: intent.role } : {}),
            }),
      },
      () => setIntent(undefined),
    );
  };
  return (
    <Shell
      title="Команда клуба"
      subtitle={view?.name}
      back={() => {
        if ((!command.blocked || state.denied) && leave()) back();
      }}
      backDisabled={command.blocked && !state.denied}
      backLabel="Назад к клубу"
    >
      <RemoteState
        loading={remote.loading}
        error={remote.error}
        refresh={remote.refresh}
      />
      {state.denied && !remote.error && (
        <RemoteState
          loading={false}
          error={command.error}
          refresh={remote.refresh}
        />
      )}
      {view && (
        <>
          <p className="po-notice">
            Права действуют только в этом клубе. Операционные действия, денежные
            операции и управление платформой не выдаются. Команда не является
            обязательным условием подготовки клуба.
          </p>
          {link && (
            <section className="po-card ob-card" aria-label="Новое приглашение">
              <h2>Приглашение создано</h2>
              <p>
                Передайте ссылку сотруднику в личном чате. После принятия
                проверьте аккаунт и код. Ссылка показывается только сейчас.
              </p>
              <Field label="Ссылка приглашения">
                <input
                  readOnly
                  value={link}
                  onFocus={(event) => event.currentTarget.select()}
                />
              </Field>
              <button
                type="button"
                className="ob-button"
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(link);
                    setLinkMessage('Ссылка скопирована');
                  } catch {
                    setLinkMessage('Выделите ссылку и скопируйте её вручную.');
                  }
                }}
              >
                Скопировать ссылку
              </button>
              {typeof navigator.share === 'function' && (
                <button
                  type="button"
                  className="ob-button"
                  onClick={async () => {
                    try {
                      await navigator.share({
                        title: 'Приглашение в команду клуба',
                        url: link,
                      });
                    } catch {
                      setLinkMessage('Ссылку можно скопировать вручную.');
                    }
                  }}
                >
                  Поделиться приглашением
                </button>
              )}
              <button
                type="button"
                className="ob-button"
                onClick={() => {
                  setLink(undefined);
                  setLinkMessage('');
                }}
              >
                Скрыть ссылку
              </button>
              {linkMessage && <p role="status">{linkMessage}</p>}
            </section>
          )}
          {missingLinkId && !link && (
            <p className="po-notice">
              Приглашение №{missingLinkId} сохранено. Ссылка повторно не
              показывается. Если ссылка потеряна, отзовите это приглашение и
              создайте новое.
            </p>
          )}
          <section className="po-card ob-card">
            <h2>Пригласить сотрудника</h2>
            <form
              onSubmit={(event) => {
                event.preventDefault();
                if (blocked || !label.trim() || !selected?.assignable || intent)
                  return;
                state.run(
                  {
                    action: 'invite',
                    expectedVersion: view.version,
                    recipientLabel: label.trim(),
                    role,
                  },
                  (result) => {
                    setLabel('');
                    setLink(result.invitationLink);
                    setLinkId(result.createdInvitationId);
                    setMissingLinkId(
                      result.invitationLink
                        ? undefined
                        : result.createdInvitationId,
                    );
                    setLinkMessage('');
                  },
                );
              }}
            >
              <Field label="Подпись получателя">
                <input
                  maxLength={255}
                  value={label}
                  disabled={blocked || Boolean(intent)}
                  onChange={(event) => {
                    command.reset();
                    setLabel(event.target.value);
                  }}
                />
              </Field>
              <p className="po-muted">
                Подпись помогает вам найти приглашение. Username не используется
                для выдачи прав.
              </p>
              <Field label="Роль нового сотрудника">
                <select
                  value={role}
                  disabled={blocked || Boolean(intent)}
                  onChange={(event) => {
                    command.reset();
                    setRole(event.target.value as StaffRole);
                  }}
                >
                  {view.roles.map((item) => (
                    <option
                      key={item.code}
                      value={item.code}
                      disabled={!item.assignable}
                    >
                      {roleNames[item.code]}
                      {!item.assignable ? ' · недостаточно ваших прав' : ''}
                    </option>
                  ))}
                </select>
              </Field>
              <Permissions values={selected?.permissions ?? []} />
              <button
                className="po-primary"
                disabled={
                  blocked ||
                  Boolean(intent) ||
                  !label.trim() ||
                  !selected?.assignable
                }
              >
                Создать приглашение
              </button>
            </form>
          </section>
          <section className="ob-list" aria-label="Приглашения команды">
            <h2>Приглашения</h2>
            {view.invitations.length === 0 && <p>Приглашений пока нет.</p>}
            {view.invitations.map((invitation) => (
              <article className="po-card ob-card" key={invitation.id}>
                <h3>
                  {invitation.recipientLabel} · №{invitation.id}
                </h3>
                <p>
                  {roleNames[invitation.role as StaffRole]} ·{' '}
                  {invitationLabels[invitation.status]}
                </p>
                <p>
                  Принять до{' '}
                  {new Date(invitation.expiresAt).toLocaleString('ru-RU')}
                </p>
                {invitation.candidate && (
                  <p>
                    Кандидат:{' '}
                    {invitation.candidate.displayName ||
                      invitation.candidate.username ||
                      'Telegram-пользователь'}{' '}
                    · Telegram ID {invitation.candidate.telegramUserId}
                  </p>
                )}
                {invitation.availableActions.includes('confirm') && (
                  <button
                    type="button"
                    className="ob-button"
                    disabled={blocked || Boolean(intent)}
                    onClick={() =>
                      open(
                        'confirm',
                        invitation.id,
                        invitation.version,
                        invitation.role as StaffRole,
                      )
                    }
                  >
                    Проверить кандидата №{invitation.id}
                  </button>
                )}
                {invitation.availableActions.includes('revoke_invitation') && (
                  <button
                    type="button"
                    className="ob-button"
                    disabled={blocked || Boolean(intent)}
                    onClick={() =>
                      open(
                        'revoke_invitation',
                        invitation.id,
                        invitation.version,
                        invitation.role as StaffRole,
                      )
                    }
                  >
                    Отозвать приглашение №{invitation.id}
                  </button>
                )}
              </article>
            ))}
          </section>
          <section className="ob-list" aria-label="Сотрудники клуба">
            <h2>Сотрудники и доступ</h2>
            {view.members.filter((member) => !member.owner).length === 0 && (
              <p>
                Сотрудников пока нет. Вы можете работать с конфигурацией клуба
                без команды.
              </p>
            )}
            {view.members.map((member) => (
              <article className="po-card ob-card" key={member.userId}>
                <h3>
                  {member.displayName ||
                    member.username ||
                    'Telegram-пользователь'}{' '}
                  · №{member.userId}
                </h3>
                <p>
                  {member.owner
                    ? 'Владелец · защищённое назначение'
                    : member.role
                      ? roleNames[member.role]
                      : 'Роль в кабинете не назначена'}{' '}
                  · {member.active ? 'Доступ действует' : 'Доступ отключён'}
                </p>
                {!member.membershipActive && (
                  <p>
                    Доступ к организации отключён платформой. Владелец клуба не
                    может восстановить его.
                  </p>
                )}
                <Permissions values={member.permissions} />
                {member.canChange && (
                  <button
                    type="button"
                    className="ob-button"
                    disabled={blocked || Boolean(intent)}
                    onClick={() =>
                      open(
                        'change_role',
                        member.userId,
                        member.version!,
                        member.role!,
                      )
                    }
                  >
                    Изменить роль №{member.userId}
                  </button>
                )}
                {member.canDisable && (
                  <button
                    type="button"
                    className="ob-button"
                    disabled={blocked || Boolean(intent)}
                    onClick={() =>
                      open(
                        'disable',
                        member.userId,
                        member.version!,
                        member.role!,
                      )
                    }
                  >
                    Отключить доступ №{member.userId}
                  </button>
                )}
                {!member.active && !member.owner && (
                  <p>
                    Для повторного допуска создайте новое приглашение. Старые
                    ссылки доступ не восстанавливают.
                  </p>
                )}
              </article>
            ))}
          </section>
          {intent && (
            <section
              className="po-card ob-card"
              aria-label="Подтверждение изменения доступа"
            >
              <h2>Проверьте изменение доступа</h2>
              <p>
                {targetInvitation
                  ? `Приглашение: ${targetInvitation.recipientLabel} · №${targetInvitation.id}`
                  : `Сотрудник №${intent.target}`}
              </p>
              {intent.action === 'change_role' && (
                <Field label="Новая роль">
                  <select
                    value={intent.role}
                    disabled={blocked}
                    onChange={(event) =>
                      setIntent({
                        ...intent,
                        role: event.target.value as StaffRole,
                        confirmed: false,
                      })
                    }
                  >
                    {view.roles.map((item) => (
                      <option
                        key={item.code}
                        value={item.code}
                        disabled={!item.assignable}
                      >
                        {roleNames[item.code]}
                      </option>
                    ))}
                  </select>
                </Field>
              )}
              {['change_role', 'disable'].includes(intent.action) && (
                <>
                  <h3>Действующие права</h3>
                  <Permissions values={targetMember?.permissions ?? []} />
                  <Field label="Причина изменения доступа">
                    <textarea
                      value={intent.reason}
                      maxLength={500}
                      disabled={blocked}
                      onChange={(event) =>
                        setIntent({ ...intent, reason: event.target.value })
                      }
                    />
                  </Field>
                </>
              )}
              {['change_role', 'confirm'].includes(intent.action) && (
                <>
                  <h3>Будут выданы только эти права</h3>
                  <Permissions values={intentRole?.permissions ?? []} />
                </>
              )}
              {intent.action === 'disable' && (
                <p>
                  Все разрешения сотрудника в этом клубе будут отключены. Другие
                  клубы и история сохранятся.
                </p>
              )}
              {intent.action === 'confirm' && (
                <Field label="Код из исходного личного чата">
                  <input
                    value={intent.code}
                    inputMode="numeric"
                    maxLength={6}
                    disabled={blocked}
                    onChange={(event) =>
                      setIntent({
                        ...intent,
                        code: event.target.value,
                        confirmed: false,
                      })
                    }
                  />
                </Field>
              )}
              {conflict && (
                <div role="alert">
                  <p>
                    Состав команды изменился. Ваш ввод сохранён. Проверьте
                    актуальные данные перед новым подтверждением.
                  </p>
                  <button
                    className="ob-button"
                    disabled={blocked}
                    onClick={() => {
                      const version = ['change_role', 'disable'].includes(
                        intent.action,
                      )
                        ? targetMember?.version
                        : targetInvitation?.version;
                      if (version)
                        setIntent({
                          ...intent,
                          version: view.version,
                          targetVersion: version,
                          confirmed: false,
                        });
                      else setIntent(undefined);
                    }}
                  >
                    Проверить актуальные данные
                  </button>
                </div>
              )}
              <label className="ob-check">
                <input
                  type="checkbox"
                  checked={intent.confirmed}
                  disabled={blocked || conflict}
                  onChange={(event) =>
                    setIntent({ ...intent, confirmed: event.target.checked })
                  }
                />
                {intent.action === 'confirm'
                  ? 'Сверил Telegram-аккаунт и код в исходном личном чате'
                  : 'Проверил пакет прав и подтверждаю изменение'}
              </label>
              <button
                type="button"
                className="po-primary"
                disabled={!canSubmit}
                onClick={save}
              >
                Подтвердить изменение доступа
              </button>
              <button
                type="button"
                className="ob-button"
                disabled={command.blocked}
                onClick={() => {
                  setIntent(undefined);
                  command.reset();
                }}
              >
                Отменить изменение
              </button>
            </section>
          )}
        </>
      )}
      {!state.denied && (
        <CommandState
          command={command}
          refresh={command.unknown ? undefined : remote.refresh}
        />
      )}
    </Shell>
  );
}
