import { useEffect, useMemo } from 'react';
import GuestHome from '../modules/guest/pages/GuestHome';
import EntryConsole from '../modules/entry/pages/EntryConsole';
import { useInitData } from '../modules/auth/hooks/useInitData';

/**
 * Root application component deciding between Guest and Entry modes.
 */
export default function App() {
  const { initData } = useInitData();
  const mode = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get('mode') === 'entry' ? 'entry' : 'guest';
  }, []);

  useEffect(() => {
    // placeholder to avoid eslint unused warning
    void initData;
  }, [initData]);

  if (mode === 'entry') {
    return <EntryConsole />;
  }
  return <GuestHome />;
}
