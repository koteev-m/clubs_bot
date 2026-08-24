import axios from 'axios';
import { http } from '../../../shared/api/http';

export const GUEST_SUPPORT_MESSAGE_MAX_LENGTH = 2000;

export const ticketTopics = ['address', 'dresscode', 'booking', 'invite', 'lost_found', 'complaint', 'other'] as const;
export type TicketTopic = (typeof ticketTopics)[number];

export const ticketTopicLabels: Record<TicketTopic, string> = {
  address: 'Адрес',
  dresscode: 'Дресс-код',
  booking: 'Бронирование',
  invite: 'Приглашения',
  lost_found: 'Потерянные вещи',
  complaint: 'Жалоба',
  other: 'Другое',
};

export const ticketStatuses = ['new', 'opened', 'in_progress', 'answered', 'resolved', 'closed'] as const;
export type TicketStatus = (typeof ticketStatuses)[number];

export const ticketStatusLabels: Record<TicketStatus, string> = {
  new: 'Новое',
  opened: 'Открыто',
  in_progress: 'В работе',
  answered: 'Отвечено',
  resolved: 'Решено',
  closed: 'Закрыто',
};

export interface SupportTicketResponse {
  id: number;
  clubId: number;
  topic: TicketTopic;
  status: TicketStatus;
  updatedAt: string;
}

export interface SupportTicketSummary {
  id: number;
  clubId: number;
  topic: TicketTopic;
  status: string;
  updatedAt: string;
  lastMessagePreview?: string | null;
  lastSenderType?: string | null;
}

export const ticketSenderTypes = ['guest', 'agent', 'system'] as const;
export type TicketSenderType = (typeof ticketSenderTypes)[number];

export interface GuestSupportTicketDetails {
  id: number;
  clubId: number;
  topic: TicketTopic;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface GuestSupportTicketMessage {
  id: number;
  senderType: TicketSenderType;
  text: string;
  attachments: string | null;
  createdAt: string;
}

export interface GuestSupportTicketThread {
  ticket: GuestSupportTicketDetails;
  messages: GuestSupportTicketMessage[];
}

export interface GuestSupportMessageResponse {
  messageId: number;
  ticketId: number;
  senderType: TicketSenderType;
  createdAt: string;
}

type ApiErrorPayload = {
  code?: string;
  message?: string | null;
  error?: {
    code?: string;
    message?: string | null;
  };
};

export class GuestSupportApiError extends Error {
  status?: number;
  code?: string;
  isAbort: boolean;

  constructor(
    message: string,
    options?: {
      status?: number;
      code?: string;
      isAbort?: boolean;
    },
  ) {
    super(message);
    this.name = 'GuestSupportApiError';
    this.status = options?.status;
    this.code = options?.code;
    this.isAbort = options?.isAbort ?? false;
  }
}

export function isGuestSupportRequestCanceled(error: unknown): boolean {
  if (error instanceof GuestSupportApiError) return error.isAbort;
  if (typeof DOMException !== 'undefined' && error instanceof DOMException) {
    return error.name === 'AbortError';
  }
  if (!axios.isAxiosError(error)) return false;
  return error.code === 'ERR_CANCELED' || error.name === 'CanceledError';
}

function normalizeGuestSupportError(error: unknown): GuestSupportApiError {
  if (error instanceof GuestSupportApiError) return error;
  if (isGuestSupportRequestCanceled(error)) {
    return new GuestSupportApiError('Запрос отменен', { isAbort: true });
  }
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as ApiErrorPayload | undefined;
    return new GuestSupportApiError('Ошибка запроса', {
      status: error.response?.status,
      code: payload?.code ?? payload?.error?.code,
    });
  }
  return new GuestSupportApiError('Ошибка запроса');
}

export interface CreateSupportTicketParams {
  clubId: number;
  topic: TicketTopic;
  text: string;
}

export function createSupportTicket(params: CreateSupportTicketParams) {
  return http.post<SupportTicketResponse>('/api/support/tickets', params);
}

export function getMySupportTickets() {
  return http.get<SupportTicketSummary[]>('/api/support/tickets/my');
}

export function getMySupportTicket(ticketId: number) {
  return http.get<GuestSupportTicketThread>(`/api/support/tickets/my/${ticketId}`);
}

export async function listMySupportTickets(signal?: AbortSignal): Promise<SupportTicketSummary[]> {
  try {
    const response = await http.get<SupportTicketSummary[]>('/api/support/tickets/my', { signal });
    return response.data;
  } catch (error) {
    throw normalizeGuestSupportError(error);
  }
}

export async function loadMySupportTicket(
  ticketId: number,
  signal?: AbortSignal,
): Promise<GuestSupportTicketThread> {
  try {
    const response = await http.get<GuestSupportTicketThread>(
      `/api/support/tickets/my/${ticketId}`,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeGuestSupportError(error);
  }
}

export async function addMySupportTicketMessage(
  ticketId: number,
  text: string,
  signal?: AbortSignal,
): Promise<GuestSupportMessageResponse> {
  try {
    const response = await http.post<GuestSupportMessageResponse>(
      `/api/support/tickets/${ticketId}/messages`,
      { text },
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeGuestSupportError(error);
  }
}
