import { type MouseEvent, type PointerEvent, type SyntheticEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AdminTable } from '../api/admin.api';
import { clientPointToNormalized, computeContainMetrics, ContainMetrics } from '../utils/containCoords';

export type HallPlanStageProps = {
  planUrl: string;
  tables: AdminTable[];
  selectedTableId: number | null;
  readOnly?: boolean;
  onSelectTable: (tableId: number) => void;
  onCreateTable: (coords: { x: number; y: number }) => void;
  onMoveTable: (tableId: number, coords: { x: number; y: number }) => void;
};

const markerSize = 28;

export default function HallPlanStage({
  planUrl,
  tables,
  selectedTableId,
  readOnly = false,
  onSelectTable,
  onCreateTable,
  onMoveTable,
}: HallPlanStageProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [imageSize, setImageSize] = useState<{ width: number; height: number } | null>(null);
  const [metrics, setMetrics] = useState<ContainMetrics | null>(null);
  const [dragging, setDragging] = useState<{ tableId: number; x: number; y: number } | null>(null);
  const dragOriginRef = useRef<{ tableId: number; x: number; y: number } | null>(null);
  const dragPositionRef = useRef<{ tableId: number; x: number; y: number } | null>(null);
  const draggingMovedRef = useRef(false);

  const updateMetrics = useCallback(() => {
    if (!containerRef.current || !imageSize) return;
    const rect = containerRef.current.getBoundingClientRect();
    const next = computeContainMetrics(rect.width, rect.height, imageSize.width, imageSize.height);
    setMetrics(next);
  }, [imageSize]);

  useEffect(() => {
    updateMetrics();
  }, [planUrl, updateMetrics]);

  useEffect(() => {
    if (!containerRef.current) return;
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', updateMetrics);
      return () => window.removeEventListener('resize', updateMetrics);
    }
    const observer = new ResizeObserver(() => updateMetrics());
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [updateMetrics]);

  const handleImageLoad = useCallback((event: SyntheticEvent<HTMLImageElement>) => {
    const { naturalWidth, naturalHeight } = event.currentTarget;
    if (naturalWidth > 0 && naturalHeight > 0) {
      setImageSize({ width: naturalWidth, height: naturalHeight });
    }
  }, []);

  const handleStageClick = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      if (readOnly || draggingMovedRef.current) {
        draggingMovedRef.current = false;
        return;
      }
      if (!containerRef.current || !metrics) return;
      const rect = containerRef.current.getBoundingClientRect();
      const normalized = clientPointToNormalized(event.clientX, event.clientY, rect, metrics);
      if (!normalized) return;
      if (selectedTableId) {
        onMoveTable(selectedTableId, normalized);
      } else {
        onCreateTable(normalized);
      }
    },
    [metrics, onCreateTable, onMoveTable, readOnly, selectedTableId],
  );

  const handlePointerDown = useCallback(
    (event: PointerEvent<HTMLButtonElement>, table: AdminTable) => {
      event.stopPropagation();
      onSelectTable(table.id);
      if (readOnly) return;
      if (typeof event.currentTarget.setPointerCapture === 'function') {
        event.currentTarget.setPointerCapture(event.pointerId);
      }
      draggingMovedRef.current = false;
      dragOriginRef.current = { tableId: table.id, x: table.x, y: table.y };
    },
    [onSelectTable, readOnly],
  );

  const resetDragging = useCallback((tableId: number) => {
    if (!dragOriginRef.current || dragOriginRef.current.tableId !== tableId) return;
    dragOriginRef.current = null;
    dragPositionRef.current = null;
    setDragging(null);
    draggingMovedRef.current = false;
  }, []);

  const handlePointerMove = useCallback(
    (event: PointerEvent<HTMLButtonElement>, table: AdminTable) => {
      if (readOnly) return;
      if (!containerRef.current || !metrics) return;
      if (!dragOriginRef.current || dragOriginRef.current.tableId !== table.id) return;
      const rect = containerRef.current.getBoundingClientRect();
      const normalized = clientPointToNormalized(event.clientX, event.clientY, rect, metrics);
      if (!normalized) return;
      draggingMovedRef.current = true;
      const next = { tableId: table.id, x: normalized.x, y: normalized.y };
      dragPositionRef.current = next;
      setDragging(next);
    },
    [metrics, readOnly],
  );

  const handlePointerUp = useCallback(
    (event: PointerEvent<HTMLButtonElement>, table: AdminTable) => {
      if (readOnly) return;
      if (!dragOriginRef.current || dragOriginRef.current.tableId !== table.id) return;
      if (typeof event.currentTarget.releasePointerCapture === 'function') {
        try {
          event.currentTarget.releasePointerCapture(event.pointerId);
        } catch {
          // Игнорируем, если захват уже был освобождён браузером.
        }
      }
      const origin = dragOriginRef.current;
      const position = dragPositionRef.current;
      dragOriginRef.current = null;
      dragPositionRef.current = null;
      if (!draggingMovedRef.current || !position || position.tableId !== table.id) {
        setDragging(null);
        return;
      }
      setDragging(null);
      draggingMovedRef.current = false;
      const dx = Math.abs(origin.x - position.x);
      const dy = Math.abs(origin.y - position.y);
      if (dx < 0.001 && dy < 0.001) return;
      onMoveTable(table.id, { x: position.x, y: position.y });
    },
    [onMoveTable, readOnly],
  );

  const handlePointerCancel = useCallback(
    (event: PointerEvent<HTMLButtonElement>, table: AdminTable) => {
      if (typeof event.currentTarget.releasePointerCapture === 'function') {
        try {
          event.currentTarget.releasePointerCapture(event.pointerId);
        } catch {
          // Игнорируем, если захват уже был освобождён браузером.
        }
      }
      resetDragging(table.id);
    },
    [resetDragging],
  );

  const positions = useMemo(() => {
    if (!metrics) return new Map<number, { left: number; top: number }>();
    const map = new Map<number, { left: number; top: number }>();
    tables.forEach((table) => {
      const source =
        dragging && dragging.tableId === table.id ? { x: dragging.x, y: dragging.y } : table;
      const left = metrics.offsetX + source.x * metrics.width;
      const top = metrics.offsetY + source.y * metrics.height;
      map.set(table.id, { left, top });
    });
    return map;
  }, [dragging, metrics, tables]);

  return (
    <div
      ref={containerRef}
      className="relative w-full overflow-hidden rounded-lg bg-gray-100"
      style={{ aspectRatio: '16 / 9' }}
      onClick={handleStageClick}
      data-testid="hall-plan-stage"
    >
      <img
        src={planUrl}
        alt="План зала"
        className="absolute inset-0 h-full w-full object-contain"
        onLoad={handleImageLoad}
        data-testid="hall-plan-image"
      />
      {tables.map((table) => {
        const position = positions.get(table.id);
        if (!position) return null;
        const isSelected = table.id === selectedTableId;
        return (
          <button
            key={table.id}
            type="button"
            data-testid={`table-marker-${table.id}`}
            className={`absolute flex h-7 w-7 items-center justify-center rounded-full border text-[10px] font-semibold shadow-sm transition ${
              isSelected
                ? 'border-blue-500 bg-blue-600 text-white'
                : 'border-white bg-white text-gray-700'
            } ${readOnly ? 'cursor-default' : 'cursor-grab'} select-none`}
            style={{
              left: position.left - markerSize / 2,
              top: position.top - markerSize / 2,
              width: markerSize,
              height: markerSize,
              touchAction: readOnly ? 'auto' : 'none',
            }}
            onPointerDown={(event) => handlePointerDown(event, table)}
            onPointerMove={(event) => handlePointerMove(event, table)}
            onPointerUp={(event) => handlePointerUp(event, table)}
            onPointerCancel={(event) => handlePointerCancel(event, table)}
            onClick={(event) => event.stopPropagation()}
          >
            {table.tableNumber}
          </button>
        );
      })}
    </div>
  );
}
