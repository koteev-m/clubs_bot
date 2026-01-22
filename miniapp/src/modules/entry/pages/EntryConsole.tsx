import { useMemo, useState } from 'react';
import QrScannerButton from '../components/QrScannerButton';
import EntryCheckinScreen from '../components/EntryCheckinScreen';
import EntryWaitlistScreen from '../components/EntryWaitlistScreen';
import EntryChecklistScreen from '../components/EntryChecklistScreen';
import ToastHost from '../../../widgets/ToastHost';
import { useEntryStore } from '../state/entry.store';

const tabs = [
  { id: 'entry', label: 'Вход' },
  { id: 'scanner', label: 'Сканер' },
  { id: 'waitlist', label: 'Лист ожидания' },
  { id: 'checklist', label: 'Чек-лист смены' },
] as const;

type TabId = (typeof tabs)[number]['id'];

/** Console for entry managers. */
export default function EntryConsole() {
  const { lastResult } = useEntryStore();
  const [activeTab, setActiveTab] = useState<TabId>('entry');
  const [clubIdInput, setClubIdInput] = useState('');
  const [eventIdInput, setEventIdInput] = useState('');
  const clubId = Number.parseInt(clubIdInput, 10) || 0;
  const eventId = Number.parseInt(eventIdInput, 10) || 0;
  const contextLabel = useMemo(() => `${clubId || '—'} / ${eventId || '—'}`, [clubId, eventId]);

  return (
    <div className="p-4 space-y-4">
      <div className="space-y-2">
        <div className="text-sm text-gray-500">Клуб / событие: {contextLabel}</div>
        <div className="grid grid-cols-2 gap-2">
          <input
            value={clubIdInput}
            onChange={(e) => setClubIdInput(e.target.value)}
            placeholder="Club ID"
            className="border p-2 rounded"
            inputMode="numeric"
          />
          <input
            value={eventIdInput}
            onChange={(e) => setEventIdInput(e.target.value)}
            placeholder="Event ID"
            className="border p-2 rounded"
            inputMode="numeric"
          />
        </div>
      </div>

      <div className="flex flex-wrap gap-2">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            onClick={() => setActiveTab(tab.id)}
            className={`px-3 py-1 rounded border ${
              activeTab === tab.id ? 'bg-black text-white' : 'bg-white text-black'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'entry' && <EntryCheckinScreen clubId={clubId} eventId={eventId} />}
      {activeTab === 'scanner' && <QrScannerButton clubId={clubId} eventId={eventId} />}
      {activeTab === 'waitlist' && <EntryWaitlistScreen clubId={clubId} eventId={eventId} />}
      {activeTab === 'checklist' && <EntryChecklistScreen clubId={clubId} eventId={eventId} />}

      {lastResult && <div className="text-sm text-gray-600">Last: {lastResult}</div>}
      <ToastHost />
    </div>
  );
}
