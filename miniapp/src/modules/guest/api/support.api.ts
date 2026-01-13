import { http } from '../../../shared/api/http';

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

export const ticketStatuses = ['opened', 'in_progress', 'answered', 'closed'] as const;
export type TicketStatus = (typeof ticketStatuses)[number];

export const ticketStatusLabels: Record<TicketStatus, string> = {
  opened: 'Открыто',
  in_progress: 'В работе',
  answered: 'Отвечено',
  closed: 'Закрыто',
};

export interface SupportTicketSummary {
  id: number;
  clubId: number;
  topic: TicketTopic;
  status: TicketStatus;
  updatedAt: string;
  lastMessagePreview?: string | null;
}

export interface CreateSupportTicketParams {
  clubId: number;
  topic: TicketTopic;
  text: string;
}

export function createSupportTicket(params: CreateSupportTicketParams) {
  return http.post('/api/support/tickets', params);
}

export function getMySupportTickets() {
  return http.get<SupportTicketSummary[]>('/api/support/tickets/my');
}
