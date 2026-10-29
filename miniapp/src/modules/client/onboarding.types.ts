export interface Invitation {
  acceptanceMode?: 'direct' | 'legacy';
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
  savedAt: string;
  permissions: string[];
  availableActions: string[];
}
export interface ClientSummary {
  limitVersion?: string;
  allowedClubs?: number | null;
  createdClubs?: number;
  availableClubs?: number;
  id: string;
  name: string;
  active?: boolean;
  clubs?: Club[];
}
export interface Membership {
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
