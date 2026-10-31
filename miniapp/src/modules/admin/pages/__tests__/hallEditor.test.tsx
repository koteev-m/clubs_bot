import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import HallEditorScreen from '../HallEditorScreen';
import HallPlanStage from '../HallPlanStage';
import { useUiStore } from '../../../../shared/store/ui';
import {
  createHallTable,
  fetchHallPlanBlob,
  listHallTables,
  updateHallTable,
} from '../../api/admin.api';
import {
  clamp01,
  clientPointToNormalized,
  computeContainMetrics,
} from '../../utils/containCoords';

vi.mock('../../../../widgets/ToastHost', () => ({
  default: () => null,
}));

vi.mock('../../api/admin.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin.api')>(
    '../../api/admin.api',
  );
  return {
    ...actual,
    listHallTables: vi.fn(),
    createHallTable: vi.fn(),
    updateHallTable: vi.fn(),
    deleteHallTable: vi.fn(),
    uploadHallPlan: vi.fn(),
    fetchHallPlanBlob: vi.fn(),
  };
});

const setupPlanMocks = () => {
  vi.mocked(fetchHallPlanBlob).mockResolvedValue({
    status: 200,
    blob: new Blob(['test'], { type: 'image/png' }),
    etag: 'etag',
  });
};

const setupTablesMock = () => {
  vi.mocked(listHallTables).mockResolvedValue([
    {
      id: 1,
      hallId: 10,
      clubId: 2,
      label: 'Table 1',
      minDeposit: 0,
      capacity: 2,
      zone: null,
      zoneName: null,
      arrivalWindow: null,
      mysteryEligible: false,
      tableNumber: 1,
      x: 0.2,
      y: 0.2,
    },
  ]);
};

const mockStageRect = (element: HTMLElement) => {
  element.getBoundingClientRect = () => ({
    width: 200,
    height: 100,
    top: 0,
    left: 0,
    right: 200,
    bottom: 100,
    x: 0,
    y: 0,
    toJSON: () => {},
  });
};

describe('contain coords utils', () => {
  it('computes contain metrics and maps points', () => {
    const metrics = computeContainMetrics(200, 100, 100, 100);
    expect(metrics).not.toBeNull();
    expect(metrics?.offsetX).toBe(50);
    expect(metrics?.offsetY).toBe(0);
    expect(metrics?.width).toBe(100);
    expect(metrics?.height).toBe(100);

    const rect = {
      left: 0,
      top: 0,
      width: 200,
      height: 100,
      right: 200,
      bottom: 100,
      x: 0,
      y: 0,
      toJSON: () => {},
    } as DOMRect;
    const center = clientPointToNormalized(100, 50, rect, metrics!);
    expect(center).toEqual({ x: 0.5, y: 0.5 });

    const letterbox = clientPointToNormalized(10, 50, rect, metrics!);
    expect(letterbox).toBeNull();

    expect(clamp01(1.2)).toBe(1);
    expect(clamp01(-0.1)).toBe(0);
  });
});

describe('hall editor interactions', () => {
  beforeEach(() => {
    useUiStore.setState({ toasts: [] });
    vi.clearAllMocks();
    Object.defineProperty(globalThis.URL, 'createObjectURL', {
      value: vi.fn(() => 'blob:mock'),
      configurable: true,
    });
    Object.defineProperty(globalThis.URL, 'revokeObjectURL', {
      value: vi.fn(),
      configurable: true,
    });
    vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(
      200,
    );
    vi.spyOn(
      HTMLImageElement.prototype,
      'naturalHeight',
      'get',
    ).mockReturnValue(100);
  });

  afterEach(() => {
    useUiStore.setState({ toasts: [] });
    vi.restoreAllMocks();
  });

  it('does not create a private object URL for a plan that resolves after unmount', async () => {
    setupTablesMock();
    let resolve!: (
      result: Awaited<ReturnType<typeof fetchHallPlanBlob>>,
    ) => void;
    vi.mocked(fetchHallPlanBlob).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const { unmount } = render(
      <HallEditorScreen clubId={2} hallId={10} onBack={vi.fn()} />,
    );
    unmount();
    await act(async () => {
      resolve({
        status: 200,
        blob: new Blob(['private plan'], { type: 'image/png' }),
        etag: 'synthetic',
      });
    });
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('does not spam update on drag', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    setupPlanMocks();
    setupTablesMock();
    vi.mocked(updateHallTable).mockResolvedValue({
      id: 1,
      hallId: 10,
      clubId: 2,
      label: 'Table 1',
      minDeposit: 0,
      capacity: 2,
      zone: null,
      zoneName: null,
      arrivalWindow: null,
      mysteryEligible: false,
      tableNumber: 1,
      x: 0.4,
      y: 0.4,
    });

    render(<HallEditorScreen clubId={2} hallId={10} onBack={vi.fn()} />);

    const stage = await screen.findByTestId('hall-plan-stage');
    const image = await screen.findByTestId('hall-plan-image');
    mockStageRect(stage);
    fireEvent.load(image);

    const marker = await screen.findByTestId('table-marker-1');
    fireEvent.pointerDown(marker, { clientX: 60, clientY: 20, pointerId: 1 });
    fireEvent.pointerMove(marker, { clientX: 80, clientY: 30, pointerId: 1 });
    fireEvent.pointerMove(marker, { clientX: 90, clientY: 40, pointerId: 1 });

    expect(updateHallTable).not.toHaveBeenCalled();

    fireEvent.pointerUp(marker, { clientX: 90, clientY: 40, pointerId: 1 });

    await waitFor(() => {
      expect(updateHallTable).toHaveBeenCalledTimes(1);
    });
    expect(confirm).not.toHaveBeenCalled();
  });

  it('creates or moves tables based on selection', async () => {
    setupPlanMocks();
    setupTablesMock();
    vi.mocked(createHallTable).mockResolvedValue({
      id: 2,
      hallId: 10,
      clubId: 2,
      label: 'Table 2',
      minDeposit: 0,
      capacity: 2,
      zone: null,
      zoneName: null,
      arrivalWindow: null,
      mysteryEligible: false,
      tableNumber: 2,
      x: 0.5,
      y: 0.5,
    });
    vi.mocked(updateHallTable).mockResolvedValue({
      id: 1,
      hallId: 10,
      clubId: 2,
      label: 'Table 1',
      minDeposit: 0,
      capacity: 2,
      zone: null,
      zoneName: null,
      arrivalWindow: null,
      mysteryEligible: false,
      tableNumber: 1,
      x: 0.6,
      y: 0.6,
    });

    render(<HallEditorScreen clubId={2} hallId={10} onBack={vi.fn()} />);

    const stage = await screen.findByTestId('hall-plan-stage');
    const image = await screen.findByTestId('hall-plan-image');
    mockStageRect(stage);
    fireEvent.load(image);

    fireEvent.click(stage, { clientX: 100, clientY: 50 });

    await waitFor(() => {
      expect(createHallTable).toHaveBeenCalledTimes(1);
    });

    const marker = await screen.findByTestId('table-marker-1');
    fireEvent.pointerDown(marker, { clientX: 60, clientY: 20, pointerId: 1 });
    fireEvent.pointerUp(marker, { clientX: 60, clientY: 20, pointerId: 1 });

    fireEvent.click(stage, { clientX: 120, clientY: 60 });

    await waitFor(() => {
      expect(updateHallTable).toHaveBeenCalledTimes(1);
    });
  });

  it('creates second table after clearing selection', async () => {
    setupPlanMocks();
    vi.mocked(listHallTables).mockResolvedValue([]);
    vi.mocked(createHallTable)
      .mockResolvedValueOnce({
        id: 1,
        hallId: 10,
        clubId: 2,
        label: 'Table 1',
        minDeposit: 0,
        capacity: 2,
        zone: null,
        zoneName: null,
        arrivalWindow: null,
        mysteryEligible: false,
        tableNumber: 1,
        x: 0.5,
        y: 0.5,
      })
      .mockResolvedValueOnce({
        id: 2,
        hallId: 10,
        clubId: 2,
        label: 'Table 2',
        minDeposit: 0,
        capacity: 2,
        zone: null,
        zoneName: null,
        arrivalWindow: null,
        mysteryEligible: false,
        tableNumber: 2,
        x: 0.6,
        y: 0.6,
      });

    render(<HallEditorScreen clubId={2} hallId={10} onBack={vi.fn()} />);

    const stage = await screen.findByTestId('hall-plan-stage');
    const image = await screen.findByTestId('hall-plan-image');
    mockStageRect(stage);
    fireEvent.load(image);

    fireEvent.click(stage, { clientX: 100, clientY: 50 });

    await waitFor(() => {
      expect(createHallTable).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(
      await screen.findByRole('button', { name: 'Снять выделение' }),
    );

    fireEvent.click(stage, { clientX: 120, clientY: 60 });

    await waitFor(() => {
      expect(createHallTable).toHaveBeenCalledTimes(2);
    });
  });

  it('disables interactions in preview mode', async () => {
    setupPlanMocks();
    setupTablesMock();

    render(<HallEditorScreen clubId={2} hallId={10} onBack={vi.fn()} />);

    const stage = await screen.findByTestId('hall-plan-stage');
    const image = await screen.findByTestId('hall-plan-image');
    mockStageRect(stage);
    fireEvent.load(image);

    fireEvent.click(
      await screen.findByRole('button', { name: 'Режим превью' }),
    );

    fireEvent.click(stage, { clientX: 100, clientY: 50 });

    const marker = await screen.findByTestId('table-marker-1');
    fireEvent.pointerDown(marker, { clientX: 60, clientY: 20, pointerId: 1 });
    fireEvent.pointerMove(marker, { clientX: 90, clientY: 40, pointerId: 1 });
    fireEvent.pointerUp(marker, { clientX: 90, clientY: 40, pointerId: 1 });

    await waitFor(() => {
      expect(createHallTable).not.toHaveBeenCalled();
      expect(updateHallTable).not.toHaveBeenCalled();
    });

    expect(screen.queryByRole('button', { name: 'Сохранить' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Удалить' })).toBeNull();
  });
});

describe('hall stage selection cancellation', () => {
  it('does not start dragging when the editor rejects changing selection', () => {
    const selected = vi.fn(() => false);
    const move = vi.fn();
    const { getByTestId } = render(
      <HallPlanStage
        planUrl="blob:synthetic"
        tables={[
          {
            id: 'opaque-table',
            hallId: 'opaque-hall',
            tableNumber: 1,
            label: 'Table',
            capacity: 2,
            x: 0.2,
            y: 0.2,
          },
        ]}
        selectedTableId={null}
        onSelectTable={selected}
        onMoveTable={move}
        onCreateTable={vi.fn()}
      />,
    );
    const stage = getByTestId('hall-plan-stage');
    mockStageRect(stage);
    const image = getByTestId('hall-plan-image');
    Object.defineProperty(image, 'naturalWidth', { value: 200 });
    Object.defineProperty(image, 'naturalHeight', { value: 100 });
    fireEvent.load(image);
    const marker = getByTestId('table-marker-opaque-table');
    fireEvent.pointerDown(marker, { clientX: 50, clientY: 20, pointerId: 1 });
    fireEvent.pointerMove(marker, { clientX: 120, clientY: 60, pointerId: 1 });
    fireEvent.pointerUp(marker, { clientX: 120, clientY: 60, pointerId: 1 });
    expect(selected).toHaveBeenCalledWith('opaque-table');
    expect(move).not.toHaveBeenCalled();
  });
});
