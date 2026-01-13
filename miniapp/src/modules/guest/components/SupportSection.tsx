import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  createSupportTicket,
  getMySupportTickets,
  SupportTicketSummary,
  ticketStatusLabels,
  ticketTopicLabels,
  ticketTopics,
  TicketStatus,
  TicketTopic,
} from '../api/support.api';
import { useGuestStore } from '../state/guest.store';
import { useUiStore } from '../../../shared/store/ui';

const MAX_TEXT_LENGTH = 2000;

function formatUpdatedAt(value: string): string {
  try {
    return new Date(value).toLocaleString('ru-RU');
  } catch {
    return value;
  }
}

function resolveStatusLabel(status: string): string {
  if (status in ticketStatusLabels) {
    return ticketStatusLabels[status as TicketStatus];
  }
  return status;
}

function resolveTopicLabel(topic: TicketTopic): string {
  return ticketTopicLabels[topic] ?? topic;
}

/** Guest support section with create form and own tickets list. */
export default function SupportSection() {
  const { selectedClub } = useGuestStore();
  const { addToast } = useUiStore();
  const [topic, setTopic] = useState<TicketTopic>(ticketTopics[0]);
  const [text, setText] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [tickets, setTickets] = useState<SupportTicketSummary[]>([]);
  const [isLoadingTickets, setIsLoadingTickets] = useState(false);

  const trimmedText = useMemo(() => text.trim(), [text]);
  const isValid = trimmedText.length > 0 && trimmedText.length <= MAX_TEXT_LENGTH;

  const loadTickets = useCallback(async () => {
    setIsLoadingTickets(true);
    try {
      const res = await getMySupportTickets();
      setTickets(res.data);
    } catch {
      addToast('Не удалось загрузить обращения');
    } finally {
      setIsLoadingTickets(false);
    }
  }, [addToast]);

  useEffect(() => {
    loadTickets();
  }, [loadTickets]);

  const handleSubmit = async () => {
    if (isSubmitting) return;
    if (!selectedClub) {
      addToast('Не удалось определить клуб');
      return;
    }
    if (!isValid) {
      addToast('Пожалуйста, опишите проблему');
      return;
    }
    setIsSubmitting(true);
    try {
      await createSupportTicket({ clubId: selectedClub, topic, text: trimmedText });
      setText('');
      await loadTickets();
      addToast('Обращение отправлено');
    } catch {
      addToast('Не удалось отправить обращение');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <section className="space-y-3 rounded border p-4">
      <div className="text-lg font-semibold">Помощь</div>
      <label className="block space-y-1 text-sm">
        <span className="text-gray-600">Тема</span>
        <select
          className="w-full rounded border p-2"
          value={topic}
          onChange={(event) => setTopic(event.target.value as TicketTopic)}
        >
          {ticketTopics.map((value) => (
            <option key={value} value={value}>
              {ticketTopicLabels[value]}
            </option>
          ))}
        </select>
      </label>
      <label className="block space-y-1 text-sm">
        <span className="text-gray-600">Опишите проблему</span>
        <textarea
          className="w-full rounded border p-2"
          rows={4}
          maxLength={MAX_TEXT_LENGTH}
          value={text}
          onChange={(event) => setText(event.target.value)}
        />
      </label>
      <div className="flex items-center justify-between text-xs text-gray-500">
        <span>{trimmedText.length}/{MAX_TEXT_LENGTH}</span>
      </div>
      <button
        type="button"
        className="w-full rounded border p-2 disabled:opacity-50"
        onClick={handleSubmit}
        disabled={!isValid || isSubmitting}
      >
        {isSubmitting ? 'Отправка...' : 'Отправить'}
      </button>
      <div className="pt-2 text-base font-semibold">Мои обращения</div>
      {isLoadingTickets ? (
        <div className="text-sm text-gray-500">Загрузка...</div>
      ) : tickets.length === 0 ? (
        <div className="text-sm text-gray-500">Пока нет обращений</div>
      ) : (
        <div className="space-y-3">
          {tickets.map((ticket) => (
            <div key={ticket.id} className="rounded border p-3 text-sm">
              <div className="font-medium">{resolveTopicLabel(ticket.topic)}</div>
              <div className="text-gray-600">Статус: {resolveStatusLabel(ticket.status)}</div>
              <div className="text-gray-600">Обновлено: {formatUpdatedAt(ticket.updatedAt)}</div>
              <div className="text-gray-600">
                Последнее сообщение: {ticket.lastMessagePreview ? ticket.lastMessagePreview : '—'}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
