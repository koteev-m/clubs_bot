import { useId, useState } from 'react';
import './clubTableCreation.css';

export type TableCreation = {
  mode: 'single' | 'range' | 'list';
  numbers: number[];
  capacity: number;
  label: string;
  minDeposit: string;
};

const maxNumber = 2_147_483_647;
const maxBatch = 100;

export function tableDepositRubles(input: string): string | undefined {
  const rubles = input.trim().replace(',', '.');
  return /^\d{1,10}(\.\d{1,2})?$/.test(rubles) ? rubles : undefined;
}

export function tableDepositLabel(rubles: string) {
  return new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB',
    minimumFractionDigits: Number(rubles) % 1 === 0 ? 0 : 2, maximumFractionDigits: 2 }).format(Number(rubles));
}

function validNumber(text: string) {
  const value = Number(text);
  return /^\d+$/.test(text) && value >= 1 && value <= maxNumber ? value : 0;
}

function proposedNumbers(
  mode: TableCreation['mode'],
  first: string,
  count: string,
  list: string,
) {
  if (mode === 'list') {
    const entries = list.trim().split(/[\s,;]+/);
    return entries.length <= maxBatch ? entries.map(validNumber) : [];
  }
  const start = validNumber(first);
  const length = mode === 'single' ? 1 : validNumber(count);
  return start > 0 &&
    length > 0 &&
    length <= maxBatch &&
    start + length - 1 <= maxNumber
    ? Array.from({ length }, (_, index) => start + index)
    : [];
}

export default function ClubTableCreationForm({
  reserved,
  hallName,
  locations = {},
  busy,
  onSave,
  onClose,
}: {
  reserved: number[];
  hallName?: string;
  locations?: Record<number, string>;
  busy: boolean;
  onSave: (creation: TableCreation) => void;
  onClose: () => void;
}) {
  const modeId = useId();
  const numbersHint = useId();
  const depositHint = useId();
  const [mode, setMode] = useState<TableCreation['mode']>('single');
  const [first, setFirst] = useState('');
  const [count, setCount] = useState('');
  const [list, setList] = useState('');
  const [label, setLabel] = useState('');
  const [capacity, setCapacity] = useState('2');
  const [deposit, setDeposit] = useState('');
  const numbers = proposedNumbers(mode, first, count, list);
  const occupied = new Set(reserved);
  const duplicates = [
    ...new Set(
      numbers.filter(
        (number, index) =>
          number > 0 &&
          (occupied.has(number) || numbers.indexOf(number) !== index),
      ),
    ),
  ];
  const capacityValue = validNumber(capacity);
  const rubles = tableDepositRubles(deposit);
  const depositLabel = mode === 'single' ? 'Минимальный депозит за стол, ₽' : 'Минимальный депозит за каждый стол, ₽';
  const valid =
    numbers.length > 0 &&
    numbers.every(Boolean) &&
    duplicates.length === 0 &&
    capacityValue >= 1 &&
    capacityValue <= 50 &&
    rubles !== undefined;
  const inputClass =
    'block min-h-[44px] w-full rounded border border-gray-300 bg-white px-3 text-gray-900';
  return (
    <form
      aria-label="Создание столов"
      className="club-table-creation space-y-3 rounded-lg border border-gray-200 p-3 text-sm"
      onSubmit={(event) => {
        event.preventDefault();
        if (!valid || busy || rubles === undefined) return;
        onSave({
          mode,
          numbers,
          capacity: capacityValue,
          label: label.trim(),
          minDeposit: rubles,
        });
      }}
    >
      {hallName && <p className="club-table-context">{hallName}</p>}
      <h3>{mode === 'single' ? 'Новый стол' : 'Новые столы'}</h3>
      <div>
        <label htmlFor={modeId}>Способ добавления</label>
        <select
          id={modeId}
          className={inputClass}
          value={mode}
          disabled={busy}
          onChange={(event) =>
            setMode(event.target.value as TableCreation['mode'])
          }
        >
          <option value="single">Один стол</option>
          <option value="range">Первый номер и количество</option>
          <option value="list">Список номеров</option>
        </select>
      </div>
      {mode !== 'list' && (
        <label className="block">
          {mode === 'single' ? 'Номер нового стола' : 'Первый номер'}
          <input
            className={inputClass}
            inputMode="numeric"
            aria-describedby={numbersHint}
            aria-invalid={duplicates.length > 0}
            disabled={busy}
            value={first}
            onChange={(event) => setFirst(event.target.value)}
          />
        </label>
      )}
      {mode === 'range' && (
        <label className="block">
          Количество столов
          <input
            className={inputClass}
            inputMode="numeric"
            aria-describedby={numbersHint}
            disabled={busy}
            value={count}
            onChange={(event) => setCount(event.target.value)}
          />
        </label>
      )}
      {mode === 'list' && (
        <label className="block">
          Номера столов
          <textarea
            className={inputClass}
            aria-describedby={numbersHint}
            aria-invalid={duplicates.length > 0}
            placeholder="Например, 7, 10, 15"
            disabled={busy}
            value={list}
            onChange={(event) => setList(event.target.value)}
          />
        </label>
      )}
      {mode === 'single' && (
        <label className="block">
          Название нового стола
          <input
            className={inputClass}
            maxLength={100}
            disabled={busy}
            value={label}
            onChange={(event) => setLabel(event.target.value)}
          />
        </label>
      )}
      <label className="block">
        {mode === 'single' ? 'Количество посадочных мест' : 'Количество посадочных мест на каждый стол'}
        <input
          className={inputClass}
          inputMode="numeric"
          disabled={busy}
          value={capacity}
          onChange={(event) => setCapacity(event.target.value)}
        />
      </label>
      <p>Допустимая вместимость — от 1 до 50 мест.</p>
      <label className="block">
        {depositLabel}
        <input
          className={inputClass}
          inputMode="decimal"
          aria-label={depositLabel}
          required
          aria-invalid={Boolean(deposit && rubles === undefined)}
          aria-describedby={depositHint}
          disabled={busy}
          value={deposit}
          onChange={(event) => setDeposit(event.target.value)}
        />
        <small id={depositHint}>
          {deposit && rubles === undefined
            ? 'Укажите сумму в рублях, до двух знаков после запятой.'
            : '0 ₽ — без минимального депозита.'}
        </small>
      </label>
      {rubles !== undefined && capacityValue >= 1 && capacityValue <= 50 && (
        <dl className="club-table-summary">
          <div><dt>Вместимость{mode !== 'single' && ' на стол'}</dt><dd>{capacityValue} чел.</dd></div>
          <div><dt>Минимум{mode !== 'single' && ' на стол'}</dt><dd>{tableDepositLabel(rubles)}</dd></div>
        </dl>
      )}
      <p aria-live="polite">
        Номера для создания:{' '}
        {numbers.length > 0 && numbers.every(Boolean)
          ? numbers.join(', ')
          : '—'}
      </p>
      <div id={numbersHint} aria-live="polite">
        <p>
          Номер стола должен быть уникальным во всём клубе, включая другие залы.
        </p>
        {duplicates.length > 0 && (
          <p role="alert">
            {duplicates
              .map((number) =>
                occupied.has(number)
                  ? `№${number} уже занят ${locations[number] ? `в зале «${locations[number]}»` : 'в клубе'}.`
                  : `№${number} повторяется в списке.`,
              )
              .join(' ')}
          </p>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        <button
          className="min-h-[44px] rounded bg-blue-600 px-3 text-white disabled:opacity-50"
          type="submit"
          disabled={!valid || busy}
        >
          Создать {mode === 'single' ? 'стол' : 'столы'}
        </button>
        <button
          type="button"
          className="min-h-[44px] px-3"
          disabled={busy}
          onClick={onClose}
        >
          Отмена
        </button>
      </div>
    </form>
  );
}
