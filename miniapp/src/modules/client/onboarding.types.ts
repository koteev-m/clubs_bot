export const businessRoleNames = {
  BUSINESS_OWNER: 'Владелец бизнеса',
  NETWORK_MANAGER: 'Управляющий сети',
  CLUB_MANAGER: 'Управляющий клуба',
} as const;
export type BusinessRole = keyof typeof businessRoleNames;
export type ManagerRole = Exclude<BusinessRole, 'BUSINESS_OWNER'>;

export interface Invitation {
  acceptanceFlow?: 'direct' | 'confirmation';
  clubs?: Club[];
  acceptanceMode?: 'direct' | 'legacy' | 'staff' | 'manager';
  clubId?: string;
  clubName?: string;
  role?: 'ENTRY_MANAGER' | 'MANAGER' | 'CLUB_ADMIN' | ManagerRole;
  permissions?: string[];
  id: string;
  clientId: string;
  clientName: string;
  recipientLabel: string;
  status: 'issued' | 'claimed' | 'accepted' | 'revoked' | 'expired';
  version: string;
  expiresAt: string;
  candidate: null | {
    userId: string;
    telegramUserId: string;
    displayName: string | null;
    username: string | null;
  };
  verificationCode: string | null;
  availableActions: string[];
  token?: string;
  invitationLink?: string;
}
export interface Club {
  id: string;
  name: string;
  permissions?: string[];
}
export interface ClubProfile {
  businessRole?: BusinessRole | null;
  region?: string;
  id: string;
  clientId: string;
  clientName: string;
  name: string;
  description: string;
  timezone: string;
  countryCode: string;
  city: string;
  address: string;
  unit: string;
  version: string;
  savedAt: string | null;
  canPreviewBooking?: boolean;
  permissions: string[];
  availableActions: string[];
}
export interface ClientSummary {
  businessOwner?: boolean;
  archived?: boolean;
  archivedAt?: string | null;
  version?: string;
  activeMembers?: number;
  archiveBlockers?: string[];
  availableActions?: string[];
  limitVersion?: string;
  allowedClubs?: number | null;
  createdClubs?: number;
  availableClubs?: number;
  id: string;
  name: string;
  active?: boolean;
  clubs?: Club[];
}
export interface PlatformClub {
  id: string;
  name: string;
  clientId: string;
  clientName: string;
  published: boolean;
  clientActive: boolean;
  clientArchived: boolean;
}
export interface Membership {
  canConfirmBusinessOwner?: boolean;
  businessOwner?: boolean;
  businessClubIds?: string[];
  businessClubsForReview?: {
    clubId: string;
    clubName: string;
    grants: { permission: string; userId: string; updatedAt: string }[];
  }[];
  userId: string;
  telegramUserId: string;
  displayName: string | null;
  username: string | null;
  active: boolean;
  version: string;
  permissions: string[];
  restorablePermissions: string[];
  clubGrants: {
    clubId: string;
    clubName: string;
    permission: string;
    active: boolean;
  }[];
}
export interface ClientDetail extends ClientSummary {
  members: Membership[];
  clubs: Club[];
  invitations: Invitation[];
}
export interface OnboardingContext extends ClientSummary {
  showClubLimit?: boolean;
  canCreate?: boolean;
  businessOwnerRequired?: boolean;
  permissions: string[];
  clubs: Club[];
}
export interface VenueFields {
  venueName: string;
  countryCode: string;
  city: string;
  address: string;
  unit: string;
  timezone: string;
  comment: string;
}
export type ApplicationStatus =
  | 'draft'
  | 'submitted'
  | 'reviewing'
  | 'approved'
  | 'rejected'
  | 'cancelled';
export interface ClubApplication extends VenueFields {
  id: string;
  clientId: string;
  clientName: string;
  authorUserId: string;
  status: ApplicationStatus;
  version: string;
  previousApplicationId: string | null;
  createdClubId?: string | null;
  submittedAt: string | null;
  decisionReason: string | null;
  history: { status: ApplicationStatus; at: string; reason: string | null }[];
  availableActions: string[];
  commercialReference?: string | null;
  commercialAgreedAt?: string | null;
  internalNote?: string | null;
  author?: {
    userId: string;
    telegramUserId: string;
    displayName: string | null;
    username: string | null;
  };
}
export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}
export const applicationLabels: Record<ApplicationStatus, string> = {
  draft: 'Черновик',
  submitted: 'Отправлена',
  reviewing: 'На рассмотрении',
  approved: 'Одобрена',
  rejected: 'Отклонена',
  cancelled: 'Отменена',
};
export const invitationLabels: Record<Invitation['status'], string> = {
  issued: 'Ожидает получателя',
  claimed: 'Аккаунт подтверждён',
  accepted: 'Доступ предоставлен',
  revoked: 'Приглашение отозвано',
  expired: 'Срок приглашения истёк',
};

export interface LocationCity {
  id: string;
  countryCode: string;
  name: string;
  region: string;
  timezone: string;
}
export interface NewVenueFields {
  cityQuery?: string;
  cityLabel?: string;
  venueName: string;
  countryCode: string;
  cityId: string;
  address: string;
}
