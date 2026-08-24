import { useEffect, useMemo, useState } from 'react';
import GuestShell from '../modules/guest/pages/GuestShell';
import EntryConsole from '../modules/entry/pages/EntryConsole';
import { useInitData } from '../modules/auth/hooks/useInitData';
import MyNights from '../modules/mynights/pages/MyNights';
import { setInitData } from '../shared/api/http';
import AdminShell from '../modules/admin/pages/AdminShell';
import PromoterShell from '../modules/promoter/pages/PromoterShell';
import SupportShell from '../modules/support/pages/SupportShell';
import GuestSupportShell from '../modules/guest/pages/GuestSupportShell';

/**
 * Root application component deciding between Guest and Entry modes.
 */
export default function App() {
  const { initData } = useInitData();
  const [installedInitData, setInstalledInitData] = useState<string | null>(null);
  const mode = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    const value = params.get('mode');
    if (value === 'admin') return 'admin';
    if (value === 'support') return 'support';
    if (value === 'guest-support') return 'guest-support';
    if (value === 'promoter') return 'promoter';
    if (value === 'entry') return 'entry';
    if (value === 'my-nights') return 'my-nights';
    return 'guest';
  }, []);

  useEffect(() => {
    setInitData(initData);
    setInstalledInitData(initData);
  }, [initData]);

  if (mode === 'entry') {
    return <EntryConsole />;
  }
  if (mode === 'my-nights') {
    return <MyNights />;
  }
  if (mode === 'admin') {
    return <AdminShell />;
  }
  if (mode === 'support' || mode === 'guest-support') {
    if (installedInitData !== initData) {
      return <main aria-busy="true" aria-label="Loading support access" />;
    }
    return mode === 'support' ? <SupportShell /> : <GuestSupportShell />;
  }
  if (mode === 'promoter') {
    return <PromoterShell />;
  }
  return <GuestShell />;
}
