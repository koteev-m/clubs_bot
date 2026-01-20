import axios from 'axios';
import { http } from '../../../shared/api/http';

export type PromoterClub = {
  id: number;
  name: string;
  city: string;
};

export type PromoterEvent = {
  id: number;
  clubId: number;
  startUtc: string;
  endUtc: string;
  title?: string | null;
  isSpecial: boolean;
};

export type PromoterHall = {
  id: number;
  clubId: number;
  name: string;
  isActive: boolean;
};

export type PromoterTable = {
  id: number;
  hallId: number;
  clubId: number;
  label: string;
  minDeposit: number;
  capacity: number;
  zone?: string | null;
  zoneName?: string | null;
  arrivalWindow?: string | null;
  mysteryEligible: boolean;
  tableNumber: number;
  x: number;
  y: number;
};

export type PromoterGuestList = {
  id: number;
  clubId: number;
  eventId: number;
  promoterId?: number | null;
  ownerType: string;
  ownerUserId: number;
  name: string;
  limit: number;
  arrivalWindowStart?: string | null;
  arrivalWindowEnd?: string | null;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type PromoterGuestListEntry = {
  id: number;
  guestListId: number;
  displayName: string;
  telegramUserId?: number | null;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type PromoterGuestListStats = {
  added: number;
  invited: number;
  confirmed: number;
  declined: number;
  arrived: number;
  noShow: number;
};

export type PromoterGuestListDetails = {
  guestList: PromoterGuestList;
  entries: PromoterGuestListEntry[];
  stats: PromoterGuestListStats;
};

export type PromoterInvitationEntry = {
  entry: PromoterGuestListEntry;
  invitationUrl: string;
  qrPayload: string;
  expiresAt: string;
};

export type PromoterStatsItem = {
  clubId: number;
  eventId: number;
  totalAdded: number;
  totalArrived: number;
  conversion: number;
};

export type PromoterStats = {
  totalAdded: number;
  totalArrived: number;
  conversion: number;
  items: PromoterStatsItem[];
};

type ApiErrorPayload = {
  code: string;
  message?: string | null;
  status?: number | null;
  requestId?: string | null;
  details?: Record<string, string> | null;
};

export class PromoterApiError extends Error {
  status?: number;
  code?: string;
  details?: Record<string, string>;
  isAbort: boolean;

  constructor(
    message: string,
    options?: {
      status?: number;
      code?: string;
      details?: Record<string, string>;
      isAbort?: boolean;
    },
  ) {
    super(message);
    this.name = 'PromoterApiError';
    this.status = options?.status;
    this.code = options?.code;
    this.details = options?.details;
    this.isAbort = options?.isAbort ?? false;
  }
}

export const isAbortError = (error: unknown): boolean => {
  if (error instanceof PromoterApiError) return error.isAbort;
  if (axios.isAxiosError(error)) {
    return error.code === 'ERR_CANCELED' || error.name === 'CanceledError';
  }
  if (error instanceof DOMException) {
    return error.name === 'AbortError';
  }
  return false;
};

export const normalizePromoterError = (error: unknown): PromoterApiError => {
  if (error instanceof PromoterApiError) return error;
  if (isAbortError(error)) {
    return new PromoterApiError('Запрос отменен', { isAbort: true });
  }
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as ApiErrorPayload | undefined;
    return new PromoterApiError(payload?.message ?? 'Ошибка запроса', {
      status: payload?.status ?? error.response?.status,
      code: payload?.code,
      details: payload?.details ?? undefined,
    });
  }
  if (error instanceof Error) {
    return new PromoterApiError(error.message);
  }
  return new PromoterApiError('Неизвестная ошибка');
};

export const listPromoterClubs = async (signal?: AbortSignal): Promise<PromoterClub[]> => {
  try {
    const response = await http.get<PromoterClub[]>('/api/promoter/clubs', { signal });
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const listClubEvents = async (
  clubId: number,
  date: string,
  signal?: AbortSignal,
): Promise<PromoterEvent[]> => {
  try {
    const response = await http.get<PromoterEvent[]>('/api/promoter/club-events', {
      signal,
      params: { clubId, date },
    });
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const listClubHalls = async (clubId: number, signal?: AbortSignal): Promise<PromoterHall[]> => {
  try {
    const response = await http.get<PromoterHall[]>(`/api/promoter/clubs/${clubId}/halls`, { signal });
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const listHallTables = async (hallId: number, signal?: AbortSignal): Promise<PromoterTable[]> => {
  try {
    const response = await http.get<PromoterTable[]>(`/api/promoter/halls/${hallId}/tables`, { signal });
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const listGuestLists = async (
  params?: { clubId?: number; from?: string; to?: string },
  signal?: AbortSignal,
): Promise<PromoterGuestList[]> => {
  try {
    const response = await http.get<{ guestLists: PromoterGuestList[] }>('/api/promoter/guest-lists', {
      signal,
      params,
    });
    return response.data.guestLists;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const createGuestList = async (
  payload: {
    clubId: number;
    eventId: number;
    arrivalWindowStart?: string | null;
    arrivalWindowEnd?: string | null;
    limit: number;
    name?: string | null;
  },
  signal?: AbortSignal,
): Promise<{ guestList: PromoterGuestList; stats: PromoterGuestListStats }> => {
  try {
    const response = await http.post<{ guestList: PromoterGuestList; stats: PromoterGuestListStats }>(
      '/api/promoter/guest-lists',
      payload,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const getGuestListDetails = async (id: number, signal?: AbortSignal): Promise<PromoterGuestListDetails> => {
  try {
    const response = await http.get<PromoterGuestListDetails>(`/api/promoter/guest-lists/${id}`, { signal });
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const addGuestListEntry = async (
  id: number,
  displayName: string,
  signal?: AbortSignal,
): Promise<{ entry: PromoterGuestListEntry; stats: PromoterGuestListStats }> => {
  try {
    const response = await http.post<{ entry: PromoterGuestListEntry; stats: PromoterGuestListStats }>(
      `/api/promoter/guest-lists/${id}/entries`,
      { displayName },
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const addGuestListEntriesBulk = async (
  id: number,
  rawText: string,
  signal?: AbortSignal,
): Promise<{ addedCount: number }> => {
  try {
    const response = await http.post<{ addedCount: number }>(
      `/api/promoter/guest-lists/${id}/entries/bulk`,
      { rawText },
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const listInvitations = async (id: number, signal?: AbortSignal): Promise<PromoterInvitationEntry[]> => {
  try {
    const response = await http.get<{ entries: PromoterInvitationEntry[] }>(
      `/api/promoter/guest-lists/${id}/invitations`,
      { signal },
    );
    return response.data.entries;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const assignBooking = async (
  payload: { guestListEntryId: number; hallId: number; tableId: number; eventId: number },
  signal?: AbortSignal,
): Promise<{ bookingId: number }> => {
  try {
    const response = await http.post<{ bookingId: number }>('/api/promoter/bookings/assign', payload, { signal });
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};

export const getPromoterStats = async (signal?: AbortSignal): Promise<PromoterStats> => {
  try {
    const response = await http.get<PromoterStats>('/api/promoter/me/stats', { signal });
    return response.data;
  } catch (error) {
    throw normalizePromoterError(error);
  }
};
