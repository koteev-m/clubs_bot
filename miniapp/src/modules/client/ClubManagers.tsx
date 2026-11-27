import InvitationDelivery, { invitationScope } from './InvitationDelivery';
import { useEffect, useState } from 'react';
import { OnboardingError } from './onboarding.api';
import { permissionNames, roleNames, type StaffRole } from './team.types';
import {
  useClubConfiguration,
  type ConfigurationView,
} from './configuration.hooks';
import {
  businessRoleNames,
  invitationLabels,
  isManagerRole,
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
type Delegation = { id: string; version: string; permission: string };
type Manager = Assignment & {
  role: ManagerRole;
  permissions?: string[];
  delegations?: Delegation[];
};
type LegacyAssignment = Assignment & { role: StaffRole };
type ManagerInvitation = Invitation & {
  acceptanceMode: 'manager';
  role: ManagerRole;
};
type Managers = ConfigurationView & {
  name: string;
  roles: ManagerRole[];
  rolePermissions?: Partial<Record<ManagerRole, string[]>>;
  delegablePermissions?: string[];
  assignableClubs?: { id: string; name: string }[];
  assignments: Manager[];
  legacyAssignments?: LegacyAssignment[];
  invitations: ManagerInvitation[];
  createdInvitationId?: string;
  invitationLink?: string;
};
const sectionNames: Record<string, string> = {
  'client.club.profile.write': 'Профиль клуба',
  'client.club.layout.write': 'Залы и столы',
  'client.club.calendar.write': 'Календарь и расписание',
  'client.club.night.create': 'Формирование операционной ночи',
  'client.club.booking_rules.write': 'Правила бронирования',
};
const operationNames: Record<string, string> = {
  'client.club.read': 'Доступ к назначенному клубу',
  'client.club.calendar.write': 'Изменение календаря и расписания',
  'client.club.night.create': 'Формирование операционной ночи',
  'client.club.operations.read': 'Рабочая ночь, брони и столы',
  'client.club.operations.booking.write': 'Операции с бронированиями',
  'client.club.operations.seat': 'Посадка гостей',
  'client.club.operations.release': 'Освобождение стола',
  'client.club.operations.table_money.read': 'Просмотр поступлений по столам',
  'client.club.operations.deposit.record': 'Запись полученного депозита',
  'client.club.operations.top_up.record': 'Запись полученной доплаты',
  'client.club.operations.analytics.read': 'Операционная аналитика клуба',
  'client.club.operations.deposit_categories.write': 'Настройка категорий депозитов',
  'client.club.operations.team.manage': 'Назначения и делегирование команде',
  'client.staff.assign.club_manager': 'Назначение управляющих клубом',
  'client.staff.revoke.club_manager': 'Отзыв управляющих клубом',
  'client.club.reports.read': 'Просмотр отчётов клуба',
};
function permissionList(permissions: string[] = []) {
  return permissions.flatMap((code) => {
    const label = operationNames[code] ?? permissionNames[code];
    return label ? [{ code, label }] : [];
  });
}
function validManagers(view: Managers) {
  return (
    typeof view.name === 'string' &&
    Array.isArray(view.roles) &&
    view.roles.every(isManagerRole) &&
    (view.rolePermissions === undefined ||
      Object.entries(view.rolePermissions).every(([role, codes]) =>
        isManagerRole(role) && Array.isArray(codes) && codes.every((value) => typeof value === 'string'),
      )) &&
    (view.delegablePermissions === undefined ||
      (Array.isArray(view.delegablePermissions) &&
        view.delegablePermissions.every((code) => Object.hasOwn(sectionNames, code)))) &&
    (view.assignableClubs === undefined ||
      (Array.isArray(view.assignableClubs) &&
        view.assignableClubs.every(
          (club) =>
            /^[1-9]\d*$/.test(club.id) && typeof club.name === 'string',
        ))) &&
    Array.isArray(view.assignments) &&
    view.assignments.every(
      (item) =>
        typeof item.id === 'string' &&
        isManagerRole(item.role) &&
        /^[1-9]\d*$/.test(item.version) &&
        (item.delegations === undefined ||
          (Array.isArray(item.delegations) && item.delegations.every(
            (grant) =>
              typeof grant.id === 'string' &&
              /^[1-9]\d*$/.test(grant.version) &&
              Object.hasOwn(sectionNames, grant.permission),
          ))),
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
        isManagerRole(item.role) &&
        item.status in invitationLabels &&
        Array.isArray(item.availableActions),
    )
  );
}
type Props = {
  inviting: boolean;
  receiptId?: string;
  issued: (id: string) => void;
  openInvite: () => void;
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
  inviting,
  receiptId,
  issued: showDelivery,
  openInvite,
  credential,
  id,
  back,
  reportDirty,
  reportPending,
}: Props) {
  const { view, remote, command, blocked, denied, run } =
    useClubConfiguration<Managers>(
      credential,
      id,
      '/managers',
      validManagers,
      ['canWrite', 'roles', 'assignableClubs', 'rolePermissions', 'delegablePermissions'],
    );
  const [label, setLabel] = useState('');
  const [extraClubIds, setExtraClubIds] = useState<string[]>([]);
  const [chosenRole, setRole] = useState<ManagerRole>('CLUB_MANAGER');
  const role = view?.roles.includes(chosenRole) ? chosenRole : view?.roles[0] ?? chosenRole;
  const team = Boolean(view?.roles.includes('FLOOR_MANAGER'));
  const packagePermissions = permissionList(view?.rolePermissions?.[role]);
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
    Boolean(
      label ||
        chosenRole !== 'CLUB_MANAGER' ||
        extraClubIds.length ||
        intent ||
        command.unknown,
    ),
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
      setExtraClubIds([]);
      setRole('CLUB_MANAGER');
    }
  }, [denied]);
  const issuedInvitation = view?.invitations.find(
    (i) =>
      i.id === receiptId &&
      i.status === 'issued' &&
      i.availableActions.includes('revoke'),
  );
  useEffect(() => {
    if (view && issued && !issuedInvitation) setIssued(undefined);
  }, [view, issued, issuedInvitation]);
  const otherClubs =
    view?.assignableClubs?.filter((club) => club.id !== id) ?? [];
  const clubIds = [
    id,
    ...otherClubs
      .filter((club) => extraClubIds.includes(club.id))
      .map((club) => club.id),
  ];
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
      title={
        inviting
          ? team ? 'Пригласить сотрудника' : 'Пригласить управляющего'
          : receiptId
            ? team ? 'Приглашение в команду' : 'Приглашение управляющего'
            : team ? 'Команда клуба' : 'Управляющие'
      }
      subtitle={view?.name}
      back={() => {
        if (denied || (!command.blocked && leave())) back();
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
          {inviting || receiptId ? (
            <section className="po-card ob-card">
              {inviting && (
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
                        ...(role === 'NETWORK_MANAGER' && clubIds.length > 1
                          ? { clubIds }
                          : {}),
                      },
                      (result) => {
                        setLabel('');
                        setRole('CLUB_MANAGER');
                        setExtraClubIds([]);
                        setIssued(
                          result.createdInvitationId
                            ? {
                                id: result.createdInvitationId,
                                link: result.invitationLink,
                              }
                            : undefined,
                        );
                        if (result.createdInvitationId) showDelivery(result.createdInvitationId);
                      },
                    );
                  }}
                >
                  <p>Заведение: {view.name}</p>
                  <Field label="Имя получателя">
                    <input
                      maxLength={255}
                      value={label}
                      disabled={blocked || Boolean(intent)}
                      onChange={(event) => setLabel(event.target.value)}
                    />
                  </Field>
                  <Field label={team ? "Роль сотрудника" : "Роль управляющего"}>
                    <select
                      value={role}
                      disabled={blocked || Boolean(intent)}
                      onChange={(event) => {
                        setRole(event.target.value as ManagerRole);
                        setExtraClubIds([]);
                      }}
                    >
                      {view.roles.map((code) => (
                        <option key={code} value={code}>
                          {businessRoleNames[code]}
                        </option>
                      ))}
                    </select>
                  </Field>
                  {packagePermissions.length > 0 && (
                    <section>
                      <h2>Полномочия назначения</h2>
                      <ul>
                        {packagePermissions.map(({ code, label }) => <li key={code}>{label}</li>)}
                      </ul>
                    </section>
                  )}
                  {role === 'NETWORK_MANAGER' && otherClubs.length > 0 && (
                    <fieldset disabled={blocked || Boolean(intent)}>
                      <legend>Дополнительные заведения</legend>
                      {otherClubs.map((club) => (
                        <label className="ob-checkbox" key={club.id}>
                          <input
                            type="checkbox"
                            checked={extraClubIds.includes(club.id)}
                            onChange={(event) =>
                              setExtraClubIds(
                                event.target.checked
                                  ? [...extraClubIds, club.id]
                                  : extraClubIds.filter(
                                      (value) => value !== club.id,
                                    ),
                              )
                            }
                          />
                          {club.name}
                        </label>
                      ))}
                    </fieldset>
                  )}
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
              )}
              {receiptId && !issuedInvitation && (
                <p>Ссылка больше недоступна. Вернитесь к списку, чтобы проверить приглашение.</p>
              )}
              {receiptId && issuedInvitation &&
                (issued?.id === receiptId && issued?.link ? (
                  <InvitationDelivery
                    key={issuedInvitation.id}
                    prefix="cm"
                    label={team ? "Ссылка для сотрудника" : "Ссылка для управляющего"}
                    link={issued.link}
                    recipient={issuedInvitation.recipientLabel}
                    role={businessRoleNames[issuedInvitation.role]}
                    scope={invitationScope(issuedInvitation)}
                    expiresAt={issuedInvitation.expiresAt}
                  />
                ) : (
                  <p>
                    Приглашение создано. Если ссылка потеряна, отзовите
                    приглашение и создайте новое.
                  </p>
                ))}
            </section>
          ) : (
            <>
              {view.canWrite && view.roles.length > 0 && (
                <button
                  className="po-primary"
                  disabled={blocked || Boolean(intent)}
                  onClick={openInvite}
                >
                  {team ? 'Пригласить сотрудника' : 'Пригласить управляющего'}
                </button>
              )}
              <section className="ob-section">
                <h2>Приглашения</h2>
                {view.invitations.length === 0 && (
                  <p>Приглашений пока нет.</p>
                )}
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
                  <p>{team ? 'Сотрудники пока не назначены.' : 'Управляющие пока не назначены.'}</p>
                )}
                {view.assignments.map((item) => (
                  <article className="po-card ob-card" key={item.id}>
                    <h3>
                      {item.displayName || 'Пользователь №' + item.userId}
                    </h3>
                    <p>
                      {businessRoleNames[item.role]} · {view.name}
                    </p>
                    {item.role === 'FLOOR_MANAGER' && item.canRevoke && Boolean(view.delegablePermissions?.length) && (
                      <fieldset disabled={blocked || Boolean(intent)}>
                        <legend>Делегированные настройки</legend>
                        {view.delegablePermissions?.map((permission) => {
                          const grant = item.delegations?.find((value) => value.permission === permission);
                          return (
                            <div className="cm-actions" key={permission}>
                              <button
                                className="ob-button"
                                type="button"
                                onClick={() =>
                                  run({
                                    action: grant ? 'revoke_delegation' : 'delegate',
                                    expectedVersion: view.version,
                                    assignmentId: item.id,
                                    expectedAssignmentVersion: item.version,
                                    confirmed: true,
                                    ...(grant
                                      ? { delegationId: grant.id, expectedDelegationVersion: grant.version }
                                      : { permission }),
                                  }, () => undefined)
                                }
                              >
                                {grant ? 'Отозвать доступ' : 'Выдать доступ'} · {sectionNames[permission]}
                              </button>
                            </div>
                          );
                        })}
                      </fieldset>
                    )}
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
              {view.legacyAssignments &&
                view.legacyAssignments.length > 0 && (
                  <section className="ob-section">
                    <h2>Прежние назначения сотрудников</h2>
                    {view.legacyAssignments.map((item) => (
                      <article className="po-card ob-card" key={item.id}>
                        <h3>
                          {item.displayName || 'Пользователь №' + item.userId}
                        </h3>
                        <p>
                          {roleNames[item.role]} · {view.name}
                        </p>
                        {item.canRevoke === true && (
                          <button
                            className="ob-button"
                            disabled={blocked || Boolean(intent)}
                            onClick={() =>
                              begin(item, 'revoke_legacy_assignment')
                            }
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
                    <p>
                      Прежнее назначение · {roleNames[legacyAssignment.role]}
                    </p>
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
                      Список обновился. Отмените действие и проверьте
                      назначение ещё раз.
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
        </>
      )}
      <CommandState command={command} refresh={remote.refresh} />
    </Shell>
  );
}
