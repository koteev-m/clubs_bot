import { vi } from 'vitest';

vi.mock('@twa-dev/sdk', () => ({
  default: {
    ready: vi.fn(),
    expand: vi.fn(),
    onEvent: vi.fn(),
    offEvent: vi.fn(),
    showScanQrPopup: vi.fn(),
    closeScanQrPopup: vi.fn(),
    openInvoice: vi.fn(),
    openTelegramLink: vi.fn(),
    openLink: vi.fn(),
    initData: '',
    platform: 'unknown',
    version: '0',
    colorScheme: 'light',
    MainButton: {
      setText: vi.fn(),
      show: vi.fn(),
      hide: vi.fn(),
      onClick: vi.fn(),
      offClick: vi.fn(),
    },
  },
}));
