import { useEffect, useMemo } from 'react';
import GuestHome from '../modules/guest/pages/GuestHome';
import EntryConsole from '../modules/entry/pages/EntryConsole';
import { useInitData } from '../modules/auth/hooks/useInitData';
import MyNights from '../modules/mynights/pages/MyNights';
import { setInitData } from '../shared/api/http';

/**
 * Root application component deciding between Guest and Entry modes.
 */
export default function App() {
  const { initData } = useInitData();
  const mode = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    const value = params.get('mode');
    if (value === 'entry') return 'entry';
    if (value === 'my-nights') return 'my-nights';
    return 'guest';
  }, []);

  useEffect(() => {
    setInitData(initData);
  }, [initData]);

  if (mode === 'entry') {
    return <EntryConsole />;
  }
  if (mode === 'my-nights') {
    return <MyNights />;
  }
  return <GuestHome />;
}
