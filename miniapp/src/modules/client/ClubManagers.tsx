import { useEffect, useState } from 'react';
import { OnboardingError } from './onboarding.api';
import { roleNames, type StaffRole } from './team.types';
import {
  useClubConfiguration,
  type ConfigurationView,
} from './configuration.hooks';
import {
  businessRoleNames,
  invitationLabels,
  type Invitation,
  type ManagerRole,
} from './onboarding.types';
import {
  CommandState,
  Field,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from './OnboardingShared';

type Assignment = {
  id: string;
  userId: string;
  displayName: string | null;
  version: string;
  canRevoke: boolean;
};
type Manager = Assignment & { role: ManagerRole };
type LegacyAssignment = Assignment & { role: StaffRole };
type ManagerInvitation = Invitation & {
  acceptanceMode: 'manager';
  role: ManagerRole;
};
type Managers = ConfigurationView & {
  name: string;
  roles: ManagerRole[];
  assignments: Manager[];
  legacyAssignments?: LegacyAssignment[];
  invitations: ManagerInvitation[];
  createdInvitationId?: string;
  invitationLink?: string;
};
const isRole = (role: string) =>
  role === 'CLUB_MANAGER' || role === 'NETWORK_MANAGER';
function validManagers(view: Managers) {
  return (
    typeof view.name === 'string' &&
    Array.isArray(view.roles) &&
    view.roles.every(isRole) &&
    Array.isArray(view.assignments) &&
    view.assignments.every(
      (item) =>
        typeof item.id === 'string' &&
        isRole(item.role) &&
        /^[1-9]\d*$/.test(item.version),
    ) &&
    (view.legacyAssignments === undefined ||
      (Array.isArray(view.legacyAssignments) &&
        view.legacyAssignments.every(
          (item) =>
            typeof item.id === 'string' &&
            Object.hasOwn(roleNames, item.role) &&
            /^[1-9]\d*$/.test(item.version) &&
            typeof item.canRevoke === 'boolean',
        ))) &&
    Array.isArray(view.invitations) &&
    view.invitations.every(
      (item) =>
        item.acceptanceMode === 'manager' &&
        item.clubId === view.clubId &&
        item.clientId === view.clientId &&
        isRole(item.role) &&
        item.status in invitationLabels &&
        Array.isArray(item.availableActions),
    )
  );
}
type Props = {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (dirty: boolean) => void;
  reportPending: (pending: boolean) => void;
};
type Intent = {
  id: string;
  version: string;
  clubVersion: string;
  action:
    | 'confirm'
    | 'revoke_invitation'
    | 'revoke_assignment'
    | 'revoke_legacy_assignment';
};

export default function ClubManagers({
  credential,
  id,
  back,
  reportDirty,
  reportPending,
}: Props) {
  const { view, remote, command, blocked, denied, run } =
    useClubConfiguration<Managers>(credential, id, '/managers', validManagers, [
      'canWrite',
      'roles',
    ]);
  const [label, setLabel] = useState('');
  const [role, setRole] = useState<ManagerRole>('CLUB_MANAGER');
  const [intent, setIntent] = useState<Intent>();
  const [refused, setRefused] = useState(false);
  const refresh = remote.refresh;
  useEffect(() => {
    if (
      command.error instanceof OnboardingError &&
      command.error.status === 403
    ) {
      setRefused(true);
      refresh();
    }
  }, [command.error, refresh]);
  const [input, setInput] = useState('');
  const [issued, setIssued] = useState<{ id: string; link?: string }>();
  const leave = useUnsavedChanges(
    Boolean(label || intent || command.unknown),
    reportDirty,
  );
  useEffect(() => {
    reportPending(command.blocked && !denied);
    return () => reportPending(false);
  }, [command.blocked, denied, reportPending]);
  useEffect(() => {
    if (denied) {
      setLabel('');
      setIntent(undefined);
      setInput('');
      setIssued(undefined);
    }
  }, [denied]);
  const issuedInvitation = view?.invitations.find(
    (i) =>
      i.id === issued?.id &&
      i.status === 'issued' &&
      i.availableActions.includes('revoke'),
  );
  useEffect(() => {
    if (view && issued && !issuedInvitation) setIssued(undefined);
  }, [view, issued, issuedInvitation]);
  const invitation = view?.invitations.find((i) => i.id === intent?.id);
  const isAssignment =
    intent?.action === 'revoke_assignment' ||
    intent?.action === 'revoke_legacy_assignment';
  const legacyAssignment =
    intent?.action === 'revoke_legacy_assignment'
      ? view?.legacyAssignments?.find((item) => item.id === intent.id)
      : undefined;
  const managerAssignment =
    intent?.action === 'revoke_assignment'
      ? view?.assignments.find((item) => item.id === intent.id)
      : undefined;
  const assignment = legacyAssignment || managerAssignment;
  const validIntent =
    view &&
    intent &&
    intent.clubVersion === view.version &&
    (isAssignment
      ? assignment?.canRevoke === true &&
        assignment.version === intent.version &&
        input.trim().length > 0
      : invitation?.version === intent.version &&
        invitation.availableActions.includes(
          intent.action === 'confirm' ? 'confirm' : 'revoke',
        ) &&
        (intent.action !== 'confirm' || /^\d{6}$/.test(input)));
  const begin = (
    item: { id: string; version: string },
    action: Intent['action'],
  ) => {
    if (view && !blocked) {
      command.reset();
      setRefused(false);
      setInput('');
      setIntent({
        id: item.id,
        version: item.version,
        clubVersion: view.version,
        action,
      });
    }
  };
  return (
    <Shell
      title="Управляющие"
      subtitle={view?.name}
      back={() => {
        if (!command.blocked && leave()) back();
      }}
      backDisabled={command.blocked && !denied}
    >
      <RemoteState {...remote} />
      {refused && (
        <p className="po-notice" role="alert">
          Это действие недоступно. Проверьте действующие назначения.
        </p>
      )}
      {view && (
        <>
          <section className="po-card ob-card">
            <h2>Пригласить управляющего</h2>
            <p>Приглашение действует для заведения «{view.name}».</p>
            <form
              onSubmit={(event) => {
                event.preventDefault();
                if (
                  blocked ||
                  intent ||
                  !label.trim() ||
                  !view.roles.includes(role)
                )
                  return;
                run(
                  {
                    action: 'invite',
                    expectedVersion: view.version,
                    recipientLabel: label.trim(),
                    role,
                  },
                  (result) => {
                    setLabel('');
                    setIssued(
                      result.createdInvitationId
                        ? {
                            id: result.createdInvitationId,
                            link: result.invitationLink,
                          }
                        : undefined,
                    );
                  },
                );
              }}
            >
              <Field label="Имя получателя">
                <input
                  maxLength={255}
                  value={label}
                  disabled={blocked || Boolean(intent)}
                  onChange={(event) => setLabel(event.target.value)}
                />
              </Field>
              <Field label="Роль управляющего">
                <select
                  value={role}
                  disabled={blocked || Boolean(intent)}
                  onChange={(event) =>
                    setRole(event.target.value as ManagerRole)
                  }
                >
                  {view.roles.map((code) => (
                    <option key={code} value={code}>
                      {businessRoleNames[code]}
                    </option>
                  ))}
                </select>
              </Field>
              <button
                className="po-primary"
                disabled={
                  blocked ||
                  Boolean(intent) ||
                  !label.trim() ||
                  !view.roles.includes(role)
                }
              >
                Создать приглашение
              </button>
            </form>
            {issuedInvitation &&
              (issued?.link ? (
                <Field label="Ссылка для управляющего">
                  <input
                    readOnly
                    value={issued.link}
                    onFocus={(event) => event.target.select()}
                  />
                </Field>
              ) : (
                <p>
                  Приглашение создано. Если ссылка потеряна, отзовите
                  приглашение и создайте новое.
                </p>
              ))}
          </section>
          <section className="ob-section">
            <h2>Приглашения</h2>
            {view.invitations.length === 0 && <p>Приглашений пока нет.</p>}
            {view.invitations.map((item) => (
              <article key={item.id} className="po-card ob-card">
                <h3>{item.recipientLabel}</h3>
                <p>
                  {businessRoleNames[item.role]} ·{' '}
                  {invitationLabels[item.status]}
                </p>
                {item.candidate && (
                  <p>
                    Telegram:{' '}
                    {item.candidate.displayName ||
                      item.candidate.username ||
                      item.candidate.telegramUserId}{' '}
                    · ID {item.candidate.telegramUserId}
                  </p>
                )}
                {item.availableActions.includes('confirm') && (
                  <button
                    className="ob-button"
                    disabled={blocked || Boolean(intent)}
                    onClick={() => begin(item, 'confirm')}
                  >
                    Подтвердить назначение
                  </button>
                )}
                {item.availableActions.includes('revoke') && (
                  <button
                    className="ob-button"
                    disabled={blocked || Boolean(intent)}
                    onClick={() => begin(item, 'revoke_invitation')}
                  >
                    Отозвать приглашение
                  </button>
                )}
              </article>
            ))}
          </section>
          <section className="ob-section">
            <h2>Действующие назначения</h2>
            {view.assignments.length === 0 && (
              <p>Управляющие пока не назначены.</p>
            )}
            {view.assignments.map((item) => (
              <article className="po-card ob-card" key={item.id}>
                <h3>{item.displayName || 'Пользователь №' + item.userId}</h3>
                <p>
                  {businessRoleNames[item.role]} · {view.name}
                </p>
                {item.canRevoke === true && (
                  <button
                    className="ob-button"
                    disabled={blocked || Boolean(intent)}
                    onClick={() => begin(item, 'revoke_assignment')}
                  >
                    Отозвать назначение
                  </button>
                )}
              </article>
            ))}
          </section>
          {view.legacyAssignments && view.legacyAssignments.length > 0 && (
            <section className="ob-section">
              <h2>Прежние назначения сотрудников</h2>
              {view.legacyAssignments.map((item) => (
                <article className="po-card ob-card" key={item.id}>
                  <h3>{item.displayName || 'Пользователь №' + item.userId}</h3>
                  <p>
                    {roleNames[item.role]} · {view.name}
                  </p>
                  {item.canRevoke === true && (
                    <button
                      className="ob-button"
                      disabled={blocked || Boolean(intent)}
                      onClick={() => begin(item, 'revoke_legacy_assignment')}
                    >
                      Отозвать прежнее назначение
                    </button>
                  )}
                </article>
              ))}
            </section>
          )}
          {intent && (
            <section className="po-card ob-card">
              <h2>
                {intent.action === 'confirm'
                  ? 'Подтверждение назначения'
                  : 'Подтверждение отзыва'}
              </h2>
              <p>
                {isAssignment
                  ? assignment?.displayName || assignment?.userId
                  : invitation?.recipientLabel}{' '}
                · {view.name}
              </p>
              {legacyAssignment && (
                <p>Прежнее назначение · {roleNames[legacyAssignment.role]}</p>
              )}
              {managerAssignment && (
                <p>{businessRoleNames[managerAssignment.role]}</p>
              )}
              {intent.action === 'confirm' && (
                <Field label="Код из Telegram получателя">
                  <input
                    inputMode="numeric"
                    maxLength={6}
                    value={input}
                    onChange={(event) => setInput(event.target.value)}
                  />
                </Field>
              )}
              {isAssignment && (
                <Field label="Причина отзыва">
                  <input
                    maxLength={255}
                    value={input}
                    onChange={(event) => setInput(event.target.value)}
                  />
                </Field>
              )}
              {view.version !== intent.clubVersion && (
                <p role="alert">
                  Список обновился. Отмените действие и проверьте назначение ещё
                  раз.
                </p>
              )}
              <button
                className="po-primary"
                disabled={blocked || !validIntent}
                onClick={() => {
                  if (blocked || !validIntent) return;
                  run(
                    {
                      action: intent.action,
                      expectedVersion: intent.clubVersion,
                      confirmed: true,
                      ...(isAssignment
                        ? {
                            assignmentId: intent.id,
                            expectedAssignmentVersion: intent.version,
                            reason: input.trim(),
                          }
                        : {
                            invitationId: intent.id,
                            expectedInvitationVersion: intent.version,
                            ...(intent.action === 'confirm'
                              ? { verificationCode: input }
                              : {}),
                          }),
                    },
                    () => {
                      setIntent(undefined);
                      setInput('');
                    },
                  );
                }}
              >
                {intent.action === 'confirm'
                  ? 'Назначить управляющего'
                  : 'Подтвердить отзыв'}
              </button>
              <button
                className="ob-button"
                disabled={command.blocked}
                onClick={() => {
                  setIntent(undefined);
                  setInput('');
                }}
              >
                Отмена
              </button>
            </section>
          )}
        </>
      )}
      <CommandState command={command} refresh={remote.refresh} />
    </Shell>
  );
}
