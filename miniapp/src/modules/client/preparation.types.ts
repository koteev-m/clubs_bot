import type { ConfigurationView } from './configuration.hooks';
export const preparationLabels = {
  unassigned: 'Ответственный не назначен',
  invitation_pending: 'Приглашение ожидает принятия или подтверждения',
  in_progress: 'Подготовка в работе',
  submitted: 'Отправлено на проверку',
  accepted: 'Принято владельцем',
  returned: 'Возвращено на доработку',
};
export type PreparationView = ConfigurationView & {
  name: string;
  status: keyof typeof preparationLabels;
  published: boolean;
  assignee: null | {
    userId: string;
    displayName: string | null;
    username: string | null;
    owner: boolean;
    active: boolean;
  };
  invitation: null | { id: string; status: string; recipientLabel: string };
  reason: string | null;
  canManage: boolean;
  canSubmit: boolean;
  canInvite: boolean;
  configurationChanged: boolean;
  createdInvitationId?: string;
  invitationLink?: string;
};
export function validPreparation(view: PreparationView) {
  return (
    typeof view.status === 'string' &&
    Object.prototype.hasOwnProperty.call(preparationLabels, view.status) &&
    [
      'published',
      'canManage',
      'canSubmit',
      'canInvite',
      'configurationChanged',
    ].every(
      (key) => typeof view[key as keyof PreparationView] === 'boolean',
    ) &&
    (view.assignee === null ||
      (typeof view.assignee === 'object' &&
        /^[1-9][0-9]*$/.test(view.assignee.userId) &&
        typeof view.assignee.active === 'boolean' &&
        typeof view.assignee.owner === 'boolean')) &&
    (view.invitation === null ||
      (typeof view.invitation === 'object' &&
        /^[1-9][0-9]*$/.test(view.invitation.id))) &&
    (view.reason === null || typeof view.reason === 'string')
  );
}
