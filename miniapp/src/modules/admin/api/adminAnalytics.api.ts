import { http } from '../../../shared/api/http';
import { normalizeAdminError } from './admin.api';

export type AnalyticsMeta = {
  hasIncompleteData: boolean;
  caveats: string[];
};

export type AttendanceChannel = {
  plannedGuests: number;
  arrivedGuests: number;
  noShowGuests: number;
  noShowRate: number;
};

export type AttendanceChannels = {
  directBookings: AttendanceChannel;
  promoterBookings: AttendanceChannel;
  guestLists: AttendanceChannel;
};

export type AttendanceHealth = {
  bookings: AttendanceChannel;
  guestLists: AttendanceChannel;
  channels: AttendanceChannels;
};

export type VisitSummary = {
  uniqueVisitors: number;
  earlyVisits: number;
  tableNights: number;
};

export type DepositSummary = {
  totalMinor: number;
  allocationSummary: Record<string, number>;
};

export type ShiftSummary = {
  status: string;
  peopleWomen: number;
  peopleMen: number;
  peopleRejected: number;
  revenueTotalMinor: number;
};

export type SegmentSummary = {
  counts: Record<string, number>;
};

export type AnalyticsResponse = {
  schemaVersion: number;
  clubId: number;
  nightStartUtc: string;
  generatedAt: string;
  meta: AnalyticsMeta;
  attendance: AttendanceHealth | null;
  visits: VisitSummary;
  deposits: DepositSummary;
  shift: ShiftSummary | null;
  segments: SegmentSummary;
};

export type StoryListItem = {
  id: number;
  nightStartUtc: string;
  schemaVersion: number;
  status: string;
  generatedAt: string;
  updatedAt: string;
};

export type StoryListResponse = {
  stories: StoryListItem[];
  limit: number;
  offset: number;
};

export type StoryDetailsResponse = {
  id: number;
  clubId: number;
  nightStartUtc: string;
  schemaVersion: number;
  status: string;
  payload: unknown;
  generatedAt: string;
  updatedAt: string;
};

export const getAnalytics = async (
  clubId: number,
  nightStartUtc: string,
  windowDays?: number,
  signal?: AbortSignal,
): Promise<AnalyticsResponse> => {
  const windowSuffix = windowDays ? `&windowDays=${windowDays}` : '';
  try {
    const response = await http.get<AnalyticsResponse>(
      `/api/admin/clubs/${clubId}/analytics?nightStartUtc=${encodeURIComponent(nightStartUtc)}${windowSuffix}`,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const listStories = async (
  clubId: number,
  limit = 20,
  offset = 0,
  signal?: AbortSignal,
): Promise<StoryListResponse> => {
  try {
    const response = await http.get<StoryListResponse>(
      `/api/admin/clubs/${clubId}/stories?limit=${limit}&offset=${offset}`,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const getStoryDetails = async (
  clubId: number,
  nightStartUtc: string,
  signal?: AbortSignal,
): Promise<StoryDetailsResponse> => {
  try {
    const response = await http.get<StoryDetailsResponse>(
      `/api/admin/clubs/${clubId}/stories/${encodeURIComponent(nightStartUtc)}`,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};
