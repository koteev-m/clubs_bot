import axios from 'axios';
import { http } from '../../../shared/api/http';

export const supportTicketStatuses = ['new', 'opened', 'in_progress', 'answered', 'closed'] as const;
export type SupportTicketStatus = (typeof supportTicketStatuses)[number];

export type SupportClub = {
  id: number;
  name: string;
};

export type SupportTicketSummary = {
  id: number;
  clubId: number;
  topic: string;
  status: SupportTicketStatus;
  updatedAt: string;
  lastMessagePreview?: string | null;
  lastSenderType?: string | null;
};

export type SupportTicket = {
  id: number;
  clubId: number;
  topic: string;
  status: SupportTicketStatus;
  createdAt: string;
  updatedAt: string;
};

export type SupportTicketMessage = {
  id: number;
  senderType: string;
  text: string;
  attachments: string | null;
  createdAt: string;
};

export type SupportTicketThread = {
  ticket: SupportTicket;
  messages: SupportTicketMessage[];
};

type ApiErrorPayload = {
  code?: string;
  message?: string | null;
  error?: {
    code?: string;
    message?: string | null;
  };
};

export class SupportApiError extends Error {
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
    this.name = 'SupportApiError';
    this.status = options?.status;
    this.code = options?.code;
    this.isAbort = options?.isAbort ?? false;
  }
}

export function isSupportRequestCanceled(error: unknown): boolean {
  if (error instanceof SupportApiError) return error.isAbort;
  if (typeof DOMException !== 'undefined' && error instanceof DOMException) {
    return error.name === 'AbortError';
  }
  if (!axios.isAxiosError(error)) return false;
  return error.code === 'ERR_CANCELED' || error.name === 'CanceledError';
}

function normalizeSupportError(error: unknown): SupportApiError {
  if (error instanceof SupportApiError) return error;
  if (isSupportRequestCanceled(error)) {
    return new SupportApiError('Запрос отменен', { isAbort: true });
  }
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as ApiErrorPayload | undefined;
    return new SupportApiError(payload?.message ?? payload?.error?.message ?? 'Ошибка запроса', {
      status: error.response?.status,
      code: payload?.code ?? payload?.error?.code,
    });
  }
  if (error instanceof Error) return new SupportApiError(error.message);
  return new SupportApiError('Неизвестная ошибка');
}

export async function listPermittedSupportClubs(signal?: AbortSignal): Promise<SupportClub[]> {
  try {
    const response = await http.get<SupportClub[]>('/api/support/staff/clubs', { signal });
    return response.data;
  } catch (error) {
    throw normalizeSupportError(error);
  }
}

export async function listSupportTickets(
  params: { clubId: number; status?: SupportTicketStatus },
  signal?: AbortSignal,
): Promise<SupportTicketSummary[]> {
  try {
    const response = await http.get<SupportTicketSummary[]>('/api/support/tickets', {
      params,
      signal,
    });
    return response.data;
  } catch (error) {
    throw normalizeSupportError(error);
  }
}

export async function getSupportTicket(ticketId: number, signal?: AbortSignal): Promise<SupportTicketThread> {
  try {
    const response = await http.get<SupportTicketThread>(`/api/support/tickets/${ticketId}`, { signal });
    return response.data;
  } catch (error) {
    throw normalizeSupportError(error);
  }
}
