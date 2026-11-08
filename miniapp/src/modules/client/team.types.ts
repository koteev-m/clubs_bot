import type { ConfigurationView } from './configuration.hooks';
import type { Invitation } from './onboarding.types';
export type StaffRole = 'ENTRY_MANAGER' | 'MANAGER' | 'CLUB_ADMIN';
export const roleNames: Record<StaffRole, string> = {
  ENTRY_MANAGER: 'Host / менеджер входа',
  MANAGER: 'Менеджер клуба',
  CLUB_ADMIN: 'Администратор клуба',
};
export const permissionNames: Record<string, string> = {
  'client.club.read': 'Просмотр клуба, профиля, залов, календаря и правил',
  'client.club.profile.write': 'Изменение профиля',
  'client.club.layout.write': 'Настройка залов, зон, схем и столов',
  'client.club.calendar.write': 'Настройка календаря и формирование ночей',
  'client.club.booking_rules.write': 'Настройка правил бронирования',
  'client.club.finance.read': 'Просмотр финансового шаблона',
  'client.club.finance.write': 'Изменение финансового шаблона',
  'client.club.modules.read': 'Просмотр настроек модулей',
  'client.club.modules.write': 'Настройка правила раннего прихода',
  'client.club.team.manage': 'Управление командой',
};
export type StaffMember = {
  userId: string;
  displayName: string | null;
  username: string | null;
  role: StaffRole | null;
  owner: boolean;
  version: string | null;
  active: boolean;
  membershipActive: boolean;
  permissions: string[];
  canChange: boolean;
  canDisable: boolean;
};
export type TeamView = ConfigurationView & {
  name: string;
  roles: { code: StaffRole; permissions: string[]; assignable: boolean }[];
  members: StaffMember[];
  invitations: Invitation[];
  createdInvitationId?: string;
  invitationLink?: string;
  token?: string;
};
const id = (value: unknown) =>
  typeof value === 'string' && /^[1-9][0-9]*$/.test(value);
export function validTeam(view: TeamView): boolean {
  return (
    Array.isArray(view.roles) &&
    view.roles.length === 3 &&
    new Set(view.roles.map((role) => role.code)).size === 3 &&
    view.roles.every(
      (role) =>
        role.code in roleNames &&
        Array.isArray(role.permissions) &&
        typeof role.assignable === 'boolean',
    ) &&
    Array.isArray(view.members) &&
    view.members.every(
      (member) =>
        id(member.userId) &&
        (member.role === null || member.role in roleNames) &&
        (member.version === null || id(member.version)) &&
        typeof member.active === 'boolean' &&
        typeof member.canChange === 'boolean' &&
        typeof member.canDisable === 'boolean' &&
        Array.isArray(member.permissions),
    ) &&
    Array.isArray(view.invitations) &&
    view.invitations.every(
      (invitation) =>
        id(invitation.id) &&
        id(invitation.version) &&
        invitation.clubId === view.clubId &&
        invitation.clientId === view.clientId &&
        invitation.acceptanceMode === 'staff' &&
        typeof invitation.role === 'string' &&
        invitation.role in roleNames &&
        ['issued', 'claimed', 'accepted', 'expired', 'revoked'].includes(
          invitation.status,
        ),
    )
  );
}
