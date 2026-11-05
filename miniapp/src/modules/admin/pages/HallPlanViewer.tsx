import { ReactNode, useEffect, useId, useRef, useState } from 'react';
import HallPlanStage, { type TableBadge } from './HallPlanStage';
import { EditorTable, LayoutId } from './layoutEditor.types';

const viewerButton =
  'min-h-[44px] rounded border border-gray-300 bg-white px-3 py-2 text-sm text-gray-700 disabled:opacity-50';

/** Read-only presentation of the canonical saved layout; it has no mutation API. */
export default function HallPlanViewer<Id extends LayoutId = number>({
  planUrl,
  tables,
  introduction,
  tableDetails,
  tableBadge,
}: {
  planUrl?: string;
  tables: EditorTable<Id>[];
  introduction?: string;
  tableBadge?: (table: EditorTable<Id>) => TableBadge;
  tableDetails?: (table: EditorTable<Id>) => ReactNode;
}) {
  const [zoom, setZoom] = useState(1);
  const [selectedId, setSelectedId] = useState<Id | null>(null);
  const cardId = useId();
  const card = useRef<HTMLDivElement>(null);
  const selected = tables.find((table) => table.id === selectedId);
  const selectedBadge = selected ? tableBadge?.(selected) : undefined;
  useEffect(() => {
    setSelectedId(null);
    setZoom(1);
  }, [planUrl]);
  useEffect(() => {
    if (selected)
      card.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
  }, [selected]);

  return (
    <section
      aria-label="Предпросмотр для гостя"
      className="space-y-4 text-gray-900"
    >
      <h3 className="text-base font-semibold">Предпросмотр для гостя</h3>
      <p className="text-sm text-gray-600">
        {introduction ??
          'Нажмите на метку или выберите стол из списка. Показаны сохранённые данные. ' +
            'Предпросмотр не публикует заведение и не создаёт бронирование. ' +
            'Доступность столов здесь не рассчитывается.'}
      </p>
      {planUrl && (
        <>
          <div
            className="flex flex-wrap items-center gap-2"
            aria-label="Масштаб схемы"
          >
            <button
              type="button"
              className={viewerButton}
              disabled={zoom <= 1}
              onClick={() => setZoom((value) => Math.max(1, value - 0.5))}
            >
              Уменьшить
            </button>
            <output aria-live="polite">{Math.round(zoom * 100)}%</output>
            <button
              type="button"
              className={viewerButton}
              disabled={zoom >= 3}
              onClick={() => setZoom((value) => Math.min(3, value + 0.5))}
            >
              Увеличить
            </button>
            <button
              type="button"
              className={viewerButton}
              onClick={() => setZoom(1)}
            >
              Вписать схему
            </button>
          </div>
          <p className="text-sm text-gray-500">
            Увеличенную схему можно прокручивать пальцем. Список столов доступен
            под схемой.
          </p>
          <div
            className="w-full overflow-auto rounded-lg border border-gray-200"
            style={{ maxHeight: '65dvh', WebkitOverflowScrolling: 'touch' }}
            tabIndex={0}
            role="region"
            aria-label="Интерактивная схема зала"
            data-testid="hall-plan-viewport"
          >
            <div
              style={{
                width: `${zoom * 100}%`,
                padding: 22,
                boxSizing: 'border-box',
              }}
              data-testid="hall-plan-zoom"
            >
              <HallPlanStage
                key={planUrl}
                planUrl={planUrl}
                tables={tables}
                selectedTableId={selected?.id ?? null}
                readOnly
                markerSize={44}
                tableBadge={tableBadge}
                onSelectTable={setSelectedId}
                onCreateTable={() => undefined}
                onMoveTable={() => undefined}
              />
            </div>
          </div>
        </>
      )}
      {tables.length === 0 ? (
        <p className="text-sm text-gray-500">Сохранённых столов пока нет.</p>
      ) : (
        <div className="flex flex-wrap gap-2" aria-label="Столы на схеме">
          {tables.map((table) => {
            const badge = tableBadge?.(table);
            return (
              <button
                key={table.id}
                type="button"
                className={viewerButton}
                aria-pressed={selected?.id === table.id}
                aria-controls={cardId}
                onClick={() => setSelectedId(table.id)}
              >
                #{table.tableNumber} · {table.label}
                {badge && (
                  <span className={`ml-2 rounded border px-1 ${badge.color}`}>
                    {badge.label}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      )}
      <div ref={card} id={cardId} aria-live="polite" aria-atomic="true">
        {selected ? (
          <section
            className="space-y-3 rounded-lg border border-gray-200 bg-gray-50 p-4"
            aria-label="Карточка стола"
          >
            <h3 className="text-base font-semibold">
              Стол №{selected.tableNumber}
            </h3>
            <dl className="space-y-2">
              <div>
                <dt>Название</dt>
                <dd>{selected.label}</dd>
              </div>
              <div>
                <dt>Вместимость</dt>
                <dd>{selected.capacity} мест</dd>
              </div>
            </dl>
            {selectedBadge && (
              <p>
                <strong>Доступность</strong> · {selectedBadge.label}
              </p>
            )}
            {tableDetails?.(selected)}
            <button
              type="button"
              className={viewerButton}
              onClick={() => setSelectedId(null)}
            >
              Закрыть карточку
            </button>
          </section>
        ) : (
          <p className="text-sm text-gray-500">
            Выберите стол, чтобы посмотреть его параметры.
          </p>
        )}
      </div>
    </section>
  );
}
