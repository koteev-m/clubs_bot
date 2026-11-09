import { useEffect, useState } from 'react';
import { useClubConfiguration } from './configuration.hooks';
import { useRemote } from './onboarding.hooks';
import { TeamView, validTeam } from './team.types';
import {
  PreparationView,
  preparationLabels,
  validPreparation,
} from './preparation.types';
import {
  CommandState,
  Field,
  RemoteState,
  useUnsavedChanges,
} from './OnboardingShared';

export const preparationAccess =
  'Ответственный за подготовку: доступ к настройкам клуба, без управления сотрудниками, денежных операций и прав собственности';
type Props = {
  credential: string;
  id: string;
  clientId: string;
  reportDirty: (value: boolean) => void;
  reportPending: (value: boolean) => void;
  openTeam: () => void;
  openSettings: () => void;
  openReadiness: () => void;
  choice?: boolean;
  onChosen?: () => void;
  onView?: (view: PreparationView | undefined) => void;
  externallyBlocked?: boolean;
};
export default function ClubPreparation(props: Props) {
  return <Preparation key={props.credential + ':' + props.id} {...props} />;
}
function Preparation({
  credential,
  id,
  clientId,
  reportDirty,
  reportPending,
  openTeam,
  openSettings,
  openReadiness,
  choice,
  onChosen,
  onView,
  externallyBlocked = false,
}: Props) {
  const state = useClubConfiguration<PreparationView>(
    credential,
    id,
    '/preparation',
    (view) => view.clientId === clientId && validPreparation(view),
    [
      'canWrite',
      'canManage',
      'canSubmit',
      'canInvite',
      'assignee',
      'configurationChanged',
    ],
  );
  const { view, remote, command } = state;
  const blocked = state.blocked || externallyBlocked;
  useEffect(() => {
    onView?.(view);
  }, [view, onView]);
  const [delegating, setDelegating] = useState(false);
  const [label, setLabel] = useState('');
  const [selected, setSelected] = useState('');
  const [reason, setReason] = useState('');
  const [returning, setReturning] = useState(false);
  const [link, setLink] = useState<string>();
  const [linkId, setLinkId] = useState<string>();
  const [message, setMessage] = useState('');
  const team = useRemote<TeamView>(
    credential,
    delegating && view?.canManage
      ? '/api/client/clubs/' + id + '/team'
      : null,
    undefined,
    {
      onSuccess: (received) => {
        if (
          received.clubId !== id ||
          received.clientId !== clientId ||
          !validTeam(received)
        )
          throw new Error('Не удалось подтвердить команду этого клуба.');
      },
    },
  );
  const dirty = Boolean(label || reason || selected);
  useUnsavedChanges(dirty || command.unknown, reportDirty);
  useEffect(() => {
    reportPending(command.blocked && !state.denied);
    return () => reportPending(false);
  }, [command.blocked, state.denied, reportPending]);
  useEffect(() => {
    if (!state.denied) return;
    setLabel('');
    setSelected('');
    setReason('');
    setLink(undefined);
    setLinkId(undefined);
    setMessage('');
    setReturning(false);
    setDelegating(false);
  }, [state.denied]);
  useEffect(() => {
    if (
      view &&
      (!view.canManage ||
        !view.canInvite ||
        (linkId && view.invitation?.id !== linkId))
    ) {
      setLink(undefined);
      setLinkId(undefined);
      setMessage('');
    }
  }, [linkId, view]);
  const run = (action: string, values: object = {}, done?: () => void) => {
    if (!view || blocked) return;
    state.run(
      { action, expectedVersion: view.version, ...values },
      (saved) => {
        setReason('');
        setReturning(false);
        setSelected('');
        if (action === 'invite') {
          setLabel('');
          setLink(saved.invitationLink);
          setLinkId(saved.createdInvitationId);
          setMessage(
            saved.invitationLink
              ? ''
              : 'Приглашение сохранено. Ссылка повторно не показывается; проверьте приглашение в разделе «Команда».',
          );
        }
        done?.();
      },
      'PATCH',
    );
  };
  const members =
    team.data?.members.filter(
      (member) =>
        !member.owner &&
        member.active &&
        member.membershipActive &&
        member.role === 'CLUB_ADMIN' &&
        team
          .data!.roles.find((role) => role.code === 'CLUB_ADMIN')!
          .permissions.every((permission) =>
            member.permissions.includes(permission),
          ),
    ) ?? [];
  return (
    <section
      className="po-card ob-card cm-preparation"
      aria-label="Состояние подготовки"
    >
      <h2>{choice ? 'Кто будет настраивать клуб?' : 'Подготовка клуба'}</h2>
      <RemoteState {...remote} />
      {view && (
        <>
          <p className="cm-status" role="status">
            {view.status === 'invitation_pending' &&
            view.invitation &&
            ['expired', 'revoked'].includes(view.invitation.status)
              ? 'Требуется новое приглашение'
              : preparationLabels[view.status]}
          </p>
          <p>
            Ответственный:{' '}
            <strong>
              {view.assignee
                ? view.assignee.owner
                  ? 'Вы · владелец клуба'
                  : view.assignee.displayName ||
                    view.assignee.username ||
                    'Сотрудник №' + view.assignee.userId
                : 'Не назначен'}
            </strong>
          </p>
          {view.assignee && !view.assignee.active && (
            <p className="po-notice" role="alert">
              Доступ ответственного отозван или необходимых разрешений больше
              нет. Его действия по подготовке заблокированы. Назначьте
              ответственного заново.
            </p>
          )}
          {view.invitation && (
            <p>
              {view.invitation.recipientLabel}:{' '}
              {view.invitation.status === 'issued'
                ? 'ожидаем принятия в Telegram'
                : view.invitation.status === 'claimed'
                  ? 'аккаунт подтверждён; владелец должен сверить код'
                  : view.invitation.status === 'accepted'
                    ? 'доступ подтверждён'
                    : view.invitation.status === 'expired'
                      ? 'срок приглашения истёк'
                      : view.invitation.status === 'revoked'
                        ? 'приглашение отозвано'
                        : 'проверьте состояние в команде'}
            </p>
          )}
          {view.reason && (
            <p className="po-notice">Причина доработки: {view.reason}</p>
          )}
          {view.configurationChanged &&
            ['submitted', 'accepted'].includes(view.status) && (
              <p className="po-notice" role="alert">
                Настройки изменились после передачи. Передайте актуальный
                результат на проверку повторно.
              </p>
            )}
          {view.canManage && (
            <>
              {(choice || view.status === 'unassigned') && (
                <div className="cm-actions">
                  <button
                    type="button"
                    className="po-primary"
                    disabled={blocked}
                    onClick={() => setDelegating(true)}
                  >
                    Назначить ответственного за подготовку
                  </button>
                  <button
                    type="button"
                    className="ob-button"
                    disabled={blocked}
                    onClick={() =>
                      run('self', {}, () => {
                        onChosen?.();
                        openSettings();
                      })
                    }
                  >
                    Настроить самостоятельно
                  </button>
                  {choice && (
                    <button
                      type="button"
                      className="ob-button"
                      disabled={blocked}
                      onClick={() => run('later', {}, onChosen)}
                    >
                      Назначить позже
                    </button>
                  )}
                </div>
              )}
              {!choice && view.status !== 'unassigned' && (
                <button
                  type="button"
                  className="ob-button"
                  disabled={blocked}
                  onClick={() => setDelegating((value) => !value)}
                >
                  Изменить ответственного
                </button>
              )}
              {delegating && (
                <section
                  className="cm-delegation"
                  aria-label="Назначение ответственного"
                >
                  <h3>Ответственный за подготовку</h3>
                  <p className="po-notice">{preparationAccess}.</p>
                  <p className="po-muted">
                    Временный пакет CLUB_ADMIN. Это доступ к конфигурации, а
                    не новая техническая роль управляющего. Приглашение не
                    передаёт владение.
                  </p>
                  <RemoteState {...team} />
                  {team.data && (
                    <>
                      {members.length > 0 && (
                        <form
                          onSubmit={(event) => {
                            event.preventDefault();
                            if (selected)
                              run('assign', { userId: selected }, () => {
                                setDelegating(false);
                                onChosen?.();
                              });
                          }}
                        >
                          <Field label="Подтверждённый сотрудник">
                            <select
                              value={selected}
                              disabled={blocked}
                              onChange={(event) =>
                                setSelected(event.target.value)
                              }
                            >
                              <option value="">Выберите сотрудника</option>
                              {members.map((member) => (
                                <option
                                  key={member.userId}
                                  value={member.userId}
                                >
                                  {member.displayName ||
                                    member.username ||
                                    'Сотрудник №' + member.userId}
                                </option>
                              ))}
                            </select>
                          </Field>
                          <button
                            className="ob-button"
                            disabled={blocked || !selected}
                          >
                            Назначить сотрудника
                          </button>
                        </form>
                      )}
                      <form
                        onSubmit={(event) => {
                          event.preventDefault();
                          if (label.trim() && view.canInvite)
                            run(
                              'invite',
                              {
                                expectedTeamVersion: team.data!.version,
                                recipientLabel: label.trim(),
                              },
                              () => {
                                team.refresh();
                              },
                            );
                        }}
                      >
                        <Field label="Имя приглашённого ответственного">
                          <input
                            maxLength={255}
                            value={label}
                            disabled={blocked || !view.canInvite}
                            onChange={(event) => setLabel(event.target.value)}
                          />
                        </Field>
                        {!view.canInvite && (
                          <p className="po-notice">
                            Полный пакет доступа к настройкам недоступен для
                            выдачи. Частичный доступ не будет выдан.
                          </p>
                        )}
                        <button
                          className="po-primary"
                          disabled={
                            blocked || !label.trim() || !view.canInvite
                          }
                        >
                          Пригласить ответственного
                        </button>
                      </form>
                    </>
                  )}
                  <p>
                    Сотрудник принимает ссылку в Telegram. Затем вы сверяете
                    его аккаунт и код в личном чате и подтверждаете доступ в
                    разделе «Команда».
                  </p>
                  <button
                    type="button"
                    className="ob-button"
                    disabled={command.blocked}
                    onClick={() => {
                      if (
                        !dirty ||
                        window.confirm(
                          'Есть несохранённые изменения. Уйти без сохранения?',
                        )
                      )
                        openTeam();
                    }}
                  >
                    Открыть команду и подтверждение
                  </button>
                  <button
                    type="button"
                    className="ob-button"
                    disabled={blocked}
                    onClick={() => {
                      setDelegating(false);
                      setLabel('');
                      setSelected('');
                    }}
                  >
                    Закрыть назначение
                  </button>
                </section>
              )}
              {view.status === 'invitation_pending' && (
                <button
                  type="button"
                  className="ob-button"
                  disabled={command.blocked}
                  onClick={openTeam}
                >
                  Проверить приглашение в команде
                </button>
              )}
              {view.status === 'submitted' && (
                <div className="cm-actions">
                  <p>
                    Откройте сохранённые настройки и проверку готовности перед
                    решением.
                  </p>
                  <button
                    type="button"
                    className="po-primary"
                    disabled={
                      blocked ||
                      view.configurationChanged ||
                      !view.assignee?.active
                    }
                    onClick={() => run('accept')}
                  >
                    Принять подготовку
                  </button>
                  <button
                    type="button"
                    className="ob-button"
                    disabled={blocked}
                    onClick={() => setReturning(true)}
                  >
                    Вернуть на доработку
                  </button>
                </div>
              )}
              {returning && (
                <form
                  onSubmit={(event) => {
                    event.preventDefault();
                    if (reason.trim())
                      run('return', { reason: reason.trim() });
                  }}
                >
                  <Field label="Причина доработки">
                    <textarea
                      maxLength={500}
                      value={reason}
                      onChange={(event) => setReason(event.target.value)}
                      disabled={blocked}
                    />
                  </Field>
                  <button
                    className="po-primary"
                    disabled={blocked || !reason.trim()}
                  >
                    Сохранить возврат на доработку
                  </button>
                  <button
                    type="button"
                    className="ob-button"
                    disabled={command.blocked}
                    onClick={() => {
                      setReturning(false);
                      setReason('');
                    }}
                  >
                    Отмена
                  </button>
                </form>
              )}
              {(view.assignee || view.invitation) && (
                <details className="cm-unassign">
                  <summary>Снять ответственность за подготовку</summary>
                  <p>
                    Задача будет снята. Доступ сотрудника и приглашение
                    сохранятся. Чтобы отозвать доступ или ссылку, используйте
                    отдельное действие в разделе «Команда».
                  </p>
                  <button
                    type="button"
                    className="ob-button"
                    disabled={blocked}
                    onClick={() => run('unassign')}
                  >
                    Снять ответственность
                  </button>
                </details>
              )}
            </>
          )}
          {view.canSubmit && (
            <div className="cm-actions">
              <p>
                Проверьте сохранённые значения и незавершённые пункты.
                Передача на проверку не публикует клуб и не открывает
                бронирования.
              </p>
              <button
                type="button"
                className="po-primary"
                disabled={blocked}
                onClick={() => run('submit')}
              >
                Передать на проверку
              </button>
            </div>
          )}
          {view.status === 'submitted' && !view.canManage && (
            <p>
              Ожидаем решения владельца. Настройки доступны в пределах ваших
              текущих прав.
            </p>
          )}
          {link && (
            <section aria-label="Приглашение ответственного">
              <h3>Приглашение создано</h3>
              <Field label="Ссылка ответственного">
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
                    setMessage('Ссылка скопирована');
                  } catch {
                    setMessage('Выделите ссылку и скопируйте вручную.');
                  }
                }}
              >
                Скопировать ссылку
              </button>
              <button
                type="button"
                className="ob-button"
                onClick={() => {
                  setLink(undefined);
                  setMessage('Ссылка скрыта. Она не выдаётся повторно.');
                }}
              >
                Скрыть ссылку
              </button>
            </section>
          )}
          {message && <p role="status">{message}</p>}
          {!choice && (
            <button
              type="button"
              className="ob-button"
              disabled={command.blocked}
              onClick={openReadiness}
            >
              Проверка готовности
            </button>
          )}
          {choice && view.status !== 'unassigned' && (
            <button
              type="button"
              className="ob-button"
              disabled={command.blocked}
              onClick={onChosen}
            >
              Перейти к клубу
            </button>
          )}
        </>
      )}
      <CommandState command={command} />
    </section>
  );
}
