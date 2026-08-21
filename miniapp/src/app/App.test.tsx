import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { setInitData } from '../shared/api/http';

const supportAuthState = vi.hoisted(() => ({ installed: false, renderedBeforeAuth: false }));

vi.mock('../modules/auth/hooks/useInitData', () => ({
  useInitData: () => ({ initData: 'signed-init-data', platform: 'web', version: '7.0' }),
}));

vi.mock('../shared/api/http', () => ({
  setInitData: vi.fn(() => {
    supportAuthState.installed = true;
  }),
}));

vi.mock('../modules/guest/pages/GuestShell', () => ({ default: () => <div>guest-shell</div> }));
vi.mock('../modules/entry/pages/EntryConsole', () => ({ default: () => <div>entry-shell</div> }));
vi.mock('../modules/mynights/pages/MyNights', () => ({ default: () => <div>my-nights-shell</div> }));
vi.mock('../modules/admin/pages/AdminShell', () => ({ default: () => <div>admin-shell</div> }));
vi.mock('../modules/promoter/pages/PromoterShell', () => ({ default: () => <div>promoter-shell</div> }));
vi.mock('../modules/support/pages/SupportShell', () => ({
  default: () => {
    if (!supportAuthState.installed) supportAuthState.renderedBeforeAuth = true;
    return <div>support-shell</div>;
  },
}));

describe('App support mode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    supportAuthState.installed = false;
    supportAuthState.renderedBeforeAuth = false;
    window.history.replaceState({}, '', '/?mode=support');
  });

  it('installs Telegram authentication before mounting the bounded support shell', async () => {
    render(<App />);

    await waitFor(() => expect(screen.getByText('support-shell')).toBeTruthy());
    expect(supportAuthState.renderedBeforeAuth).toBe(false);
    expect(screen.queryByText('admin-shell')).toBeNull();
    expect(setInitData).toHaveBeenCalledWith('signed-init-data');
  });
});
