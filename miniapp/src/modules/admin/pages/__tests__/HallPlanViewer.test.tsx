import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import HallPlanViewer from '../HallPlanViewer';
import HallPlanStage from '../HallPlanStage';
import { EditorTable } from '../layoutEditor.types';

const saved: EditorTable<string> = {
  id: '9007199254741001',
  hallId: '9007199254741000',
  tableNumber: 7,
  label: 'У окна',
  capacity: 6,
  x: 0,
  y: 0.25,
};
let width = 320;
let height = 180;
function loadPlan() {
  const stage = screen.getByTestId('hall-plan-stage');
  stage.getBoundingClientRect = () => ({
    left: 0,
    top: 0,
    right: width,
    bottom: height,
    x: 0,
    y: 0,
    width,
    height,
    toJSON: () => ({}),
  });
  fireEvent.load(screen.getByTestId('hall-plan-image'));
  return stage;
}

beforeEach(() => {
  width = 320;
  height = 180;
  vi.stubGlobal('ResizeObserver', undefined);
  vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(
    100,
  );
  vi.spyOn(HTMLImageElement.prototype, 'naturalHeight', 'get').mockReturnValue(
    100,
  );
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('canonical saved hall viewer', () => {
  it('shows the saved table card on click and keeps 44px targets over object-contain after resizing', () => {
    render(<HallPlanViewer planUrl="blob:private" tables={[saved]} />);
    loadPlan();
    const marker = screen.getByRole('button', {
      name: 'Стол 7: У окна, мест: 6',
    });
    expect(marker.style.width).toBe('44px');
    expect(marker.style.height).toBe('44px');
    // Square source in a 16:9 stage: 70px letterbox + normalized x=0.
    expect(marker.style.left).toBe('48px');
    expect(marker.style.top).toBe('23px');
    fireEvent.click(marker);
    expect(marker.getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('region', { name: 'Карточка стола' })).toBeTruthy();
    expect(screen.getByText('Стол №7')).toBeTruthy();
    expect(screen.getByText('6 мест')).toBeTruthy();
    expect(
      screen.queryByRole('button', {
        name: /Бронировать|HOLD|Сохранить|Удалить/,
      }),
    ).toBeNull();
    width = 640;
    height = 360;
    fireEvent(window, new Event('resize'));
    expect(marker.style.left).toBe('118px');
    expect(marker.style.top).toBe('68px');
    expect(saved.x).toBe(0);
    expect(saved.y).toBe(0.25);
    fireEvent.click(screen.getByRole('button', { name: 'Закрыть карточку' }));
    expect(screen.queryByRole('region', { name: 'Карточка стола' })).toBeNull();
  });

  it('offers bounded scrollable zoom and a full-size list for crowded markers, preserving stored positions on reopen', () => {
    const first = render(
      <HallPlanViewer planUrl="blob:private" tables={[saved]} />,
    );
    loadPlan();
    for (let index = 0; index < 4; index += 1)
      fireEvent.click(screen.getByRole('button', { name: 'Увеличить' }));
    expect(screen.getByText('300%')).toBeTruthy();
    expect(
      (screen.getByRole('button', { name: 'Увеличить' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(screen.getByTestId('hall-plan-zoom').style.width).toBe('300%');
    fireEvent.click(screen.getByRole('button', { name: '#7 · У окна' }));
    expect(screen.getByText('Стол №7')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Вписать схему' }));
    expect(screen.getByText('100%')).toBeTruthy();
    first.unmount();
    render(<HallPlanViewer planUrl="blob:reopened-private" tables={[saved]} />);
    loadPlan();
    expect(screen.getByTestId('table-marker-' + saved.id).style.left).toBe(
      '48px',
    );
    expect(screen.queryByRole('region', { name: 'Карточка стола' })).toBeNull();
  });

  it('drops selection when the saved table disappears or the plan changes', () => {
    const view = render(
      <HallPlanViewer planUrl="blob:private" tables={[saved]} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '#7 · У окна' }));
    view.rerender(<HallPlanViewer planUrl="blob:private" tables={[]} />);
    expect(screen.queryByText('Стол №7')).toBeNull();
    expect(screen.getByText('Сохранённых столов пока нет.')).toBeTruthy();
    view.rerender(<HallPlanViewer planUrl="blob:new" tables={[saved]} />);
    expect(screen.queryByText('Стол №7')).toBeNull();
  });

  it('read-only stage selects through click without dragging or creating, including keyboard activation', () => {
    const select = vi.fn();
    const move = vi.fn();
    const create = vi.fn();
    render(
      <HallPlanStage
        planUrl="blob:private"
        tables={[saved]}
        selectedTableId={null}
        readOnly
        markerSize={44}
        onSelectTable={select}
        onMoveTable={move}
        onCreateTable={create}
      />,
    );
    const stage = loadPlan();
    const marker = screen.getByTestId('table-marker-' + saved.id);
    fireEvent.pointerDown(marker, { pointerId: 1, clientX: 70, clientY: 45 });
    fireEvent.pointerMove(marker, { pointerId: 1, clientX: 100, clientY: 80 });
    fireEvent.pointerUp(marker, { pointerId: 1 });
    expect(select).not.toHaveBeenCalled();
    fireEvent.click(marker, { detail: 0 });
    fireEvent.click(stage, { clientX: 100, clientY: 50 });
    expect(select).toHaveBeenCalledWith(saved.id);
    expect(move).not.toHaveBeenCalled();
    expect(create).not.toHaveBeenCalled();
  });
});
