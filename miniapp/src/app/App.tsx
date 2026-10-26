import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import GuestShell from '../modules/guest/pages/GuestShell';
import EntryConsole from '../modules/entry/pages/EntryConsole';
import { useInitData } from '../modules/auth/hooks/useInitData';
import MyNights from '../modules/mynights/pages/MyNights';
import { setInitData } from '../shared/api/http';
import AdminShell from '../modules/admin/pages/AdminShell';
import PromoterShell from '../modules/promoter/pages/PromoterShell';
import SupportShell from '../modules/support/pages/SupportShell';
import GuestSupportShell from '../modules/guest/pages/GuestSupportShell';
import { resolvePlatformOwnerEntry } from '../modules/platform-owner/previewAccess';
import PlatformOwnerAccess from '../modules/platform-owner/PlatformOwnerAccess';

// Vite removes the unguarded preview entry from production builds.
const PlatformOwnerPreview = import.meta.env.DEV
  ? lazy(() => import('../modules/platform-owner/PlatformOwnerPreview'))
  : null;

export default function App() {
  const entry = resolvePlatformOwnerEntry({
    development: import.meta.env.DEV,
    hostname: window.location.hostname,
    pathname: window.location.pathname,
    search: window.location.search,
    baseUrl: import.meta.env.BASE_URL,
  });

  if (entry === 'preview' && PlatformOwnerPreview) {
    return (
      <Suspense
        fallback={<main aria-busy="true" aria-label="Загрузка предпросмотра" />}
      >
        <PlatformOwnerPreview />
      </Suspense>
    );
  }
  if (entry === 'unavailable') {
    return (
      <main
        className="min-h-screen bg-gray-50 p-6 flex items-center justify-center"
        lang="ru"
      >
        <div className="max-w-sm space-y-3">
          <h1 className="text-xl font-semibold">
            Доступ к управлению платформой не подключён
          </h1>
          <p>Необходимо серверное подтверждение доступа владельца платформы.</p>
        </div>
      </main>
    );
  }
  if (entry === 'protected') return <PlatformOwnerAccess />;
  return <ExistingApp />;
}

/**
 * Root application component deciding between Guest and Entry modes.
 */
function ExistingApp() {
  const { initData } = useInitData();
  const [installedInitData, setInstalledInitData] = useState<string | null>(
    null,
  );
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
