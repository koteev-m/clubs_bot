import { http } from '../../../shared/api/http';

export interface HostCheckinRequest {
  clubId: number;
  eventId: number;
  bookingId?: string;
  guestListEntryId?: number;
  invitationToken?: string;
  action?: 'ARRIVE' | 'DENY';
  denyReason?: string;
}

export interface HostScanRequest {
  clubId: number;
  eventId: number;
  qrPayload: string;
}

export interface HostCheckinResponse {
  outcomeStatus: 'ARRIVED' | 'LATE' | 'DENIED';
  denyReason?: string;
  subject: {
    kind: 'BOOKING' | 'GUEST_LIST_ENTRY' | 'INVITATION';
    bookingId?: string;
    guestListEntryId?: number;
    invitationId?: number;
  };
  bookingStatus?: string;
  entryStatus?: string;
  occurredAt?: string;
}

export interface HostSearchItem {
  kind: 'BOOKING' | 'GUEST_LIST_ENTRY';
  displayName: string;
  bookingId?: string;
  guestListEntryId?: number;
  status: string;
  guestCount: number;
  arrived: boolean;
  tableNumber?: number;
  arrivalWindowStart?: string;
  arrivalWindowEnd?: string;
}

export interface WaitlistEntry {
  id: number;
  clubId: number;
  eventId: number;
  userId: number;
  partySize: number;
  status: string;
  calledAt?: string;
  expiresAt?: string;
  createdAt: string;
}

export interface ChecklistItem {
  id: string;
  section: string;
  text: string;
  done: boolean;
  updatedAt?: string | null;
  actorId?: number | null;
}

export interface ChecklistResponse {
  clubId: number;
  eventId: number;
  now: string;
  items: ChecklistItem[];
}

/** Check-in guest via host endpoint. */
export function hostCheckin(payload: HostCheckinRequest) {
  return http.post<HostCheckinResponse>('/api/host/checkin', payload);
}

/** Scan QR via host endpoint. */
export function hostScan(payload: HostScanRequest, signal?: AbortSignal) {
  return http.post<HostCheckinResponse>('/api/host/checkin/scan', payload, { signal });
}

/** Search guests by name in host mode. */
export function searchHostGuests(clubId: number, eventId: number, query: string, limit = 20) {
  const params = new URLSearchParams({
    clubId: String(clubId),
    eventId: String(eventId),
    query,
    limit: String(limit),
  });
  return http.get<HostSearchItem[]>(`/api/host/checkin/search?${params.toString()}`);
}

/** Load waitlist in host mode. */
export function fetchWaitlist(clubId: number, eventId: number) {
  const params = new URLSearchParams({ clubId: String(clubId), eventId: String(eventId) });
  return http.get<WaitlistEntry[]>(`/api/host/waitlist?${params.toString()}`);
}

/** Invite waitlist entry in host mode (may be disabled). */
export function inviteWaitlistEntry(id: number, clubId: number, eventId: number) {
  return http.post(`/api/host/waitlist/${id}/invite`, { clubId, eventId });
}

/** Load shift checklist. */
export function fetchChecklist(clubId: number, eventId: number) {
  const params = new URLSearchParams({ clubId: String(clubId), eventId: String(eventId) });
  return http.get<ChecklistResponse>(`/api/host/checklist?${params.toString()}`);
}

/** Update shift checklist item. */
export function updateChecklistItem(clubId: number, eventId: number, itemId: string, done: boolean) {
  const params = new URLSearchParams({ clubId: String(clubId), eventId: String(eventId) });
  return http.post<ChecklistResponse>(`/api/host/checklist?${params.toString()}`, { itemId, done });
}
