import { useMemo } from 'react';
import { useUiStore } from '../../../shared/store/ui';

const templates = [
  'Привет! Ты в гостевом списке на эту ночь. Ждём тебя!',
  'Добро пожаловать! Мы внесли тебя в список гостей, приходи вовремя.',
  'Ты в списке гостей. Вход до 01:00, не забудь паспорт.',
  'Ждём тебя в клубе! Твой проход по гостевому списку.',
  'Сегодня твой вечер: место в списке гостей подтверждено.',
  'Ты на списке. Приходи раньше, чтобы не ждать.',
  'Готово! Ты в списке гостей, увидимся на входе.',
  'Гость подтверждён. Приходи с хорошим настроением!',
];

const hints = ['За 1–2 дня до события', 'Утром в день события', 'За 2–3 часа до начала'];

export default function CreativesScreen() {
  const addToast = useUiStore((state) => state.addToast);
  const items = useMemo(() => templates.map((text, idx) => ({ id: idx, text })), []);

  const handleCopy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      addToast('Текст скопирован');
    } catch {
      addToast('Не удалось скопировать текст');
    }
  };

  return (
    <div className="px-4 py-6">
      <h2 className="text-lg font-semibold text-gray-900">Креативы</h2>
      <div className="mt-4 rounded-lg bg-white p-4 shadow-sm">
        <div className="text-sm font-semibold text-gray-900">Когда отправлять</div>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-gray-700">
          {hints.map((hint) => (
            <li key={hint}>{hint}</li>
          ))}
        </ul>
      </div>
      <div className="mt-4 space-y-3">
        {items.map((item) => (
          <div key={item.id} className="rounded-lg bg-white p-4 shadow-sm">
            <p className="text-sm text-gray-700">{item.text}</p>
            <button
              type="button"
              className="mt-3 rounded-md border border-gray-200 px-3 py-2 text-xs font-semibold text-gray-700"
              onClick={() => handleCopy(item.text)}
            >
              Копировать текст
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
