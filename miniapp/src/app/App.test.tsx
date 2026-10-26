import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { setInitData } from '../shared/api/http';

const supportAuthState = vi.hoisted(() => ({
  installed: false,
  staffRenderedBeforeAuth: false,
  guestRenderedBeforeAuth: false,
}));

vi.mock('../modules/auth/hooks/useInitData', () => ({
  useInitData: () => ({
    initData: 'signed-init-data',
    platform: 'web',
    version: '7.0',
  }),
}));

vi.mock('../shared/api/http', () => ({
  setInitData: vi.fn(() => {
    supportAuthState.installed = true;
  }),
}));

vi.mock('../modules/guest/pages/GuestShell', () => ({
  default: () => <div>guest-shell</div>,
}));
vi.mock('../modules/entry/pages/EntryConsole', () => ({
  default: () => <div>entry-shell</div>,
}));
vi.mock('../modules/mynights/pages/MyNights', () => ({
  default: () => <div>my-nights-shell</div>,
}));
vi.mock('../modules/admin/pages/AdminShell', () => ({
  default: () => <div>admin-shell</div>,
}));
vi.mock('../modules/promoter/pages/PromoterShell', () => ({
  default: () => <div>promoter-shell</div>,
}));
vi.mock('../modules/platform-owner/PlatformOwnerAccess', () => ({
  default: () => <div>server-platform-access-check</div>,
}));
vi.mock('../modules/support/pages/SupportShell', () => ({
  default: () => {
    if (!supportAuthState.installed)
      supportAuthState.staffRenderedBeforeAuth = true;
    return <div>support-shell</div>;
  },
}));
vi.mock('../modules/guest/pages/GuestSupportShell', () => ({
  default: () => {
    if (!supportAuthState.installed)
      supportAuthState.guestRenderedBeforeAuth = true;
    return <div>guest-support-shell</div>;
  },
}));

describe('App bounded support modes', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });
  beforeEach(() => {
    vi.clearAllMocks();
    supportAuthState.installed = false;
    supportAuthState.staffRenderedBeforeAuth = false;
    supportAuthState.guestRenderedBeforeAuth = false;
  });

  it('installs Telegram authentication before mounting the bounded support shell', async () => {
    window.history.replaceState({}, '', '/?mode=support');
    render(<App />);

    await waitFor(() => expect(screen.getByText('support-shell')).toBeTruthy());
    expect(supportAuthState.staffRenderedBeforeAuth).toBe(false);
    expect(screen.queryByText('guest-support-shell')).toBeNull();
    expect(screen.queryByText('admin-shell')).toBeNull();
    expect(setInitData).toHaveBeenCalledWith('signed-init-data');
  });

  it('installs Telegram authentication before mounting only the bounded guest support shell', async () => {
    window.history.replaceState({}, '', '/?mode=guest-support');
    render(<App />);

    await waitFor(() =>
      expect(screen.getByText('guest-support-shell')).toBeTruthy(),
    );
    expect(supportAuthState.guestRenderedBeforeAuth).toBe(false);
    expect(screen.queryByText('support-shell')).toBeNull();
    expect(screen.queryByText('guest-shell')).toBeNull();
    expect(screen.queryByText('admin-shell')).toBeNull();
    expect(setInitData).toHaveBeenCalledWith('signed-init-data');
  });

  it('routes platform-owner presentation only through the server access gate', () => {
    window.history.replaceState({}, '', '/app?mode=platform-owner&role=OWNER');
    render(<App />);
    expect(screen.getByText('server-platform-access-check')).toBeTruthy();
    expect(screen.queryByText('admin-shell')).toBeNull();
    expect(
      screen.queryByRole('button', { name: 'Пригласить владельца' }),
    ).toBeNull();
  });

  it.each(['owner', 'platform'])(
    'rejects unconfirmed platform access through mode=%s',
    (mode) => {
      window.history.replaceState(
        {},
        '',
        `/?mode=${mode}&role=OWNER&scope=GLOBAL`,
      );
      render(<App />);
      expect(
        screen.getByRole('heading', {
          name: 'Доступ к управлению платформой не подключён',
        }),
      ).toBeTruthy();
      expect(screen.queryByText('admin-shell')).toBeNull();
      expect(
        screen.queryByRole('button', { name: 'Пригласить владельца' }),
      ).toBeNull();
      expect(setInitData).not.toHaveBeenCalled();
    },
  );

  it.each(['/__preview/platform', '/__preview/platform/invite'])(
    'denies the preview in production even with an OWNER claim at %s',
    (path) => {
      vi.stubEnv('DEV', false);
      window.history.replaceState({}, '', `${path}?role=OWNER&mode=owner`);
      render(<App />);
      expect(
        screen.getByRole('heading', {
          name: 'Доступ к управлению платформой не подключён',
        }),
      ).toBeTruthy();
      expect(
        screen.queryByRole('button', { name: 'Пригласить владельца' }),
      ).toBeNull();
      expect(setInitData).not.toHaveBeenCalled();
    },
  );

  it('keeps a client-supplied OWNER role in the ordinary guest surface', () => {
    window.history.replaceState(
      {},
      '',
      '/?role=OWNER&scope=GLOBAL&preview=platform',
    );
    render(<App />);
    expect(screen.getByText('guest-shell')).toBeTruthy();
    expect(screen.queryByText('admin-shell')).toBeNull();
    expect(
      screen.queryByRole('button', { name: 'Пригласить владельца' }),
    ).toBeNull();
  });
});
